# CatLifePet v1.0.0 发布清单

## 版本

- applicationId: `com.example.catlifepet`
- versionCode: `10000`
- versionName: `1.0.0`
- minSdk: `23`
- targetSdk: `35`

## 权限说明

- `SYSTEM_ALERT_WINDOW`：显示屏幕悬浮小猫，用户可在系统设置中关闭。
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`：保持桌宠服务稳定运行。
- `POST_NOTIFICATIONS`：显示前台服务通知和生活提醒。
- `RECEIVE_BOOT_COMPLETED`：设备重启后恢复本地提醒计划。
- `INTERNET`：登录、AI 对话、云端聊天记录和陪伴记忆。

## 应用商店简介草案

CatLifePet 是一只会待在屏幕边缘陪伴你的生活小猫。它可以拖动、贴边、点击互动，也会在合适的时候提醒你喝水、吃饭、休息和睡觉。未登录时，本地桌宠和生活提醒仍可使用；登录后可以和小猫 AI 聊天，并同步云端聊天记录与陪伴记忆。

## 截图建议

- 陪伴首页。
- 生活提醒中心。
- 桌宠设置。
- 小猫悬浮在其他 App 上方。
- AI 聊天页。
- 隐私与数据页。

## 签名与密钥

- 正式发布密钥必须保存在仓库外。
- 本项目读取 `release-signing.properties`、Gradle property 或环境变量。
- 必需字段：
  - `CATLIFEPET_RELEASE_STORE_FILE`
  - `CATLIFEPET_RELEASE_STORE_PASSWORD`
  - `CATLIFEPET_RELEASE_KEY_ALIAS`
  - `CATLIFEPET_RELEASE_KEY_PASSWORD`
- `release-signing.properties`、`*.jks`、`*.keystore` 已被 `.gitignore` 排除。

## 发布前硬性门槛

- 生产 HTTPS API 已部署，默认 release API 为 `https://api.catlifepet.top/`；如环境不同，用 `CATLIFEPET_RELEASE_API_BASE_URL` 覆盖并确认仍为 HTTPS。
- SMTP、DeepSeek API key、PostgreSQL、备份和监控已配置在服务器端。
- 隐私政策补齐运营者名称、联系邮箱、服务器地区和公开链接。
- 签名 APK/AAB 完成密钥扫描，确认不含 API key、JWT secret、refresh token 或测试服务器地址。
- 真机完成登录、AI 聊天、本地桌宠、提醒、退出和注销账号测试。
