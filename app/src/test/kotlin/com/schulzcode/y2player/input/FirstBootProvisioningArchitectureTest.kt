package com.schulzcode.y2player.input

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class FirstBootProvisioningArchitectureTest {
    @Test fun stockProvisionActivityCanOutrankTheLauncherOnFirstBoot() {
        val manifest = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(
            File(repositoryRoot(), "app/src/main/AndroidManifest.xml")
        )

        val activities = manifest.getElementsByTagName("activity")
        val launcher = (0 until activities.length)
            .map { activities.item(it) as Element }
            .first { it.androidAttribute("name") == ".ui.MainActivity" }
        val filters = launcher.getElementsByTagName("intent-filter")
        val homeFilter = (0 until filters.length)
            .map { filters.item(it) as Element }
            .first { filter ->
                val categories = filter.getElementsByTagName("category")
                (0 until categories.length)
                    .map { categories.item(it) as Element }
                    .any { it.androidAttribute("name") == "android.intent.category.HOME" }
            }

        val priority = homeFilter.androidAttribute("priority").toIntOrNull() ?: 0
        assertTrue(
            "Y2Player HOME must stay below stock Provision.apk priority 1",
            priority < STOCK_PROVISION_HOME_PRIORITY
        )
    }

    private fun Element.androidAttribute(name: String): String =
        getAttributeNS(ANDROID_NAMESPACE, name)

    private fun repositoryRoot(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, "app/src/main/AndroidManifest.xml").isFile) return directory
            directory = directory.parentFile
        }
        throw AssertionError("repository root not found")
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val STOCK_PROVISION_HOME_PRIORITY = 1
    }
}
