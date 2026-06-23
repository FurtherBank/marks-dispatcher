package com.marksdispatcher.app.model

data class LinkSource(
    val id: String,
    val label: String
)

data class DispatchPayload(
    val url: String,
    val sourceId: String,
    val sourceLabel: String,
    val rawText: String,
    val detectedAt: String
)

data class DispatchRecord(
    val payload: DispatchPayload,
    val success: Boolean,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

/** 局域网扫描到的 cpu-collector 设备 */
data class DiscoveredDevice(
    val deviceId: String,
    val deviceName: String,
    val ip: String,
    val port: Int,
    val service: String,
    val version: String,
    val dispatchUrl: String
)

/** 用户已配对的接收端设备（按 device_id 绑定，IP 可变） */
data class PairedDevice(
    val deviceId: String,
    val deviceName: String,
    val apiToken: String = "",
    val lastKnownIp: String = "",
    val lastKnownPort: Int = CollectorDefaults.PORT,
    val lastSeenAt: Long = 0L
)

/** 待重发的派发任务（直到成功一次后移除） */
data class PendingDispatch(
    val id: String,
    val payload: DispatchPayload,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAttemptAt: Long = 0L,
    val attemptCount: Int = 0,
    val lastError: String = ""
)

data class AppSettings(
    val apiEndpoint: String,
    val apiToken: String,
    val monitorEnabled: Boolean,
    val autoStartOnBoot: Boolean,
    val pairedDevice: PairedDevice? = null,
    /** true=按配对设备自动解析 IP；false=使用手动 apiEndpoint */
    val usePairedDevice: Boolean = true
)

object CollectorDefaults {
    const val PORT = 10889
    const val DISPATCH_PATH = "/dispatch"
    const val SERVICE_NAME = "cpu-collector"
    const val PING_PATH = "/ping"

    fun dispatchUrl(ip: String, port: Int = PORT): String {
        return "http://$ip:$port$DISPATCH_PATH"
    }

    fun pingUrl(ip: String, port: Int = PORT): String {
        return "http://$ip:$port$PING_PATH"
    }
}
