package com.schulzcode.y2player.library

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.schulzcode.y2player.BuildConfig
import com.schulzcode.y2player.core.model.LibraryIndex
import com.schulzcode.y2player.core.model.LibraryScanProgress
import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaylistSummary
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.diagnostics.DiagnosticLogger
import com.schulzcode.y2player.diagnostics.Ev
import com.schulzcode.y2player.diagnostics.EventLog
import com.schulzcode.y2player.diagnostics.Sub
import com.schulzcode.y2player.playback.NativeAudio
import com.schulzcode.y2player.storage.Y2StoragePaths
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LibraryRepository(
    appContext: Context,
    private val database: LibraryDatabase,
    private val scanner: LibraryScanner = LibraryScanner(),
    private val logger: DiagnosticLogger,
    private val eventLog: EventLog? = null
) {
    fun interface Listener { fun onLibraryChanged(state: LibraryState) }

    private val stateExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            try {
                runnable.run()
            } catch (error: Throwable) {
                logger.error("Library", "background database operation failed", error)
            }
        }, "y2-library").apply { isDaemon = true }
    }
    private val scanExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                runnable.run()
            } catch (error: Throwable) {
                logger.error("Library", "scan task failed", error)
            }
        }, "y2-scan").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanWakeLock =
        (appContext.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Y2Player:LibraryScan")
            .apply { setReferenceCounted(true) }
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val scanning = AtomicBoolean(false)
    private val initialScanRequested = AtomicBoolean(false)
    private val cachedLoadRequested = AtomicBoolean(false)
    private val playlistFiles = PlaylistFileManager(database)
    @Volatile private var cancellation: ScanCancellation? = null
    @Volatile private var pendingReason: ScanReason? = null
    @Volatile private var activeReason: ScanReason = ScanReason.MANUAL
    @Volatile private var scanStartedAtMs = 0L
    @Volatile private var current = LibraryState()
    private var revision = 0L
    private var tracksRevision = 0L
    private var availabilityRevision = 0L
    private var lastProgressPublishAt = 0L

    fun addListener(listener: Listener, emitImmediately: Boolean = true) {
        listeners += listener
        if (emitImmediately) listener.onLibraryChanged(current)
    }

    fun removeListener(listener: Listener) { listeners -= listener }

    fun loadCached() {
        if (!cachedLoadRequested.compareAndSet(false, true)) return
        stateExecutor.execute {
            runCatching {
                database.ensureOpen()
                loadState(isScanning = current.isScanning, lastScanAt = current.lastScanAt)
            }
                .onSuccess(::publish)
                .onFailure { error ->
                    cachedLoadRequested.set(false)
                    publish(current.copy(errorMessage = error.message ?: error.javaClass.simpleName))
                }
        }
    }

    fun scan() = scan(ScanReason.MANUAL)

    fun scan(reason: ScanReason) {
        initialScanRequested.set(true)
        if (!scanning.compareAndSet(false, true)) {
            if (pendingReason == null || reason != ScanReason.MANUAL) pendingReason = reason
            eventLog?.debug(Sub.SCANNER, Ev.RESCAN_REQUESTED, "reason" to reason.code, "queued" to true)
            return
        }
        activeReason = reason
        scanStartedAtMs = SystemClock.elapsedRealtime()
        val localScanStartedAtMs = scanStartedAtMs
        val profiler = ScanProfiler(enabled = BuildConfig.DEBUG)
        if (profiler.enabled) NativeAudio.nativeResetMetadataProfile()
        eventLog?.info(Sub.SCANNER, Ev.SCAN_START, "reason" to reason.code)
        val localCancellation = ScanCancellation()
        cancellation = localCancellation
        stateExecutor.execute {
            publish(current.copy(isScanning = true, scanProgress = LibraryScanProgress(), errorMessage = null))
        }
        executeScan {
            var failure: Throwable? = null
            var allVolumesComplete = false
            var coverageGap: CoverageGap? = null
            var totalProcessed = 0L
            var totalErrors = 0L
            var volumesScanned = 0
            var totalCost = ScanCost()
            val discoveredPlaylists = ArrayList<java.io.File>()
            try {
                val rootsStarted = profiler.start()
                val roots = Y2StoragePaths.availableRoots()
                profiler.stop(ScanPhase.ROOT_DISCOVERY, rootsStarted)
                logger.info("Library", "scan started roots=${roots.joinToString { it.id }}")
                allVolumesComplete = roots.isNotEmpty()
                roots.forEach { root ->
                    if (localCancellation.isCancelled()) return@forEach
                    var scanRecordStarted = profiler.start()
                    val scanId = database.recordScanStart(root.id)
                    profiler.stop(ScanPhase.SCAN_RECORD, scanRecordStarted)
                    try {
                        val outcome = scanner.scan(
                            root = root,
                            fingerprintLookup = { paths -> database.loadTrackFingerprints(root.id, paths) },
                            cancellation = localCancellation,
                            onBatch = { files -> database.applyScanBatch(root.id, scanId, files, profiler) },
                            onProgress = { path, count ->
                                publishProgress(
                                    root.id,
                                    path,
                                    (totalProcessed + count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                                )
                            },
                            playbackActive = { playbackActive },
                            profiler = profiler
                        )
                        if (outcome.complete) {
                            val finishStarted = profiler.start()
                            database.finishScan(root.id, scanId)
                            profiler.stop(ScanPhase.FINISH_SCAN, finishStarted)
                            discoveredPlaylists += outcome.playlistFiles
                        } else {
                            allVolumesComplete = false
                            if (coverageGap == null) coverageGap = outcome.coverageGap
                        }
                        scanRecordStarted = profiler.start()
                        database.recordScanEnd(
                            scanId,
                            when {
                                outcome.cancelled -> "CANCELLED"
                                outcome.complete -> "SUCCESS"
                                else -> "INCOMPLETE"
                            },
                            outcome.processedFiles,
                            scanNote(outcome)
                        )
                        profiler.stop(ScanPhase.SCAN_RECORD, scanRecordStarted)
                        totalProcessed += outcome.processedFiles.toLong()
                        totalErrors += outcome.recoverableErrors.toLong()
                        totalCost += outcome.cost
                        volumesScanned += 1
                        logger.info(
                            "Library",
                            "scan ${root.id} files=${outcome.processedFiles} cancelled=${outcome.cancelled} " +
                                "complete=${outcome.complete} errors=${outcome.recoverableErrors} ${costSummary(outcome.cost)}"
                        )
                        eventLog?.debug(
                            Sub.SCANNER, Ev.SCAN_COMPLETE,
                            "volume" to root.id,
                            "files" to outcome.processedFiles,
                            "errors" to outcome.recoverableErrors,
                            "complete" to outcome.complete,
                            "gap" to outcome.coverageGap?.name,
                            "cancelled" to outcome.cancelled,
                            "read" to outcome.cost.filesRead,
                            "readMs" to outcome.cost.metadataMs,
                            "readKb" to outcome.cost.bytesRead / 1024,
                            "yieldMs" to outcome.cost.yieldMs,
                            "yields" to outcome.cost.yields
                        )
                    } catch (error: Throwable) {
                        val failedRecordStarted = profiler.start()
                        database.recordScanEnd(scanId, "ERROR", 0, error.message)
                        profiler.stop(ScanPhase.SCAN_RECORD, failedRecordStarted)
                        logger.error("Library", "scan failed for ${root.id}", error)
                        throw error
                    }
                }
                if (!localCancellation.isCancelled() && discoveredPlaylists.isNotEmpty()) {
                    val playlistStarted = profiler.start()
                    val result = playlistFiles.importFiles(discoveredPlaylists)
                    profiler.stop(ScanPhase.PLAYLIST_IMPORT, playlistStarted)
                    logger.info("Playlist", "automatic M3U import files=${result.imported} tracks=${result.matchedTracks}")
                }
            } catch (error: Throwable) {
                failure = error
            }
            val cancelled = localCancellation.isCancelled()
            val complete = allVolumesComplete
            val localGap = coverageGap
            val localFailure = failure
            val nativeProfile = if (profiler.enabled) NativeAudio.nativeMetadataProfile() else LongArray(0)
            stateExecutor.execute {
                val settledState = if (localFailure != null) {
                    current.copy(
                        isScanning = false,
                        scanProgress = LibraryScanProgress(),
                        errorMessage = localFailure.message ?: localFailure.javaClass.simpleName
                    )
                } else {
                    loadState(
                        isScanning = false,
                        lastScanAt = if (complete && !cancelled) System.currentTimeMillis() else current.lastScanAt,
                        profiler = profiler
                    ).let { state ->
                        val gap = if (complete || cancelled) null else coverageGapMessage(localGap)
                        if (gap == null) state else state.copy(errorMessage = gap)
                    }
                }
                val publishStarted = profiler.start()
                publish(settledState)
                profiler.stop(ScanPhase.STATE_PUBLISH, publishStarted)
                if (profiler.enabled) mainHandler.post {
                    emitScanProfile(
                        profiler = profiler,
                        nativeProfile = nativeProfile,
                        wallMs = SystemClock.elapsedRealtime() - localScanStartedAtMs,
                        files = totalProcessed
                    )
                }
            }
            val elapsedMs = SystemClock.elapsedRealtime() - localScanStartedAtMs
            eventLog?.info(
                Sub.SCANNER,
                if (cancelled) Ev.SCAN_CANCELLED else if (localFailure != null) Ev.SCAN_ERROR else Ev.SCAN_COMPLETE,
                "reason" to activeReason.code,
                "ms" to elapsedMs,
                "complete" to complete,
                "gap" to localGap?.name,
                "volumes" to volumesScanned,
                "files" to totalProcessed,
                "errors" to totalErrors,
                "read" to totalCost.filesRead,
                "readMs" to totalCost.metadataMs,
                "readKb" to totalCost.bytesRead / 1024,
                "yieldMs" to totalCost.yieldMs,
                "yields" to totalCost.yields,
                "playlists" to discoveredPlaylists.size,
                "error" to localFailure?.javaClass?.simpleName,
                "errorMessage" to localFailure?.message?.take(160)
            )
            cancellation = null
            scanning.set(false)
            val queued = pendingReason
            pendingReason = null
            if (queued != null && !cancelled) scan(queued)
        }
    }

    private fun executeScan(task: () -> Unit) {
        val wakeLockAcquired = acquireScanWakeLock()
        try {
            scanExecutor.execute {
                try {
                    task()
                } finally {
                    releaseScanWakeLock(wakeLockAcquired)
                }
            }
        } catch (error: RuntimeException) {
            releaseScanWakeLock(wakeLockAcquired)
            throw error
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireScanWakeLock(): Boolean = try {
        scanWakeLock.acquire()
        true
    } catch (error: RuntimeException) {
        runCatching { logger.warn("Library", "scan wake lock unavailable: ${error.message}") }
        false
    }

    private fun releaseScanWakeLock(acquired: Boolean) {
        if (!acquired) return
        try {
            scanWakeLock.release()
        } catch (error: RuntimeException) {
            runCatching { logger.warn("Library", "scan wake lock release failed: ${error.message}") }
        }
    }

    fun setPlaybackActive(active: Boolean) { playbackActive = active }

    @Volatile private var playbackActive = false

    fun requestInitialScan() {
        if (initialScanRequested.compareAndSet(false, true)) scan()
    }

    fun cancelScan(reason: String) {
        logger.warn("Library", "scan cancellation requested: $reason")
        cancellation?.cancel()
    }

    fun markVolumeUnavailable(volumeId: String) {
        cancelScan("volume $volumeId unavailable")
        stateExecutor.execute {
            database.markVolumeUnavailable(volumeId)
            logger.warn("Storage", "$volumeId marked unavailable")
            if (current.tracks.any { it.volumeId == volumeId }) {
                revision += 1
                tracksRevision += 1
                availabilityRevision += 1
                val remaining = current.tracks.filterNot { it.volumeId == volumeId }
                publish(current.copy(
                    revision = revision,
                    tracksRevision = tracksRevision,
                    availabilityRevision = availabilityRevision,
                    index = LibraryIndex.of(remaining)
                ))
            }
        }
    }

    fun recordPlaybackFailure(trackId: Long, reason: String) = updatePlaybackError(trackId, reason)

    fun clearPlaybackFailure(trackId: Long) = updatePlaybackError(trackId, null)

    private fun updatePlaybackError(trackId: Long, reason: String?) = stateExecutor.execute {
        val track = current.byId[trackId] ?: database.findTrack(trackId) ?: return@execute
        if (track.playbackError == reason) return@execute
        database.setPlaybackError(trackId, reason)
        logger.info("Library", "track=$trackId playbackError=${reason ?: "cleared"}")
        revision += 1
        val updatedTracks = replaceTrack(current.tracks, track.copy(playbackError = reason))
        if (updatedTracks === current.tracks) {
            publish(current.copy(revision = revision))
        } else {
            tracksRevision += 1
            publish(current.copy(
                revision = revision,
                tracksRevision = tracksRevision,
                index = LibraryIndex.of(updatedTracks)
            ))
        }
    }

    fun refreshAudiobookProgress() = stateExecutor.execute {
        val progress = runCatching { database.loadAllAudiobookProgress() }.getOrElse { error ->
            logger.warn("Library", "audiobook progress unavailable (${error.javaClass.simpleName})")
            return@execute
        }
        if (progress == current.audiobookProgress) return@execute
        revision += 1
        publish(current.copy(revision = revision, audiobookProgress = progress))
    }

    fun toggleFavorite(trackId: Long) = stateExecutor.execute {
        val track = current.byId[trackId] ?: database.findTrack(trackId) ?: return@execute
        val favorite = !track.favorite
        database.setFavorite(trackId, favorite)
        logger.info("Library", "favorite track=$trackId value=$favorite")
        revision += 1
        val updatedTracks = replaceTrack(current.tracks, track.copy(favorite = favorite))
        if (updatedTracks === current.tracks) {
            publish(current.copy(revision = revision))
        } else {
            tracksRevision += 1
            publish(current.copy(
                revision = revision,
                tracksRevision = tracksRevision,
                index = LibraryIndex.of(updatedTracks)
            ))
        }
    }

    fun createPlaylist() = stateExecutor.execute {
        val playlist = database.createPlaylist()
        logger.info("Playlist", "created ${playlist.name}")
        revision += 1
        publish(current.copy(
            revision = revision,
            playlists = current.playlists + playlist,
            playlistTrackIds = current.playlistTrackIds + (playlist.id to emptyList())
        ))
    }

    fun createPlaylistWithTrack(trackId: Long) = stateExecutor.execute {
        val playlist = database.createPlaylist()
        database.addTrackToPlaylist(playlist.id, trackId)
        logger.info("Playlist", "created ${playlist.name} with track=$trackId")
        revision += 1
        publish(current.copy(
            revision = revision,
            playlists = current.playlists + playlist.copy(trackCount = 1),
            playlistTrackIds = current.playlistTrackIds + (playlist.id to listOf(trackId))
        ))
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) = stateExecutor.execute {
        val existing = current.playlistTrackIds[playlistId].orEmpty()
        if (trackId in existing) return@execute
        database.addTrackToPlaylist(playlistId, trackId)
        logger.info("Playlist", "added track=$trackId playlist=$playlistId")
        revision += 1
        val ids = existing + trackId
        publish(current.copy(
            revision = revision,
            playlists = updatePlaylistCount(current.playlists, playlistId, ids.size),
            playlistTrackIds = current.playlistTrackIds + (playlistId to ids)
        ))
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) = stateExecutor.execute {
        val ids = current.playlistTrackIds[playlistId].orEmpty().filterNot { it == trackId }
        database.removeTrackFromPlaylist(playlistId, trackId)
        revision += 1
        publish(current.copy(
            revision = revision,
            playlists = updatePlaylistCount(current.playlists, playlistId, ids.size),
            playlistTrackIds = current.playlistTrackIds + (playlistId to ids)
        ))
    }

    fun deletePlaylist(playlistId: Long) = stateExecutor.execute {
        database.deletePlaylist(playlistId)
        logger.info("Playlist", "deleted playlist=$playlistId")
        revision += 1
        publish(current.copy(
            revision = revision,
            playlists = current.playlists.filterNot { it.id == playlistId },
            playlistTrackIds = current.playlistTrackIds - playlistId
        ))
    }

    fun importM3uPlaylists(onComplete: (PlaylistFileManager.ImportResult) -> Unit = {}) = stateExecutor.execute {
        val files = Y2StoragePaths.availableRoots().flatMap { playlistFiles.discover(it.directory) }
        val result = playlistFiles.importFiles(files)
        logger.info("Playlist", "manual M3U import files=${result.imported} tracks=${result.matchedTracks}")
        publish(loadState(isScanning = current.isScanning, lastScanAt = current.lastScanAt))
        mainHandler.post { onComplete(result) }
    }

    fun exportM3uPlaylists(onComplete: (PlaylistFileManager.ExportResult) -> Unit = {}) = stateExecutor.execute {
        val result = playlistFiles.exportAll(current, Y2StoragePaths.availableRoots())
        logger.info("Playlist", "M3U export files=${result.exported} directory=${result.directory}")
        mainHandler.post { onComplete(result) }
    }

    fun recordRecentlyPlayed(trackId: Long) = stateExecutor.execute {
        database.recordRecentlyPlayed(trackId)
        val ids = listOf(trackId) + current.recentlyPlayedIds.filterNot { it == trackId }.take(99)
        revision += 1
        publish(current.copy(revision = revision, recentlyPlayedIds = ids))
    }

    fun resetLibrary() {
        cancelScan("library reset")
        stateExecutor.execute {
            database.resetLibrary()
            logger.warn("Library", "library and user collections reset")
            revision += 1
            tracksRevision += 1
            availabilityRevision += 1
            publish(LibraryState(
                revision = revision,
                tracksRevision = tracksRevision,
                availabilityRevision = availabilityRevision
            ))
        }
    }

    fun findTrack(id: Long): Track? = current.byId[id] ?: database.findTrack(id)
    fun snapshot(): LibraryState = current

    private fun loadState(
        isScanning: Boolean,
        lastScanAt: Long?,
        profiler: ScanProfiler? = null
    ): LibraryState {
        val previousAvailability = current.availableTrackIds
        val tracksStarted = profiler?.start() ?: 0L
        val tracks = database.loadTracks()
        profiler?.stop(ScanPhase.STATE_LOAD_TRACKS, tracksStarted)
        val indexStarted = profiler?.start() ?: 0L
        val index = LibraryIndex.of(tracks)
        profiler?.stop(ScanPhase.STATE_BUILD_INDEX, indexStarted)
        revision += 1
        tracksRevision += 1
        if (index.availableTrackIds != previousAvailability) availabilityRevision += 1
        val queriesStarted = profiler?.start() ?: 0L
        val playlists = database.loadPlaylists()
        val allPlaylistTracks = database.loadAllPlaylistTrackIds()
        val totalIndexedTracks = database.countTracks()
        val recentlyPlayedIds = database.loadRecentlyPlayedIds()
        // copy, not a fresh LibraryState: anything this function does not query is
        // carried over rather than silently reset to its default. Audiobook progress
        // is loaded on demand and was being wiped by every scan.
        val state = current.copy(
            revision = revision,
            tracksRevision = tracksRevision,
            availabilityRevision = availabilityRevision,
            index = index,
            totalIndexedTracks = totalIndexedTracks,
            playlists = playlists,
            playlistTrackIds = playlists.associate { it.id to allPlaylistTracks[it.id].orEmpty() },
            recentlyPlayedIds = recentlyPlayedIds,
            isScanning = isScanning,
            scanProgress = if (isScanning) current.scanProgress else LibraryScanProgress(),
            lastScanAt = lastScanAt,
            errorMessage = null
        )
        profiler?.stop(ScanPhase.STATE_OTHER_QUERIES, queriesStarted)
        return state
    }

    private fun coverageGapMessage(gap: CoverageGap?): String = when (gap) {
        CoverageGap.ROOT_UNREADABLE -> "Storage could not be read; existing tracks were kept"
        CoverageGap.DIRECTORY_UNREADABLE -> "Some folders could not be read; existing tracks were kept"
        CoverageGap.FILE_LIMIT ->
            "More than ${LibraryScanner.MAX_AUDIO_FILES} audio files; the rest were skipped"
        null -> "No storage was available to scan; existing tracks were kept"
    }

    private fun costSummary(cost: ScanCost): String {
        if (cost.filesRead == 0) return "read=0"
        return "read=${cost.filesRead} readMs=${cost.metadataMs} " +
            "perFile=${cost.metadataMs / cost.filesRead}ms/${cost.bytesRead / cost.filesRead / 1024}kb " +
            "yieldMs=${cost.yieldMs} yields=${cost.yields}"
    }

    private fun emitScanProfile(
        profiler: ScanProfiler,
        nativeProfile: LongArray,
        wallMs: Long,
        files: Long
    ) {
        val totalUs = (wallMs.coerceAtLeast(1L) * 1_000L)
        val phases = profiler.snapshot()
        val accountedUs = phases.sumOf(ScanPhaseTiming::totalUs)
        eventLog?.info(
            Sub.SCANNER, Ev.SCAN_PROFILE,
            "kind" to "summary",
            "wallMs" to wallMs,
            "files" to files,
            "accountedUs" to accountedUs,
            "residualUs" to (totalUs - accountedUs).coerceAtLeast(0L),
            "phaseCount" to phases.size
        )
        phases.forEach { timing ->
            eventLog?.info(
                Sub.SCANNER, Ev.SCAN_PROFILE,
                "kind" to "exclusive",
                "phase" to timing.phase.code,
                "count" to timing.count,
                "totalUs" to timing.totalUs,
                "avgUs" to timing.averageUs,
                "maxUs" to timing.maximumUs,
                "wallPctBp" to percentageBasisPoints(timing.totalUs, totalUs)
            )
        }
        if (nativeProfile.size < NATIVE_PROFILE_SIZE) return
        val nativeCalls = nativeProfile[0]
        eventLog?.info(
            Sub.SCANNER, Ev.SCAN_PROFILE,
            "kind" to "native_summary",
            "calls" to nativeCalls,
            "success" to nativeProfile[1],
            "failure" to nativeProfile[2],
            "totalUs" to nativeProfile[3],
            "avgUs" to if (nativeCalls == 0L) 0L else nativeProfile[3] / nativeCalls,
            "maxUs" to nativeProfile[4],
            "bytes" to nativeProfile[5],
            "maxBytes" to nativeProfile[6],
            "wallPctBp" to percentageBasisPoints(nativeProfile[3], totalUs)
        )
        NATIVE_PHASE_NAMES.forEachIndexed { index, name ->
            val base = NATIVE_PHASE_BASE + index * NATIVE_PHASE_WIDTH
            val count = nativeProfile[base]
            val phaseTotalUs = nativeProfile[base + 1]
            eventLog?.info(
                Sub.SCANNER, Ev.SCAN_PROFILE,
                "kind" to "native_child",
                "phase" to name,
                "count" to count,
                "totalUs" to phaseTotalUs,
                "avgUs" to if (count == 0L) 0L else phaseTotalUs / count,
                "maxUs" to nativeProfile[base + 2],
                "wallPctBp" to percentageBasisPoints(phaseTotalUs, totalUs),
                "nativePctBp" to percentageBasisPoints(phaseTotalUs, nativeProfile[3])
            )
        }
        logger.info(
            "LibraryProfile",
            "wallMs=$wallMs files=$files accountedUs=$accountedUs " +
                "nativeUs=${nativeProfile[3]} residualUs=${(totalUs - accountedUs).coerceAtLeast(0L)}"
        )
    }

    private fun percentageBasisPoints(part: Long, whole: Long): Long =
        if (whole <= 0L) 0L else ((part.coerceAtLeast(0L) * 10_000L) / whole).coerceAtMost(10_000L)

    private fun scanNote(outcome: ScanOutcome): String? {
        val parts = ArrayList<String>(2)
        outcome.coverageGap?.let { parts += it.name.lowercase() }
        if (outcome.recoverableErrors > 0) parts += "${outcome.recoverableErrors} skipped files"
        return parts.joinToString(", ").ifEmpty { null }
    }

    private fun publishProgress(volumeId: String, path: String, count: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastProgressPublishAt < PROGRESS_INTERVAL_MS && count % 100 != 0) return
        lastProgressPublishAt = now
        stateExecutor.execute {
            if (!scanning.get()) return@execute
            publish(current.copy(
                isScanning = true,
                scanProgress = LibraryScanProgress(volumeId, path, count),
                errorMessage = null
            ))
        }
    }

    private fun replaceTrack(tracks: List<Track>, replacement: Track): List<Track> {
        val index = tracks.indexOfFirst { it.id == replacement.id }
        if (index < 0) return tracks
        return tracks.toMutableList().apply { this[index] = replacement }
    }

    private fun updatePlaylistCount(playlists: List<PlaylistSummary>, playlistId: Long, count: Int): List<PlaylistSummary> =
        playlists.map { if (it.id == playlistId) it.copy(trackCount = count) else it }

    private fun publish(state: LibraryState) {
        current = state
        mainHandler.post { listeners.forEach { it.onLibraryChanged(state) } }
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val NATIVE_PROFILE_SIZE = 37
        private const val NATIVE_PHASE_BASE = 7
        private const val NATIVE_PHASE_WIDTH = 3
        private val NATIVE_PHASE_NAMES = arrayOf(
            "jni_path", "setup", "open_input", "stream_info", "select_stream",
            "dictionary", "replaygain", "artwork", "java_result", "close"
        )
    }
}
