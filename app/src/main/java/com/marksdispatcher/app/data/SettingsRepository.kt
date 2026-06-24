package com.marksdispatcher.app.data

import android.content.Context
import com.marksdispatcher.app.model.AppSettings
import com.marksdispatcher.app.model.CollectorDefaults
import com.marksdispatcher.app.model.DispatchRecord
import com.marksdispatcher.app.model.PairedDevice
import com.marksdispatcher.app.model.PendingDispatch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSettings(): AppSettings {
        return AppSettings(
            apiEndpoint = prefs.getString(KEY_API_ENDPOINT, DEFAULT_ENDPOINT).orEmpty(),
            apiToken = prefs.getString(KEY_API_TOKEN, "").orEmpty(),
            monitorEnabled = prefs.getBoolean(KEY_MONITOR_ENABLED, false),
            autoStartOnBoot = prefs.getBoolean(KEY_AUTO_START, true),
            pairedDevice = getPairedDevice(),
            usePairedDevice = prefs.getBoolean(KEY_USE_PAIRED_DEVICE, true)
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_API_ENDPOINT, settings.apiEndpoint.trim())
            .putString(KEY_API_TOKEN, settings.apiToken.trim())
            .putBoolean(KEY_MONITOR_ENABLED, settings.monitorEnabled)
            .putBoolean(KEY_AUTO_START, settings.autoStartOnBoot)
            .putBoolean(KEY_USE_PAIRED_DEVICE, settings.usePairedDevice)
            .apply()
        if (settings.pairedDevice != null) {
            savePairedDevice(settings.pairedDevice)
        }
    }

    fun getPairedDevice(): PairedDevice? {
        val deviceId = prefs.getString(KEY_PAIRED_DEVICE_ID, null) ?: return null
        return PairedDevice(
            deviceId = deviceId,
            deviceName = prefs.getString(KEY_PAIRED_DEVICE_NAME, deviceId).orEmpty(),
            apiToken = prefs.getString(KEY_PAIRED_DEVICE_TOKEN, "").orEmpty(),
            lastKnownIp = prefs.getString(KEY_PAIRED_DEVICE_IP, "").orEmpty(),
            lastKnownPort = prefs.getInt(KEY_PAIRED_DEVICE_PORT, CollectorDefaults.PORT),
            lastSeenAt = prefs.getLong(KEY_PAIRED_DEVICE_SEEN, 0L)
        )
    }

    fun savePairedDevice(device: PairedDevice) {
        prefs.edit()
            .putString(KEY_PAIRED_DEVICE_ID, device.deviceId)
            .putString(KEY_PAIRED_DEVICE_NAME, device.deviceName)
            .putString(KEY_PAIRED_DEVICE_TOKEN, device.apiToken)
            .putString(KEY_PAIRED_DEVICE_IP, device.lastKnownIp)
            .putInt(KEY_PAIRED_DEVICE_PORT, device.lastKnownPort)
            .putLong(KEY_PAIRED_DEVICE_SEEN, device.lastSeenAt)
            .apply()
    }

    fun clearPairedDevice() {
        prefs.edit()
            .remove(KEY_PAIRED_DEVICE_ID)
            .remove(KEY_PAIRED_DEVICE_NAME)
            .remove(KEY_PAIRED_DEVICE_TOKEN)
            .remove(KEY_PAIRED_DEVICE_IP)
            .remove(KEY_PAIRED_DEVICE_PORT)
            .remove(KEY_PAIRED_DEVICE_SEEN)
            .apply()
    }

    fun setMonitorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MONITOR_ENABLED, enabled).apply()
    }

    fun getHistory(): List<DispatchRecord> {
        val raw = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        return parseHistory(raw)
    }

    fun appendHistory(record: DispatchRecord) {
        val history = getHistory().toMutableList()
        history.add(0, record)
        val trimmed = history.take(MAX_HISTORY)
        prefs.edit().putString(KEY_HISTORY, serializeHistory(trimmed)).apply()
    }

    fun clearHistory() {
        prefs.edit().putString(KEY_HISTORY, "[]").apply()
    }

    fun getPendingDispatches(): List<PendingDispatch> {
        val raw = prefs.getString(KEY_PENDING, "[]") ?: "[]"
        return parsePending(raw)
    }

    fun enqueuePending(payload: com.marksdispatcher.app.model.DispatchPayload): PendingDispatch {
        val pending = PendingDispatch(
            id = UUID.randomUUID().toString(),
            payload = payload
        )
        val list = getPendingDispatches().toMutableList()
        val exists = list.any { it.payload.url == payload.url && it.payload.rawText == payload.rawText }
        if (!exists) {
            list.add(pending)
            prefs.edit().putString(KEY_PENDING, serializePending(list)).apply()
        }
        return pending
    }

    fun removePending(id: String) {
        val list = getPendingDispatches().filterNot { it.id == id }
        prefs.edit().putString(KEY_PENDING, serializePending(list)).apply()
    }

    fun updatePendingAttempt(id: String, error: String) {
        val list = getPendingDispatches().map { item ->
            if (item.id == id) {
                item.copy(
                    attemptCount = item.attemptCount + 1,
                    lastAttemptAt = System.currentTimeMillis(),
                    lastError = error
                )
            } else item
        }
        prefs.edit().putString(KEY_PENDING, serializePending(list)).apply()
    }

    fun pendingCount(): Int = getPendingDispatches().size

    fun getLastDispatchedUrl(): String? {
        return prefs.getString(KEY_LAST_DISPATCHED_URL, null)
    }

    fun getLastDispatchedAt(): Long {
        return prefs.getLong(KEY_LAST_DISPATCHED_AT, 0L)
    }

    fun setLastDispatchedUrl(url: String) {
        prefs.edit()
            .putString(KEY_LAST_DISPATCHED_URL, url)
            .putLong(KEY_LAST_DISPATCHED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun parseHistory(raw: String): List<DispatchRecord> {
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val payload = item.getJSONObject("payload")
                    add(
                        DispatchRecord(
                            payload = com.marksdispatcher.app.model.DispatchPayload(
                                url = payload.getString("url"),
                                sourceId = payload.getString("sourceId"),
                                sourceLabel = payload.getString("sourceLabel"),
                                rawText = payload.getString("rawText"),
                                detectedAt = payload.getString("detectedAt")
                            ),
                            success = item.getBoolean("success"),
                            message = item.getString("message"),
                            timestamp = item.getLong("timestamp")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeHistory(records: List<DispatchRecord>): String {
        val array = JSONArray()
        records.forEach { record ->
            val payload = JSONObject()
                .put("url", record.payload.url)
                .put("sourceId", record.payload.sourceId)
                .put("sourceLabel", record.payload.sourceLabel)
                .put("rawText", record.payload.rawText)
                .put("detectedAt", record.payload.detectedAt)

            array.put(
                JSONObject()
                    .put("payload", payload)
                    .put("success", record.success)
                    .put("message", record.message)
                    .put("timestamp", record.timestamp)
            )
        }
        return array.toString()
    }

    private fun parsePending(raw: String): List<PendingDispatch> {
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val payload = item.getJSONObject("payload")
                    add(
                        PendingDispatch(
                            id = item.getString("id"),
                            payload = com.marksdispatcher.app.model.DispatchPayload(
                                url = payload.getString("url"),
                                sourceId = payload.getString("sourceId"),
                                sourceLabel = payload.getString("sourceLabel"),
                                rawText = payload.getString("rawText"),
                                detectedAt = payload.getString("detectedAt")
                            ),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                            lastAttemptAt = item.optLong("lastAttemptAt", 0L),
                            attemptCount = item.optInt("attemptCount", 0),
                            lastError = item.optString("lastError", "")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializePending(items: List<PendingDispatch>): String {
        val array = JSONArray()
        items.forEach { item ->
            val payload = JSONObject()
                .put("url", item.payload.url)
                .put("sourceId", item.payload.sourceId)
                .put("sourceLabel", item.payload.sourceLabel)
                .put("rawText", item.payload.rawText)
                .put("detectedAt", item.payload.detectedAt)

            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("payload", payload)
                    .put("createdAt", item.createdAt)
                    .put("lastAttemptAt", item.lastAttemptAt)
                    .put("attemptCount", item.attemptCount)
                    .put("lastError", item.lastError)
            )
        }
        return array.toString()
    }

    companion object {
        private const val PREFS_NAME = "marks_dispatcher_prefs"
        private const val KEY_API_ENDPOINT = "api_endpoint"
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_MONITOR_ENABLED = "monitor_enabled"
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val KEY_HISTORY = "dispatch_history"
        private const val KEY_PENDING = "pending_dispatches"
        private const val KEY_USE_PAIRED_DEVICE = "use_paired_device"
        private const val KEY_PAIRED_DEVICE_ID = "paired_device_id"
        private const val KEY_PAIRED_DEVICE_NAME = "paired_device_name"
        private const val KEY_PAIRED_DEVICE_TOKEN = "paired_device_token"
        private const val KEY_PAIRED_DEVICE_IP = "paired_device_ip"
        private const val KEY_PAIRED_DEVICE_PORT = "paired_device_port"
        private const val KEY_PAIRED_DEVICE_SEEN = "paired_device_seen"
        private const val KEY_LAST_DISPATCHED_URL = "last_dispatched_url"
        private const val KEY_LAST_DISPATCHED_AT = "last_dispatched_at"
        private const val MAX_HISTORY = 50

        /** 默认走 cpu-collector 标准端口，配对后自动解析 IP */
        const val DEFAULT_ENDPOINT = ""
    }
}
