// File-level: the RemoteControlClient *import* itself triggers the deprecation
// warning, and RemoteControlClient is the only lock-screen/remote metadata API
// on API 19 (MediaSession arrived in API 21).
@file:Suppress("DEPRECATION")

package com.schulzcode.y2player.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.RemoteControlClient
import com.schulzcode.y2player.artwork.AlbumArtworkLoader
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.diagnostics.DiagnosticLogger

/**
 * API 19 RemoteControlClient owns applied artwork and recycles the previous
 * bitmap. Always give it a detached copy so cached UI artwork remains valid.
 */
internal inline fun <T> publishDetachedArtwork(
    source: T,
    copy: (T) -> T?,
    publish: (T) -> Unit
) {
    val detached = copy(source) ?: return
    publish(detached)
}

class LegacyRemoteControlController(
    context: Context,
    private val logger: DiagnosticLogger,
    private val positionProvider: () -> Long,
    private val onSeekRequested: (Long) -> Unit,
    // Shared process-wide loader (AppContainer); this class must not shut it down.
    private val artworkLoader: AlbumArtworkLoader
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val receiverComponent = ComponentName(appContext, MediaButtonReceiver::class.java)
    private val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiverComponent)
    private val pendingIntent = PendingIntent.getBroadcast(appContext, 0, mediaButtonIntent, PendingIntent.FLAG_UPDATE_CURRENT)
    private val remoteControlClient = RemoteControlClient(pendingIntent)
    private var registered = false
    private var lastMetadataTrackId: Long? = null
    private var lastMetadataDurationMs = -1L
    @Volatile private var artworkPath: String? = null

    init {
        remoteControlClient.setTransportControlFlags(
            RemoteControlClient.FLAG_KEY_MEDIA_PLAY or
                RemoteControlClient.FLAG_KEY_MEDIA_PAUSE or
                RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE or
                RemoteControlClient.FLAG_KEY_MEDIA_STOP or
                RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS or
                RemoteControlClient.FLAG_KEY_MEDIA_NEXT or
                RemoteControlClient.FLAG_KEY_MEDIA_POSITION_UPDATE
        )
        remoteControlClient.setPlaybackPositionUpdateListener { position ->
            onSeekRequested(position.coerceAtLeast(0))
        }
        remoteControlClient.setOnGetPlaybackPositionListener { positionProvider() }
        register()
    }

    fun update(snapshot: PlaybackSnapshot, track: Track?) {
        if (!registered) register()
        if (track?.id != lastMetadataTrackId || snapshot.durationMs != lastMetadataDurationMs) {
            lastMetadataTrackId = track?.id
            lastMetadataDurationMs = snapshot.durationMs
            val editor = remoteControlClient.editMetadata(true)
            editor.putString(MediaMetadataRetriever.METADATA_KEY_TITLE, track?.title ?: "Y2 Player")
            editor.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, track?.displayArtist ?: "")
            editor.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, track?.displayAlbum ?: "")
            editor.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, snapshot.durationMs)
            editor.apply()
        }
        val path = track?.absolutePath
        if (path != null && path != artworkPath) {
            artworkPath = path
            artworkLoader.load(path, REMOTE_ARTWORK_SIZE_PX) { loadedPath, bitmap ->
                if (loadedPath == artworkPath && bitmap != null) {
                    runCatching {
                        publishDetachedArtwork(
                            source = bitmap,
                            copy = { source -> source.copy(source.config ?: Bitmap.Config.RGB_565, false) }
                        ) { remoteArtwork ->
                            remoteControlClient.editMetadata(false)
                                .putBitmap(RemoteControlClient.MetadataEditor.BITMAP_KEY_ARTWORK, remoteArtwork)
                                .apply()
                        }
                    }.onFailure { logger.warn("RemoteControl", "artwork update failed: ${it.message}") }
                }
            }
        } else if (path == null) {
            artworkPath = null
        }
        remoteControlClient.setPlaybackState(
            when (snapshot.status) {
                PlaybackStatus.PLAYING -> RemoteControlClient.PLAYSTATE_PLAYING
                PlaybackStatus.PREPARING -> RemoteControlClient.PLAYSTATE_BUFFERING
                PlaybackStatus.PAUSED -> RemoteControlClient.PLAYSTATE_PAUSED
                PlaybackStatus.ERROR -> RemoteControlClient.PLAYSTATE_ERROR
                PlaybackStatus.IDLE -> RemoteControlClient.PLAYSTATE_STOPPED
            },
            snapshot.positionMs,
            1f
        )
    }

    fun release() {
        artworkPath = null
        if (registered) runCatching { audioManager.unregisterRemoteControlClient(remoteControlClient) }
        registered = false
    }

    private fun register() {
        runCatching {
            audioManager.registerRemoteControlClient(remoteControlClient)
            registered = true
        }.onFailure {
            logger.warn("RemoteControl", "registration failed: ${it.message}")
        }
    }

    companion object {
        // Kept in sync with Y2PlayerView.SHARED_ARTWORK_SIZE_PX so both consumers hit
        // the same cache entry.
        private const val REMOTE_ARTWORK_SIZE_PX = 256
    }
}
