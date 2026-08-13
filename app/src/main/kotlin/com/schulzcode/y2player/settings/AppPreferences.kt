package com.schulzcode.y2player.settings

import android.content.Context
import android.content.SharedPreferences
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

class AppPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private var cached: PlayerPreferencesState? = null

    @Synchronized
    fun snapshot(): PlayerPreferencesState = cached ?: readSnapshot().also { cached = it }

    private fun readSnapshot(): PlayerPreferencesState = PlayerPreferencesState(
        uiSoundEffectsEnabled = boolean(KEY_UI_SOUND_EFFECTS, false),
        verboseDiagnostics = boolean(KEY_VERBOSE_DIAGNOSTICS, false),
        volumeMode = VolumeMode.fromStorage(string(KEY_VOLUME_MODE, null)),
        volumeLevel = VolumeCurve.clampLevel(integer(KEY_VOLUME_LEVEL, VolumeCurve.STEPS)),
        replayGainMode = ReplayGainMode.fromStorage(string(KEY_REPLAY_GAIN_MODE, null)),
        hapticLevel = HapticLevel.fromStorage(string(KEY_HAPTIC_LEVEL, null)),
        wrapLists = boolean(KEY_WRAP_LISTS, true),
        keepScreenOnWhilePlaying = boolean(KEY_KEEP_SCREEN_ON, false),
        extraTrackInfo = boolean(KEY_EXTRA_TRACK_INFO, false),
        lightTheme = boolean(KEY_LIGHT_THEME, false),
        localKeysWhileScreenOff = boolean(KEY_SCREEN_OFF_KEYS, false),
        pauseOnDisconnect = boolean(KEY_PAUSE_ON_DISCONNECT, true),
        resumePosition = boolean(KEY_RESUME_POSITION, true),
        sortOrder = TrackSortOrder.fromStorage(string(KEY_SORT_ORDER, TrackSortOrder.TITLE.storageId)),
        albumSortOrder = AlbumSortOrder.fromStorage(string(KEY_ALBUM_SORT_ORDER, AlbumSortOrder.TITLE.storageId)),
        yearSortOrder = YearSortOrder.fromStorage(string(KEY_YEAR_SORT_ORDER, YearSortOrder.NEWEST_FIRST.storageId)),
        gaplessEnabled = boolean(KEY_GAPLESS, true),
        crossfadeMs = integer(KEY_CROSSFADE, 0).takeIf { it in CROSSFADE_LEVELS } ?: 0,
        crossfadeMode = CrossfadeMode.fromStorage(string(KEY_CROSSFADE_MODE, null)),
        pauseResumeFadeMs = integer(KEY_PAUSE_FADE, 200).takeIf { it in FADE_LEVELS } ?: 200,
        seekStepMs = integer(KEY_SEEK_STEP, 10_000).takeIf { it in SEEK_LEVELS } ?: 10_000,
        longSeekStepMs = integer(KEY_LONG_SEEK_STEP, 30_000).takeIf { it in LONG_SEEK_LEVELS } ?: 30_000,
        previousRestartThresholdMs = integer(KEY_PREVIOUS_THRESHOLD, 4_000)
            .takeIf { it in PREVIOUS_THRESHOLD_LEVELS } ?: 4_000,
        duckOnFocusLoss = boolean(KEY_DUCK_ON_FOCUS_LOSS, true),
        audioQualityMode = AudioQualityMode.fromStorage(string(KEY_AUDIO_QUALITY, null)),
        audioEffectsEnabled = boolean(KEY_EFFECTS_ENABLED, false),
        equalizerPreset = integer(KEY_EQ_PRESET, 0).coerceIn(-1, MAX_EQ_PRESETS),
        equalizerBandLevelsMb = decodeLevels(string(KEY_EQ_BANDS, null)),
        bassStrength = integer(KEY_BASS_STRENGTH, 0).coerceIn(0, 1_000),
        loudnessGainMb = integer(KEY_LOUDNESS_GAIN, 0).coerceIn(0, 300),
        balance = AudioBalance.clamp(integer(KEY_BALANCE, AudioBalance.CENTRE))
    )

    fun toggleUiSoundEffects() = updateBoolean(KEY_UI_SOUND_EFFECTS, !snapshot().uiSoundEffectsEnabled)
    fun toggleVerboseDiagnostics() = updateBoolean(KEY_VERBOSE_DIAGNOSTICS, !snapshot().verboseDiagnostics)
    fun toggleKeepScreenOn() = updateBoolean(KEY_KEEP_SCREEN_ON, !snapshot().keepScreenOnWhilePlaying)
    fun toggleExtraTrackInfo() = updateBoolean(KEY_EXTRA_TRACK_INFO, !snapshot().extraTrackInfo)
    fun toggleLightTheme() = updateBoolean(KEY_LIGHT_THEME, !snapshot().lightTheme)
    fun toggleLocalKeysWhileScreenOff() = updateBoolean(KEY_SCREEN_OFF_KEYS, !snapshot().localKeysWhileScreenOff)
    fun togglePauseOnDisconnect() = updateBoolean(KEY_PAUSE_ON_DISCONNECT, !snapshot().pauseOnDisconnect)
    fun toggleResumePosition() = updateBoolean(KEY_RESUME_POSITION, !snapshot().resumePosition)
    fun toggleGapless() = updateBoolean(KEY_GAPLESS, !snapshot().gaplessEnabled)
    fun toggleDuckOnFocusLoss() = updateBoolean(KEY_DUCK_ON_FOCUS_LOSS, !snapshot().duckOnFocusLoss)
    fun toggleAudioEffects() = updateBoolean(KEY_EFFECTS_ENABLED, !snapshot().audioEffectsEnabled)
    fun cycleHapticLevel(): PlayerPreferencesState {
        val next = snapshot().hapticLevel.next()
        return commit { putString(KEY_HAPTIC_LEVEL, next.storageId) }
    }

    fun setVolumeMode(mode: VolumeMode, appLevel: Int): PlayerPreferencesState {
        return commit {
            putString(KEY_VOLUME_MODE, mode.storageId)
            putInt(KEY_VOLUME_LEVEL, VolumeCurve.clampLevel(appLevel))
        }
    }
    fun toggleWrapLists() = updateBoolean(KEY_WRAP_LISTS, !snapshot().wrapLists)

    fun adjustVolumeLevel(direction: Int): PlayerPreferencesState {
        val current = snapshot()
        if (current.volumeMode != VolumeMode.PERCEPTUAL) return current
        val next = VolumeCurve.adjustLevel(current.volumeLevel, direction)
        if (next == current.volumeLevel) return current
        return commit { putInt(KEY_VOLUME_LEVEL, next) }
    }

    fun cycleAudioQuality(): PlayerPreferencesState {
        val next = snapshot().audioQualityMode.next()
        return commit { putString(KEY_AUDIO_QUALITY, next.storageId) }
    }

    fun cycleReplayGain(): PlayerPreferencesState {
        val next = snapshot().replayGainMode.next()
        return commit { putString(KEY_REPLAY_GAIN_MODE, next.storageId) }
    }

    fun cycleCrossfade(): PlayerPreferencesState = cycleInt(KEY_CROSSFADE, CROSSFADE_LEVELS, snapshot().crossfadeMs)

    fun cycleCrossfadeMode(): PlayerPreferencesState {
        val next = snapshot().crossfadeMode.next()
        return commit { putString(KEY_CROSSFADE_MODE, next.storageId) }
    }
    fun cyclePauseFade(): PlayerPreferencesState = cycleInt(KEY_PAUSE_FADE, FADE_LEVELS, snapshot().pauseResumeFadeMs)
    fun cycleSeekStep(): PlayerPreferencesState = cycleInt(KEY_SEEK_STEP, SEEK_LEVELS, snapshot().seekStepMs)
    fun cycleLongSeekStep(): PlayerPreferencesState = cycleInt(KEY_LONG_SEEK_STEP, LONG_SEEK_LEVELS, snapshot().longSeekStepMs)
    fun cyclePreviousThreshold(): PlayerPreferencesState = cycleInt(
        KEY_PREVIOUS_THRESHOLD,
        PREVIOUS_THRESHOLD_LEVELS,
        snapshot().previousRestartThresholdMs
    )

    fun cycleEqualizerPreset(presetCount: Int): PlayerPreferencesState {
        if (presetCount <= 0) return snapshot()
        val current = snapshot().equalizerPreset
        val next = when {
            current < 0 -> 0
            current + 1 >= presetCount -> -1
            else -> current + 1
        }
        return commit { putInt(KEY_EQ_PRESET, next) }
    }

    fun adjustEqualizerBand(index: Int, deltaSteps: Int, minMb: Int, maxMb: Int, bandCount: Int): PlayerPreferencesState {
        if (index !in 0 until bandCount) return snapshot()
        val levels = snapshot().equalizerBandLevelsMb.toMutableList()
        while (levels.size < bandCount) levels += 0
        levels[index] = (levels[index] + deltaSteps * EQ_STEP_MB).coerceIn(minMb, maxMb)
        return commit {
            putInt(KEY_EQ_PRESET, -1)
            putString(KEY_EQ_BANDS, levels.joinToString(","))
        }
    }

    fun cycleBassStrength(): PlayerPreferencesState = cycleInt(KEY_BASS_STRENGTH, BASS_LEVELS, snapshot().bassStrength)
    fun cycleLoudnessGain(): PlayerPreferencesState = cycleInt(KEY_LOUDNESS_GAIN, LOUDNESS_LEVELS, snapshot().loudnessGainMb)

    fun setBalance(balance: Int): PlayerPreferencesState =
        commit { putInt(KEY_BALANCE, AudioBalance.clamp(balance)) }

    fun setSortOrder(order: TrackSortOrder): PlayerPreferencesState =
        commit { putString(KEY_SORT_ORDER, order.storageId) }

    fun setAlbumSortOrder(order: AlbumSortOrder): PlayerPreferencesState =
        commit { putString(KEY_ALBUM_SORT_ORDER, order.storageId) }

    fun setYearSortOrder(order: YearSortOrder): PlayerPreferencesState =
        commit { putString(KEY_YEAR_SORT_ORDER, order.storageId) }

    /** Replaces only Y2Player-owned settings and commits synchronously for restore transactions. */
    @Synchronized
    fun restore(value: PlayerPreferencesState): Boolean {
        val committed = preferences.edit().clear().apply {
            putBoolean(KEY_UI_SOUND_EFFECTS, value.uiSoundEffectsEnabled)
            putBoolean(KEY_VERBOSE_DIAGNOSTICS, value.verboseDiagnostics)
            putString(KEY_VOLUME_MODE, value.volumeMode.storageId)
            putInt(KEY_VOLUME_LEVEL, VolumeCurve.clampLevel(value.volumeLevel))
            putString(KEY_REPLAY_GAIN_MODE, value.replayGainMode.storageId)
            putString(KEY_HAPTIC_LEVEL, value.hapticLevel.storageId)
            putBoolean(KEY_WRAP_LISTS, value.wrapLists)
            putBoolean(KEY_KEEP_SCREEN_ON, value.keepScreenOnWhilePlaying)
            putBoolean(KEY_EXTRA_TRACK_INFO, value.extraTrackInfo)
            putBoolean(KEY_LIGHT_THEME, value.lightTheme)
            putBoolean(KEY_SCREEN_OFF_KEYS, value.localKeysWhileScreenOff)
            putBoolean(KEY_PAUSE_ON_DISCONNECT, value.pauseOnDisconnect)
            putBoolean(KEY_RESUME_POSITION, value.resumePosition)
            putString(KEY_SORT_ORDER, value.sortOrder.storageId)
            putString(KEY_ALBUM_SORT_ORDER, value.albumSortOrder.storageId)
            putString(KEY_YEAR_SORT_ORDER, value.yearSortOrder.storageId)
            putBoolean(KEY_GAPLESS, value.gaplessEnabled)
            putInt(KEY_CROSSFADE, value.crossfadeMs)
            putString(KEY_CROSSFADE_MODE, value.crossfadeMode.storageId)
            putInt(KEY_PAUSE_FADE, value.pauseResumeFadeMs)
            putInt(KEY_SEEK_STEP, value.seekStepMs)
            putInt(KEY_LONG_SEEK_STEP, value.longSeekStepMs)
            putInt(KEY_PREVIOUS_THRESHOLD, value.previousRestartThresholdMs)
            putBoolean(KEY_DUCK_ON_FOCUS_LOSS, value.duckOnFocusLoss)
            putString(KEY_AUDIO_QUALITY, value.audioQualityMode.storageId)
            putBoolean(KEY_EFFECTS_ENABLED, value.audioEffectsEnabled)
            putInt(KEY_EQ_PRESET, value.equalizerPreset)
            putString(KEY_EQ_BANDS, value.equalizerBandLevelsMb.joinToString(","))
            putInt(KEY_BASS_STRENGTH, value.bassStrength)
            putInt(KEY_LOUDNESS_GAIN, value.loudnessGainMb)
            putInt(KEY_BALANCE, value.balance)
        }.commit()
        if (committed) cached = value
        return committed
    }

    private fun updateBoolean(key: String, value: Boolean): PlayerPreferencesState =
        commit { putBoolean(key, value) }

    private fun cycleInt(key: String, levels: List<Int>, current: Int): PlayerPreferencesState {
        val index = levels.indexOf(current).takeIf { it >= 0 } ?: 0
        return commit { putInt(key, levels[(index + 1) % levels.size]) }
    }

    @Synchronized
    private fun commit(block: SharedPreferences.Editor.() -> Unit): PlayerPreferencesState {
        val editor = preferences.edit()
        editor.block()
        editor.apply()
        cached = null
        return readSnapshot().also { cached = it }
    }

    private fun boolean(key: String, fallback: Boolean): Boolean =
        runCatching { preferences.getBoolean(key, fallback) }.getOrDefault(fallback)

    private fun integer(key: String, fallback: Int): Int =
        runCatching { preferences.getInt(key, fallback) }.getOrDefault(fallback)

    private fun string(key: String, fallback: String?): String? =
        runCatching { preferences.getString(key, fallback) }.getOrDefault(fallback)

    private fun decodeLevels(raw: String?): List<Int> = raw.orEmpty().take(MAX_EQ_TEXT_CHARS)
        .split(',').asSequence().take(MAX_EQ_BANDS).mapNotNull(String::toIntOrNull)
        .map { it.coerceIn(-MAX_EQ_LEVEL_MB, MAX_EQ_LEVEL_MB) }.toList()

    companion object {
        private const val FILE_NAME = "y2_player_preferences"
        private const val KEY_UI_SOUND_EFFECTS = "ui_sound_effects"
        private const val KEY_VERBOSE_DIAGNOSTICS = "verbose_diagnostics"
        private const val KEY_VOLUME_MODE = "volume_mode"
        private const val KEY_VOLUME_LEVEL = "volume_level"
        private const val KEY_REPLAY_GAIN_MODE = "replay_gain_mode"
        private const val KEY_HAPTIC_LEVEL = "haptic_level"
        private const val KEY_WRAP_LISTS = "wrap_lists"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_EXTRA_TRACK_INFO = "extra_track_info"
        private const val KEY_LIGHT_THEME = "light_theme"
        private const val KEY_SCREEN_OFF_KEYS = "screen_off_keys"
        private const val KEY_PAUSE_ON_DISCONNECT = "pause_on_disconnect"
        private const val KEY_RESUME_POSITION = "resume_position"
        private const val KEY_SORT_ORDER = "sort_order"
        private const val KEY_ALBUM_SORT_ORDER = "album_sort_order"
        private const val KEY_YEAR_SORT_ORDER = "year_sort_order"
        private const val KEY_GAPLESS = "gapless"
        private const val KEY_CROSSFADE = "crossfade_ms"
        private const val KEY_CROSSFADE_MODE = "crossfade_mode"
        private const val KEY_PAUSE_FADE = "pause_resume_fade_ms"
        private const val KEY_SEEK_STEP = "seek_step_ms"
        private const val KEY_LONG_SEEK_STEP = "long_seek_step_ms"
        private const val KEY_PREVIOUS_THRESHOLD = "previous_restart_threshold_ms"
        private const val KEY_DUCK_ON_FOCUS_LOSS = "duck_on_focus_loss"
        private const val KEY_AUDIO_QUALITY = "audio_quality_mode"
        private const val KEY_EFFECTS_ENABLED = "effects_enabled"
        private const val KEY_EQ_PRESET = "eq_preset"
        private const val KEY_EQ_BANDS = "eq_bands"
        private const val KEY_BASS_STRENGTH = "bass_strength"
        private const val KEY_LOUDNESS_GAIN = "loudness_gain_mb"
        private const val KEY_BALANCE = "balance"
        private const val EQ_STEP_MB = 300
        private const val MAX_EQ_PRESETS = 100
        private const val MAX_EQ_BANDS = 32
        private const val MAX_EQ_TEXT_CHARS = 512
        private const val MAX_EQ_LEVEL_MB = 3_000

        val CROSSFADE_LEVELS = listOf(0, 2_000, 4_000, 6_000)
        val FADE_LEVELS = listOf(0, 100, 200, 300)
        val SEEK_LEVELS = listOf(5_000, 10_000, 15_000, 30_000, 60_000)
        val LONG_SEEK_LEVELS = listOf(10_000, 30_000, 60_000)
        val PREVIOUS_THRESHOLD_LEVELS = listOf(0, 2_000, 4_000, 6_000)
        val BASS_LEVELS = listOf(0, 250, 500, 750, 1_000)
        val LOUDNESS_LEVELS = listOf(0, 100, 200, 300)
    }
}
