package com.schulzcode.y2player.ui

import com.schulzcode.y2player.core.model.AudioQualityMode
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.SleepTimerMode
import com.schulzcode.y2player.core.state.AppState
import com.schulzcode.y2player.core.state.BluetoothAdapterMode
import com.schulzcode.y2player.core.state.BluetoothLinkState
import com.schulzcode.y2player.core.state.ScreenRow
import com.schulzcode.y2player.input.HapticLevel
import com.schulzcode.y2player.playback.ReplayGainMode

object Y2RowState {
    fun isActive(row: ScreenRow, state: AppState): Boolean {
        if (row is ScreenRow.TrackRow) return if (row.selectionIndex != null) row.selected else
            (row.queueEntry?.id?.let { it == state.playback.currentQueueEntryId }
            ?: (row.track.id == state.playback.currentTrackId)) &&
            (state.playback.status == PlaybackStatus.PLAYING || state.playback.status == PlaybackStatus.PREPARING)
        val action = row as? ScreenRow.Action ?: return false
        val key = action.key
        return when {
            key == "shuffle" -> state.playback.shuffleEnabled
            key == "repeat" -> state.playback.repeatMode != RepeatMode.OFF
            key == "sleep_timer" -> state.playback.sleepTimerMode != SleepTimerMode.OFF
            key == "gapless" -> state.preferences.gaplessEnabled && state.preferences.crossfadeMs == 0
            key == "crossfade" -> state.preferences.crossfadeMs > 0
            key == "pause_fade" -> state.preferences.pauseResumeFadeMs > 0
            key == "pause_disconnect" -> !state.preferences.pauseOnDisconnect
            key == "resume_position" -> state.preferences.resumePosition
            key == "keep_screen_on" -> state.preferences.keepScreenOnWhilePlaying
            key == "extra_track_info" -> state.preferences.extraTrackInfo
            key == "wrap_lists" -> state.preferences.wrapLists
            key == "screen_off_keys" -> state.preferences.localKeysWhileScreenOff
            key == "ui_sounds" -> state.preferences.uiSoundEffectsEnabled
            key == "haptics" -> state.preferences.hapticLevel != HapticLevel.OFF
            key == "theme" -> state.preferences.lightTheme
            key == "effects_toggle" -> state.preferences.audioEffectsEnabled
            key == "bass" -> state.preferences.bassStrength > 0
            key == "loudness" -> state.preferences.loudnessGainMb > 0
            key == "audio_quality" -> state.preferences.audioQualityMode == AudioQualityMode.DIRECT_DAC
            key == "replay_gain" -> state.preferences.replayGainMode != ReplayGainMode.OFF
            key == "diag_verbose" -> state.preferences.verboseDiagnostics
            key == "bt_toggle" -> state.bluetooth.adapterMode == BluetoothAdapterMode.ON
            isFavoriteKey(key) -> favoriteTrackId(key)?.let(state.library.byId::get)?.favorite == true
            key.startsWith("bt_device:") -> state.bluetooth.devices.any {
                "bt_device:${it.address}" == key &&
                    (it.audioStreaming || it.linkState == BluetoothLinkState.CONNECTED)
            }
            key.startsWith("track_sort:") -> key.substringAfter(':') == state.preferences.sortOrder.storageId
            key.startsWith("album_sort:") -> key.substringAfter(':') == state.preferences.albumSortOrder.storageId
            key.startsWith("year_sort:") -> key.substringAfter(':') == state.preferences.yearSortOrder.storageId
            key.startsWith("balance:") -> key.substringAfter(':').toIntOrNull() == state.preferences.balance
            key.startsWith("brightness:") -> key.substringAfter(':').toIntOrNull()?.let {
                kotlin.math.abs(state.display.brightnessPercent - it) <= 5
            } == true
            key.startsWith("timeout:") -> key.substringAfter(':').toIntOrNull() == state.display.screenTimeoutMs
            else -> false
        }
    }

    fun isFavoriteKey(key: String): Boolean =
        key.startsWith("track_favorite:") || key.startsWith("np_favorite:")

    private fun favoriteTrackId(key: String): Long? = key.substringAfter(':', "").toLongOrNull()
}
