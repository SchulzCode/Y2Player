package com.schulzcode.y2player.debug

import android.app.Service
import android.content.Intent
import android.os.Debug
import android.os.IBinder
import android.graphics.BitmapFactory
import com.schulzcode.y2player.Y2Application
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.core.model.AudioCodecSupport
import com.schulzcode.y2player.core.model.CodecSupport
import com.schulzcode.y2player.library.FfmpegMetadata
import com.schulzcode.y2player.library.LibraryScanner
import com.schulzcode.y2player.library.ScanCancellation
import com.schulzcode.y2player.library.ScanPhaseTiming
import com.schulzcode.y2player.library.ScanProfiler
import com.schulzcode.y2player.playback.NativeAudio
import com.schulzcode.y2player.playback.NativeDecoder
import com.schulzcode.y2player.playback.NativeDecoderException
import com.schulzcode.y2player.storage.StorageRoot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * ADB-driven, debug-only media regression runner.
 *
 * The fixture manifest supplies paths and hashes, but never influences actual
 * values. This component calls the same JNI, scanner and database code as the
 * product, then exports raw observations for the independent host comparator.
 */
class MediaRegressionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val corpusPath = intent?.getStringExtra(EXTRA_CORPUS)
        val outputPath = intent?.getStringExtra(EXTRA_OUTPUT)
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_ALL
        val repeats = intent?.getIntExtra(EXTRA_REPEATS, 1)?.coerceIn(1, 100) ?: 1
        val probeBytes = intent?.getIntExtra(EXTRA_PROBE_BYTES, DEFAULT_PROBE_BYTES) ?: DEFAULT_PROBE_BYTES
        val analyzeUs = intent?.getIntExtra(EXTRA_ANALYZE_US, DEFAULT_ANALYZE_US) ?: DEFAULT_ANALYZE_US
        Thread({
            val result = runCatching {
                require(!corpusPath.isNullOrBlank()) { "missing corpus path" }
                require(!outputPath.isNullOrBlank()) { "missing output path" }
                execute(File(corpusPath), mode, repeats, probeBytes, analyzeUs)
            }.getOrElse { error ->
                JSONObject().put("complete", false).put("fatalError", error.stackTraceToString())
            }
            if (!outputPath.isNullOrBlank()) {
                val output = File(outputPath)
                output.parentFile?.mkdirs()
                output.writeText(result.toString(2), Charsets.UTF_8)
            }
            stopSelf(startId)
        }, "y2-media-regression").start()
        return START_NOT_STICKY
    }

    private fun execute(
        corpus: File,
        mode: String,
        repeats: Int,
        probeBytes: Int,
        analyzeUs: Int
    ): JSONObject {
        val app = application as Y2Application
        // Keep the debug process from launching the ordinary whole-card scan in
        // response to a mount callback while this isolated corpus run owns the DB.
        app.container.safeModeManager.forceSafeMode()
        app.container.libraryRepository.cancelScan("media regression isolation")
        val manifestFile = File(corpus, "manifest.json")
        val fixtures = if (mode == MODE_SCAN && !manifestFile.isFile) {
            JSONArray()
        } else {
            require(manifestFile.isFile) { "manifest not found: $manifestFile" }
            JSONObject(manifestFile.readText(Charsets.UTF_8)).getJSONArray("fixtures")
        }
        require(NativeAudio.nativeConfigureMetadataProbeLimits(probeBytes, analyzeUs)) {
            "invalid metadata probe limits: probeBytes=$probeBytes analyzeUs=$analyzeUs"
        }
        val before = resources()
        val started = System.nanoTime()
        val output = JSONObject()
            .put("schemaVersion", 1)
            .put("complete", false)
            .put("mode", mode)
            .put("repeats", repeats)
            .put("probeBytes", probeBytes)
            .put("analyzeUs", analyzeUs)
            .put("corpus", corpus.absolutePath)
            .put("fixtureCount", fixtures.length())
            .put("nativeBuild", runCatching { NativeDecoder.buildInformation() }.getOrNull())
            .put("resourcesBefore", before)

        if (mode == MODE_ALL || mode == MODE_METADATA || mode == MODE_RESOURCE) {
            output.put("metadata", runMetadata(corpus, fixtures, repeats))
        }
        if (mode == MODE_ALL || mode == MODE_SCAN) {
            output.put("scan", runScan(corpus))
        }
        if (mode == MODE_ALL || mode == MODE_DECODE || mode == MODE_RESOURCE) {
            output.put("decode", runDecode(corpus, fixtures, repeats))
        }
        Runtime.getRuntime().gc()
        Thread.sleep(100)
        output.put("resourcesAfter", resources())
        output.put("elapsedMs", (System.nanoTime() - started) / 1_000_000L)
        output.put("complete", true)
        return output
    }

    private fun runMetadata(corpus: File, fixtures: JSONArray, repeats: Int): JSONObject {
        val results = JSONArray()
        NativeAudio.nativeResetMetadataProfile()
        var jniCrossings = 0L
        for (repeat in 0 until repeats) {
            for (index in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(index)
                val file = File(corpus, fixture.getString("path"))
                val started = System.nanoTime()
                val actual = NativeAudio.nativeReadMetadata(file.absolutePath)
                val elapsedUs = (System.nanoTime() - started) / 1_000L
                jniCrossings += 1
                if (repeat == 0) {
                    results.put(metadataJson(fixture.getString("id"), file, actual, elapsedUs))
                }
            }
        }
        return JSONObject()
            .put("results", results)
            .put("jniCrossings", jniCrossings)
            .put("nativeProfile", nativeProfileJson(NativeAudio.nativeMetadataProfile()))
    }

    private fun metadataJson(id: String, file: File, value: FfmpegMetadata, elapsedUs: Long): JSONObject {
        val result = JSONObject()
            .put("id", id)
            .put("path", file.absolutePath)
            .put("sha256", sha256(file))
            .put("elapsedUs", elapsedUs)
            .put("success", value.success)
            .put("errorCategory", value.errorCategory)
            .putNullable("errorDetail", value.errorDetail)
            .putNullable("title", value.title)
            .putNullable("artist", value.artist)
            .putNullable("album", value.album)
            .putNullable("albumArtist", value.albumArtist)
            .putNullable("composer", value.composer)
            .putNullable("genre", value.genre)
            .putNullable("date", value.date)
            .putNullable("comment", value.comment)
            .put("trackNumber", value.trackNumber)
            .put("trackTotal", value.trackTotal)
            .put("discNumber", value.discNumber)
            .put("discTotal", value.discTotal)
            .put("year", value.year)
            .put("durationMs", value.durationMs)
            .putNullable("codec", value.codec)
            .putNullable("container", value.container)
            .put("bitrate", value.bitrate)
            .put("sampleRate", value.sampleRate)
            .put("bitDepth", value.bitDepth)
            .put("channels", value.channels)
            .put("replayGainTrackGainScaled", value.replayGainTrackGain)
            .put("replayGainTrackPeakScaled", value.replayGainTrackPeak)
            .put("replayGainAlbumGainScaled", value.replayGainAlbumGain)
            .put("replayGainAlbumPeakScaled", value.replayGainAlbumPeak)
            .put("bytesRead", value.bytesRead)
            .put("hasArtwork", value.hasArtwork)
        if (value.hasArtwork) {
            val artworkStarted = System.nanoTime()
            val artwork = NativeAudio.nativeReadArtwork(file.absolutePath, MAX_ARTWORK_BYTES)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (artwork != null) BitmapFactory.decodeByteArray(artwork, 0, artwork.size, options)
            result.put("artworkReadUs", (System.nanoTime() - artworkStarted) / 1_000L)
                .put("artworkBytes", artwork?.size ?: 0)
                .put("artworkWidth", options.outWidth)
                .put("artworkHeight", options.outHeight)
                .put("artworkValid", artwork != null && options.outWidth > 0 && options.outHeight > 0)
        }
        return result
    }

    private fun runScan(corpus: File): JSONObject {
        val app = application as Y2Application
        val database = app.container.database
        val profiler = ScanProfiler(enabled = true)
        val scanner = LibraryScanner()
        val volumeId = "media-regression"
        val token = System.currentTimeMillis()
        database.resetLibrary()
        NativeAudio.nativeResetMetadataProfile()
        val started = System.nanoTime()
        val outcome = scanner.scan(
            root = StorageRoot(volumeId, corpus),
            fingerprintLookup = { paths -> database.loadTrackFingerprints(volumeId, paths) },
            cancellation = ScanCancellation(),
            onBatch = { files -> database.applyScanBatch(volumeId, token, files, profiler) },
            onProgress = { _, _ -> },
            profiler = profiler
        )
        if (outcome.complete) database.finishScan(volumeId, token)
        val scanElapsedUs = (System.nanoTime() - started) / 1_000L
        val loadStarted = System.nanoTime()
        val tracks = database.loadTracks().filter { it.volumeId == volumeId }
        val loadElapsedUs = (System.nanoTime() - loadStarted) / 1_000L
        return JSONObject()
            .put("complete", outcome.complete)
            .put("processedFiles", outcome.processedFiles)
            .put("recoverableErrors", outcome.recoverableErrors)
            .putNullable("coverageGap", outcome.coverageGap?.name)
            .put("elapsedUs", scanElapsedUs)
            .put("databaseLoadUs", loadElapsedUs)
            .put("metadataFilesRead", outcome.cost.filesRead)
            .put("metadataBytesRead", outcome.cost.bytesRead)
            .put("metadataMs", outcome.cost.metadataMs)
            .put("phases", JSONArray().also { values -> profiler.snapshot().forEach { values.put(phaseJson(it)) } })
            .put("nativeProfile", nativeProfileJson(NativeAudio.nativeMetadataProfile()))
            .put("tracks", JSONArray().also { values -> tracks.forEach { values.put(trackJson(it, corpus)) } })
    }

    private fun trackJson(track: Track, corpus: File): JSONObject = JSONObject()
        .put("relativePath", File(track.absolutePath).relativeTo(corpus).path.replace('\\', '/'))
        .put("title", track.title)
        .putNullable("artist", track.artist)
        .putNullable("album", track.album)
        .putNullable("albumArtist", track.albumArtist)
        .putNullable("composer", track.composer)
        .putNullable("genre", track.genre)
        .putNullable("date", track.date)
        .putNullable("trackNumber", track.trackNumber)
        .putNullable("trackTotal", track.trackTotal)
        .putNullable("discNumber", track.discNumber)
        .putNullable("discTotal", track.discTotal)
        .putNullable("comment", track.comment)
        .putNullable("year", track.year)
        .put("durationMs", track.durationMs)
        .putNullable("codec", track.codec)
        .putNullable("container", track.container)
        .putNullable("bitrate", track.bitrate)
        .putNullable("sampleRate", track.sampleRate)
        .putNullable("bitDepth", track.bitDepth)
        .putNullable("channels", track.channels)
        .putNullable("replayGainTrackDb", track.replayGainTrackDb)
        .putNullable("replayGainTrackPeak", track.replayGainTrackPeak)
        .putNullable("replayGainAlbumDb", track.replayGainAlbumDb)
        .putNullable("replayGainAlbumPeak", track.replayGainAlbumPeak)
        .put("hasArtwork", track.hasArtwork)
        .putNullable("scanError", track.scanError)
        .putNullable("playbackError", track.playbackError)

    private fun runDecode(corpus: File, fixtures: JSONArray, repeats: Int): JSONObject {
        val results = JSONArray()
        for (repeat in 0 until repeats) {
            for (index in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(index)
                val file = File(corpus, fixture.getString("path"))
                val actual = decodeOne(fixture.getString("id"), file)
                if (repeat == 0) results.put(actual)
            }
        }
        return JSONObject().put("results", results)
    }

    private fun decodeOne(id: String, file: File): JSONObject {
        val output = JSONObject().put("id", id)
        val started = System.nanoTime()
        var frames = 0L
        var finite = true
        var seekSucceeded = false
        var durationMs = 0L
        var warning: String? = null
        var category = 0
        var detail: String? = null
        try {
            NativeDecoder().use { decoder ->
                val info = decoder.open(file.absolutePath, OUTPUT_RATE, OUTPUT_CHANNELS)
                durationMs = info.durationMs
                output.put("open", true)
                    .put("durationMs", info.durationMs)
                    .put("sourceSampleRate", info.sourceSampleRate)
                    .put("sourceChannels", info.sourceChannels)
                    .put("codec", info.codecName)
                val buffer = ByteBuffer.allocateDirect(DECODE_FRAMES * OUTPUT_CHANNELS * 4).order(ByteOrder.nativeOrder())
                var loops = 0
                while (loops++ < MAX_DECODE_LOOPS) {
                    buffer.clear()
                    val decoded = decoder.decode(buffer, DECODE_FRAMES)
                    if (decoded == 0) break
                    frames += decoded
                    for (sample in 0 until decoded * OUTPUT_CHANNELS) {
                        if (!buffer.getFloat(sample * 4).isFinite()) finite = false
                    }
                }
                val damagedPackets = decoder.warningCount()
                output.put("decodeWarningCount", damagedPackets)
                if (damagedPackets > 0) warning = "$damagedPackets damaged packet(s) skipped"
                if (loops >= MAX_DECODE_LOOPS) warning = "decode loop limit reached"
            }
        } catch (error: NativeDecoderException) {
            category = error.category.wireValue
            detail = error.message
            if (frames > 0) warning = error.message
        } catch (error: Throwable) {
            category = 5
            detail = error.message ?: error.javaClass.simpleName
            if (frames > 0) warning = detail
        }
        if (frames > 0 && durationMs > 2) {
            runCatching {
                NativeDecoder().use { seeker ->
                    val info = seeker.open(file.absolutePath, OUTPUT_RATE, OUTPUT_CHANNELS)
                    seeker.seekTo(info.durationMs / 2)
                    val buffer = ByteBuffer.allocateDirect(DECODE_FRAMES * OUTPUT_CHANNELS * 4)
                        .order(ByteOrder.nativeOrder())
                    seekSucceeded = seeker.decode(buffer, DECODE_FRAMES) > 0
                }
            }.onFailure { error ->
                if (detail == null) detail = error.message ?: error.javaClass.simpleName
            }
        }
        val decodedDurationMs = frames * 1_000L / OUTPUT_RATE
        if (frames > 0 && durationMs - decodedDurationMs > PARTIAL_PCM_TOLERANCE_MS) {
            warning = "decoded ${decodedDurationMs}ms of declared ${durationMs}ms"
        }
        val knownUnsupported = AudioCodecSupport.of(null, file.extension) == CodecSupport.UNSUPPORTED
        val classification = when {
            frames > 0 && warning != null -> "PLAY_WITH_WARNINGS"
            frames > 0 -> "PLAY"
            knownUnsupported -> "UNSUPPORTED"
            category == 2 -> "INDEX_ONLY"
            category == 3 -> "CORRUPT"
            else -> "CORRUPT"
        }
        return output
            .put("decodedFrames", frames)
            .put("finitePcm", finite)
            .put("seekSucceeded", seekSucceeded)
            .put("errorCategory", category)
            .putNullable("errorDetail", detail)
            .putNullable("warning", warning)
            .put("classification", classification)
            .put("elapsedUs", (System.nanoTime() - started) / 1_000L)
    }

    private fun phaseJson(timing: ScanPhaseTiming): JSONObject = JSONObject()
        .put("phase", timing.phase.code)
        .put("count", timing.count)
        .put("totalUs", timing.totalUs)
        .put("averageUs", timing.averageUs)
        .put("maximumUs", timing.maximumUs)

    private fun nativeProfileJson(values: LongArray): JSONObject {
        val output = JSONObject().put("raw", JSONArray().also { array -> values.forEach(array::put) })
        if (values.size < 37) return output
        output.put("calls", values[0]).put("successes", values[1]).put("failures", values[2])
            .put("totalUs", values[3]).put("maximumUs", values[4])
            .put("bytesRead", values[5]).put("maximumBytesRead", values[6])
        val phases = JSONArray()
        PROFILE_PHASES.forEachIndexed { index, name ->
            val base = 7 + index * 3
            phases.put(JSONObject().put("phase", name).put("count", values[base])
                .put("totalUs", values[base + 1]).put("maximumUs", values[base + 2]))
        }
        return output.put("phases", phases)
    }

    private fun resources(): JSONObject {
        val runtime = Runtime.getRuntime()
        return JSONObject()
            .put("fileDescriptors", File("/proc/self/fd").list()?.size ?: -1)
            .put("threads", File("/proc/self/task").list()?.size ?: -1)
            .put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    companion object {
        const val ACTION_RUN = "com.schulzcode.y2player.debug.RUN_MEDIA_REGRESSION"
        const val EXTRA_CORPUS = "corpus"
        const val EXTRA_OUTPUT = "output"
        const val EXTRA_MODE = "mode"
        const val EXTRA_REPEATS = "repeats"
        const val EXTRA_PROBE_BYTES = "probeBytes"
        const val EXTRA_ANALYZE_US = "analyzeUs"
        const val MODE_ALL = "all"
        const val MODE_METADATA = "metadata"
        const val MODE_SCAN = "scan"
        const val MODE_DECODE = "decode"
        const val MODE_RESOURCE = "resource"
        private const val OUTPUT_RATE = 44_100
        private const val OUTPUT_CHANNELS = 2
        private const val DECODE_FRAMES = 1_024
        private const val MAX_DECODE_LOOPS = 2_048
        private const val PARTIAL_PCM_TOLERANCE_MS = 40L
        private const val DEFAULT_PROBE_BYTES = 32 * 1024
        private const val DEFAULT_ANALYZE_US = 100_000
        private const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024
        private val PROFILE_PHASES = arrayOf(
            "jni_path", "setup", "open", "stream_info", "select_stream",
            "dictionary", "replaygain", "artwork", "java_result", "close"
        )
    }
}
