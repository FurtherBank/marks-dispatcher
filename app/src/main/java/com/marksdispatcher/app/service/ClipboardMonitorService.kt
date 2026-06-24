package com.marksdispatcher.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import com.marksdispatcher.app.overlay.FloatingBubbleManager
import com.marksdispatcher.app.util.DispatchDeduper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 前台服务：维持通知 + 执行派发；复制后由用户点击浮标触发同步。
 */
class ClipboardMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var dispatchManager: DispatchManager

    override fun onCreate() {
        super.onCreate()
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
                val force = intent.getBooleanExtra(EXTRA_FORCE, false)
                if (!text.isNullOrBlank()) {
                    processClipboardText(text, force = force)
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

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_monitoring)))
        if (!FloatingBubbleManager.show(this)) {
            updateNotification(getString(R.string.notification_bubble_failed))
        }
        serviceScope.launch {
            dispatchManager.retryPendingQueue()
        }
        return START_STICKY
    }

    private fun processClipboardText(text: String, force: Boolean) {
        val trimmed = text.trim()
        if (!force) {
            when (DispatchDeduper.evaluate(this, trimmed)) {
                DispatchDeduper.Decision.Empty,
                DispatchDeduper.Decision.NotALink -> {
                    updateNotification(getString(R.string.notification_not_link))
                    return
                }
                DispatchDeduper.Decision.DuplicateSkipped -> return
                DispatchDeduper.Decision.Proceed -> Unit
            }
        }

        val analysis = LinkSourceDetector.analyzeClipboardText(trimmed)
        if (analysis == null) {
            if (force) {
                updateNotification(getString(R.string.notification_not_link))
            }
            return
        }
        val (url, source) = analysis

        if (!force) {
            DispatchDeduper.markDispatched(this, url)
        }

        updateNotification(getString(R.string.notification_dispatching, source.label))

        val payload = DispatchPayload(
            url = url,
            sourceId = source.id,
            sourceLabel = source.label,
            rawText = trimmed,
            detectedAt = Instant.now().toString()
        )

        serviceScope.launch {
            val outcome = dispatchManager.dispatchPayload(payload)

            val record = DispatchRecord(
                payload = payload,
                success = outcome.success,
                message = outcome.message
            )
            settingsRepository.appendHistory(record)

            val statusText = if (outcome.success) {
                getString(R.string.notification_dispatched, source.label)
            } else {
                outcome.message
            }
            updateNotification(statusText)
            sendBroadcast(Intent(ACTION_DISPATCH_UPDATED).setPackage(packageName))
        }
    }

    private fun stopMonitoring() {
        FloatingBubbleManager.hide()
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
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
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
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        FloatingBubbleManager.hide()
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
        const val EXTRA_FORCE = "extra_force"

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
            context.startService(
                Intent(context, ClipboardMonitorService::class.java).setAction(ACTION_STOP)
            )
        }

        fun dispatchNow(context: Context, text: String, force: Boolean = false) {
            val intent = Intent(context, ClipboardMonitorService::class.java)
                .setAction(ACTION_DISPATCH_NOW)
                .putExtra(EXTRA_CLIP_TEXT, text)
                .putExtra(EXTRA_FORCE, force)
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
