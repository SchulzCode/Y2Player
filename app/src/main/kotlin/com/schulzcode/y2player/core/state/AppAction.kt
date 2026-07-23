package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.TrackSortOrder
import com.schulzcode.y2player.diagnostics.DiagnosticsState

sealed interface AppAction {
    data object WheelClockwise : AppAction
    data object WheelCounterClockwise : AppAction
    data object Confirm : AppAction
    /** Long-press of the center key: contextual options (Now Playing menu, track/queue options). */
    data object ConfirmLong : AppAction
    data object Back : AppAction
    /** HOME re-entry: a launcher always returns to its top level. */
    data object NavigateHome : AppAction
    data object Left : AppAction
    data object Right : AppAction
    data object PlayPause : AppAction
    data object MediaNext : AppAction
    data object MediaPrevious : AppAction
    data object MediaStop : AppAction
    data object SeekBackward : AppAction
    data object SeekForward : AppAction
    data object SeekBackwardLong : AppAction
    data object SeekForwardLong : AppAction
    data class LibraryChanged(val library: LibraryState) : AppAction
    data class PlaybackChanged(val playback: PlaybackSnapshot) : AppAction
    data class DeviceChanged(val device: DeviceState) : AppAction
    data class BluetoothChanged(val bluetooth: BluetoothUiState) : AppAction
    data class DisplayChanged(val display: DisplayState) : AppAction
    data class PreferencesChanged(val preferences: PlayerPreferencesState) : AppAction
    data class DiagnosticsChanged(val diagnostics: DiagnosticsState) : AppAction
    data class SafeModeChanged(val enabled: Boolean) : AppAction
    data class ShowMessage(val message: String?) : AppAction
    data class SelectIndex(val index: Int) : AppAction
}

sealed interface AppEffect {
    data class PlayCollection(val trackIds: List<Long>, val startIndex: Int) : AppEffect
    data class PlayQueueIndex(val index: Int) : AppEffect
    data class RemoveQueueIndex(val index: Int) : AppEffect
    data class MoveQueueItem(val index: Int, val delta: Int) : AppEffect
    data class PlayNext(val trackId: Long) : AppEffect
    data class AddToQueue(val trackId: Long) : AppEffect
    data object ClearUpcoming : AppEffect
    data object ClearQueue : AppEffect
    data object TogglePlayback : AppEffect
    data object NextTrack : AppEffect
    data object PreviousTrack : AppEffect
    data object ToggleShuffle : AppEffect
    data object CycleRepeat : AppEffect
    data class AdjustVolume(val direction: Int) : AppEffect
    data class SeekBy(val deltaMs: Long) : AppEffect
    data object RequestLibraryScan : AppEffect
    /** One-press "just play my music": random start track, shuffle enabled. */
    data object ShuffleAll : AppEffect
    data class ToggleFavorite(val trackId: Long) : AppEffect
    data object CreatePlaylist : AppEffect
    data class CreatePlaylistWithTrack(val trackId: Long) : AppEffect
    data class AddTrackToPlaylist(val playlistId: Long, val trackId: Long) : AppEffect
    data class RemoveTrackFromPlaylist(val playlistId: Long, val trackId: Long) : AppEffect
    data class DeletePlaylist(val playlistId: Long) : AppEffect
    data object ImportM3uPlaylists : AppEffect
    data object ExportM3uPlaylists : AppEffect

    data class SetBluetoothEnabled(val enabled: Boolean) : AppEffect
    data object StartBluetoothScan : AppEffect
    data object StopBluetoothScan : AppEffect
    data object RefreshBluetoothService : AppEffect
    data class ActivateBluetoothDevice(val address: String) : AppEffect
    data class ForgetBluetoothDevice(val address: String) : AppEffect

    data class SetBrightness(val percent: Int) : AppEffect
    data class SetScreenTimeout(val timeoutMs: Int) : AppEffect
    data object ToggleUiSoundEffects : AppEffect
    data object ToggleVerboseDiagnostics : AppEffect
    data object ToggleKeepScreenOn : AppEffect
    data object TogglePauseOnDisconnect : AppEffect
    data object ToggleResumePosition : AppEffect
    data object ToggleGapless : AppEffect
    data object CycleCrossfade : AppEffect
    data object CyclePauseFade : AppEffect
    data object CycleSeekStep : AppEffect
    data object CycleLongSeekStep : AppEffect
    data object CyclePreviousThreshold : AppEffect
    data object ToggleDuckOnFocusLoss : AppEffect
    data object CycleVolumeMode : AppEffect
    data object CycleHapticLevel : AppEffect
    data object CycleSleepTimer : AppEffect
    data object CycleAudioQuality : AppEffect
    data object ToggleAudioEffects : AppEffect
    data object CycleEqualizerPreset : AppEffect
    data class AdjustEqualizerBand(val index: Int, val deltaSteps: Int) : AppEffect
    data object CycleBassStrength : AppEffect
    data object CycleLoudnessGain : AppEffect
    data class SetSortOrder(val order: TrackSortOrder) : AppEffect

    data object ExportDiagnostics : AppEffect
    data object RunFormatProbe : AppEffect
    data object ResetLibrary : AppEffect
    data object EnterSafeMode : AppEffect
    data object ExitSafeMode : AppEffect

    /** Escape hatch to the platform Settings app (see docs/PRODUCT_DESIGN.md). */
    data object OpenAndroidSettings : AppEffect

    /** Explicit enable/disable — never a toggle, so the outcome is deterministic. */
}

data class Reduction(val state: AppState, val effects: List<AppEffect> = emptyList())
