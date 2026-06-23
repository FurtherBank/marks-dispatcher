# 收藏派发器 (Marks Dispatcher)

当你在各类内容 App（视频网站、图文平台等）中复制分享链接时，本 App 会自动识别链接来源，并将上下文信息派发到可配置的外部 API，由后端完成收藏自动化处理。

## v1.1.0 功能

- **局域网扫描配对**：扫描同一 Wi‑Fi 下的 [cpu-collector](https://github.com/FurtherBank/cpu-collector)（标准端口 `10889`），按 `device_id` 绑定电脑
- **IP 自动跟踪**：配对后按 `device_id` 解析当前 IP（先尝试上次 IP，再扫描局域网）
- **离线重发队列**：接收端离线或派发失败时入队，设备上线后自动重试，**每条消息成功一次后停止重发**
- **手动模式保留**：可关闭「使用配对设备」，改用手填 `http://<ip>:10889/dispatch`

## MVP 功能

- **剪贴板监听**：前台服务持续监听剪贴板，复制链接后自动触发
- **来源识别**：根据 URL 域名识别来源（哔哩哔哩、YouTube、微博、知乎、抖音、小红书等）
- **外部派发**：将结构化 JSON POST 到配置的 API 端点
- **手动派发**：支持一键派发当前剪贴板内容（便于调试或权限受限场景）
- **派发历史**：本地记录最近 50 条派发结果
- **开机恢复**：可选开机后自动恢复监听

## 外部 API 约定

`POST {api_endpoint}`

请求头：

```
Content-Type: application/json
Authorization: Bearer {api_token}   # 可选
```

请求体示例：

```json
{
  "url": "https://www.bilibili.com/video/BV1xx",
  "source_id": "bilibili",
  "source_label": "哔哩哔哩",
  "raw_text": "快来看看 https://www.bilibili.com/video/BV1xx",
  "detected_at": "2026-06-18T12:00:00Z",
  "platform": "android",
  "app": "marks-dispatcher"
}
```

## 本地构建

环境要求：JDK 17、Android SDK（API 34）

```bash
chmod +x gradlew
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

运行单元测试：

```bash
./gradlew testDebugUnitTest
```

## 手机使用说明

1. 在电脑上启动 `cpu-collector`（监听 `0.0.0.0:10889`）
2. 安装 APK，点击「扫描局域网」并选择你的电脑
3. 填写 Token（若电脑端配置了 `COLLECTOR_API_TOKEN`）
4. 开启「剪贴板监听」并授予通知权限
5. 复制 B 站 / 知乎等链接，App 将自动派发到已配对电脑
6. 电脑离线时消息进入待重发队列，上线后自动补发

> **注意**：Android 10+ 对后台剪贴板读取有限制。本 App 通过前台服务尽量保持可访问性；不同厂商 ROM 行为可能略有差异。

## CI

GitHub Actions 工作流 `.github/workflows/android-ci.yml` 会在 push/PR 到 `main` 时：

1. 运行单元测试
2. 构建 debug APK
3. 上传 APK 与测试报告为 Artifact

## 项目结构

```
app/src/main/java/com/marksdispatcher/app/
├── MainActivity.kt              # 配置与历史界面
├── api/DispatchApiClient.kt     # HTTP 派发
├── data/SettingsRepository.kt   # 本地配置与历史
├── detector/LinkSourceDetector.kt  # URL 提取与来源识别
├── service/ClipboardMonitorService.kt  # 剪贴板前台服务
└── receiver/BootReceiver.kt     # 开机自启
```

## 技术栈

- Kotlin + Android SDK 34
- Material 3
- OkHttp
- ViewBinding
- Gradle 8.7 + AGP 8.5
