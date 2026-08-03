package com.schulzcode.y2player.core.state

import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.ui.Y2Icon
import com.schulzcode.y2player.ui.Y2RowIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScreenCatalogueTest {

    @Before fun clearRowCache() {
        ScreenContent.clearCachedRows()
    }

    private fun bare(screen: Screen) = AppState(screenStack = listOf(ScreenEntry(screen)))

    @Test fun `every declared screen is covered by the catalogue`() {
        assertEquals(
            "a new Screen was added without covering it in ScreenCatalogue",
            emptyList<String>(),
            ScreenCatalogue.missingFromCatalogue()
        )
    }

    @Test fun `the catalogue is not empty and matches the hierarchy size`() {
        val declared = ScreenCatalogue.declaredSubtypes().size
        assertTrue("sealedSubclasses returned nothing; the catalogue would be vacuous", declared > 0)
        assertEquals(declared, ScreenCatalogue.all().size)
    }

    @Test fun `every screen has a title`() {
        ScreenCatalogue.all().forEach { screen ->
            assertTrue("${screen.code} has a blank title", ScreenContent.title(bare(screen)).isNotBlank())
        }
    }

    @Test fun `every screen code is unique`() {
        val codes = ScreenCatalogue.all().map { it.code }
        assertEquals("screen codes must be unique", codes.size, codes.toSet().size)
    }

    @Test fun `every non content screen builds rows on a bare device`() {
        ScreenCatalogue.all()
            .filterNot { it.code in ScreenCatalogue.rowless || it.code in ScreenCatalogue.contentScreens }
            .forEach { screen ->
                assertTrue(
                    "${screen.code} produced no rows; its builder may have been lost",
                    ScreenContent.rows(bare(screen)).isNotEmpty()
                )
            }
    }

    @Test fun `every row key on every screen resolves to a specific icon`() {
        ScreenCatalogue.all().forEach { screen ->
            ScreenContent.rows(bare(screen)).forEach { row ->
                if (row is ScreenRow.Action) {
                    assertTrue(
                        "${screen.code} row ${row.key} falls back to the generic icon",
                        Y2RowIcons.forActionKey(row.key) != Y2Icon.ACTION
                    )
                }
            }
        }
    }

    @Test fun `no row anywhere has a blank title`() {
        ScreenCatalogue.all().forEach { screen ->
            ScreenContent.rows(bare(screen)).forEach { row ->
                assertTrue("${screen.code} has a blank row title", row.title.isNotBlank())
            }
        }
    }

    @Test fun `no screen has duplicate row keys`() {
        ScreenCatalogue.all().forEach { screen ->
            val keys = ScreenContent.rows(bare(screen)).mapNotNull {
                (it as? ScreenRow.Action)?.key ?: (it as? ScreenRow.Group)?.key
            }
            assertEquals("${screen.code} emits a duplicate row key", keys.size, keys.toSet().size)
        }
    }

    @Test fun `Confirm on every row of every screen is safe`() {
        ScreenCatalogue.all().forEach { screen ->
            val state = bare(screen).copy(library = LibraryState())
            ScreenContent.rows(state).indices.forEach { index ->
                val selected = state.copy(
                    screenStack = listOf(ScreenEntry(screen, index))
                )
                val result = AppReducer.reduce(selected, AppAction.Confirm)
                assertTrue(
                    "${screen.code} row $index left the selection negative",
                    result.state.selectedIndex >= 0
                )
                assertTrue(
                    "${screen.code} row $index grew the stack without bound",
                    result.state.screenStack.size <= selected.screenStack.size + 1
                )
            }
        }
    }

    @Test fun `Back from every screen never lands nowhere`() {
        ScreenCatalogue.all().forEach { screen ->
            val nested = AppState(screenStack = listOf(ScreenEntry(Screen.MainMenu), ScreenEntry(screen)))
            val back = AppReducer.reduce(nested, AppAction.Back).state
            assertTrue("${screen.code} left an empty stack", back.screenStack.isNotEmpty())
            assertTrue(
                "${screen.code} did not move up",
                back.screenStack.size < nested.screenStack.size || back.currentScreen == screen
            )
        }
    }

    @Test fun `Left and Right are media controls on every screen`() {
        ScreenCatalogue.all().forEach { screen ->
            val state = bare(screen).copy(library = LibraryState())
            val right = AppReducer.reduce(state, AppAction.Right)
            val left = AppReducer.reduce(state, AppAction.Left)
            assertEquals("${screen.code}: Right navigated", state.screenStack, right.state.screenStack)
            assertEquals("${screen.code}: Right did not skip", AppEffect.NextTrack, right.effects.single())
            assertEquals("${screen.code}: Left navigated", state.screenStack, left.state.screenStack)
            assertEquals("${screen.code}: Left did not skip", AppEffect.PreviousTrack, left.effects.single())
        }
    }

    @Test fun `no reachable action mutates without landing somewhere sensible`() {
        val destructive = ScreenCatalogue.all().flatMap { screen ->
            val state = bare(screen).copy(library = LibraryState())
            ScreenContent.rows(state).indices.map { index ->
                screen to AppReducer.reduce(
                    state.copy(screenStack = listOf(ScreenEntry(screen, index))),
                    AppAction.Confirm
                )
            }
        }.filter { (_, result) ->
            result.effects.any {
                it == AppEffect.ResetLibrary || it == AppEffect.ClearQueue ||
                    it is AppEffect.ForgetBluetoothDevice || it is AppEffect.ClearAudiobookProgress ||
                    it is AppEffect.DeletePlaylist || it == AppEffect.ClearPlaybackHistory
            }
        }
        destructive.forEach { (screen, _) ->
            assertTrue(
                "${screen.code} performs a destructive action directly; it must confirm first",
                screen is Screen.ConfirmAction
            )
        }
    }
}
