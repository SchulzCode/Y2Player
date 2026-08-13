package com.schulzcode.y2player.core.model

enum class PlaybackStatus { IDLE, PREPARING, PLAYING, PAUSED, ERROR }

enum class AudioOutputRoute { WIRED, BLUETOOTH, SPEAKER, NONE, UNKNOWN }

object AudioOutputRouteResolver {
    fun resolve(
        wired: Boolean,
        bluetooth: Boolean,
        status: PlaybackStatus,
        pauseReason: PauseReason
    ): AudioOutputRoute = when {
        bluetooth -> AudioOutputRoute.BLUETOOTH
        wired -> AudioOutputRoute.WIRED
        pauseReason == PauseReason.OUTPUT_DISCONNECTED -> AudioOutputRoute.NONE
        status == PlaybackStatus.PLAYING || status == PlaybackStatus.PREPARING -> AudioOutputRoute.SPEAKER
        else -> AudioOutputRoute.UNKNOWN
    }
}

enum class AudioQualityMode(val storageId: String, val label: String) {
    BALANCED("balanced", "Balanced"),
    DIRECT_DAC("direct_dac", "Direct DAC");

    fun next(): AudioQualityMode = values()[(ordinal + 1) % values().size]

    companion object {
        fun fromStorage(value: String?): AudioQualityMode = values().firstOrNull {
            it.storageId == value || it.name == value
        } ?: BALANCED
    }
}

enum class PauseReason {
    NONE,
    USER,
    AUDIO_FOCUS,
    OUTPUT_DISCONNECTED,
    STORAGE_REMOVED,
    PLAYBACK_ERROR,
    SAFE_MODE,
    SLEEP_TIMER
}

enum class RepeatMode(val storageId: String) {
    OFF("off"),
    ALL("all"),
    ONE("one");

    fun next(): RepeatMode = when (this) { OFF -> ALL; ALL -> ONE; ONE -> OFF }

    companion object {
        fun fromStorage(value: String?): RepeatMode = values().firstOrNull {
            it.storageId == value || it.name == value
        } ?: OFF
    }
}

enum class QueueOrigin(val storageId: String) {
    CONTINUATION("continuation"),
    UP_NEXT("up_next");

    companion object {
        fun fromStorage(value: String?): QueueOrigin = values().firstOrNull {
            it.storageId == value || it.name == value
        } ?: CONTINUATION
    }
}

/** A unique occurrence in the materialized playback order. */
data class QueueEntry(
    val id: Long,
    val trackId: Long,
    val origin: QueueOrigin,
    /** Original collection position, used to restore ordered playback after shuffle. */
    val sourceOrder: Int?
)

// Codes are written to the history file and read off-device. R8 renames the
// constants, so the code is the contract, not the name.
enum class PlaybackExitReason(val code: String) {
    COMPLETED("completed"),
    MANUAL_NEXT("manual_next"),
    MANUAL_PREVIOUS("manual_previous"),
    MANUAL_SELECT("manual_select"),
    QUEUE_REPLACED("queue_replaced"),
    EXPLICIT_STOP("explicit_stop"),
    QUEUE_END("queue_end"),
    SLEEP_TIMER("sleep_timer"),
    SOURCE_REMOVED("source_removed"),
    ERROR("error"),
    SERVICE_SHUTDOWN("service_shutdown"),
    SAFE_MODE("safe_mode"),
    UNKNOWN("unknown")
}

enum class SleepTimerMode(val label: String, val durationMs: Long? = null) {
    OFF("Off"),
    MINUTES_15("15 minutes", 15 * 60_000L),
    MINUTES_30("30 minutes", 30 * 60_000L),
    MINUTES_60("60 minutes", 60 * 60_000L),
    END_TRACK("End of track"),
    END_ALBUM("End of album"),
    END_QUEUE("End of queue");

    fun next(): SleepTimerMode = values()[(ordinal + 1) % values().size]
}

data class AudioEffectsState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val equalizerSupported: Boolean = false,
    val presetNames: List<String> = emptyList(),
    val selectedPreset: Int = 0,
    val bandFrequenciesHz: List<Int> = emptyList(),
    val bandLevelsMb: List<Int> = emptyList(),
    val bandMinMb: Int = -1_500,
    val bandMaxMb: Int = 1_500,
    val bassBoostSupported: Boolean = false,
    val bassStrength: Int = 0,
    val loudnessSupported: Boolean = false,
    val loudnessGainMb: Int = 0,
    val errorMessage: String? = null
)

data class DacState(
    val detected: Boolean = false,
    val hiFiRequestAccepted: Boolean = false,
    val outputSampleRate: Int? = null,
    val outputFormat: String? = null,
    val limitation: String? = null
)

data class PlaybackSnapshot(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val currentTrackId: Long? = null,
    val nextTrackId: Long? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Current entry followed by the exact upcoming playback order; history is intentionally hidden. */
    val queue: List<QueueEntry> = emptyList(),
    val currentQueueEntryId: Long? = null,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val pauseReason: PauseReason = PauseReason.NONE,
    val errorMessage: String? = null,
    val sleepTimerMode: SleepTimerMode = SleepTimerMode.OFF,
    val sleepTimerRemainingMs: Long? = null,
    val outputRoute: AudioOutputRoute = AudioOutputRoute.UNKNOWN,
    val audioEffects: AudioEffectsState = AudioEffectsState(),
    val dac: DacState = DacState()
)
