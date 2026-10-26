# Sony KD-75X7800F（国行，2018）

| 项目 | 值 |
|---|---|
| 固件 | `BRAVIA_ATV3_4K_CN-user 8.0.0 OPR2.170623.027.S25 665201 release-keys` |
| Android / API | 8.0 / 26 |
| 安全补丁 | 2019-05-01 |
| 内存 | 约 1.6 GB（空闲常只有一百多 MB） |
| ABI | armeabi-v7a |
| 原桌面 | `com.google.android.tvlauncher`（HOME 优先级 2，必须停用才能换桌面）；后备 `com.android.tv.settings/.system.FallbackHome` |
| 遥控器 | 蓝牙语音遥控器，输入设备名 `SONY TV RC MIC 001` |
| 话筒键 | `KEYCODE_SEARCH`（84），scancode 217 |
| 原语音搜索 | `com.sony.dtv.searchapp`（6.2.2，2019-06 后未更新），背后是百度 DuerOS |
| 直播/信号源 | `com.sony.dtv.tvx`；HDMI 通过 TvInputManager passthrough 切换 |

## 关键发现

- **话筒键被写死**：把 `settings secure assistant` 改成别的应用没用，按键仍直接打开索尼搜索。只能用无障碍服务（`flagRequestFilterKeyEvents`）在系统层截住 `KEYCODE_SEARCH`。
- **第三方应用能录到遥控器的声音**：用 `MediaRecorder.AudioSource.VOICE_RECOGNITION`，和索尼自带搜索用的是同一个录音通道（日志里 `audio.source:6`）。音量偏小，录完放大 4–8 倍再送去识别效果更好。
- **按话筒键的同时电视在播放视频**，声音会被收进话筒，导致判断不出“说完了”。录音时申请音频焦点让视频暂停即可。
- 出厂预装了“悟空遥控助手”（`com.wukongtv.wkhelper`），ADB 调试在我们动手前就已经是开着的。建议用完关掉 ADB 并撤销授权。
- 换桌面用的是 [cleanyourtv](https://github.com/Gathin23/cleanyourtv) 的桌面，只停用了原桌面，没有停用其他系统组件。

## 验证情况（2026-10）

| 功能 | 状态 |
|---|---|
| 话筒键接管、录音、识别 | ✅ 日常在用 |
| 说“打开 XX”打开应用 | ✅ |
| 在 B站/极光/奇异果/芒果/酷喵里直接搜 | ✅（见 docs/deeplinks.md） |
| B站“我的收藏/历史记录”等页面 | ✅ 入口已实测，语音触发刚加入 |
| 切 HDMI | ✅ “切到 HDMI2” 实测可用 |
| 聊天问答（文字显示） | ✅ |
| 朗读回答（TTS） | ❌ 硅基流动 CosyVoice 接口一直无响应，默认关闭 |
| 搜索词 AI 纠错 | 🟡 刚加入，还没有评测数据 |
