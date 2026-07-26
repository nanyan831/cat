# CatLifePet 本地真实 AI 测试

这个流程用于在正式部署前，用电脑本地 server + 真机 Android App 测试真实 OpenAI 对话。

## 原则

- 不要把 `OPENAI_API_KEY` 写进聊天、Git、Android Gradle 配置、APK 或日志。
- OpenAI Key 只放在本机 PowerShell 环境变量或服务器环境变量里。
- Android App 只连接 CatLifePet Server，不直接访问 OpenAI。
- 未设置 `OPENAI_API_KEY` 时，本地 server 自动使用 fake AI，方便离线测试。

## 1. 在当前 PowerShell 会话设置 Key

不要把真实 key 发给任何人。直接在本机 PowerShell 执行：

```powershell
$env:OPENAI_API_KEY = "你的 OpenAI API Key"
$env:OPENAI_MODEL = "gpt-5.6-luna"
$env:CATLIFEPET_AI_TIMEOUT_SECONDS = "30"
$env:CATLIFEPET_AI_MAX_OUTPUT_TOKENS = "500"
```

如果需要临时验证 Key 是否能调用真实 OpenAI provider：

```powershell
$env:CATLIFEPET_RUN_OPENAI_SMOKE = "true"
.\gradlew.bat :server:test --tests "*OpenAiResponsesProviderTest.optional real provider smoke test"
```

## 2. 启动本地设备测试 server

```powershell
.\gradlew.bat :server:runDeviceAuthServer
```

启动后应看到：

```text
CatLifePet device auth server ready at http://127.0.0.1:8080
Development mailbox: ...
AI provider: openai, model: ...
```

如果没有设置 `OPENAI_API_KEY`，会显示：

```text
AI provider: fake
```

## 3. 让手机连接电脑 server

USB 连接手机后执行：

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb reverse tcp:8080 tcp:8080
```

Debug 包默认 API 地址是 `http://127.0.0.1:8080/`，通过 `adb reverse` 后手机会连到电脑 server。

## 4. 构建并安装 Debug 包

```powershell
.\gradlew.bat :app:assembleDebug
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb shell monkey -p com.example.catlifepet -c android.intent.category.LAUNCHER 1
```

## 5. 登录测试账号

App 里进入“账号与云同步”，输入任意测试邮箱，例如：

```text
test@catlifepet.local
```

本地设备测试 server 的验证码固定为：

```text
424242
```

登录成功后进入“和小猫聊聊”，发送一条消息，例如：

```text
今天有点累，你陪我聊一会儿吧
```

预期结果：

- 消息发送成功。
- 小猫回复通过 SSE 流式显示。
- 关闭再打开聊天页后，历史消息仍在。
- `OPENAI_API_KEY` 不出现在 Android 日志或 APK 中。

## 6. 常见问题

- 如果登录失败：确认 `:server:runDeviceAuthServer` 正在运行，并且 `adb reverse tcp:8080 tcp:8080` 已执行。
- 如果 AI 回复是固定假文案：说明当前 server 没读到 `OPENAI_API_KEY`。
- 如果 OpenAI 报模型不存在：把 `OPENAI_MODEL` 改成你账户可用的模型名后重启 server。
- 如果手机无法安装 Debug 包：确认开发者选项里的 USB 安装已经允许。
