package com.schulzcode.y2player.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EventLogTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun readAll(directory: File): String =
        directory.listFiles()
            ?.filter { it.name.startsWith("events") }
            ?.sortedBy { it.name }
            ?.joinToString("\n") { it.readText() }
            ?: ""

    private fun newLog(
        internal: File,
        mirror: () -> File? = { null },
        buildId: String = "test-build"
    ) = EventLog(
        primaryDirectory = internal,
        mirrorProvider = mirror,
        appVersion = "1.0",
        buildId = buildId
    )

    @Test fun writesToInternalStorageWithNoCardPresent() {
        val internal = temporaryFolder.newFolder("internal")
        val log = newLog(internal)

        log.warn(Sub.STORAGE, Ev.STORAGE_BROADCAST, "action" to "media_removed")
        log.flush()

        val text = readAll(internal)
        assertTrue("event reached internal storage", text.contains("storage_broadcast"))
        assertTrue("field survived", text.contains("media_removed"))
    }

    @Test fun internalStorageIsPrimaryAndDoesNotDependOnTheMirror() {
        val internal = temporaryFolder.newFolder("internal")
        val log = newLog(internal, mirror = { null })

        log.warn(Sub.STORAGE, Ev.SCAN_ERROR, "code" to "unmount-failed")
        log.flush()

        assertTrue(
            "logging continues with no card at all",
            readAll(internal).contains("unmount-failed")
        )
    }

    @Test fun mirrorsToTheCardWhenItIsAvailable() {
        val internal = temporaryFolder.newFolder("internal")
        val card = temporaryFolder.newFolder("card")
        val log = newLog(internal, mirror = { card })

        log.warn(Sub.APP, Ev.CRASH, "type" to "IllegalStateException")
        log.flush()

        assertTrue("internal copy written", readAll(internal).contains("IllegalStateException"))
        assertTrue("card copy written", readAll(card).contains("IllegalStateException"))
    }

    @Test fun losingTheCardMidSessionDoesNotStopInternalLogging() {
        val internal = temporaryFolder.newFolder("internal")
        val card = temporaryFolder.newFolder("card")
        var cardAvailable = true
        val log = newLog(internal, mirror = { if (cardAvailable) card else null })

        log.warn(Sub.STORAGE, Ev.STORAGE_BROADCAST, "stage" to "before_unmount")
        log.flush()

        cardAvailable = false
        log.warn(Sub.STORAGE, Ev.STORAGE_BROADCAST, "stage" to "while_absent")
        log.flush()

        val internalText = readAll(internal)
        assertTrue("pre-unmount event kept", internalText.contains("before_unmount"))
        assertTrue(
            "events during the export still reach internal storage",
            internalText.contains("while_absent")
        )
        assertFalse(
            "nothing was written to the card while it was unmounted",
            readAll(card).contains("while_absent")
        )
    }

    @Test fun mirroringResumesWhenTheCardComesBack() {
        val internal = temporaryFolder.newFolder("internal")
        val card = temporaryFolder.newFolder("card")
        var cardAvailable = false
        val log = newLog(internal, mirror = { if (cardAvailable) card else null })

        log.warn(Sub.STORAGE, Ev.STORAGE_BROADCAST, "stage" to "while_absent")
        log.flush()

        cardAvailable = true
        log.warn(Sub.STORAGE, Ev.STORAGE_VOLUME_CHANGE, "stage" to "after_remount")
        log.flush()

        val cardText = readAll(card)
        assertTrue("mirroring resumed", cardText.contains("after_remount"))
        assertFalse("no unbounded backlog is replayed", cardText.contains("while_absent"))
        assertTrue("internal log is complete", readAll(internal).contains("while_absent"))
    }

    @Test fun everyEventCarriesTheBuildId() {
        val internal = temporaryFolder.newFolder("internal")
        val log = newLog(internal, buildId = "20260721T101500Z-abc123def456")

        log.warn(Sub.APP, Ev.APP_START)
        log.flush()

        assertTrue(
            "build id is present so a log can be traced to one artifact set",
            readAll(internal).contains("20260721T101500Z-abc123def456")
        )
    }

    @Test fun verboseToggleSuppressesOnlyDebugEvents() {
        val internal = temporaryFolder.newFolder("internal")
        val log = newLog(internal)
        log.setEnabled(false)

        log.debug(Sub.REDUCER, Ev.ACTION, "marker" to "debug_event")
        log.info(Sub.STORAGE, Ev.STORAGE_VOLUME_CHANGE, "marker" to "info_event")
        log.warn(Sub.SCANNER, Ev.SCAN_ERROR, "marker" to "warn_event")
        log.flush()

        val text = readAll(internal)
        assertFalse("debug is suppressed when verbose is off", text.contains("debug_event"))
        assertTrue("info survives a disabled verbose toggle", text.contains("info_event"))
        assertTrue("warn survives a disabled verbose toggle", text.contains("warn_event"))
    }

    @Test fun rotationBoundsTotalDiskUsage() {
        val internal = temporaryFolder.newFolder("internal")
        val log = newLog(internal)
        val padding = "x".repeat(400)

        val block = EventLog.QUEUE_CAPACITY / 2
        var written = 0
        repeat(12) {
            repeat(block) { log.warn(Sub.DIAG, Ev.ACTION, "index" to written++, "pad" to padding) }
            log.flush()
        }

        val files = internal.listFiles().orEmpty().filter { it.name.startsWith("events") }
        assertTrue("rotation produced backup files", files.size > 1)
        assertTrue(
            "no more files than the configured backup count plus the active one",
            files.size <= EventLog.BACKUP_COUNT + 1
        )
        val total = files.sumOf { it.length() }
        val ceiling = EventLog.MAX_FILE_BYTES * (EventLog.BACKUP_COUNT + 1)
        assertTrue("total size $total exceeds the $ceiling ceiling", total <= ceiling)
    }

    @Test fun anUnwritableDestinationDoesNotCrashTheCaller() {
        val blocker = temporaryFolder.newFile("not-a-directory")
        val log = newLog(File(blocker, "logs"))

        log.warn(Sub.APP, Ev.CRASH, "type" to "anything")
        log.info(Sub.APP, Ev.APP_START)
        log.flush()

        assertTrue("logging to an unwritable destination is survivable", true)
    }

    @Test fun crashFlushWritesSynchronouslyWithoutTheWriterThread() {
        val internal = temporaryFolder.newFolder("internal")
        val log = newLog(internal)

        log.crashFlush(IllegalStateException("boom"))

        val text = readAll(internal)
        assertTrue("the crash event was written", text.contains("crash"))
        assertTrue("the exception type is recorded", text.contains("IllegalStateException"))
        assertTrue("a bounded message is recorded", text.contains("boom"))
    }

    @Test fun droppedEventsAreCountedRatherThanSilentlyLost() {
        val internal = temporaryFolder.newFolder("internal")
        val log = newLog(internal)

        val burst = EventLog.QUEUE_CAPACITY * 4
        repeat(burst) { index ->
            log.warn(Sub.DIAG, Ev.ACTION, "index" to index)
        }

        log.warn(Sub.DIAG, Ev.CRASH, "marker" to "sentinel")

        var text = ""
        for (attempt in 0 until 100) {
            log.flush()
            text = readAll(internal)
            if (text.contains("sentinel")) break
            Thread.sleep(20)
        }

        assertTrue("something was written", text.isNotEmpty())
        assertTrue("the sentinel was recorded", text.contains("sentinel"))
        val lines = text.lines().count { it.contains("\"ev\":\"action\"") }
        assertTrue(
            "either every event survived or the gap is recorded",
            lines == burst || text.contains("\"dropped\":")
        )
    }

    @Test fun logFilesAreDiscoverableForExport() {
        val internal = temporaryFolder.newFolder("internal")
        val log = newLog(internal)

        log.warn(Sub.APP, Ev.APP_START)
        log.flush()

        val files = log.logFiles()
        assertTrue("the active log is discoverable", files.isNotEmpty())
        assertEquals(EventLog.ACTIVE_NAME, files.last().name)
    }
}
