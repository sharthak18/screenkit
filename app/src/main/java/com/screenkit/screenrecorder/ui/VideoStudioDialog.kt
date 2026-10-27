package com.screenkit.screenrecorder.ui

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screenkit.screenrecorder.data.CaptureKind
import com.screenkit.screenrecorder.data.CaptureSink
import com.screenkit.screenrecorder.data.LegacyStorage
import com.screenkit.screenrecorder.data.MediaCapture
import com.screenkit.screenrecorder.data.SettingsStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Built-in Video Studio dialog for any recorded MP4 in Recent Captures:
 * 1. **Lossless MP4 Video Trimmer** (Start % to End % via MediaExtractor + MediaMuxer).
 * 2. **High-Res PNG Frame Grabber** (Extracts any frame at chosen timestamp to PNG).
 * 3. **Animated GIF Maker** (Converts clip range into a shareable animated .gif in Gallery).
 */
@Composable
internal fun VideoStudioDialog(
    context: Context,
    capture: MediaCapture,
    onDismiss: () -> Unit,
    onCompleted: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var startFraction by remember { mutableFloatStateOf(0.0f) }
    var endFraction by remember { mutableFloatStateOf(1.0f) }
    var frameFraction by remember { mutableFloatStateOf(0.5f) }
    var busyMessage by remember { mutableStateOf<String?>(null) }

    val panelBg = androidx.compose.ui.graphics.Color(0xFF162025)
    val lime = androidx.compose.ui.graphics.Color(0xFFB7F36B)
    val ink = androidx.compose.ui.graphics.Color(0xFF10170D)
    val softWhite = androidx.compose.ui.graphics.Color(0xFFF1F6F2)
    val muted = androidx.compose.ui.graphics.Color(0xFF98A7A1)

    AlertDialog(
        onDismissRequest = { if (busyMessage == null) onDismiss() },
        containerColor = panelBg,
        title = {
            Column {
                Text("VIDEO STUDIO & GIF MAKER", color = lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(capture.title, color = softWhite, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Trim Start: ${(startFraction * 100).toInt()}%  →  End: ${(endFraction * 100).toInt()}%",
                    color = softWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = startFraction,
                    onValueChange = { v ->
                        startFraction = v.coerceIn(0f, (endFraction - 0.1f).coerceAtLeast(0f))
                    },
                    enabled = busyMessage == null,
                )
                Slider(
                    value = endFraction,
                    onValueChange = { v ->
                        endFraction = v.coerceIn((startFraction + 0.1f).coerceAtMost(1f), 1f)
                    },
                    enabled = busyMessage == null,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            busyMessage = "Trimming MP4 video…"
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    VideoStudioEngine.trimMp4(context, capture.uri, startFraction, endFraction)
                                }
                                busyMessage = null
                                if (ok) {
                                    SettingsStore.incrementCapturesSaved(context)
                                    onCompleted("Trimmed MP4 saved to your library!")
                                } else {
                                    onCompleted("Could not trim this video.")
                                }
                            }
                        },
                        enabled = busyMessage == null,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = lime, contentColor = ink),
                    ) {
                        Text("✂ Save Trim", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            busyMessage = "Building animated GIF…"
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    VideoStudioEngine.exportGif(context, capture.uri, startFraction, endFraction)
                                }
                                busyMessage = null
                                if (ok) {
                                    SettingsStore.incrementCapturesSaved(context)
                                    onCompleted("Animated GIF saved to Pictures/ScreenCapture!")
                                } else {
                                    onCompleted("Could not create animated GIF from this video.")
                                }
                            }
                        },
                        enabled = busyMessage == null,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = androidx.compose.ui.graphics.Color(0xFF8FE0C1),
                            contentColor = ink,
                        ),
                    ) {
                        Text("🎞 Export GIF", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFF2A383D))

                Text(
                    "Extract Frame Screenshot at ${(frameFraction * 100).toInt()}% of video",
                    color = softWhite,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = frameFraction,
                    onValueChange = { frameFraction = it.coerceIn(0f, 1f) },
                    enabled = busyMessage == null,
                )
                OutlinedButton(
                    onClick = {
                        busyMessage = "Extracting PNG frame…"
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                VideoStudioEngine.extractFramePng(context, capture.uri, frameFraction)
                            }
                            busyMessage = null
                            if (ok) {
                                SettingsStore.incrementCapturesSaved(context)
                                onCompleted("Frame PNG saved to your screenshot library!")
                            } else {
                                onCompleted("Could not extract frame from this video.")
                            }
                        }
                    },
                    enabled = busyMessage == null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = lime),
                ) {
                    Text("📸 Save Frame as PNG Screenshot", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                if (busyMessage != null) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = androidx.compose.ui.graphics.Color(0xFF1E2D33),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            busyMessage!!,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = lime,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    Text(
                        "All trimmed MP4s, GIFs, and extracted PNG frames are saved directly on your device.",
                        color = muted,
                        fontSize = 10.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = busyMessage == null) {
                Text("Close", color = muted, fontWeight = FontWeight.Bold)
            }
        },
    )
}

