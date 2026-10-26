# tv-voice-ai

**按一下遥控器话筒键，说一句话，就在电视的 B站、极光、芒果里直接搜到。**

在电视上用 B站，最痛苦的是搜索：拿遥控器一个拼音字母一个字母地按。家里的索尼是 2018 年的老款，自带的语音搜索识别差、也不认识 B站 UP主。于是我和 AI（Claude）一起，花了一个周末给它做了这个：

```
话筒键 ──► 录音（说完自动停）──► 云端语音识别（SenseVoice，免费）
        ──► 本地规则：打开应用 / 搜片 / B站收藏历史 / 切 HDMI（不花钱，瞬间）
        └─► 规则没听懂的 ──► DeepSeek 理解意图、纠正 UP主 名字（约 0.3 分钱/次）
        ──► 用各应用的内部跳转，直接打开搜索结果
```

我不写 Android 代码。这个仓库里的代码、试探脚本和文档都是在和 AI 的来回协作中完成的：我负责在电脑上运行命令、拿遥控器测试、反馈哪里不好用。

## 能做什么

| 说 | 效果 | 状态 |
|---|---|---|
| “打开B站”“打开芒果TV” | 打开应用 | ✅ |
| 在 B站里说“搜索小猪佩奇第12季英文版” | 在当前应用里直接搜 | ✅ B站、极光、奇异果、芒果、酷喵 |
| “我的收藏”“历史记录”“排行榜” | 直达 B站对应页面 | ✅ |
| “切到 HDMI2” | 切信号源 | ✅ |
| “讲个笑话”“这部剧讲什么” | 屏幕上显示 AI 回答 | ✅（朗读暂不可用） |
| “搜索 meat food” → meetfood | AI 纠正听错的 UP主 名字 | 🟡 刚加入 |

**诚实说明**

- **只在一台电视上完整验证过**：索尼 KD-75X7800F（国行，Android 8），见 [docs/devices](docs/devices/)。其他 Android 电视（8.0 以上）大概率能用，但话筒键、录音方式、应用入口都可能要调整，所以提供了 [PROMPT.md](PROMPT.md)，让你的 AI 助手帮你适配。
- **非官方**：各视频应用的搜索入口是试出来的（见 [docs/deeplinks.md](docs/deeplinks.md)），不是官方接口，应用更新后可能失效。与索尼、B站、腾讯等公司无关。
- 识别偶尔会认错（比如“打开B站”被听成“开闭账”），大部分时候 2–4 秒出结果，偶尔服务器慢会更久。

## 花多少钱

需要一个[硅基流动](https://cloud.siliconflow.cn)的 API 密钥。
- 语音识别（SenseVoiceSmall）：免费
- 打开应用、搜片、切信号源：在电视本地处理，不花钱
- 聊天问答、B站 搜索纠错：DeepSeek，约 0.3 分钱/次，默认每天上限 100 次
- 注意：硅基流动账户余额为负时会拦下所有调用（包括免费模型）。应用会自动退回电视自带的识别，搜片、开应用照常可用。

## 快速开始

最省事的方式：把 [PROMPT.md](PROMPT.md) 交给 Claude Code / Codex 这类能执行命令的 AI 助手，让它在你的电视上一步步做。

手动安装（macOS）：

```bash
brew install android-platform-tools openjdk@17 android-commandlinetools
sdkmanager "platforms;android-34" "build-tools;34.0.0"
# 电视：开发者选项 → 打开 USB/ADB 调试；设置 → 关于 → 状态 里查 IP
TV_HOST=192.168.x.x bash scripts/install.sh     # 第一次会让你粘贴 API 密钥
```

| 脚本 | 作用 |
|---|---|
| `scripts/install.sh` | 编译、安装、接管话筒键（`--key` 重新设置密钥） |
| `scripts/restore.sh` | 完整还原，话筒键交还电视自带语音 |
| `scripts/debug.sh` | 取回日志、最后一次录音、用量记录 |
| `tools/probe-voice.sh` | 探测话筒键和音频设备（只读） |
| `tools/probe-search.sh` | 试探各视频应用的搜索入口并截图 |
| `tools/kbtest/` | 评测：大模型认不认识 B站 UP主 |
| `aliases.example.txt` | 自定义纠错表（UP主 名字、英文名） |

## 安全与隐私

- 只装一个应用、开它的一个无障碍服务（只截话筒键，不读屏幕内容）、授予录音和使用记录权限，不停用任何系统应用。
- 只在你按下话筒键后录音几秒；录音只发给硅基流动做识别。
- API 密钥只在你的终端里输入，存在应用私有目录，不进仓库。
- 需要打开 ADB 调试，**用完请关掉并撤销授权**：5555 端口是明文的。

## 致谢

- 桌面清理用的是 [cleanyourtv](https://github.com/Gathin23/cleanyourtv)，PROMPT.md 的形式也是学它的。
- 语音识别：[SenseVoice](https://github.com/FunAudioLLM/SenseVoice)；大模型：DeepSeek；接口：硅基流动。

MIT License
