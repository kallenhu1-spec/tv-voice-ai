#!/bin/bash
# 只读检查：遥控器话筒键发出什么、交给谁处理、话筒声音从哪进来。不修改电视任何设置。
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; . "$ROOT/scripts/common.sh"
OUT="$STATE/voice-probe.txt"
adb connect "$T" >/dev/null 2>&1; sleep 1
[ "$(adb -s "$T" get-state 2>/dev/null)" = device ] || { echo "❌ 连不上电视，确认电视开着、ADB 调试开着"; exit 1; }
A(){ adb -s "$T" shell "$@"; }
{
echo "### 1. 话筒键的按键事件"
} > "$OUT"
echo
echo "👉 接下来 8 秒内：按一下遥控器的【话筒键】，说一句“今天天气怎么样”，然后松开。"
read -r -p "准备好了按回车开始： " _
A timeout 8 getevent -lt >> "$OUT" 2>&1
sleep 1
{ echo; echo "### 2. 按完话筒键后屏幕上是哪个界面"
  A dumpsys activity activities | grep -E "mResumedActivity|mFocusedActivity"
  A dumpsys window | grep -E "mCurrentFocus|mFocusedApp"
} >> "$OUT"
echo "（已记录。现在可以按遥控器【返回键】关掉语音搜索界面）"
sleep 3
{
echo; echo "### 3. 哪些应用能接管语音/助手入口"
for a in android.intent.action.ASSIST android.intent.action.SEARCH_LONG_PRESS android.intent.action.VOICE_COMMAND android.speech.action.WEB_SEARCH android.speech.action.RECOGNIZE_SPEECH android.intent.action.VOICE_ASSIST android.search.action.GLOBAL_SEARCH; do
  echo "-- $a"; A cmd package query-activities --brief -a $a 2>&1 | grep -v "^ *priority" | head -8; done
echo; echo "-- 语音识别服务"; A cmd package query-services --brief -a android.speech.RecognitionService 2>&1 | head -8
echo; echo "### 4. 索尼搜索应用的入口"
A dumpsys package com.sony.dtv.searchapp | sed -n '/Activity Resolver Table/,/Receiver Resolver Table/p' | head -80
echo; echo "### 5. 话筒/音频输入设备"
A dumpsys media.audio_policy | grep -i -E "input|mic|remote|bus|0x8" | head -40
echo; echo "### 6. 遥控器连接方式"
A dumpsys input | grep -i -E "^ *[0-9]+: |Name:|Sources:|Classes:" | head -40
A dumpsys bluetooth_manager | grep -i -E "bonded|name:|connected" | head -15
echo; echo "### 7. 系统信息"
A getprop ro.build.version.sdk; A getprop ro.product.cpu.abilist
} >> "$OUT" 2>&1
echo; echo "✅ 检查完成，结果在 state/voice-probe.txt"
