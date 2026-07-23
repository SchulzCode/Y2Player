package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.AudioQualityMode
import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.TrackSortOrder
import com.schulzcode.y2player.diagnostics.DiagnosticsState
import com.schulzcode.y2player.input.HapticLevel
import com.schulzcode.y2player.playback.VolumeCurve
import com.schulzcode.y2player.playback.VolumeMode

enum class BluetoothAdapterMode { UNSUPPORTED, OFF, TURNING_ON, ON, TURNING_OFF }
enum class BluetoothLinkState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

data class BluetoothDeviceEntry(
    val address: String,
    val name: String,
    val bonded: Boolean,
    val bonding: Boolean,
    val linkState: BluetoothLinkState = BluetoothLinkState.DISCONNECTED,
    val audioStreaming: Boolean = false
)

data class BluetoothUiState(
    val adapterMode: BluetoothAdapterMode = BluetoothAdapterMode.UNSUPPORTED,
    val isDiscovering: Boolean = false,
    val devices: List<BluetoothDeviceEntry> = emptyList(),
    val pendingOperation: String? = null,
    val lastError: String? = null
) {
    val audioConnected: Boolean get() = devices.any { it.linkState == BluetoothLinkState.CONNECTED }
    val audioStreaming: Boolean get() = devices.any { it.audioStreaming }
}

data class StorageVolumeState(
    val id: String,
    val label: String,
    val path: String,
    val available: Boolean,
    val totalBytes: Long = 0,
    val freeBytes: Long = 0
)

data class DeviceState(
    val internalStorageAvailable: Boolean = false,
    val removableStorageAvailable: Boolean = false,
    val storageVolumes: List<StorageVolumeState> = emptyList(),
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val deviceModel: String = "Innioasis Y2",
    val androidVersion: String = "Android 4.4",
    val firmwareBuild: String = "Unknown",
    val uptimeMs: Long = 0,
    /** False until the device profile is resolved; hides the haptics setting. */
    val hapticsAvailable: Boolean = false
)

data class DisplayState(
    val brightnessPercent: Int = 50,
    val screenTimeoutMs: Int = 60_000,
    val canWriteSystemSettings: Boolean = false
)

data class PlayerPreferencesState(
    /**
     * Android's own UI click/feedback sounds. Default off: a dedicated music
     * player must never inject its own clicks into the listener's ears. The
     * setting is user-reversible because it mutates a system-wide value.
     */
    val uiSoundEffectsEnabled: Boolean = false,
    /**
     * Records DEBUG/INFO structured events (WARN and ERROR are always recorded
     * regardless, so normal use costs almost nothing).
     */
    val verboseDiagnostics: Boolean = false,
    /**
     * Which layer attenuates the signal. SYSTEM is the default: the Android music
     * stream responds to the volume keys and the app adds no attenuation of its
     * own. PERCEPTUAL attenuates inside the player instead. The two are never
     * stacked — exactly one of them responds to the volume keys.
     */
    val volumeMode: VolumeMode = VolumeMode.SYSTEM,
    /** In-app level in `0..VolumeCurve.STEPS`. Only meaningful in PERCEPTUAL mode. */
    val volumeLevel: Int = VolumeCurve.STEPS,
    /** Wheel detent feedback. Off by default; see [HapticLevel] for why it is a duration. */
    val hapticLevel: HapticLevel = HapticLevel.OFF,
    val keepScreenOnWhilePlaying: Boolean = false,
    val pauseOnDisconnect: Boolean = true,
    val resumePosition: Boolean = true,
    val sortOrder: TrackSortOrder = TrackSortOrder.TITLE,
    val gaplessEnabled: Boolean = true,
    val crossfadeMs: Int = 0,
    val pauseResumeFadeMs: Int = 200,
    val seekStepMs: Int = 10_000,
    val longSeekStepMs: Int = 30_000,
    val previousRestartThresholdMs: Int = 4_000,
    val duckOnFocusLoss: Boolean = true,
    val audioQualityMode: AudioQualityMode = AudioQualityMode.BALANCED,
    val audioEffectsEnabled: Boolean = false,
    val equalizerPreset: Int = 0,
    val equalizerBandLevelsMb: List<Int> = emptyList(),
    val bassStrength: Int = 0,
    val loudnessGainMb: Int = 0
)

data class AppState(
    val screenStack: List<ScreenEntry> = listOf(ScreenEntry(Screen.MainMenu)),
    val library: LibraryState = LibraryState(),
    val playback: PlaybackSnapshot = PlaybackSnapshot(),
    val device: DeviceState = DeviceState(),
    val bluetooth: BluetoothUiState = BluetoothUiState(),
    val display: DisplayState = DisplayState(),
    val preferences: PlayerPreferencesState = PlayerPreferencesState(),
    val diagnostics: DiagnosticsState = DiagnosticsState(),
    val safeMode: Boolean = false,
    val transientMessage: String? = null
) {
    val currentEntry: ScreenEntry get() = screenStack.last()
    val currentScreen: Screen get() = currentEntry.screen
    val selectedIndex: Int get() = currentEntry.selectedIndex

}
