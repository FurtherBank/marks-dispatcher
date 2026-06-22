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

data class AppSettings(
    val apiEndpoint: String,
    val apiToken: String,
    val monitorEnabled: Boolean,
    val autoStartOnBoot: Boolean
)
