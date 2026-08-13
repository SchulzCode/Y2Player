package com.schulzcode.y2player.backup

import com.schulzcode.y2player.core.model.AudioQualityMode
import com.schulzcode.y2player.core.model.AlbumSortOrder
import com.schulzcode.y2player.core.model.TrackSortOrder
import com.schulzcode.y2player.core.model.YearSortOrder
import com.schulzcode.y2player.core.state.PlayerPreferencesState
import com.schulzcode.y2player.input.HapticLevel
import com.schulzcode.y2player.playback.AudioBalance
import com.schulzcode.y2player.playback.CrossfadeMode
import com.schulzcode.y2player.playback.ReplayGainMode
import com.schulzcode.y2player.playback.VolumeCurve
import com.schulzcode.y2player.playback.VolumeMode
import com.schulzcode.y2player.settings.AppPreferences

object PreferenceBackup {
    fun encode(value: PlayerPreferencesState): Map<String, String> = linkedMapOf(
        "ui_sound_effects" to value.uiSoundEffectsEnabled.toString(),
        "verbose_diagnostics" to value.verboseDiagnostics.toString(),
        "volume_mode" to value.volumeMode.storageId,
        "volume_level" to value.volumeLevel.toString(),
        "replay_gain_mode" to value.replayGainMode.storageId,
        "haptic_level" to value.hapticLevel.storageId,
        "wrap_lists" to value.wrapLists.toString(),
        "keep_screen_on" to value.keepScreenOnWhilePlaying.toString(),
        "extra_track_info" to value.extraTrackInfo.toString(),
        "light_theme" to value.lightTheme.toString(),
        "screen_off_keys" to value.localKeysWhileScreenOff.toString(),
        "pause_on_disconnect" to value.pauseOnDisconnect.toString(),
        "resume_position" to value.resumePosition.toString(),
        "sort_order" to value.sortOrder.storageId,
        "album_sort_order" to value.albumSortOrder.storageId,
        "year_sort_order" to value.yearSortOrder.storageId,
        "gapless" to value.gaplessEnabled.toString(),
        "crossfade_ms" to value.crossfadeMs.toString(),
        "crossfade_mode" to value.crossfadeMode.storageId,
        "pause_resume_fade_ms" to value.pauseResumeFadeMs.toString(),
        "seek_step_ms" to value.seekStepMs.toString(),
        "long_seek_step_ms" to value.longSeekStepMs.toString(),
        "previous_restart_threshold_ms" to value.previousRestartThresholdMs.toString(),
        "duck_on_focus_loss" to value.duckOnFocusLoss.toString(),
        "audio_quality_mode" to value.audioQualityMode.storageId,
        "effects_enabled" to value.audioEffectsEnabled.toString(),
        "eq_preset" to value.equalizerPreset.toString(),
        "eq_bands" to value.equalizerBandLevelsMb.joinToString(","),
        "bass_strength" to value.bassStrength.toString(),
        "loudness_gain_mb" to value.loudnessGainMb.toString(),
        "balance" to value.balance.toString()
    )

    fun decode(values: Map<String, String>): PlayerPreferencesState {
        val missing = REQUIRED_KEYS - values.keys
        require(missing.isEmpty()) { "Backup settings are incomplete: ${missing.sorted().joinToString()}" }

        fun boolean(key: String): Boolean = when (val raw = values.getValue(key)) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("Invalid $key setting")
        }
        fun integer(key: String): Int = values.getValue(key).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid $key setting")
        fun <T> enum(key: String, candidates: Array<T>, storageId: (T) -> String): T {
            val raw = values.getValue(key)
            return candidates.firstOrNull { storageId(it) == raw }
                ?: throw IllegalArgumentException("Invalid $key setting")
        }
        fun <T> optionalEnum(key: String, candidates: Array<T>, fallback: T, storageId: (T) -> String): T {
            val raw = values[key] ?: return fallback
            return candidates.firstOrNull { storageId(it) == raw }
                ?: throw IllegalArgumentException("Invalid $key setting")
        }
        fun member(key: String, allowed: List<Int>): Int = integer(key).also {
            require(it in allowed) { "Invalid $key setting" }
        }

