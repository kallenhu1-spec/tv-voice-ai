#!/bin/bash
# 评测：大模型认不认识 B站 UP主（场景推荐 → 用 B站公开搜索核对真实性和是否拍过该城市；编造名字测幻觉）。
# 从电视上的 AI 语音读取你的硅基流动密钥；花费约一两毛钱，耗时约 10 分钟。结果在 state/。
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; . "$ROOT/scripts/common.sh"
tv_connect
export SF_KEY=$(A run-as $PKG cat files/config.properties | sed -n 's/^key=//p' | tr -d '\r')
[ -n "$SF_KEY" ] || fail "读不到密钥，先运行 scripts/install.sh"
cd "$STATE" && python3 -I "$ROOT/tools/kbtest/kbtest.py"
