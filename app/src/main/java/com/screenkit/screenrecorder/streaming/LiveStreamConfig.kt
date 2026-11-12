package com.screenkit.screenrecorder.streaming

import java.net.URI

/** Deliberately not a data class: toString must never include the stream key. */
internal class LiveStreamConfig(
    val server: String,
    private val streamKey: String,
    val shortEdge: Int = 720,
    val fps: Int = 30,
    val bitrateKbps: Int = 2500,
) {
    fun endpoint(): String = validateEndpoint(server, streamKey)

    fun validate() {
        endpoint()
        require(shortEdge in listOf(480, 720, 1080)) { "Choose 480p, 720p, or 1080p." }
        require(fps in listOf(24, 30, 60)) { "Choose 24, 30, or 60 fps." }
        require(bitrateKbps in 500..12000) { "Bitrate must be 500–12000 kbps." }
    }

    fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        // Preserve screen aspect ratio, avoid upscaling, keep encoder dimensions even.
        val scale = minOf(1.0, shortEdge.toDouble() / minOf(width, height), 1920.0 / maxOf(width, height))
        return ((width * scale).toInt() and -2).coerceAtLeast(2) to
            ((height * scale).toInt() and -2).coerceAtLeast(2)
    }

    companion object {
        fun validateEndpoint(server: String, key: String): String {
            val base = server.trim().trimEnd('/')
            val secret = key.trim()
            require(base.length <= 4096 && secret.length <= 4096) { "Server URL or stream key is too long." }
            require(base.isNotEmpty() && secret.isNotEmpty()) { "Enter both the server URL and stream key." }
            require((base + secret).none { it.isWhitespace() || it.isISOControl() }) {
                "Server URL and stream key must not contain spaces or control characters."
            }
            val uri = runCatching { URI(base) }.getOrNull()
            require(uri != null && uri.scheme in listOf("rtmp", "rtmps") && !uri.host.isNullOrBlank()) {
                "Use a valid rtmp:// or rtmps:// server URL."
            }
            require(uri!!.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Paste the server URL separately, without credentials, query, or fragment."
            }
            require(uri.port == -1 || uri.port in 1..65535) { "Invalid server port." }
            require(!uri.path.isNullOrBlank() && uri.path != "/") { "The server URL needs an application path, such as /live." }
            require(!secret.startsWith('/') && !secret.contains("://") && !secret.contains('#')) {
                "Paste only the stream key, not a full URL."
            }
            val endpoint = "$base/$secret"
            require(runCatching { URI(endpoint) }.isSuccess) { "The stream key contains invalid URL characters." }
            return endpoint
        }
    }
}