internal object VideoStudioEngine {
    @SuppressLint("WrongConstant")
    fun trimMp4(
        context: Context,
        sourceUri: Uri,
        startRatio: Float,
        endRatio: Float,
    ): Boolean {
        val extractor = MediaExtractor()
        val tempOut = File(CaptureSink.cacheDirectory(context), "trim_${System.currentTimeMillis()}.mp4")
        try {
            val pfd = context.contentResolver.openFileDescriptor(sourceUri, "r") ?: return false
            pfd.use {
                extractor.setDataSource(it.fileDescriptor)
            }
            var durationUs = 0L
            val trackCount = extractor.trackCount
            for (i in 0 until trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = maxOf(durationUs, fmt.getLong(MediaFormat.KEY_DURATION))
                }
            }
            if (durationUs <= 0L) durationUs = 5_000_000L
            val startUs = (durationUs * startRatio.coerceIn(0f, 0.92f)).toLong()
            val endUs = (durationUs * endRatio.coerceIn(startRatio + 0.05f, 1f)).toLong()

            val muxer = MediaMuxer(tempOut.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = HashMap<Int, Int>()
            for (i in 0 until trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    trackMap[i] = muxer.addTrack(fmt)
                }
            }
            if (trackMap.isEmpty()) {
                muxer.release()
                return false
            }
            muxer.start()
            if (startUs > 0L) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var basePtsUs = -1L
            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) break
                if (sampleTimeUs >= startUs) {
                    if (basePtsUs < 0L) basePtsUs = sampleTimeUs
                    val dstTrack = trackMap[trackIndex]
                    if (dstTrack != null) {
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = (sampleTimeUs - basePtsUs).coerceAtLeast(0L)
                        info.flags = extractor.sampleFlags
                        muxer.writeSampleData(dstTrack, buffer, info)
                    }
                }
                extractor.advance()
            }
            runCatching { muxer.stop() }
            runCatching { muxer.release() }

            if (!tempOut.exists() || tempOut.length() <= 0L) return false
            val sink = CaptureSink.create(context, CaptureKind.SCREEN_RECORDING, ".mp4") ?: return false
            val written = sink.openStreamForWrite(context)?.use { out ->
                tempOut.inputStream().use { input -> input.copyTo(out) }
                out.flush()
                true
            } ?: false
            if (!written) {
                sink.discard(context)
                return false
            }
            return sink.publish(context) != null
        } catch (_: Throwable) {
            return false
        } finally {
            runCatching { extractor.release() }
            runCatching { tempOut.delete() }
        }
    }

    fun extractFramePng(context: Context, sourceUri: Uri, fraction: Float): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, sourceUri)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 2_000L
            val targetUs = (durMs * 1_000L * fraction.coerceIn(0f, 1f)).toLong()
            val bmp = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(0L)
                ?: return false
            val sink = CaptureSink.create(context, CaptureKind.SCREENSHOT, ".png") ?: return false
            val ok = sink.openStreamForWrite(context)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: false
            bmp.recycle()
            if (!ok) {
                sink.discard(context)
                false
            } else {
                sink.publish(context) != null
            }
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun exportGif(
        context: Context,
        sourceUri: Uri,
        startRatio: Float,
        endRatio: Float,
    ): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, sourceUri)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(500L) ?: 2_000L
            val startMs = (durMs * startRatio.coerceIn(0f, 0.9f)).toLong()
            val endMs = (durMs * endRatio.coerceIn(startRatio + 0.1f, 1f)).toLong()
            val spanMs = (endMs - startMs).coerceAtLeast(300L)
            val frameCount = 10
            val frames = mutableListOf<Bitmap>()

            for (i in 0 until frameCount) {
                val tUs = (startMs + (spanMs * i) / frameCount) * 1_000L
                val raw = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                val targetW = 280
                val targetH = ((raw.height.toFloat() / raw.width.coerceAtLeast(1)) * targetW)
                    .toInt().coerceIn(120, 560)
                val scaled = Bitmap.createScaledBitmap(raw, targetW, targetH, true)
                if (scaled !== raw) raw.recycle()
                frames.add(scaled)
            }
            if (frames.isEmpty()) return false

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "ScreenCapture_$timestamp.gif"
            val outStream = openGifOutputStream(context, fileName) ?: return false
            outStream.use { out ->
                SimpleGif89Encoder.writeAnimatedGif(out, frames, delayCs = 14)
            }
            frames.forEach { it.recycle() }
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun openGifOutputStream(context: Context, displayName: String): OutputStream? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ScreenCapture/")
            } else {
                val dir = LegacyStorage.publicDirectory(CaptureKind.SCREENSHOT)
                if (!dir.exists()) dir.mkdirs()
                put(MediaStore.MediaColumns.DATA, File(dir, displayName).absolutePath)
            }
        }
        val uri = runCatching {
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        return runCatching { context.contentResolver.openOutputStream(uri, "w") }.getOrNull()
    }
}

