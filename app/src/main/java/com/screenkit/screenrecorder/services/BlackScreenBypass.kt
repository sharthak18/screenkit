package com.screenkit.screenrecorder.services

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.PixelCopy
import android.view.Surface
import android.view.View
import android.view.Window
import android.view.WindowManager
import java.util.concurrent.TimeUnit

/**
 * Android-level, Hardware/OpenGL-level, and WebView-level utilities to prevent or bypass
 * black screens during screenshots and screen recordings.
 */
object BlackScreenBypass {

    /**
     * Ensures the given Activity window never carries FLAG_SECURE and uses hardware-accelerated
     * RGBA_8888 rendering suitable for direct PixelCopy and MediaProjection capture.
     */
    fun prepareWindowForCapture(window: Window) {
        runCatching {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            window.setFormat(PixelFormat.RGBA_8888)
        }
    }

    /**
     * Best-effort OS/secure settings configuration on devices with WRITE_SECURE_SETTINGS or root.
     */
    fun trySystemSecureBypass(context: Context) {
        try {
            Settings.Global.putInt(context.contentResolver, "disable_secure_windows", 1)
        } catch (_: Exception) {}
        try {
            Settings.Secure.putInt(context.contentResolver, "disable_secure_windows", 1)
        } catch (_: Exception) {}
    }

