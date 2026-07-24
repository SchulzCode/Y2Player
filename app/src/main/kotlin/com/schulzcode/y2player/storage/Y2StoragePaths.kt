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

    /**
     * The two volume roots, re-probed at most once per [ROOT_CACHE_MS].
     *
     * Selecting a root stats every candidate path, and this property is read on
     * paths that repeat: the device snapshot builds two volumes from it, and the
     * event log re-resolves its card mirror on every write batch. Caching for
     * the same short window already used for `/proc/mounts` keeps a mount
     * transition visible within half a second while removing the repeated
     * syscalls between them.
     */
    val roots: List<StorageRoot>
        get() {
            val now = SystemClock.uptimeMillis()
            val cached = cachedRoots
            if (cached != null && now - rootsReadAt <= ROOT_CACHE_MS) return cached
            return synchronized(this) {
                val current = cachedRoots
                if (current != null && now - rootsReadAt <= ROOT_CACHE_MS) current
                else listOf(selectRoot("internal"), selectRoot("sdcard")).also {
                    cachedRoots = it
                    rootsReadAt = now
                }
            }
        }

    @Volatile private var mountsReadAt = Long.MIN_VALUE
    @Volatile private var mountedPaths: Set<String> = emptySet()
    @Volatile private var rootsReadAt = Long.MIN_VALUE
    @Volatile private var cachedRoots: List<StorageRoot>? = null
    private var mountedVolumesReadAt = Long.MIN_VALUE
    private val mountedVolumes = HashMap<String, Boolean>()

    fun availableRoots(): List<StorageRoot> = roots.filter(::isAvailable)

    /**
     * Whether the volume behind a stored track is mounted *now*.
     *
     * Playback asks this instead of trusting `Track.available`, which only
     * records what the last scan concluded and can outlive reality — most
     * visibly after a boot where the card mounted later than the startup grace
     * period.
     *
     * Memoised for the same short window as [roots], because the caller that
     * matters is the skip-to-next-playable loop: with a removed card that walks
     * the whole queue, and an uncached answer would stat the volume directory
     * once per item.
     */
    @Synchronized
    fun isVolumeMounted(volumeId: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - mountedVolumesReadAt > ROOT_CACHE_MS) {
            mountedVolumes.clear()
            mountedVolumesReadAt = now
        }
        mountedVolumes[volumeId]?.let { return it }
        // Reentrant: roots and currentMounts take this same monitor.
        val mounted = roots.firstOrNull { it.id == volumeId }?.let(::isAvailable) == true
        mountedVolumes[volumeId] = mounted
        return mounted
    }

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
    private const val ROOT_CACHE_MS = 500L
}
