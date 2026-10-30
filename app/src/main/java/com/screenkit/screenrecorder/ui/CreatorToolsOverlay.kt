package com.screenkit.screenrecorder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.screenkit.screenrecorder.CaptureIntents
import com.screenkit.screenrecorder.data.SettingsStore
import com.screenkit.screenrecorder.services.ScreenKitAutoService
import kotlin.math.abs

/**
 * Provides three floating creator & productivity overlays:
 * 1. **Floating Teleprompter**: Auto-scrolling script reader with speed & font-size controls.
 * 2. **On-Screen Magnifier Loupe & Color Picker (Eyedropper)**: Live (X, Y) coordinate reticle,
 *    3× pixel zoom loupe, #HEX / RGB inspector, clipboard copy, and 1-tap Smart Color Trigger binding.
 * 3. **Custom Creator Watermark Badge**: Draggable on-screen branding badge during recordings.
 */
internal object CreatorToolsOverlay {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null

    // 1. Teleprompter state
    private var teleprompterView: LinearLayout? = null
    private var teleprompterParams: WindowManager.LayoutParams? = null
    private var teleprompterScrollView: ScrollView? = null
    private var teleprompterTextView: TextView? = null
    private var teleprompterScrolling = false
    private var teleprompterTextSp = 15f
    @Volatile
    var isTeleprompterShowing = false
        private set

    // 2. Magnifier & Eyedropper state
    private var magnifierView: LinearLayout? = null
    private var magnifierParams: WindowManager.LayoutParams? = null
    private var loupeCanvasView: LoupeReticleView? = null
    private var magnifierInfoTv: TextView? = null
    private var currentSampleX = 540
    private var currentSampleY = 960
    private var currentSampleColor: Int = Color.TRANSPARENT
    private var sampleScreenWidth = 1
    private var sampleScreenHeight = 1
    private var sampledAt = 0L
    private var feedContext: Context? = null
    private fun freshPixels(): Boolean = latestScreenBitmap?.isRecycled == false &&
        android.os.SystemClock.elapsedRealtime() - sampledAt < 2000L
    private val magnifierTicker = object : Runnable {
        override fun run() {
            if (!isMagnifierShowing) return
            samplePixelAtReticle()
            mainHandler.postDelayed(this, 500L)
        }
    }
    @Volatile
    var latestScreenBitmap: Bitmap? = null
        private set
    @Volatile
    var isMagnifierShowing = false
        private set

    // 3. Creator Watermark state
    private var watermarkView: LinearLayout? = null
    private var watermarkParams: WindowManager.LayoutParams? = null
    private var watermarkLabelTv: TextView? = null
    @Volatile
    var isWatermarkShowing = false
        private set

    private var hiddenForScreenshot = false

    private val teleprompterTicker = object : Runnable {
        override fun run() {
            if (!isTeleprompterShowing || !teleprompterScrolling) return
            val sv = teleprompterScrollView ?: return
            val ctx = sv.context
            val speed = SettingsStore.teleprompterSpeed(ctx)
            sv.scrollBy(0, speed.coerceIn(1, 6))
            mainHandler.postDelayed(this, 45L)
        }
    }

    /** Main-thread ownership transfer: at most one live bitmap is retained, never screenshot history. */
    fun updateLiveFrame(bitmap: Bitmap, screenWidth: Int, screenHeight: Int) {
        val previous = latestScreenBitmap
        latestScreenBitmap = bitmap
        sampleScreenWidth = screenWidth; sampleScreenHeight = screenHeight
        sampledAt = android.os.SystemClock.elapsedRealtime()
        previous?.takeUnless { it === bitmap || it.isRecycled }?.recycle()
        if (isMagnifierShowing) samplePixelAtReticle()
    }