        val bands = values.getValue("eq_bands").let { raw ->
            if (raw.isEmpty()) emptyList() else raw.split(',').map { token ->
                token.toIntOrNull()?.takeIf { it in -3_000..3_000 }
                    ?: throw IllegalArgumentException("Invalid equalizer band setting")
            }.also { require(it.size <= 32) { "Too many equalizer bands" } }
        }
        return PlayerPreferencesState(
            uiSoundEffectsEnabled = boolean("ui_sound_effects"),
            verboseDiagnostics = boolean("verbose_diagnostics"),
            volumeMode = enum("volume_mode", VolumeMode.values(), VolumeMode::storageId),
            volumeLevel = integer("volume_level").also { require(it in 0..VolumeCurve.STEPS) { "Invalid volume setting" } },
            replayGainMode = enum("replay_gain_mode", ReplayGainMode.values(), ReplayGainMode::storageId),
            hapticLevel = enum("haptic_level", HapticLevel.values(), HapticLevel::storageId),
            wrapLists = boolean("wrap_lists"),
            keepScreenOnWhilePlaying = boolean("keep_screen_on"),
            extraTrackInfo = boolean("extra_track_info"),
            lightTheme = boolean("light_theme"),
            localKeysWhileScreenOff = boolean("screen_off_keys"),
            pauseOnDisconnect = boolean("pause_on_disconnect"),
            resumePosition = boolean("resume_position"),
            sortOrder = enum("sort_order", TrackSortOrder.values(), TrackSortOrder::storageId),
            albumSortOrder = optionalEnum(
                "album_sort_order", AlbumSortOrder.values(), AlbumSortOrder.TITLE, AlbumSortOrder::storageId
            ),
            yearSortOrder = optionalEnum(
                "year_sort_order", YearSortOrder.values(), YearSortOrder.NEWEST_FIRST, YearSortOrder::storageId
            ),
            gaplessEnabled = boolean("gapless"),
            crossfadeMs = member("crossfade_ms", AppPreferences.CROSSFADE_LEVELS),
            crossfadeMode = enum("crossfade_mode", CrossfadeMode.values(), CrossfadeMode::storageId),
            pauseResumeFadeMs = member("pause_resume_fade_ms", AppPreferences.FADE_LEVELS),
            seekStepMs = member("seek_step_ms", AppPreferences.SEEK_LEVELS),
            longSeekStepMs = member("long_seek_step_ms", AppPreferences.LONG_SEEK_LEVELS),
            previousRestartThresholdMs = member("previous_restart_threshold_ms", AppPreferences.PREVIOUS_THRESHOLD_LEVELS),
            duckOnFocusLoss = boolean("duck_on_focus_loss"),
            audioQualityMode = enum("audio_quality_mode", AudioQualityMode.values(), AudioQualityMode::storageId),
            audioEffectsEnabled = boolean("effects_enabled"),
            equalizerPreset = integer("eq_preset").also { require(it in -1..100) { "Invalid equalizer preset" } },
            equalizerBandLevelsMb = bands,
            bassStrength = member("bass_strength", AppPreferences.BASS_LEVELS),
            loudnessGainMb = member("loudness_gain_mb", AppPreferences.LOUDNESS_LEVELS),
            balance = integer("balance").also { require(it in -AudioBalance.RANGE..AudioBalance.RANGE) { "Invalid balance" } }
        )
    }

    private val OPTIONAL_KEYS = setOf("album_sort_order", "year_sort_order")
    private val REQUIRED_KEYS = encode(PlayerPreferencesState()).keys - OPTIONAL_KEYS
}
