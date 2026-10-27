package com.screenkit.screenrecorder.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.screenkit.screenrecorder.CaptureContract
import com.screenkit.screenrecorder.data.SettingsStore
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Visualizes screen touches during recording and interactive sessions.
 *
 * Combines:
 * 1. Android system `show_touches` toggle (via Settings.System / shell when permitted).
 * 2. A non-touchable, non-focusable transparent overlay canvas that renders animated touch
 *    circles without blocking or intercepting underlying app input.
 */
object TouchFeedbackOverlay {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ripples = CopyOnWriteArrayList<TouchRipple>()
    private val activePointers = java.util.concurrent.ConcurrentHashMap<Int, PointerPoint>()

    private var windowManager: WindowManager? = null
    private var canvasView: TouchCanvasView? = null
    private var watcherView: View? = null
    private var isTemporarilyHidden = false
    @Volatile var isShowing = false
        private set

    private data class TouchRipple(
        val x: Float,
        val y: Float,
        val startedAtMs: Long,
    )

    private data class PointerPoint(
        val x: Float,
        val y: Float,
        val updatedAtMs: Long,
    )

    private const val RIPPLE_DURATION_MS = 420L

    fun setEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        SettingsStore.setShowTouches(appContext, enabled)
        tryApplySystemShowTouches(appContext, enabled)
        mainHandler.post {
            if (enabled) {
                ensureShown(appContext)
            } else {
                hideInternal()
            }
            appContext.sendBroadcast(
                Intent(CaptureContract.ACTION_TOUCH_STATE)
                    .setPackage(appContext.packageName)
                    .putExtra(CaptureContract.EXTRA_SHOW_TOUCHES, enabled),
            )
        }
    }

    fun sync(context: Context) {
        val appContext = context.applicationContext
        val enabled = SettingsStore.showTouches(appContext)
        mainHandler.post {
            if (enabled) {
                ensureShown(appContext)
            } else {
                hideInternal()
            }
        }
    }

    fun setTemporarilyHiddenForScreenshot(hidden: Boolean) {
        mainHandler.post {
            isTemporarilyHidden = hidden
            canvasView?.visibility = if (hidden) View.GONE else View.VISIBLE
            if (hidden) {
                ripples.clear()
                activePointers.clear()
                canvasView?.invalidate()
            }
        }
    }

    fun recordSyntheticTap(rawX: Float, rawY: Float) {
        recordTouch(rawX, rawY)
    }

    fun recordTouch(rawX: Float, rawY: Float) {
        if (!isShowing || isTemporarilyHidden) return
        if (rawX <= 0f && rawY <= 0f) return
        val now = SystemClock.uptimeMillis()
        ripples.add(TouchRipple(rawX, rawY, now))
        while (ripples.size > 24) {
            ripples.removeAt(0)
        }
        mainHandler.post { canvasView?. triggerInvalidate() }
    }

    fun recordTouchEvent(event: MotionEvent) {
        if (!isShowing || isTemporarilyHidden) return
        val now = SystemClock.uptimeMillis()
        val rawX = event.rawX
        val rawY = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                activePointers[event.getPointerId(event.actionIndex)] = PointerPoint(rawX, rawY, now)
                recordTouch(rawX, rawY)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = rawX - event.x
                val dy = rawY - event.y
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val px = event.getX(i) + dx
                    val py = event.getY(i) + dy
                    activePointers[pid] = PointerPoint(px, py, now)
                }
                mainHandler.post { canvasView?.triggerInvalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    activePointers.remove(event.getPointerId(event.actionIndex))
                } else {
                    activePointers.clear()
                }
                recordTouch(rawX, rawY)
            }
            MotionEvent.ACTION_OUTSIDE -> {
                if (rawX > 0f || rawY > 0f) {
                    recordTouch(rawX, rawY)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun ensureShown(context: Context) {
        if (!Settings.canDrawOverlays(context)) return
        val wm = (windowManager ?: context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?: return
        windowManager = wm
        if (canvasView != null) {
            canvasView?.visibility = if (isTemporarilyHidden) View.GONE else View.VISIBLE
            isShowing = true
            return
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 1. Non-touchable full-screen canvas for rendering touch circles
        val canvas = TouchCanvasView(context)
        val canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
        }

        // 2. 1x1 outside-touch observer that never consumes touches
        val watcher = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE ||
                    event.actionMasked == MotionEvent.ACTION_DOWN
                ) {
                    recordTouch(event.rawX, event.rawY)
                }
                false
            }
        }
        val watcherParams = WindowManager.LayoutParams(
            1,
            1,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
        }

        runCatching {
            wm.addView(canvas, canvasParams)
            canvasView = canvas
            canvas.visibility = if (isTemporarilyHidden) View.GONE else View.VISIBLE
            isShowing = true
        }
        runCatching {
            wm.addView(watcher, watcherParams)
            watcherView = watcher
        }
    }

    private fun hideInternal() {
        val wm = windowManager
        canvasView?.let { v -> runCatching { wm?.removeView(v) } }
        watcherView?.let { v -> runCatching { wm?.removeView(v) } }
        canvasView = null
        watcherView = null
        ripples.clear()
        activePointers.clear()
        isShowing = false
    }

    private fun tryApplySystemShowTouches(context: Context, enabled: Boolean) {
        val value = if (enabled) 1 else 0
        try {
            if (Settings.System.canWrite(context)) {
                Settings.System.putInt(context.contentResolver, "show_touches", value)
                return
            }
        } catch (_: Exception) {}
        try {
            Settings.System.putInt(context.contentResolver, "show_touches", value)
            return
        } catch (_: Exception) {}
        Thread {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "settings put system show_touches $value")).waitFor()
            }
        }.start()
    }

    private class TouchCanvasView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        }

        fun triggerInvalidate() {
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isTemporarilyHidden) return
            val now = SystemClock.uptimeMillis()
            var needsMoreFrames = false

            // Draw active held/dragged pointers
            val pointerIter = activePointers.entries.iterator()
            while (pointerIter.hasNext()) {
                val entry = pointerIter.next()
                val pt = entry.value
                if (now - pt.updatedAtMs > 1500L) {
                    pointerIter.remove()
                    continue
                }
                val baseRadius = 18f * density
                fillPaint.color = Color.argb(115, 183, 243, 107)
                canvas.drawCircle(pt.x, pt.y, baseRadius, fillPaint)
                strokePaint.color = Color.argb(235, 255, 255, 255)
                canvas.drawCircle(pt.x, pt.y, baseRadius, strokePaint)
                fillPaint.color = Color.argb(220, 255, 255, 255)
                canvas.drawCircle(pt.x, pt.y, 5f * density, fillPaint)
                needsMoreFrames = true
            }

            // Draw expanding/fading tap ripples
            for (ripple in ripples) {
                val elapsed = now - ripple.startedAtMs
                if (elapsed !in 0..RIPPLE_DURATION_MS) {
                    ripples.remove(ripple)
                    continue
                }
                val progress = elapsed.toFloat() / RIPPLE_DURATION_MS.toFloat()
                val alpha = ((1f - progress) * 190f).toInt().coerceIn(0, 255)
                val radius = (12f + progress * 20f) * density

                fillPaint.color = Color.argb((alpha * 0.55f).toInt(), 183, 243, 107)
                canvas.drawCircle(ripple.x, ripple.y, radius, fillPaint)

                strokePaint.color = Color.argb(alpha, 255, 255, 255)
                canvas.drawCircle(ripple.x, ripple.y, radius, strokePaint)

                fillPaint.color = Color.argb(alpha, 183, 243, 107)
                canvas.drawCircle(ripple.x, ripple.y, 4.5f * density * (1f - progress * 0.4f), fillPaint)
                needsMoreFrames = true
            }

            if (needsMoreFrames) {
                postInvalidateOnAnimation()
            }
        }
    }
}
