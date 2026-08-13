package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NavigationCorrectnessTest {

    @Before fun clearRowCache() {
        ScreenContent.clearCachedRows()
    }

    private val track = Track(
        id = 1,
        volumeId = "internal",
        absolutePath = "/storage/sdcard0/song.mp3",
        relativePath = "song.mp3",
        title = "Song",
        artist = "Artist",
        album = "Album",
        albumArtist = null,
        trackNumber = 1,
        discNumber = 1,
        durationMs = 60_000,
        fileSize = 123,
        modifiedAt = 1
    )

    private val library = LibraryState(tracks = listOf(track))
    private val playing = PlaybackSnapshot(
        currentTrackId = 1L,
        queue = testQueue(1L),
        currentQueueEntryId = 1L,
        status = PlaybackStatus.PLAYING
    )

    private fun selectKey(state: AppState, key: String): AppState {
        val index = ScreenContent.rows(state).indexOfFirst { (it as? ScreenRow.Action)?.key == key }
        require(index >= 0) { "Missing row $key on ${state.currentScreen}" }
        return state.copy(
            screenStack = state.screenStack.dropLast(1) + state.currentEntry.copy(selectedIndex = index)
        )
    }

    private fun bluetoothState(
        devices: List<BluetoothDeviceEntry>,
        stack: List<ScreenEntry> = listOf(ScreenEntry(Screen.Bluetooth))
    ) = AppState(
        screenStack = stack,
        bluetooth = BluetoothUiState(adapterMode = BluetoothAdapterMode.ON, devices = devices)
    )

    private val pairedDevice = BluetoothDeviceEntry(
        address = "AA:BB:CC:DD:EE:FF",
        name = "Headphones",
        bonded = true,
        bonding = false
    )

    // ---- unwind to Now Playing -------------------------------------------------

    @Test fun `playing from a list pushes Now Playing once`() {
        val songs = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(Screen.Music), ScreenEntry(Screen.Songs)),
            library = library
        )
        val result = AppReducer.reduce(songs, AppAction.Confirm).state
        assertEquals(Screen.NowPlaying, result.currentScreen)
        assertEquals(4, result.screenStack.size)
        assertEquals(1, result.screenStack.count { it.screen == Screen.NowPlaying })
    }

    @Test fun `queue selection unwinds to the existing Now Playing instead of stacking a second one`() {
        val stack = listOf(
            ScreenEntry(Screen.MainMenu),
            ScreenEntry(Screen.NowPlaying),
            ScreenEntry(Screen.NowPlayingOptions),
            ScreenEntry(Screen.Queue, selectedIndex = 1)
        )
        val state = AppState(screenStack = stack, library = library, playback = playing)

        val options = AppReducer.reduce(state, AppAction.Confirm).state
        assertTrue(options.currentScreen is Screen.QueueOptions)
        val result = AppReducer.reduce(options, AppAction.Confirm).state

        assertEquals(Screen.NowPlaying, result.currentScreen)
        assertEquals("must unwind, not push", 2, result.screenStack.size)
        assertEquals(1, result.screenStack.count { it.screen == Screen.NowPlaying })
    }

    @Test fun `one Back from an unwound Now Playing reaches the main menu`() {
        val stack = listOf(
            ScreenEntry(Screen.MainMenu),
            ScreenEntry(Screen.NowPlaying),
            ScreenEntry(Screen.NowPlayingOptions),
            ScreenEntry(Screen.Queue, selectedIndex = 1)
        )
        val state = AppState(screenStack = stack, library = library, playback = playing)
        val options = AppReducer.reduce(state, AppAction.Confirm).state
        val nowPlaying = AppReducer.reduce(options, AppAction.Confirm).state

        val back = AppReducer.reduce(nowPlaying, AppAction.Back).state

        assertEquals(Screen.MainMenu, back.currentScreen)
    }

    @Test fun `long Play never stacks a second Now Playing`() {
        var state: AppState = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(Screen.NowPlaying)),
            library = library,
            playback = playing
        )
        repeat(5) { state = AppReducer.reduce(state, AppAction.ShowNowPlaying).state }
        assertEquals(2, state.screenStack.size)
        assertEquals(1, state.screenStack.count { it.screen == Screen.NowPlaying })
    }

    @Test fun `short Confirm on Now Playing only keeps the display active`() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(Screen.NowPlaying)),
            library = library,
            playback = playing
        )

        val result = AppReducer.reduce(state, AppAction.Confirm)

        assertEquals(state, result.state)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun `media Play Pause still controls playback from Now Playing`() {
        val state = AppState(
            screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(Screen.NowPlaying)),
            library = library,
            playback = playing
        )

        val result = AppReducer.reduce(state, AppAction.PlayPause)

        assertEquals(AppEffect.TogglePlayback, result.effects.single())
    }

    @Test fun `shuffle all from the main menu opens Now Playing once`() {
        val state = selectKey(AppState(library = library), "shuffle_all")
        val result = AppReducer.reduce(state, AppAction.Confirm)
        assertEquals(Screen.NowPlaying, result.state.currentScreen)
        assertEquals(1, result.state.screenStack.count { it.screen == Screen.NowPlaying })
        assertTrue(result.effects.contains(AppEffect.ShuffleAll))
    }

    // ---- contextual options stay on long center --------------------------------

    @Test fun `long center on a Bluetooth device opens Device Options and forgets nothing`() {
        val state = selectKey(bluetoothState(listOf(pairedDevice)), "bt_device:${pairedDevice.address}")

        val result = AppReducer.reduce(state, AppAction.ConfirmLong)

        assertEquals(Screen.BluetoothDevice(pairedDevice.address), result.state.currentScreen)
        assertTrue("long center must not act on the device", result.effects.isEmpty())
    }

    @Test fun `long Confirm on a Bluetooth device forgets nothing`() {
        val state = selectKey(bluetoothState(listOf(pairedDevice)), "bt_device:${pairedDevice.address}")

        val result = AppReducer.reduce(state, AppAction.ConfirmLong)

        assertEquals(Screen.BluetoothDevice(pairedDevice.address), result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun `no reachable single action forgets a Bluetooth device`() {
        val list = bluetoothState(listOf(pairedDevice))
        val onDevice = selectKey(list, "bt_device:${pairedDevice.address}")
        val actions = listOf(AppAction.Confirm, AppAction.ConfirmLong, AppAction.Right, AppAction.Left, AppAction.Back)

        actions.forEach { action ->
            val effects = AppReducer.reduce(onDevice, action).effects
            assertFalse(
                "$action forgot the device from the list screen",
                effects.any { it is AppEffect.ForgetBluetoothDevice }
            )
        }
    }

    @Test fun `Device Options connects a paired device`() {
        val stack = listOf(ScreenEntry(Screen.Bluetooth), ScreenEntry(Screen.BluetoothDevice(pairedDevice.address)))
        val state = selectKey(
            bluetoothState(listOf(pairedDevice), stack),
            "bt_device_activate:${pairedDevice.address}"
        )

        val result = AppReducer.reduce(state, AppAction.Confirm)

        assertEquals(AppEffect.ActivateBluetoothDevice(pairedDevice.address), result.effects.single())
        assertEquals(Screen.Bluetooth, result.state.currentScreen)
    }

    // ---- destructive confirmation ---------------------------------------------

    @Test fun `Forget Device opens a confirmation with Cancel preselected`() {
        val stack = listOf(ScreenEntry(Screen.Bluetooth), ScreenEntry(Screen.BluetoothDevice(pairedDevice.address)))
        val state = selectKey(
            bluetoothState(listOf(pairedDevice), stack),
            "bt_device_forget:${pairedDevice.address}"
        )

        val result = AppReducer.reduce(state, AppAction.Confirm)

        assertEquals(
            Screen.ConfirmAction(ConfirmPrompts.FORGET_DEVICE + pairedDevice.address),
            result.state.currentScreen
        )
        assertTrue("confirmation must not act", result.effects.isEmpty())
        val selectedRow = ScreenContent.rows(result.state)[result.state.selectedIndex] as ScreenRow.Action
        assertEquals(ScreenContent.CONFIRM_CANCEL_KEY, selectedRow.key)
    }

    @Test fun `cancelling the confirmation returns to Device Options without acting`() {
        val stack = listOf(
            ScreenEntry(Screen.Bluetooth),
            ScreenEntry(Screen.BluetoothDevice(pairedDevice.address)),
            ScreenEntry(Screen.ConfirmAction(ConfirmPrompts.FORGET_DEVICE + pairedDevice.address))
        )
        val state = selectKey(bluetoothState(listOf(pairedDevice), stack), ScreenContent.CONFIRM_CANCEL_KEY)

        val result = AppReducer.reduce(state, AppAction.Confirm)

        assertEquals(Screen.BluetoothDevice(pairedDevice.address), result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun `confirming forgets the device and returns to the Bluetooth list`() {
        val stack = listOf(
            ScreenEntry(Screen.Bluetooth),
            ScreenEntry(Screen.BluetoothDevice(pairedDevice.address)),
            ScreenEntry(Screen.ConfirmAction(ConfirmPrompts.FORGET_DEVICE + pairedDevice.address))
        )
        val state = selectKey(bluetoothState(listOf(pairedDevice), stack), ScreenContent.CONFIRM_OK_KEY)

        val result = AppReducer.reduce(state, AppAction.Confirm)

        assertEquals(AppEffect.ForgetBluetoothDevice(pairedDevice.address), result.effects.single())
        assertEquals(Screen.Bluetooth, result.state.currentScreen)
    }

    @Test fun `Back from the confirmation acts as cancel`() {
        val stack = listOf(
            ScreenEntry(Screen.Bluetooth),
            ScreenEntry(Screen.BluetoothDevice(pairedDevice.address)),
            ScreenEntry(Screen.ConfirmAction(ConfirmPrompts.FORGET_DEVICE + pairedDevice.address))
        )
        val state = bluetoothState(listOf(pairedDevice), stack)

        val result = AppReducer.reduce(state, AppAction.Back)

        assertEquals(Screen.BluetoothDevice(pairedDevice.address), result.state.currentScreen)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun `the confirmation names the device it will forget`() {
        val state = bluetoothState(
            listOf(pairedDevice),
            listOf(ScreenEntry(Screen.ConfirmAction(ConfirmPrompts.FORGET_DEVICE + pairedDevice.address)))
        )
        val prompt = ScreenContent.rows(state).first() as ScreenRow.Group
        assertTrue(prompt.title.contains("Headphones"))
    }

    @Test fun `Right skips tracks without acting on the confirmation screen`() {
        val stack = listOf(
            ScreenEntry(Screen.Bluetooth),
            ScreenEntry(Screen.BluetoothDevice(pairedDevice.address)),
            ScreenEntry(Screen.ConfirmAction(ConfirmPrompts.FORGET_DEVICE + pairedDevice.address))
        )
        listOf(ScreenContent.CONFIRM_CANCEL_KEY, ScreenContent.CONFIRM_OK_KEY).forEach { key ->
            val state = selectKey(bluetoothState(listOf(pairedDevice), stack), key)
            val result = AppReducer.reduce(state, AppAction.Right)
            assertEquals("Right moved from $key", state.screenStack, result.state.screenStack)
            assertEquals(AppEffect.NextTrack, result.effects.single())
        }
    }

    @Test fun `a device that disappears while Device Options is open does not crash`() {
        val stack = listOf(ScreenEntry(Screen.Bluetooth), ScreenEntry(Screen.BluetoothDevice(pairedDevice.address)))
        val state = bluetoothState(emptyList(), stack)
        val rows = ScreenContent.rows(state)
        assertEquals(1, rows.size)
        assertTrue(rows.first() is ScreenRow.Group)
        assertTrue(AppReducer.reduce(state, AppAction.Confirm).effects.isEmpty())
    }
}
