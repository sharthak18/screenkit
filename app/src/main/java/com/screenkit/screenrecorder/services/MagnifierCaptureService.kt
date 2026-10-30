package com.screenkit.screenrecorder.services

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.screenkit.screenrecorder.CaptureContract
import com.screenkit.screenrecorder.R
import com.screenkit.screenrecorder.ui.CreatorToolsOverlay
import java.util.concurrent.atomic.AtomicBoolean

/** User-consented live feed for the loupe. No audio, file saving, network, or screenshot history. */
class MagnifierCaptureService : Service() {
    private val main = Handler(android.os.Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var reader: ImageReader? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    @Volatile private var closed = false
    private var lastFrameAt = 0L
    private val deliveryPending = AtomicBoolean(false)
    private var screenWidth = 0
    private var screenHeight = 0
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
        override fun onCapturedContentResize(width: Int, height: Int) {
            // A fresh consent/feed is safer than sampling incorrect coordinates after rotation/app-only sharing.
            if (width != screenWidth || height != screenHeight) {
                Toast.makeText(this@MagnifierCaptureService, "Screen size changed. Reopen Zoom and share the entire screen.", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }
    }
    private val rotationCheck = object : Runnable {
        override fun run() {
            if (closed) return
            val metrics = metrics()
            if (metrics.widthPixels != screenWidth || metrics.heightPixels != screenHeight) {
                Toast.makeText(this@MagnifierCaptureService, "Screen rotated. Reopen Zoom to realign the live feed.", Toast.LENGTH_SHORT).show()
                stopSelf()
            } else main.postDelayed(this, 1000L)
        }
    }
    companion object {
        private const val CHANNEL = "live_magnifier"
        private const val NOTIFICATION = 5104
        const val STOP = "screenkit.magnifier.STOP"
        @Volatile var active = false
            private set
        fun stop(context: Context) { context.stopService(Intent(context, MagnifierCaptureService::class.java)) }
    }
    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (active) return START_NOT_STICKY
        try {
            if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Live screen magnifier", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 5104, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_capture)
                .setContentTitle("Live magnifier active").setContentText("Screen pixels are processed on-device only. Tap Stop to end sharing.")
                .setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).addAction(0, "Stop Zoom", stop).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(NOTIFICATION, notification)
            check(android.provider.Settings.canDrawOverlays(this) && !ScreenCaptureService.isActive && !CameraCaptureService.isRecording) { "Capture is busy" }
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(CaptureContract.EXTRA_RESULT_DATA, Intent::class.java)
                else intent.getParcelableExtra(CaptureContract.EXTRA_RESULT_DATA)
            check(intent.getIntExtra(CaptureContract.EXTRA_RESULT_CODE, 0) == Activity.RESULT_OK && data != null)
            val m = metrics(); screenWidth = m.widthPixels; screenHeight = m.heightPixels
            val scale = minOf(1.0, 1600.0 / maxOf(screenWidth, screenHeight))
            val width = (screenWidth * scale).toInt().coerceAtLeast(2)
            val height = (screenHeight * scale).toInt().coerceAtLeast(2)
            worker = HandlerThread("LiveMagnifierPixels").also { it.start() }
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = imageReader
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, data!!)
            projection!!.registerCallback(callback, main)
            imageReader.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                image.use {
                    val now = SystemClock.elapsedRealtime()
                    if (closed || now - lastFrameAt < 200 || !deliveryPending.compareAndSet(false, true)) return@setOnImageAvailableListener
                    lastFrameAt = now
                    try {
                        val plane = it.planes[0]
                        val paddedWidth = plane.rowStride / plane.pixelStride
                        val padded = Bitmap.createBitmap(paddedWidth, it.height, Bitmap.Config.ARGB_8888)
                        val frame = try {
                            padded.copyPixelsFromBuffer(plane.buffer)
                            if (paddedWidth == it.width) padded else Bitmap.createBitmap(padded, 0, 0, it.width, it.height)
                        } catch (error: Exception) { padded.recycle(); throw error }
                        if (frame !== padded) padded.recycle()
                        main.post {
                            try {
                                if (closed) frame.recycle()
                                else CreatorToolsOverlay.updateLiveFrame(frame, screenWidth, screenHeight)
                            } finally { deliveryPending.set(false) }
                        }
                    } catch (_: Exception) { deliveryPending.set(false); main.post { stopSelf() } }
                }
            }, Handler(worker!!.looper))
            display = projection!!.createVirtualDisplay("ScreenKit live magnifier", width, height, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null)
            active = true
            CreatorToolsOverlay.showMagnifier(this)
            main.postDelayed(rotationCheck, 1000L)
        } catch (_: Exception) {
            Toast.makeText(this, "Zoom could not start. Stop other captures, allow overlays, and approve whole-screen sharing.", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }
    @Suppress("DEPRECATION")
    private fun metrics(): DisplayMetrics = DisplayMetrics().also {
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
    }
    override fun onDestroy() {
        closed = true; active = false
        main.removeCallbacks(rotationCheck)
        reader?.setOnImageAvailableListener(null, null)
        runCatching { display?.release() }; display = null
        runCatching { reader?.close() }; reader = null
        runCatching { projection?.unregisterCallback(callback); projection?.stop() }; projection = null
        worker?.quitSafely(); worker = null
        CreatorToolsOverlay.hideMagnifier(stopFeed = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
