package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.AlbumSortOrder
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.TrackSortOrder
import com.schulzcode.y2player.core.model.YearSortOrder
import com.schulzcode.y2player.diagnostics.DiagnosticsState

sealed interface AppAction {
    data class WheelMoved(val delta: Int) : AppAction
    data object Confirm : AppAction
    data object ConfirmLong : AppAction
    data object ShowNowPlaying : AppAction
    data object Back : AppAction
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
    data class BackupChanged(val backup: BackupUiState) : AppAction
    data class BackupImportReady(val summary: String) : AppAction
    data class SafeModeChanged(val enabled: Boolean) : AppAction
    data class ShowMessage(val message: String?) : AppAction
    data class SelectIndex(val index: Int) : AppAction
}

sealed interface AppEffect {
    data class PlayCollection(
        val trackIds: List<Long>,
        val startIndex: Int,
        val shuffled: Boolean = false,
        val fromStart: Boolean = false
    ) : AppEffect
    data class PlayQueueEntry(val entryId: Long) : AppEffect
    data class RemoveQueueEntry(val entryId: Long) : AppEffect
    data class MoveQueueEntry(val entryId: Long, val delta: Int) : AppEffect
    data class PromoteQueueEntry(val entryId: Long) : AppEffect
    data class PlayNext(val trackIds: List<Long>) : AppEffect
    data class AddToUpNext(val trackIds: List<Long>, val shuffled: Boolean = false) : AppEffect
    data object ClearUpNext : AppEffect
    data object ClearRemaining : AppEffect
    data object ClearQueue : AppEffect
    data object TogglePlayback : AppEffect
    data object NextTrack : AppEffect
    data object PreviousTrack : AppEffect
    data object ToggleShuffle : AppEffect
    data object CycleRepeat : AppEffect
    data class AdjustVolume(val direction: Int) : AppEffect
    data class SeekBy(val deltaMs: Long) : AppEffect
    data object RequestLibraryScan : AppEffect
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
    data object ToggleExtraTrackInfo : AppEffect
    data object ToggleLightTheme : AppEffect
    data object ToggleLocalKeysWhileScreenOff : AppEffect
    data class SetBalance(val balance: Int) : AppEffect
    data object TogglePauseOnDisconnect : AppEffect
    data object ToggleResumePosition : AppEffect
    data object ToggleGapless : AppEffect
    data object CycleCrossfade : AppEffect
    data object CycleCrossfadeMode : AppEffect
    data object CyclePauseFade : AppEffect
    data object CycleSeekStep : AppEffect
    data object CycleLongSeekStep : AppEffect
    data object CyclePreviousThreshold : AppEffect
    data object ToggleDuckOnFocusLoss : AppEffect
    data object CycleVolumeMode : AppEffect
    data object CycleReplayGain : AppEffect
    data object CycleHapticLevel : AppEffect
    data object ToggleWrapLists : AppEffect
    data object CycleSleepTimer : AppEffect
    data object CycleAudioQuality : AppEffect
    data object ToggleAudioEffects : AppEffect
    data object CycleEqualizerPreset : AppEffect
    data class AdjustEqualizerBand(val index: Int, val deltaSteps: Int) : AppEffect
    data object CycleBassStrength : AppEffect
    data object CycleLoudnessGain : AppEffect
    data class SetSortOrder(val order: TrackSortOrder) : AppEffect
    data class SetAlbumSortOrder(val order: AlbumSortOrder) : AppEffect
    data class SetYearSortOrder(val order: YearSortOrder) : AppEffect

    data object RefreshPlaybackHistory : AppEffect
    data object RefreshAudiobooks : AppEffect
    data class ClearAudiobookProgress(val folderKey: String) : AppEffect
    data object ClearPlaybackHistory : AppEffect
    data object ExportDiagnostics : AppEffect
    data object ClearDiagnostics : AppEffect
    data object ExportBackup : AppEffect
    data object InspectBackup : AppEffect
    data object ImportBackup : AppEffect
    data object ResetLibrary : AppEffect
    data object EnterSafeMode : AppEffect
    data object ExitSafeMode : AppEffect

    data object OpenAndroidSettings : AppEffect
}

data class Reduction(val state: AppState, val effects: List<AppEffect> = emptyList())

// R8 renames these classes, so simpleName logs as `b0` in release builds.
val AppAction.code: String get() = when (this) {
    is AppAction.WheelMoved -> if (delta >= 0) "wheel_clockwise" else "wheel_counter_clockwise"
    AppAction.Confirm -> "confirm"
    AppAction.ConfirmLong -> "confirm_long"
    AppAction.ShowNowPlaying -> "show_now_playing"
    AppAction.Back -> "back"
    AppAction.NavigateHome -> "navigate_home"
    AppAction.Left -> "left"
    AppAction.Right -> "right"
    AppAction.PlayPause -> "play_pause"
    AppAction.MediaNext -> "media_next"
    AppAction.MediaPrevious -> "media_previous"
    AppAction.MediaStop -> "media_stop"
    AppAction.SeekBackward -> "seek_backward"
    AppAction.SeekForward -> "seek_forward"
    AppAction.SeekBackwardLong -> "seek_backward_long"
    AppAction.SeekForwardLong -> "seek_forward_long"
    is AppAction.LibraryChanged -> "library_changed"
    is AppAction.PlaybackChanged -> "playback_changed"
    is AppAction.DeviceChanged -> "device_changed"
    is AppAction.BluetoothChanged -> "bluetooth_changed"
    is AppAction.DisplayChanged -> "display_changed"
    is AppAction.PreferencesChanged -> "preferences_changed"
    is AppAction.DiagnosticsChanged -> "diagnostics_changed"
    is AppAction.BackupChanged -> "backup_changed"
    is AppAction.BackupImportReady -> "backup_import_ready"
    is AppAction.SafeModeChanged -> "safe_mode_changed"
    is AppAction.ShowMessage -> "show_message"
    is AppAction.SelectIndex -> "select_index"
}
