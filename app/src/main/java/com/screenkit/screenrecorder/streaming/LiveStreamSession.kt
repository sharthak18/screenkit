package com.screenkit.screenrecorder.streaming

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class LiveStreamStatus(
    val active: Boolean = false,
    val connected: Boolean = false,
    val message: String = "Ready to stream",
    val startedAt: Long = 0L,
    val bitrate: Long = 0L,
    val muted: Boolean = false,
)

/** Process-local only. Never persist destinations/keys in preferences, saved state, or backups. */
internal object LiveStreamSession {
    @Volatile var pending: LiveStreamConfig? = null
    private val mutableStatus = MutableStateFlow(LiveStreamStatus())
    val status = mutableStatus.asStateFlow()
    fun update(value: LiveStreamStatus) { mutableStatus.value = value }
}
