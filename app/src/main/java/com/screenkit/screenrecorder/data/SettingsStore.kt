package com.screenkit.screenrecorder.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import com.screenkit.screenrecorder.CaptureContract

/**
 * What ends up in the audio track of a recording.
 *
 * [MICROPHONE] is handled by MediaRecorder; [DEVICE] and [MIXED] need AudioPlaybackCapture, which
 * means raw PCM mixed by ScreenKit and encoded with MediaCodec (see ScreenRecorderEngine).
 */
internal enum class CaptureAudioMode(
    val id: String,
    val label: String,
    val detail: String,
    val usesMicrophone: Boolean,
    val usesDeviceAudio: Boolean,
) {
    NONE("none", "Off", "Silent video", false, false),
    MICROPHONE("mic", "Microphone", "Your voice and room sound", true, false),
    DEVICE("device", "Device audio", "Sound this phone plays (Android 10+)", false, true),
    MIXED("mixed", "Mic + device", "Commentary over the phone's sound", true, true);

    /** AudioPlaybackCapture needs RECORD_AUDIO even when the mic itself is not recorded. */
    val requiresRecordPermission: Boolean get() = usesMicrophone || usesDeviceAudio

    fun next(): CaptureAudioMode = when (this) {
        NONE -> MICROPHONE
        MICROPHONE -> DEVICE
        DEVICE -> MIXED
        MIXED -> NONE
    }

    companion object {
        fun fromId(id: String?): CaptureAudioMode? = values().firstOrNull { it.id == id }
    }
}

/** Where ScreenKit writes finished files. */
internal enum class SaveLocationMode(val id: String, val label: String, val detail: String) {
    MEDIA_DEFAULT("media", "Device gallery", "Pictures/ScreenCapture and Movies/ScreenCapture"),
    MEDIA_VOLUME("volume", "SD card / volume", "Media library on a chosen storage volume"),
    CUSTOM_FOLDER("folder", "Folder", "Any folder you pick, used as-is");

    companion object {
        fun fromId(id: String?): SaveLocationMode = values().firstOrNull { it.id == id } ?: MEDIA_DEFAULT
    }
}

/** How much of the screen a projection session may capture. */
internal enum class ProjectionScope(val id: String) {
    /** Android 14+: the consent sheet only offers the whole display, so nothing freezes when you switch apps. */
    ENTIRE_SCREEN("entire"),

    /** Android 14+: the consent sheet also offers "a single app", which stops updating outside that app. */
    USER_CHOICE("choice");

    companion object {
        fun fromId(id: String?): ProjectionScope =
            values().firstOrNull { it.id == id } ?: ENTIRE_SCREEN
    }
}

/** Active automation mode for ScreenKit's Auto Clicker, Auto Scroller & Touch Macro Recorder. */
internal enum class AutoToolMode(val id: String, val label: String, val subtitle: String) {
    CLICK("click", "Auto Clicker (1 → 2 → 3…)", "Sequential multi-point or single-point tapping"),
    SCROLL("scroll", "Auto Scroller (↑ / ↓)", "Timed automatic up, down, left, or right scrolling"),
    MACRO("macro", "Touch Macro (Rec & Play)", "Record your real taps & swipes and replay them");

    companion object {
        fun fromId(id: String?): AutoToolMode =
            values().firstOrNull { it.id == id } ?: CLICK
    }
}

/** A recorded user touch action (tap or swipe) in a Touch Macro sequence. */
internal data class AutoMacroStep(
    val isSwipe: Boolean,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val durationMs: Long,
    val delayBeforeMs: Long,
)

/** Named saved automation preset/profile. */
internal data class AutoSavedProfile(
    val slot: Int,
    val name: String,
    val summary: String,
)

/** Scroll direction for Auto Scroller. */
internal enum class AutoScrollDirection(
    val id: String,
    val label: String,
    val shortLabel: String,
    val arrow: String,
    val detail: String,
) {
    DOWN("down", "Scroll Down", "Down", "⬇", "Swipes upward to scroll down feeds, pages, or Reels"),
    UP("up", "Scroll Up", "Up", "⬆", "Swipes downward to scroll back up toward the top"),
    LEFT("left", "Scroll Left", "Left", "⬅", "Swipes right-to-left across horizontal carousels"),
    RIGHT("right", "Scroll Right", "Right", "➡", "Swipes left-to-right across pages or galleries");

    fun next(): AutoScrollDirection = when (this) {
        DOWN -> UP
        UP -> LEFT
        LEFT -> RIGHT
        RIGHT -> DOWN
    }

    companion object {
        fun fromId(id: String?): AutoScrollDirection =
            values().firstOrNull { it.id == id } ?: DOWN
    }
}

