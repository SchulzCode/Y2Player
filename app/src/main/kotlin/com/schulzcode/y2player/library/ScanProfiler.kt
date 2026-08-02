package com.schulzcode.y2player.library

class ScanProfiler(val enabled: Boolean) {
    private val counts = LongArray(ScanPhase.entries.size)
    private val totalNanos = LongArray(ScanPhase.entries.size)
    private val maximumNanos = LongArray(ScanPhase.entries.size)

    fun start(): Long = if (enabled) System.nanoTime() else 0L

    fun stop(phase: ScanPhase, startedAtNanos: Long, count: Long = 1L) {
        if (!enabled) return
        record(phase, System.nanoTime() - startedAtNanos, count)
    }

    @Synchronized
    fun record(phase: ScanPhase, nanos: Long, count: Long = 1L) {
        if (!enabled || nanos < 0L || count <= 0L) return
        val index = phase.ordinal
        counts[index] += count
        totalNanos[index] += nanos
        if (nanos > maximumNanos[index]) maximumNanos[index] = nanos
    }

    @Synchronized
    fun snapshot(): List<ScanPhaseTiming> = ScanPhase.entries.mapNotNull { phase ->
        val index = phase.ordinal
        val count = counts[index]
        if (count == 0L) null else ScanPhaseTiming(
            phase = phase,
            count = count,
            totalNanos = totalNanos[index],
            maximumNanos = maximumNanos[index]
        )
    }
}

enum class ScanPhase(val code: String) {
    ROOT_DISCOVERY("root_discovery"),
    SCAN_RECORD("scan_record"),
    ROOT_SETUP("root_setup"),
    DIRECTORY_CANONICAL("directory_canonical"),
    DIRECTORY_LIST("directory_list"),
    DISCOVERY_FILTER("discovery_filter"),
    PATH_BUILD("path_build"),
    FINGERPRINT_QUERY("fingerprint_query"),
    FILE_STAT_COMPARE("file_stat_compare"),
    METADATA_NATIVE("metadata_native"),
    METADATA_MAPPING("metadata_mapping"),
    PROGRESS_CALLBACK("progress_callback"),
    DATABASE_BEGIN("database_begin"),
    DATABASE_ABSOLUTE_UPDATE("database_absolute_update"),
    DATABASE_RELATIVE_UPDATE("database_relative_update"),
    DATABASE_INSERT("database_insert"),
    DATABASE_UNCHANGED_UPDATE("database_unchanged_update"),
    DATABASE_COMMIT("database_commit"),
    FINISH_SCAN("finish_scan"),
    PLAYLIST_IMPORT("playlist_import"),
    STATE_LOAD_TRACKS("state_load_tracks"),
    STATE_BUILD_INDEX("state_build_index"),
    STATE_OTHER_QUERIES("state_other_queries"),
    STATE_PUBLISH("state_publish"),
    PLAYBACK_YIELD("playback_yield")
}

data class ScanPhaseTiming(
    val phase: ScanPhase,
    val count: Long,
    val totalNanos: Long,
    val maximumNanos: Long
) {
    val totalUs: Long get() = totalNanos / 1_000L
    val averageUs: Long get() = if (count == 0L) 0L else totalUs / count
    val maximumUs: Long get() = maximumNanos / 1_000L
}

