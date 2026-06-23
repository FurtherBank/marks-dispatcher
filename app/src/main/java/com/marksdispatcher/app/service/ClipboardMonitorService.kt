package com.marksdispatcher.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.marksdispatcher.app.MainActivity
import com.marksdispatcher.app.R
import com.marksdispatcher.app.data.SettingsRepository
import com.marksdispatcher.app.detector.LinkSourceDetector
import com.marksdispatcher.app.dispatch.DispatchManager
import com.marksdispatcher.app.model.DispatchPayload
import com.marksdispatcher.app.model.DispatchRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant

class ClipboardMonitorService : Service(), ClipboardManager.OnPrimaryClipChangedListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var dispatchManager: DispatchManager

    private var lastProcessedText: String? = null

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        settingsRepository = SettingsRepository(this)
        dispatchManager = DispatchManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            ACTION_DISPATCH_NOW -> {
                val text = intent.getStringExtra(EXTRA_CLIP_TEXT)
                if (!text.isNullOrBlank()) {
                    processClipboardText(text, force = true)
                }
                return START_STICKY
            }
            ACTION_RETRY_PENDING -> {
                serviceScope.launch {
                    val count = dispatchManager.retryPendingQueue()
                    updateNotification(if (count > 0) "已重发 $count 条" else "暂无待重发")
                    sendBroadcast(Intent(ACTION_DISPATCH_UPDATED).setPackage(packageName))
                }
                return START_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("监听中，复制链接后将自动派发"))
        clipboardManager.addPrimaryClipChangedListener(this)
        serviceScope.launch {
            dispatchManager.retryPendingQueue()
        }
        return START_STICKY
    }

    override fun onPrimaryClipChanged() {
        val text = readClipboardText() ?: return
        processClipboardText(text, force = false)
    }

    private fun readClipboardText(): String? {
        if (!clipboardManager.hasPrimaryClip()) return null
        val clip = clipboardManager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val item: ClipData.Item = clip.getItemAt(0)
        return item.coerceToText(this)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun processClipboardText(text: String, force: Boolean) {
        if (!force && text == lastProcessedText) return

        val analysis = LinkSourceDetector.analyzeClipboardText(text) ?: return
        val (url, source) = analysis

        lastProcessedText = text
        updateNotification("正在派发: ${source.label}")

        val payload = DispatchPayload(
            url = url,
            sourceId = source.id,
            sourceLabel = source.label,
            rawText = text,
            detectedAt = Instant.now().toString()
        )

        serviceScope.launch {
            val outcome = dispatchManager.dispatchPayload(payload)

            val record = DispatchRecord(
                payload = payload,
                success = outcome.success,
                message = outcome.message
            )
            if (outcome.success) {
                settingsRepository.appendHistory(record)
            } else if (!outcome.queuedForRetry) {
                settingsRepository.appendHistory(record)
            } else {
                settingsRepository.appendHistory(record)
            }

            val statusText = if (outcome.success) {
                "已派发 ${source.label}"
            } else {
                outcome.message
            }
            updateNotification(statusText)
            sendBroadcast(Intent(ACTION_DISPATCH_UPDATED).setPackage(packageName))
        }
    }

    private fun stopMonitoring() {
        clipboardManager.removePrimaryClipChangedListener(this)
        settingsRepository.setMonitorEnabled(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ClipboardMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "停止监听", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.marksdispatcher.app.action.STOP_MONITOR"
        const val ACTION_DISPATCH_NOW = "com.marksdispatcher.app.action.DISPATCH_NOW"
        const val ACTION_RETRY_PENDING = "com.marksdispatcher.app.action.RETRY_PENDING"
        const val ACTION_DISPATCH_UPDATED = "com.marksdispatcher.app.action.DISPATCH_UPDATED"
        const val EXTRA_CLIP_TEXT = "extra_clip_text"

        private const val CHANNEL_ID = "clipboard_monitor"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ClipboardMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ClipboardMonitorService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun dispatchNow(context: Context, text: String) {
            val intent = Intent(context, ClipboardMonitorService::class.java)
                .setAction(ACTION_DISPATCH_NOW)
                .putExtra(EXTRA_CLIP_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun retryPending(context: Context) {
            val intent = Intent(context, ClipboardMonitorService::class.java)
                .setAction(ACTION_RETRY_PENDING)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
