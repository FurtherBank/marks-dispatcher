# 收藏派发器 (Marks Dispatcher)

当你在各类内容 App 中复制分享链接后，点击屏幕边缘浮标，即可将链接派发到已配对的 cpu-collector。

## v1.3.0 功能

- **浮标一键同步**：复制链接后点浮标「发」，合法读取剪贴板并派发
- **防重复处理**：同一 URL **10 分钟内**不重复派发；浮标 **800ms** 连点防抖
- **轻量反馈**：浮标变色/改字（发 → … → ✓ / 已发 / ×），不弹 Toast 打扰
- **可拖动浮标**：长按拖动调整位置

## v1.1.0 功能

- **局域网扫描配对**：扫描同一 Wi‑Fi 下的 [cpu-collector](https://github.com/FurtherBank/cpu-collector)（标准端口 `10889`），按 `device_id` 绑定电脑
- **IP 自动跟踪**：配对后按 `device_id` 解析当前 IP（先尝试上次 IP，再扫描局域网）
- **离线重发队列**：接收端离线或派发失败时入队，设备上线后自动重试，**每条消息成功一次后停止重发**
- **手动模式保留**：可关闭「使用配对设备」，改用手填 `http://<ip>:10889/dispatch`

## 核心功能

- **浮标同步**：前台服务 + 屏幕浮标，复制后点一下同步
- **来源识别**：根据 URL 域名识别来源（哔哩哔哩、YouTube、微博、知乎、抖音、小红书等）
- **外部派发**：将结构化 JSON POST 到配置的 API 端点
- **手动派发**：App 内一键派发（跳过去重，便于强制重发）
- **派发历史**：本地记录最近 50 条派发结果
- **开机恢复**：可选开机后自动恢复浮标

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

## 手机使用说明

1. 在电脑上启动 `cpu-collector`（监听 `0.0.0.0:10889`）
2. 安装 APK，扫描局域网并配对电脑
3. 填写 Token（若电脑端配置了 `COLLECTOR_API_TOKEN`）
4. 授予 **悬浮窗权限**（显示在其他应用上层）
5. 开启「浮标同步」并授予通知权限
6. 在内容 App 中 **分享 → 复制链接**，再点屏幕边缘浮标 **「发」**
7. 浮标显示 **✓** 表示已派发；**已发** 表示 10 分钟内已同步过同一链接

## 项目结构

```
app/src/main/java/com/marksdispatcher/app/
├── MainActivity.kt
├── ClipboardCaptureActivity.kt      # 浮标点击后读剪贴板
├── overlay/FloatingBubbleManager.kt # 浮标 UI
├── overlay/OverlayPermissionHelper.kt
├── util/DispatchDeduper.kt          # URL 去重
├── service/ClipboardMonitorService.kt
└── ...
```

## 技术栈

- Kotlin + Android SDK 34
- Material 3 · OkHttp · ViewBinding
