package com.marksdispatcher.app.api

import com.marksdispatcher.app.model.CollectorDefaults
import com.marksdispatcher.app.model.DiscoveredDevice
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CollectorDiscoveryClient {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    fun probe(ip: String, port: Int = CollectorDefaults.PORT): DiscoveredDevice? {
        val url = CollectorDefaults.pingUrl(ip, port)
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                parsePingResponse(body, ip, port) ?: return null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun probeEndpoint(endpoint: String): DiscoveredDevice? {
        val pingUrl = endpoint
            .replace(Regex("/dispatch/?$"), CollectorDefaults.PING_PATH)
            .trimEnd('/')
            .let { if (it.endsWith(CollectorDefaults.PING_PATH)) it else "$it${CollectorDefaults.PING_PATH}" }

        val request = Request.Builder().url(pingUrl).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val ip = pingUrl.substringAfter("http://").substringBefore(":").substringBefore("/")
                val port = pingUrl.substringAfter(":$").substringBefore("/").toIntOrNull()
                    ?: CollectorDefaults.PORT
                parsePingResponse(response.body?.string().orEmpty(), ip, port)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parsePingResponse(body: String, ip: String, port: Int): DiscoveredDevice? {
        return try {
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) return null
            val service = json.optString("service")
            if (service != CollectorDefaults.SERVICE_NAME) return null

            val deviceId = json.optString("device_id").ifBlank { return null }
            val deviceName = json.optString("device_name", ip)
            val resolvedPort = json.optInt("port", port)
            val dispatchPath = json.optString("dispatch_path", CollectorDefaults.DISPATCH_PATH)
            val dispatchUrls = json.optJSONArray("dispatch_urls")
            val dispatchUrl = when {
                dispatchUrls != null && dispatchUrls.length() > 0 -> dispatchUrls.getString(0)
                else -> "http://$ip:$resolvedPort$dispatchPath"
            }

            DiscoveredDevice(
                deviceId = deviceId,
                deviceName = deviceName,
                ip = ip,
                port = resolvedPort,
                service = service,
                version = json.optString("version", ""),
                dispatchUrl = dispatchUrl
            )
        } catch (_: Exception) {
            null
        }
    }
}
