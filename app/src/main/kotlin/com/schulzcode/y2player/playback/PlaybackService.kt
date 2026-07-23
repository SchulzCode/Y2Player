package com.schulzcode.y2player.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.schulzcode.y2player.R
import com.schulzcode.y2player.Y2Application
import com.schulzcode.y2player.core.model.AudioEffectsState
import com.schulzcode.y2player.core.model.AudioOutputRouteResolver
import com.schulzcode.y2player.core.model.AudioQualityMode
import com.schulzcode.y2player.core.model.DacState
import com.schulzcode.y2player.core.model.PauseReason
import com.schulzcode.y2player.core.model.PlaybackSnapshot
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.model.RepeatMode
import com.schulzcode.y2player.core.model.SleepTimerMode
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.core.state.DeviceState
import com.schulzcode.y2player.core.state.PlayerPreferencesState
import com.schulzcode.y2player.diagnostics.DiagnosticLogger
import com.schulzcode.y2player.input.HardwareKeyGate
import com.schulzcode.y2player.library.LibraryDatabase
import com.schulzcode.y2player.library.LibraryRepository
import com.schulzcode.y2player.queue.QueueController
import com.schulzcode.y2player.safe.SafeModeManager
import com.schulzcode.y2player.settings.AppPreferences
import com.schulzcode.y2player.storage.StorageMonitor
import com.schulzcode.y2player.storage.Y2StoragePaths
import com.schulzcode.y2player.ui.MainActivity
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class PlaybackService : Service(), PlaybackEngine.Listener, AudioFocusController.Callback {
    fun interface Listener { fun onPlaybackChanged(snapshot: PlaybackSnapshot) }

    inner class LocalBinder : Binder() {
        fun addListener(listener: Listener) = this@PlaybackService.addListener(listener)
        fun removeListener(listener: Listener) = this@PlaybackService.removeListener(listener)
        fun snapshot(): PlaybackSnapshot = snapshot

        fun playCollection(trackIds: List<Long>, startIndex: Int) = post {
            beginExplicitPlaybackRequest()
            queue.replace(trackIds, startIndex)
            currentRetryCount = 0
            consecutiveErrors = 0
            prepareCurrent(autoPlay = true, positionMs = 0)
        }

        /** Shuffle All: random start track, shuffle enabled with a fresh seed. */
        fun playCollectionShuffled(trackIds: List<Long>) = post {
            if (trackIds.isEmpty()) return@post
            beginExplicitPlaybackRequest()
            queue.replace(trackIds, startIndex = (Math.random() * trackIds.size).toInt().coerceIn(0, trackIds.size - 1))
            if (!queue.snapshot().shuffleEnabled) queue.toggleShuffle()
            currentRetryCount = 0
            consecutiveErrors = 0
            prepareCurrent(autoPlay = true, positionMs = 0)
        }

        fun playQueueIndex(index: Int) = post {
            if (queue.moveToQueueIndex(index) != null) {
                beginExplicitPlaybackRequest()
                currentRetryCount = 0
                consecutiveErrors = 0
                prepareCurrent(autoPlay = true, positionMs = 0)
            }
        }

        fun togglePlayback() = post(::togglePlaybackInternal)
        fun next() = post { nextInternal(userInitiated = true) }
        fun previous() = post(::previousInternal)
        fun seekBy(deltaMs: Long) = post { seekByInternal(deltaMs) }
        fun removeQueueIndex(index: Int) = post { removeQueueIndexInternal(index) }

        fun moveQueueItem(index: Int, delta: Int) = post {
            if (queue.moveItem(index, delta)) {
                refreshSnapshot()
                persistQueueState()
                refreshPreload()
                publishSnapshot()
            }
        }

        fun clearUpcoming() = post {
            queue.clearUpcoming()
            refreshSnapshot()
            persistQueueState()
            refreshPreload()
            publishSnapshot()
        }

        fun clearQueue() = post(::clearQueueInternal)

        /**
         * USB hand-off barrier. Unlike [clearQueue], this does not return until
         * the playback thread has cancelled preparation, released both player
         * slots and persisted the empty queue. It is called from the UI's
         * bounded background worker, never from the main thread.
         */
        fun playNext(trackId: Long) = post {
            queue.addNext(trackId)
            refreshSnapshot()
            persistQueueState()
            refreshPreload()
            publishSnapshot()
        }

        fun addToQueue(trackId: Long) = post {
            queue.append(trackId)
            refreshSnapshot()
            persistQueueState()
            refreshPreload()
            publishSnapshot()
        }

        fun toggleShuffle() = post {
            queue.toggleShuffle()
            refreshSnapshot()
            persistQueueState()
            refreshPreload()
            publishSnapshot()
        }

        fun cycleRepeat() = post {
            queue.cycleRepeat()
            refreshSnapshot()
            persistQueueState()
            refreshPreload()
            publishSnapshot()
        }

        fun cycleSleepTimer() = post(::cycleSleepTimerInternal)

        fun applyPreferences(value: PlayerPreferencesState) = post {
            val previousGain = appVolumeGain()
            val effective = runtimePreferences(value)
            val modeChanged = value.audioQualityMode != requestedPreferences.audioQualityMode
            val transitionChanged = effective.gaplessEnabled != currentPreferences.gaplessEnabled ||
                effective.crossfadeMs != currentPreferences.crossfadeMs
            if (modeChanged || transitionChanged) clearPreload()
            requestedPreferences = value
            currentPreferences = effective
            // A volume step must be audible immediately. Skipped while a fade is
            // running: that fade owns the volume ramp, and cancelling it would
            // drop the completion callback that actually pauses the engine. The
            // fade's own terminal setOutputVolume recomputes the gain anyway.
            if (appVolumeGain() != previousGain && !fadeInProgress) setOutputVolume(effectiveVolume())
            dacController.applyDirectMode(value.audioQualityMode == AudioQualityMode.DIRECT_DAC)
            audioEffectsState = audioEffectsController.apply(effective)
            if (modeChanged || transitionChanged) refreshPreload()
            refreshSnapshot()
            publishSnapshot()
        }

        fun reconcileAvailability(availableTrackIds: Set<Long>) = post {
            reconcileAvailabilityInternal(availableTrackIds)
        }

        fun setSafeMode(enabled: Boolean) = post {
            if (enabled) enterSafeModeInternal() else exitSafeModeInternal()
        }
    }

    private data class NotificationKey(
        val trackId: Long?,
        val status: PlaybackStatus,
        val title: String?,
        val artist: String?
    )

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val persistenceExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
        { runnable -> Thread(runnable, "y2-playback-state").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )
    private lateinit var playbackThread: HandlerThread
    private lateinit var playbackHandler: Handler
    private lateinit var engine: PlaybackEngine
    private lateinit var audioFocus: AudioFocusController
    private lateinit var audioEffectsController: AudioEffectsController
    private lateinit var dacController: DacController
    private lateinit var remoteControl: LegacyRemoteControlController
    private lateinit var database: LibraryDatabase
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var queue: QueueController
    private lateinit var audioManager: AudioManager
    private lateinit var preferences: AppPreferences
    private lateinit var safeModeManager: SafeModeManager
    private lateinit var logger: DiagnosticLogger
    private lateinit var mediaButtonComponent: ComponentName
    private lateinit var storageMonitor: StorageMonitor
    private lateinit var routeMonitor: AudioRouteMonitor

    @Volatile private var snapshot = PlaybackSnapshot()
    @Volatile private var currentTrack: Track? = null
    @Volatile private var shuttingDown = false
    @Volatile private var boundClients = 0
    private var requestedPreferences = PlayerPreferencesState()
    private var currentPreferences = PlayerPreferencesState()
    private var audioEffectsState = AudioEffectsState()
    private var pendingPositionMs = 0L
    private var pendingAutoPlay = false
    private var duckedForFocus = false
    /** True only while a volume ramp is running on the playback thread. */
    private var fadeInProgress = false
    private var currentRetryCount = 0
    private var preloadRetryCount = 0
    private var consecutiveErrors = 0
    private var lastPersistedPositionBucket = -1L
    private var lastPublishedProgressSecond = -1L
    private var currentPreparationRecorded = false
    private var requestCounter = 0L
    private var activeRequestId = 0L
    private var preloadedRequestId: Long? = null
    private var preloadedTrack: Track? = null
    // Written on the playback thread (start/transition paths) and on the main
    // thread (updateNotificationIfNeeded). The race is accepted: the worst
    // outcome is one redundant notify() with identical content.
    private var lastNotificationKey: NotificationKey? = null
    @Volatile private var lastPersistedQueueItems: List<Long>? = null
    private var queuePersistScheduled = false
    private val queuePersistRunnable = Runnable {
        queuePersistScheduled = false
        flushQueuePersist()
    }
    private var currentWatchdogRequest: Long? = null
    private var nextWatchdogRequest: Long? = null
    private var autoPreloadAttemptedForRequest = 0L
    private var fadeGeneration = 0L
    private var outputVolume = 1f
    private var sleepTimerMode = SleepTimerMode.OFF
    private var sleepTimerDeadlineElapsed: Long? = null
    private var sleepTimerCallback: Runnable? = null
    private val sleepTimerGeneration = GenerationGuard()
    private val safetyPolicy = PlaybackSafetyPolicy()

    private val storageListener = StorageMonitor.Listener { device ->
        post { reconcileStorageSnapshot(device) }
    }

    override fun onCreate() {
        super.onCreate()
        val container = (application as Y2Application).container
        database = container.database
        libraryRepository = container.libraryRepository
        preferences = container.preferences
        requestedPreferences = preferences.snapshot()
        currentPreferences = runtimePreferences(requestedPreferences)
        safeModeManager = container.safeModeManager
        logger = container.logger
        dacController = DacController(this, logger)
        dacController.applyDirectMode(requestedPreferences.audioQualityMode == AudioQualityMode.DIRECT_DAC)
        storageMonitor = container.storageMonitor
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mediaButtonComponent = ComponentName(this, MediaButtonReceiver::class.java)

        playbackThread = HandlerThread("y2-playback").apply { start() }
        playbackHandler = Handler(playbackThread.looper)
        routeMonitor = AudioRouteMonitor(this) { event -> post { handleRouteEvent(event) } }
        routeMonitor.start()
        audioManager.registerMediaButtonEventReceiver(mediaButtonComponent)
        storageMonitor.addListener(storageListener)

        post {
            engine = runCatching<PlaybackEngine> { AndroidMediaPlayerEngine(this, logger) }
                .onFailure { logger.error("Playback", "MediaPlayer engine initialization failed", it) }
                .getOrElse { UnavailablePlaybackEngine("MediaPlayer is unavailable") }
                .also { it.setListener(this) }
            audioFocus = AudioFocusController(this, this)
            audioEffectsController = AudioEffectsController(this, engine.audioSessionId, logger)
            audioEffectsState = audioEffectsController.apply(currentPreferences)
            remoteControl = LegacyRemoteControlController(
                context = this,
                logger = logger,
                positionProvider = { snapshot.positionMs },
                onSeekRequested = { position -> post { seekAbsoluteInternal(position) } },
                artworkLoader = container.artworkLoader
            )
            queue = QueueController()
            restorePersistedState(skipQueue = safeModeManager.isSafeMode())
            publishSnapshot()
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        boundClients += 1
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClients = (boundClients - 1).coerceAtLeast(0)
        post(::stopSelfIfIdle)
        // Returning true makes Android call onRebind for later clients, keeping
        // boundClients accurate when an activity is recreated.
        return true
    }

    override fun onRebind(intent: Intent?) {
        boundClients += 1
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_MEDIA_BUTTON) {
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (event?.action == KeyEvent.ACTION_UP && HardwareKeyGate.isInputAllowed(this, event.keyCode)) {
                handleMediaKey(event.keyCode)
            }
        }
        return if (snapshot.status == PlaybackStatus.PLAYING || snapshot.status == PlaybackStatus.PREPARING) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        shuttingDown = true
        if (::routeMonitor.isInitialized) routeMonitor.stop()
        runCatching { audioManager.unregisterMediaButtonEventReceiver(mediaButtonComponent) }
        if (::storageMonitor.isInitialized) storageMonitor.removeListener(storageListener)
        mainHandler.removeCallbacksAndMessages(null)

        if (::playbackHandler.isInitialized) {
            playbackHandler.removeCallbacksAndMessages(null)
            persistenceExecutor.queue.clear()
            val cleanup = Runnable {
                if (::queue.isInitialized) runCatching {
                    // Flush any debounced queue write synchronously into the executor
                    // before it shuts down; otherwise up to QUEUE_PERSIST_DEBOUNCE_MS
                    // of queue edits would be lost at service destruction.
                    playbackHandler.removeCallbacks(queuePersistRunnable)
                    queuePersistScheduled = false
                    if (queue.snapshot().items === lastPersistedQueueItems) persistSession() else flushQueuePersist()
                }
                if (::audioFocus.isInitialized) audioFocus.abandon()
                if (::remoteControl.isInitialized) remoteControl.release()
                if (::audioEffectsController.isInitialized) audioEffectsController.release()
                if (::engine.isInitialized) engine.release()
            }
            if (Looper.myLooper() == playbackThread.looper) {
                cleanup.run()
            } else {
                val completed = CountDownLatch(1)
                val posted = playbackHandler.post {
                    try { cleanup.run() } finally { completed.countDown() }
                }
                if (posted) runCatching { completed.await(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            }
            playbackThread.quitSafely()
        }
        persistenceExecutor.shutdown()
        val persisted = runCatching {
            persistenceExecutor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!persisted) persistenceExecutor.shutdownNow()
        if (::dacController.isInitialized) dacController.applyDirectMode(false)
        logger.info("Playback", "service destroyed")
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        post {
            cancelVolumeFade()
            clearPreload()
            logger.warn("Playback", "low memory: secondary player and transitions released")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // TRIM levels are not ordered by memory pressure: TRIM_MEMORY_UI_HIDDEN (20)
        // only means no UI is visible and fires with zero pressure, yet it is numerically
        // above TRIM_MEMORY_RUNNING_LOW (10). Releasing the preloaded next player there
        // would break gapless during background listening, the primary use case.
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            post {
                cancelVolumeFade()
                clearPreload()
                logger.warn("Playback", "trim memory level=$level: secondary player released")
            }
        }
    }

    override fun onPrepared(requestId: Long, durationMs: Long) = post {
        if (!PlaybackRequestGate.accepts(requestId, activeRequestId)) return@post
        cancelCurrentWatchdog(requestId)
        if (durationMs <= 0) {
            engine.cancel()
            onError(requestId, "Track has no playable duration")
            return@post
        }
        val requestedPosition = normalizedResumePosition(pendingPositionMs, durationMs)
        if (requestedPosition > 0) engine.seekTo(requestedPosition)
        val wantedAutoPlay = pendingAutoPlay
        if (wantedAutoPlay && safetyPolicy.onRoutesChanged(
                routeMonitor.snapshot(),
                becomingNoisy = false,
                speakerFallbackAllowed = !currentPreferences.pauseOnDisconnect
            )
        ) {
            handlePrivateRouteLoss("route changed while preparing")
            return@post
        }
        val focusGranted = !wantedAutoPlay || audioFocus.request()
        val playRequested = wantedAutoPlay && focusGranted && safetyPolicy.canAutomaticallyStart()
        if (playRequested) startEngineWithFade()
        val started = playRequested && engine.state == EngineState.PLAYING
        if (started) {
            startForeground(NOTIFICATION_ID, createNotification())
            lastNotificationKey = notificationKey()
            scheduleProgress()
            recordCurrentPlaybackStart()
        } else {
            if (playRequested) {
                audioFocus.abandon()
                logger.warn("Playback", "player did not enter PLAYING after prepare")
            }
        }
        snapshot = buildSnapshot(
            status = if (started) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
            positionMs = requestedPosition,
            durationMs = durationMs,
            pauseReason = when {
                started -> PauseReason.NONE
                wantedAutoPlay && !focusGranted -> PauseReason.AUDIO_FOCUS
                wantedAutoPlay && !safetyPolicy.canAutomaticallyStart() -> PauseReason.OUTPUT_DISCONNECTED
                else -> PauseReason.USER
            },
            errorMessage = when {
                wantedAutoPlay && !focusGranted -> "Audio focus is unavailable"
                wantedAutoPlay && !safetyPolicy.canAutomaticallyStart() -> ROUTE_LOSS_MESSAGE
                else -> null
            }
        )
        currentTrack?.let { logger.info("Playback", "prepared request=$requestId track=${it.id} ${it.title}") }
        pendingPositionMs = 0
        pendingAutoPlay = false
        persistSession()
        refreshPreload()
        publishSnapshot()
    }

    override fun onNextPrepared(requestId: Long, durationMs: Long) = post {
        if (!PlaybackRequestGate.accepts(requestId, preloadedRequestId ?: 0)) return@post
        cancelNextWatchdog(requestId)
        logger.info("Playback", "next ready request=$requestId duration=$durationMs track=${preloadedTrack?.id}")
        refreshSnapshot()
        publishSnapshot()
    }

    override fun onTransitioned(requestId: Long, durationMs: Long) {
        if (::playbackThread.isInitialized && Looper.myLooper() == playbackThread.looper) {
            handleTransitioned(requestId, durationMs)
        } else post { handleTransitioned(requestId, durationMs) }
    }

    private fun handleTransitioned(requestId: Long, durationMs: Long) {
        if (!PlaybackRequestGate.accepts(requestId, preloadedRequestId ?: 0)) {
            logger.warn("Playback", "ignored stale transition request=$requestId expected=$preloadedRequestId")
            return
        }
        cancelNextWatchdog(requestId)
        val expectedTrack = preloadedTrack ?: return
        val advanced = when (sleepTimerMode) {
            SleepTimerMode.END_ALBUM, SleepTimerMode.END_QUEUE -> queue.nextInCurrentPass()
            else -> queue.next()
        }
        if (advanced != expectedTrack.id) {
            logger.warn("Playback", "queue transition mismatch expected=${expectedTrack.id} actual=$advanced")
            queue.snapshot().items.indexOf(expectedTrack.id).takeIf { it >= 0 }?.let(queue::moveToQueueIndex)
        }
        currentTrack = expectedTrack
        activeRequestId = requestId
        preloadedRequestId = null
        preloadedTrack = null
        currentPreparationRecorded = false
        currentRetryCount = 0
        recordCurrentPlaybackStart()
        startForeground(NOTIFICATION_ID, createNotification())
        lastNotificationKey = notificationKey()
        snapshot = buildSnapshot(
            status = PlaybackStatus.PLAYING,
            positionMs = engine.currentPositionMs(),
            durationMs = durationMs,
            pauseReason = PauseReason.NONE
        )
        persistSession(positionOverride = snapshot.positionMs)
        refreshPreload()
        scheduleProgress()
        publishSnapshot()
    }

    override fun onCompleted(requestId: Long) = post {
        if (!PlaybackRequestGate.accepts(requestId, activeRequestId) || snapshot.status != PlaybackStatus.PLAYING) return@post
        if (shouldStopAfterCurrentTrack()) {
            stopForSleepTimer()
            return@post
        }
        val preloaded = preloadedRequestId
        if (preloaded != null && engine.hasPreparedNext(preloaded) && engine.startPreparedNext(0)) return@post
        nextInternal(userInitiated = false)
    }

    override fun onError(requestId: Long, message: String) = post {
        if (!PlaybackRequestGate.accepts(requestId, activeRequestId)) return@post
        cancelCurrentWatchdog(requestId)
        val track = currentTrack
        val shouldAutoPlay = pendingAutoPlay || snapshot.status in ACTIVE_STATUSES
        logger.error(
            "Playback",
            "track=${track?.id} path=${track?.absolutePath} format=${track?.extension} retry=$currentRetryCount error=$message"
        )
        if (currentRetryCount < MAX_TRACK_RETRIES && track?.let(::resolvePlayableTrack) != null) {
            currentRetryCount += 1
            logger.warn("Playback", "retrying track=${track.id} attempt=$currentRetryCount")
            prepareCurrent(shouldAutoPlay, snapshot.positionMs, preserveRetry = true)
            return@post
        }

        consecutiveErrors += 1
        if (consecutiveErrors < queue.snapshot().items.size.coerceAtLeast(1) && moveToNextAvailable(ignoreRepeatOne = true)) {
            currentRetryCount = 0
            prepareCurrent(autoPlay = shouldAutoPlay && safetyPolicy.canAutomaticallyStart(), positionMs = 0)
        } else {
            stopForeground(true)
            lastNotificationKey = null
            snapshot = buildSnapshot(PlaybackStatus.ERROR, 0, 0, PauseReason.PLAYBACK_ERROR, message)
            persistSession(positionOverride = 0)
            publishSnapshot()
        }
    }

    override fun onNextError(requestId: Long, message: String) = post {
        if (!PlaybackRequestGate.accepts(requestId, preloadedRequestId ?: 0)) return@post
        cancelNextWatchdog(requestId)
        val failedTrack = preloadedTrack
        logger.warn("Playback", "preload track=${failedTrack?.id} retry=$preloadRetryCount error=$message")
        if (preloadRetryCount < MAX_TRACK_RETRIES && failedTrack?.let(::resolvePlayableTrack) != null) {
            preloadRetryCount += 1
            preloadTrack(failedTrack)
        } else {
            clearPreload()
            refreshSnapshot()
            publishSnapshot()
        }
    }

    override fun onPermanentLoss() = post {
        safetyPolicy.onPermanentFocusLoss()
        duckedForFocus = false
        if (snapshot.status in ACTIVE_STATUSES) pauseInternal(PauseReason.AUDIO_FOCUS, abandonFocus = false, useFade = false)
    }

    override fun onTransientLoss() = post {
        val wasPlaying = engine.isPlaying()
        safetyPolicy.onTransientFocusLoss(wasPlaying)
        duckedForFocus = false
        if (snapshot.status in ACTIVE_STATUSES) pauseInternal(PauseReason.AUDIO_FOCUS, abandonFocus = false, useFade = false)
    }

    override fun onDuck() = post {
        if (!engine.isPlaying()) return@post
        if (currentPreferences.duckOnFocusLoss) {
            duckedForFocus = true
            fadeToVolume(effectiveVolume(), SHORT_FOCUS_FADE_MS)
        } else {
            safetyPolicy.onTransientFocusLoss(wasPlaying = true)
            pauseInternal(PauseReason.AUDIO_FOCUS, abandonFocus = false, useFade = false)
        }
    }

    override fun onGain() = post {
        val wasDucked = duckedForFocus
        duckedForFocus = false
        if (wasDucked) fadeToVolume(effectiveVolume(), SHORT_FOCUS_FADE_MS)
        if (safetyPolicy.consumeFocusResume() && snapshot.status == PlaybackStatus.PAUSED) {
            startInternal()
        }
    }

    private fun handleMediaKey(keyCode: Int) {
        logger.info("MediaButton", "keyCode=$keyCode")
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> post {
                if (snapshot.status != PlaybackStatus.PLAYING) togglePlaybackInternal()
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> post {
                if (snapshot.status in ACTIVE_STATUSES) pauseInternal(PauseReason.USER, useFade = false)
                else {
                    safetyPolicy.onManualPause()
                    duckedForFocus = false
                    audioFocus.abandon()
                    stopSelfIfIdle()
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> post(::togglePlaybackInternal)
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> post { nextInternal(userInitiated = true) }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_DPAD_LEFT -> post(::previousInternal)
            KeyEvent.KEYCODE_MEDIA_STOP -> post { pauseInternal(PauseReason.USER) }
            KeyEvent.KEYCODE_MEDIA_REWIND -> post { seekByInternal(-currentPreferences.longSeekStepMs.toLong()) }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> post { seekByInternal(currentPreferences.longSeekStepMs.toLong()) }
        }
    }

    private fun togglePlaybackInternal() {
        when (snapshot.status) {
            PlaybackStatus.PLAYING -> pauseInternal(PauseReason.USER)
            PlaybackStatus.PREPARING -> pauseInternal(PauseReason.USER)
            PlaybackStatus.PAUSED -> {
                beginExplicitPlaybackRequest()
                if (currentTrack == null) currentTrack = queue.currentTrackId()?.let(libraryRepository::findTrack)
                val track = currentTrack ?: return
                val playable = resolvePlayableTrack(track)
                if (playable == null) {
                    snapshot = snapshot.copy(errorMessage = "Track is unavailable", pauseReason = PauseReason.STORAGE_REMOVED)
                    publishSnapshot()
                    return
                }
                currentTrack = playable
                if (engine.state !in setOf(EngineState.READY, EngineState.PAUSED) || engine.durationMs() <= 0) {
                    prepareCurrent(autoPlay = true, positionMs = snapshot.positionMs)
                } else startInternal()
            }
            PlaybackStatus.IDLE, PlaybackStatus.ERROR -> {
                beginExplicitPlaybackRequest()
                if (queue.currentTrackId() == null && queue.snapshot().items.isNotEmpty()) queue.moveToQueueIndex(0)
                prepareCurrent(autoPlay = true, positionMs = snapshot.positionMs)
            }
        }
    }

    private fun startInternal() {
        if (!safetyPolicy.canAutomaticallyStart()) return
        if (!audioFocus.request()) {
            snapshot = buildSnapshot(
                PlaybackStatus.PAUSED,
                snapshot.positionMs,
                snapshot.durationMs,
                PauseReason.AUDIO_FOCUS,
                "Audio focus is unavailable"
            )
            publishSnapshot()
            return
        }
        startEngineWithFade()
        if (engine.state != EngineState.PLAYING) {
            audioFocus.abandon()
            snapshot = snapshot.copy(errorMessage = "Unable to start playback")
            publishSnapshot()
            return
        }
        recordCurrentPlaybackStart()
        startForeground(NOTIFICATION_ID, createNotification())
        lastNotificationKey = notificationKey()
        snapshot = buildSnapshot(PlaybackStatus.PLAYING, engine.currentPositionMs(), engine.durationMs(), PauseReason.NONE)
        scheduleProgress()
        refreshPreload()
        publishSnapshot()
    }

    private fun startEngineWithFade() {
        // Re-assert media-button ownership. On API 19 the last registrant wins,
        // so any other media app used since startup silently captures AVRCP
        // buttons (AirPods stem presses, car controls). One cheap AudioManager
        // call at every engine start returns them to this player exactly when
        // the user demonstrates intent to control it.
        runCatching { audioManager.registerMediaButtonEventReceiver(mediaButtonComponent) }
        cancelVolumeFade()
        val duration = currentPreferences.pauseResumeFadeMs.toLong()
        if (duration > 0) setOutputVolume(0f)
        else setOutputVolume(effectiveVolume())
        engine.start()
        if (engine.state == EngineState.PLAYING && duration > 0) fadeToVolume(effectiveVolume(), duration)
    }

    private fun pauseInternal(
        reason: PauseReason,
        abandonFocus: Boolean = true,
        useFade: Boolean = true
    ) {
        if (reason == PauseReason.USER) safetyPolicy.onManualPause()
        val position = when {
            snapshot.status == PlaybackStatus.PREPARING -> pendingPositionMs.takeIf { it > 0 } ?: snapshot.positionMs
            ::engine.isInitialized -> engine.currentPositionMs().takeIf { it > 0 } ?: snapshot.positionMs
            else -> snapshot.positionMs
        }
        val duration = if (::engine.isInitialized) engine.durationMs().takeIf { it > 0 } ?: snapshot.durationMs else snapshot.durationMs

        pendingAutoPlay = false
        if (abandonFocus) {
            duckedForFocus = false
        }
        playbackHandler.removeCallbacks(progressRunnable)
        if (snapshot.status == PlaybackStatus.PREPARING) {
            cancelActivePreparation()
        } else if (::engine.isInitialized) {
            if (engine.isTransitioning) clearPreload()
            val fadeDuration = if (useFade) currentPreferences.pauseResumeFadeMs.toLong() else 0L
            if (engine.isPlaying() && fadeDuration > 0) {
                fadeToVolume(0f, fadeDuration) {
                    engine.pause()
                    setOutputVolume(effectiveVolume())
                }
            } else {
                cancelVolumeFade()
                engine.pause()
                setOutputVolume(effectiveVolume())
            }
        }
        if (abandonFocus && ::audioFocus.isInitialized) audioFocus.abandon()
        stopForeground(true)
        lastNotificationKey = null
        snapshot = buildSnapshot(PlaybackStatus.PAUSED, position, duration, reason)
        persistSession(positionOverride = position)
        publishSnapshot()
        stopSelfIfIdle()
    }

    private fun seekByInternal(deltaMs: Long) {
        val duration = engine.durationMs().takeIf { it > 0 } ?: snapshot.durationMs
        if (duration <= 0) return
        val current = engine.currentPositionMs().takeIf { it > 0 } ?: snapshot.positionMs
        seekAbsoluteInternal((current + deltaMs).coerceIn(0, duration))
    }

    private fun seekAbsoluteInternal(positionMs: Long) {
        val duration = engine.durationMs().takeIf { it > 0 } ?: snapshot.durationMs
        if (duration <= 0) return
        val target = positionMs.coerceIn(0, duration)
        if (snapshot.status == PlaybackStatus.PREPARING) {
            pendingPositionMs = target
            snapshot = buildSnapshot(PlaybackStatus.PREPARING, target, duration, snapshot.pauseReason, snapshot.errorMessage)
            persistSession(positionOverride = target)
            publishSnapshot()
            return
        }
        if (engine.isTransitioning) {
            clearPreload()
            engine.pause()
            engine.start()
        }
        engine.seekTo(target)
        snapshot = buildSnapshot(snapshot.status, target, duration, snapshot.pauseReason, snapshot.errorMessage)
        persistSession(positionOverride = target)
        refreshPreload()
        publishSnapshot()
    }

    private fun previousInternal() {
        val threshold = currentPreferences.previousRestartThresholdMs.toLong()
        if (threshold > 0 && engine.currentPositionMs() > threshold) {
            seekAbsoluteInternal(0)
            return
        }
        if (queue.previousIgnoringRepeatOne() != null) {
            currentRetryCount = 0
            consecutiveErrors = 0
            prepareCurrent(snapshot.status == PlaybackStatus.PLAYING, 0)
        }
    }

    private fun nextInternal(userInitiated: Boolean) {
        if (userInitiated) beginExplicitPlaybackRequest()
        if (userInitiated) consecutiveErrors = 0
        val shouldAutoPlay = (userInitiated || snapshot.status == PlaybackStatus.PLAYING) && safetyPolicy.canAutomaticallyStart()
        val preloadRequest = preloadedRequestId
        if (preloadRequest != null && engine.hasPreparedNext(preloadRequest)) {
            if ((!shouldAutoPlay || audioFocus.request()) && engine.startPreparedNext(0)) return
        }
        val currentPassOnly = !userInitiated && sleepTimerMode in setOf(SleepTimerMode.END_ALBUM, SleepTimerMode.END_QUEUE)
        if (!moveToNextAvailable(ignoreRepeatOne = userInitiated, currentPassOnly = currentPassOnly)) {
            finishQueue(endedBySleepTimer = sleepTimerMode in setOf(SleepTimerMode.END_ALBUM, SleepTimerMode.END_QUEUE))
            return
        }
        currentRetryCount = 0
        prepareCurrent(shouldAutoPlay, 0)
    }

    private fun finishQueue(endedBySleepTimer: Boolean) {
        pendingAutoPlay = false
        safetyPolicy.onManualPause()
        duckedForFocus = false
        clearPreload()
        engine.pause()
        audioFocus.abandon()
        playbackHandler.removeCallbacks(progressRunnable)
        stopForeground(true)
        lastNotificationKey = null
        if (endedBySleepTimer) clearSleepTimer()
        snapshot = buildSnapshot(
            PlaybackStatus.PAUSED,
            snapshot.durationMs,
            snapshot.durationMs,
            if (endedBySleepTimer) PauseReason.SLEEP_TIMER else PauseReason.USER
        )
        persistSession(positionOverride = snapshot.durationMs)
        publishSnapshot()
    }

    private fun moveToNextAvailable(
        ignoreRepeatOne: Boolean = false,
        currentPassOnly: Boolean = false
    ): Boolean {
        val size = queue.snapshot().items.size
        repeat(size.coerceAtLeast(1)) {
            val next = when {
                currentPassOnly -> queue.nextInCurrentPass()
                ignoreRepeatOne -> queue.nextIgnoringRepeatOne()
                else -> queue.next()
            }
            if (next == null) return false
            val track = libraryRepository.findTrack(next)
            if (track?.let(::resolvePlayableTrack) != null) return true
            logger.warn("Playback", "skipping unavailable track=$next")
        }
        return false
    }

    private fun removeQueueIndexInternal(index: Int) {
        val oldId = queue.currentTrackId()
        queue.removeAt(index)
        val newId = queue.currentTrackId()
        if (oldId != newId) {
            if (newId == null) clearQueueInternal()
            else prepareCurrent(snapshot.status == PlaybackStatus.PLAYING, 0)
        } else {
            refreshSnapshot()
            persistQueueState()
            refreshPreload()
            publishSnapshot()
        }
    }

    private fun clearQueueInternal() {
        cancelActivePreparation()
        clearPreload()
        clearSleepTimer()
        safetyPolicy.onSessionCleared()
        duckedForFocus = false
        queue.clear()
        currentTrack = null
        pendingPositionMs = 0
        pendingAutoPlay = false
        if (::audioFocus.isInitialized) audioFocus.abandon()
        playbackHandler.removeCallbacks(progressRunnable)
        stopForeground(true)
        lastNotificationKey = null
        snapshot = PlaybackSnapshot(audioEffects = audioEffectsState, sleepTimerMode = sleepTimerMode)
        persistQueueState(positionOverride = 0)
        publishSnapshot()
    }

    private fun refreshSnapshot() {
        snapshot = buildSnapshot(snapshot.status, snapshot.positionMs, snapshot.durationMs, snapshot.pauseReason, snapshot.errorMessage)
    }

    private fun prepareCurrent(
        autoPlay: Boolean,
        positionMs: Long,
        preserveRetry: Boolean = false
    ) {
        val trackId = queue.currentTrackId()
        val indexedTrack = trackId?.let(libraryRepository::findTrack)
        val track = indexedTrack?.let(::resolvePlayableTrack)
        if (track == null) {
            clearPreload()
            pendingAutoPlay = false
            pendingPositionMs = 0
            currentTrack = indexedTrack
            if (::engine.isInitialized) engine.cancel()
            if (::audioFocus.isInitialized) audioFocus.abandon()
            playbackHandler.removeCallbacks(progressRunnable)
            stopForeground(true)
            lastNotificationKey = null
            snapshot = buildSnapshot(
                PlaybackStatus.ERROR,
                0,
                indexedTrack?.durationMs ?: 0,
                PauseReason.STORAGE_REMOVED,
                "Track is no longer available"
            )
            persistQueueState(positionOverride = 0)
            publishSnapshot()
            return
        }

        clearPreload()
        if (!preserveRetry) currentRetryCount = 0
        currentTrack = track
        currentPreparationRecorded = false
        pendingAutoPlay = autoPlay
        pendingPositionMs = positionMs.coerceAtLeast(0)
        activeRequestId = ++requestCounter
        snapshot = buildSnapshot(PlaybackStatus.PREPARING, pendingPositionMs, track.durationMs, PauseReason.NONE)
        persistQueueState(positionOverride = pendingPositionMs)
        publishSnapshot()
        engine.prepare(track, activeRequestId)
        scheduleCurrentWatchdog(activeRequestId)
    }

    private fun refreshPreload() {
        if (!::engine.isInitialized || currentTrack == null || snapshot.status !in PRELOADABLE_STATUSES ||
            queue.snapshot().repeatMode == RepeatMode.ONE
        ) {
            clearPreload()
            return
        }
        val nextId = when (sleepTimerMode) {
            SleepTimerMode.END_TRACK -> null
            SleepTimerMode.END_ALBUM, SleepTimerMode.END_QUEUE -> queue.peekNextInCurrentPass()
            else -> queue.peekNext()
        } ?: run { clearPreload(); return }
        val nextTrack = libraryRepository.findTrack(nextId)?.let(::resolvePlayableTrack) ?: run { clearPreload(); return }
        if (shouldStopBefore(nextTrack)) {
            clearPreload()
            return
        }
        if (preloadedTrack?.id == nextTrack.id && preloadedRequestId != null) return
        preloadRetryCount = 0
        preloadTrack(nextTrack)
    }

    private fun preloadTrack(track: Track) {
        clearPreload()
        preloadedTrack = track
        preloadedRequestId = ++requestCounter
        val requestId = preloadedRequestId ?: return
        engine.prepareNext(
            track,
            requestId,
            gapless = currentPreferences.gaplessEnabled && currentPreferences.crossfadeMs <= 0
        )
        scheduleNextWatchdog(requestId)
        refreshSnapshot()
    }

    private fun clearPreload() {
        nextWatchdogRequest = null
        if (::playbackHandler.isInitialized) playbackHandler.removeCallbacks(nextWatchdogRunnable)
        if (::engine.isInitialized) engine.clearNext()
        preloadedRequestId = null
        preloadedTrack = null
    }

    private fun cancelActivePreparation() {
        activeRequestId = ++requestCounter
        currentWatchdogRequest = null
        playbackHandler.removeCallbacks(currentWatchdogRunnable)
        pendingAutoPlay = false
        if (::engine.isInitialized) engine.cancel()
    }

    private fun recordCurrentPlaybackStart() {
        if (currentPreparationRecorded) return
        val track = currentTrack ?: return
        currentPreparationRecorded = true
        libraryRepository.recordRecentlyPlayed(track.id)
        logger.info("Playback", "playback started track=${track.id} ${track.title}")
    }

    private fun scheduleProgress() {
        playbackHandler.removeCallbacks(progressRunnable)
        playbackHandler.postDelayed(progressRunnable, progressInterval())
    }

    private fun progressInterval(remainingMs: Long? = null): Long {
        val crossfade = currentPreferences.crossfadeMs.toLong()
        return if (crossfade > 0 && remainingMs != null &&
            remainingMs <= crossfade + CROSSFADE_APPROACH_WINDOW_MS
        ) CROSSFADE_POLL_INTERVAL_MS else PROGRESS_INTERVAL_MS
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (snapshot.status != PlaybackStatus.PLAYING || !engine.isPlaying()) return
            val position = engine.currentPositionMs()
            val duration = engine.durationMs()
            if (consecutiveErrors > 0 && position >= STABLE_PLAYBACK_RESET_MS) consecutiveErrors = 0
            val remaining = (duration - position).coerceAtLeast(0)
            // If memory pressure released the preload (onTrimMemory/onLowMemory), restore it
            // once near the end of the track so gapless self-heals without pinning a second
            // player for the whole duration. Guarded to one attempt per active request so a
            // permanently failing preload cannot retry every tick.
            if (preloadedRequestId == null && !engine.isTransitioning &&
                autoPreloadAttemptedForRequest != activeRequestId &&
                remaining in 1..PRELOAD_RESTORE_WINDOW_MS
            ) {
                autoPreloadAttemptedForRequest = activeRequestId
                refreshPreload()
            }
            val crossfadeMs = currentPreferences.crossfadeMs.toLong()
            val preloadRequest = preloadedRequestId
            if (crossfadeMs > 0 && !engine.isTransitioning && preloadRequest != null &&
                engine.hasPreparedNext(preloadRequest) && remaining in 1..crossfadeMs
            ) {
                engine.startPreparedNext(remaining.coerceAtLeast(MIN_CROSSFADE_MS))
            }

            val progressSecond = position / 1_000L
            if (progressSecond != lastPublishedProgressSecond) {
                lastPublishedProgressSecond = progressSecond
                snapshot = buildSnapshot(PlaybackStatus.PLAYING, position, duration, PauseReason.NONE)
                publishSnapshot()
            }
            val bucket = position / POSITION_PERSIST_INTERVAL_MS
            if (bucket != lastPersistedPositionBucket) {
                lastPersistedPositionBucket = bucket
                submitPersistence("position persistence") {
                    database.updatePlaybackPosition(if (currentPreferences.resumePosition) position else 0)
                }
            }
            playbackHandler.postDelayed(this, progressInterval(remaining))
        }
    }

    private fun scheduleCurrentWatchdog(requestId: Long) {
        currentWatchdogRequest = requestId
        playbackHandler.removeCallbacks(currentWatchdogRunnable)
        playbackHandler.postDelayed(currentWatchdogRunnable, PREPARE_TIMEOUT_MS)
    }

    private fun cancelCurrentWatchdog(requestId: Long) {
        if (currentWatchdogRequest == requestId) {
            currentWatchdogRequest = null
            playbackHandler.removeCallbacks(currentWatchdogRunnable)
        }
    }

    private val currentWatchdogRunnable = Runnable {
        val requestId = currentWatchdogRequest ?: return@Runnable
        if (requestId == activeRequestId && snapshot.status == PlaybackStatus.PREPARING) {
            currentWatchdogRequest = null
            logger.error("Playback", "preparation timed out request=$requestId track=${currentTrack?.absolutePath}")
            engine.cancel()
            onError(requestId, "Preparation timed out")
        }
    }

    private fun scheduleNextWatchdog(requestId: Long) {
        nextWatchdogRequest = requestId
        playbackHandler.removeCallbacks(nextWatchdogRunnable)
        playbackHandler.postDelayed(nextWatchdogRunnable, PRELOAD_TIMEOUT_MS)
    }

    private fun cancelNextWatchdog(requestId: Long) {
        if (nextWatchdogRequest == requestId) {
            nextWatchdogRequest = null
            playbackHandler.removeCallbacks(nextWatchdogRunnable)
        }
    }

    private val nextWatchdogRunnable = Runnable {
        val requestId = nextWatchdogRequest ?: return@Runnable
        if (requestId == preloadedRequestId && !engine.hasPreparedNext(requestId)) {
            nextWatchdogRequest = null
            logger.warn("Playback", "preload timed out request=$requestId track=${preloadedTrack?.absolutePath}")
            onNextError(requestId, "Preload timed out")
        }
    }

    private fun reconcileAvailabilityInternal(availableTrackIds: Set<Long>) {
        val currentId = queue.currentTrackId()
        if (currentId != null && currentId !in availableTrackIds && snapshot.status in ACTIVE_STATUSES) {
            releaseStorageSource()
        }
        if (preloadedTrack?.id?.let { it !in availableTrackIds } == true) clearPreload()
    }

    private fun reconcileStorageSnapshot(device: DeviceState) {
        val track = currentTrack ?: return
        val volume = device.storageVolumes.firstOrNull { it.id == track.volumeId }
        if ((volume?.available == false || resolvePlayableTrack(track) == null) && snapshot.status in ACTIVE_STATUSES) {
            releaseStorageSource()
        }
        preloadedTrack?.let { nextTrack ->
            val nextVolume = device.storageVolumes.firstOrNull { it.id == nextTrack.volumeId }
            if (nextVolume?.available == false || resolvePlayableTrack(nextTrack) == null) clearPreload()
        }
    }

    /**
     * A stock-UMS unmount must close the MediaPlayer data source, not merely
     * pause it. Keeping the queue and position allows an explicit user resume
     * after remount, while cancelling the engine releases every media file
     * descriptor before the stock storage transition removes the volume.
     */
    private fun releaseStorageSource() {
        val position = when {
            snapshot.status == PlaybackStatus.PREPARING -> pendingPositionMs.takeIf { it > 0 } ?: snapshot.positionMs
            ::engine.isInitialized -> engine.currentPositionMs().takeIf { it > 0 } ?: snapshot.positionMs
            else -> snapshot.positionMs
        }
        val duration = if (::engine.isInitialized) {
            engine.durationMs().takeIf { it > 0 } ?: snapshot.durationMs
        } else snapshot.durationMs
        cancelVolumeFade()
        cancelActivePreparation()
        clearPreload()
        playbackHandler.removeCallbacks(progressRunnable)
        duckedForFocus = false
        if (::audioFocus.isInitialized) audioFocus.abandon()
        stopForeground(true)
        lastNotificationKey = null
        snapshot = buildSnapshot(
            PlaybackStatus.PAUSED,
            position,
            duration,
            PauseReason.STORAGE_REMOVED,
            "Current storage was removed"
        )
        persistSession(positionOverride = position)
        logger.warn("Playback", "source released after storage loss track=${currentTrack?.id} volume=${currentTrack?.volumeId}")
        publishSnapshot()
        stopSelfIfIdle()
    }

    private fun enterSafeModeInternal() {
        cancelActivePreparation()
        clearPreload()
        clearSleepTimer()
        safetyPolicy.onSessionCleared()
        duckedForFocus = false
        playbackHandler.removeCallbacks(progressRunnable)
        if (::audioFocus.isInitialized) audioFocus.abandon()
        stopForeground(true)
        lastNotificationKey = null
        queue.restore(emptyList(), null)
        currentTrack = null
        snapshot = PlaybackSnapshot(
            status = PlaybackStatus.PAUSED,
            pauseReason = PauseReason.SAFE_MODE,
            audioEffects = audioEffectsState,
            sleepTimerMode = sleepTimerMode
        )
        publishSnapshot()
    }

    private fun exitSafeModeInternal() {
        restorePersistedState(skipQueue = false)
        publishSnapshot()
    }

    private fun restorePersistedState(skipQueue: Boolean) {
        if (skipQueue) {
            queue.restore(emptyList(), null)
            currentTrack = null
            snapshot = PlaybackSnapshot(
                status = PlaybackStatus.PAUSED,
                pauseReason = PauseReason.SAFE_MODE,
                audioEffects = audioEffectsState
            )
            logger.warn("Playback", "safe mode: queue restore skipped")
            return
        }

        val persistedQueue = runCatching { database.loadQueue() }.onFailure {
            logger.error("Playback", "queue restore failed; starting with an empty queue", it)
        }.getOrDefault(emptyList())
        val persistedSession = runCatching { database.loadPlaybackSession() }.onFailure {
            logger.error("Playback", "session restore failed; restoring paused", it)
        }.getOrNull()
        queue.restore(persistedQueue, persistedSession)
        val validTrackIds = runCatching { database.validTrackIds(persistedQueue) }.onFailure {
            logger.error("Playback", "queue validation failed; removing unsafe references", it)
        }.getOrDefault(emptySet())
        queue.retainKnown(validTrackIds)
        if (queue.currentTrackId() == null && queue.snapshot().items.isNotEmpty()) queue.moveToQueueIndex(0)
        lastPersistedQueueItems = null
        val trackId = queue.currentTrackId()
        currentTrack = trackId?.let(libraryRepository::findTrack)
        if (currentTrack != null) safetyPolicy.onRestoredPausedSession(routeMonitor.snapshot())
        else safetyPolicy.onSessionCleared()
        val duration = currentTrack?.durationMs ?: 0
        val savedPosition = if (currentPreferences.resumePosition) {
            normalizedResumePosition(persistedSession?.positionMs ?: 0, duration)
        } else 0
        snapshot = buildSnapshot(
            status = if (trackId != null) PlaybackStatus.PAUSED else PlaybackStatus.IDLE,
            positionMs = savedPosition,
            durationMs = duration,
            pauseReason = PauseReason.USER
        )
        persistQueueState(positionOverride = savedPosition)
    }

    private fun normalizedResumePosition(positionMs: Long, durationMs: Long): Long {
        return PlaybackPositionPolicy.clampRestored(positionMs, durationMs)
    }

    // Scheduling intentionally uses Handler.postDelayed (uptime clock), not AlarmManager:
    // while playing, the MediaPlayer partial wakelock keeps the CPU awake so uptime
    // tracks elapsed time. If the user pauses with a timer armed and the SoC deep-sleeps,
    // the callback fires late — but its only job is to pause, so late-while-paused is
    // harmless, and the elapsedRealtime deadline check keeps it correct on wake.
    private fun cycleSleepTimerInternal() {
        sleepTimerMode = sleepTimerMode.next()
        sleepTimerDeadlineElapsed = sleepTimerMode.durationMs?.let { SystemClock.elapsedRealtime() + it }
        sleepTimerCallback?.let(playbackHandler::removeCallbacks)
        val generation = sleepTimerGeneration.advance()
        sleepTimerDeadlineElapsed?.let { deadline ->
            val callback = Runnable {
                if (sleepTimerGeneration.isCurrent(generation) && sleepTimerDeadlineElapsed != null &&
                    SystemClock.elapsedRealtime() >= sleepTimerDeadlineElapsed!!
                ) {
                    clearSleepTimer()
                    pauseInternal(PauseReason.SLEEP_TIMER, useFade = true)
                }
            }
            sleepTimerCallback = callback
            playbackHandler.postDelayed(callback, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        }
        refreshPreload()
        refreshSnapshot()
        publishSnapshot()
    }

    private fun clearSleepTimer() {
        sleepTimerGeneration.advance()
        sleepTimerMode = SleepTimerMode.OFF
        sleepTimerDeadlineElapsed = null
        sleepTimerCallback?.let { if (::playbackHandler.isInitialized) playbackHandler.removeCallbacks(it) }
        sleepTimerCallback = null
    }

    private fun shouldStopAfterCurrentTrack(): Boolean = when (sleepTimerMode) {
        SleepTimerMode.END_TRACK -> true
        SleepTimerMode.END_ALBUM -> queue.peekNextInCurrentPass()
            ?.let(libraryRepository::findTrack)
            ?.let(::shouldStopBefore) ?: true
        SleepTimerMode.END_QUEUE -> queue.peekNextInCurrentPass() == null
        else -> false
    }

    private fun shouldStopBefore(nextTrack: Track): Boolean = when (sleepTimerMode) {
        SleepTimerMode.END_TRACK -> true
        SleepTimerMode.END_ALBUM -> albumKey(currentTrack) != albumKey(nextTrack)
        else -> false
    }

    private fun resolvePlayableTrack(track: Track): Track? {
        if (!track.available || track.absolutePath.isBlank() || track.relativePath.isBlank()) return null
        val path = Y2StoragePaths.resolveReadablePath(track.volumeId, track.relativePath, track.absolutePath) ?: return null
        return if (path == track.absolutePath) track else track.copy(absolutePath = path)
    }

    private fun albumKey(track: Track?): String? = track?.let {
        val album = it.album?.trim().orEmpty()
        val folder = File(it.relativePath).parent.orEmpty()
        val owner = it.albumArtist?.trim().takeUnless { value -> value.isNullOrEmpty() }
            ?: "${it.volumeId}|$folder"
        if (album.isNotEmpty()) "$owner|$album" else "${it.volumeId}|$folder"
    }

    private fun stopForSleepTimer() {
        clearSleepTimer()
        clearPreload()
        safetyPolicy.onManualPause()
        duckedForFocus = false
        engine.pause()
        audioFocus.abandon()
        playbackHandler.removeCallbacks(progressRunnable)
        stopForeground(true)
        lastNotificationKey = null
        snapshot = buildSnapshot(PlaybackStatus.PAUSED, snapshot.durationMs, snapshot.durationMs, PauseReason.SLEEP_TIMER)
        persistSession(positionOverride = snapshot.durationMs)
        publishSnapshot()
    }

    /**
     * Attenuation contributed by the in-app volume mode. Exactly 1.0 in SYSTEM
     * mode, so the multiplication below is a no-op and MediaPlayer receives the
     * unmodified value; only PERCEPTUAL mode changes the gain.
     */
    private fun appVolumeGain(): Float =
        if (currentPreferences.volumeMode == VolumeMode.PERCEPTUAL) {
            VolumeCurve.gainForLevel(currentPreferences.volumeLevel)
        } else 1f

    /**
     * The steady-state output level. Ducking and the in-app gain compose by
     * multiplication, which is correct for amplitudes: ducking to 20% of an
     * already-attenuated signal still lands 14 dB down, as intended. Fades and
     * crossfades multiply on top of this inside the engine.
     */
    private fun effectiveVolume(): Float =
        appVolumeGain() * (if (duckedForFocus) EFFECTIVE_DUCK_VOLUME else 1f)

    private fun fadeToVolume(target: Float, durationMs: Long, onComplete: (() -> Unit)? = null) {
        val from = outputVolume
        val safeTarget = target.coerceIn(0f, 1f)
        val generation = ++fadeGeneration
        if (durationMs <= 0) {
            fadeInProgress = false
            setOutputVolume(safeTarget)
            onComplete?.invoke()
            return
        }
        fadeInProgress = true
        val startedAt = SystemClock.uptimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                if (generation != fadeGeneration) return
                val fraction = ((SystemClock.uptimeMillis() - startedAt).toFloat() / durationMs).coerceIn(0f, 1f)
                setOutputVolume(from + (safeTarget - from) * fraction)
                if (fraction >= 1f) {
                    fadeInProgress = false
                    onComplete?.invoke()
                } else playbackHandler.postDelayed(this, VOLUME_FADE_STEP_MS)
            }
        }
        playbackHandler.post(runnable)
    }

    private fun cancelVolumeFade() {
        fadeGeneration += 1
        fadeInProgress = false
    }

    private fun setOutputVolume(value: Float) {
        outputVolume = value.coerceIn(0f, 1f)
        engine.setVolume(outputVolume)
    }

    private fun beginExplicitPlaybackRequest() {
        safetyPolicy.onExplicitPlaybackRequest(routeMonitor.snapshot())
    }

    private fun handleRouteEvent(event: AudioRouteMonitor.Event) {
        logger.info(
            "AudioRoute",
            "action=${event.action} wired=${event.routes.wired} bluetooth=${event.routes.bluetooth} noisy=${event.becomingNoisy}"
        )
        val mustPause = safetyPolicy.onRoutesChanged(
            event.routes,
            becomingNoisy = event.becomingNoisy,
            speakerFallbackAllowed = !currentPreferences.pauseOnDisconnect
        )
        if (mustPause) {
            handlePrivateRouteLoss(event.action)
        } else if (::queue.isInitialized) {
            // Route changes are UI state too; publishing does not change playback behavior.
            refreshSnapshot()
            publishSnapshot()
        }
    }

    private fun handlePrivateRouteLoss(action: String) {
        safetyPolicy.onRouteLoss()
        cancelVolumeFade()
        duckedForFocus = false
        logger.warn("Playback", "private output lost action=$action; pausing without speaker fallback")
        pauseInternal(PauseReason.OUTPUT_DISCONNECTED, useFade = false)
        clearPreload()
        snapshot = buildSnapshot(
            PlaybackStatus.PAUSED,
            snapshot.positionMs,
            snapshot.durationMs,
            PauseReason.OUTPUT_DISCONNECTED,
            ROUTE_LOSS_MESSAGE
        )
        persistSession(positionOverride = snapshot.positionMs)
        publishSnapshot()
    }

    private fun runtimePreferences(value: PlayerPreferencesState): PlayerPreferencesState {
        return if (value.audioQualityMode == AudioQualityMode.DIRECT_DAC) {
            value.copy(
                audioEffectsEnabled = false,
                crossfadeMs = 0,
                pauseResumeFadeMs = 0
            )
        } else value
    }

    private fun buildSnapshot(
        status: PlaybackStatus,
        positionMs: Long,
        durationMs: Long,
        pauseReason: PauseReason = PauseReason.NONE,
        errorMessage: String? = null
    ): PlaybackSnapshot {
        val queueState = queue.snapshot()
        val remaining = sleepTimerDeadlineElapsed?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0) }
        val routes = if (::routeMonitor.isInitialized) routeMonitor.snapshot() else PrivateRouteSnapshot()
        return PlaybackSnapshot(
            status = status,
            currentTrackId = queue.currentTrackId(),
            nextTrackId = preloadedTrack?.id,
            positionMs = positionMs.coerceAtLeast(0),
            durationMs = durationMs.coerceAtLeast(0),
            queue = queueState.items,
            currentQueueIndex = queueState.currentIndex,
            repeatMode = queueState.repeatMode,
            shuffleEnabled = queueState.shuffleEnabled,
            pauseReason = pauseReason,
            errorMessage = errorMessage,
            sleepTimerMode = sleepTimerMode,
            sleepTimerRemainingMs = remaining,
            outputRoute = AudioOutputRouteResolver.resolve(
                wired = routes.wired,
                bluetooth = routes.bluetooth,
                status = status,
                pauseReason = pauseReason
            ),
            audioEffects = audioEffectsState,
            dac = if (::dacController.isInitialized) dacController.snapshot(currentTrack) else DacState()
        )
    }

    private fun persistQueueState(positionOverride: Long? = null) {
        if (!::queue.isInitialized) return
        val items = queue.snapshot().items
        if (items === lastPersistedQueueItems) {
            val session = queue.session(positionOverride ?: currentPersistPosition())
            submitPersistence("session persistence") { database.savePlaybackSession(session) }
        } else if (!queuePersistScheduled) {
            // A queue rewrite deletes and reinserts up to 50k rows; coalescing a burst
            // of edits into one debounced write keeps eMMC traffic and persistence
            // latency bounded. Session and queue stay in one atomic save so a session
            // can never reference an unpersisted queue.
            queuePersistScheduled = true
            playbackHandler.postDelayed(queuePersistRunnable, QUEUE_PERSIST_DEBOUNCE_MS)
        }
    }

    private fun flushQueuePersist() {
        if (!::queue.isInitialized) return
        val items = queue.snapshot().items
        val session = queue.session(currentPersistPosition())
        submitPersistence("atomic queue persistence") {
            database.saveQueueState(items, session)
            lastPersistedQueueItems = items
        }
    }

    private fun persistSession(positionOverride: Long? = null) {
        if (!::queue.isInitialized) return
        val position = positionOverride ?: currentPersistPosition()
        val session = queue.session(position)
        submitPersistence("session persistence") { database.savePlaybackSession(session) }
    }

    private fun currentPersistPosition(): Long {
        if (!currentPreferences.resumePosition) return 0
        return if (::engine.isInitialized && engine.durationMs() > 0) engine.currentPositionMs() else snapshot.positionMs
    }

    private fun addListener(listener: Listener) {
        listeners += listener
        mainHandler.post { listener.onPlaybackChanged(snapshot) }
    }

    private fun removeListener(listener: Listener) { listeners -= listener }

    private fun publishSnapshot() {
        val value = snapshot
        if (::remoteControl.isInitialized) remoteControl.update(value, currentTrack)
        mainHandler.post {
            listeners.forEach { it.onPlaybackChanged(value) }
            updateNotificationIfNeeded(value)
        }
    }

    private fun updateNotificationIfNeeded(value: PlaybackSnapshot) {
        if (value.status != PlaybackStatus.PLAYING) return
        val key = notificationKey()
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, createNotification())
    }

    private fun notificationKey(): NotificationKey = NotificationKey(
        snapshot.currentTrackId,
        snapshot.status,
        currentTrack?.title,
        currentTrack?.displayArtist
    )

    private fun post(block: () -> Unit) {
        if (!shuttingDown && ::playbackHandler.isInitialized) playbackHandler.post(block)
    }

    private fun submitPersistence(operation: String, block: () -> Unit) {
        if (persistenceExecutor.isShutdown) return
        persistenceExecutor.execute {
            runCatching(block).onFailure { logger.error("Playback", "$operation failed", it) }
        }
    }

    private fun stopSelfIfIdle() {
        if (boundClients == 0 && snapshot.status !in ACTIVE_STATUSES && !safetyPolicy.hasPendingFocusResume()) stopSelf()
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(currentTrack?.title ?: getString(R.string.app_name))
            .setContentText(currentTrack?.displayArtist ?: "Music player")
            .setContentIntent(pendingIntent)
            .setOngoing(snapshot.status == PlaybackStatus.PLAYING)
            .build()
    }

    companion object {
        const val ACTION_MEDIA_BUTTON = "com.schulzcode.y2player.action.MEDIA_BUTTON"
        private const val NOTIFICATION_ID = 19
        private const val PROGRESS_INTERVAL_MS = 1_000L
        private const val CROSSFADE_POLL_INTERVAL_MS = 250L
        private const val CROSSFADE_APPROACH_WINDOW_MS = 1_000L
        // 5 s bounds the position lost to a battery pull / hard crash to the stated
        // acceptable window (±2–5 s). One WAL row update every 5 s is negligible eMMC
        // traffic; losing 15 s of a long track on reboot is noticeable.
        private const val POSITION_PERSIST_INTERVAL_MS = 5_000L
        private const val QUEUE_PERSIST_DEBOUNCE_MS = 500L
        private const val SHUTDOWN_TIMEOUT_MS = 2_000L
        private const val PREPARE_TIMEOUT_MS = 15_000L
        private const val PRELOAD_TIMEOUT_MS = 15_000L
        private const val PRELOAD_RESTORE_WINDOW_MS = 30_000L
        private const val MAX_TRACK_RETRIES = 1
        private const val STABLE_PLAYBACK_RESET_MS = 2_000L
        private const val MIN_CROSSFADE_MS = 100L
        private const val EFFECTIVE_DUCK_VOLUME = 0.2f
        private const val SHORT_FOCUS_FADE_MS = 100L
        private const val VOLUME_FADE_STEP_MS = 25L
        private const val ROUTE_LOSS_MESSAGE = "Private audio output disconnected - playback paused"
        private val ACTIVE_STATUSES = setOf(PlaybackStatus.PLAYING, PlaybackStatus.PREPARING)
        /** States in which preparing the next track ahead of time makes sense. */
        private val PRELOADABLE_STATUSES = setOf(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED)
    }
}