    /**
     * Creates a VirtualDisplay attempting presentation/public mirror flags first when hardware
     * bypass is enabled, falling back cleanly to standard auto-mirror on strict ROMs.
     */
    fun createResilientVirtualDisplay(
        projection: MediaProjection,
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        handler: Handler,
        hwBypass: Boolean,
    ): VirtualDisplay? {
        val candidateFlags = if (hwBypass) {
            intArrayOf(
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            )
        } else {
            intArrayOf(DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
        }

        for (flags in candidateFlags) {
            val display = runCatching {
                projection.createVirtualDisplay(
                    name,
                    width,
                    height,
                    densityDpi,
                    flags,
                    surface,
                    null,
                    handler,
                )
            }.getOrNull()
            if (display != null) return display
        }
        return null
    }

    /**
     * Returns true if the bitmap consists entirely of black or near-black pixels (e.g., initial
     * uninitialized GPU compositor frame or a FLAG_SECURE masked buffer).
     */
    fun isFrameBlackOrBlank(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return true
        val stepX = (bitmap.width / 14).coerceAtLeast(1)
        val stepY = (bitmap.height / 14).coerceAtLeast(1)
        var sampled = 0
        var dark = 0
        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                sampled++
                val a = Color.alpha(pixel)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (a == 0 || (r <= 3 && g <= 3 && b <= 3)) {
                    dark++
                }
                x += stepX
            }
            y += stepY
        }
        return sampled > 0 && dark == sampled
    }

    /**
     * Attempts a hardware framebuffer screencap (`screencap -p`) if MediaProjection returned a
     * black frame and the device grants shell or root access.
     */
    fun tryHardwareScreencapFallback(cropRect: RectF?): Bitmap? {
        val commands = listOf(
            arrayOf("su", "-c", "screencap -p"),
            arrayOf("sh", "-c", "screencap -p"),
        )
        for (cmd in commands) {
            val decoded = runCatching {
                val proc = Runtime.getRuntime().exec(cmd)
                val bytes = proc.inputStream.readBytes()
                proc.waitFor(1500, TimeUnit.MILLISECONDS)
                if (bytes.size > 256) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else {
                    null
                }
            }.getOrNull()
            if (decoded != null && !isFrameBlackOrBlank(decoded)) {
                return cropBitmap(decoded, cropRect)
            } else {
                decoded?.recycle()
            }
        }
        return null
    }

    /**
     * Captures a view/window directly from hardware memory via PixelCopy (API 26+) with automatic
     * fallback to software Canvas drawing, completely independent of MediaProjection.
     */
    fun captureViewDirectly(
        activity: Activity,
        targetView: View,
        onResult: (Bitmap?) -> Unit,
    ) {
        val w = targetView.width.coerceAtLeast(1)
        val h = targetView.height.coerceAtLeast(1)
        prepareWindowForCapture(activity.window)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val loc = IntArray(2)
            targetView.getLocationInWindow(loc)
            val rect = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
            val hwBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(
                    activity.window,
                    rect,
                    hwBitmap,
                    { copyResult ->
                        if (copyResult == PixelCopy.SUCCESS && !isFrameBlackOrBlank(hwBitmap)) {
                            onResult(hwBitmap)
                        } else {
                            hwBitmap.recycle()
                            onResult(drawViewToCanvasBitmap(targetView, w, h))
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
                return
            } catch (_: Exception) {
                hwBitmap.recycle()
            }
        }
        onResult(drawViewToCanvasBitmap(targetView, w, h))
    }

    private fun drawViewToCanvasBitmap(targetView: View, w: Int, h: Int): Bitmap? = runCatching {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        targetView.draw(canvas)
        bitmap
    }.getOrNull()

    private fun cropBitmap(src: Bitmap, crop: RectF?): Bitmap {
        if (crop == null) return src
        val left = crop.left.toInt().coerceIn(0, src.width - 1)
        val top = crop.top.toInt().coerceIn(0, src.height - 1)
        val right = crop.right.toInt().coerceIn(left + 1, src.width)
        val bottom = crop.bottom.toInt().coerceIn(top + 1, src.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return src
        return runCatching {
            Bitmap.createBitmap(src, left, top, w, h).also {
                if (it != src) src.recycle()
            }
        }.getOrDefault(src)
    }

    /**
     * JavaScript injected into WebCaptureActivity to neutralize website anti-screenshot/recording
     * scripts, focus-loss blackouts, WebGL buffer clearing, and hardware-secure EME overlay surfaces.
     */
    const val WEB_BYPASS_INJECTION_JS = """
        (function() {
            if (window.__screenkitBypassApplied) return;
            window.__screenkitBypassApplied = true;
            try {
                Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                Object.defineProperty(document, 'webkitHidden', { get: function() { return false; }, configurable: true });
                Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                document.hasFocus = function() { return true; };
            } catch (e) {}

            try {
                var blockedEvents = { 'visibilitychange': 1, 'webkitvisibilitychange': 1, 'blur': 1, 'focusout': 1, 'pagehide': 1 };
                var origDocAdd = EventTarget.prototype.addEventListener;
                EventTarget.prototype.addEventListener = function(type, listener, options) {
                    if (type && blockedEvents[String(type).toLowerCase()]) {
                        return;
                    }
                    return origDocAdd.call(this, type, listener, options);
                };
            } catch (e) {}

            try {
                if (navigator.requestMediaKeySystemAccess) {
                    var origRmk = navigator.requestMediaKeySystemAccess.bind(navigator);
                    navigator.requestMediaKeySystemAccess = function(keySystem, supportedConfigurations) {
                        try {
                            var rewritten = (supportedConfigurations || []).map(function(cfg) {
                                var copy = Object.assign({}, cfg);
                                if (copy.videoCapabilities) {
                                    copy.videoCapabilities = copy.videoCapabilities.map(function(vc) {
                                        var vcCopy = Object.assign({}, vc);
                                        if (vcCopy.robustness && vcCopy.robustness.indexOf('HW_SECURE') !== -1) {
                                            vcCopy.robustness = 'SW_SECURE_CRYPTO';
                                        }
                                        return vcCopy;
                                    });
                                }
                                if (copy.audioCapabilities) {
                                    copy.audioCapabilities = copy.audioCapabilities.map(function(ac) {
                                        var acCopy = Object.assign({}, ac);
                                        if (acCopy.robustness && acCopy.robustness.indexOf('HW_SECURE') !== -1) {
                                            acCopy.robustness = 'SW_SECURE_CRYPTO';
                                        }
                                        return acCopy;
                                    });
                                }
                                return copy;
                            });
                            return origRmk(keySystem, rewritten).catch(function() {
                                return origRmk(keySystem, supportedConfigurations);
                            });
                        } catch (err) {
                            return origRmk(keySystem, supportedConfigurations);
                        }
                    };
                }
            } catch (e) {}

            try {
                var origGetContext = HTMLCanvasElement.prototype.getContext;
                HTMLCanvasElement.prototype.getContext = function(type, attrs) {
                    if (type === 'webgl' || type === 'webgl2' || type === 'experimental-webgl') {
                        attrs = Object.assign({}, attrs || {}, { preserveDrawingBuffer: true });
                    }
                    return origGetContext.call(this, type, attrs);
                };
            } catch (e) {}

            try {
                var style = document.createElement('style');
                style.textContent = '* { -webkit-user-select: auto !important; user-select: auto !important; }';
                (document.head || document.documentElement).appendChild(style);
            } catch (e) {}
        })();
    """
}
