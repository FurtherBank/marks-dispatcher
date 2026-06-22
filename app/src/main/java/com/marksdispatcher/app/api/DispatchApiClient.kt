package com.marksdispatcher.app.api

import com.marksdispatcher.app.model.DispatchPayload
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

class DispatchApiClient {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()

    data class DispatchResult(
        val success: Boolean,
        val message: String,
        val httpCode: Int? = null
    )

    fun dispatch(
        endpoint: String,
        token: String,
        url: String,
        sourceId: String,
        sourceLabel: String,
        rawText: String
    ): DispatchResult {
        if (endpoint.isBlank()) {
            return DispatchResult(false, "API 地址未配置")
        }

        val payload = DispatchPayload(
            url = url,
            sourceId = sourceId,
            sourceLabel = sourceLabel,
            rawText = rawText,
            detectedAt = Instant.now().toString()
        )

        val bodyJson = JSONObject()
            .put("url", payload.url)
            .put("source_id", payload.sourceId)
            .put("source_label", payload.sourceLabel)
            .put("raw_text", payload.rawText)
            .put("detected_at", payload.detectedAt)
            .put("platform", "android")
            .put("app", "marks-dispatcher")
            .toString()

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(bodyJson.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")

        if (token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    DispatchResult(
                        success = true,
                        message = "派发成功 (${response.code})",
                        httpCode = response.code
                    )
                } else {
                    DispatchResult(
                        success = false,
                        message = "派发失败 (${response.code}): ${responseBody.take(200)}",
                        httpCode = response.code
                    )
                }
            }
        } catch (e: Exception) {
            DispatchResult(success = false, message = "网络错误: ${e.message}")
        }
    }
}
