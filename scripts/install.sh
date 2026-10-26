#!/bin/bash
# 编译并安装 AI 语音，把遥控器话筒键交给它。第一次运行会让你粘贴硅基流动 API 密钥（输入不显示、不进命令历史）。
# 用法：TV_HOST=192.168.x.x bash scripts/install.sh      重新设置密钥：加 --key
# 撤销：bash scripts/restore.sh
set -uo pipefail
. "$(dirname "$0")/common.sh"
cd "$ROOT"
SVC=$PKG/$PKG.MicKeyService
[ -n "${ANDROID_HOME:-}" ] || fail "没找到 Android SDK，先按 README 安装 openjdk@17 和 android-commandlinetools"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
echo "== 编译 =="; ./gradlew assembleDebug -q --console=plain || fail "编译失败"
tv_connect
SDK=$(A getprop ro.build.version.sdk | tr -dc 0-9); [ "${SDK:-0}" -ge 26 ] || fail "电视系统低于 Android 8.0（SDK $SDK），装不了"
echo "== 安装 =="; adb -s "$T" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null || fail "安装失败"
A pm grant $PKG android.permission.RECORD_AUDIO
A appops set $PKG GET_USAGE_STATS allow   # 只用来知道你当前在用哪个应用，好在那个应用里搜

if [ "${1:-}" = "--key" ] || ! A run-as $PKG cat files/config.properties 2>/dev/null | grep -q '^key='; then
  echo; echo "请粘贴硅基流动（siliconflow.cn）的 API 密钥，sk- 开头，粘贴后不会显示，直接回车："
  read -r -s KEY; echo
  KEY=$(printf '%s' "$KEY" | tr -d '[:space:]')
  [ -n "$KEY" ] || fail "没有收到密钥"
  echo "收到密钥：${KEY:0:5}…（共 ${#KEY} 个字符）"
  H=$(mktemp); chmod 600 "$H"; printf 'Authorization: Bearer %s\n' "$KEY" > "$H"
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -m 15 -H @"$H" https://api.siliconflow.cn/v1/models); rm -f "$H"
  [ "$CODE" = 200 ] || fail "密钥验证没通过（HTTP ${CODE}）"
  CONF=$(printf 'key=%s\nasr_model=FunAudioLLM/SenseVoiceSmall\nchat_model=deepseek-ai/DeepSeek-V4.1-Flash\nfallback_model=deepseek-ai/DeepSeek-V3.2\ndaily_limit=100\ntts=off\nsearch_fix=on\n' "$KEY")
  if printf '%s' "$CONF" | adb -s "$T" shell "run-as $PKG sh -c 'mkdir -p files && cat > files/config.properties && chmod 600 files/config.properties'" 2>/dev/null \
     && A run-as $PKG cat files/config.properties 2>/dev/null | grep -q '^key='; then
    echo "密钥已存进应用私有目录 ✅"
  else
    TMPF=$(mktemp); printf '%s' "$CONF" > "$TMPF"; A mkdir -p /sdcard/Android/data/$PKG/files
    adb -s "$T" push "$TMPF" /sdcard/Android/data/$PKG/files/config.properties >/dev/null; rm -f "$TMPF"
    echo "密钥已送到电视，应用第一次打开时会搬进私有目录并删除临时文件"
  fi
  unset KEY CONF
fi

# 自定义纠错表（可选）：复制 aliases.example.txt 为 aliases.txt 再改
[ -f aliases.txt ] && adb -s "$T" shell "run-as $PKG sh -c 'cat > files/aliases.txt'" < aliases.txt && echo "纠错表已更新"
A "run-as $PKG sh -c \"rm -f shared_prefs/usage.xml\"" 2>/dev/null   # 清掉“今天余额不足”的标记

# 话筒键：启用只截搜索键的无障碍服务（先记下原设置，restore.sh 会还原）
[ -f "$STATE/accessibility-before.txt" ] || { A settings get secure enabled_accessibility_services > "$STATE/accessibility-before.txt"; A settings get secure accessibility_enabled >> "$STATE/accessibility-before.txt"; }
CUR=$(A settings get secure enabled_accessibility_services | tr -d '\r')
OTHERS=$(printf '%s' "$CUR" | tr ':' '\n' | grep -v -x -e "$SVC" -e null -e '' | paste -sd: -)
if [ -n "$OTHERS" ]; then A settings put secure enabled_accessibility_services "$OTHERS"; else A settings delete secure enabled_accessibility_services >/dev/null; fi
sleep 1   # 先关再开，让服务用上新版本
A settings put secure enabled_accessibility_services "${OTHERS:+$OTHERS:}$SVC"
A settings put secure accessibility_enabled 1
echo; echo "✅ 装好了。按遥控器话筒键试试：“打开B站”“搜索小猪佩奇”“我的收藏”“切到HDMI2”"
echo "撤销：bash scripts/restore.sh"
