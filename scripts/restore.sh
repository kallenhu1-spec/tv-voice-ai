#!/bin/bash
# 还原：关掉话筒键服务、恢复原来的无障碍设置、卸载 AI 语音。话筒键回到电视自带的语音搜索。
. "$(dirname "$0")/common.sh"
tv_connect
F="$STATE/accessibility-before.txt"
if [ -f "$F" ]; then
  S=$(sed -n 1p "$F" | tr -d '\r'); E=$(sed -n 2p "$F" | tr -d '\r')
  if [ "$S" = null ] || [ -z "$S" ]; then A settings delete secure enabled_accessibility_services; else A settings put secure enabled_accessibility_services "$S"; fi
  if [ "$E" = null ] || [ -z "$E" ]; then A settings delete secure accessibility_enabled; else A settings put secure accessibility_enabled "$E"; fi
else
  CUR=$(A settings get secure enabled_accessibility_services | tr -d '\r')
  A settings put secure enabled_accessibility_services "$(printf '%s' "$CUR" | tr ':' '\n' | grep -v "$PKG" | paste -sd: -)"
fi
adb -s "$T" uninstall $PKG
echo "已还原：话筒键交还电视自带语音搜索，AI 语音已卸载。"