/**
 * Compact GIF89a animated encoder using a universal 256-color RGB332 global palette
 * and 8-bit uncompressed LZW blocks so every GIF viewer plays it smoothly.
 */
private object SimpleGif89Encoder {
    fun writeAnimatedGif(out: OutputStream, frames: List<Bitmap>, delayCs: Int) {
        val w = frames.first().width
        val h = frames.first().height
        val buf = ByteArrayOutputStream()

        // 1. Header "GIF89a"
        buf.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShortLE(buf, w)
        writeShortLE(buf, h)
        // Global color table flag = 1, color res = 7, sort = 0, size = 7 (256 colors) -> 0xF7
        buf.write(0xF7)
        buf.write(0) // background index
        buf.write(0) // pixel aspect ratio

        // 2. 256-color RGB332 Global Palette (3 bits R, 3 bits G, 2 bits B)
        for (i in 0 until 256) {
            val r = ((i shr 5) and 0x07) * 255 / 7
            val g = ((i shr 2) and 0x07) * 255 / 7
            val b = (i and 0x03) * 255 / 3
            buf.write(r)
            buf.write(g)
            buf.write(b)
        }

        // 3. Netscape 2.0 infinite loop extension
        buf.write(
            byteArrayOf(
                0x21, 0xFF.toByte(), 0x0B,
                'N'.code.toByte(), 'E'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(),
                'C'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte(), 'E'.code.toByte(),
                '2'.code.toByte(), '.'.code.toByte(), '0'.code.toByte(),
                0x03, 0x01, 0x00, 0x00, 0x00,
            ),
        )

        val pixels = IntArray(w * h)
        for (bmp in frames) {
            // Graphic Control Extension
            buf.write(0x21)
            buf.write(0xF9)
            buf.write(0x04)
            buf.write(0x00)
            writeShortLE(buf, delayCs)
            buf.write(0x00)
            buf.write(0x00)

            // Image Descriptor
            buf.write(0x2C)
            writeShortLE(buf, 0)
            writeShortLE(buf, 0)
            writeShortLE(buf, w)
            writeShortLE(buf, h)
            buf.write(0x00) // Use global color table

            // LZW Minimum Code Size = 8
            buf.write(8)

            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            writeUncompressedLzw8(buf, pixels)
            buf.write(0x00) // Block terminator
        }

        // GIF Trailer
        buf.write(0x3B)
        out.write(buf.toByteArray())
        out.flush()
    }

    /**
     * Emits valid 9-bit LZW codes (with clear code 256 every 120 pixels and EOI 257 at the end)
     * packed into GIF sub-blocks of up to 255 bytes.
     */
    private fun writeUncompressedLzw8(out: ByteArrayOutputStream, argbPixels: IntArray) {
        val clearCode = 256
        val eoiCode = 257
        val bitPacker = LzwSubBlockPacker(out)

        var runCount = 0
        bitPacker.write9BitCode(clearCode)
        for (c in argbPixels) {
            val r3 = (Color.red(c) * 7 + 127) / 255
            val g3 = (Color.green(c) * 7 + 127) / 255
            val b2 = (Color.blue(c) * 3 + 127) / 255
            val idx = ((r3 and 0x07) shl 5) or ((g3 and 0x07) shl 2) or (b2 and 0x03)
            bitPacker.write9BitCode(idx and 0xFF)
            runCount++
            if (runCount >= 120) {
                bitPacker.write9BitCode(clearCode)
                runCount = 0
            }
        }
        bitPacker.write9BitCode(eoiCode)
        bitPacker.flush()
    }

    private class LzwSubBlockPacker(private val out: ByteArrayOutputStream) {
        private var bitBuf = 0
        private var bitCount = 0
        private val block = ByteArray(255)
        private var blockLen = 0

        fun write9BitCode(code: Int) {
            bitBuf = bitBuf or ((code and 0x1FF) shl bitCount)
            bitCount += 9
            while (bitCount >= 8) {
                appendByte((bitBuf and 0xFF).toByte())
                bitBuf = bitBuf ushr 8
                bitCount -= 8
            }
        }

        fun flush() {
            if (bitCount > 0) {
                appendByte((bitBuf and 0xFF).toByte())
                bitBuf = 0
                bitCount = 0
            }
            if (blockLen > 0) {
                out.write(blockLen)
                out.write(block, 0, blockLen)
                blockLen = 0
            }
        }

        private fun appendByte(b: Byte) {
            block[blockLen++] = b
            if (blockLen == 255) {
                out.write(255)
                out.write(block, 0, 255)
                blockLen = 0
            }
        }
    }

    private fun writeShortLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}
