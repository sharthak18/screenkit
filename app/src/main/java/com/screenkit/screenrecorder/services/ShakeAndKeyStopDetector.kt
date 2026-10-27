package com.screenkit.screenrecorder.services

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.screenkit.screenrecorder.CaptureIntents
import com.screenkit.screenrecorder.data.SettingsStore
import com.screenkit.screenrecorder.ui.AutoClickScrollOverlay
import kotlin.math.sqrt

/**
 * Detects a deliberate phone shake so the user can immediately stop an active screen recording
 * or emergency-stop the Auto Clicker / Auto Scroller without touching the screen.
 */
internal object ShakeAndKeyStopDetector : SensorEventListener {
    private var appContext: Context? = null
    private var sensorManager: SensorManager? = null
    private var registered = false
    private var firstPeakAtMs = 0L
    private var lastTriggeredAtMs = 0L

    fun start(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (registered) return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager = sm
        runCatching {
            registered = sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        if (!registered) return
        runCatching { sensorManager?.unregisterListener(this) }
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val ctx = appContext ?: return
        if (!SettingsStore.shakeToStopEnabled(ctx)) return
        val autoRunning = AutoClickScrollOverlay.isExecuting
        val screenRecRunning = ScreenCaptureService.isRecording
        if (!autoRunning && !screenRecRunning) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val netG = magnitude - SensorManager.GRAVITY_EARTH
        if (netG < 11.5f) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggeredAtMs < 2200L) return

        if (now - firstPeakAtMs in 110L..850L) {
            firstPeakAtMs = 0L
            lastTriggeredAtMs = now
            if (autoRunning) {
                AutoClickScrollOverlay.stopExecution()
                CaptureIntents.toast(ctx, "Shake detected: Auto Click / Scroll stopped.")
            }
            if (screenRecRunning) {
                CaptureIntents.stopScreenCapture(ctx)
                CaptureIntents.toast(ctx, if (ScreenCaptureService.isLive) "Shake detected: ending live stream…" else "Shake detected: Saving screen recording…")
            }
        } else {
            firstPeakAtMs = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
