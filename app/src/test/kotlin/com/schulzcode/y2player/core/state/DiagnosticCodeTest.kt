package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCodeTest {
    @Test
    fun `action codes are snake case and carry no class name`() {
        assertEquals("play_pause", AppAction.PlayPause.code)
        assertEquals("show_now_playing", AppAction.ShowNowPlaying.code)
        assertEquals("wheel_clockwise", AppAction.WheelMoved(3).code)
        assertEquals("confirm_long", AppAction.ConfirmLong.code)
    }

    @Test
    fun `screen codes are snake case`() {
        assertEquals("main_menu", (Screen.MainMenu as Screen).code)
        assertEquals("now_playing", (Screen.NowPlaying as Screen).code)
        assertEquals("playback_history", (Screen.PlaybackHistory as Screen).code)
        assertEquals("playback_transitions", (Screen.PlaybackTransitions as Screen).code)
    }

    @Test
    fun `parameterised screens report only their kind, never user data`() {
        assertEquals("album_songs", (Screen.AlbumSongs("Kind of Blue", "Miles Davis") as Screen).code)
        assertEquals("playlist_tracks", (Screen.PlaylistTracks(5, "Road Trip") as Screen).code)
        assertEquals("folders", (Screen.Folders("sdcard", "Music/Jazz") as Screen).code)
        assertEquals("track_options", (Screen.TrackOptions(42) as Screen).code)

        for (screen in listOf<Screen>(
            Screen.AlbumSongs("Kind of Blue", "Miles Davis"),
            Screen.PlaylistTracks(5, "Road Trip"),
            Screen.Folders("sdcard", "Music/Jazz")
        )) {
            val code = screen.code
            assertFalse(code.contains("Kind of Blue"))
            assertFalse(code.contains("Miles Davis"))
            assertFalse(code.contains("Road Trip"))
            assertFalse(code.contains("Music/Jazz"))
        }
    }

    @Test
    fun `parameterised screens of the same kind share one code`() {
        assertEquals(
            (Screen.AlbumSongs("A") as Screen).code,
            (Screen.AlbumSongs("B", "C") as Screen).code
        )
    }

    @Test
    fun `codes survive obfuscation because they are literals`() {
        val action: AppAction = AppAction.PlayPause
        assertFalse(action.code == action::class.java.simpleName)
        assertTrue(action.code.none { it.isUpperCase() })
    }

    @Test
    fun `every screen reachable from the reducer has a code`() {
        val screens = listOf<Screen>(
            Screen.MainMenu, Screen.Music, Screen.Audiobooks,
            Screen.AudiobookOptions("k"), Screen.AudiobookChapters("k"),
            Screen.Songs, Screen.Favorites, Screen.RecentlyPlayed,
            Screen.Albums, Screen.Artists, Screen.Playlists, Screen.Queue, Screen.NowPlaying,
            Screen.NowPlayingOptions, Screen.Audio, Screen.Settings,
            Screen.PlaybackTransitions, Screen.PlaybackSeeking, Screen.PlaybackVolume,
            Screen.PlaybackInterruptions, Screen.SoundEffects, Screen.EqualizerSettings,
            Screen.OutputInformation, Screen.EqualizerBands, Screen.SortOrder,
            Screen.Bluetooth, Screen.InterfaceSettings, Screen.LibrarySettings, Screen.Display,
            Screen.Controls, Screen.Balance, Screen.Brightness, Screen.ScreenTimeout, Screen.Storage,
            Screen.PlaybackHistory, Screen.System, Screen.Diagnostics, Screen.About,
            Screen.QueueManagement, Screen.QueueMove(1, 1), Screen.Reset,
            Screen.BluetoothDevice("AA:BB:CC:DD:EE:FF"),
            Screen.ConfirmAction("forget_device:AA:BB:CC:DD:EE:FF")
        )
        val codes = screens.map { it.code }
        assertTrue(codes.none { it.isBlank() })
        assertEquals("codes must be unique", codes.size, codes.toSet().size)
    }

    @Test
    fun `every action the reducer accepts has a unique code`() {
        val actions = listOf(
            AppAction.WheelMoved(1), AppAction.WheelMoved(-1), AppAction.Confirm,
            AppAction.ConfirmLong, AppAction.ShowNowPlaying, AppAction.Back, AppAction.NavigateHome,
            AppAction.Left, AppAction.Right, AppAction.PlayPause, AppAction.MediaNext,
            AppAction.MediaPrevious, AppAction.MediaStop, AppAction.SeekBackward,
            AppAction.SeekForward, AppAction.SeekBackwardLong, AppAction.SeekForwardLong,
            AppAction.LibraryChanged(LibraryState()), AppAction.ShowMessage("x"),
            AppAction.SelectIndex(3)
        )
        val codes = actions.map { it.code }
        assertTrue(codes.none { it.isBlank() })
        assertEquals("codes must be unique", codes.size, codes.toSet().size)
    }
}
