package com.schulzcode.y2player.core.state

internal object ListNavigationPolicy {
    private const val MIN_ACCELERATED_ITEMS = 12

    fun firstSelectableIndex(screen: Screen, itemCount: Int): Int =
        if (screen is Screen.ConfirmAction && itemCount > 1) 1 else 0

    fun nextIndex(
        screen: Screen,
        currentIndex: Int,
        delta: Int,
        itemCount: Int,
        wrapLists: Boolean
    ): Int {
        if (itemCount <= 0) return 0
        val first = firstSelectableIndex(screen, itemCount)
        val last = itemCount - 1
        val current = currentIndex.coerceIn(first, last)
        if (current == last && first == last) return current
        if (!wraps(screen, wrapLists)) return (current + delta).coerceIn(first, last)

        val selectableCount = last - first + 1
        val relative = ((current - first + delta) % selectableCount + selectableCount) % selectableCount
        return first + relative
    }

    fun allowsAcceleration(screen: Screen, itemCount: Int): Boolean =
        itemCount >= MIN_ACCELERATED_ITEMS && when (screen) {
            Screen.Songs, Screen.Favorites, Screen.RecentlyPlayed,
            Screen.Albums, Screen.Artists, Screen.Playlists, Screen.Queue,
            Screen.Audiobooks, is Screen.AlbumSongs, is Screen.ArtistAlbums,
            is Screen.ArtistSongs, is Screen.Folders, is Screen.PlaylistTracks,
            is Screen.AudiobookChapters, is Screen.AddToPlaylist -> true
            else -> false
        }

    private fun wraps(screen: Screen, preference: Boolean): Boolean = when (screen) {
        is Screen.ConfirmAction -> false
        Screen.EqualizerBands, Screen.NowPlayingOptions -> true
        else -> preference
    }
}
