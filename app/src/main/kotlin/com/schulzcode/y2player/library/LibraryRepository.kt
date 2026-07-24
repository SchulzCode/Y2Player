package com.schulzcode.y2player.library

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.schulzcode.y2player.core.model.LibraryIndex
import com.schulzcode.y2player.core.model.LibraryScanProgress
import com.schulzcode.y2player.core.model.LibraryState
import com.schulzcode.y2player.core.model.PlaylistSummary
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.diagnostics.DiagnosticLogger
import com.schulzcode.y2player.diagnostics.Ev
import com.schulzcode.y2player.diagnostics.EventLog
import com.schulzcode.y2player.diagnostics.Sub
import com.schulzcode.y2player.storage.Y2StoragePaths
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LibraryRepository(
    private val database: LibraryDatabase,
    private val scanner: LibraryScanner = LibraryScanner(),
    private val logger: DiagnosticLogger,
    private val eventLog: EventLog? = null
) {
    fun interface Listener { fun onLibraryChanged(state: LibraryState) }

    /**
     * Threading model:
     * - [stateExecutor] owns every mutation of [current] and all small user-facing DB
     *   writes (favorites, playlists, recently played). It must stay responsive.
     * - [scanExecutor] runs the multi-minute filesystem walk and its batched DB writes.
     *   Keeping scans separate prevents filesystem work from delaying user-facing writes.
     * Scan results are handed back to the state thread for publishing, so `current`,
     * `revision`, `tracksRevision` and `availabilityRevision` remain single-threaded.
     */
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
            try {
                runnable.run()
            } catch (error: Throwable) {
                logger.error("Library", "scan task failed", error)
            }
        }, "y2-scan").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val scanning = AtomicBoolean(false)
    private val initialScanRequested = AtomicBoolean(false)
    private val cachedLoadRequested = AtomicBoolean(false)
    private val playlistFiles = PlaylistFileManager(database)
    @Volatile private var cancellation: ScanCancellation? = null
    /** Set when a request arrives mid-scan; one follow-up pass runs afterwards. */
    @Volatile private var pendingReason: ScanReason? = null
    @Volatile private var activeReason: ScanReason = ScanReason.MANUAL
    @Volatile private var scanStartedAtMs = 0L
    @Volatile private var current = LibraryState()
    // State-thread-confined counters.
    private var revision = 0L
    private var tracksRevision = 0L
    private var availabilityRevision = 0L
    // Scan-thread-confined throttle.
    private var lastProgressPublishAt = 0L

    fun addListener(listener: Listener, emitImmediately: Boolean = true) {
        listeners += listener
        if (emitImmediately) listener.onLibraryChanged(current)
    }

    fun removeListener(listener: Listener) { listeners -= listener }

    fun loadCached() {
        if (!cachedLoadRequested.compareAndSet(false, true)) return
        stateExecutor.execute {
            runCatching { loadState(isScanning = current.isScanning, lastScanAt = current.lastScanAt) }
                .onSuccess(::publish)
                .onFailure { error ->
                    cachedLoadRequested.set(false)
                    publish(current.copy(errorMessage = error.message ?: error.javaClass.simpleName))
                }
        }
    }

    fun scan() = scan(ScanReason.MANUAL)

    /**
     * Starts an incremental scan, recording *why*.
     *
     * Two behaviours matter here and neither is cosmetic:
     *
     * - **Coalescing.** A request arriving while a scan runs is not dropped.
     *   It sets [pendingReason], and one further pass runs when the current one
     *   finishes. This keeps files copied over MTP mid-scan from staying invisible
     *   until some unrelated trigger, while bounding the work: the worst case is
     *   exactly one extra incremental pass, never a queue of them, because repeated
     *   requests collapse into the same single flag.
     * - **Attribution.** The reason is logged at start and repeated in the
     *   completion summary, so a support log answers "why did it rescan?"
     *   without guesswork.
     */
    fun scan(reason: ScanReason) {
        // Mount-triggered and Safe-Mode-exit scans also satisfy the one process-start scan.
        initialScanRequested.set(true)
        if (!scanning.compareAndSet(false, true)) {
            // Keep the most specific pending reason; MANUAL never downgrades a
            // storage-driven one that is already queued.
            if (pendingReason == null || reason != ScanReason.MANUAL) pendingReason = reason
            eventLog?.debug(Sub.SCANNER, Ev.RESCAN_REQUESTED, "reason" to reason.code, "queued" to true)
            return
        }
        activeReason = reason
        scanStartedAtMs = SystemClock.elapsedRealtime()
        eventLog?.info(Sub.SCANNER, Ev.SCAN_START, "reason" to reason.code)
        val localCancellation = ScanCancellation()
        cancellation = localCancellation
        stateExecutor.execute {
            publish(current.copy(isScanning = true, scanProgress = LibraryScanProgress(), errorMessage = null))
        }
        scanExecutor.execute {
            var failure: Throwable? = null
            var allVolumesComplete = false
            // Aggregate counters. Deliberately totals, never per-file events: a
            // full card is tens of thousands of files and one event each would
            // evict every other event from the bounded queue.
            var totalProcessed = 0L
            var totalErrors = 0L
            var volumesScanned = 0
            val discoveredPlaylists = ArrayList<java.io.File>()
            try {
                val roots = Y2StoragePaths.availableRoots()
                logger.info("Library", "scan started roots=${roots.joinToString { it.id }}")
                allVolumesComplete = roots.isNotEmpty()
                roots.forEach { root ->
                    if (localCancellation.isCancelled()) return@forEach
                    val scanId = database.recordScanStart(root.id)
                    try {
                        val outcome = scanner.scan(
                            root = root,
                            fingerprintLookup = { paths -> database.loadTrackFingerprints(root.id, paths) },
                            cancellation = localCancellation,
                            onBatch = { files -> database.applyScanBatch(root.id, scanId, files) },
                            onProgress = { path, count -> publishProgress(root.id, path, count) }
                        )
                        if (outcome.complete) {
                            database.finishScan(root.id, scanId)
                            discoveredPlaylists += outcome.playlistFiles
                        } else {
                            allVolumesComplete = false
                        }
                        database.recordScanEnd(
                            scanId,
                            when {
                                outcome.cancelled -> "CANCELLED"
                                outcome.complete -> "SUCCESS"
                                else -> "INCOMPLETE"
                            },
                            outcome.processedFiles,
                            if (outcome.recoverableErrors > 0) "${outcome.recoverableErrors} recoverable I/O errors" else null
                        )
                        totalProcessed += outcome.processedFiles.toLong()
                        totalErrors += outcome.recoverableErrors.toLong()
                        volumesScanned += 1
                        logger.info("Library", "scan ${root.id} files=${outcome.processedFiles} cancelled=${outcome.cancelled} complete=${outcome.complete} errors=${outcome.recoverableErrors}")
                        // Per-volume totals, so a scan that stalls on one card is
                        // distinguishable from one that found nothing anywhere.
                        eventLog?.debug(
                            Sub.SCANNER, Ev.SCAN_COMPLETE,
                            "volume" to root.id,
                            "files" to outcome.processedFiles,
                            "errors" to outcome.recoverableErrors,
                            "complete" to outcome.complete,
                            "cancelled" to outcome.cancelled
                        )
                    } catch (error: Throwable) {
                        database.recordScanEnd(scanId, "ERROR", 0, error.message)
                        logger.error("Library", "scan failed for ${root.id}", error)
                        throw error
                    }
                }
                if (!localCancellation.isCancelled() && discoveredPlaylists.isNotEmpty()) {
                    val result = playlistFiles.importFiles(discoveredPlaylists)
                    logger.info("Playlist", "automatic M3U import files=${result.imported} tracks=${result.matchedTracks}")
                }
            } catch (error: Throwable) {
                failure = error
            } finally {
                // The retriever holds a native service connection; free it between scans.
                scanner.releaseMetadata()
            }
            val cancelled = localCancellation.isCancelled()
            val complete = allVolumesComplete
            val localFailure = failure
            stateExecutor.execute {
                if (localFailure != null) {
                    publish(current.copy(
                        isScanning = false,
                        scanProgress = LibraryScanProgress(),
                        errorMessage = localFailure.message ?: localFailure.javaClass.simpleName
                    ))
                } else {
                    publish(loadState(
                        isScanning = false,
                        lastScanAt = if (complete && !cancelled) System.currentTimeMillis() else current.lastScanAt
                    ).let { state ->
                        if (complete) state else state.copy(errorMessage = "Storage scan incomplete; existing tracks were preserved")
                    })
                }
            }
            val elapsedMs = SystemClock.elapsedRealtime() - scanStartedAtMs
            eventLog?.info(
                Sub.SCANNER,
                if (cancelled) Ev.SCAN_CANCELLED else if (localFailure != null) Ev.SCAN_ERROR else Ev.SCAN_COMPLETE,
                "reason" to activeReason.code,
                "ms" to elapsedMs,
                "complete" to complete,
                "volumes" to volumesScanned,
                "files" to totalProcessed,
                "errors" to totalErrors,
                "playlists" to discoveredPlaylists.size,
                "error" to localFailure?.javaClass?.simpleName,
                "errorMessage" to localFailure?.message?.take(160)
            )
            cancellation = null
            scanning.set(false)
            // Run at most one follow-up pass for requests that arrived mid-scan.
            val queued = pendingReason
            pendingReason = null
            if (queued != null && !cancelled) scan(queued)
        }
    }

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
                // Unavailable tracks leave RAM entirely; SQLite keeps their metadata and
                // the next successful scan of the remounted volume restores them.
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

    fun toggleFavorite(trackId: Long) = stateExecutor.execute {
        val track = current.byId[trackId] ?: database.findTrack(trackId) ?: return@execute
        val favorite = !track.favorite
        database.setFavorite(trackId, favorite)
        logger.info("Library", "favorite track=$trackId value=$favorite")
        revision += 1
        val updatedTracks = replaceTrack(current.tracks, track.copy(favorite = favorite))
        if (updatedTracks === current.tracks) {
            // Track is not resident (unavailable volume); DB flag is enough.
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
        // Deliberately bumps only `revision`, not `tracksRevision`: this runs at every
        // track transition and must not invalidate the sorted rows of track screens.
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

    private fun loadState(isScanning: Boolean, lastScanAt: Long?): LibraryState {
        val previousAvailability = current.availableTrackIds
        val tracks = database.loadTracks()
        val index = LibraryIndex.of(tracks)
        revision += 1
        tracksRevision += 1
        if (index.availableTrackIds != previousAvailability) availabilityRevision += 1
        val playlists = database.loadPlaylists()
        val allPlaylistTracks = database.loadAllPlaylistTrackIds()
        return LibraryState(
            revision = revision,
            tracksRevision = tracksRevision,
            availabilityRevision = availabilityRevision,
            index = index,
            totalIndexedTracks = database.countTracks(),
            playlists = playlists,
            playlistTrackIds = playlists.associate { it.id to allPlaylistTracks[it.id].orEmpty() },
            recentlyPlayedIds = database.loadRecentlyPlayedIds(),
            isScanning = isScanning,
            scanProgress = if (isScanning) current.scanProgress else LibraryScanProgress(),
            lastScanAt = lastScanAt,
            errorMessage = null
        )
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
    }
}
