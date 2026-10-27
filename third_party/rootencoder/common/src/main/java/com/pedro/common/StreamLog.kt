package com.pedro.common

/** ScreenKit privacy patch: transport command payloads and server errors are never logged. */
@Suppress("UNUSED_PARAMETER")
object StreamLog {
    fun i(tag: String, message: String): Int = 0
    fun e(tag: String, message: String, error: Throwable? = null): Int = 0
}
