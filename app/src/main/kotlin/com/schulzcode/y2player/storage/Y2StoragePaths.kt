package com.schulzcode.y2player.storage

import android.os.Environment
import android.os.SystemClock
import java.io.File

data class StorageRoot(val id: String, val directory: File)

object Y2StoragePaths {
    private val candidates = mapOf(
        "internal" to listOf("/storage/sdcard0", "/mnt/sdcard", "/storage/emulated/0"),
        "sdcard" to listOf("/storage/sdcard1", "/mnt/sdcard2", "/mnt/extSdCard", "/storage/extSdCard")
    )

    val roots: List<StorageRoot>
        get() = listOf(selectRoot("internal"), selectRoot("sdcard"))

    @Volatile private var mountsReadAt = Long.MIN_VALUE
    @Volatile private var mountedPaths: Set<String> = emptySet()

    fun availableRoots(): List<StorageRoot> = roots.filter(::isAvailable)

    fun isAvailable(root: StorageRoot): Boolean {
        val directory = root.directory
        if (!directory.exists() || !directory.isDirectory || !directory.canRead()) return false
        val mounted = currentMounts().any { path ->
            directory.absolutePath == path || directory.absolutePath.startsWith("$path/") || path.startsWith("${directory.absolutePath}/")
        }
        if (mounted) return true
        if (root.id == "internal") {
            val state = Environment.getExternalStorageState()
            if (state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY) return true
        }
        // Some MTK builds bind-mount storage without exposing the final alias in /proc/mounts.
        return runCatching { directory.listFiles() != null }.getOrDefault(false)
    }

    fun resolveReadablePath(volumeId: String, relativePath: String, previousAbsolutePath: String): String? {
        File(previousAbsolutePath).takeIf { it.isFile && it.canRead() }?.let { return it.absolutePath }
        val safeRelative = relativePath.replace('\\', '/').trimStart('/')
        if (safeRelative.isEmpty() || safeRelative.contains("../")) return null
        return candidates[volumeId].orEmpty().asSequence()
            .map { root -> File(root, safeRelative) }
            .firstOrNull { it.isFile && it.canRead() }
            ?.absolutePath
    }

    private fun selectRoot(id: String): StorageRoot {
        val paths = candidates[id].orEmpty()
        val selected = paths.asSequence().map(::File).firstOrNull { directory ->
            directory.exists() && directory.isDirectory && directory.canRead()
        } ?: File(paths.first())
        return StorageRoot(id, selected)
    }

    private fun currentMounts(): Set<String> {
        val now = SystemClock.uptimeMillis()
        if (now - mountsReadAt <= MOUNT_CACHE_MS) return mountedPaths
        synchronized(this) {
            if (now - mountsReadAt <= MOUNT_CACHE_MS) return mountedPaths
            mountedPaths = runCatching {
                File("/proc/mounts").useLines { lines ->
                    lines.mapNotNull { line ->
                        line.split(' ').getOrNull(1)?.replace("\\040", " ")?.takeIf { it.startsWith('/') }
                    }.toSet()
                }
            }.getOrDefault(emptySet())
            mountsReadAt = now
            return mountedPaths
        }
    }

    private const val MOUNT_CACHE_MS = 500L
}
