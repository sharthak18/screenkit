package com.screenkit.screenrecorder.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.screenkit.screenrecorder.CaptureIntents
import com.screenkit.screenrecorder.data.AutoClickTargetSpec
import com.screenkit.screenrecorder.data.AutoMacroStep
import com.screenkit.screenrecorder.data.AutoScrollDirection
import com.screenkit.screenrecorder.data.AutoToolMode
import com.screenkit.screenrecorder.data.SettingsStore
import com.screenkit.screenrecorder.services.ScreenKitAutoService
import com.screenkit.screenrecorder.services.ShakeAndKeyStopDetector
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Interactive floating controller and target pin manager for:
 * 1. Multi-point sequential Auto Clicker (`1 → 2 → 3 → 4…`) with customizable delay between points,
 *    clicking speed (hold duration), taps-per-point, total repeat cycles / timer, anti-detection
 *    human jitter, and Smart Color Trigger.
 * 2. Directional Auto Scroller (`Down ⬇`, `Up ⬆`, `Left ⬅`, `Right ➡`) with customizable swipe
 *    speed (fast/slow), pause interval between scrolls, swipe distance, and scroll duration timer.
 * 3. Touch Macro Recorder & Replayer (`● Rec` / `▶ Play`) that records real finger taps & swipes
 *    with exact timing and replays them across any app.
 * 4. Saved Profiles / Presets (Slots 1, 2, 3).
 */
internal object AutoClickScrollOverlay {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var windowManager: WindowManager? = null

    private var controlBarView: LinearLayout? = null
    private var controlBarParams: WindowManager.LayoutParams? = null
    private var statusTextView: TextView? = null
    private var playStopBtnView: TextView? = null
    private var modeBtnView: TextView? = null

    private var settingsPanelView: FrameLayout? = null
    private var settingsPanelParams: WindowManager.LayoutParams? = null

    private var scrollGuideView: ScrollGuideView? = null
    private var scrollGuideParams: WindowManager.LayoutParams? = null

    private var macroRecorderView: MacroCaptureView? = null
    private var macroRecorderParams: WindowManager.LayoutParams? = null
    private val recordedMacroSteps = mutableListOf<AutoMacroStep>()

    private val targetPinHolders = mutableListOf<TargetPinHolder>()
    private var onStateChangedCallback: (() -> Unit)? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    @Volatile
    var isExecuting: Boolean = false
        private set

    @Volatile
    var isRecordingMacro: Boolean = false
        private set

    private var isTemporarilyHiddenForScreenshot = false
    private var activePointIndex = -1
    private var activeMacroStepIndex = 0
    private var currentTapWithinPoint = 0
    private var completedCycles = 0
    private var completedScrolls = 0
    private var executionStartedElapsedMs = 0L
    private val scrollPolicy = com.screenkit.screenrecorder.automation.SmartScrollPolicy()
    private var scrollHeld = false
    private var scrollNextRequested = false
    private var scrollAdvice = ""
    private var executionGeneration = 0L
    private var scrollGestureInFlight = false
    private var scrollCooldownUntil = 0L
    private var scrollAnchorX = 0
    private var scrollAnchorY = 0

