package com.screenkit.screenrecorder.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.screenkit.screenrecorder.CaptureContract
import com.screenkit.screenrecorder.CaptureIntents
import com.screenkit.screenrecorder.data.SettingsStore
import com.screenkit.screenrecorder.ui.AutoClickScrollOverlay
import com.screenkit.screenrecorder.ui.TouchFeedbackOverlay

/**
 * AccessibilityService that dispatches system-wide multi-target auto-clicking (1 → 2 → 3 → 4…)
 * and directional auto-scrolling (up, down, left, right) across any app on Android 7.0+.
 */
class ScreenKitAutoService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        broadcastState(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // ScreenKit does not read window content; it only dispatches user-started gestures.
    }

    override fun onInterrupt() {
        AutoClickScrollOverlay.stopExecution()
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
            (AutoClickScrollOverlay.isExecuting || ScreenCaptureService.isLive) &&
            SettingsStore.volumeKeyStopEnabled(this)
        ) {
            if (AutoClickScrollOverlay.isExecuting) AutoClickScrollOverlay.stopExecution()
            if (ScreenCaptureService.isLive) CaptureIntents.stopScreenCapture(this)
            CaptureIntents.toast(this, "Volume Down pressed: active live stream / automation stopped.")
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AutoClickScrollOverlay.stopExecution()
        if (instance === this) {
            instance = null
        }
        broadcastState(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AutoClickScrollOverlay.stopExecution()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    /**
     * Dispatches a single tap at `(x, y)` lasting `durationMs` milliseconds.
     */
    fun performTap(
        x: Float,
        y: Float,
        durationMs: Long,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        val safeX = x.coerceAtLeast(1f)
        val safeY = y.coerceAtLeast(1f)
        val holdMs = durationMs.coerceIn(15L, 2_500L)
        val path = Path().apply {
            moveTo(safeX, safeY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, holdMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        TouchFeedbackOverlay.recordSyntheticTap(safeX, safeY)
        SettingsStore.incrementAutoGestures(this, 1)

        val dispatched = runCatching {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        mainHandler.post { onComplete?.invoke(true) }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        mainHandler.post { onComplete?.invoke(false) }
                    }
                },
                mainHandler,
            )
        }.getOrDefault(false)

        if (!dispatched) {
            mainHandler.post { onComplete?.invoke(false) }
        }
    }

    /**
     * Dispatches a smooth swipe from `(startX, startY)` to `(endX, endY)` over `durationMs`.
     */
    fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        val swipeMs = durationMs.coerceIn(80L, 4_000L)
        val path = Path().apply {
            moveTo(startX.coerceAtLeast(1f), startY.coerceAtLeast(1f))
            lineTo(endX.coerceAtLeast(1f), endY.coerceAtLeast(1f))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, swipeMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        TouchFeedbackOverlay.recordSyntheticTap(
            (startX + endX) * 0.5f,
            (startY + endY) * 0.5f,
        )
        SettingsStore.incrementAutoGestures(this, 1)

        val dispatched = runCatching {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        mainHandler.post { onComplete?.invoke(true) }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        mainHandler.post { onComplete?.invoke(false) }
                    }
                },
                mainHandler,
            )
        }.getOrDefault(false)

        if (!dispatched) {
            mainHandler.post { onComplete?.invoke(false) }
        }
    }

    companion object {
        @Volatile
        var instance: ScreenKitAutoService? = null
            private set

        /**
         * Returns true if ScreenKit's AccessibilityService is active or enabled in Android Settings.
         */
        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            val expected = ComponentName(context, ScreenKitAutoService::class.java)
            val enabledServices = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            }.getOrNull().orEmpty()
            if (enabledServices.isBlank()) return false
            return enabledServices.split(':').any { entry ->
                ComponentName.unflattenFromString(entry) == expected
            }
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }

        fun broadcastState(context: Context) {
            val appContext = context.applicationContext
            appContext.sendBroadcast(
                Intent(CaptureContract.ACTION_AUTO_STATE).setPackage(appContext.packageName),
            )
        }
    }
}
