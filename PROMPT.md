# 给 AI Agent 的操作说明

把这份文件交给 Claude Code、Codex 或其他能在电脑上执行命令的 AI 助手，让它在**你家的电视**上适配并安装 tv-voice-ai。电脑和电视要在同一个 Wi-Fi 下。

这套代码只在一台索尼 KD-75X7800F（国行，Android 8）上完整验证过（见 `docs/devices/`）。别的电视，话筒键是哪个键、能不能录到遥控器的声音、视频应用的跳转方式都可能不同，所以下面的步骤是**先探测、再修改、再安装**。

---

我想给我的 Android 电视装 tv-voice-ai：按遥控器话筒键，用云端语音识别 + 大模型听懂我的话，在当前应用里搜片、打开应用、切信号源。我的电视型号是：<型号>。请在真实设备上一步步做，每步都确认结果，涉及我的偏好时先问我。

## 安全规则（必须遵守）

- 只做本仓库需要的修改：安装一个应用、开启它的一个无障碍服务、授予录音和使用记录权限。**不要停用、卸载任何系统应用。**
- 改动前先备份（`scripts/install.sh` 会把原来的无障碍设置记到 `state/`），并确认 `scripts/restore.sh` 能完整还原。
- API 密钥只在我的终端里输入（`install.sh` 会提示，输入不显示），不要让我把密钥贴到对话里，也不要写进任何文件或提交到仓库。
- 结束时提醒我：在开发者选项里关掉 USB/ADB 调试并撤销授权。端口 5555 是明文的，开着等于家里任何设备都能连电视。

## 1. 环境与连接

- 电视：设置 → 关于 → 连按“版本号/内部版本号”7 次打开开发者模式 → 开发者选项里打开 USB 调试（有的叫“ADB 调试”）。在“状态”里查 IP。
- 电脑：需要 adb、JDK 17、Android SDK（platforms;android-34、build-tools;34.0.0）。macOS 用 Homebrew：`brew install android-platform-tools openjdk@17 android-commandlinetools`，再 `sdkmanager "platforms;android-34" "build-tools;34.0.0"`。国内网络下载 Gradle 慢，可以把 `gradle/wrapper/gradle-wrapper.properties` 的地址换成腾讯镜像 `https://mirrors.cloud.tencent.com/gradle/`（文件里的 sha256 校验不变）。
- 第一次运行脚本时带上 `TV_HOST=<电视IP>`，之后会记住。电视弹出“允许 USB 调试”时提醒我选允许。
- 确认 `getprop ro.build.version.sdk` ≥ 26（Android 8.0）。低于这个版本就停下告诉我装不了。

## 2. 探测话筒键和录音（只读）

- 运行 `bash tools/probe-voice.sh`：它会让我按一下话筒键，记录按键事件、哪个应用响应、遥控器的输入设备、音频输入设备。
- 判断话筒键的 keycode。索尼这台是 `KEYCODE_SEARCH`。如果是别的键（比如 `KEYCODE_VOICE_ASSIST`、`KEYCODE_ASSIST` 或厂商自定义键），修改 `app/src/main/java/com/home/tvvoice/MicKeyService.java` 里的判断。注意：通过 adb 注入的按键不会经过无障碍服务，必须让我按真遥控器验证。
- 先别假设“改默认助手设置”有用：索尼会绕过 `settings secure assistant` 直接打开自己的搜索，所以本项目用无障碍服务截按键。
- 能不能录到遥控器的声音，要装上之后看日志里的音量（`rec ms= … maxRms=`）。如果一直“没听到声音”，换 `AudioSource.MIC` 或 `DEFAULT` 试，并把结果告诉我。

## 3. 适配视频应用的搜索入口

- 运行 `bash tools/probe-search.sh`：用 `docs/deeplinks.md` 里已知的跳转方式逐个打开我电视上装的视频应用，记录停在哪个界面并截图。
- 我的电视上如果有表里没有的应用，按 `docs/deeplinks.md` 末尾的方法找入口：先看 `dumpsys package` 登记的 action/scheme，再从安装包 dex 里搜字符串，然后逐个试、截图确认。
- 把能用的入口加进 `AssistActivity.java` 的 `searchIntent()`，应用的叫法加进 `APPS`。

## 4. 安装与验证

- `bash scripts/install.sh`（第一次会让我粘贴硅基流动的 API 密钥。语音识别用的 SenseVoiceSmall 免费；聊天和搜索纠错用 DeepSeek，约 0.3 分钱一次）。
- 让我用真遥控器依次试：“打开 B站”、在 B站里“搜索小猪佩奇”、“我的收藏”、“切到 HDMI2”、“讲个笑话”。每次面板底部会显示每一步耗时。
- 有问题就运行 `bash scripts/debug.sh`，读 `state/debug-log.txt` 排查。
- 撤销：`bash scripts/restore.sh`。

## 5. 收尾

- 关掉 ADB 调试、撤销授权、重启电视。
- 如果愿意，把这台电视的探测结果整理成 `docs/devices/<型号>.md`（参照已有的索尼那份，去掉 IP、序列号、账号信息），新试出来的跳转方式补进 `docs/deeplinks.md`，提 PR 帮到后来的人。