/** Stored position and optional per-point overrides for numbered target pins (1, 2, 3, 4...). */
internal data class AutoClickTargetSpec(
    val index: Int,
    val x: Int,
    val y: Int,
    val customTaps: Int = 0, // 0 = use global tapsPerPoint
    val customDelayMs: Long = 0L, // 0L = use global delayMs
)

/** Single source of truth for ScreenKit's persisted preferences. */
internal object SettingsStore {
    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(CaptureContract.PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- audio

    fun audioMode(context: Context): CaptureAudioMode {
        val stored = prefs(context).getString(CaptureContract.PREF_AUDIO_MODE, null)
        CaptureAudioMode.fromId(stored)?.let { return it }
        // Migrate the original single microphone switch.
        return if (prefs(context).getBoolean(CaptureContract.PREF_MICROPHONE_ENABLED, false)) {
            CaptureAudioMode.MICROPHONE
        } else {
            CaptureAudioMode.NONE
        }
    }

    fun setAudioMode(context: Context, mode: CaptureAudioMode) {
        prefs(context).edit()
            .putString(CaptureContract.PREF_AUDIO_MODE, mode.id)
            .putBoolean(CaptureContract.PREF_MICROPHONE_ENABLED, mode.usesMicrophone)
            .apply()
    }

    fun microphoneEnabled(context: Context): Boolean = audioMode(context).usesMicrophone

    // ---------------------------------------------------------------- camera

    fun cameraFront(context: Context): Boolean =
        prefs(context).getBoolean(CaptureContract.PREF_CAMERA_FRONT, false)

    fun setCameraFront(context: Context, front: Boolean) {
        prefs(context).edit().putBoolean(CaptureContract.PREF_CAMERA_FRONT, front).apply()
    }

    fun cameraOverlayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(CaptureContract.PREF_CAMERA_OVERLAY_ENABLED, false)

    fun setCameraOverlayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(CaptureContract.PREF_CAMERA_OVERLAY_ENABLED, enabled).apply()
    }

    // ---------------------------------------------------------------- overlay

