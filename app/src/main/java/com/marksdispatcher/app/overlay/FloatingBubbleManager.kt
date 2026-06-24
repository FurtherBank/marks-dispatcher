package com.marksdispatcher.app.overlay

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.marksdispatcher.app.ClipboardCaptureActivity
import com.marksdispatcher.app.R

@SuppressLint("ClickableViewAccessibility")
object FloatingBubbleManager {

    const val ACTION_BUBBLE_FEEDBACK = "com.marksdispatcher.app.action.BUBBLE_FEEDBACK"
    const val EXTRA_FEEDBACK = "extra_feedback"

    enum class Feedback {
        Syncing,
        Success,
        Duplicate,
        NotLink,
        Empty
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var labelView: TextView? = null
    private var cardView: MaterialCardView? = null
    private var attachedContext: Context? = null
    private var feedbackReceiver: BroadcastReceiver? = null
    private var resetRunnable: Runnable? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var lastBubbleClickAt = 0L

    fun show(context: Context) {
        if (!OverlayPermissionHelper.canDrawOverlays(context)) return
        if (bubbleView != null) return

        val appContext = context.applicationContext
        attachedContext = appContext
        windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 24
            y = 0
        }
        layoutParams = params

        val view = LayoutInflater.from(appContext).inflate(R.layout.floating_bubble, null)
        labelView = view.findViewById(R.id.bubbleLabel)
        cardView = view.findViewById(R.id.bubbleCard)
        setupTouch(view)
        windowManager?.addView(view, params)
        bubbleView = view
        labelView?.text = appContext.getString(R.string.bubble_label_idle)
        cardView?.setCardBackgroundColor(ContextCompat.getColor(appContext, R.color.bubble_background))

        registerFeedbackReceiver(appContext)
    }

    fun hide() {
        unregisterFeedbackReceiver()
        resetRunnable?.let { handler.removeCallbacks(it) }
        bubbleView?.let { windowManager?.removeView(it) }
        bubbleView = null
        labelView = null
        cardView = null
        layoutParams = null
        windowManager = null
        attachedContext = null
    }

    fun onBubbleTapped(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastBubbleClickAt < TAP_DEBOUNCE_MS) {
            return
        }
        lastBubbleClickAt = now
        showFeedback(Feedback.Syncing, resetToIdle = false)
        ClipboardCaptureActivity.launch(context)
    }

    fun sendFeedback(context: Context, feedback: Feedback) {
        context.applicationContext.sendBroadcast(
            Intent(ACTION_BUBBLE_FEEDBACK)
                .setPackage(context.packageName)
                .putExtra(EXTRA_FEEDBACK, feedback.name)
        )
    }

    private fun registerFeedbackReceiver(context: Context) {
        if (feedbackReceiver != null) return
        feedbackReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val name = intent?.getStringExtra(EXTRA_FEEDBACK) ?: return
                val feedback = runCatching { Feedback.valueOf(name) }.getOrNull() ?: return
                showFeedback(feedback)
            }
        }
        ContextCompat.registerReceiver(
            context,
            feedbackReceiver!!,
            IntentFilter(ACTION_BUBBLE_FEEDBACK),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterFeedbackReceiver() {
        val context = attachedContext ?: return
        feedbackReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        feedbackReceiver = null
    }

    private fun showFeedback(feedback: Feedback, resetToIdle: Boolean = true) {
        val label = labelView ?: return
        val card = cardView ?: return
        val context = attachedContext ?: return

        resetRunnable?.let { handler.removeCallbacks(it) }

        when (feedback) {
            Feedback.Syncing -> {
                label.text = context.getString(R.string.bubble_label_syncing)
                card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.bubble_syncing))
            }
            Feedback.Success -> {
                label.text = context.getString(R.string.bubble_label_success)
                card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.bubble_success))
            }
            Feedback.Duplicate -> {
                label.text = context.getString(R.string.bubble_label_duplicate)
                card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.bubble_duplicate))
            }
            Feedback.NotLink, Feedback.Empty -> {
                label.text = context.getString(R.string.bubble_label_invalid)
                card.setCardBackgroundColor(ContextCompat.getColor(context, R.color.bubble_invalid))
            }
        }

        if (resetToIdle && feedback != Feedback.Syncing) {
            resetToIdleDelayed()
        }
    }

    private fun resetToIdleDelayed() {
        resetRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            val context = attachedContext ?: return@Runnable
            labelView?.text = context.getString(R.string.bubble_label_idle)
            cardView?.setCardBackgroundColor(ContextCompat.getColor(context, R.color.bubble_background))
        }
        resetRunnable = runnable
        handler.postDelayed(runnable, RESET_IDLE_MS)
    }

    private fun setupTouch(view: View) {
        view.setOnTouchListener { v, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX - dx
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(v, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        attachedContext?.let { onBubbleTapped(it) }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun windowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private const val RESET_IDLE_MS = 1_200L
    private const val TAP_DEBOUNCE_MS = 800L
}
