package com.schulzcode.y2player.ui

import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.core.state.AppState
import com.schulzcode.y2player.core.state.Screen
import com.schulzcode.y2player.core.state.ScreenRow

internal data class VisibleRowArtworkRequest(
    val rowIndex: Int,
    val track: Track
)

internal object VisibleRowArtworkPlanner {
    fun requests(
        state: AppState,
        rows: List<ScreenRow>,
        visibleStart: Int,
        visibleCount: Int
    ): List<VisibleRowArtworkRequest> {
        if (visibleCount <= 0 || rows.isEmpty()) return emptyList()
        val start = visibleStart.coerceIn(0, rows.size)
        val end = (start + visibleCount).coerceAtMost(rows.size)
        return buildList(end - start) {
            for (index in start until end) {
                val row = rows[index]
                if (row is ScreenRow.TrackRow && state.currentScreen is Screen.AlbumSongs) continue
                val track = row.artworkTrackId?.let(state.library.byId::get) ?: continue
                if (track.available && track.absolutePath.isNotBlank()) {
                    add(VisibleRowArtworkRequest(index, track))
                }
            }
        }
    }
}
