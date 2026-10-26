#!/bin/bash
# 试探电视上各视频应用的“直接搜索”入口：逐个用已知的跳转方式打开，记录停在哪个界面并截图到 state/probe-search/。
# 电视会自己打开几个应用（约 2 分钟），期间别按遥控器。只打开应用，不改设置。
# 用法：bash tools/probe-search.sh [搜索词，默认“庆余年”]
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; . "$ROOT/scripts/common.sh"
tv_connect
Q="${1:-庆余年}"; QE=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$Q")
OUT="$STATE/probe-search"; mkdir -p "$OUT"; : > "$OUT/log.txt"
has(){ A pm path "$1" 2>/dev/null | grep -q package:; }
try(){ n=$1; pkg=$2; shift 2; has "$pkg" || { echo "== $n 跳过（没装 $pkg）" >> "$OUT/log.txt"; return; }
  echo "== $n: $*" >> "$OUT/log.txt"; A "$@" >> "$OUT/log.txt" 2>&1; sleep 10
  A dumpsys activity activities | grep -m1 -E "mResumedActivity|topResumedActivity" >> "$OUT/log.txt"
  A screencap -p /sdcard/probe.png; adb -s "$T" pull /sdcard/probe.png "$OUT/$n.png" >/dev/null 2>&1
  A input keyevent KEYCODE_HOME; sleep 3; }
try ktcp-search   com.ktcp.video          am start -a com.tencent.qqlivetv.open -d "tenvideo2://?action=9\&search_key=$Q" com.ktcp.video
try gitv-result   com.gitvdemo.video      am start -a com.gitvdemo.video.action.ACTION_SEARCHRESULT -d "gala://page?keyword=$QE" -p com.gitvdemo.video --es keyword "$Q"
try cibn-search   com.cibn.tv             am start -a android.intent.action.VIEW -d "cibntv_yingshi://search?keyword=$QE" com.cibn.tv
try mgtv-search   com.hunantv.market      am start -a android.intent.action.VIEW -d "mgtvapp://search/ott/searchInput?searchKey=$QE" com.hunantv.market
try yst-search    com.xiaodianshi.tv.yst  am start -a android.intent.action.VIEW -d "yst://com.xiaodianshi.tv.yst?type=11\&keyword=$QE\&bundle_keyword=$QE" com.xiaodianshi.tv.yst
try yst-favorite  com.xiaodianshi.tv.yst  am start -a android.intent.action.VIEW -d "yst://com.xiaodianshi.tv.yst?type=12" com.xiaodianshi.tv.yst
A rm -f /sdcard/probe.png
# 顺便导出各应用登记的搜索相关入口，给 Agent 找新的跳转方式用
for p in com.ktcp.video com.gitvdemo.video com.hunantv.market com.cibn.tv com.xiaodianshi.tv.yst; do
  has $p && A dumpsys package $p | sed -n '/Activity Resolver Table/,/Receiver Resolver Table/p' > "$OUT/filters-$p.txt"
done
echo "✅ 完成：state/probe-search/（log.txt 记录每次停在哪个界面；有些应用禁止截屏，截图会是空的）"