    /** Fail closed: a missing/stale frame must never pretend the requested color matched. */
    fun doesScreenColorMatchIfAvailable(x: Int, y: Int, targetHex: String): Boolean {
        if (!freshPixels()) return false
        val bmp = latestScreenBitmap ?: return false
        if (x !in 0 until sampleScreenWidth || y !in 0 until sampleScreenHeight) return false
        val target = runCatching { Color.parseColor(targetHex) }.getOrNull() ?: return false
        val pixel = bmp.getPixel((x.toLong() * bmp.width / sampleScreenWidth).toInt().coerceIn(0, bmp.width - 1),
            (y.toLong() * bmp.height / sampleScreenHeight).toInt().coerceIn(0, bmp.height - 1))
        return abs(Color.red(pixel) - Color.red(target)) <= 38 &&
            abs(Color.green(pixel) - Color.green(target)) <= 38 && abs(Color.blue(pixel) - Color.blue(target)) <= 38
    }

    fun setHiddenForScreenshot(hidden: Boolean) {
        mainHandler.post {
            hiddenForScreenshot = hidden
            val vis = if (hidden) View.GONE else View.VISIBLE
            teleprompterView?.visibility = vis
            magnifierView?.visibility = vis
            watermarkView?.visibility = vis
        }
    }

    // ---------------------------------------------------------------- 1. Floating Teleprompter

    fun toggleTeleprompter(context: Context, onChanged: (() -> Unit)? = null) {
        if (isTeleprompterShowing) {
            hideTeleprompter()
            onChanged?.invoke()
        } else {
            showTeleprompter(context, onChanged)
        }
    }

