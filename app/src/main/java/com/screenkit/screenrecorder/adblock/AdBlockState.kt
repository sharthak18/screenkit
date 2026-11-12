package com.screenkit.screenrecorder.adblock

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class AdBlockStatus(
    val enabled: Boolean = false,
    val active: Boolean = false,
    val message: String = "Off — network filtering is optional",
    val queries: Long = 0,
    val blocked: Long = 0,
)
internal object AdBlockState {
    private val mutable = MutableStateFlow(AdBlockStatus())
    val status = mutable.asStateFlow()
    fun enabled(context: Context) = context.getSharedPreferences("ad_filter", Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun refresh(context: Context) {
        val enabled = enabled(context)
        mutable.update { it.copy(enabled = enabled, message = if (enabled && !it.enabled) "Ready — starts with floating controls" else it.message) }
    }
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("ad_filter", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        mutable.update { it.copy(enabled = enabled, active = if (enabled) it.active else false,
            message = if (enabled) "Ready — starts with floating controls" else "Off — network filtering is optional") }
    }
    fun set(message: String, active: Boolean = false, resetCounts: Boolean = false) {
        mutable.update { it.copy(message = message, active = active,
            queries = if (resetCounts) 0 else it.queries, blocked = if (resetCounts) 0 else it.blocked) }
    }
    fun counted(blocked: Boolean) { mutable.update { it.copy(queries = it.queries + 1, blocked = it.blocked + if (blocked) 1 else 0) } }
}
