package com.schulzcode.y2player.playback

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import com.schulzcode.y2player.core.model.AudioEffectsState
import com.schulzcode.y2player.core.state.PlayerPreferencesState
import com.schulzcode.y2player.diagnostics.DiagnosticLogger

@Suppress("DEPRECATION")
class AudioEffectsController(
    context: Context,
    private val sessionId: Int,
    private val logger: DiagnosticLogger
) {
    private val appContext = context.applicationContext
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var lastError: String? = null

    init {
        // Session 0 is the global output mix; attaching effects there (possible when
        // the MediaPlayer engine failed to initialize) would process every app's audio.
        // Effects are only created for a real, positive session id.
        if (sessionId > 0) {
            appContext.sendBroadcast(Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, appContext.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            })
            equalizer = create("Equalizer") { Equalizer(0, sessionId) }
            bassBoost = create("BassBoost") { BassBoost(0, sessionId) }
            loudnessEnhancer = create("LoudnessEnhancer") { LoudnessEnhancer(sessionId) }
        }
    }

    fun apply(preferences: PlayerPreferencesState): AudioEffectsState {
        lastError = null
        val enabled = preferences.audioEffectsEnabled
        val eq = equalizer
        val bass = bassBoost
        val loudness = loudnessEnhancer

        if (eq != null) {
            runEffect("Equalizer") {
                if (preferences.equalizerPreset >= 0 && preferences.equalizerPreset < eq.numberOfPresets) {
                    eq.usePreset(preferences.equalizerPreset.toShort())
                } else {
                    val range = eq.bandLevelRange
                    repeat(eq.numberOfBands.toInt()) { index ->
                        val requested = preferences.equalizerBandLevelsMb.getOrNull(index) ?: 0
                        eq.setBandLevel(index.toShort(), requested.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
                    }
                }
                eq.enabled = enabled
            }
        }

        if (bass != null) {
            runEffect("BassBoost") {
                if (bass.strengthSupported) bass.setStrength(preferences.bassStrength.coerceIn(0, 1_000).toShort())
                bass.enabled = enabled && preferences.bassStrength > 0
            }
        }

        if (loudness != null) {
            runEffect("LoudnessEnhancer") {
                loudness.setTargetGain(preferences.loudnessGainMb.coerceIn(0, 300))
                loudness.enabled = enabled && preferences.loudnessGainMb > 0
            }
        }

        return snapshot(preferences)
    }

    fun snapshot(preferences: PlayerPreferencesState): AudioEffectsState {
        val eq = equalizer
        val range = runCatching { eq?.bandLevelRange }.getOrNull()
        val presetNames = if (eq == null) emptyList() else buildList {
            repeat(runCatching { eq.numberOfPresets.toInt() }.getOrDefault(0).coerceIn(0, MAX_EFFECT_ITEMS)) { index ->
                add(runCatching { eq.getPresetName(index.toShort()) }.getOrDefault("Preset ${index + 1}"))
            }
        }
        val frequencies = if (eq == null) emptyList() else buildList {
            repeat(runCatching { eq.numberOfBands.toInt() }.getOrDefault(0).coerceIn(0, MAX_EFFECT_ITEMS)) { index ->
                add(runCatching { eq.getCenterFreq(index.toShort()) / 1_000 }.getOrDefault(0))
            }
        }
        val levels = if (eq == null) emptyList() else buildList {
            repeat(runCatching { eq.numberOfBands.toInt() }.getOrDefault(0).coerceIn(0, MAX_EFFECT_ITEMS)) { index ->
                add(runCatching { eq.getBandLevel(index.toShort()).toInt() }.getOrDefault(0))
            }
        }
        return AudioEffectsState(
            available = eq != null || bassBoost != null || loudnessEnhancer != null,
            enabled = preferences.audioEffectsEnabled,
            equalizerSupported = eq != null,
            presetNames = presetNames,
            selectedPreset = preferences.equalizerPreset,
            bandFrequenciesHz = frequencies,
            bandLevelsMb = levels,
            bandMinMb = range?.getOrNull(0)?.toInt() ?: -1_500,
            bandMaxMb = range?.getOrNull(1)?.toInt() ?: 1_500,
            bassBoostSupported = bassBoost?.strengthSupported == true,
            bassStrength = preferences.bassStrength,
            loudnessSupported = loudnessEnhancer != null,
            loudnessGainMb = preferences.loudnessGainMb,
            errorMessage = lastError
        )
    }

    fun release() {
        runCatching { equalizer?.enabled = false }
        runCatching { bassBoost?.enabled = false }
        runCatching { loudnessEnhancer?.enabled = false }
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { loudnessEnhancer?.release() }
        equalizer = null
        bassBoost = null
        loudnessEnhancer = null
        if (sessionId > 0) {
            appContext.sendBroadcast(Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, appContext.packageName)
            })
        }
    }

    private inline fun <T> create(name: String, factory: () -> T): T? = try {
        factory()
    } catch (error: Throwable) {
        logger.warn("AudioEffects", "$name unavailable: ${error.message ?: error.javaClass.simpleName}")
        null
    }

    private inline fun runEffect(name: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            lastError = "$name: ${error.message ?: error.javaClass.simpleName}"
            logger.warn("AudioEffects", lastError.orEmpty())
        }
    }

    companion object {
        private const val MAX_EFFECT_ITEMS = 64
    }
}