    fun showTeleprompter(context: Context, onChanged: (() -> Unit)? = null) {
        val ctx = context.applicationContext
        if (!Settings.canDrawOverlays(ctx)) {
            CaptureIntents.toast(ctx, "Allow ScreenKit to display over other apps first.")
            return
        }
        mainHandler.post {
            val wm = (windowManager ?: ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?: return@post
            windowManager = wm
            if (isTeleprompterShowing && teleprompterView != null) {
                teleprompterTextView?.text = SettingsStore.teleprompterScript(ctx)
                onChanged?.invoke()
                return@post
            }
            val metrics = realDisplayMetrics(ctx, wm)
            val widthPx = (metrics.widthPixels * 0.78f).toInt().coerceAtMost(dp(ctx, 320))
            val heightPx = dp(ctx, 175)

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
                background = GradientDrawable().apply {
                    setColor(0xD910181C.toInt())
                    cornerRadius = dp(ctx, 16).toFloat()
                    setStroke(dp(ctx, 1.5f), 0xFFB7F36B.toInt())
                }
                elevation = dp(ctx, 12).toFloat()
            }

            val topBar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(ctx).apply {
                text = "⠿ TELEPROMPTER"
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFB7F36B.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            topBar.addView(title)

            lateinit var playBtn: TextView
            playBtn = makeSmallChip(ctx, "▶ Scroll", 0xFFB7F36B.toInt(), 0xFF10170D.toInt()) {
                teleprompterScrolling = !teleprompterScrolling
                playBtn.text = if (teleprompterScrolling) "⏸ Pause" else "▶ Scroll"
                mainHandler.removeCallbacks(teleprompterTicker)
                if (teleprompterScrolling) {
                    mainHandler.post(teleprompterTicker)
                }
            }
            topBar.addView(playBtn)

            topBar.addView(
                makeSmallChip(ctx, "↺", 0xFF1E2B30.toInt(), Color.WHITE) {
                    teleprompterScrollView?.smoothScrollTo(0, 0)
                },
            )
            topBar.addView(
                makeSmallChip(ctx, "A+", 0xFF1E2B30.toInt(), Color.WHITE) {
                    teleprompterTextSp = if (teleprompterTextSp >= 21f) 13f else teleprompterTextSp + 2f
                    teleprompterTextView?.textSize = teleprompterTextSp
                },
            )
            topBar.addView(
                makeSmallChip(ctx, "✕", 0xFF2B2024.toInt(), 0xFFFF8A93.toInt()) {
                    hideTeleprompter()
                    onChanged?.invoke()
                },
            )
            root.addView(topBar)

            val sv = ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ).apply { topMargin = dp(ctx, 6) }
            }
            val tv = TextView(ctx).apply {
                text = SettingsStore.teleprompterScript(ctx)
                textSize = teleprompterTextSp
                setTextColor(0xFFF1F6F2.toInt())
                setLineSpacing(0f, 1.25f)
            }
            sv.addView(tv)
            root.addView(sv)

            val params = WindowManager.LayoutParams(
                widthPx,
                heightPx,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = (metrics.widthPixels - widthPx) / 2
                y = dp(ctx, 76)
            }

            val drag = makeDragListener(ctx, root, params)
            topBar.setOnTouchListener(drag)
            title.setOnTouchListener(drag)

            teleprompterView = root
            teleprompterParams = params
            teleprompterScrollView = sv
            teleprompterTextView = tv
            isTeleprompterShowing = true
            runCatching { wm.addView(root, params) }
            ScreenKitAutoService.broadcastState(ctx)
            onChanged?.invoke()
        }
    }

    fun hideTeleprompter() {
        mainHandler.post {
            teleprompterScrolling = false
            mainHandler.removeCallbacks(teleprompterTicker)
            val wm = windowManager
            val ctx = teleprompterView?.context
            teleprompterView?.let { v -> runCatching { wm?.removeView(v) } }
            teleprompterView = null
            teleprompterParams = null
            teleprompterScrollView = null
            teleprompterTextView = null
            isTeleprompterShowing = false
            if (ctx != null) ScreenKitAutoService.broadcastState(ctx)
        }
    }

    // ---------------------------------------------------------------- 2. Magnifier & Color Picker

    fun toggleMagnifier(context: Context, onChanged: (() -> Unit)? = null) {
        if (isMagnifierShowing) {
            hideMagnifier()
            onChanged?.invoke()
        } else {
            if (!Settings.canDrawOverlays(context)) {
                CaptureIntents.toast(context, "Allow display over other apps before using Zoom.")
                return
            }
            val intent = android.content.Intent(context, com.screenkit.screenrecorder.CaptureRequestActivity::class.java)
                .putExtra(com.screenkit.screenrecorder.CaptureContract.EXTRA_OVERLAY_COMMAND, com.screenkit.screenrecorder.CaptureContract.COMMAND_TOGGLE_MAGNIFIER)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure { CaptureIntents.toast(context, "Open ScreenKit to approve live screen sharing for Zoom.") }
        }
    }

    fun showMagnifier(context: Context, onChanged: (() -> Unit)? = null) {
        val ctx = context.applicationContext
        feedContext = ctx
        if (!Settings.canDrawOverlays(ctx)) {
            CaptureIntents.toast(ctx, "Allow ScreenKit to display over other apps first.")
            return
        }
        mainHandler.post {
            val wm = (windowManager ?: ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?: return@post
            windowManager = wm
            if (isMagnifierShowing && magnifierView != null) {
                onChanged?.invoke()
                return@post
            }

            val metrics = realDisplayMetrics(ctx, wm)
            currentSampleX = metrics.widthPixels / 2
            currentSampleY = metrics.heightPixels / 2

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            val loupeSize = dp(ctx, 98)
            val loupe = LoupeReticleView(ctx)
            root.addView(loupe, LinearLayout.LayoutParams(loupeSize, loupeSize))

            val pill = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
                background = GradientDrawable().apply {
                    setColor(0xEE121A1F.toInt())
                    cornerRadius = dp(ctx, 13).toFloat()
                    setStroke(dp(ctx, 1), 0xFFB7F36B.toInt())
                }
            }

            val infoTv = TextView(ctx).apply {
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            magnifierInfoTv = infoTv
            pill.addView(infoTv)

            val btnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 4), 0, 0)
            }
            btnRow.addView(
                makeSmallChip(ctx, "📋 Hex", 0xFF1E2B30.toInt(), 0xFFB7F36B.toInt()) {
                    if (!freshPixels()) {
                        CaptureIntents.toast(ctx, "No fresh screen pixels. Reopen Zoom and approve screen sharing.")
                        return@makeSmallChip
                    }
                    val hex = String.format("#%06X", 0xFFFFFF and currentSampleColor)
                    val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cb?.setPrimaryClip(ClipData.newPlainText("ScreenKit Color", hex))
                    CaptureIntents.toast(ctx, "Copied $hex at ($currentSampleX, $currentSampleY)")
                },
            )
            btnRow.addView(
                makeSmallChip(ctx, "🎯 AutoPin", 0xFFB7F36B.toInt(), 0xFF10170D.toInt()) {
                    if (!freshPixels()) {
                        CaptureIntents.toast(ctx, "No fresh screen pixels. Reopen Zoom and approve screen sharing.")
                        return@makeSmallChip
                    }
                    val hex = String.format("#%06X", 0xFFFFFF and currentSampleColor)
                    SettingsStore.setAutoColorTriggerHex(ctx, hex)
                    SettingsStore.setAutoColorTriggerEnabled(ctx, true)
                    AutoClickScrollOverlay.refreshFromSettings(ctx)
                    ScreenKitAutoService.broadcastState(ctx)
                    CaptureIntents.toast(ctx, "Set Auto-Click Color Trigger to $hex!")
                },
            )
            btnRow.addView(
                makeSmallChip(ctx, "✕", 0xFF2B2024.toInt(), 0xFFFF8A93.toInt()) {
                    hideMagnifier()
                    onChanged?.invoke()
                },
            )
            pill.addView(btnRow)
            root.addView(pill)
            root.addView(TextView(ctx).apply {
                text = "▼ target: 20dp below this panel · 3× live"
                textSize = 9f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                setBackgroundColor(0xEE121A1F.toInt())
            })

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = (currentSampleX - loupeSize / 2).coerceAtLeast(0)
                y = (currentSampleY - loupeSize / 2).coerceAtLeast(0)
            }

            val drag = makeDragListener(ctx, root, params) { newX, newY ->
                params.y = newY.coerceAtMost((metrics.heightPixels - root.height - dp(ctx, 24)).coerceAtLeast(0))
                root.post { samplePixelAtReticle() }
            }
            loupe.setOnTouchListener(drag)
            pill.setOnTouchListener(drag)

            magnifierView = root
            magnifierParams = params
            loupeCanvasView = loupe
            isMagnifierShowing = true
            samplePixelAtReticle()
            runCatching { wm.addView(root, params) }.onFailure {
                hideMagnifier()
                CaptureIntents.toast(ctx, "Zoom overlay could not open. Check display-over-apps permission.")
            }
            root.post {
                params.y = params.y.coerceAtMost((metrics.heightPixels - root.height - dp(ctx, 24)).coerceAtLeast(0))
                runCatching { wm.updateViewLayout(root, params) }
                samplePixelAtReticle()
            }
            mainHandler.removeCallbacks(magnifierTicker)
            mainHandler.post(magnifierTicker)
            ScreenKitAutoService.broadcastState(ctx)
            onChanged?.invoke()
        }
    }

    private fun samplePixelAtReticle() {
        magnifierView?.let { root ->
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            currentSampleX = location[0] + root.width / 2
            currentSampleY = location[1] + root.height + dp(root.context, 20)
        }
        val bmp = latestScreenBitmap
        if (!freshPixels() || bmp == null || currentSampleX !in 0 until sampleScreenWidth || currentSampleY !in 0 until sampleScreenHeight) {
            currentSampleColor = Color.TRANSPARENT
            magnifierInfoTv?.text = "Waiting for live screen pixels…\nProtected content may remain black."
            loupeCanvasView?.invalidate()
            return
        }
        val sx = (currentSampleX.toLong() * bmp.width / sampleScreenWidth).toInt().coerceIn(0, bmp.width - 1)
        val sy = (currentSampleY.toLong() * bmp.height / sampleScreenHeight).toInt().coerceIn(0, bmp.height - 1)
        currentSampleColor = bmp.getPixel(sx, sy)
        val hex = String.format("#%06X", 0xFFFFFF and currentSampleColor)
        magnifierInfoTv?.text = "LIVE X:$currentSampleX Y:$currentSampleY · $hex\nRGB(${Color.red(currentSampleColor)}, ${Color.green(currentSampleColor)}, ${Color.blue(currentSampleColor)})"
        loupeCanvasView?.invalidate()
    }

    fun hideMagnifier(stopFeed: Boolean = true) {
        mainHandler.post {
            val ctx = feedContext ?: magnifierView?.context
            mainHandler.removeCallbacks(magnifierTicker)
            magnifierView?.let { v -> runCatching { windowManager?.removeView(v) } }
            magnifierView = null; magnifierParams = null; loupeCanvasView = null; magnifierInfoTv = null
            latestScreenBitmap?.takeUnless { it.isRecycled }?.recycle()
            latestScreenBitmap = null; sampledAt = 0L
            isMagnifierShowing = false
            if (ctx != null) {
                if (stopFeed) com.screenkit.screenrecorder.services.MagnifierCaptureService.stop(ctx)
                ScreenKitAutoService.broadcastState(ctx)
            }
            feedContext = null
        }
    }

    private class LoupeReticleView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            color = 0xFFB7F36B.toInt()
        }
        private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = Color.WHITE
        }
        private val srcRect = Rect()
        private val dstRect = Rect()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width * 0.5f
            val cy = height * 0.5f
            val radius = (width.coerceAtMost(height) * 0.44f)

            val bmp = latestScreenBitmap
            if (bmp != null && freshPixels()) {
                val sx = currentSampleX * bmp.width.toFloat() / sampleScreenWidth
                val sy = currentSampleY * bmp.height.toFloat() / sampleScreenHeight
                val halfX = (radius / 3f * bmp.width / sampleScreenWidth).coerceAtLeast(1f)
                val halfY = (radius / 3f * bmp.height / sampleScreenHeight).coerceAtLeast(1f)
                srcRect.set(
                    (sx - halfX).toInt().coerceIn(0, bmp.width - 1),
                    (sy - halfY).toInt().coerceIn(0, bmp.height - 1),
                    (sx + halfX).toInt().coerceIn(1, bmp.width),
                    (sy + halfY).toInt().coerceIn(1, bmp.height),
                )
                dstRect.set(
                    (cx - radius).toInt(),
                    (cy - radius).toInt(),
                    (cx + radius).toInt(),
                    (cy + radius).toInt(),
                )
                canvas.save()
                val clipPath = android.graphics.Path().apply {
                    addCircle(cx, cy, radius, android.graphics.Path.Direction.CW)
                }
                canvas.clipPath(clipPath)
                canvas.drawBitmap(bmp, srcRect, dstRect, null)
                canvas.restore()
            } else {
                swatchPaint.color = 0xBB10181C.toInt()
                canvas.drawCircle(cx, cy, radius, swatchPaint)
            }

            // Center color swatch ring
            swatchPaint.color = currentSampleColor
            canvas.drawCircle(cx, cy + radius * 0.55f, 8f * density, swatchPaint)
            canvas.drawCircle(cx, cy + radius * 0.55f, 8f * density, crossPaint)

            // Crosshair lines
            canvas.drawLine(cx - radius * 0.6f, cy, cx + radius * 0.6f, cy, crossPaint)
            canvas.drawLine(cx, cy - radius * 0.6f, cx, cy + radius * 0.6f, crossPaint)
            canvas.drawCircle(cx, cy, radius, ringPaint)
        }
    }

    // ---------------------------------------------------------------- 3. Creator Watermark Badge

    fun syncWatermark(context: Context) {
        val ctx = context.applicationContext
        if (SettingsStore.watermarkEnabled(ctx)) {
            showWatermark(ctx)
        } else {
            hideWatermark()
        }
    }

    fun toggleWatermark(context: Context, onChanged: (() -> Unit)? = null) {
        val ctx = context.applicationContext
        val next = !SettingsStore.watermarkEnabled(ctx)
        SettingsStore.setWatermarkEnabled(ctx, next)
        if (next) showWatermark(ctx) else hideWatermark()
        onChanged?.invoke()
    }

    fun showWatermark(context: Context) {
        val ctx = context.applicationContext
        if (!Settings.canDrawOverlays(ctx)) return
        mainHandler.post {
            val wm = (windowManager ?: ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?: return@post
            windowManager = wm
            if (isWatermarkShowing && watermarkView != null) {
                watermarkLabelTv?.text = "● ${SettingsStore.watermarkText(ctx)}"
                return@post
            }

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
                background = GradientDrawable().apply {
                    setColor(0x9910181C.toInt())
                    cornerRadius = dp(ctx, 14).toFloat()
                    setStroke(dp(ctx, 1), 0x88B7F36B.toInt())
                }
            }
            val tv = TextView(ctx).apply {
                text = "● ${SettingsStore.watermarkText(ctx)}"
                textSize = 11f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(0xEEFFFFFF.toInt())
            }
            watermarkLabelTv = tv
            root.addView(tv)

            val metrics = realDisplayMetrics(ctx, wm)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = dp(ctx, 16)
                y = (metrics.heightPixels - dp(ctx, 96)).coerceAtLeast(dp(ctx, 60))
            }

            root.setOnTouchListener(makeDragListener(ctx, root, params))
            watermarkView = root
            watermarkParams = params
            isWatermarkShowing = true
            runCatching { wm.addView(root, params) }
            ScreenKitAutoService.broadcastState(ctx)
        }
    }

    fun hideWatermark() {
        mainHandler.post {
            val wm = windowManager
            val ctx = watermarkView?.context
            watermarkView?.let { v -> runCatching { wm?.removeView(v) } }
            watermarkView = null
            watermarkParams = null
            watermarkLabelTv = null
            isWatermarkShowing = false
            if (ctx != null) ScreenKitAutoService.broadcastState(ctx)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun makeSmallChip(
        ctx: Context,
        label: String,
        bgColor: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(ctx, 9).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(ctx, 4) }
            setOnClickListener { onClick() }
        }
    }

    private fun makeDragListener(
        ctx: Context,
        view: View,
        params: WindowManager.LayoutParams,
        onMoved: ((Int, Int) -> Unit)? = null,
    ): View.OnTouchListener {
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                    }
                    if (dragging) {
                        val wm = windowManager ?: return@OnTouchListener true
                        val metrics = realDisplayMetrics(ctx, wm)
                        val maxX = (metrics.widthPixels - view.width.coerceAtLeast(dp(ctx, 60))).coerceAtLeast(0)
                        val maxY = (metrics.heightPixels - view.height.coerceAtLeast(dp(ctx, 40))).coerceAtLeast(0)
                        params.x = (startX + dx).coerceIn(0, maxX)
                        params.y = (startY + dy).coerceIn(0, maxY)
                        onMoved?.invoke(params.x, params.y)
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    true
                }
                else -> dragging
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    @Suppress("DEPRECATION")
    private fun realDisplayMetrics(ctx: Context, wm: WindowManager): DisplayMetrics {
        val metrics = DisplayMetrics()
        val display = wm.defaultDisplay
        if (display != null) {
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(ctx.resources.displayMetrics)
        }
        return metrics
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    private fun dp(ctx: Context, value: Float): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}
