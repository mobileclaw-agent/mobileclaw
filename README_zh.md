# MobileClaw

[English](README.md)

一个运行在 Android 手机上的 AI 代理。它可以连接任何兼容 OpenAI 或 Anthropic 的 API，并拥有真正的工具能力：读取设备状态、运行 Shell 命令、通过 Shizuku 获取提权、搜索网页和调用 API、发送通知或开口说话、保存自己的文件，甚至定时唤醒自己。

它还会自己编写 `IDENTITY.md` 和 `MEMORY.md`——包括它自己的名字。`MobileClaw` 只是默认名称。

## 构建

```bash
export JAVA_HOME=/path/to/jdk21          # JDK 17–21
export ANDROID_HOME=/path/to/android-sdk # 需要 platform-35 + build-tools 35
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

或者在 Android Studio 中打开项目，点击运行。

- `minSdk` 26（Android 8.0），`targetSdk`/`compileSdk` 35。
- 如果 Studio 没有自动生成，需创建 `local.properties` 并设置 `sdk.dir=/path/to/android-sdk`。

## 设置

打开**设置**，填写三个字段：

| 提供商 | Base URL | 调用端点 |
|---|---|---|
| OpenAI 兼容 | `https://api.openai.com/v1` | `POST {base}/chat/completions` |
| Anthropic | `https://api.anthropic.com/v1` | `POST {base}/messages` |

OpenAI 路径兼容 OpenRouter、Groq、Together、Ollama、LM Studio 和 vLLM——只需将 Base URL 指向对应服务并设置模型名称即可。然后授予通知权限，如果需要后台行为，打开**保持代理运行**。

## 工具

**设备** — `get_android_version`、`get_build_version`、`get_device_name`、`get_device_model`、`get_system_stats`、`get_time`、`list_installed_apps`

**应用操作** — `open_url`、`launch_app`、`open_app_settings`

**剪贴板** — `set_clipboard`、`get_clipboard`

**网络** — `get_connection_method`（wlan / mobile_net / ethernet / vpn，是否计费，是否验证）、`get_local_ips`、`is_hotspot_running`

**电源** — `get_battery_info`

**屏幕与音频** — `get_screen_state`、`get_volumes`、`set_media_volume`、`vibrate`

**语音** — `speak`（文字转语音，可指定语言、音调、语速）

**文件** — `list_files`、`read_file`、`write_file`、`delete_file` — 代理自己的持久工作区，严格限制在应用私有存储内

**Shell** — `run_cmd`（非特权，在应用沙箱内运行）、`get_shizuku_status`、`connect_shizuku`、`run_shizuku_cmd`（提权命令）

**网页** — `web_search`（DuckDuckGo HTML 前端，lite 前端作为备选）、`web_fetch`、`http_request`（面向 API 等机器可读端点的原始 HTTP 请求）

**通知** — `check_notification_permission`、`request_notification_permission`、`send_notification`

**自身** — `read_identity`、`write_identity`、`read_memory`、`append_memory`、`write_memory`、`set_agent_name`

**定时任务** — `create_cron`、`list_crons`、`delete_cron`、`set_cron_enabled`

### Shizuku

Shizuku 让普通应用无需特权即可获得 ADB 级别（或 root）的 Shell 权限。它依赖用户通过无线调试、ADB 或 root 启动的一个服务。

代理会收到以下明确的返回令牌：

- `connect_shizuku` → `shizuku_conned_success`：特权命令可用
- `connect_shizuku` → `shizuku_notreachable`：服务未运行，或用户拒绝
- `run_shizuku_cmd` → `shizuku not connected`：未连接时调用，不会执行任何命令

### 自我调度

`create_cron` 接受一个**分钟为单位的间隔**和代理为未来自己编写的提示词。每隔指定间隔，该提示词作为 `[EVENT]` 投递，代理获得完整的对话轮次。

`interval_minutes = 0` 是允许的，但大约每 10 秒触发一次；工具会返回明确警告，5 分钟以下的间隔同理。定时任务仅在前台服务运行时触发。

## 电池告警

服务每 5 分钟检查一次，同时会立即响应电池电量变化的广播：

| 电量 | 级别 | 行为 |
|---|---|---|
| 15% | 提示 | 通知代理，由代理自行决定如何处理 |
| 5% | 警告 | 通知代理 |
| 2%、1%、0% | 严重 | 直接触发高优先级系统通知，同时告知代理通知已显示 |

严重告警不依赖 API 请求成功——1% 的电量警告不应因请求超时而丢失。

每个阈值在每次放电周期内仅触发一次，仅在电量回升超过该阈值 3 个百分点或手机开始充电后才重新激活。充电中的手机不会触发告警。

## IDENTITY.md 和 MEMORY.md

两个文件都存储在应用私有目录（`/data/data/at.creepervm1000.mobileclaw/files/`）中，并在每次系统提示中注入，因此编辑后下一个对话轮次立即生效。设置页面有**导出**按钮，可将两个文件复制到下载目录供查看。

系统提示会指示代理主动维护这些文件：在学到持久信息时追加到记忆中，在自我认知变化时重写身份信息，并且它的名字由它自己选择。清除对话不会影响这两个文件——这正是它们存在的意义。

## 注意事项和限制

- 不持有永久唤醒锁。这是刻意的：一个监控电池的应用如果为了监控而耗尽电池，那就自相矛盾了。因此 Doze 模式可以延迟定时器；如果需要严格的 5 分钟精度，请在电池优化中将应用设为豁免。
- 前台服务类型为 `specialUse` 而非 `dataSync`。在 Android 15 上，`dataSync` 服务每天会被强制停止 6 小时，这将静默杀死代理。Play Store 分发需要向审核团队说明 `specialUse` 的合理性；侧载安装则不需要。
- `is_hotspot_running` 是尽力而为的。`isWifiApEnabled` 是隐藏 API；当平台阻止调用时，工具会回退到探测网络接口并报告 `confident: false`。
- 网页搜索通过抓取 DuckDuckGo 的 HTML 前端实现，可能会被限速或要求验证码；工具会如实报告而非返回空结果。
- 请求不使用流式传输——回复在完成后一次性显示。
- `QUERY_ALL_PACKAGES` 权限用于 `list_installed_apps`。侧载安装没有问题；Play Store 分发则需要说明合理性或移除该功能。
- 允许明文 HTTP 通信，因此 `web_fetch` 可以访问 `127.0.0.1` 上的本地模型服务、局域网设备或仅支持 HTTP 的页面。从 `targetSdk` 28 开始 Android 默认阻止明文通信，这会导致相同的 URL 在浏览器中正常加载但在应用中网络请求失败。

## 许可证

MIT —— 详见 [LICENSE](LICENSE)。