    fun overlayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(CaptureContract.PREF_OVERLAY_ENABLED, false)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(CaptureContract.PREF_OVERLAY_ENABLED, enabled).apply()
    }

    fun overlayIntroSeen(context: Context): Boolean =
        prefs(context).getBoolean(CaptureContract.PREF_OVERLAY_INTRO_SEEN, false)

    fun markOverlayIntroSeen(context: Context) {
        prefs(context).edit().putBoolean(CaptureContract.PREF_OVERLAY_INTRO_SEEN, true).apply()
    }

    // ---------------------------------------------------------------- touches & bypass

    fun showTouches(context: Context): Boolean =
        prefs(context).getBoolean(CaptureContract.PREF_SHOW_TOUCHES, false)

    fun setShowTouches(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(CaptureContract.PREF_SHOW_TOUCHES, enabled).apply()
    }

    fun hwGlBypass(context: Context): Boolean =
        prefs(context).getBoolean(CaptureContract.PREF_HW_GL_BYPASS, true)

    fun setHwGlBypass(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(CaptureContract.PREF_HW_GL_BYPASS, enabled).apply()
    }

    fun lastWebUrl(context: Context): String =
        prefs(context).getString(CaptureContract.PREF_LAST_WEB_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: "https://www.google.com"

    fun setLastWebUrl(context: Context, url: String) {
        prefs(context).edit().putString(CaptureContract.PREF_LAST_WEB_URL, url).apply()
    }

    // ---------------------------------------------------------------- auto clicker & auto scroller

    fun autoToolMode(context: Context): AutoToolMode =
        AutoToolMode.fromId(prefs(context).getString(CaptureContract.PREF_AUTO_TOOL_MODE, null))

    fun setAutoToolMode(context: Context, mode: AutoToolMode) {
        prefs(context).edit().putString(CaptureContract.PREF_AUTO_TOOL_MODE, mode.id).apply()
    }

    fun autoClickPointCount(context: Context): Int =
        prefs(context).getInt(CaptureContract.PREF_AUTO_CLICK_POINT_COUNT, 3).coerceIn(1, 20)

    fun setAutoClickPointCount(context: Context, count: Int) {
        prefs(context).edit().putInt(CaptureContract.PREF_AUTO_CLICK_POINT_COUNT, count.coerceIn(1, 20)).apply()
    }

    /** Delay in milliseconds between each click (e.g. between Click 1 -> Click 2 -> Click 3). */
    fun autoClickDelayMs(context: Context): Long =
        prefs(context).getLong(CaptureContract.PREF_AUTO_CLICK_DELAY_MS, 500L).coerceIn(40L, 60_000L)

    fun setAutoClickDelayMs(context: Context, delayMs: Long) {
        prefs(context).edit().putLong(CaptureContract.PREF_AUTO_CLICK_DELAY_MS, delayMs.coerceIn(40L, 60_000L)).apply()
    }

    /** Clicking speed (how long the finger holds down on each tap in ms: 25ms turbo .. 1000ms long press). */
    fun autoClickSpeedMs(context: Context): Long =
        prefs(context).getLong(CaptureContract.PREF_AUTO_CLICK_SPEED_MS, 55L).coerceIn(20L, 2_000L)

    fun setAutoClickSpeedMs(context: Context, speedMs: Long) {
        prefs(context).edit().putLong(CaptureContract.PREF_AUTO_CLICK_SPEED_MS, speedMs.coerceIn(20L, 2_000L)).apply()
    }

    /** How many times each numbered position (1, 2, 3...) is tapped before advancing to the next position. */
    fun autoClickTapsPerPoint(context: Context): Int =
        prefs(context).getInt(CaptureContract.PREF_AUTO_CLICK_TAPS_PER_POINT, 1).coerceIn(1, 100)

    fun setAutoClickTapsPerPoint(context: Context, taps: Int) {
        prefs(context).edit().putInt(CaptureContract.PREF_AUTO_CLICK_TAPS_PER_POINT, taps.coerceIn(1, 100)).apply()
    }

    /** Total number of full sequence cycles (1 -> 2 -> 3...) to run. 0 means Infinite (until stopped). */
    fun autoClickTotalCycles(context: Context): Int =
        prefs(context).getInt(CaptureContract.PREF_AUTO_CLICK_TOTAL_CYCLES, 10).coerceIn(0, 10_000)

    fun setAutoClickTotalCycles(context: Context, cycles: Int) {
        prefs(context).edit().putInt(CaptureContract.PREF_AUTO_CLICK_TOTAL_CYCLES, cycles.coerceIn(0, 10_000)).apply()
    }

    /** Maximum duration in seconds for Auto Clicker. 0 means no time limit. */
    fun autoClickDurationSec(context: Context): Int =
        prefs(context).getInt(CaptureContract.PREF_AUTO_CLICK_DURATION_SEC, 0).coerceIn(0, 7200)

    fun setAutoClickDurationSec(context: Context, seconds: Int) {
        prefs(context).edit().putInt(CaptureContract.PREF_AUTO_CLICK_DURATION_SEC, seconds.coerceIn(0, 7200)).apply()
    }

    fun loadAutoClickTargets(context: Context, screenW: Int, screenH: Int): List<AutoClickTargetSpec> {
        val count = autoClickPointCount(context)
        val raw = prefs(context).getString(CaptureContract.PREF_AUTO_CLICK_POINTS_DATA, null).orEmpty()
        val parsed = mutableMapOf<Int, AutoClickTargetSpec>()
        if (raw.isNotBlank()) {
            raw.split(";").forEach { entry ->
                val parts = entry.split(",")
                if (parts.size >= 3) {
                    val idx = parts[0].toIntOrNull() ?: return@forEach
                    val x = parts[1].toIntOrNull() ?: return@forEach
                    val y = parts[2].toIntOrNull() ?: return@forEach
                    val taps = parts.getOrNull(3)?.toIntOrNull() ?: 0
                    val delay = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                    parsed[idx] = AutoClickTargetSpec(
                        index = idx,
                        x = x.coerceIn(40, (screenW - 40).coerceAtLeast(100)),
                        y = y.coerceIn(80, (screenH - 80).coerceAtLeast(160)),
                        customTaps = taps,
                        customDelayMs = delay,
                    )
                }
            }
        }
        val centerX = (screenW / 2).coerceAtLeast(180)
        val startY = (screenH * 0.30f).toInt().coerceAtLeast(200)
        val stepY = ((screenH * 0.42f) / count.coerceAtLeast(2)).toInt().coerceIn(70, 150)
        return (1..count).map { idx ->
            parsed[idx] ?: AutoClickTargetSpec(
                index = idx,
                x = (centerX + ((idx - 1) % 2) * 90 - 45).coerceIn(60, (screenW - 60).coerceAtLeast(120)),
                y = (startY + (idx - 1) * stepY).coerceIn(100, (screenH - 120).coerceAtLeast(180)),
            )
        }
    }

    fun saveAutoClickTargets(context: Context, targets: List<AutoClickTargetSpec>) {
        val serialized = targets.joinToString(";") { t ->
            "${t.index},${t.x},${t.y},${t.customTaps},${t.customDelayMs}"
        }
        prefs(context).edit()
            .putInt(CaptureContract.PREF_AUTO_CLICK_POINT_COUNT, targets.size.coerceIn(1, 20))
            .putString(CaptureContract.PREF_AUTO_CLICK_POINTS_DATA, serialized)
            .apply()
    }

    fun autoScrollDirection(context: Context): AutoScrollDirection =
        AutoScrollDirection.fromId(prefs(context).getString(CaptureContract.PREF_AUTO_SCROLL_DIRECTION, null))

    fun setAutoScrollDirection(context: Context, direction: AutoScrollDirection) {
        prefs(context).edit().putString(CaptureContract.PREF_AUTO_SCROLL_DIRECTION, direction.id).apply()
    }

    /** How fast or slow each scroll swipe gesture moves across the screen (in ms: 120ms fast .. 1600ms slow). */
    fun autoScrollSpeedMs(context: Context): Long =
        prefs(context).getLong(CaptureContract.PREF_AUTO_SCROLL_SPEED_MS, 450L).coerceIn(100L, 2500L)

    fun setAutoScrollSpeedMs(context: Context, speedMs: Long) {
        prefs(context).edit().putLong(CaptureContract.PREF_AUTO_SCROLL_SPEED_MS, speedMs.coerceIn(100L, 2500L)).apply()
    }

    /** Delay / pause between consecutive scroll gestures in ms. */
    fun autoScrollIntervalMs(context: Context): Long =
        prefs(context).getLong(CaptureContract.PREF_AUTO_SCROLL_INTERVAL_MS, 1500L).coerceIn(200L, 3_600_000L)

    fun setAutoScrollIntervalMs(context: Context, intervalMs: Long) {
        prefs(context).edit().putLong(CaptureContract.PREF_AUTO_SCROLL_INTERVAL_MS, intervalMs.coerceIn(200L, 3_600_000L)).apply()
    }

    fun autoScrollSmartEnabled(context: Context): Boolean = prefs(context).getBoolean("auto_scroll_smart_v1", false)
    fun setAutoScrollSmartEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("auto_scroll_smart_v1", enabled).apply()
    }

    /** How long the auto-scrolling will run in seconds (0 = Infinite until stopped). */
    fun autoScrollDurationSec(context: Context): Int =
        prefs(context).getInt(CaptureContract.PREF_AUTO_SCROLL_DURATION_SEC, 120).coerceIn(0, 7200)

    fun setAutoScrollDurationSec(context: Context, durationSec: Int) {
        prefs(context).edit().putInt(CaptureContract.PREF_AUTO_SCROLL_DURATION_SEC, durationSec.coerceIn(0, 7200)).apply()
    }

    /** Maximum number of scrolls to perform (0 = Unlimited / governed by durationSec). */
    fun autoScrollMaxCount(context: Context): Int =
        prefs(context).getInt(CaptureContract.PREF_AUTO_SCROLL_MAX_COUNT, 0).coerceIn(0, 10_000)

    fun setAutoScrollMaxCount(context: Context, count: Int) {
        prefs(context).edit().putInt(CaptureContract.PREF_AUTO_SCROLL_MAX_COUNT, count.coerceIn(0, 10_000)).apply()
    }

    /** Scroll distance as percentage of screen height/width (25% short .. 80% long). */
    fun autoScrollDistancePct(context: Context): Int =
        prefs(context).getInt(CaptureContract.PREF_AUTO_SCROLL_DISTANCE_PCT, 55).coerceIn(20, 85)

    fun setAutoScrollDistancePct(context: Context, pct: Int) {
        prefs(context).edit().putInt(CaptureContract.PREF_AUTO_SCROLL_DISTANCE_PCT, pct.coerceIn(20, 85)).apply()
    }

    // ---------------------------------------------------------------- jitter, color trigger, macro & profiles

    fun autoRandomJitter(context: Context): Boolean =
        prefs(context).getBoolean(CaptureContract.PREF_AUTO_RANDOM_JITTER, true)

    fun setAutoRandomJitter(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(CaptureContract.PREF_AUTO_RANDOM_JITTER, enabled).apply()
    }

    fun autoColorTriggerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(CaptureContract.PREF_AUTO_COLOR_TRIGGER_ENABLED, false)

    fun setAutoColorTriggerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(CaptureContract.PREF_AUTO_COLOR_TRIGGER_ENABLED, enabled).apply()
    }

    fun autoColorTriggerHex(context: Context): String =
        prefs(context).getString(CaptureContract.PREF_AUTO_COLOR_TRIGGER_HEX, "#4CAF50")
            ?.takeIf { it.startsWith("#") && it.length >= 7 }
            ?: "#4CAF50"

    fun setAutoColorTriggerHex(context: Context, hex: String) {
        val clean = if (hex.startsWith("#")) hex.uppercase() else "#${hex.uppercase()}"
        prefs(context).edit().putString(CaptureContract.PREF_AUTO_COLOR_TRIGGER_HEX, clean).apply()
    }

    fun loadMacroSteps(context: Context): List<AutoMacroStep> {
        val raw = prefs(context).getString(CaptureContract.PREF_AUTO_MACRO_STEPS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { item ->
            val p = item.split(",")
            if (p.size < 7) return@mapNotNull null
            AutoMacroStep(
                isSwipe = p[0] == "1",
                x1 = p[1].toIntOrNull() ?: return@mapNotNull null,
                y1 = p[2].toIntOrNull() ?: return@mapNotNull null,
                x2 = p[3].toIntOrNull() ?: return@mapNotNull null,
                y2 = p[4].toIntOrNull() ?: return@mapNotNull null,
                durationMs = p[5].toLongOrNull() ?: 60L,
                delayBeforeMs = p[6].toLongOrNull() ?: 350L,
            )
        }
    }

    fun saveMacroSteps(context: Context, steps: List<AutoMacroStep>) {
        val raw = steps.take(80).joinToString(";") { s ->
            "${if (s.isSwipe) 1 else 0},${s.x1},${s.y1},${s.x2},${s.y2},${s.durationMs},${s.delayBeforeMs}"
        }
        prefs(context).edit().putString(CaptureContract.PREF_AUTO_MACRO_STEPS, raw).apply()
    }

    fun saveCurrentToProfileSlot(context: Context, slot: Int, customName: String? = null) {
        val p = prefs(context)
        val mode = autoToolMode(context)
        val defaultName = when (slot) {
            1 -> "Profile 1 (${mode.id.uppercase()})"
            2 -> "Profile 2 (${mode.id.uppercase()})"
            else -> "Profile 3 (${mode.id.uppercase()})"
        }
        val name = customName?.takeIf { it.isNotBlank() } ?: defaultName
        val pointsRaw = p.getString(CaptureContract.PREF_AUTO_CLICK_POINTS_DATA, "").orEmpty()
        val macroRaw = p.getString(CaptureContract.PREF_AUTO_MACRO_STEPS, "").orEmpty()
        val bundle = listOf(
            name.replace("|", " "),
            mode.id,
            autoClickPointCount(context).toString(),
            autoClickDelayMs(context).toString(),
            autoClickSpeedMs(context).toString(),
            autoClickTapsPerPoint(context).toString(),
            autoClickTotalCycles(context).toString(),
            autoScrollDirection(context).id,
            autoScrollSpeedMs(context).toString(),
            autoScrollIntervalMs(context).toString(),
            autoScrollDurationSec(context).toString(),
            autoScrollDistancePct(context).toString(),
            pointsRaw,
            macroRaw,
        ).joinToString("|")
        p.edit().putString("${CaptureContract.PREF_AUTO_PROFILE_PREFIX}$slot", bundle).apply()
    }

    fun loadProfileSlot(context: Context, slot: Int): String? {
        val p = prefs(context)
        val raw = p.getString("${CaptureContract.PREF_AUTO_PROFILE_PREFIX}$slot", null)
        if (raw.isNullOrBlank()) {
            // Apply built-in starter preset for this slot!
            return when (slot) {
                1 -> {
                    // Preset 1: Reels / TikTok Auto Scroll
                    setAutoToolMode(context, AutoToolMode.SCROLL)
                    setAutoScrollDirection(context, AutoScrollDirection.DOWN)
                    setAutoScrollSpeedMs(context, 300L)
                    setAutoScrollIntervalMs(context, 4000L)
                    setAutoScrollDurationSec(context, 300)
                    setAutoScrollDistancePct(context, 75)
                    "Reels / TikTok Scroll (Down every 4s)"
                }
                2 -> {
                    // Preset 2: Fast Game Farming 1 -> 4
                    setAutoToolMode(context, AutoToolMode.CLICK)
                    setAutoClickPointCount(context, 4)
                    setAutoClickDelayMs(context, 250L)
                    setAutoClickSpeedMs(context, 55L)
                    setAutoClickTapsPerPoint(context, 1)
                    setAutoClickTotalCycles(context, 50)
                    "Game Farming (1→4 @ 250ms)"
                }
                else -> {
                    // Preset 3: Slow Reading Scroll
                    setAutoToolMode(context, AutoToolMode.SCROLL)
                    setAutoScrollDirection(context, AutoScrollDirection.DOWN)
                    setAutoScrollSpeedMs(context, 1400L)
                    setAutoScrollIntervalMs(context, 2500L)
                    setAutoScrollDurationSec(context, 600)
                    setAutoScrollDistancePct(context, 30)
                    "Reading Mode (Slow smooth scroll)"
                }
            }
        }
        val parts = raw.split("|")
        if (parts.size < 12) return null
        val name = parts[0]
        setAutoToolMode(context, AutoToolMode.fromId(parts[1]))
        setAutoClickPointCount(context, parts[2].toIntOrNull() ?: 3)
        setAutoClickDelayMs(context, parts[3].toLongOrNull() ?: 500L)
        setAutoClickSpeedMs(context, parts[4].toLongOrNull() ?: 55L)
        setAutoClickTapsPerPoint(context, parts[5].toIntOrNull() ?: 1)
        setAutoClickTotalCycles(context, parts[6].toIntOrNull() ?: 10)
        setAutoScrollDirection(context, AutoScrollDirection.fromId(parts[7]))
        setAutoScrollSpeedMs(context, parts[8].toLongOrNull() ?: 450L)
        setAutoScrollIntervalMs(context, parts[9].toLongOrNull() ?: 1500L)
        setAutoScrollDurationSec(context, parts[10].toIntOrNull() ?: 120)
        setAutoScrollDistancePct(context, parts[11].toIntOrNull() ?: 55)
        val ed = p.edit()
        if (parts.size >= 13 && parts[12].isNotBlank()) {
            ed.putString(CaptureContract.PREF_AUTO_CLICK_POINTS_DATA, parts[12])
        }
        if (parts.size >= 14 && parts[13].isNotBlank()) {
            ed.putString(CaptureContract.PREF_AUTO_MACRO_STEPS, parts[13])
        }
        ed.apply()
        return name
    }

    fun listSavedProfiles(context: Context): List<AutoSavedProfile> {
        val p = prefs(context)
        return (1..3).map { slot ->
            val raw = p.getString("${CaptureContract.PREF_AUTO_PROFILE_PREFIX}$slot", null)
            if (raw.isNullOrBlank()) {
                when (slot) {
                    1 -> AutoSavedProfile(1, "Preset 1: Reels/TikTok", "Scroll Down · 300ms · 4s gap")
                    2 -> AutoSavedProfile(2, "Preset 2: Game 1→4", "4 points · 250ms delay · 50×")
                    else -> AutoSavedProfile(3, "Preset 3: Reader", "Slow 1400ms scroll · 30% dist")
                }
            } else {
                val parts = raw.split("|")
                val name = parts.getOrNull(0) ?: "Profile $slot"
                val modeId = parts.getOrNull(1) ?: "click"
                val summary = if (modeId == "scroll") {
                    "Saved Scroll (${parts.getOrNull(7) ?: "down"}, ${parts.getOrNull(9) ?: "1500"}ms)"
                } else {
                    "Saved Click (1→${parts.getOrNull(2) ?: "3"}, ${parts.getOrNull(3) ?: "500"}ms)"
                }
                AutoSavedProfile(slot, name, summary)
            }
        }
    }

    // ---------------------------------------------------------------- emergency stops & creator tools

    fun shakeToStopEnabled(context: Context): Boolean =
        prefs(context).getBoolean(CaptureContract.PREF_SHAKE_TO_STOP, true)

    fun setShakeToStopEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(CaptureContract.PREF_SHAKE_TO_STOP, enabled).apply()
    }

    fun volumeKeyStopEnabled(context: Context): Boolean =
        prefs(context).getBoolean(CaptureContract.PREF_VOLUME_KEY_STOP, true)

    fun setVolumeKeyStopEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(CaptureContract.PREF_VOLUME_KEY_STOP, enabled).apply()
    }

    fun teleprompterScript(context: Context): String =
        prefs(context).getString(CaptureContract.PREF_TELEPROMPTER_SCRIPT, null)
            ?.takeIf { it.isNotBlank() }
            ?: "Welcome back! Today I am recording my screen with ScreenKit.\n\n1. Show the floating controls and multi-point Auto Clicker (1 → 2 → 3 → 4).\n2. Demonstrate automatic directional scrolling and live brush annotations.\n3. Don't forget to like, share, and subscribe!"

    fun setTeleprompterScript(context: Context, script: String) {
        prefs(context).edit().putString(CaptureContract.PREF_TELEPROMPTER_SCRIPT, script).apply()
    }

    fun teleprompterSpeed(context: Context): Int =
        prefs(context).getInt(CaptureContract.PREF_TELEPROMPTER_SPEED, 2).coerceIn(1, 5)

    fun setTeleprompterSpeed(context: Context, speed: Int) {
        prefs(context).edit().putInt(CaptureContract.PREF_TELEPROMPTER_SPEED, speed.coerceIn(1, 5)).apply()
    }

    fun watermarkEnabled(context: Context): Boolean =
        prefs(context).getBoolean(CaptureContract.PREF_WATERMARK_ENABLED, false)

    fun setWatermarkEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(CaptureContract.PREF_WATERMARK_ENABLED, enabled).apply()
    }

    fun watermarkText(context: Context): String =
        prefs(context).getString(CaptureContract.PREF_WATERMARK_TEXT, null)
            ?.takeIf { it.isNotBlank() }
            ?: "Recorded with ScreenKit"

    fun setWatermarkText(context: Context, text: String) {
        prefs(context).edit().putString(CaptureContract.PREF_WATERMARK_TEXT, text).apply()
    }

    // ---------------------------------------------------------------- telemetry counters

    fun statAppOpens(context: Context): Int =
        prefs(context).getInt(CaptureContract.PREF_STAT_APP_OPENS, 1)

    fun incrementAppOpens(context: Context) {
        val next = statAppOpens(context) + 1
        prefs(context).edit().putInt(CaptureContract.PREF_STAT_APP_OPENS, next).apply()
    }

    fun statCapturesSaved(context: Context): Int =
        prefs(context).getInt(CaptureContract.PREF_STAT_CAPTURES_SAVED, 0)

    fun incrementCapturesSaved(context: Context) {
        val next = statCapturesSaved(context) + 1
        prefs(context).edit().putInt(CaptureContract.PREF_STAT_CAPTURES_SAVED, next).apply()
    }

    fun statAutoGestures(context: Context): Int =
        prefs(context).getInt(CaptureContract.PREF_STAT_AUTO_GESTURES, 0)

    fun incrementAutoGestures(context: Context, delta: Int = 1) {
        val next = statAutoGestures(context) + delta
        prefs(context).edit().putInt(CaptureContract.PREF_STAT_AUTO_GESTURES, next).apply()
    }

    // ---------------------------------------------------------------- projection scope

    fun projectionScope(context: Context): ProjectionScope =
        ProjectionScope.fromId(prefs(context).getString(CaptureContract.PREF_PROJECTION_SCOPE, null))

    fun setProjectionScope(context: Context, scope: ProjectionScope) {
        prefs(context).edit().putString(CaptureContract.PREF_PROJECTION_SCOPE, scope.id).apply()
    }

    // ---------------------------------------------------------------- save location

    fun saveMode(context: Context): SaveLocationMode =
        SaveLocationMode.fromId(prefs(context).getString(CaptureContract.PREF_SAVE_MODE, null))

    fun setSaveMode(context: Context, mode: SaveLocationMode) {
        prefs(context).edit().putString(CaptureContract.PREF_SAVE_MODE, mode.id).apply()
    }

    fun mediaVolume(context: Context): String? =
        prefs(context).getString(CaptureContract.PREF_SAVE_VOLUME, null)?.takeIf { it.isNotBlank() }

    fun setMediaVolume(context: Context, volume: String?) {
        prefs(context).edit().putString(CaptureContract.PREF_SAVE_VOLUME, volume).apply()
    }

    fun treeUri(context: Context): Uri? =
        prefs(context).getString(CaptureContract.PREF_SAVE_TREE_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun folderLabel(context: Context): String? =
        prefs(context).getString(CaptureContract.PREF_SAVE_FOLDER_LABEL, null)?.takeIf { it.isNotBlank() }

    /** Keeps the SAF grant and switches the save location to that folder. */
    fun commitTreeLocation(context: Context, uri: Uri, writeGranted: Boolean): Boolean {
        val flags = IntentFlags.readGrant(writeGranted)
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        }.getOrDefault(false)
        val label = describeTree(context, uri)
        prefs(context).edit()
            .putString(CaptureContract.PREF_SAVE_TREE_URI, uri.toString())
            .putString(CaptureContract.PREF_SAVE_FOLDER_LABEL, label)
            .putString(CaptureContract.PREF_SAVE_MODE, SaveLocationMode.CUSTOM_FOLDER.id)
            .apply()
        return persisted
    }

    fun describeTree(context: Context, uri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return uri.toString()
        val volume = documentId.substringBefore(':', documentId)
        val path = documentId.substringAfter(':', "")
        val volumeLabel = if (volume == "primary" || volume == "home") {
            "Device storage"
        } else {
            StorageVolumes.list(context).firstOrNull { it.name == volume }?.label ?: volume
        }
        return if (path.isBlank()) volumeLabel else "$volumeLabel/$path"
    }

    /** Human-readable description of where the next capture will be written. */
    fun describeSaveLocation(context: Context): String = when (saveMode(context)) {
        SaveLocationMode.MEDIA_DEFAULT -> "Pictures/ScreenCapture and Movies/ScreenCapture"
        SaveLocationMode.MEDIA_VOLUME -> {
            val volume = mediaVolume(context)
            val label = StorageVolumes.list(context).firstOrNull { it.name == volume }?.label
            "Media library · ${label ?: volume ?: "secondary storage"}"
        }
        SaveLocationMode.CUSTOM_FOLDER -> folderLabel(context) ?: "your chosen folder"
    }
}

/** Small indirection so [SettingsStore] does not need to import Intent flags directly. */
private object IntentFlags {
    fun readGrant(writeGranted: Boolean): Int =
        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (writeGranted) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
}

/** Removable and internal volumes that MediaStore can index (Android 10+). */
internal object StorageVolumes {
    data class VolumeChoice(val name: String, val label: String, val removable: Boolean)

    fun list(context: Context): List<VolumeChoice> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return emptyList()
        val result = mutableListOf<VolumeChoice>()
        manager.storageVolumes.forEach { volume ->
            if (volume.state != Environment.MEDIA_MOUNTED) return@forEach
            val name = volume.mediaStoreVolumeName ?: return@forEach
            if (name.isBlank()) return@forEach
            val description = runCatching { volume.getDescription(context) }.getOrNull() ?: name
            result += VolumeChoice(name, description, volume.isRemovable)
        }
        return result
    }
}
