package com.marksdispatcher.app.overlay

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.marksdispatcher.app.ClipboardCaptureActivity
import com.marksdispatcher.app.R

@SuppressLint("ClickableViewAccessibility")
object FloatingBubbleManager {

    private const val TAG = "FloatingBubble"

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
    private var cardView: View? = null
    private var attachedContext: Context? = null
    private var feedbackReceiver: BroadcastReceiver? = null
    private var resetRunnable: Runnable? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var lastBubbleClickAt = 0L

    fun show(context: Context): Boolean {
        if (!OverlayPermissionHelper.canDrawOverlays(context)) {
            Log.w(TAG, "overlay permission missing")
            return false
        }
        if (bubbleView != null) return true

        val appContext = context.applicationContext
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            showOnMainThread(appContext)
        } else {
            var success = false
            val latch = java.util.concurrent.CountDownLatch(1)
            handler.post {
                success = showOnMainThread(appContext)
                latch.countDown()
            }
            latch.await()
            success
        }
    }

    private fun showOnMainThread(appContext: Context): Boolean {
        if (bubbleView != null) return true
        return try {
            attachedContext = appContext
            windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val touchWidth = dpToPx(appContext, BUBBLE_TOUCH_WIDTH_DP)
            val touchHeight = dpToPx(appContext, BUBBLE_TOUCH_HEIGHT_DP)

            val params = WindowManager.LayoutParams(
                touchWidth,
                touchHeight,
                windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                x = dpToPx(appContext, 8)
                y = 0
            }
            layoutParams = params

            val themedContext = ContextThemeWrapper(appContext, R.style.Theme_MarksDispatcher)
            val view = LayoutInflater.from(themedContext).inflate(R.layout.floating_bubble, null)
            cardView = view.findViewById(R.id.bubbleCard)
            setupTouch(view)
            setBubbleColor(R.color.bubble_background)
            windowManager?.addView(view, params)
            bubbleView = view
            registerFeedbackReceiver(appContext)
            Log.i(TAG, "bubble shown ${touchWidth}x${touchHeight}px at x=${params.x}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to show bubble", e)
            hide()
            false
        }
    }

    fun hide() {
        handler.post {
            unregisterFeedbackReceiver()
            resetRunnable?.let { handler.removeCallbacks(it) }
            bubbleView?.let { view ->
                runCatching { windowManager?.removeView(view) }
            }
            bubbleView = null
            cardView = null
            layoutParams = null
            windowManager = null
            attachedContext = null
        }
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
        if (cardView == null) return

        resetRunnable?.let { handler.removeCallbacks(it) }

        when (feedback) {
            Feedback.Syncing -> setBubbleColor(R.color.bubble_syncing)
            Feedback.Success -> setBubbleColor(R.color.bubble_success)
            Feedback.Duplicate -> setBubbleColor(R.color.bubble_duplicate)
            Feedback.NotLink, Feedback.Empty -> setBubbleColor(R.color.bubble_invalid)
        }

        if (resetToIdle && feedback != Feedback.Syncing) {
            resetToIdleDelayed()
        }
    }

    private fun setBubbleColor(colorRes: Int) {
        val context = attachedContext ?: return
        val card = cardView ?: return
        val color = ContextCompat.getColor(context, colorRes)
        val stroke = ContextCompat.getColor(context, R.color.bubble_stroke)
        val radius = 10f * context.resources.displayMetrics.density
        val strokeWidth = (1f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
            setStroke(strokeWidth, stroke)
        }
        card.background = drawable
    }

    private fun resetToIdleDelayed() {
        resetRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            setBubbleColor(R.color.bubble_background)
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

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun windowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private const val BUBBLE_TOUCH_WIDTH_DP = 48
    private const val BUBBLE_TOUCH_HEIGHT_DP = 80
    private const val RESET_IDLE_MS = 1_200L
    private const val TAP_DEBOUNCE_MS = 800L
}
