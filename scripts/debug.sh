#!/bin/bash
# 取回最近的日志、最后一次录音和用量记录到 state/（只读，不改电视）。日志里有每一步耗时，方便排查“慢在哪”。
. "$(dirname "$0")/common.sh"
tv_connect
adb -s "$T" logcat -d -s TVVOICE:I | grep -v "^---" | tail -300 > "$STATE/debug-log.txt"
adb -s "$T" pull /sdcard/Android/data/$PKG/files/last.wav "$STATE/debug-last.wav" >/dev/null 2>&1
adb -s "$T" pull /sdcard/Android/data/$PKG/files/usage.csv "$STATE/usage.csv" >/dev/null 2>&1
echo "✅ 已取回到 state/：debug-log.txt、debug-last.wav、usage.csv"
