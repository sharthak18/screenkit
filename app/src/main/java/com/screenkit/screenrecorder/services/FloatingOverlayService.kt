package com.screenkit.screenrecorder.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.screenkit.screenrecorder.CaptureContract
import com.screenkit.screenrecorder.CaptureIntents
import com.screenkit.screenrecorder.CaptureRequestActivity
import com.screenkit.screenrecorder.MainActivity
import com.screenkit.screenrecorder.R
import com.screenkit.screenrecorder.WebCaptureActivity
import com.screenkit.screenrecorder.data.CaptureAudioMode
import com.screenkit.screenrecorder.data.SettingsStore
import com.screenkit.screenrecorder.ui.BrushOverlay
import com.screenkit.screenrecorder.ui.TouchFeedbackOverlay
import kotlin.math.abs

/**
 * Compact, Messenger-style draggable floating bubble with quick capture, brush, show-touches,
 * and black-screen web bypass controls.
 *
 * - Automatically appears whenever the app is opened.
 * - Compact 34dp circular bubble when collapsed.
 * - Drag to bottom "✕" target to close (Messenger style).
 * - Immediately hides during screenshots so the bubble never appears in captures.
 */
class FloatingOverlayService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayRoot: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var expanded = false
    private var screenRecording = false
    private var cameraRecording = false
    private var cameraPreview = false
    private var showTouches = false
    private var receiverRegistered = false

    private var removeTargetView: FrameLayout? = null
    private var removeTargetCircle: FrameLayout? = null
    private var removeTargetParams: WindowManager.LayoutParams? = null
    private var removeTargetShown = false
    private var isOverRemoveTarget = false
    private var snapAnimator: android.animation.ValueAnimator? = null

    private var isTemporarilyHiddenForScreenshot = false
    private val safetyRestoreRunnable = Runnable {
        if (isTemporarilyHiddenForScreenshot) {
            applyScreenshotHiddenState(false)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                CaptureContract.ACTION_SCREEN_STATE -> {
                    screenRecording = intent.getBooleanExtra(CaptureContract.EXTRA_RECORDING, false)
                    val mode = intent.getStringExtra(CaptureContract.EXTRA_MODE).orEmpty()
                    val active = intent.getBooleanExtra(CaptureContract.EXTRA_ACTIVE, false)
                    if (active && mode == "screenshot") {
                        applyScreenshotHiddenState(true)
                    } else if (!isTemporarilyHiddenForScreenshot) {
                        rebuildOverlay()
                    }
                }
                CaptureContract.ACTION_SCREEN_FINISHED -> {
                    if (isTemporarilyHiddenForScreenshot) {
                        applyScreenshotHiddenState(false)
                    } else {
                        rebuildOverlay()
                    }
                }
                CaptureContract.ACTION_CAMERA_STATE -> {
                    cameraRecording = intent.getBooleanExtra(CaptureContract.EXTRA_RECORDING, false)
                    cameraPreview = intent.getBooleanExtra(CaptureContract.EXTRA_PREVIEW, false)
                    if (!isTemporarilyHiddenForScreenshot) {
                        rebuildOverlay()
                    }
                }
                CaptureContract.ACTION_TOUCH_STATE -> {
                    showTouches = intent.getBooleanExtra(
                        CaptureContract.EXTRA_SHOW_TOUCHES,
                        SettingsStore.showTouches(this@FloatingOverlayService),
                    )
                    if (!isTemporarilyHiddenForScreenshot) {
                        rebuildOverlay()
                    }
                }
                CaptureContract.ACTION_TEMPORARY_HIDE_OVERLAY -> {
                    applyScreenshotHiddenState(true)
                }
                CaptureContract.ACTION_RESTORE_OVERLAY -> {
                    applyScreenshotHiddenState(false)
                }
            }
        }
    }

    companion object {
        const val ACTION_SHOW = "com.screenkit.screenrecorder.overlay.SHOW"
        const val ACTION_HIDE = "com.screenkit.screenrecorder.overlay.HIDE"
        const val ACTION_UPDATE = "com.screenkit.screenrecorder.overlay.UPDATE"
        private const val CHANNEL_ID = "floating_controls"
        private const val NOTIFICATION_ID = 5301

        // Balanced floating window dimensions (icon a little bigger for easy visibility & tap)
        private const val BUBBLE_DP = 42
        private const val CELL_WIDTH_DP = 38
        private const val CELL_HEIGHT_DP = 40
        private const val COLUMNS = 6
        private const val REMOVE_TARGET_SIZE_DP = 58
        private const val REMOVE_TARGET_MARGIN_BOTTOM_DP = 42

        @Volatile var isRunning: Boolean = false
            private set

        fun show(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, FloatingOverlayService::class.java).setAction(ACTION_SHOW)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }

        fun hide(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                appContext.startService(
                    Intent(appContext, FloatingOverlayService::class.java).setAction(ACTION_HIDE),
                )
            }
        }

        fun setHiddenForScreenshot(context: Context, hidden: Boolean) {
            val appContext = context.applicationContext
            val action = if (hidden) {
                CaptureContract.ACTION_TEMPORARY_HIDE_OVERLAY
            } else {
                CaptureContract.ACTION_RESTORE_OVERLAY
            }
            appContext.sendBroadcast(Intent(action).setPackage(appContext.packageName))
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        showTouches = SettingsStore.showTouches(this)
        TouchFeedbackOverlay.sync(this)

        val filter = IntentFilter().apply {
            addAction(CaptureContract.ACTION_SCREEN_STATE)
            addAction(CaptureContract.ACTION_SCREEN_FINISHED)
            addAction(CaptureContract.ACTION_CAMERA_STATE)
            addAction(CaptureContract.ACTION_TOUCH_STATE)
            addAction(CaptureContract.ACTION_TEMPORARY_HIDE_OVERLAY)
            addAction(CaptureContract.ACTION_RESTORE_OVERLAY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                removeOverlay()
                isRunning = false
                SettingsStore.setOverlayEnabled(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_SHOW, ACTION_UPDATE, null -> {
                if (!Settings.canDrawOverlays(this)) {
                    isRunning = false
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                promoteToForeground()
                isRunning = true
                SettingsStore.setOverlayEnabled(this, true)
                screenRecording = ScreenCaptureService.isRecording
                cameraRecording = CameraCaptureService.isRecording
                cameraPreview = CameraCaptureService.isPreviewVisible
                showTouches = SettingsStore.showTouches(this)
                TouchFeedbackOverlay.sync(this)

                if (overlayRoot == null) {
                    addOverlay()
                } else {
                    overlayRoot?.animate()?.cancel()
                    if (!isTemporarilyHiddenForScreenshot) {
                        overlayRoot?.visibility = View.VISIBLE
                        overlayRoot?.alpha = 1f
                        overlayRoot?.scaleX = 1f
                        overlayRoot?.scaleY = 1f
                    }
                    rebuildOverlay()
                }
            }
            else -> Unit
        }
        return START_STICKY
    }

    private fun applyScreenshotHiddenState(hidden: Boolean) {
        mainHandler.removeCallbacks(safetyRestoreRunnable)
        isTemporarilyHiddenForScreenshot = hidden
        TouchFeedbackOverlay.setTemporarilyHiddenForScreenshot(hidden)
        BrushOverlay.setToolbarHiddenForScreenshot(hidden)
        val root = overlayRoot ?: return
        val params = overlayParams ?: return
        val manager = windowManager ?: return
        if (hidden) {
            if (expanded) expanded = false
            root.visibility = View.GONE
            root.alpha = 0f
            runCatching { manager.updateViewLayout(root, params) }
            mainHandler.postDelayed(safetyRestoreRunnable, 15_000L)
        } else {
            root.visibility = View.VISIBLE
            root.alpha = 1f
            root.scaleX = 1f
            root.scaleY = 1f
            rebuildOverlay()
        }
    }

    @Suppress("DEPRECATION")
    private fun addOverlay() {
        val manager = windowManager ?: return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
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
            x = dp(8)
            y = dp(240)
        }
        overlayParams = params
        val root = LinearLayout(this)
        overlayRoot = root
        rebuildOverlay()
        try {
            manager.addView(root, params)
        } catch (_: SecurityException) {
            stopAfterOverlayFailure()
        } catch (_: WindowManager.BadTokenException) {
            stopAfterOverlayFailure()
        }
    }

    private fun stopAfterOverlayFailure() {
        overlayRoot = null
        overlayParams = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun rebuildOverlay() {
        val root = overlayRoot ?: return
        val params = overlayParams ?: return
        val manager = windowManager ?: return
        val metrics = realDisplayMetrics()
        val beforeWidth = root.width
        root.removeAllViews()
        root.orientation = LinearLayout.HORIZONTAL
        root.gravity = Gravity.CENTER_VERTICAL

        if (expanded) {
            root.setPadding(dp(4), dp(4), dp(4), dp(4))
            root.background = GradientDrawable().apply {
                setColor(0xF2141C20.toInt())
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), 0xAA4E635B.toInt())
            }
            root.elevation = dp(8).toFloat()
        } else {
            // Compact: no bulky rectangular border around the 34dp circle when collapsed!
            root.setPadding(0, 0, 0, 0)
            root.background = null
            root.elevation = dp(6).toFloat()
        }

        val bubble = makeBubble()
        val menu = if (expanded) makeMenu() else null
        val placeMenuBeforeBubble = expanded && params.x > metrics.widthPixels / 2
        if (placeMenuBeforeBubble && menu != null) {
            root.addView(
                menu,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(CELL_HEIGHT_DP * 2),
                ),
            )
            root.addView(
                bubble,
                LinearLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP)).apply {
                    leftMargin = dp(4)
                },
            )
        } else {
            root.addView(bubble, LinearLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP)))
            if (menu != null) {
                root.addView(
                    menu,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(CELL_HEIGHT_DP * 2),
                    ).apply { leftMargin = dp(4) },
                )
            }
        }
        bubble.setOnTouchListener(makeBubbleTouchListener(root, params))

        if (beforeWidth > 0 || expanded) {
            val estimatedWidth = if (expanded) {
                dp(BUBBLE_DP + 4 + CELL_WIDTH_DP * COLUMNS + 8)
            } else {
                dp(BUBBLE_DP)
            }
            val maxX = (metrics.widthPixels - estimatedWidth).coerceAtLeast(0)
            params.x = params.x.coerceIn(0, maxX)
        }

        if (isTemporarilyHiddenForScreenshot) {
            root.visibility = View.GONE
            root.alpha = 0f
        } else {
            root.visibility = View.VISIBLE
            root.alpha = if (expanded || screenRecording || cameraRecording) 1f else 0.9f
        }
        runCatching { manager.updateViewLayout(root, params) }
    }

    private fun makeBubble(): FrameLayout {
        val bubble = FrameLayout(this)
        val recordingNow = screenRecording || cameraRecording
        val fill = if (recordingNow) 0xFFE54655.toInt() else 0xFFB7F36B.toInt()
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(1.5f), if (recordingNow) 0xFFFFD3D7.toInt() else 0xFF1A261E.toInt())
        }

        if (recordingNow) {
            val mark = TextView(this).apply {
                text = "■"
                textSize = 14f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            bubble.addView(
                mark,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        } else {
            val iconView = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_launcher)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                val padding = dp(4)
                setPadding(padding, padding, padding, padding)
            }
            bubble.addView(
                iconView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (recordingNow) Color.WHITE else if (showTouches) 0xFF2196F3.toInt() else 0xFF2E5C3E.toInt())
                setStroke(dp(1), if (recordingNow) 0xFFE54655.toInt() else 0xFFB7F36B.toInt())
            }
        }
        bubble.addView(
            dot,
            FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(1)
                rightMargin = dp(1)
            },
        )
        bubble.contentDescription = if (recordingNow) {
            "ScreenKit capture active. Tap for controls or drag to bottom to close."
        } else {
            "ScreenKit floating controls. Drag to bottom to close."
        }
        return bubble
    }

    /**
     * Compact 2x6 grid of quick actions including Brush, Show Touch, Crop/Full Screenshot & Record,
     * Camera Lens, Flip, Sound, and Anti-Black-Screen Web Bypass.
     */
    private fun makeMenu(): LinearLayout {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val soundActive = SettingsStore.audioMode(this) != CaptureAudioMode.NONE
        val touchesActive = SettingsStore.showTouches(this)
        val brushActive = BrushOverlay.isShowing
        val rows = listOf(
            listOf(
                MenuAction("▧", "Full", CaptureContract.COMMAND_SCREENSHOT, false),
                MenuAction("✂", "Crop SS", CaptureContract.COMMAND_PARTIAL_SCREENSHOT, false),
                MenuAction(
                    if (screenRecording) "■" else "●",
                    if (screenRecording) "Stop" else "Screen",
                    CaptureContract.COMMAND_TOGGLE_SCREEN_RECORDING,
                    screenRecording,
                ),
                MenuAction("✂", "Crop Rec", CaptureContract.COMMAND_PARTIAL_SCREEN_RECORDING, false),
                MenuAction(
                    if (cameraRecording) "■" else "◉",
                    if (cameraRecording) "Stop" else "Camera",
                    CaptureContract.COMMAND_TOGGLE_CAMERA_RECORDING,
                    cameraRecording,
                ),
                MenuAction(soundIcon(), soundLabel(), CaptureContract.COMMAND_CYCLE_AUDIO, soundActive),
            ),
            listOf(
                MenuAction("✎", "Brush", CaptureContract.COMMAND_OPEN_BRUSH, brushActive),
                MenuAction("⊙", "Touch", CaptureContract.COMMAND_TOGGLE_TOUCHES, touchesActive),
                MenuAction(
                    if (cameraPreview) "◉" else "◎",
                    "Lens",
                    CaptureContract.COMMAND_TOGGLE_CAMERA_OVERLAY,
                    cameraPreview,
                ),
                MenuAction("⇄", "Flip", CaptureContract.COMMAND_FLIP_CAMERA, false),
                MenuAction("🌐", "Bypass", CaptureContract.COMMAND_OPEN_WEB_BYPASS, false),
                MenuAction("⊞", "Home", CaptureContract.COMMAND_OPEN_APP, false),
            ),
        )
        rows.forEach { actions ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            actions.forEach { action ->
                row.addView(
                    makeMenuButton(action),
                    LinearLayout.LayoutParams(dp(CELL_WIDTH_DP), dp(CELL_HEIGHT_DP)),
                )
            }
            column.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(CELL_HEIGHT_DP),
                ),
            )
        }
        return column
    }

    private fun soundIcon(): String = when (SettingsStore.audioMode(this)) {
        CaptureAudioMode.NONE -> "×"
        CaptureAudioMode.MICROPHONE -> "♩"
        CaptureAudioMode.DEVICE -> "♪"
        CaptureAudioMode.MIXED -> "♫"
    }

    private fun soundLabel(): String = when (SettingsStore.audioMode(this)) {
        CaptureAudioMode.NONE -> "Mute"
        CaptureAudioMode.MICROPHONE -> "Mic"
        CaptureAudioMode.DEVICE -> "Device"
        CaptureAudioMode.MIXED -> "Mix"
    }

    private fun makeMenuButton(action: MenuAction): LinearLayout {
        val button = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(1), dp(1), dp(1), dp(1))
            background = selectableBackground(action.active)
            setOnClickListener { dispatch(action.command) }
        }
        val tint = if (action.active) 0xFFB7F36B.toInt() else Color.WHITE
        val icon = TextView(this).apply {
            text = action.icon
            textSize = 15.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(tint)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        button.addView(icon, LinearLayout.LayoutParams(dp(CELL_WIDTH_DP - 2), dp(22)))
        val label = TextView(this).apply {
            text = action.label
            textSize = 8.2f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(if (action.active) 0xFFB7F36B.toInt() else 0xFFC3D0CA.toInt())
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
        }
        button.addView(label, LinearLayout.LayoutParams(dp(CELL_WIDTH_DP - 2), dp(14)))
        return button
    }

    /**
     * Runs what the tap asked for. Actions that do not require runtime permissions or consent
     * sheets execute directly inside the service so the current app stays uninterrupted.
     */
    private fun dispatch(command: String) {
        if (command == CaptureContract.COMMAND_CYCLE_AUDIO ||
            command == CaptureContract.COMMAND_TOGGLE_AUDIO
        ) {
            val next = SettingsStore.audioMode(this).next()
            if (!next.requiresRecordPermission ||
                CaptureIntents.hasPermission(this, android.Manifest.permission.RECORD_AUDIO)
            ) {
                val applied = CaptureIntents.effectiveAudioMode(this, next)
                SettingsStore.setAudioMode(this, applied)
                if (CameraCaptureService.isActive) {
                    CaptureIntents.setCameraAudioMuted(this, !applied.usesMicrophone)
                }
                rebuildOverlay()
                CaptureIntents.toast(this, "Sound: ${applied.label}")
                return
            }
        }

        expanded = false
        if (command == CaptureContract.COMMAND_SCREENSHOT ||
            command == CaptureContract.COMMAND_PARTIAL_SCREENSHOT
        ) {
            // Requirement 4: Immediately hide the floating bubble when taking a screenshot!
            applyScreenshotHiddenState(true)
        } else {
            rebuildOverlay()
        }

        when (command) {
            CaptureContract.COMMAND_OPEN_APP -> {
                openMain()
                return
            }
            CaptureContract.COMMAND_OPEN_WEB_BYPASS -> {
                openWebBypass()
                return
            }
            CaptureContract.COMMAND_HIDE_OVERLAY -> {
                hideOverlay()
                return
            }
            CaptureContract.COMMAND_OPEN_BRUSH -> {
                toggleBrushOverlay()
                return
            }
            CaptureContract.COMMAND_TOGGLE_TOUCHES -> {
                toggleShowTouches()
                return
            }
            CaptureContract.COMMAND_FLIP_CAMERA -> {
                flipCamera()
                return
            }
            CaptureContract.COMMAND_TOGGLE_SCREEN_RECORDING -> if (ScreenCaptureService.isRecording) {
                CaptureIntents.stopScreenCapture(this)
                CaptureIntents.toast(this, "Saving the screen recording…")
                return
            }
            CaptureContract.COMMAND_PARTIAL_SCREEN_RECORDING -> if (ScreenCaptureService.isRecording) {
                CaptureIntents.stopScreenCapture(this)
                CaptureIntents.toast(this, "Saving the screen recording…")
                return
            }
            CaptureContract.COMMAND_TOGGLE_CAMERA_RECORDING -> if (CameraCaptureService.isRecording) {
                CaptureIntents.stopCameraCapture(this)
                return
            }
            CaptureContract.COMMAND_TOGGLE_CAMERA_OVERLAY -> if (CameraCaptureService.isPreviewVisible) {
                CaptureIntents.hideCameraPreview(this)
                return
            }
        }
        launchRequest(command)
    }

    private fun launchRequest(command: String) {
        val intent = Intent(this, CaptureRequestActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
            .putExtra(CaptureContract.EXTRA_OVERLAY_COMMAND, command)
        runCatching { startActivity(intent) }.onFailure {
            if (isTemporarilyHiddenForScreenshot) applyScreenshotHiddenState(false)
            CaptureIntents.toast(this, "ScreenKit could not start that action.")
        }
    }

    private fun toggleShowTouches() {
        val next = !SettingsStore.showTouches(this)
        showTouches = next
        TouchFeedbackOverlay.setEnabled(this, next)
        rebuildOverlay()
        CaptureIntents.toast(
            this,
            if (next) "Show touches enabled" else "Show touches disabled",
        )
    }

    private fun toggleBrushOverlay() {
        BrushOverlay.toggle(this) {
            rebuildOverlay()
        }
    }

    private fun flipCamera() {
        val front = !SettingsStore.cameraFront(this)
        SettingsStore.setCameraFront(this, front)
        val label = if (front) "Front camera" else "Back camera"
        when {
            CameraCaptureService.isRecording ->
                CaptureIntents.toast(this, "Stop the camera recording before switching cameras.")
            CameraCaptureService.isActive || CameraCaptureService.isPreviewVisible -> {
                CaptureIntents.setCameraFacing(this, front)
                CaptureIntents.toast(this, label)
            }
            else -> CaptureIntents.toast(this, "$label selected for the next capture")
        }
        refreshServiceState()
    }

    private fun refreshServiceState() {
        screenRecording = ScreenCaptureService.isRecording
        cameraRecording = CameraCaptureService.isRecording
        cameraPreview = CameraCaptureService.isPreviewVisible
        showTouches = SettingsStore.showTouches(this)
        rebuildOverlay()
    }

    private fun hideOverlay() {
        SettingsStore.setOverlayEnabled(this, false)
        removeOverlay()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openMain() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        runCatching { startActivity(intent) }
    }

    private fun openWebBypass() {
        val intent = Intent(this, WebCaptureActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        runCatching { startActivity(intent) }
    }

    private fun showRemoveTarget() {
        if (removeTargetShown) return
        val manager = windowManager ?: return
        val target = FrameLayout(this)

        val circle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xDD1E2A2F.toInt())
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(16).toFloat()
        }
        val xText = TextView(this).apply {
            text = "✕"
            textSize = 20f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        circle.addView(
            xText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val circleLp = FrameLayout.LayoutParams(
            dp(REMOVE_TARGET_SIZE_DP),
            dp(REMOVE_TARGET_SIZE_DP),
            Gravity.CENTER,
        )
        target.addView(circle, circleLp)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            dp(REMOVE_TARGET_SIZE_DP + 28),
            dp(REMOVE_TARGET_SIZE_DP + 28),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(REMOVE_TARGET_MARGIN_BOTTOM_DP)
        }
        removeTargetView = target
        removeTargetCircle = circle
        removeTargetParams = params
        target.alpha = 0f
        target.scaleX = 0.5f
        target.scaleY = 0.5f
        runCatching {
            manager.addView(target, params)
            removeTargetShown = true
            target.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        }
    }

    private fun hideRemoveTarget(onHidden: (() -> Unit)? = null) {
        if (!removeTargetShown) {
            onHidden?.invoke()
            return
        }
        val view = removeTargetView
        val manager = windowManager
        if (view != null && manager != null) {
            view.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).setDuration(140).withEndAction {
                runCatching { manager.removeView(view) }
                removeTargetView = null
                removeTargetCircle = null
                removeTargetParams = null
                removeTargetShown = false
                isOverRemoveTarget = false
                onHidden?.invoke()
            }.start()
        } else {
            removeTargetView = null
            removeTargetCircle = null
            removeTargetParams = null
            removeTargetShown = false
            isOverRemoveTarget = false
            onHidden?.invoke()
        }
    }

    private fun updateRemoveTargetHover(hover: Boolean) {
        if (isOverRemoveTarget == hover) return
        isOverRemoveTarget = hover
        val view = removeTargetView ?: return
        val circle = removeTargetCircle
        if (hover) {
            circle?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE54655.toInt())
                setStroke(dp(2.5f), Color.WHITE)
            }
            view.animate().scaleX(1.26f).scaleY(1.26f).setDuration(110).start()
        } else {
            circle?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xDD1E2A2F.toInt())
                setStroke(dp(2), Color.WHITE)
            }
            view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(110).start()
        }
    }

    private fun makeBubbleTouchListener(
        root: View,
        params: WindowManager.LayoutParams,
    ): View.OnTouchListener {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false
        return View.OnTouchListener { _, event ->
            TouchFeedbackOverlay.recordTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dragging = false
                    root.alpha = 1f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) {
                        if (!dragging) {
                            dragging = true
                            if (expanded) {
                                expanded = false
                                rebuildOverlay()
                            }
                            showRemoveTarget()
                        }
                    }
                    if (dragging) {
                        val metrics = realDisplayMetrics()
                        val screenWidth = metrics.widthPixels
                        val screenHeight = metrics.heightPixels
                        val bubblePx = dp(BUBBLE_DP)

                        val targetCenterX = screenWidth / 2
                        val targetCenterY =
                            screenHeight - dp(REMOVE_TARGET_MARGIN_BOTTOM_DP + REMOVE_TARGET_SIZE_DP / 2)

                        val fingerX = event.rawX.toInt()
                        val fingerY = event.rawY.toInt()
                        val distSq = (fingerX - targetCenterX) * (fingerX - targetCenterX) +
                            (fingerY - targetCenterY) * (fingerY - targetCenterY)
                        val captureRadius = dp(88)
                        val inBottomCloseZone = distSq <= captureRadius * captureRadius ||
                            (fingerY >= screenHeight - dp(125) && abs(fingerX - targetCenterX) <= dp(88))

                        updateRemoveTargetHover(inBottomCloseZone)

                        if (inBottomCloseZone) {
                            // Messenger-style magnetic snap into the center of the bottom ✕ circle!
                            params.x = targetCenterX - bubblePx / 2
                            params.y = targetCenterY - bubblePx / 2
                        } else {
                            val maxX = (screenWidth - bubblePx).coerceAtLeast(0)
                            val maxY = (screenHeight - bubblePx).coerceAtLeast(dp(24))
                            params.x = (startX + dx).coerceIn(0, maxX)
                            params.y = (startY + dy).coerceIn(dp(24), maxY)
                        }
                        runCatching { windowManager?.updateViewLayout(root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val closedByDrag = isOverRemoveTarget
                        hideRemoveTarget {
                            if (closedByDrag) {
                                animateBubbleDismiss(root)
                            }
                        }
                        if (!closedByDrag) {
                            val metrics = realDisplayMetrics()
                            val screenWidth = metrics.widthPixels
                            val bubblePx = dp(BUBBLE_DP)
                            val maxX = (screenWidth - bubblePx).coerceAtLeast(0)
                            val targetX = if (params.x + bubblePx / 2 < screenWidth / 2) 0 else maxX
                            animateSnapToEdge(root, params, targetX)
                        }
                    } else {
                        toggleExpandedWithAnimation(root)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun animateSnapToEdge(root: View, params: WindowManager.LayoutParams, targetX: Int) {
        val startX = params.x
        snapAnimator?.cancel()
        val animator = android.animation.ValueAnimator.ofInt(startX, targetX).apply {
            duration = 220
            interpolator = android.view.animation.OvershootInterpolator(0.8f)
            addUpdateListener { va ->
                params.x = va.animatedValue as Int
                runCatching { windowManager?.updateViewLayout(root, params) }
            }
        }
        snapAnimator = animator
        animator.start()
        if (!expanded && !screenRecording && !cameraRecording) {
            root.animate().alpha(0.9f).setDuration(220).start()
        }
    }

    private fun animateBubbleDismiss(root: View) {
        root.animate()
            .scaleX(0.1f)
            .scaleY(0.1f)
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                hideOverlay()
            }
            .start()
    }

    private fun toggleExpandedWithAnimation(root: View) {
        if (!expanded) {
            expanded = true
            rebuildOverlay()
            root.alpha = 0f
            root.scaleX = 0.88f
            root.scaleY = 0.88f
            root.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(160)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            root.animate()
                .alpha(0.6f)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(110)
                .withEndAction {
                    expanded = false
                    rebuildOverlay()
                    root.scaleX = 1f
                    root.scaleY = 1f
                }
                .start()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val root = overlayRoot ?: return
        val params = overlayParams ?: return
        val metrics = realDisplayMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        params.x = params.x.coerceIn(0, (width - dp(BUBBLE_DP)).coerceAtLeast(0))
        params.y = params.y.coerceIn(dp(24), (height - dp(BUBBLE_DP) - dp(24)).coerceAtLeast(dp(24)))
        runCatching { windowManager?.updateViewLayout(root, params) }
        rebuildOverlay()
    }

    private fun promoteToForeground() {
        val open = PendingIntent.getActivity(
            this, 601,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hide = PendingIntent.getService(
            this, 602,
            Intent(this, FloatingOverlayService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle("ScreenKit floating controls are on")
            .setContentText("Tap the bubble to capture, or drag it to the bottom to close.")
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", hide)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Floating capture controls", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows when the user-enabled ScreenKit bubble is active."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun removeOverlay() {
        mainHandler.removeCallbacks(safetyRestoreRunnable)
        snapAnimator?.cancel()
        snapAnimator = null
        hideRemoveTarget()
        BrushOverlay.dismissActive()
        overlayRoot?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlayRoot = null
        overlayParams = null
    }

    private fun selectableBackground(active: Boolean): android.graphics.drawable.Drawable {
        val drawable = GradientDrawable().apply {
            setColor(if (active) 0x26B7F36B else 0x00141C20)
            cornerRadius = dp(8).toFloat()
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x335B6B62), drawable, null,
        )
    }

    @Suppress("DEPRECATION")
    private fun realDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val display = windowManager?.defaultDisplay
        if (display != null) {
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(resources.displayMetrics)
        }
        return metrics
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(stateReceiver) }
            receiverRegistered = false
        }
        removeOverlay()
        isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class MenuAction(val icon: String, val label: String, val command: String, val active: Boolean)
}
