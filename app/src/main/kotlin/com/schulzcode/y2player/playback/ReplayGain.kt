package com.schulzcode.y2player.playback

import kotlin.math.min
import kotlin.math.pow

enum class ReplayGainMode(val storageId: String, val label: String) {
    OFF("off", "Off"),
    ALBUM("album", "Album Gain"),
    TRACK("track", "Track Gain"),
    TRACK_WHEN_SHUFFLING("track_when_shuffling", "Track Gain while shuffling");

    fun next(): ReplayGainMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromStorage(value: String?): ReplayGainMode = entries.firstOrNull {
            it.storageId == value || it.name == value
        } ?: OFF
    }
}

enum class CrossfadeMode(val storageId: String, val label: String) {
    ALWAYS("always", "Always"),

    WHILE_SHUFFLING("while_shuffling", "While shuffling");

    fun next(): CrossfadeMode = entries[(ordinal + 1) % entries.size]

    fun effectiveMs(configuredMs: Int, shuffleEnabled: Boolean): Long = when {
        configuredMs <= 0 -> 0L
        this == ALWAYS -> configuredMs.toLong()
        shuffleEnabled -> configuredMs.toLong()
        else -> 0L
    }

    companion object {
        fun fromStorage(value: String?): CrossfadeMode = entries.firstOrNull {
            it.storageId == value || it.name == value
        } ?: ALWAYS
    }
}

internal data class ReplayGainMetadata(
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null
)

internal enum class ReplayGainSource { NONE, TRACK, ALBUM }

internal data class ReplayGainAdjustment(
    val linearGain: Float = 1f,
    val gainDb: Float? = null,
    val peak: Float? = null,
    val source: ReplayGainSource = ReplayGainSource.NONE,
    val clippingPrevented: Boolean = false
)

internal object ReplayGain {
    private const val MIN_GAIN_DB = -60f
    private const val MAX_GAIN_DB = 24f

    fun resolve(
        mode: ReplayGainMode,
        shuffling: Boolean,
        metadata: ReplayGainMetadata
    ): ReplayGainAdjustment {
        if (mode == ReplayGainMode.OFF) return ReplayGainAdjustment()
        val preferTrack = mode == ReplayGainMode.TRACK ||
            (mode == ReplayGainMode.TRACK_WHEN_SHUFFLING && shuffling)
        return if (preferTrack) {
            adjustment(
                preferredGainDb = metadata.trackGainDb,
                preferredPeak = metadata.trackPeak,
                preferredSource = ReplayGainSource.TRACK,
                fallbackGainDb = metadata.albumGainDb,
                fallbackPeak = metadata.albumPeak,
                fallbackSource = ReplayGainSource.ALBUM
            )
        } else {
            adjustment(
                preferredGainDb = metadata.albumGainDb,
                preferredPeak = metadata.albumPeak,
                preferredSource = ReplayGainSource.ALBUM,
                fallbackGainDb = metadata.trackGainDb,
                fallbackPeak = metadata.trackPeak,
                fallbackSource = ReplayGainSource.TRACK
            )
        }
    }

    private fun adjustment(
        preferredGainDb: Float?,
        preferredPeak: Float?,
        preferredSource: ReplayGainSource,
        fallbackGainDb: Float?,
        fallbackPeak: Float?,
        fallbackSource: ReplayGainSource
    ): ReplayGainAdjustment {
        val usePreferred = preferredGainDb.isUsableGain()
        val gainDb = (if (usePreferred) preferredGainDb else fallbackGainDb)
            ?.takeIf(Float::isFinite)
            ?: return ReplayGainAdjustment()
        val peak = (if (usePreferred) preferredPeak else fallbackPeak)
            ?.takeIf { it.isFinite() && it > 0f }
        val source = if (usePreferred) preferredSource else fallbackSource
        val requested = 10.0.pow(gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) / 20.0).toFloat()
        val maximumWithoutClipping = peak?.let { 1f / it }
        val applied = maximumWithoutClipping?.let { min(requested, it) } ?: requested
        return ReplayGainAdjustment(
            linearGain = applied.coerceIn(0f, PcmGain.MAX_LEVEL),
            gainDb = gainDb,
            peak = peak,
            source = source,
            clippingPrevented = maximumWithoutClipping != null && applied < requested
        )
    }

    private fun Float?.isUsableGain(): Boolean = this != null && isFinite()
}
