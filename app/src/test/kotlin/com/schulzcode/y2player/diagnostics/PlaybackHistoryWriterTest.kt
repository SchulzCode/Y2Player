package com.schulzcode.y2player.diagnostics

import com.schulzcode.y2player.core.model.PlaybackExitReason
import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlaybackHistoryWriterTest {
    @get:Rule val temporary = TemporaryFolder()

    private var directory: File? = null

    private val warnings = mutableListOf<String>()

    private fun history() = PlaybackHistory(
        directoryProvider = { directory },
        appVersion = "2.1.1",
        onWarning = { warnings += it }
    )

    private fun session(title: String = "Song", listenedMs: Long = 180_000) = PlaybackSession(
        track = Track(
            id = 1,
            volumeId = "sdcard",
            absolutePath = "/storage/sdcard1/Music/$title.flac",
            relativePath = "Music/$title.flac",
            title = title,
            artist = "Artist",
            album = "Album",
            albumArtist = null,
            trackNumber = 1,
            discNumber = null,
            durationMs = 240_000,
            fileSize = 1,
            modifiedAt = 0
        ),
        startedAtUtcMs = 1_700_000_000_000,
        endedAtUptimeMs = 1_000,
        startPositionMs = 0,
        endPositionMs = listenedMs,
        listenedMs = listenedMs,
        exitReason = PlaybackExitReason.COMPLETED,
        shuffleEnabled = false,
        repeatMode = RepeatMode.OFF
    )

    private fun activeFile() = File(directory, PlaybackHistory.ACTIVE_NAME)
    private fun backupFile() = File(directory, PlaybackHistory.BACKUP_NAME)
    private fun lines() = activeFile().takeIf { it.isFile }?.readLines().orEmpty()

    @Test
    fun `appends one line per session and creates the directory`() {
        directory = File(temporary.root, "Y2Player")
        val history = history()

        assertTrue(history.append(session("One")))
        assertTrue(history.append(session("Two")))

        assertEquals(2, lines().size)
        assertTrue(lines()[0].contains("\"title\":\"One\""))
        assertTrue(lines()[1].contains("\"title\":\"Two\""))
    }

    @Test
    fun `appends rather than truncating across writer instances`() {
        directory = temporary.newFolder("Y2Player")
        history().append(session("Before"))
        history().append(session("After"))

        assertEquals(2, lines().size)
        assertTrue(lines()[0].contains("Before"))
        assertTrue(lines()[1].contains("After"))
    }

    @Test
    fun `history resumes by itself once storage comes back`() {
        directory = null
        val history = history()

        repeat(10) { assertFalse("no storage, so nothing is written", history.append(session())) }

        directory = temporary.newFolder("Y2Player")
        assertTrue("writing must be attempted again once storage returns", history.append(session("Back")))
        assertEquals(1, lines().size)
        assertTrue(lines().single().contains("Back"))
    }

    @Test
    fun `a missing directory never throws and never blocks`() {
        directory = null
        assertFalse(history().append(session()))
        assertEquals(PlaybackHistory.Summary(), history().summary())
        assertFalse(history().clear())
    }

    @Test
    fun `rotates by renaming once the cap is passed`() {
        directory = temporary.newFolder("Y2Player")
        val history = history()
        activeFile().writeText("x".repeat((PlaybackHistory.MAX_ACTIVE_BYTES + 1).toInt()))

        history.append(session("AfterRotation"))

        assertTrue("previous generation is kept", backupFile().isFile)
        assertEquals("active file restarts", 1, lines().size)
        assertTrue(lines().single().contains("AfterRotation"))
    }

    @Test
    fun `only one rotated generation is ever kept`() {
        directory = temporary.newFolder("Y2Player")
        val history = history()
        backupFile().writeText("older generation\n")
        activeFile().writeText("x".repeat((PlaybackHistory.MAX_ACTIVE_BYTES + 1).toInt()))

        history.append(session())

        assertFalse("the older generation is replaced, not accumulated", backupFile().readText().contains("older generation"))
        assertEquals(2, directory!!.listFiles()!!.size)
    }

    @Test
    fun `summary counts sessions across both files and ignores blank lines`() {
        directory = temporary.newFolder("Y2Player")
        activeFile().writeText("{\"a\":1}\n\n{\"a\":2}\n")
        backupFile().writeText("{\"a\":0}\n")

        val summary = history().summary()

        assertEquals(3, summary.sessions)
        assertEquals(activeFile().length() + backupFile().length(), summary.bytes)
    }

    @Test
    fun `summary tolerates a truncated final line`() {
        directory = temporary.newFolder("Y2Player")
        activeFile().writeText("{\"a\":1}\n{\"a\":2}\n{\"trunca")

        assertEquals(3, history().summary().sessions)
    }

    @Test
    fun `summary of an empty directory is zero rather than an error`() {
        directory = temporary.newFolder("Y2Player")
        assertEquals(PlaybackHistory.Summary(0, 0), history().summary())
    }

    @Test
    fun `clear removes both files and reports whether anything went`() {
        directory = temporary.newFolder("Y2Player")
        val history = history()
        history.append(session())
        backupFile().writeText("old\n")

        assertTrue(history.clear())
        assertFalse(activeFile().exists())
        assertFalse(backupFile().exists())
        assertFalse("nothing left to clear", history.clear())
    }

    @Test
    fun `appending after a clear starts a fresh file`() {
        directory = temporary.newFolder("Y2Player")
        val history = history()
        history.append(session("Before"))
        history.clear()
        history.append(session("After"))

        assertEquals(1, lines().size)
        assertTrue(lines().single().contains("After"))
    }

    @Test
    fun `an unusable directory is handled quietly rather than as a fault`() {
        directory = temporary.newFile("not-a-directory")
        val history = history()

        assertFalse(history.append(session()))
        assertTrue("an absent or unusable card is not a fault worth logging", warnings.isEmpty())
    }

    @Test
    fun `an unusable directory does not permanently disable history`() {
        directory = temporary.newFile("not-a-directory")
        val history = history()
        repeat(10) { history.append(session()) }

        directory = temporary.newFolder("Y2Player")
        assertTrue(history.append(session("Recovered")))
        assertTrue(lines().single().contains("Recovered"))
    }
}
