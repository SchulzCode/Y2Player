package com.schulzcode.y2player.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemHapticsArchitectureTest {
    @Test fun platformHapticsAreSuppressedAtStartupResumeAndBootCompleted() {
        val root = repositoryRoot()
        assertContains(
            File(root, "app/src/main/kotlin/com/schulzcode/y2player/Y2Application.kt"),
            "SystemHapticsController(this).suppress()"
        )
        assertContains(
            File(root, "app/src/main/kotlin/com/schulzcode/y2player/ui/MainActivity.kt"),
            "systemHapticsController.suppress()"
        )
        assertContains(
            File(root, "app/src/main/kotlin/com/schulzcode/y2player/settings/BootSettingsReceiver.kt"),
            "SystemHapticsController(context).suppress()"
        )
    }

    private fun assertContains(file: File, expected: String) {
        assertTrue("${file.path} must contain $expected", file.readText().contains(expected))
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
