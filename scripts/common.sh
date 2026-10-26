# 各脚本共用：电视地址、Java/Android SDK 位置、状态目录。由其他脚本 source，不要单独运行。
ROOT="${ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
STATE="$ROOT/state"; mkdir -p "$STATE"
TV_HOST="${TV_HOST:-$(cat "$STATE/tvhost" 2>/dev/null)}"
if [ -z "$TV_HOST" ]; then
  echo "请先告诉脚本电视的 IP（电视：设置 → 关于 → 状态）："
  echo "  TV_HOST=192.168.x.x bash $0"
  echo "（只需第一次，之后会记在 state/tvhost 里）"; exit 1
fi
echo "$TV_HOST" > "$STATE/tvhost"
case "$TV_HOST" in *:*) T="$TV_HOST" ;; *) T="$TV_HOST:5555" ;; esac
PKG=com.home.tvvoice
fail(){ echo; echo "❌ $*"; exit 1; }
A(){ adb -s "$T" shell "$@"; }
tv_connect(){
  adb connect "$T" >/dev/null 2>&1; sleep 1
  [ "$(adb -s "$T" get-state 2>/dev/null)" = device ] || fail "连不上电视 $T：确认电视开着、开发者选项里 USB/ADB 调试已打开，电视弹出授权时选允许"
}
# Java 17 和 Android SDK：优先用环境变量，其次 Homebrew / Android Studio 默认位置
[ -n "${JAVA_HOME:-}" ] || for d in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk-arm64; do [ -d "$d" ] && export JAVA_HOME="$d" && break; done
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
[ -n "${ANDROID_HOME:-}" ] || for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools; do [ -d "$d" ] && export ANDROID_HOME="$d" && break; done
