package com.screenkit.screenrecorder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.screenkit.screenrecorder.data.AutoToolMode
import com.screenkit.screenrecorder.data.CaptureAudioMode
import com.screenkit.screenrecorder.data.SettingsStore
import com.screenkit.screenrecorder.services.BlackScreenBypass
import com.screenkit.screenrecorder.services.CameraCaptureService
import com.screenkit.screenrecorder.services.FloatingOverlayService
import com.screenkit.screenrecorder.services.ScreenCaptureService
import com.screenkit.screenrecorder.ui.AutoClickScrollOverlay
import com.screenkit.screenrecorder.ui.BrushOverlay
import com.screenkit.screenrecorder.ui.CreatorToolsOverlay
import com.screenkit.screenrecorder.ui.TouchFeedbackOverlay

/**
 * The invisible hop between a tap on the floating control and Android's permission or consent
 * sheet.
 */
class CaptureRequestActivity : ComponentActivity() {
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlaySettingsLauncher: ActivityResultLauncher<Intent>

    private var command: String? = null
    private var pendingMode: String? = null
    private var pendingPermissionAction: (() -> Unit)? = null
    private var handled = false
    private var overlaySettingsPending = false
    private val watchdog = Handler(Looper.getMainLooper())

    private var pendingCropRect: android.graphics.RectF? = null
    private var areaSelectionOverlay: com.screenkit.screenrecorder.ui.AreaSelectionOverlay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BlackScreenBypass.prepareWindowForCapture(window)
        command = intent?.getStringExtra(CaptureContract.EXTRA_OVERLAY_COMMAND)
            ?: savedInstanceState?.getString(KEY_COMMAND)
        pendingMode = savedInstanceState?.getString(KEY_MODE)
        overlaySettingsPending = savedInstanceState?.getBoolean(KEY_OVERLAY_SETTINGS) ?: false
        if (savedInstanceState != null) handled = true

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            val action = pendingPermissionAction
            pendingPermissionAction = null
            if (action != null) action() else {
                FloatingOverlayService.setHiddenForScreenshot(this, false)
                finishSafely()
            }
        }
        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val mode = pendingMode ?: command ?: CaptureContract.MODE_SCREENSHOT
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) {
                FloatingOverlayService.setHiddenForScreenshot(this, false)
                CaptureIntents.toast(this, "Screen capture was cancelled.")
                finishSafely()
                return@registerForActivityResult
            }
            if (!CaptureIntents.startScreenCapture(this, mode, result.resultCode, data, pendingCropRect)) {
                FloatingOverlayService.setHiddenForScreenshot(this, false)
                CaptureIntents.toast(this, "Could not start the screen capture.")
            }
            finishSafely()
        }
        overlaySettingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            if (overlaySettingsPending && Settings.canDrawOverlays(this)) {
                overlaySettingsPending = false
                startCameraPreviewFlow()
            } else {
                finishSafely()
            }
        }

        watchdog.postDelayed({
            if (!isFinishing) {
                FloatingOverlayService.setHiddenForScreenshot(this, false)
                finishSafely()
            }
        }, WATCHDOG_MS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_COMMAND, command)
        outState.putString(KEY_MODE, pendingMode)
        outState.putBoolean(KEY_OVERLAY_SETTINGS, overlaySettingsPending)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (handled) return
        handled = true
        when (command) {
            CaptureContract.COMMAND_SCREENSHOT -> {
                FloatingOverlayService.setHiddenForScreenshot(this, true)
                requestScreenCapture(CaptureContract.MODE_SCREENSHOT)
            }
            CaptureContract.COMMAND_LONG_SCREENSHOT -> {
                FloatingOverlayService.setHiddenForScreenshot(this, true)
                requestScreenCapture(CaptureContract.MODE_LONG_SCREENSHOT)
            }
            CaptureContract.COMMAND_PAUSE_RESUME_SCREEN_RECORDING -> {
                if (ScreenCaptureService.isRecording) {
                    CaptureIntents.togglePauseScreenCapture(this)
                }
                finishSafely()
            }
            CaptureContract.COMMAND_TOGGLE_TELEPROMPTER -> {
                CreatorToolsOverlay.toggleTeleprompter(this)
                finishSafely()
            }
            CaptureContract.COMMAND_TOGGLE_MAGNIFIER -> {
                if (CreatorToolsOverlay.isMagnifierShowing) {
                    CreatorToolsOverlay.hideMagnifier(); finishSafely()
                } else requestScreenCapture(CaptureContract.MODE_MAGNIFIER)
            }
            CaptureContract.COMMAND_TOGGLE_WATERMARK -> {
                CreatorToolsOverlay.toggleWatermark(this)
                finishSafely()
            }
            CaptureContract.COMMAND_PARTIAL_SCREENSHOT -> {
                FloatingOverlayService.setHiddenForScreenshot(this, true)
                startAreaSelection(CaptureContract.MODE_PARTIAL_SCREENSHOT)
            }
            CaptureContract.COMMAND_PARTIAL_SCREEN_RECORDING -> {
                if (ScreenCaptureService.isRecording) {
                    CaptureIntents.stopScreenCapture(this)
                    CaptureIntents.toast(this, "Saving the screen recording…")
                    finishSafely()
                } else {
                    startAreaSelection(CaptureContract.MODE_PARTIAL_SCREEN_RECORDING)
                }
            }
            CaptureContract.COMMAND_TOGGLE_SCREEN_RECORDING -> {
                if (ScreenCaptureService.isRecording) {
                    CaptureIntents.stopScreenCapture(this)
                    CaptureIntents.toast(this, "Saving the screen recording…")
                    finishSafely()
                } else {
                    requestScreenCapture(CaptureContract.MODE_SCREEN_RECORDING)
                }
            }
            CaptureContract.COMMAND_TOGGLE_CAMERA_RECORDING -> {
                if (CameraCaptureService.isRecording) {
                    CaptureIntents.stopCameraCapture(this)
                    finishSafely()
                } else {
                    requestPermissions(CaptureContract.MODE_CAMERA_RECORDING) { continueCameraRecording() }
                }
            }
            CaptureContract.COMMAND_TOGGLE_CAMERA_OVERLAY -> {
                if (CameraCaptureService.isPreviewVisible) {
                    CaptureIntents.hideCameraPreview(this)
                    finishSafely()
                } else {
                    startCameraPreviewFlow()
                }
            }
            CaptureContract.COMMAND_FLIP_CAMERA -> {
                flipCamera()
                finishSafely()
            }
            CaptureContract.COMMAND_CYCLE_AUDIO, CaptureContract.COMMAND_TOGGLE_AUDIO -> cycleAudio()
            CaptureContract.COMMAND_OPEN_APP -> {
                openMainApp()
                finishSafely()
            }
            CaptureContract.COMMAND_OPEN_WEB_BYPASS -> {
                openWebBypass()
                finishSafely()
            }
            CaptureContract.COMMAND_HIDE_OVERLAY -> {
                FloatingOverlayService.hide(this)
                finishSafely()
            }
            CaptureContract.COMMAND_OPEN_BRUSH -> {
                BrushOverlay.toggle(this)
                finishSafely()
            }
            CaptureContract.COMMAND_OPEN_AUTO_CLICK -> {
                AutoClickScrollOverlay.show(this, AutoToolMode.CLICK)
                finishSafely()
            }
            CaptureContract.COMMAND_OPEN_AUTO_SCROLL -> {
                AutoClickScrollOverlay.show(this, AutoToolMode.SCROLL)
                finishSafely()
            }
            CaptureContract.COMMAND_TOGGLE_AUTO_TOOL -> {
                AutoClickScrollOverlay.toggle(this)
                finishSafely()
            }
            CaptureContract.COMMAND_TOGGLE_TOUCHES -> {
                val next = !SettingsStore.showTouches(this)
                TouchFeedbackOverlay.setEnabled(this, next)
                CaptureIntents.toast(this, if (next) "Show touches enabled" else "Show touches disabled")
                finishSafely()
            }
            null -> finishSafely()
            else -> {
                CaptureIntents.toast(this, "ScreenKit does not know that action.")
                finishSafely()
            }
        }
    }

    // ------------------------------------------------------------------ screen capture

    private fun startAreaSelection(targetMode: String) {
        val title = if (targetMode == CaptureContract.MODE_PARTIAL_SCREENSHOT) {
            "Select area for screenshot"
        } else {
            "Select area for recording"
        }
        val overlay = com.screenkit.screenrecorder.ui.AreaSelectionOverlay(
            context = this,
            title = title,
            onConfirmed = { rect ->
                pendingCropRect = rect
                if (targetMode == CaptureContract.MODE_PARTIAL_SCREENSHOT) {
                    FloatingOverlayService.setHiddenForScreenshot(this, true)
                }
                requestScreenCapture(targetMode)
            },
            onCancelled = {
                FloatingOverlayService.setHiddenForScreenshot(this, false)
                finishSafely()
            },
        )
        areaSelectionOverlay = overlay
        overlay.show()
    }

    private fun requestScreenCapture(mode: String) {
        pendingMode = mode
        requestPermissions(mode) { continueScreenCapture(mode) }
    }

    private fun continueScreenCapture(mode: String) {
        if (ScreenCaptureService.isActive || com.screenkit.screenrecorder.services.MagnifierCaptureService.active) {
            FloatingOverlayService.setHiddenForScreenshot(this, false)
            CaptureIntents.toast(this, "Stop the current screen capture or close Zoom first.")
            finishSafely(); return
        }
        if (mode == CaptureContract.MODE_LONG_SCREENSHOT && com.screenkit.screenrecorder.services.ScreenKitAutoService.instance == null) {
            FloatingOverlayService.setHiddenForScreenshot(this, false)
            CaptureIntents.toast(this, "Enable ScreenKit Auto Click & Scroll in Accessibility for Long Screenshot.")
            finishSafely(); return
        }
        if ((mode == CaptureContract.MODE_MAGNIFIER || mode == CaptureContract.MODE_SCREEN_RECORDING || mode == CaptureContract.MODE_PARTIAL_SCREEN_RECORDING) &&
            CameraCaptureService.isRecording
        ) {
            FloatingOverlayService.setHiddenForScreenshot(this, false)
            CaptureIntents.toast(this, "Stop the camera recording before starting a screen recording.")
            finishSafely()
            return
        }
        if (mode != CaptureContract.MODE_MAGNIFIER && !CaptureIntents.canWriteExternalStorage(this)) {
            FloatingOverlayService.setHiddenForScreenshot(this, false)
            CaptureIntents.toast(this, "Storage permission is needed to save captures on this Android version.")
            finishSafely()
            return
        }
        val consent = CaptureIntents.projectionConsentIntent(this, wholeDisplay = mode == CaptureContract.MODE_MAGNIFIER)
        if (consent == null) {
            FloatingOverlayService.setHiddenForScreenshot(this, false)
            CaptureIntents.toast(this, "Screen capture is not available on this device.")
            finishSafely()
            return
        }
        runCatching { projectionLauncher.launch(consent) }.onFailure {
            FloatingOverlayService.setHiddenForScreenshot(this, false)
            CaptureIntents.toast(this, "Could not open Android's screen-capture prompt.")
            finishSafely()
        }
    }

    // ------------------------------------------------------------------ camera

    private fun continueCameraRecording() {
        if (ScreenCaptureService.isActive) {
            CaptureIntents.toast(this, "Finish the screen capture before starting a camera recording.")
            finishSafely()
            return
        }
        if (!CaptureIntents.hasPermission(this, Manifest.permission.CAMERA)) {
            CaptureIntents.toast(this, "Camera permission is required to record video.")
            finishSafely()
            return
        }
        if (!CaptureIntents.canWriteExternalStorage(this)) {
            CaptureIntents.toast(this, "Storage permission is needed to save a camera video.")
            finishSafely()
            return
        }
        val audio = SettingsStore.audioMode(this)
        if (!CaptureIntents.startCameraRecording(this)) {
            CaptureIntents.toast(this, "Could not start the camera recording.")
        } else if (audio.usesMicrophone && !CaptureIntents.hasPermission(this, Manifest.permission.RECORD_AUDIO)) {
            CaptureIntents.toast(this, "Recording video without sound.")
        }
        finishSafely()
    }

    private fun startCameraPreviewFlow() {
        requestPermissions(CaptureContract.MODE_CAMERA_PREVIEW) { continueCameraPreview() }
    }

    private fun continueCameraPreview() {
        if (!CaptureIntents.hasPermission(this, Manifest.permission.CAMERA)) {
            CaptureIntents.toast(this, "Camera permission is required for the live preview.")
            finishSafely()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            overlaySettingsPending = true
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            runCatching { overlaySettingsLauncher.launch(intent) }.onFailure {
                CaptureIntents.toast(this, "Allow display over other apps to show the camera preview.")
                finishSafely()
            }
            return
        }
        val front = SettingsStore.cameraFront(this)
        if (!CaptureIntents.startCameraPreview(this, front)) {
            CaptureIntents.toast(this, "Could not start the camera preview.")
        } else {
            SettingsStore.setCameraOverlayEnabled(this, true)
        }
        finishSafely()
    }

    private fun flipCamera() {
        val front = !SettingsStore.cameraFront(this)
        SettingsStore.setCameraFront(this, front)
        val label = if (front) "Front camera" else "Back camera"
        when {
            CameraCaptureService.isRecording ->
                CaptureIntents.toast(this, "Stop the camera recording before switching cameras.")
            CameraCaptureService.isActive -> {
                CaptureIntents.setCameraFacing(this, front)
                CaptureIntents.toast(this, label)
            }
            else -> CaptureIntents.toast(this, "$label selected for the next capture")
        }
    }

    // ------------------------------------------------------------------ audio

    private fun cycleAudio() {
        val next = SettingsStore.audioMode(this).next()
        if (next.requiresRecordPermission &&
            !CaptureIntents.hasPermission(this, Manifest.permission.RECORD_AUDIO)
        ) {
            pendingPermissionAction = { applyAudioMode(next) }
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        applyAudioMode(next)
    }

    private fun applyAudioMode(requested: CaptureAudioMode) {
        val applied = CaptureIntents.effectiveAudioMode(this, requested)
        SettingsStore.setAudioMode(this, applied)
        if (requested.usesDeviceAudio && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            CaptureIntents.toast(this, "Device audio needs Android 10 or newer.")
        } else if (applied != requested) {
            CaptureIntents.toast(this, "Microphone permission is off, so sound was set to ${applied.label}.")
        } else if (ScreenCaptureService.isRecording) {
            CaptureIntents.toast(this, "Screen-recording audio is fixed when the MP4 starts; this applies next time.")
        } else {
            if (CameraCaptureService.isActive) {
                CaptureIntents.setCameraAudioMuted(this, !applied.usesMicrophone)
            }
            CaptureIntents.toast(this, "Sound: ${applied.label}")
        }
        finishSafely()
    }

    // ------------------------------------------------------------------ helpers

    private fun requestPermissions(mode: String, action: () -> Unit) {
        val missing = CaptureIntents.requiredPermissions(this, mode)
        if (missing.isEmpty()) {
            action()
            return
        }
        pendingPermissionAction = action
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun openMainApp() {
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

    @Suppress("DEPRECATION")
    private fun finishSafely() {
        runCatching { finish() }
        runCatching { overridePendingTransition(0, 0) }
    }

    private companion object {
        const val KEY_COMMAND = "screenkit.request.command"
        const val KEY_MODE = "screenkit.request.mode"
        const val KEY_OVERLAY_SETTINGS = "screenkit.request.overlay_settings"
        const val WATCHDOG_MS = 90_000L
    }
}
