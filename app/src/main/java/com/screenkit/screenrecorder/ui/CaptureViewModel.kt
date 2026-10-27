package com.screenkit.screenrecorder.ui

import androidx.lifecycle.ViewModel
import com.screenkit.screenrecorder.data.AutoSavedProfile
import com.screenkit.screenrecorder.data.AutoScrollDirection
import com.screenkit.screenrecorder.data.AutoToolMode
import com.screenkit.screenrecorder.data.CaptureAudioMode
import com.screenkit.screenrecorder.data.GithubReleaseSummary
import com.screenkit.screenrecorder.data.MediaCapture
import com.screenkit.screenrecorder.data.ProjectionScope
import com.screenkit.screenrecorder.data.SaveLocationMode
import com.screenkit.screenrecorder.data.StorageVolumes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class CameraFacing { FRONT, BACK }

internal data class CaptureUiState(
    val overlayPermission: Boolean = false,
    val overlayEnabled: Boolean = false,
    val cameraPermission: Boolean = false,
    val microphonePermission: Boolean = false,
    val notificationPermission: Boolean = true,
    val storagePermission: Boolean = true,
    val microphoneEnabled: Boolean = false,
    val cameraOverlayEnabled: Boolean = false,
    val cameraFacing: CameraFacing = CameraFacing.BACK,
    val audioMode: CaptureAudioMode = CaptureAudioMode.NONE,
    val deviceAudioSupported: Boolean = false,
    val saveMode: SaveLocationMode = SaveLocationMode.MEDIA_DEFAULT,
    val saveLocationLabel: String = "",
    val volumes: List<StorageVolumes.VolumeChoice> = emptyList(),
    val selectedVolume: String? = null,
    val projectionScope: ProjectionScope = ProjectionScope.ENTIRE_SCREEN,
    val screenSessionActive: Boolean = false,
    val screenRecording: Boolean = false,
    val screenPaused: Boolean = false,
    val screenshotInProgress: Boolean = false,
    val screenRecordingStartedAt: Long = 0L,
    val cameraSessionActive: Boolean = false,
    val cameraRecording: Boolean = false,
    val cameraPreviewVisible: Boolean = false,
    val cameraRecordingStartedAt: Long = 0L,
    val showTouches: Boolean = false,
    val hwGlBypass: Boolean = true,
    val autoAccessibilityEnabled: Boolean = false,
    val autoOverlayShowing: Boolean = false,
    val autoExecuting: Boolean = false,
    val autoToolMode: AutoToolMode = AutoToolMode.CLICK,
    val autoClickPointCount: Int = 3,
    val autoClickDelayMs: Long = 500L,
    val autoClickSpeedMs: Long = 55L,
    val autoClickTapsPerPoint: Int = 1,
    val autoClickTotalCycles: Int = 10,
    val autoScrollDirection: AutoScrollDirection = AutoScrollDirection.DOWN,
    val autoScrollSpeedMs: Long = 450L,
    val autoScrollIntervalMs: Long = 1500L,
    val autoScrollDurationSec: Int = 120,
    val autoScrollDistancePct: Int = 55,
    val autoRandomJitter: Boolean = true,
    val autoColorTriggerEnabled: Boolean = false,
    val autoColorTriggerHex: String = "#4CAF50",
    val autoMacroStepCount: Int = 0,
    val autoSavedProfiles: List<AutoSavedProfile> = emptyList(),
    val shakeToStopEnabled: Boolean = true,
    val volumeKeyStopEnabled: Boolean = true,
    val teleprompterShowing: Boolean = false,
    val teleprompterScript: String = "",
    val magnifierShowing: Boolean = false,
    val watermarkEnabled: Boolean = false,
    val watermarkText: String = "Recorded with ScreenKit",
    val statAppOpens: Int = 1,
    val statCapturesSaved: Int = 0,
    val statAutoGestures: Int = 0,
    val githubReleaseSummary: GithubReleaseSummary? = null,
    val recentCaptures: List<MediaCapture> = emptyList(),
)

/** UI state is fed by Android permission checks and foreground-service state broadcasts. */
internal class CaptureViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = mutableState.asStateFlow()

    fun update(transform: (CaptureUiState) -> CaptureUiState) {
        mutableState.update(transform)
    }

    fun setRecentCaptures(captures: List<MediaCapture>) {
        mutableState.update { it.copy(recentCaptures = captures) }
    }

    fun setCameraFacing(facing: CameraFacing) {
        mutableState.update { it.copy(cameraFacing = facing) }
    }
}
