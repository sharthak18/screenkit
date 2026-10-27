package com.screenkit.screenrecorder

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.screenkit.screenrecorder.data.AutoScrollDirection
import com.screenkit.screenrecorder.data.AutoToolMode
import com.screenkit.screenrecorder.data.CaptureAudioMode
import com.screenkit.screenrecorder.data.MediaCapture
import com.screenkit.screenrecorder.data.MediaStoreRepository
import com.screenkit.screenrecorder.data.ProjectionScope
import com.screenkit.screenrecorder.data.SaveLocationMode
import com.screenkit.screenrecorder.data.SettingsStore
import com.screenkit.screenrecorder.data.StorageVolumes
import com.screenkit.screenrecorder.data.UpdateAndStatsManager
import com.screenkit.screenrecorder.services.BlackScreenBypass
import com.screenkit.screenrecorder.services.CameraCaptureService
import com.screenkit.screenrecorder.services.FloatingOverlayService
import com.screenkit.screenrecorder.services.ScreenCaptureService
import com.screenkit.screenrecorder.services.ScreenKitAutoService
import com.screenkit.screenrecorder.services.ShakeAndKeyStopDetector
import com.screenkit.screenrecorder.ui.AutoClickScrollOverlay
import com.screenkit.screenrecorder.ui.BrushOverlay
import com.screenkit.screenrecorder.ui.CameraFacing
import com.screenkit.screenrecorder.ui.CaptureViewModel
import com.screenkit.screenrecorder.ui.CreatorToolsOverlay
import com.screenkit.screenrecorder.ui.MainScreen
import com.screenkit.screenrecorder.ui.TouchFeedbackOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: CaptureViewModel
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlaySettingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var folderLauncher: ActivityResultLauncher<Uri?>

    private var pendingProjectionMode: String? = null
    private var pendingCropRect: android.graphics.RectF? = null
    private var pendingPermissionFlow: String? = null
    private var pendingPermissionAction: (() -> Unit)? = null
    private var overlaySettingsPending = false
    private var cameraPreviewSettingsPending = false
    private var screenshotFlowPending = false
    private var receiverRegistered = false
    private var showOverlayWelcome by mutableStateOf(false)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                CaptureContract.ACTION_SCREEN_STATE -> {
                    val active = intent.getBooleanExtra(CaptureContract.EXTRA_ACTIVE, false)
                    val recording = intent.getBooleanExtra(CaptureContract.EXTRA_RECORDING, false)
                    val paused = intent.getBooleanExtra(CaptureContract.EXTRA_PAUSED, false)
                    val mode = intent.getStringExtra(CaptureContract.EXTRA_MODE).orEmpty()
                    val startedAt = intent.getLongExtra(CaptureContract.EXTRA_STARTED_AT, 0L)
                    viewModel.update { old ->
                        old.copy(
                            screenSessionActive = active,
                            screenRecording = active && recording,
                            screenPaused = active && recording && paused,
                            screenshotInProgress = active && mode == "screenshot",
                            screenRecordingStartedAt = if (active && recording) startedAt else 0L,
                        )
                    }
                    if (!active) refreshRecentMedia()
                }
                CaptureContract.ACTION_CAMERA_STATE -> {
                    val active = intent.getBooleanExtra(CaptureContract.EXTRA_ACTIVE, false)
                    val recording = intent.getBooleanExtra(CaptureContract.EXTRA_RECORDING, false)
                    val preview = intent.getBooleanExtra(CaptureContract.EXTRA_PREVIEW, false)
                    val startedAt = intent.getLongExtra(CaptureContract.EXTRA_STARTED_AT, 0L)
                    val muted = intent.getBooleanExtra(CaptureContract.EXTRA_AUDIO_MUTED, true)
                    val front = intent.getBooleanExtra(CaptureContract.EXTRA_CAMERA_FRONT, false)
                    viewModel.update { old ->
                        old.copy(
                            cameraSessionActive = active,
                            cameraRecording = active && recording,
                            cameraPreviewVisible = active && preview,
                            cameraOverlayEnabled = active && preview,
                            cameraRecordingStartedAt = if (active && recording) startedAt else 0L,
                            cameraFacing = if (front) CameraFacing.FRONT else CameraFacing.BACK,
                            microphoneEnabled = if (recording) !muted else old.microphoneEnabled,
                        )
                    }
                }
                CaptureContract.ACTION_TOUCH_STATE -> {
                    val enabled = intent.getBooleanExtra(
                        CaptureContract.EXTRA_SHOW_TOUCHES,
                        SettingsStore.showTouches(this@MainActivity),
                    )
                    viewModel.update { it.copy(showTouches = enabled) }
                }
                CaptureContract.ACTION_AUTO_STATE -> {
                    refreshStorageSnapshot()
                }
                CaptureContract.ACTION_SCREEN_FINISHED,
                CaptureContract.ACTION_CAMERA_FINISHED -> {
                    if (intent.action == CaptureContract.ACTION_SCREEN_FINISHED) {
                        screenshotFlowPending = false
                        FloatingOverlayService.setHiddenForScreenshot(this@MainActivity, false)
                    }
                    val success = intent.getBooleanExtra(CaptureContract.EXTRA_SUCCESS, false)
                    val message = intent.getStringExtra(CaptureContract.EXTRA_MESSAGE)
                    if (!message.isNullOrBlank()) showMessage(message)
                    if (!success) {
                        syncServiceStates()
                    }
                    refreshRecentMedia()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BlackScreenBypass.prepareWindowForCapture(window)
        BlackScreenBypass.trySystemSecureBypass(this)

        pendingProjectionMode = savedInstanceState?.getString("pending_projection_mode")
        pendingPermissionFlow = savedInstanceState?.getString("pending_permission_flow")
        overlaySettingsPending = savedInstanceState?.getBoolean("overlay_settings_pending") ?: false
        cameraPreviewSettingsPending = savedInstanceState?.getBoolean("camera_preview_settings_pending") ?: false
        screenshotFlowPending = savedInstanceState?.getBoolean("screenshot_flow_pending") ?: false

        viewModel = ViewModelProvider(this)[CaptureViewModel::class.java]
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            refreshPermissionSnapshot()
            val action = pendingPermissionAction
            pendingPermissionAction = null
            if (action != null) {
                pendingPermissionFlow = null
                action()
            } else {
                val flow = pendingPermissionFlow
                pendingPermissionFlow = null
                resumePermissionFlow(flow)
            }
        }
        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val mode = pendingProjectionMode
            pendingProjectionMode = null
            val crop = pendingCropRect
            pendingCropRect = null
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null || mode == null) {
                screenshotFlowPending = false
                FloatingOverlayService.setHiddenForScreenshot(this, false)
                showMessage("Screen capture was cancelled.")
                return@registerForActivityResult
            }
            if (!CaptureIntents.startScreenCapture(this, mode, result.resultCode, data, crop)) {
                screenshotFlowPending = false
                FloatingOverlayService.setHiddenForScreenshot(this, false)
                showMessage("Could not start the screen capture. Please try again.")
            }
        }
        overlaySettingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            refreshPermissionSnapshot()
            continueAfterOverlaySettings()
        }
        folderLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri == null) {
                showMessage("No folder was chosen.")
                return@registerForActivityResult
            }
            val persisted = SettingsStore.commitTreeLocation(this, uri, writeGranted = true)
            if (persisted) {
                refreshStorageSnapshot()
                showMessage("Captures will be saved to ${SettingsStore.describeSaveLocation(this)}.")
            } else {
                SettingsStore.setSaveMode(this, SaveLocationMode.MEDIA_DEFAULT)
                refreshStorageSnapshot()
                showMessage("Android did not keep access to that folder, so the gallery is still used.")
            }
        }

        viewModel.update {
            it.copy(
                cameraOverlayEnabled = CameraCaptureService.isPreviewVisible,
                overlayEnabled = FloatingOverlayService.isRunning,
            )
        }
        if (savedInstanceState == null) {
            SettingsStore.incrementAppOpens(this)
        }
        ShakeAndKeyStopDetector.start(this)
        refreshPermissionSnapshot()
        refreshStorageSnapshot()
        syncServiceStates()
        refreshGitHubStats()
        setContent {
            val state = viewModel.state.collectAsStateWithLifecycle().value
            MainScreen(
                state = state,
                showOverlayWelcome = showOverlayWelcome,
                onDismissOverlayWelcome = {
                    showOverlayWelcome = false
                    SettingsStore.markOverlayIntroSeen(this)
                },
                onEnableOverlay = {
                    showOverlayWelcome = false
                    SettingsStore.markOverlayIntroSeen(this)
                    enableFloatingOverlay()
                },
                onDisableOverlay = { disableFloatingOverlay() },
                onTakeScreenshot = { requestScreenCapture(CaptureContract.MODE_SCREENSHOT) },
                onTakePartialScreenshot = { requestPartialScreenCapture(CaptureContract.MODE_PARTIAL_SCREENSHOT) },
                onTakeLongScreenshot = { requestScreenCapture(CaptureContract.MODE_LONG_SCREENSHOT) },
                onToggleScreenRecording = { toggleScreenRecording() },
                onPauseResumeScreenRecording = {
                    CaptureIntents.togglePauseScreenCapture(this)
                },
                onTogglePartialScreenRecording = { togglePartialScreenRecording() },
                onToggleCameraRecording = { toggleCameraRecording() },
                onAudioModeSelected = { mode -> setAudioMode(mode) },
                onToggleCameraOverlay = { enabled -> setCameraOverlayEnabled(enabled) },
                onCameraFacingSelected = { facing -> setCameraFacing(facing) },
                onProjectionScopeSelected = { scope -> setProjectionScope(scope) },
                onSaveModeSelected = { mode -> setSaveMode(mode) },
                onVolumeSelected = { name -> selectVolume(name) },
                onPickFolder = { pickFolder() },
                onOpenCapture = { capture -> openCapture(capture) },
                onToggleShowTouches = { enabled -> setShowTouches(enabled) },
                onOpenBrush = { BrushOverlay.toggle(this) },
                onToggleHwGlBypass = { enabled -> setHwGlBypass(enabled) },
                onOpenWebBypass = { openWebBypass() },
                onOpenAccessibilitySettings = {
                    ScreenKitAutoService.openAccessibilitySettings(this)
                },
                onLaunchAutoTool = { mode -> launchOrToggleAutoTool(mode) },
                onDismissAutoTool = {
                    AutoClickScrollOverlay.dismiss()
                    refreshStorageSnapshot()
                },
                onAutoModeSelected = { mode ->
                    SettingsStore.setAutoToolMode(this, mode)
                    AutoClickScrollOverlay.refreshFromSettings(this)
                    refreshStorageSnapshot()
                },
                onAutoClickPointCountChanged = { count ->
                    SettingsStore.setAutoClickPointCount(this, count)
                    AutoClickScrollOverlay.refreshFromSettings(this)
                    refreshStorageSnapshot()
                },
                onAutoClickDelayChanged = { delayMs ->
                    SettingsStore.setAutoClickDelayMs(this, delayMs)
                    AutoClickScrollOverlay.refreshFromSettings(this)
                    refreshStorageSnapshot()
                },
                onAutoClickSpeedChanged = { speedMs ->
                    SettingsStore.setAutoClickSpeedMs(this, speedMs)
                    AutoClickScrollOverlay.refreshFromSettings(this)
                    refreshStorageSnapshot()
                },
                onAutoClickTapsPerPointChanged = { taps ->
                    SettingsStore.setAutoClickTapsPerPoint(this, taps)
                    AutoClickScrollOverlay.refreshFromSettings(this)
                    refreshStorageSnapshot()
                },
                onAutoClickTotalCyclesChanged = { cycles ->
                    SettingsStore.setAutoClickTotalCycles(this, cycles)
                    AutoClickScrollOverlay.refreshFromSettings(this)
                    refreshStorageSnapshot()
                },
                onAutoScrollDirectionChanged = { dir ->
                    SettingsStore.setAutoScrollDirection(this, dir)
                    AutoClickScrollOverlay.refreshFromSettings(this)
                    refreshStorageSnapshot()
                },
                onAutoScrollSpeedChanged = { speedMs ->
                    SettingsStore.setAutoScrollSpeedMs(this, speedMs)
                    AutoClickScrollOverlay.refreshFromSettings(this)
                    refreshStorageSnapshot()
                },
                onAutoScrollIntervalChanged = { intervalMs ->
                    SettingsStore.setAutoScrollIntervalMs(this, intervalMs)
                    AutoClickScrollOverlay.refreshFromSettings(this)
                    refreshStorageSnapshot()
                },
                onAutoScrollDurationChanged = { durSec ->
                    SettingsStore.setAutoScrollDurationSec(this, durSec)
                    AutoClickScrollOverlay.refreshFromSettings(this)
                    refreshStorageSnapshot()
                },
                onAutoScrollDistanceChanged = { distPct ->
                    SettingsStore.setAutoScrollDistancePct(this, distPct)
                    AutoClickScrollOverlay.refreshFromSettings(this)
                    refreshStorageSnapshot()
                },
                onToggleAutoJitter = { enabled ->
                    SettingsStore.setAutoRandomJitter(this, enabled)
                    refreshStorageSnapshot()
                },
                onToggleAutoColorTrigger = { enabled ->
                    SettingsStore.setAutoColorTriggerEnabled(this, enabled)
                    AutoClickScrollOverlay.refreshFromSettings(this)
                    refreshStorageSnapshot()
                },
                onSelectColorTriggerHex = { hex ->
                    SettingsStore.setAutoColorTriggerHex(this, hex)
                    SettingsStore.setAutoColorTriggerEnabled(this, true)
                    AutoClickScrollOverlay.refreshFromSettings(this)
                    refreshStorageSnapshot()
                },
                onLoadAutoProfile = { slot ->
                    val name = SettingsStore.loadProfileSlot(this, slot)
                    AutoClickScrollOverlay.refreshFromSettings(this)
                    refreshStorageSnapshot()
                    if (name != null) showMessage("Loaded profile: $name")
                },
                onSaveAutoProfile = { slot ->
                    SettingsStore.saveCurrentToProfileSlot(this, slot)
                    refreshStorageSnapshot()
                    showMessage("Saved current Auto settings & pin positions to Slot $slot!")
                },
                onToggleShakeToStop = { enabled ->
                    SettingsStore.setShakeToStopEnabled(this, enabled)
                    refreshStorageSnapshot()
                },
                onToggleVolumeKeyStop = { enabled ->
                    SettingsStore.setVolumeKeyStopEnabled(this, enabled)
                    refreshStorageSnapshot()
                },
                onToggleTeleprompter = {
                    CreatorToolsOverlay.toggleTeleprompter(this) { refreshStorageSnapshot() }
                },
                onTeleprompterScriptChanged = { script ->
                    SettingsStore.setTeleprompterScript(this, script)
                    refreshStorageSnapshot()
                },
                onToggleMagnifier = {
                    CreatorToolsOverlay.toggleMagnifier(this) { refreshStorageSnapshot() }
                },
                onToggleWatermark = { enabled ->
                    SettingsStore.setWatermarkEnabled(this, enabled)
                    CreatorToolsOverlay.syncWatermark(this)
                    refreshStorageSnapshot()
                },
                onWatermarkTextChanged = { text ->
                    SettingsStore.setWatermarkText(this, text)
                    CreatorToolsOverlay.syncWatermark(this)
                    refreshStorageSnapshot()
                },
                onCheckForUpdates = {
                    refreshGitHubStats(showToast = true)
                },
                onDownloadLatestApk = { url ->
                    UpdateAndStatsManager.openLatestApkDownload(this, url)
                },
                onRefreshLibrary = { msg ->
                    showMessage(msg)
                    refreshStorageSnapshot()
                    refreshRecentMedia()
                },
            )
        }

        // Requirement 1: Always ensure the floating window appears automatically when opening the app
        if (!Settings.canDrawOverlays(this)) {
            showOverlayWelcome = true
        } else {
            ensureFloatingOverlayVisible()
        }

        requestInitialPermissionsAutomatically()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            TouchFeedbackOverlay.recordTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun requestInitialPermissionsAutomatically() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (!hasPermission(Manifest.permission.CAMERA)) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_projection_mode", pendingProjectionMode)
        outState.putString("pending_permission_flow", pendingPermissionFlow)
        outState.putBoolean("overlay_settings_pending", overlaySettingsPending)
        outState.putBoolean("camera_preview_settings_pending", cameraPreviewSettingsPending)
        outState.putBoolean("screenshot_flow_pending", screenshotFlowPending)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(CaptureContract.ACTION_SCREEN_STATE)
            addAction(CaptureContract.ACTION_SCREEN_FINISHED)
            addAction(CaptureContract.ACTION_CAMERA_STATE)
            addAction(CaptureContract.ACTION_CAMERA_FINISHED)
            addAction(CaptureContract.ACTION_TOUCH_STATE)
            addAction(CaptureContract.ACTION_AUTO_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, filter)
        }
        receiverRegistered = true

        // Requirement 1: Pop up the floating window automatically whenever the user opens/enters the app
        if (Settings.canDrawOverlays(this) && !screenshotFlowPending && !overlaySettingsPending) {
            ensureFloatingOverlayVisible()
        }
    }

    override fun onResume() {
        super.onResume()
        BlackScreenBypass.prepareWindowForCapture(window)
        refreshPermissionSnapshot()
        refreshStorageSnapshot()
        syncServiceStates()
        refreshRecentMedia()
        continueAfterOverlaySettings()
        TouchFeedbackOverlay.sync(this)

        // Requirement 1: Even if the user closed the floating window first and then opened the app,
        // automatically show the floating window again!
        if (Settings.canDrawOverlays(this) && !screenshotFlowPending && !overlaySettingsPending) {
            ensureFloatingOverlayVisible()
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(permissionReceiver) }
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshPermissionSnapshot()
        refreshStorageSnapshot()
        syncServiceStates()
        if (Settings.canDrawOverlays(this) && !screenshotFlowPending && !overlaySettingsPending) {
            ensureFloatingOverlayVisible()
        }
    }

    // ------------------------------------------------------------------ state

    private fun refreshPermissionSnapshot() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val cameraGranted = hasPermission(Manifest.permission.CAMERA)
        val microphoneGranted = hasPermission(Manifest.permission.RECORD_AUDIO)
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        val storageGranted = CaptureIntents.canWriteExternalStorage(this)
        viewModel.update { state ->
            state.copy(
                overlayPermission = overlayGranted,
                cameraPermission = cameraGranted,
                microphonePermission = microphoneGranted,
                notificationPermission = notificationsGranted,
                storagePermission = storageGranted,
                overlayEnabled = overlayGranted && FloatingOverlayService.isRunning,
            )
        }
    }

    private fun refreshStorageSnapshot() {
        val volumes = StorageVolumes.list(this)
        viewModel.update { state ->
            state.copy(
                audioMode = SettingsStore.audioMode(this),
                microphoneEnabled = SettingsStore.audioMode(this).usesMicrophone,
                saveMode = SettingsStore.saveMode(this),
                saveLocationLabel = SettingsStore.describeSaveLocation(this),
                volumes = volumes,
                selectedVolume = SettingsStore.mediaVolume(this),
                projectionScope = SettingsStore.projectionScope(this),
                deviceAudioSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                cameraFacing = if (SettingsStore.cameraFront(this)) CameraFacing.FRONT else CameraFacing.BACK,
                showTouches = SettingsStore.showTouches(this),
                hwGlBypass = SettingsStore.hwGlBypass(this),
                autoAccessibilityEnabled = ScreenKitAutoService.isServiceEnabled(this),
                autoOverlayShowing = AutoClickScrollOverlay.isShowing,
                autoExecuting = AutoClickScrollOverlay.isExecuting,
                autoToolMode = SettingsStore.autoToolMode(this),
                autoClickPointCount = SettingsStore.autoClickPointCount(this),
                autoClickDelayMs = SettingsStore.autoClickDelayMs(this),
                autoClickSpeedMs = SettingsStore.autoClickSpeedMs(this),
                autoClickTapsPerPoint = SettingsStore.autoClickTapsPerPoint(this),
                autoClickTotalCycles = SettingsStore.autoClickTotalCycles(this),
                autoScrollDirection = SettingsStore.autoScrollDirection(this),
                autoScrollSpeedMs = SettingsStore.autoScrollSpeedMs(this),
                autoScrollIntervalMs = SettingsStore.autoScrollIntervalMs(this),
                autoScrollDurationSec = SettingsStore.autoScrollDurationSec(this),
                autoScrollDistancePct = SettingsStore.autoScrollDistancePct(this),
                autoRandomJitter = SettingsStore.autoRandomJitter(this),
                autoColorTriggerEnabled = SettingsStore.autoColorTriggerEnabled(this),
                autoColorTriggerHex = SettingsStore.autoColorTriggerHex(this),
                autoMacroStepCount = SettingsStore.loadMacroSteps(this).size,
                autoSavedProfiles = SettingsStore.listSavedProfiles(this),
                shakeToStopEnabled = SettingsStore.shakeToStopEnabled(this),
                volumeKeyStopEnabled = SettingsStore.volumeKeyStopEnabled(this),
                teleprompterShowing = CreatorToolsOverlay.isTeleprompterShowing,
                teleprompterScript = SettingsStore.teleprompterScript(this),
                magnifierShowing = CreatorToolsOverlay.isMagnifierShowing,
                watermarkEnabled = SettingsStore.watermarkEnabled(this),
                watermarkText = SettingsStore.watermarkText(this),
                statAppOpens = SettingsStore.statAppOpens(this),
                statCapturesSaved = SettingsStore.statCapturesSaved(this),
                statAutoGestures = SettingsStore.statAutoGestures(this),
            )
        }
    }

    private fun refreshGitHubStats(showToast: Boolean = false) {
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                UpdateAndStatsManager.fetchReleaseSummary(this@MainActivity)
            }
            viewModel.update { it.copy(githubReleaseSummary = summary) }
            if (showToast) {
                if (summary.updateAvailable) {
                    showMessage("New update ${summary.latestTag} is available! Tap 'Download Latest APK'.")
                } else {
                    showMessage("You are on the latest release (${summary.latestTag}) · ${summary.totalApkDownloads} total APK downloads.")
                }
            }
        }
    }

    private fun syncServiceStates() {
        val screenActive = ScreenCaptureService.isActive
        val screenRecording = ScreenCaptureService.isRecording
        val screenPaused = ScreenCaptureService.isPaused
        val screenMode = ScreenCaptureService.activeMode
        val cameraActive = CameraCaptureService.isActive
        viewModel.update { old ->
            old.copy(
                screenSessionActive = screenActive,
                screenRecording = screenActive && screenRecording,
                screenPaused = screenActive && screenRecording && screenPaused,
                screenshotInProgress = screenActive && screenMode == "screenshot",
                screenRecordingStartedAt = if (screenRecording) ScreenCaptureService.recordingStartedAt else 0L,
                cameraSessionActive = cameraActive,
                cameraRecording = cameraActive && CameraCaptureService.isRecording,
                cameraPreviewVisible = cameraActive && CameraCaptureService.isPreviewVisible,
                cameraOverlayEnabled = cameraActive && CameraCaptureService.isPreviewVisible,
                cameraRecordingStartedAt = if (CameraCaptureService.isRecording) {
                    CameraCaptureService.recordingStartedAt
                } else 0L,
                microphoneEnabled = if (CameraCaptureService.isRecording) {
                    !CameraCaptureService.muted
                } else old.microphoneEnabled,
            )
        }
    }

    private fun refreshRecentMedia() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                MediaStoreRepository.queryRecent(this@MainActivity)
            }
            viewModel.setRecentCaptures(items)
        }
    }

    // ------------------------------------------------------------------ screen capture

    private fun requestPartialScreenCapture(mode: String) {
        val title = if (mode == CaptureContract.MODE_PARTIAL_SCREENSHOT) {
            "Select area for screenshot"
        } else {
            "Select area for recording"
        }
        val overlay = com.screenkit.screenrecorder.ui.AreaSelectionOverlay(
            context = this,
            title = title,
            onConfirmed = { rect ->
                pendingCropRect = rect
                requestScreenCapture(mode)
            },
            onCancelled = {
                pendingCropRect = null
                FloatingOverlayService.setHiddenForScreenshot(this, false)
            },
        )
        overlay.show()
    }

    private fun togglePartialScreenRecording() {
        val state = viewModel.state.value
        when {
            state.screenRecording -> stopScreenRecording()
            state.screenSessionActive -> showMessage("A screenshot is already being saved.")
            else -> requestPartialScreenCapture(CaptureContract.MODE_PARTIAL_SCREEN_RECORDING)
        }
    }

    private fun requestScreenCapture(mode: String) {
        val state = viewModel.state.value
        if (state.screenRecording) {
            if (mode == CaptureContract.MODE_SCREENSHOT || mode == CaptureContract.MODE_PARTIAL_SCREENSHOT) {
                showMessage("Stop the current screen recording before taking a screenshot.")
            } else {
                stopScreenRecording()
            }
            return
        }
        if (state.screenSessionActive || state.cameraRecording) {
            showMessage("Finish the active capture before starting another one.")
            return
        }
        if (mode == CaptureContract.MODE_SCREENSHOT ||
            mode == CaptureContract.MODE_PARTIAL_SCREENSHOT ||
            mode == CaptureContract.MODE_LONG_SCREENSHOT
        ) {
            screenshotFlowPending = true
            FloatingOverlayService.setHiddenForScreenshot(this, true)
        }
        pendingProjectionMode = mode
        runWithPermissions(
            CaptureIntents.requiredPermissions(this, mode),
            permissionFlow = "screen:$mode",
        ) {
            continueScreenCaptureAfterPermissions(mode)
        }
    }

    private fun continueScreenCaptureAfterPermissions(mode: String) {
        if (!CaptureIntents.canWriteExternalStorage(this)) {
            pendingProjectionMode = null
            screenshotFlowPending = false
            FloatingOverlayService.setHiddenForScreenshot(this, false)
            showMessage("Storage permission is needed to save captures on this Android version.")
            return
        }
        if ((mode == CaptureContract.MODE_SCREEN_RECORDING || mode == CaptureContract.MODE_PARTIAL_SCREEN_RECORDING) &&
            SettingsStore.audioMode(this).requiresRecordPermission &&
            !hasPermission(Manifest.permission.RECORD_AUDIO)
        ) {
            SettingsStore.setAudioMode(this, CaptureAudioMode.NONE)
            viewModel.update { it.copy(audioMode = CaptureAudioMode.NONE, microphonePermission = false) }
            showMessage("Microphone permission was declined; this recording will be silent.")
        }
        launchProjectionConsent(mode)
    }

    private fun launchProjectionConsent(mode: String) {
        val consent = CaptureIntents.projectionConsentIntent(this)
        if (consent == null) {
            pendingProjectionMode = null
            screenshotFlowPending = false
            FloatingOverlayService.setHiddenForScreenshot(this, false)
            showMessage("Screen capture is not available on this device.")
            return
        }
        pendingProjectionMode = mode
        runCatching { projectionLauncher.launch(consent) }.onFailure {
            pendingProjectionMode = null
            screenshotFlowPending = false
            FloatingOverlayService.setHiddenForScreenshot(this, false)
            showMessage("Could not open Android's screen-capture approval prompt.")
        }
    }

    private fun toggleScreenRecording() {
        val state = viewModel.state.value
        when {
            state.screenRecording -> stopScreenRecording()
            state.screenSessionActive -> showMessage("A screenshot is already being saved.")
            else -> requestScreenCapture(CaptureContract.MODE_SCREEN_RECORDING)
        }
    }

    private fun stopScreenRecording() {
        if (!CaptureIntents.stopScreenCapture(this)) {
            showMessage("Use the ScreenKit capture notification to stop the recording.")
        }
    }

    // ------------------------------------------------------------------ camera

    private fun toggleCameraRecording() {
        val state = viewModel.state.value
        if (state.cameraRecording) {
            if (!CaptureIntents.stopCameraCapture(this)) {
                showMessage("Use the camera capture notification to stop recording.")
            }
            return
        }
        if (state.screenSessionActive) {
            showMessage("Finish the screen capture before starting a camera recording.")
            return
        }
        runWithPermissions(
            CaptureIntents.requiredPermissions(this, CaptureContract.MODE_CAMERA_RECORDING),
            permissionFlow = "camera_recording",
        ) {
            continueCameraRecordingAfterPermissions()
        }
    }

    private fun continueCameraRecordingAfterPermissions() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            showMessage("Camera permission is required to record video.")
            return
        }
        if (!CaptureIntents.canWriteExternalStorage(this)) {
            showMessage("Storage permission is needed to save a camera video on this Android version.")
            return
        }
        val audio = SettingsStore.audioMode(this)
        if (audio.usesMicrophone && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            SettingsStore.setAudioMode(this, CaptureAudioMode.NONE)
            showMessage("Microphone access was declined; the camera video will be silent.")
        }
        if (!CaptureIntents.startCameraRecording(this)) {
            showMessage("Could not start camera capture. Please try again.")
        }
    }

    private fun setCameraOverlayEnabled(enabled: Boolean) {
        if (!enabled) {
            SettingsStore.setCameraOverlayEnabled(this, false)
            viewModel.update { it.copy(cameraOverlayEnabled = false, cameraPreviewVisible = false) }
            CaptureIntents.hideCameraPreview(this)
            return
        }
        if (viewModel.state.value.cameraPreviewVisible) return
        if (!Settings.canDrawOverlays(this)) {
            cameraPreviewSettingsPending = true
            requestOverlaySettings()
            return
        }
        runWithPermissions(
            CaptureIntents.requiredPermissions(this, CaptureContract.MODE_CAMERA_PREVIEW),
            permissionFlow = "camera_preview",
        ) {
            continueCameraPreviewAfterPermissions()
        }
    }

    private fun continueCameraPreviewAfterPermissions() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            showMessage("Camera permission is required for a live overlay preview.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            cameraPreviewSettingsPending = true
            requestOverlaySettings()
            return
        }
        startCameraPreview()
    }

    private fun startCameraPreview() {
        val front = SettingsStore.cameraFront(this)
        if (CaptureIntents.startCameraPreview(this, front)) {
            SettingsStore.setCameraOverlayEnabled(this, true)
            viewModel.update { it.copy(cameraOverlayEnabled = true) }
        } else {
            showMessage("Could not start the live camera overlay.")
        }
    }

    private fun setCameraFacing(facing: CameraFacing) {
        val front = facing == CameraFacing.FRONT
        SettingsStore.setCameraFront(this, front)
        viewModel.setCameraFacing(facing)
        if (CameraCaptureService.isActive && !CameraCaptureService.isRecording) {
            CaptureIntents.setCameraFacing(this, front)
        }
    }

    // ------------------------------------------------------------------ audio

    private fun setAudioMode(requested: CaptureAudioMode) {
        if (requested.usesDeviceAudio && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            showMessage("Device audio needs Android 10 or newer.")
            return
        }
        if (requested.requiresRecordPermission && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            runWithPermissions(
                listOf(Manifest.permission.RECORD_AUDIO),
                permissionFlow = "audio_mode:${requested.id}",
            ) {
                applyAudioMode(requested)
            }
            return
        }
        applyAudioMode(requested)
    }

    private fun applyAudioMode(requested: CaptureAudioMode) {
        val applied = CaptureIntents.effectiveAudioMode(this, requested)
        SettingsStore.setAudioMode(this, applied)
        when {
            applied != requested ->
                showMessage("Microphone permission is off, so sound was set to ${applied.label}.")
            viewModel.state.value.screenSessionActive ->
                showMessage("Screen-recording audio is fixed when the MP4 starts; this applies next time.")
            else -> {
                if (CameraCaptureService.isActive) {
                    CaptureIntents.setCameraAudioMuted(this, !applied.usesMicrophone)
                }
                if (applied.usesDeviceAudio) {
                    showMessage("Sound: ${applied.label}. Apps that opt out of capture stay silent in the video.")
                }
            }
        }
        refreshStorageSnapshot()
    }

    // ------------------------------------------------------------------ save location & bypass

    private fun setSaveMode(mode: SaveLocationMode) {
        when (mode) {
            SaveLocationMode.MEDIA_DEFAULT -> {
                SettingsStore.setSaveMode(this, mode)
                refreshStorageSnapshot()
                showMessage("Captures will be saved to the device gallery.")
            }
            SaveLocationMode.MEDIA_VOLUME -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    showMessage("Choosing a storage volume needs Android 10+; pick a folder instead.")
                    return
                }
                val volumes = StorageVolumes.list(this)
                val selected = SettingsStore.mediaVolume(this)
                    ?: volumes.firstOrNull { it.removable }?.name
                    ?: volumes.firstOrNull()?.name
                if (selected == null) {
                    showMessage("No secondary storage was found; pick a folder instead.")
                    return
                }
                SettingsStore.setMediaVolume(this, selected)
                SettingsStore.setSaveMode(this, mode)
                refreshStorageSnapshot()
                showMessage("Captures will use ${SettingsStore.describeSaveLocation(this)}.")
            }
            SaveLocationMode.CUSTOM_FOLDER -> {
                if (SettingsStore.treeUri(this) == null) {
                    pickFolder()
                } else {
                    SettingsStore.setSaveMode(this, mode)
                    refreshStorageSnapshot()
                    showMessage("Captures will be saved to ${SettingsStore.describeSaveLocation(this)}.")
                }
            }
        }
    }

    private fun selectVolume(name: String) {
        SettingsStore.setMediaVolume(this, name)
        SettingsStore.setSaveMode(this, SaveLocationMode.MEDIA_VOLUME)
        refreshStorageSnapshot()
        showMessage("Captures will use ${SettingsStore.describeSaveLocation(this)}.")
    }

    private fun pickFolder() {
        runCatching { folderLauncher.launch(null) }
            .onFailure { showMessage("Could not open the folder picker.") }
    }

    private fun setProjectionScope(scope: ProjectionScope) {
        SettingsStore.setProjectionScope(this, scope)
        refreshStorageSnapshot()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            showMessage("This device always shares the whole screen; the choice matters on Android 14+.")
        }
    }

    private fun setShowTouches(enabled: Boolean) {
        TouchFeedbackOverlay.setEnabled(this, enabled)
        viewModel.update { it.copy(showTouches = enabled) }
        showMessage(if (enabled) "Show touches enabled." else "Show touches disabled.")
    }

    private fun setHwGlBypass(enabled: Boolean) {
        SettingsStore.setHwGlBypass(this, enabled)
        viewModel.update { it.copy(hwGlBypass = enabled) }
        showMessage(
            if (enabled) "Hardware OpenGL ES black-screen bypass enabled."
            else "Hardware OpenGL ES bypass disabled.",
        )
    }

    private fun openWebBypass() {
        val intent = Intent(this, WebCaptureActivity::class.java)
        runCatching { startActivity(intent) }
    }

    private fun launchOrToggleAutoTool(mode: AutoToolMode) {
        if (!Settings.canDrawOverlays(this)) {
            overlaySettingsPending = true
            requestOverlaySettings()
            return
        }
        SettingsStore.setAutoToolMode(this, mode)
        AutoClickScrollOverlay.show(this, mode) {
            refreshStorageSnapshot()
        }
        refreshStorageSnapshot()
        if (!ScreenKitAutoService.isServiceEnabled(this)) {
            showMessage("Enable 'ScreenKit Auto Click & Scroll' in Accessibility Settings so ▶ Start can perform gestures.")
        } else {
            showMessage(
                if (mode == AutoToolMode.CLICK) {
                    "Drag targets 1 → ${SettingsStore.autoClickPointCount(this)} to position, then tap ▶ Start!"
                } else {
                    "Auto Scroller (${SettingsStore.autoScrollDirection(this).label}) ready — tap ▶ Start on the floating bar!"
                },
            )
        }
    }

    // ------------------------------------------------------------------ floating control

    private fun ensureFloatingOverlayVisible() {
        if (!Settings.canDrawOverlays(this)) return
        SettingsStore.markOverlayIntroSeen(this)
        SettingsStore.setOverlayEnabled(this, true)
        startFloatingOverlay()
    }

    private fun enableFloatingOverlay() {
        SettingsStore.markOverlayIntroSeen(this)
        if (!Settings.canDrawOverlays(this)) {
            overlaySettingsPending = true
            requestOverlaySettings()
            return
        }
        startFloatingOverlay()
    }

    private fun requestOverlaySettings() {
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { overlaySettingsLauncher.launch(settingsIntent) }
            .onFailure { showMessage("Open Android Settings to allow display over other apps.") }
    }

    private fun continueAfterOverlaySettings() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlaySettingsPending) {
            overlaySettingsPending = false
            startFloatingOverlay()
        }
        if (cameraPreviewSettingsPending) {
            cameraPreviewSettingsPending = false
            setCameraOverlayEnabled(true)
        }
    }

    private fun startFloatingOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            showMessage("Allow ScreenKit to display over other apps to enable the floating control.")
            return
        }
        runCatching { FloatingOverlayService.show(this) }
            .onSuccess {
                SettingsStore.setOverlayEnabled(this, true)
                viewModel.update { it.copy(overlayPermission = true, overlayEnabled = true) }
            }
            .onFailure { showMessage("Could not show the floating control.") }
    }

    private fun disableFloatingOverlay() {
        runCatching { FloatingOverlayService.hide(this) }
        SettingsStore.setOverlayEnabled(this, false)
        viewModel.update { it.copy(overlayEnabled = false) }
    }

    // ------------------------------------------------------------------ helpers

    private fun runWithPermissions(
        required: List<String>,
        permissionFlow: String? = null,
        action: () -> Unit,
    ) {
        val missing = required.distinct().filterNot(::hasPermission)
        if (missing.isEmpty()) {
            action()
        } else {
            pendingPermissionFlow = permissionFlow
            pendingPermissionAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun resumePermissionFlow(flow: String?) {
        when {
            flow == null -> Unit
            flow.startsWith("audio_mode:") -> {
                val mode = CaptureAudioMode.fromId(flow.substringAfter(':'))
                if (mode != null) applyAudioMode(mode)
            }
            flow == "screen:${CaptureContract.MODE_SCREENSHOT}" ->
                continueScreenCaptureAfterPermissions(CaptureContract.MODE_SCREENSHOT)
            flow == "screen:${CaptureContract.MODE_PARTIAL_SCREENSHOT}" ->
                continueScreenCaptureAfterPermissions(CaptureContract.MODE_PARTIAL_SCREENSHOT)
            flow == "screen:${CaptureContract.MODE_LONG_SCREENSHOT}" ->
                continueScreenCaptureAfterPermissions(CaptureContract.MODE_LONG_SCREENSHOT)
            flow == "screen:${CaptureContract.MODE_SCREEN_RECORDING}" ->
                continueScreenCaptureAfterPermissions(CaptureContract.MODE_SCREEN_RECORDING)
            flow == "screen:${CaptureContract.MODE_PARTIAL_SCREEN_RECORDING}" ->
                continueScreenCaptureAfterPermissions(CaptureContract.MODE_PARTIAL_SCREEN_RECORDING)
            flow == "camera_recording" -> continueCameraRecordingAfterPermissions()
            flow == "camera_preview" -> continueCameraPreviewAfterPermissions()
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openCapture(capture: MediaCapture) {
        val mime = if (capture.kind.isVideo) "video/mp4" else "image/png"
        val openIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(capture.uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(openIntent) }
            .onFailure { showMessage("No installed app can open this capture.") }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
