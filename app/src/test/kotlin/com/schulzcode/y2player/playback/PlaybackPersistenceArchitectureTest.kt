package com.schulzcode.y2player.playback

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPersistenceArchitectureTest {
    @Test fun persistenceNeverSilentlyEvictsQueuedWrites() {
        val source = File(
            repositoryRoot(),
            "app/src/main/kotlin/com/schulzcode/y2player/playback/PlaybackService.kt"
        ).readText()

        assertTrue(source.contains("LinkedBlockingQueue()"))
        assertFalse(source.contains("DiscardOldestPolicy"))
        assertFalse(source.contains("persistenceExecutor.queue.clear()"))
    }

    private fun repositoryRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "app/src/main/AndroidManifest.xml").isFile) return directory
            directory = directory.parentFile
        }
        throw AssertionError("repository root not found")
    }
}