    private data class TargetPinHolder(
        var spec: AutoClickTargetSpec,
        val view: TargetPinView,
        val params: WindowManager.LayoutParams,
    )

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isShowing || !isExecuting) return
            val ctx = appContext ?: return
            val autoService = ScreenKitAutoService.instance
            if (autoService == null) {
                stopExecution()
                CaptureIntents.toast(ctx, "Enable 'ScreenKit Auto Click & Scroll' in Accessibility Settings.")
                ScreenKitAutoService.openAccessibilitySettings(ctx)
                return
            }

            when (SettingsStore.autoToolMode(ctx)) {
                AutoToolMode.CLICK -> runNextClickStep(ctx, autoService)
                AutoToolMode.SCROLL -> runNextScrollStep(ctx, autoService)
                AutoToolMode.MACRO -> runNextMacroStep(ctx, autoService)
            }
        }
    }

    private val timerUiRunnable = object : Runnable {
        override fun run() {
            if (!isShowing || !isExecuting) return
            val ctx = appContext ?: return
            val mode = SettingsStore.autoToolMode(ctx)
            val elapsedSec = ((SystemClock.elapsedRealtime() - executionStartedElapsedMs) / 1000L).toInt()
            if (mode == AutoToolMode.SCROLL) {
                val maxDurSec = SettingsStore.autoScrollDurationSec(ctx)
                if (maxDurSec > 0 && elapsedSec >= maxDurSec) {
                    stopExecution()
                    CaptureIntents.toast(ctx, "Auto Scroll finished (${formatSeconds(maxDurSec)}).")
                    return
                }
            } else {
                val maxDurSec = SettingsStore.autoClickDurationSec(ctx)
                if (maxDurSec > 0 && elapsedSec >= maxDurSec) {
                    stopExecution()
                    CaptureIntents.toast(ctx, "Auto Click finished (${formatSeconds(maxDurSec)}).")
                    return
                }
            }
            updateStatusBanner()
            mainHandler.postDelayed(this, 500L)
        }
    }

    fun show(
        context: Context,
        initialMode: AutoToolMode? = null,
        onStateChanged: (() -> Unit)? = null,
    ) {
        val ctx = context.applicationContext
        appContext = ctx
        if (initialMode != null) {
            SettingsStore.setAutoToolMode(ctx, initialMode)
        }
        if (onStateChanged != null) {
            onStateChangedCallback = onStateChanged
        }
        if (!Settings.canDrawOverlays(ctx)) {
            CaptureIntents.toast(ctx, "Allow ScreenKit to display over other apps first.")
            return
        }
        ShakeAndKeyStopDetector.start(ctx)
        mainHandler.post {
            val wm = (windowManager ?: ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?: return@post
            windowManager = wm
            recordedMacroSteps.clear()
            recordedMacroSteps.addAll(SettingsStore.loadMacroSteps(ctx))
            if (isShowing) {
                rebuildTargetViews(ctx)
                rebuildControlBar(ctx)
                ScreenKitAutoService.broadcastState(ctx)
                onStateChangedCallback?.invoke()
                return@post
            }
            isShowing = true
            val metrics = realDisplayMetrics(ctx, wm)
            scrollAnchorX = metrics.widthPixels / 2
            scrollAnchorY = metrics.heightPixels / 2
            createControlBar(ctx, wm, metrics)
            rebuildTargetViews(ctx)
            ScreenKitAutoService.broadcastState(ctx)
            onStateChangedCallback?.invoke()
        }
    }

    fun toggle(context: Context, onStateChanged: (() -> Unit)? = null) {
        if (isShowing) {
            dismiss()
            onStateChanged?.invoke()
        } else {
            show(context, onStateChanged = onStateChanged)
        }
    }

    fun dismiss() {
        mainHandler.post {
            stopMacroRecordingInternal()
            stopExecutionInternal()
            closeSettingsPanel()
            val wm = windowManager
            controlBarView?.let { v -> runCatching { wm?.removeView(v) } }
            controlBarView = null
            controlBarParams = null
            statusTextView = null
            playStopBtnView = null
            modeBtnView = null

            scrollGuideView?.let { v -> runCatching { wm?.removeView(v) } }
            scrollGuideView = null
            scrollGuideParams = null

            clearTargetPinViews()
            isShowing = false
            appContext?.let { ScreenKitAutoService.broadcastState(it) }
            onStateChangedCallback?.invoke()
        }
    }

    fun stopExecution() {
        mainHandler.post {
            stopMacroRecordingInternal()
            stopExecutionInternal()
        }
    }

    fun refreshFromSettings(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        mainHandler.post {
            recordedMacroSteps.clear()
            recordedMacroSteps.addAll(SettingsStore.loadMacroSteps(ctx))
            if (!isShowing) return@post
            rebuildTargetViews(ctx)
            rebuildControlBar(ctx)
        }
    }

    fun setHiddenForScreenshot(hidden: Boolean) {
        mainHandler.post {
            isTemporarilyHiddenForScreenshot = hidden
            val vis = if (hidden) View.GONE else View.VISIBLE
            controlBarView?.visibility = vis
            settingsPanelView?.visibility = vis
            scrollGuideView?.visibility = vis
            macroRecorderView?.visibility = vis
            targetPinHolders.forEach { it.view.visibility = vis }
        }
    }

    // ---------------------------------------------------------------- jitter helpers

    private fun jitterCoord(value: Float, enabled: Boolean): Float {
        if (!enabled) return value
        return (value + Random.nextInt(-4, 5)).coerceAtLeast(2f)
    }

    private fun jitterDuration(ms: Long, enabled: Boolean): Long {
        if (!enabled) return ms
        val factor = 1.0 + Random.nextDouble(-0.12, 0.12)
        return (ms * factor).toLong().coerceAtLeast(20L)
    }

    private fun jitterDelay(ms: Long, enabled: Boolean): Long {
        if (!enabled) return ms
        val factor = 1.0 + Random.nextDouble(-0.15, 0.15)
        return (ms * factor).toLong().coerceAtLeast(35L)
    }

    // ---------------------------------------------------------------- execution loop

    private fun toggleExecution(ctx: Context) {
        if (isRecordingMacro) {
            stopMacroRecordingInternal()
            return
        }
        if (isExecuting) {
            stopExecutionInternal()
            return
        }
        val autoService = ScreenKitAutoService.instance
        if (autoService == null) {
            CaptureIntents.toast(
                ctx,
                "Please enable 'ScreenKit Auto Click & Scroll' in Accessibility Settings first.",
            )
            ScreenKitAutoService.openAccessibilitySettings(ctx)
            return
        }
        val mode = SettingsStore.autoToolMode(ctx)
        if (mode == AutoToolMode.MACRO && recordedMacroSteps.isEmpty()) {
            recordedMacroSteps.addAll(SettingsStore.loadMacroSteps(ctx))
            if (recordedMacroSteps.isEmpty()) {
                CaptureIntents.toast(ctx, "Tap '● Rec' first to record your touch macro actions!")
                return
            }
        }
        closeSettingsPanel()
        isExecuting = true
        activePointIndex = 0
        activeMacroStepIndex = 0
        currentTapWithinPoint = 0
        completedCycles = 0
        completedScrolls = 0
        executionStartedElapsedMs = SystemClock.elapsedRealtime()
        executionGeneration++
        scrollHeld = false; scrollNextRequested = false; scrollGestureInFlight = false
        scrollCooldownUntil = 0L
        scrollPolicy.reset(executionStartedElapsedMs)
        scrollAdvice = "Waiting before first scroll…"

        setPinsTouchable(false)
        scrollGuideView?.invalidate()
        rebuildControlBar(ctx)
        ScreenKitAutoService.broadcastState(ctx)
        onStateChangedCallback?.invoke()

        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.removeCallbacks(timerUiRunnable)
        mainHandler.postDelayed(tickRunnable, 120L)
        mainHandler.postDelayed(timerUiRunnable, 500L)
    }

    private fun stopExecutionInternal() {
        val wasRunning = isExecuting
        executionGeneration++
        scrollGestureInFlight = false
        isExecuting = false
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.removeCallbacks(timerUiRunnable)
        activePointIndex = -1
        activeMacroStepIndex = 0
        currentTapWithinPoint = 0
        targetPinHolders.forEach { holder ->
            holder.view.isFiringNow = false
            holder.view.invalidate()
        }
        scrollGuideView?.isFiringNow = false
        scrollGuideView?.invalidate()
        setPinsTouchable(true)
        val ctx = appContext
        if (ctx != null && isShowing) {
            rebuildControlBar(ctx)
        }
        if (wasRunning && ctx != null) {
            ScreenKitAutoService.broadcastState(ctx)
            onStateChangedCallback?.invoke()
        }
    }

    private fun runNextClickStep(ctx: Context, autoService: ScreenKitAutoService) {
        if (!isExecuting || targetPinHolders.isEmpty()) {
            stopExecutionInternal()
            return
        }
        if (activePointIndex !in targetPinHolders.indices) {
            activePointIndex = 0
        }
        val holder = targetPinHolders[activePointIndex]
        val jitter = SettingsStore.autoRandomJitter(ctx)

        // Smart Color Trigger check on Pin #1 when enabled and a screen color sample is available
        if (activePointIndex == 0 && SettingsStore.autoColorTriggerEnabled(ctx)) {
            val targetHex = SettingsStore.autoColorTriggerHex(ctx)
            val matches = CreatorToolsOverlay.doesScreenColorMatchIfAvailable(
                holder.spec.x,
                holder.spec.y,
                targetHex,
            )
            if (!matches) {
                updateStatusBanner()
                mainHandler.postDelayed(tickRunnable, 250L)
                return
            }
        }

        val globalTapsPerPoint = SettingsStore.autoClickTapsPerPoint(ctx)
        val tapsNeededAtPoint = if (holder.spec.customTaps > 0) {
            holder.spec.customTaps
        } else {
            globalTapsPerPoint
        }
        val clickSpeedMs = jitterDuration(SettingsStore.autoClickSpeedMs(ctx), jitter)
        val baseDelayMs = if (holder.spec.customDelayMs > 0L) {
            holder.spec.customDelayMs
        } else {
            SettingsStore.autoClickDelayMs(ctx)
        }
        val delayAfterMs = jitterDelay(baseDelayMs, jitter)

        targetPinHolders.forEachIndexed { idx, h ->
            val firing = idx == activePointIndex
            if (h.view.isFiringNow != firing) {
                h.view.isFiringNow = firing
                h.view.invalidate()
            }
        }

        val centerX = jitterCoord(holder.spec.x.toFloat(), jitter)
        val centerY = jitterCoord(holder.spec.y.toFloat(), jitter)
        updateStatusBanner()

        autoService.performTap(centerX, centerY, clickSpeedMs) {
            if (!isExecuting) return@performTap
            currentTapWithinPoint++
            if (currentTapWithinPoint < tapsNeededAtPoint) {
                val intraTapGap = (clickSpeedMs + 45L).coerceAtMost(delayAfterMs)
                mainHandler.postDelayed(tickRunnable, intraTapGap)
            } else {
                currentTapWithinPoint = 0
                activePointIndex++
                if (activePointIndex >= targetPinHolders.size) {
                    activePointIndex = 0
                    completedCycles++
                    val maxCycles = SettingsStore.autoClickTotalCycles(ctx)
                    if (maxCycles > 0 && completedCycles >= maxCycles) {
                        stopExecutionInternal()
                        CaptureIntents.toast(ctx, "Completed $completedCycles click cycle(s).")
                        return@performTap
                    }
                }
                updateStatusBanner()
                mainHandler.postDelayed(tickRunnable, delayAfterMs)
            }
        }
    }

    private fun runNextScrollStep(ctx: Context, autoService: ScreenKitAutoService) {
        if (!isExecuting || scrollGestureInFlight) return
        val now = SystemClock.elapsedRealtime()
        val smart = SettingsStore.autoScrollSmartEnabled(ctx)
        val advice = scrollPolicy.decide(now, SettingsStore.autoScrollIntervalMs(ctx), smart, scrollHeld,
            if (smart) autoService.observePlayback() else com.screenkit.screenrecorder.automation.PlaybackObservation())
        scrollAdvice = advice.message
        if (!scrollNextRequested && (now < scrollCooldownUntil || advice.decision != com.screenkit.screenrecorder.automation.ScrollDecision.NEXT)) {
            if (advice.decision == com.screenkit.screenrecorder.automation.ScrollDecision.SKIP_AD) autoService.observePlayback(clickSkip = true)
            updateStatusBanner()
            mainHandler.postDelayed(tickRunnable, 350L)
            return
        }
        scrollNextRequested = false
        val generation = executionGeneration
        val wm = windowManager ?: return
        val metrics = realDisplayMetrics(ctx, wm)
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()

        val jitter = SettingsStore.autoRandomJitter(ctx)
        val direction = SettingsStore.autoScrollDirection(ctx)
        val speedMs = jitterDuration(SettingsStore.autoScrollSpeedMs(ctx), jitter)
        // User-selected viewing time is exact; jitter applies only to the swipe, not the timer.
        val distFraction = (SettingsStore.autoScrollDistancePct(ctx) / 100f).coerceIn(0.20f, 0.85f)

        val anchorX = jitterCoord(scrollAnchorX.toFloat().coerceIn(screenW * 0.15f, screenW * 0.85f), jitter)
        val anchorY = jitterCoord(scrollAnchorY.toFloat().coerceIn(screenH * 0.20f, screenH * 0.80f), jitter)

        val halfSpanY = (screenH * distFraction * 0.5f).coerceAtLeast(120f)
        val halfSpanX = (screenW * distFraction * 0.5f).coerceAtLeast(100f)

        val (startX, startY, endX, endY) = when (direction) {
            AutoScrollDirection.DOWN -> {
                val sy = (anchorY + halfSpanY).coerceIn(screenH * 0.15f, screenH * 0.88f)
                val ey = (anchorY - halfSpanY).coerceIn(screenH * 0.12f, screenH * 0.85f)
                Quad(anchorX, sy, anchorX, ey)
            }
            AutoScrollDirection.UP -> {
                val sy = (anchorY - halfSpanY).coerceIn(screenH * 0.12f, screenH * 0.85f)
                val ey = (anchorY + halfSpanY).coerceIn(screenH * 0.15f, screenH * 0.88f)
                Quad(anchorX, sy, anchorX, ey)
            }
            AutoScrollDirection.LEFT -> {
                val sx = (anchorX + halfSpanX).coerceIn(screenW * 0.12f, screenW * 0.90f)
                val ex = (anchorX - halfSpanX).coerceIn(screenW * 0.10f, screenW * 0.88f)
                Quad(sx, anchorY, ex, anchorY)
            }
            AutoScrollDirection.RIGHT -> {
                val sx = (anchorX - halfSpanX).coerceIn(screenW * 0.10f, screenW * 0.88f)
                val ex = (anchorX + halfSpanX).coerceIn(screenW * 0.12f, screenW * 0.90f)
                Quad(sx, anchorY, ex, anchorY)
            }
        }

        scrollGuideView?.isFiringNow = true
        scrollGuideView?.invalidate()
        updateStatusBanner()

        scrollGestureInFlight = true
        autoService.performSwipe(startX, startY, endX, endY, speedMs) { success ->
            if (generation != executionGeneration) return@performSwipe
            scrollGestureInFlight = false
            scrollPolicy.reset(SystemClock.elapsedRealtime())
            scrollCooldownUntil = SystemClock.elapsedRealtime() + 1000L
            if (!success) scrollAdvice = "Swipe interrupted — waiting before retry"
            scrollGuideView?.isFiringNow = false
            scrollGuideView?.invalidate()
            if (!isExecuting) return@performSwipe

            completedScrolls++
            val maxScrolls = SettingsStore.autoScrollMaxCount(ctx)
            if (maxScrolls > 0 && completedScrolls >= maxScrolls) {
                stopExecutionInternal()
                CaptureIntents.toast(ctx, "Completed $completedScrolls auto-scroll(s).")
                return@performSwipe
            }
            updateStatusBanner()
            mainHandler.postDelayed(tickRunnable, 350L)
        }
    }

    private fun runNextMacroStep(ctx: Context, autoService: ScreenKitAutoService) {
        if (!isExecuting || recordedMacroSteps.isEmpty()) {
            stopExecutionInternal()
            return
        }
        if (activeMacroStepIndex !in recordedMacroSteps.indices) {
            activeMacroStepIndex = 0
        }
        val step = recordedMacroSteps[activeMacroStepIndex]
        val jitter = SettingsStore.autoRandomJitter(ctx)
        val x1 = jitterCoord(step.x1.toFloat(), jitter)
        val y1 = jitterCoord(step.y1.toFloat(), jitter)
        val x2 = jitterCoord(step.x2.toFloat(), jitter)
        val y2 = jitterCoord(step.y2.toFloat(), jitter)
        val dur = jitterDuration(step.durationMs, jitter)
        updateStatusBanner()

        val onDone: (Boolean) -> Unit = {
            if (isExecuting) {
                activeMacroStepIndex++
                if (activeMacroStepIndex >= recordedMacroSteps.size) {
                    activeMacroStepIndex = 0
                    completedCycles++
                    val maxCycles = SettingsStore.autoClickTotalCycles(ctx)
                    if (maxCycles > 0 && completedCycles >= maxCycles) {
                        stopExecutionInternal()
                        CaptureIntents.toast(ctx, "Macro finished ($completedCycles cycle(s)).")
                    } else {
                        val nextDelay = jitterDelay(recordedMacroSteps[0].delayBeforeMs.coerceAtLeast(250L), jitter)
                        mainHandler.postDelayed(tickRunnable, nextDelay)
                    }
                } else {
                    val nextDelay = jitterDelay(recordedMacroSteps[activeMacroStepIndex].delayBeforeMs, jitter)
                    mainHandler.postDelayed(tickRunnable, nextDelay)
                }
            }
        }

        if (step.isSwipe) {
            autoService.performSwipe(x1, y1, x2, y2, dur, onDone)
        } else {
            autoService.performTap(x1, y1, dur, onDone)
        }
    }

    private data class Quad(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    // ---------------------------------------------------------------- touch macro recording

    private fun toggleMacroRecording(ctx: Context) {
        if (isRecordingMacro) {
            stopMacroRecordingInternal()
            return
        }
        if (ScreenKitAutoService.instance == null) {
            CaptureIntents.toast(
                ctx,
                "Enable 'ScreenKit Auto Click & Scroll' in Accessibility Settings first.",
            )
            ScreenKitAutoService.openAccessibilitySettings(ctx)
            return
        }
        stopExecutionInternal()
        closeSettingsPanel()
        val wm = windowManager ?: return
        recordedMacroSteps.clear()
        isRecordingMacro = true

        val captureView = MacroCaptureView(ctx)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
        }
        macroRecorderView = captureView
        macroRecorderParams = params
        runCatching { wm.addView(captureView, params) }

        // Keep control bar on top of the macro capture layer
        controlBarView?.let { bar ->
            runCatching {
                wm.removeView(bar)
                wm.addView(bar, controlBarParams)
            }
        }
        rebuildControlBar(ctx)
        CaptureIntents.toast(ctx, "Recording Macro: Tap or swipe on screen, then tap '■ Done Rec'!")
    }

    private fun stopMacroRecordingInternal() {
        if (!isRecordingMacro && macroRecorderView == null) return
        isRecordingMacro = false
        val wm = windowManager
        macroRecorderView?.let { v -> runCatching { wm?.removeView(v) } }
        macroRecorderView = null
        macroRecorderParams = null
        val ctx = appContext
        if (ctx != null) {
            SettingsStore.saveMacroSteps(ctx, recordedMacroSteps)
            rebuildControlBar(ctx)
            ScreenKitAutoService.broadcastState(ctx)
            if (recordedMacroSteps.isNotEmpty()) {
                CaptureIntents.toast(ctx, "Saved ${recordedMacroSteps.size} macro action(s)! Tap ▶ Start to replay.")
            }
        }
    }

    private class MacroCaptureView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            color = 0xAAFF6671.toInt()
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xDDFF6671.toInt()
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        private var downX = 0f
        private var downY = 0f
        private var downAtMs = 0L
        private var lastStepEndedAtMs = SystemClock.elapsedRealtime()

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isRecordingMacro) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downAtMs = SystemClock.elapsedRealtime()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val upX = event.rawX
                    val upY = event.rawY
                    val now = SystemClock.elapsedRealtime()
                    val holdMs = (now - downAtMs).coerceIn(35L, 2_000L)
                    val delayBefore = (downAtMs - lastStepEndedAtMs).coerceIn(80L, 15_000L)
                    lastStepEndedAtMs = now
                    val dist = hypot((upX - downX).toDouble(), (upY - downY).toDouble()).toFloat()
                    val isSwipe = dist > 26f * density
                    val step = AutoMacroStep(
                        isSwipe = isSwipe,
                        x1 = downX.toInt(),
                        y1 = downY.toInt(),
                        x2 = upX.toInt(),
                        y2 = upY.toInt(),
                        durationMs = if (isSwipe) holdMs.coerceAtLeast(140L) else holdMs.coerceAtMost(350L),
                        delayBeforeMs = delayBefore,
                    )
                    recordedMacroSteps.add(step)
                    invalidate()
                    updateStatusBanner()

                    // Temporarily make this capture overlay pass-through so the live gesture reaches the app below!
                    val wm = windowManager
                    val params = macroRecorderParams
                    if (wm != null && params != null) {
                        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        runCatching { wm.updateViewLayout(this, params) }
                    }
                    val svc = ScreenKitAutoService.instance
                    val restoreTouchable: (Boolean) -> Unit = {
                        mainHandler.postDelayed({
                            if (isRecordingMacro && wm != null && params != null && macroRecorderView === this) {
                                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                                runCatching { wm.updateViewLayout(this, params) }
                            }
                        }, 45L)
                    }
                    if (svc != null) {
                        if (isSwipe) {
                            svc.performSwipe(downX, downY, upX, upY, step.durationMs, restoreTouchable)
                        } else {
                            svc.performTap(downX, downY, step.durationMs, restoreTouchable)
                        }
                    } else {
                        restoreTouchable(false)
                    }
                    return true
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)
            recordedMacroSteps.forEachIndexed { idx, s ->
                if (s.isSwipe) {
                    canvas.drawLine(s.x1.toFloat(), s.y1.toFloat(), s.x2.toFloat(), s.y2.toFloat(), borderPaint)
                }
                canvas.drawCircle(s.x1.toFloat(), s.y1.toFloat(), 14f * density, dotPaint)
                canvas.drawText("${idx + 1}", s.x1.toFloat(), s.y1.toFloat() + 4f * density, textPaint)
            }
        }
    }

    // ---------------------------------------------------------------- floating control bar

    @Suppress("DEPRECATION")
    private fun createControlBar(ctx: Context, wm: WindowManager, metrics: DisplayMetrics) {
        val type = overlayWindowType()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = dp(ctx, 12)
            y = (metrics.heightPixels * 0.14f).toInt().coerceAtLeast(dp(ctx, 72))
        }
        val bar = LinearLayout(ctx)
        controlBarView = bar
        controlBarParams = params
        rebuildControlBar(ctx)
        runCatching { wm.addView(bar, params) }
    }

    private fun rebuildControlBar(ctx: Context) {
        val bar = controlBarView ?: return
        val params = controlBarParams ?: return
        val wm = windowManager ?: return
        bar.removeAllViews()
        bar.orientation = LinearLayout.VERTICAL
        bar.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
        bar.background = GradientDrawable().apply {
            setColor(0xF2121A1F.toInt())
            cornerRadius = dp(ctx, 18).toFloat()
            setStroke(
                dp(ctx, 1.5f),
                if (isExecuting || isRecordingMacro) 0xFFFF6671.toInt() else 0xFFB7F36B.toInt(),
            )
        }
        bar.elevation = dp(ctx, 12).toFloat()

        val mode = SettingsStore.autoToolMode(ctx)

        // Top row: Drag handle + live status text
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, 4))
        }
        val dragGrip = TextView(ctx).apply {
            text = "⠿ "
            textSize = 13f
            setTextColor(0xFFB7F36B.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        headerRow.addView(dragGrip)

        val statusTv = TextView(ctx).apply {
            textSize = 10.5f
            maxWidth = (realDisplayMetrics(ctx, wm).widthPixels - dp(ctx, 48)).coerceAtLeast(dp(ctx, 180))
            maxLines = 3
            setTextColor(0xFFE6F0EC.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        statusTextView = statusTv
        headerRow.addView(statusTv)
        bar.addView(headerRow)

        // Action buttons row
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 1. Start / Stop button
        val playBtn = makeBarPillButton(
            ctx = ctx,
            label = if (isExecuting) "■ Stop" else "▶ Start",
            bgColor = if (isExecuting) 0xFFE54655.toInt() else 0xFFB7F36B.toInt(),
            textColor = if (isExecuting) Color.WHITE else 0xFF10170D.toInt(),
        ) {
            toggleExecution(ctx)
        }
        playStopBtnView = playBtn
        btnRow.addView(playBtn)

        when (mode) {
            AutoToolMode.CLICK -> {
                btnRow.addView(
                    makeBarPillButton(
                        ctx = ctx,
                        label = "＋Pt",
                        bgColor = 0xFF1F2D33.toInt(),
                        textColor = 0xFFB7F36B.toInt(),
                    ) {
                        if (isExecuting) stopExecutionInternal()
                        addNextTargetPin(ctx)
                    },
                )
                if (targetPinHolders.size > 1) {
                    btnRow.addView(
                        makeBarPillButton(
                            ctx = ctx,
                            label = "－Pt",
                            bgColor = 0xFF1F2D33.toInt(),
                            textColor = 0xFFD5E0DC.toInt(),
                        ) {
                            if (isExecuting) stopExecutionInternal()
                            removeLastTargetPin(ctx)
                        },
                    )
                }
            }
            AutoToolMode.SCROLL -> {
                if (isExecuting) {
                    val manualRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                    manualRow.addView(makeBarPillButton(ctx, if (scrollHeld) "▶ Resume" else "⏸ Hold", 0xFF1F2D33.toInt(), Color.WHITE) {
                        scrollHeld = !scrollHeld
                        rebuildControlBar(ctx)
                    })
                    manualRow.addView(makeBarPillButton(ctx, "Next ➜", 0xFF1F2D33.toInt(), Color.WHITE) {
                        scrollNextRequested = true
                        if (!scrollGestureInFlight) {
                            mainHandler.removeCallbacks(tickRunnable)
                            mainHandler.post(tickRunnable)
                        }
                    })
                    bar.addView(manualRow)
                }
                val dir = SettingsStore.autoScrollDirection(ctx)
                btnRow.addView(
                    makeBarPillButton(
                        ctx = ctx,
                        label = "${dir.arrow} ${dir.shortLabel}",
                        bgColor = 0xFF1F2D33.toInt(),
                        textColor = 0xFFB7F36B.toInt(),
                    ) {
                        val nextDir = dir.next()
                        SettingsStore.setAutoScrollDirection(ctx, nextDir)
                        scrollGuideView?.invalidate()
                        rebuildControlBar(ctx)
                        ScreenKitAutoService.broadcastState(ctx)
                    },
                )
            }
            AutoToolMode.MACRO -> {
                btnRow.addView(
                    makeBarPillButton(
                        ctx = ctx,
                        label = if (isRecordingMacro) "■ Done Rec" else "● Rec",
                        bgColor = if (isRecordingMacro) 0xFFE54655.toInt() else 0xFF2B2024.toInt(),
                        textColor = if (isRecordingMacro) Color.WHITE else 0xFFFF6671.toInt(),
                    ) {
                        toggleMacroRecording(ctx)
                    },
                )
            }
        }

        // Cycle Mode (Click -> Scroll -> Macro)
        val nextModeLabel = when (mode) {
            AutoToolMode.CLICK -> "⇅ Scroll"
            AutoToolMode.SCROLL -> "● Macro"
            AutoToolMode.MACRO -> "⊕ Click"
        }
        val modeBtn = makeBarPillButton(
            ctx = ctx,
            label = nextModeLabel,
            bgColor = 0xFF1F2D33.toInt(),
            textColor = 0xFF8FE0C1.toInt(),
        ) {
            if (isExecuting) stopExecutionInternal()
            if (isRecordingMacro) stopMacroRecordingInternal()
            val nextMode = when (mode) {
                AutoToolMode.CLICK -> AutoToolMode.SCROLL
                AutoToolMode.SCROLL -> AutoToolMode.MACRO
                AutoToolMode.MACRO -> AutoToolMode.CLICK
            }
            SettingsStore.setAutoToolMode(ctx, nextMode)
            rebuildTargetViews(ctx)
            rebuildControlBar(ctx)
            ScreenKitAutoService.broadcastState(ctx)
        }
        modeBtnView = modeBtn
        btnRow.addView(modeBtn)

        // Quick Settings Panel button
        btnRow.addView(
            makeBarPillButton(
                ctx = ctx,
                label = "⚙",
                bgColor = 0xFF1F2D33.toInt(),
                textColor = Color.WHITE,
            ) {
                if (isExecuting) stopExecutionInternal()
                if (isRecordingMacro) stopMacroRecordingInternal()
                toggleSettingsPanel(ctx)
            },
        )

        // Close button
        btnRow.addView(
            makeBarPillButton(
                ctx = ctx,
                label = "✕",
                bgColor = 0xFF2B2024.toInt(),
                textColor = 0xFFFF8A93.toInt(),
            ) {
                dismiss()
            },
        )

        bar.addView(btnRow)
        updateStatusBanner()

        val dragListener = makeWindowDragListener(ctx, bar, params)
        headerRow.setOnTouchListener(dragListener)
        bar.setOnTouchListener(dragListener)

        if (isTemporarilyHiddenForScreenshot) {
            bar.visibility = View.GONE
        }
        runCatching { wm.updateViewLayout(bar, params) }
    }

    private fun updateStatusBanner() {
        val ctx = appContext ?: return
        val tv = statusTextView ?: return
        val mode = SettingsStore.autoToolMode(ctx)
        when (mode) {
            AutoToolMode.CLICK -> {
                val count = targetPinHolders.size
                val delayMs = SettingsStore.autoClickDelayMs(ctx)
                val speedMs = SettingsStore.autoClickSpeedMs(ctx)
                val tapsPerPt = SettingsStore.autoClickTapsPerPoint(ctx)
                val maxCycles = SettingsStore.autoClickTotalCycles(ctx)
                val cycleLabel = if (maxCycles == 0) "∞" else "${completedCycles}/$maxCycles"
                tv.text = if (isExecuting) {
                    val stepNum = (activePointIndex + 1).coerceIn(1, count.coerceAtLeast(1))
                    "CLICKING #$stepNum/$count · Cycle $cycleLabel · ${delayMs}ms gap"
                } else {
                    "Auto Click: 1→$count pts · ${tapsPerPt}×/pt · ${delayMs}ms delay · ${speedMs}ms tap"
                }
            }
            AutoToolMode.SCROLL -> {
                val dir = SettingsStore.autoScrollDirection(ctx)
                val speedMs = SettingsStore.autoScrollSpeedMs(ctx)
                val intervalMs = SettingsStore.autoScrollIntervalMs(ctx)
                val durSec = SettingsStore.autoScrollDurationSec(ctx)
                if (isExecuting) {
                    val elapsedSec = ((SystemClock.elapsedRealtime() - executionStartedElapsedMs) / 1000L).toInt()
                    val timeLabel = if (durSec > 0) {
                        "${formatSeconds((durSec - elapsedSec).coerceAtLeast(0))} left"
                    } else {
                        "${formatSeconds(elapsedSec)} (∞)"
                    }
                    tv.text = "${dir.arrow} #$completedScrolls · $timeLabel\n$scrollAdvice"
                } else {
                    val durLabel = if (durSec == 0) "∞" else formatSeconds(durSec)
                    tv.text = "${dir.arrow} ${dir.label} · ${speedMs}ms swipe · ${intervalMs}ms gap · $durLabel"
                }
            }
            AutoToolMode.MACRO -> {
                val stepCount = recordedMacroSteps.size
                tv.text = when {
                    isRecordingMacro -> "● REC MACRO: $stepCount action(s) recorded — tap '■ Done Rec' when done"
                    isExecuting -> "PLAYING MACRO: step ${activeMacroStepIndex + 1}/$stepCount · cycle ${completedCycles + 1}"
                    stepCount > 0 -> "Touch Macro Ready: $stepCount recorded action(s) · Tap ▶ Start"
                    else -> "Touch Macro: Tap '● Rec' to record your taps & swipes"
                }
            }
        }
    }

    private fun makeBarPillButton(
        ctx: Context,
        label: String,
        bgColor: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 11.5f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(ctx, 11).toFloat()
                setStroke(dp(ctx, 1), 0x44FFFFFF)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                rightMargin = dp(ctx, 5)
            }
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    // ---------------------------------------------------------------- numbered target pins (1, 2, 3, 4...)

    private fun rebuildTargetViews(ctx: Context) {
        val wm = windowManager ?: return
        clearTargetPinViews()
        scrollGuideView?.let { v -> runCatching { wm.removeView(v) } }
        scrollGuideView = null
        scrollGuideParams = null

        val metrics = realDisplayMetrics(ctx, wm)
        when (SettingsStore.autoToolMode(ctx)) {
            AutoToolMode.CLICK -> {
                val specs = SettingsStore.loadAutoClickTargets(ctx, metrics.widthPixels, metrics.heightPixels)
                specs.forEach { spec ->
                    addTargetPinView(ctx, wm, spec)
                }
            }
            AutoToolMode.SCROLL -> {
                addScrollGuideView(ctx, wm, metrics)
            }
            AutoToolMode.MACRO -> {
                // Macro mode uses recorded steps
            }
        }
    }

    private fun addTargetPinView(ctx: Context, wm: WindowManager, spec: AutoClickTargetSpec) {
        val pinSizePx = dp(ctx, PIN_SIZE_DP)
        val halfPin = pinSizePx / 2
        val view = TargetPinView(ctx, spec)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (isExecuting) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)

        val params = WindowManager.LayoutParams(
            pinSizePx,
            pinSizePx,
            overlayWindowType(),
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (spec.x - halfPin).coerceAtLeast(0)
            y = (spec.y - halfPin).coerceAtLeast(0)
        }

        val holder = TargetPinHolder(spec, view, params)
        view.setOnTouchListener(makePinDragListener(ctx, holder))
        runCatching {
            wm.addView(view, params)
            targetPinHolders.add(holder)
        }
    }

    private fun addNextTargetPin(ctx: Context) {
        val wm = windowManager ?: return
        val metrics = realDisplayMetrics(ctx, wm)
        val nextIndex = targetPinHolders.size + 1
        if (nextIndex > 20) {
            CaptureIntents.toast(ctx, "Maximum 20 sequential click points reached.")
            return
        }
        val offset = ((nextIndex - 1) % 6) * dp(ctx, 32) - dp(ctx, 64)
        val newSpec = AutoClickTargetSpec(
            index = nextIndex,
            x = (metrics.widthPixels / 2 + offset).coerceIn(dp(ctx, 40), metrics.widthPixels - dp(ctx, 40)),
            y = (metrics.heightPixels / 2 + offset).coerceIn(dp(ctx, 90), metrics.heightPixels - dp(ctx, 90)),
        )
        addTargetPinView(ctx, wm, newSpec)
        persistCurrentTargetSpecs(ctx)
        rebuildControlBar(ctx)
        ScreenKitAutoService.broadcastState(ctx)
    }

    private fun removeLastTargetPin(ctx: Context) {
        if (targetPinHolders.size <= 1) return
        val wm = windowManager ?: return
        val removed = targetPinHolders.removeAt(targetPinHolders.lastIndex)
        runCatching { wm.removeView(removed.view) }
        persistCurrentTargetSpecs(ctx)
        rebuildControlBar(ctx)
        ScreenKitAutoService.broadcastState(ctx)
    }

    private fun persistCurrentTargetSpecs(ctx: Context) {
        val specs = targetPinHolders.mapIndexed { i, h ->
            h.spec = h.spec.copy(index = i + 1)
            h.view.spec = h.spec
            h.view.invalidate()
            h.spec
        }
        SettingsStore.saveAutoClickTargets(ctx, specs)
    }

    private fun setPinsTouchable(touchable: Boolean) {
        val wm = windowManager ?: return
        targetPinHolders.forEach { holder ->
            holder.params.flags = if (touchable) {
                holder.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                holder.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            runCatching { wm.updateViewLayout(holder.view, holder.params) }
        }
        val sgView = scrollGuideView
        val sgParams = scrollGuideParams
        if (sgView != null && sgParams != null) {
            sgParams.flags = if (touchable) {
                sgParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                sgParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            runCatching { wm.updateViewLayout(sgView, sgParams) }
        }
    }

    private fun clearTargetPinViews() {
        val wm = windowManager
        targetPinHolders.forEach { holder ->
            runCatching { wm?.removeView(holder.view) }
        }
        targetPinHolders.clear()
    }

    private fun makePinDragListener(ctx: Context, holder: TargetPinHolder): View.OnTouchListener {
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        val halfPin = dp(ctx, PIN_SIZE_DP) / 2
        var startParamX = 0
        var startParamY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false
        return View.OnTouchListener { _, event ->
            if (isExecuting) return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startParamX = holder.params.x
                    startParamY = holder.params.y
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
                        val maxX = (metrics.widthPixels - dp(ctx, PIN_SIZE_DP)).coerceAtLeast(0)
                        val maxY = (metrics.heightPixels - dp(ctx, PIN_SIZE_DP)).coerceAtLeast(0)
                        holder.params.x = (startParamX + dx).coerceIn(0, maxX)
                        holder.params.y = (startParamY + dy).coerceIn(0, maxY)
                        holder.spec = holder.spec.copy(
                            x = holder.params.x + halfPin,
                            y = holder.params.y + halfPin,
                        )
                        holder.view.spec = holder.spec
                        runCatching { wm.updateViewLayout(holder.view, holder.params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        persistCurrentTargetSpecs(ctx)
                    } else {
                        val nextTaps = when (holder.spec.customTaps) {
                            0 -> 2
                            2 -> 3
                            3 -> 5
                            5 -> 10
                            else -> 0
                        }
                        holder.spec = holder.spec.copy(customTaps = nextTaps)
                        holder.view.spec = holder.spec
                        holder.view.invalidate()
                        persistCurrentTargetSpecs(ctx)
                        val label = if (nextTaps == 0) {
                            "Point #${holder.spec.index}: uses global tap count (${SettingsStore.autoClickTapsPerPoint(ctx)}×)"
                        } else {
                            "Point #${holder.spec.index}: set to tap ${nextTaps}× before moving to next point"
                        }
                        CaptureIntents.toast(ctx, label)
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ---------------------------------------------------------------- scroll guide indicator

    private fun addScrollGuideView(ctx: Context, wm: WindowManager, metrics: DisplayMetrics) {
        val w = dp(ctx, 72)
        val h = dp(ctx, 136)
        val guide = ScrollGuideView(ctx)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (isExecuting) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        val params = WindowManager.LayoutParams(
            w,
            h,
            overlayWindowType(),
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (scrollAnchorX - w / 2).coerceIn(0, (metrics.widthPixels - w).coerceAtLeast(0))
            y = (scrollAnchorY - h / 2).coerceIn(0, (metrics.heightPixels - h).coerceAtLeast(0))
        }
        guide.setOnTouchListener(
            makeWindowDragListener(ctx, guide, params) { newX, newY ->
                scrollAnchorX = newX + w / 2
                scrollAnchorY = newY + h / 2
            },
        )
        scrollGuideView = guide
        scrollGuideParams = params
        runCatching { wm.addView(guide, params) }
    }

    // ---------------------------------------------------------------- in-overlay quick settings panel

    private fun toggleSettingsPanel(ctx: Context) {
        if (settingsPanelView != null) {
            closeSettingsPanel()
        } else {
            openSettingsPanel(ctx)
        }
    }

    private fun closeSettingsPanel() {
        val wm = windowManager
        settingsPanelView?.let { v -> runCatching { wm?.removeView(v) } }
        settingsPanelView = null
        settingsPanelParams = null
    }

    private fun openSettingsPanel(ctx: Context) {
        val wm = windowManager ?: return
        closeSettingsPanel()
        val metrics = realDisplayMetrics(ctx, wm)
        val cardWidth = (metrics.widthPixels * 0.90f).toInt().coerceAtMost(dp(ctx, 370))

        val container = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                setColor(0xF5121A1F.toInt())
                cornerRadius = dp(ctx, 20).toFloat()
                setStroke(dp(ctx, 1.5f), 0xFFB7F36B.toInt())
            }
            elevation = dp(ctx, 18).toFloat()
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }

        val scroll = ScrollView(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(col)
        container.addView(scroll)

        fun populatePanel() {
            col.removeAllViews()
            val mode = SettingsStore.autoToolMode(ctx)

            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(ctx).apply {
                text = when (mode) {
                    AutoToolMode.CLICK -> "AUTO CLICKER (1 → 2 → 3…)"
                    AutoToolMode.SCROLL -> "AUTO SCROLLER (↑ / ↓)"
                    AutoToolMode.MACRO -> "TOUCH MACRO RECORDER"
                }
                textSize = 12f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(0xFFB7F36B.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            topRow.addView(title)

            val doneBtn = makeBarPillButton(ctx, "Done ✓", 0xFFB7F36B.toInt(), 0xFF10170D.toInt()) {
                closeSettingsPanel()
                rebuildTargetViews(ctx)
                rebuildControlBar(ctx)
            }
            topRow.addView(doneBtn)
            col.addView(topRow)

            // Mode switcher row
            addOptionRow(
                ctx = ctx,
                parent = col,
                label = "Automation Mode",
                choices = listOf(
                    "⊕ Click (1→2→3…)" to (mode == AutoToolMode.CLICK),
                    "⇅ Scroll (↑/↓)" to (mode == AutoToolMode.SCROLL),
                    "● Macro Rec" to (mode == AutoToolMode.MACRO),
                ),
            ) { idx ->
                val chosen = when (idx) {
                    0 -> AutoToolMode.CLICK
                    1 -> AutoToolMode.SCROLL
                    else -> AutoToolMode.MACRO
                }
                SettingsStore.setAutoToolMode(ctx, chosen)
                rebuildTargetViews(ctx)
                rebuildControlBar(ctx)
                ScreenKitAutoService.broadcastState(ctx)
                populatePanel()
            }

            // Saved Profiles (Slots 1, 2, 3)
            addOptionRow(
                ctx = ctx,
                parent = col,
                label = "Quick Load / Save Profile Presets",
                choices = listOf(
                    "▶ Slot 1" to false,
                    "▶ Slot 2" to false,
                    "▶ Slot 3" to false,
                    "💾 Save→1" to false,
                    "💾 Save→2" to false,
                    "💾 Save→3" to false,
                ),
            ) { idx ->
                if (idx in 0..2) {
                    val slot = idx + 1
                    val name = SettingsStore.loadProfileSlot(ctx, slot)
                    rebuildTargetViews(ctx)
                    rebuildControlBar(ctx)
                    ScreenKitAutoService.broadcastState(ctx)
                    if (name != null) CaptureIntents.toast(ctx, "Loaded: $name")
                    populatePanel()
                } else {
                    val slot = idx - 2
                    SettingsStore.saveCurrentToProfileSlot(ctx, slot)
                    ScreenKitAutoService.broadcastState(ctx)
                    CaptureIntents.toast(ctx, "Saved current layout & settings to Slot $slot!")
                    populatePanel()
                }
            }

            // Anti-detection randomization & Smart Color Trigger
            val jitterOn = SettingsStore.autoRandomJitter(ctx)
            val colorTriggerOn = SettingsStore.autoColorTriggerEnabled(ctx)
            addOptionRow(
                ctx = ctx,
                parent = col,
                label = "Smart Protection & Triggers",
                choices = listOf(
                    "🎲 Human Jitter: ${if (jitterOn) "ON" else "OFF"}" to jitterOn,
                    "🎨 Color Trigger (${SettingsStore.autoColorTriggerHex(ctx)}): ${if (colorTriggerOn) "ON" else "OFF"}" to colorTriggerOn,
                ),
            ) { idx ->
                if (idx == 0) {
                    SettingsStore.setAutoRandomJitter(ctx, !jitterOn)
                } else {
                    SettingsStore.setAutoColorTriggerEnabled(ctx, !colorTriggerOn)
                }
                rebuildTargetViews(ctx)
                ScreenKitAutoService.broadcastState(ctx)
                populatePanel()
            }

            if (mode == AutoToolMode.CLICK) {
                val curPts = targetPinHolders.size.coerceAtLeast(SettingsStore.autoClickPointCount(ctx))
                val ptOptions = listOf(1, 2, 3, 4, 5, 6, 8, 10)
                addOptionRow(
                    ctx = ctx,
                    parent = col,
                    label = "Number of Click Positions (1 → 2 → 3…): $curPts",
                    choices = ptOptions.map { "${it} pt" to (curPts == it) },
                ) { idx ->
                    val count = ptOptions[idx]
                    SettingsStore.setAutoClickPointCount(ctx, count)
                    rebuildTargetViews(ctx)
                    rebuildControlBar(ctx)
                    ScreenKitAutoService.broadcastState(ctx)
                    populatePanel()
                }

                val curDelay = SettingsStore.autoClickDelayMs(ctx)
                val delayOptions = listOf(
                    100L to "100ms",
                    250L to "250ms",
                    500L to "0.5s",
                    1000L to "1s",
                    2000L to "2s",
                    5000L to "5s",
                )
                addOptionRow(
                    ctx = ctx,
                    parent = col,
                    label = "Delay Between Clicks (1 → 2 → 3): ${curDelay}ms",
                    choices = delayOptions.map { it.second to (curDelay == it.first) },
                ) { idx ->
                    SettingsStore.setAutoClickDelayMs(ctx, delayOptions[idx].first)
                    rebuildControlBar(ctx)
                    ScreenKitAutoService.broadcastState(ctx)
                    populatePanel()
                }

                val curSpeed = SettingsStore.autoClickSpeedMs(ctx)
                val speedOptions = listOf(
                    25L to "Turbo 25ms",
                    55L to "Fast 55ms",
                    110L to "Normal 110ms",
                    250L to "Slow 250ms",
                    600L to "Hold 600ms",
                )
                addOptionRow(
                    ctx = ctx,
                    parent = col,
                    label = "Clicking Speed (Tap Hold): ${curSpeed}ms",
                    choices = speedOptions.map { it.second to (curSpeed == it.first) },
                ) { idx ->
                    SettingsStore.setAutoClickSpeedMs(ctx, speedOptions[idx].first)
                    rebuildControlBar(ctx)
                    ScreenKitAutoService.broadcastState(ctx)
                    populatePanel()
                }

                val curTaps = SettingsStore.autoClickTapsPerPoint(ctx)
                val tapOptions = listOf(1, 2, 3, 5, 10, 20)
                addOptionRow(
                    ctx = ctx,
                    parent = col,
                    label = "Taps at Each Position Before Next: ${curTaps}×",
                    choices = tapOptions.map { "${it}×" to (curTaps == it) },
                ) { idx ->
                    SettingsStore.setAutoClickTapsPerPoint(ctx, tapOptions[idx])
                    rebuildControlBar(ctx)
                    ScreenKitAutoService.broadcastState(ctx)
                    populatePanel()
                }

                val curCycles = SettingsStore.autoClickTotalCycles(ctx)
                val cycleOptions = listOf(
                    1 to "1 cycle",
                    5 to "5×",
                    10 to "10×",
                    25 to "25×",
                    100 to "100×",
                    0 to "Infinite ∞",
                )
                addOptionRow(
                    ctx = ctx,
                    parent = col,
                    label = "Total Sequence Repeats: ${if (curCycles == 0) "Infinite ∞" else "${curCycles}×"}",
                    choices = cycleOptions.map { it.second to (curCycles == it.first) },
                ) { idx ->
                    SettingsStore.setAutoClickTotalCycles(ctx, cycleOptions[idx].first)
                    rebuildControlBar(ctx)
                    ScreenKitAutoService.broadcastState(ctx)
                    populatePanel()
                }
            } else if (mode == AutoToolMode.SCROLL) {
                val curDir = SettingsStore.autoScrollDirection(ctx)
                val dirOptions = AutoScrollDirection.values().toList()
                addOptionRow(
                    ctx = ctx,
                    parent = col,
                    label = "Scroll Direction: ${curDir.label}",
                    choices = dirOptions.map { "${it.arrow} ${it.shortLabel}" to (curDir == it) },
                ) { idx ->
                    SettingsStore.setAutoScrollDirection(ctx, dirOptions[idx])
                    scrollGuideView?.invalidate()
                    rebuildControlBar(ctx)
                    ScreenKitAutoService.broadcastState(ctx)
                    populatePanel()
                }

                val curSpeed = SettingsStore.autoScrollSpeedMs(ctx)
                val speedOptions = listOf(
                    160L to "⚡ Very Fast",
                    300L to "Fast",
                    450L to "Normal",
                    850L to "Slow",
                    1400L to "🐢 Reading",
                )
                addOptionRow(
                    ctx = ctx,
                    parent = col,
                    label = "Scroll Swipe Speed: ${curSpeed}ms",
                    choices = speedOptions.map { it.second to (curSpeed == it.first) },
                ) { idx ->
                    SettingsStore.setAutoScrollSpeedMs(ctx, speedOptions[idx].first)
                    rebuildControlBar(ctx)
                    ScreenKitAutoService.broadcastState(ctx)
                    populatePanel()
                }

                val curInterval = SettingsStore.autoScrollIntervalMs(ctx)
                val intervalOptions = listOf(
                    600L to "0.6s",
                    1000L to "1s",
                    1500L to "1.5s",
                    2500L to "2.5s",
                    4000L to "4s",
                    8000L to "8s",
                    30000L to "30s",
                    60000L to "60s",
                    120000L to "120s",
                )
                addOptionRow(
                    ctx = ctx,
                    parent = col,
                    label = "Pause Between Scrolls: ${curInterval}ms",
                    choices = intervalOptions.map { it.second to (curInterval == it.first) },
                ) { idx ->
                    SettingsStore.setAutoScrollIntervalMs(ctx, intervalOptions[idx].first)
                    rebuildControlBar(ctx)
                    ScreenKitAutoService.broadcastState(ctx)
                    populatePanel()
                }

                col.addView(makeBarPillButton(ctx, "Custom seconds / Smart playback", 0xFF1F2D33.toInt(), Color.WHITE) {
                    if (isExecuting) stopExecutionInternal()
                    ScrollPreferencesDialog.show(ctx) {
                        rebuildControlBar(ctx)
                        ScreenKitAutoService.broadcastState(ctx)
                        populatePanel()
                    }
                })
                val curDur = SettingsStore.autoScrollDurationSec(ctx)
                val durOptions = listOf(
                    30 to "30s",
                    60 to "1m",
                    120 to "2m",
                    300 to "5m",
                    600 to "10m",
                    0 to "Infinite ∞",
                )
                addOptionRow(
                    ctx = ctx,
                    parent = col,
                    label = "How Long to Scroll: ${if (curDur == 0) "Infinite ∞" else formatSeconds(curDur)}",
                    choices = durOptions.map { it.second to (curDur == it.first) },
                ) { idx ->
                    SettingsStore.setAutoScrollDurationSec(ctx, durOptions[idx].first)
                    rebuildControlBar(ctx)
                    ScreenKitAutoService.broadcastState(ctx)
                    populatePanel()
                }

                val curDist = SettingsStore.autoScrollDistancePct(ctx)
                val distOptions = listOf(
                    30 to "Short 30%",
                    55 to "Medium 55%",
                    75 to "Long 75%",
                )
                addOptionRow(
                    ctx = ctx,
                    parent = col,
                    label = "Scroll Distance: $curDist%",
                    choices = distOptions.map { it.second to (curDist == it.first) },
                ) { idx ->
                    SettingsStore.setAutoScrollDistancePct(ctx, distOptions[idx].first)
                    rebuildControlBar(ctx)
                    ScreenKitAutoService.broadcastState(ctx)
                    populatePanel()
                }
            } else {
                // Macro settings
                val curCycles = SettingsStore.autoClickTotalCycles(ctx)
                val cycleOptions = listOf(
                    1 to "1×",
                    5 to "5×",
                    10 to "10×",
                    25 to "25×",
                    100 to "100×",
                    0 to "Infinite ∞",
                )
                addOptionRow(
                    ctx = ctx,
                    parent = col,
                    label = "Macro Replay Count (${recordedMacroSteps.size} recorded actions): ${if (curCycles == 0) "Infinite ∞" else "${curCycles}×"}",
                    choices = cycleOptions.map { it.second to (curCycles == it.first) },
                ) { idx ->
                    SettingsStore.setAutoClickTotalCycles(ctx, cycleOptions[idx].first)
                    rebuildControlBar(ctx)
                    ScreenKitAutoService.broadcastState(ctx)
                    populatePanel()
                }
            }
        }

        populatePanel()

        val params = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        settingsPanelView = container
        settingsPanelParams = params
        runCatching { wm.addView(container, params) }
    }

    private fun addOptionRow(
        ctx: Context,
        parent: LinearLayout,
        label: String,
        choices: List<Pair<String, Boolean>>,
        onPick: (Int) -> Unit,
    ) {
        val labelTv = TextView(ctx).apply {
            text = label
            textSize = 10.5f
            setTextColor(0xFFC3D0CA.toInt())
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
        }
        parent.addView(labelTv)

        val hsv = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        choices.forEachIndexed { idx, (text, selected) ->
            val chip = makeBarPillButton(
                ctx = ctx,
                label = text,
                bgColor = if (selected) 0xFFB7F36B.toInt() else 0xFF1E2A30.toInt(),
                textColor = if (selected) 0xFF10170D.toInt() else 0xFFE6F0EC.toInt(),
            ) {
                onPick(idx)
            }
            row.addView(chip)
        }
        hsv.addView(row)
        parent.addView(hsv)
    }

    // ---------------------------------------------------------------- custom views

    /**
     * Circular crosshair target pin labeled 1, 2, 3, 4...
     */
    private class TargetPinView(
        context: Context,
        var spec: AutoClickTargetSpec,
    ) : View(context) {
        var isFiringNow: Boolean = false
        private val density = context.resources.displayMetrics.density
        private val outerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        }
        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * density
            color = 0xAAFFFFFF.toInt()
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF10170D.toInt()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width * 0.5f
            val cy = height * 0.5f
            val radius = (width.coerceAtMost(height) * 0.42f)

            outerFillPaint.color = if (isFiringNow) {
                0xCCFF6671.toInt()
            } else {
                0xCC162429.toInt()
            }
            ringPaint.color = if (isFiringNow) {
                Color.WHITE
            } else if (spec.index == 1 && SettingsStore.autoColorTriggerEnabled(context)) {
                runCatching { Color.parseColor(SettingsStore.autoColorTriggerHex(context)) }
                    .getOrDefault(0xFFB7F36B.toInt())
            } else {
                0xFFB7F36B.toInt()
            }

            canvas.drawCircle(cx, cy, radius, outerFillPaint)
            canvas.drawCircle(cx, cy, radius, ringPaint)

            canvas.drawLine(cx - radius, cy, cx - radius * 0.55f, cy, crosshairPaint)
            canvas.drawLine(cx + radius * 0.55f, cy, cx + radius, cy, crosshairPaint)
            canvas.drawLine(cx, cy - radius, cx, cy - radius * 0.55f, crosshairPaint)
            canvas.drawLine(cx, cy + radius * 0.55f, cx, cy + radius, crosshairPaint)

            textPaint.color = if (isFiringNow) Color.WHITE else 0xFFB7F36B.toInt()
            textPaint.textSize = 15f * density
            val textY = cy - (textPaint.descent() + textPaint.ascent()) * 0.5f
            canvas.drawText(spec.index.toString(), cx, textY, textPaint)

            if (spec.customTaps > 1) {
                val bx = cx + radius * 0.68f
                val by = cy - radius * 0.68f
                val br = 8.5f * density
                canvas.drawCircle(bx, by, br, badgeFillPaint)
                canvas.drawCircle(bx, by, br, ringPaint)
                textPaint.color = Color.WHITE
                textPaint.textSize = 8.5f * density
                val byText = by - (textPaint.descent() + textPaint.ascent()) * 0.5f
                canvas.drawText("${spec.customTaps}×", bx, byText, textPaint)
            }
        }
    }

    /**
     * Visual directional swipe guide for Auto Scroller mode.
     */
    private class ScrollGuideView(context: Context) : View(context) {
        var isFiringNow: Boolean = false
        private val density = context.resources.displayMetrics.density
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val dir = SettingsStore.autoScrollDirection(context)
            val pad = 4f * density
            val rx = 22f * density

            pillPaint.color = if (isFiringNow) 0xAAFF6671.toInt() else 0xAA141E24.toInt()
            strokePaint.color = if (isFiringNow) Color.WHITE else 0xFFB7F36B.toInt()

            canvas.drawRoundRect(
                pad,
                pad,
                width - pad,
                height - pad,
                rx,
                rx,
                pillPaint,
            )
            canvas.drawRoundRect(
                pad,
                pad,
                width - pad,
                height - pad,
                rx,
                rx,
                strokePaint,
            )

            textPaint.color = if (isFiringNow) Color.WHITE else 0xFFB7F36B.toInt()
            textPaint.textSize = 22f * density
            canvas.drawText(dir.arrow, width * 0.5f, height * 0.45f, textPaint)

            textPaint.color = Color.WHITE
            textPaint.textSize = 10f * density
            canvas.drawText(dir.shortLabel.uppercase(), width * 0.5f, height * 0.72f, textPaint)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun makeWindowDragListener(
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
                        val maxX = (metrics.widthPixels - view.width.coerceAtLeast(dp(ctx, 80))).coerceAtLeast(0)
                        val maxY = (metrics.heightPixels - view.height.coerceAtLeast(dp(ctx, 48))).coerceAtLeast(0)
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

    private fun formatSeconds(sec: Int): String {
        if (sec <= 0) return "0s"
        val m = sec / 60
        val s = sec % 60
        return if (m > 0 && s > 0) "${m}m ${s}s" else if (m > 0) "${m}m" else "${s}s"
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    private fun dp(ctx: Context, value: Float): Int =
        (value * ctx.resources.displayMetrics.density).toInt()

    private const val PIN_SIZE_DP = 44
}
