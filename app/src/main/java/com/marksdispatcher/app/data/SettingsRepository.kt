package com.marksdispatcher.app.data

import android.content.Context
import com.marksdispatcher.app.model.AppSettings
import com.marksdispatcher.app.model.DispatchRecord
import org.json.JSONArray
import org.json.JSONObject

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSettings(): AppSettings {
        return AppSettings(
            apiEndpoint = prefs.getString(KEY_API_ENDPOINT, DEFAULT_ENDPOINT).orEmpty(),
            apiToken = prefs.getString(KEY_API_TOKEN, "").orEmpty(),
            monitorEnabled = prefs.getBoolean(KEY_MONITOR_ENABLED, false),
            autoStartOnBoot = prefs.getBoolean(KEY_AUTO_START, true)
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_API_ENDPOINT, settings.apiEndpoint.trim())
            .putString(KEY_API_TOKEN, settings.apiToken.trim())
            .putBoolean(KEY_MONITOR_ENABLED, settings.monitorEnabled)
            .putBoolean(KEY_AUTO_START, settings.autoStartOnBoot)
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

    companion object {
        private const val PREFS_NAME = "marks_dispatcher_prefs"
        private const val KEY_API_ENDPOINT = "api_endpoint"
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_MONITOR_ENABLED = "monitor_enabled"
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val KEY_HISTORY = "dispatch_history"
        private const val MAX_HISTORY = 50

        const val DEFAULT_ENDPOINT = "https://your-api.example.com/v1/collect"
    }
}
