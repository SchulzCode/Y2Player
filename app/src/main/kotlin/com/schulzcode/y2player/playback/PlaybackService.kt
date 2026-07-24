package com.schulzcode.y2player.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import com.schulzcode.y2player.diagnostics.Ev
import com.schulzcode.y2player.diagnostics.EventLog
import com.schulzcode.y2player.diagnostics.Sub
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
            if (queue.moveItem(index, delta)) afterQueueMutation()
        }

        fun clearUpcoming() = post {
            queue.clearUpcoming()
            afterQueueMutation()
        }

        fun clearQueue() = post(::clearQueueInternal)

        /** Inserts a track directly after the current one. */
        fun playNext(trackId: Long) = post {
            queue.addNext(trackId)
            afterQueueMutation()
        }

        fun addToQueue(trackId: Long) = post {
            queue.append(trackId)
            afterQueueMutation()
        }

        fun toggleShuffle() = post {
            queue.toggleShuffle()
            afterQueueMutation()
        }

        fun cycleRepeat() = post {
            queue.cycleRepeat()
            afterQueueMutation()
        }

        fun cycleSleepTimer() = post(::cycleSleepTimerInternal)

        fun applyPreferences(value: PlayerPreferencesState) = post {
            applyPreferencesInternal(value)
            normalizeSystemVolumeForAppControl()
        }

        /**
         * Transfers volume ownership without a loudness spike. Entering app
         * control attenuates MediaPlayer before raising the Android stream;
         * leaving it lowers the Android stream before removing app attenuation.
         */
        fun applyVolumeModeTransition(value: PlayerPreferencesState, systemVolumeIndex: Int) = post {
            applyVolumeModeTransitionInternal(value, systemVolumeIndex)
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
    private lateinit var eventLog: EventLog
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
    private var lastPeriodicPersistedPositionMs = Long.MIN_VALUE
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
    /**
     * Effects attach to the audio *session*, not to a player, and the session id
     * is fixed for the life of the engine. Re-applying on every prepare rewrote
     * every band and rebuilt three description lists once per track change for
     * no behavioural gain; this reapplies once per engine session instead.
     */
    private var audioEffectsSessionApplied = false
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
        eventLog = container.eventLog
        dacController = DacController(this, logger)
        storageMonitor = container.storageMonitor
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        playbackThread = HandlerThread("y2-playback").apply { start() }
        playbackHandler = Handler(playbackThread.looper)
        routeMonitor = AudioRouteMonitor(this) { event -> post { handleRouteEvent(event) } }
        routeMonitor.start()
        storageMonitor.addListener(storageListener)

        post {
            // Vendor Hi-Fi routing goes through AudioSystem.setParameters, a
            // synchronous call into the audio HAL. An MT6582 HAL that is slow to
            // answer an undocumented parameter would stall the main thread at
            // every service start, so it is requested from the playback thread.
            dacController.applyDirectMode(requestedPreferences.audioQualityMode == AudioQualityMode.DIRECT_DAC)
            engine = runCatching<PlaybackEngine> { AndroidMediaPlayerEngine(this, logger) }
                .onFailure { logger.error("Playback", "MediaPlayer engine initialization failed", it) }
                .getOrElse { UnavailablePlaybackEngine("MediaPlayer is unavailable") }
                .also { it.setListener(this) }
            // Restore the persisted app gain before normalizing the system layer.
            // This order also makes a reboot into in-app mode free of a volume spike.
            setOutputVolume(effectiveVolume())
            normalizeSystemVolumeForAppControl()
            audioFocus = AudioFocusController(this, this)
            audioEffectsController = AudioEffectsController(this, engine.audioSessionId, logger)
            audioEffectsState = audioEffectsController.apply(currentPreferences)
            audioEffectsSessionApplied = true
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
        logger.info("Playback", "client bound count=$boundClients")
        post(::refreshProgressForNewClient)
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundClients = (boundClients - 1).coerceAtLeast(0)
        logger.info("Playback", "client unbound count=$boundClients")
        post(::stopSelfIfIdle)
        // Returning true makes Android call onRebind for later clients, keeping
        // boundClients accurate when an activity is recreated.
        return true
    }

    override fun onRebind(intent: Intent?) {
        boundClients += 1
        logger.info("Playback", "client rebound count=$boundClients")
        post(::refreshProgressForNewClient)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_MEDIA_BUTTON) {
            val keyCode = intent.getIntExtra(EXTRA_MEDIA_KEY_CODE, KeyEvent.KEYCODE_UNKNOWN)
            // The non-exported service trusts the receiver's source-scoped key
            // mapping and edge normalization; repeating either gate here would
            // block valid DOWN-only or UP-only API-19 vendor delivery.
            if (keyCode != KeyEvent.KEYCODE_UNKNOWN) handleMediaKey(keyCode)
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
                // Same reason as onCreate: this reaches the audio HAL, so it must
                // not run on the main thread during teardown. If the post below
                // fails the looper is already gone and this is skipped; the next
                // service start constructs a fresh DacController that applies the
                // configured mode unconditionally, so the vendor flag self-corrects.
                if (::dacController.isInitialized) dacController.applyDirectMode(false)
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
        logger.info("Playback", "service destroyed")
        eventLog.info(Sub.PLAYBACK, Ev.PLAYBACK_RELEASE, "persisted" to persisted)
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        releaseTransientPlaybackResources("low memory")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // TRIM levels are not ordered by memory pressure: TRIM_MEMORY_UI_HIDDEN (20)
        // only means no UI is visible and fires with zero pressure, yet it is numerically
        // above TRIM_MEMORY_RUNNING_LOW (10). Releasing the preloaded next player there
        // would break gapless during background listening, the primary use case.
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            releaseTransientPlaybackResources("trim memory level=$level")
        }
    }

    /**
     * Drops the second player and any running transition under memory pressure.
     * The current track keeps playing; only what can be rebuilt is released.
     */
    private fun releaseTransientPlaybackResources(reason: String) = post {
        cancelVolumeFade()
        clearPreload()
        // Allow one later near-end recovery so gapless self-heals if pressure eases.
        autoPreloadAttemptedForRequest = 0L
        logger.warn("Playback", "$reason: secondary player and transitions released")
    }

    override fun onPrepared(requestId: Long, durationMs: Long) = post {
        if (!PlaybackRequestGate.accepts(requestId, activeRequestId)) return@post
        cancelCurrentWatchdog(requestId)
        if (durationMs <= 0) {
            engine.cancel()
            onError(requestId, "Track has no playable duration")
            return@post
        }
        // The first prepared player is the earliest point at which API-19
        // vendor stacks are guaranteed to have a live playback path for the
        // session, so persisted settings are applied once here rather than
        // remaining attached only to the idle player that allocated the session
        // ID. Every later track reuses that same session id, so re-applying per
        // track only rewrote settings that were already in force.
        if (!audioEffectsSessionApplied) {
            audioEffectsState = audioEffectsController.apply(currentPreferences)
            audioEffectsSessionApplied = true
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
            enterForeground()
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
        eventLog.info(
            Sub.PLAYBACK, Ev.PLAYBACK_PREPARED,
            "request" to requestId,
            "track" to currentTrack?.id,
            "durationMs" to durationMs,
            "positionMs" to requestedPosition,
            "started" to started,
            "focus" to focusGranted
        )
        if (started) {
            eventLog.info(
                Sub.PLAYBACK, Ev.PLAYBACK_START,
                "request" to requestId,
                "track" to currentTrack?.id,
                "codec" to currentTrack?.codec,
                "sampleRate" to currentTrack?.sampleRate,
                "reason" to "prepared"
            )
        }
        pendingPositionMs = 0
        pendingAutoPlay = false
        persistSession()
        armNearEndPreload()
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
        enterForeground()
        eventLog.info(
            Sub.PLAYBACK, Ev.PLAYBACK_START,
            "request" to requestId,
            "track" to expectedTrack.id,
            "codec" to expectedTrack.codec,
            "sampleRate" to expectedTrack.sampleRate,
            "reason" to if (currentPreferences.crossfadeMs > 0) "crossfade" else "gapless"
        )
        snapshot = buildSnapshot(
            status = PlaybackStatus.PLAYING,
            positionMs = engine.currentPositionMs(),
            durationMs = durationMs,
            pauseReason = PauseReason.NONE
        )
        persistSession(positionOverride = snapshot.positionMs)
        armNearEndPreload()
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
        eventLog.error(
            Sub.PLAYBACK, Ev.PLAYBACK_ERROR,
            "request" to requestId,
            "track" to track?.id,
            "format" to track?.extension,
            "codec" to track?.codec,
            "retry" to currentRetryCount,
            "message" to message
        )
        // A failed player is the one case where the effect backend may have been
        // torn down with it, so allow one re-application on the next prepare.
        audioEffectsSessionApplied = false
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
            leaveForeground()
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
                // Re-read rather than reuse: the held Track is an immutable copy
                // taken when the session was restored, which on a cold boot is
                // before the card has finished mounting. Reusing it made a
                // restored session that failed once fail forever, because every
                // later press re-tested the same stale object. One indexed
                // lookup per play press is not a cost worth that.
                val restored = queue.currentTrackId()?.let(libraryRepository::findTrack)
                val track = restored ?: currentTrack ?: return
                currentTrack = track
                val playable = resolvePlayableTrack(track)
                if (playable == null) {
                    logger.warn(
                        "Playback",
                        "resume refused track=${track.id} volume=${track.volumeId} path=${track.absolutePath}"
                    )
                    eventLog.warn(
                        Sub.PLAYBACK, Ev.PLAYBACK_SOURCE_LOST,
                        "track" to track.id,
                        "volume" to track.volumeId,
                        "mounted" to Y2StoragePaths.isVolumeMounted(track.volumeId),
                        "reason" to "resume_unresolved"
                    )
                    snapshot = snapshot.copy(errorMessage = "Track is unavailable", pauseReason = PauseReason.STORAGE_REMOVED)
                    publishSnapshot()
                    return
                }
                currentTrack = playable
                if (engine.state !in RESUMABLE_ENGINE_STATES || engine.durationMs() <= 0) {
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
        enterForeground()
        eventLog.info(
            Sub.PLAYBACK, Ev.PLAYBACK_START,
            "track" to currentTrack?.id,
            "codec" to currentTrack?.codec,
            "sampleRate" to currentTrack?.sampleRate,
            "reason" to "resume"
        )
        snapshot = buildSnapshot(PlaybackStatus.PLAYING, engine.currentPositionMs(), engine.durationMs(), PauseReason.NONE)
        scheduleProgress()
        armNearEndPreload()
        publishSnapshot()
    }

    private fun startEngineWithFade() {
        // Re-assert media-button ownership. On API 19 the last registrant wins,
        // so any other media app used since startup silently captures AVRCP
        // buttons (AirPods stem presses, car controls). One cheap AudioManager
        // call at every engine start returns them to this player exactly when
        // the user demonstrates intent to control it.
        MediaButtonReceiver.register(this, logger)
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
        useFade: Boolean = true,
        errorMessage: String? = null
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
            // A paused track holds no second MediaPlayer: release any preloaded next
            // player (this also handles the mid-crossfade case, which clearPreload
            // cancels safely). Near-end preload re-arms when playback resumes.
            clearPreload()
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
        leaveForeground()
        snapshot = buildSnapshot(PlaybackStatus.PAUSED, position, duration, reason, errorMessage)
        persistSession(positionOverride = position)
        eventLog.info(
            Sub.PLAYBACK, Ev.PLAYBACK_PAUSE,
            "track" to currentTrack?.id,
            "reason" to reason.name,
            "positionMs" to position
        )
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
        armNearEndPreload()
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
        val currentPassOnly = !userInitiated && sleepTimerMode in PASS_BOUNDED_SLEEP_MODES
        if (!moveToNextAvailable(ignoreRepeatOne = userInitiated, currentPassOnly = currentPassOnly)) {
            finishQueue(endedBySleepTimer = sleepTimerMode in PASS_BOUNDED_SLEEP_MODES)
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
        leaveForeground()
        if (endedBySleepTimer) clearSleepTimer()
        snapshot = buildSnapshot(
            PlaybackStatus.PAUSED,
            snapshot.durationMs,
            snapshot.durationMs,
            if (endedBySleepTimer) PauseReason.SLEEP_TIMER else PauseReason.USER
        )
        persistSession(positionOverride = snapshot.durationMs)
        eventLog.info(
            Sub.PLAYBACK, Ev.PLAYBACK_STOP,
            "reason" to if (endedBySleepTimer) "sleep_timer" else "queue_end",
            "track" to currentTrack?.id
        )
        publishSnapshot()
    }

    /**
     * Advances to the next item that actually resolves to a readable file.
     *
     * Each probe mutates the queue position, so a search that finds nothing
     * restores the index it started from: leaving the queue parked on an
     * arbitrary unplayable item made the next user action continue from
     * wherever the failed search happened to stop.
     */
    private fun moveToNextAvailable(
        ignoreRepeatOne: Boolean = false,
        currentPassOnly: Boolean = false
    ): Boolean {
        val size = queue.snapshot().items.size
        val startIndex = queue.snapshot().currentIndex
        repeat(size.coerceAtLeast(1)) {
            val next = when {
                currentPassOnly -> queue.nextInCurrentPass()
                ignoreRepeatOne -> queue.nextIgnoringRepeatOne()
                else -> queue.next()
            }
            if (next == null) {
                startIndex?.let(queue::moveToQueueIndex)
                return false
            }
            val track = libraryRepository.findTrack(next)
            if (track?.let(::resolvePlayableTrack) != null) return true
            logger.warn("Playback", "skipping unavailable track=$next")
        }
        startIndex?.let(queue::moveToQueueIndex)
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
            afterQueueMutation()
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
        leaveForeground()
        snapshot = PlaybackSnapshot(audioEffects = audioEffectsState, sleepTimerMode = sleepTimerMode)
        // Written immediately rather than debounced: Reset Library clears the
        // queue and then wipes the tracks it refers to, and a write still sitting
        // in the debounce window would race that wipe.
        playbackHandler.removeCallbacks(queuePersistRunnable)
        queuePersistScheduled = false
        flushQueuePersist()
        eventLog.info(Sub.PLAYBACK, Ev.PLAYBACK_STOP, "reason" to "queue_cleared")
        publishSnapshot()
    }

    private fun refreshSnapshot() {
        snapshot = buildSnapshot(snapshot.status, snapshot.positionMs, snapshot.durationMs, snapshot.pauseReason, snapshot.errorMessage)
    }

    /**
     * The four steps every queue edit owes: refresh the snapshot, persist the
     * new order, re-evaluate preload eligibility, publish.
     *
     * They were repeated verbatim at seven call sites, where omitting one — most
     * easily [armNearEndPreload], whose absence leaves a preloaded player that no
     * longer matches the next item — produced a bug visible only at the next
     * track transition.
     */
    private fun afterQueueMutation() {
        refreshSnapshot()
        persistQueueState()
        armNearEndPreload()
        publishSnapshot()
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
            leaveForeground()
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
        eventLog.info(
            Sub.PLAYBACK, Ev.PLAYBACK_OPEN,
            "request" to activeRequestId,
            "track" to track.id,
            "format" to track.extension,
            "codec" to track.codec,
            "sampleRate" to track.sampleRate,
            "volume" to track.volumeId,
            "autoPlay" to autoPlay,
            "positionMs" to pendingPositionMs
        )
        engine.prepare(track, activeRequestId)
        scheduleCurrentWatchdog(activeRequestId)
    }

    /** The next track the queue would advance to, respecting the sleep-timer mode. */
    private fun expectedNextTrackId(): Long? = when (sleepTimerMode) {
        SleepTimerMode.END_TRACK -> null
        SleepTimerMode.END_ALBUM, SleepTimerMode.END_QUEUE -> queue.peekNextInCurrentPass()
        else -> queue.peekNext()
    }

    /**
     * Re-evaluates preload eligibility after a discrete change (prepare, transition,
     * start, seek, queue edit, preference or sleep-timer change). Drops a preload
     * that no longer matches the current next track and re-opens the per-track
     * attempt so the near-end check can run again. It never prepares the next player
     * here — that happens near the end of the track in [maybePreloadNearEnd].
     */
    private fun armNearEndPreload() {
        if (!::engine.isInitialized) return
        // Re-open a fresh near-end attempt for the current request.
        autoPreloadAttemptedForRequest = 0L
        // Drop any held next player whose eligibility has been lost: no current
        // track, Repeat One, no valid next item, or a sleep-timer boundary. This
        // never prepares a player — near-end preparation happens in the progress
        // loop once the current track approaches its end.
        val expectedNextId = expectedNextTrackId()
        if (currentTrack == null ||
            queue.snapshot().repeatMode == RepeatMode.ONE ||
            expectedNextId == null
        ) {
            clearPreload()
            return
        }
        val nextTrack = libraryRepository.findTrack(expectedNextId)?.let(::resolvePlayableTrack)
        if (nextTrack == null || shouldStopBefore(nextTrack)) {
            clearPreload()
            return
        }
        // A queue edit may have changed which track comes next; drop a stale preload.
        if (preloadedTrack != null && preloadedTrack?.id != expectedNextId) clearPreload()
        // Short tracks begin inside the window. Check immediately instead of
        // waiting for the first periodic tick, which may be several seconds away
        // with the display off.
        maybePreloadNearEnd(snapshot.positionMs, snapshot.durationMs, nextTrack)
    }

    /**
     * Prepares the next track once the current one is within the near-end window.
     * Called every progress tick; the pure [NearEndPreloadPolicy] decides, and the
     * per-track attempt guard keeps a failed preparation from retrying every tick.
     */
    private fun maybePreloadNearEnd(positionMs: Long, durationMs: Long, resolvedNext: Track? = null) {
        if (!::engine.isInitialized || currentTrack == null) return
        val crossfadeMs = currentPreferences.crossfadeMs.toLong()
        val remainingMs = durationMs - positionMs
        // This cheap time check must precede queue/library/file resolution. A long
        // track therefore does no next-track work on ordinary progress ticks.
        if (!NearEndPreloadPolicy.isWithinWindow(remainingMs, crossfadeMs)) return
        if (snapshot.status != PlaybackStatus.PLAYING || !engine.isPlaying()) return
        if (queue.snapshot().repeatMode == RepeatMode.ONE) return
        if (preloadedRequestId != null || engine.isTransitioning) return
        if (autoPreloadAttemptedForRequest == activeRequestId) return
        val nextId = expectedNextTrackId() ?: return
        val nextTrack = resolvedNext?.takeIf { it.id == nextId }
            ?: libraryRepository.findTrack(nextId)?.let(::resolvePlayableTrack)
            ?: return
        val inputs = NearEndPreloadPolicy.Inputs(
            isPlaying = true,
            hasCurrentTrack = true,
            hasNextItem = true,
            repeatOne = queue.snapshot().repeatMode == RepeatMode.ONE,
            stopAfterCurrent = shouldStopBefore(nextTrack),
            alreadyPreparedOrPreparing = preloadedRequestId != null,
            transitioning = engine.isTransitioning,
            attemptedForThisRequest = autoPreloadAttemptedForRequest == activeRequestId,
            remainingMs = remainingMs,
            crossfadeMs = crossfadeMs
        )
        if (!NearEndPreloadPolicy.shouldPreload(inputs)) return
        autoPreloadAttemptedForRequest = activeRequestId
        preloadRetryCount = 0
        logger.info(
            "Playback",
            "near-end preload request=$activeRequestId remainingMs=${inputs.remainingMs} " +
                "thresholdMs=${NearEndPreloadPolicy.effectiveThresholdMs(crossfadeMs)} nextTrack=${nextTrack.id} " +
                "gapless=${currentPreferences.gaplessEnabled && crossfadeMs <= 0} crossfadeMs=$crossfadeMs"
        )
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
        val duration = if (::engine.isInitialized) engine.durationMs() else 0L
        val remaining = if (duration > 0L && engine.isPlaying()) {
            (duration - engine.currentPositionMs()).coerceAtLeast(0L)
        } else null
        playbackHandler.postDelayed(progressRunnable, progressInterval(remaining))
    }

    private fun progressInterval(remainingMs: Long? = null): Long {
        val crossfade = currentPreferences.crossfadeMs.toLong()
        // Approaching a crossfade: always poll fast so the transition isn't missed.
        if (crossfade > 0 && remainingMs != null && remainingMs <= crossfade + CROSSFADE_APPROACH_WINDOW_MS) {
            return CROSSFADE_POLL_INTERVAL_MS
        }
        // Screen off (no UI bound): poll slowly to save power, but tighten back to
        // the 1 s cadence as the near-end preload threshold approaches so the
        // preparation trigger is never missed.
        if (boundClients == 0) {
            val threshold = NearEndPreloadPolicy.effectiveThresholdMs(crossfade)
            val nearPreloadBoundary = remainingMs != null && remainingMs <= threshold + BACKGROUND_PROGRESS_INTERVAL_MS
            return if (nearPreloadBoundary) PROGRESS_INTERVAL_MS else BACKGROUND_PROGRESS_INTERVAL_MS
        }
        return PROGRESS_INTERVAL_MS
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (snapshot.status != PlaybackStatus.PLAYING || !engine.isPlaying()) return
            val position = engine.currentPositionMs()
            val duration = engine.durationMs()
            if (consecutiveErrors > 0 && position >= STABLE_PLAYBACK_RESET_MS) consecutiveErrors = 0
            val remaining = (duration - position).coerceAtLeast(0)
            // Prepare the next track only once we are within the near-end window.
            // The policy and the per-track attempt guard keep this to a single
            // preparation, so a failed preload does not retry every tick and a
            // long track never allocates the second player near its start.
            maybePreloadNearEnd(position, duration)
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
                // Always keep the internal position current (persistence, remote-control
                // position provider, next bind), but only wake UI listeners when one is
                // actually bound. Periodic progress never takes the material path, so it
                // does not rewrite RemoteControlClient state or the notification.
                updateInternalProgress(position, duration)
                publishUiProgressIfBound()
            }
            // Persist less often in the background to spare the eMMC while the
            // screen is off; foreground keeps the tighter 5 s cadence.
            val persistInterval = if (boundClients == 0) BACKGROUND_POSITION_PERSIST_INTERVAL_MS else POSITION_PERSIST_INTERVAL_MS
            val lastPosition = lastPeriodicPersistedPositionMs
            if (lastPosition == Long.MIN_VALUE || position < lastPosition || position - lastPosition >= persistInterval) {
                lastPeriodicPersistedPositionMs = position
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
        leaveForeground()
        snapshot = buildSnapshot(
            PlaybackStatus.PAUSED,
            position,
            duration,
            PauseReason.STORAGE_REMOVED,
            "Current storage was removed"
        )
        persistSession(positionOverride = position)
        logger.warn("Playback", "source released after storage loss track=${currentTrack?.id} volume=${currentTrack?.volumeId}")
        eventLog.warn(
            Sub.PLAYBACK, Ev.PLAYBACK_SOURCE_LOST,
            "track" to currentTrack?.id,
            "volume" to currentTrack?.volumeId,
            "positionMs" to position
        )
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
        leaveForeground()
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
        armNearEndPreload()
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

    /**
     * Resolves a stored track to a file that can actually be opened right now.
     *
     * Deliberately does **not** consult `Track.available`. That flag records
     * what the last scan concluded, not what the filesystem currently holds, and
     * the two disagree after an ordinary reboot: as the HOME app this process
     * starts before the card is mounted, so a card that takes longer than
     * BOOT_STORAGE_GRACE_MS to appear gets its whole volume flagged unavailable.
     * A restored session captured while that flag was false could then never be
     * resumed — the check failed before ever touching the file, and the copy of
     * the track held by the service was never re-read, so it stayed broken even
     * after the card mounted and the rescan set the flag back.
     *
     * The volume check that replaces it is live rather than remembered, so a
     * genuinely absent card still short-circuits without stat-ing every queue
     * item, which is what the flag was really protecting.
     */
    private fun resolvePlayableTrack(track: Track): Track? {
        if (track.absolutePath.isBlank() || track.relativePath.isBlank()) return null
        if (!Y2StoragePaths.isVolumeMounted(track.volumeId)) return null
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
        leaveForeground()
        snapshot = buildSnapshot(PlaybackStatus.PAUSED, snapshot.durationMs, snapshot.durationMs, PauseReason.SLEEP_TIMER)
        persistSession(positionOverride = snapshot.durationMs)
        eventLog.info(
            Sub.PLAYBACK, Ev.PLAYBACK_STOP,
            "reason" to "sleep_timer_track_end",
            "track" to currentTrack?.id
        )
        publishSnapshot()
    }

    private fun applyPreferencesInternal(value: PlayerPreferencesState) {
        val previousGain = appVolumeGain()
        val effective = runtimePreferences(value)
        val modeChanged = value.audioQualityMode != requestedPreferences.audioQualityMode
        val transitionChanged = effective.gaplessEnabled != currentPreferences.gaplessEnabled ||
            effective.crossfadeMs != currentPreferences.crossfadeMs
        if (modeChanged || transitionChanged) clearPreload()
        requestedPreferences = value
        currentPreferences = effective
        // A volume step must be audible immediately. While a fade is running,
        // its terminal update applies the newest steady-state gain instead.
        if (appVolumeGain() != previousGain && !fadeInProgress) setOutputVolume(effectiveVolume())
        dacController.applyDirectMode(value.audioQualityMode == AudioQualityMode.DIRECT_DAC)
        audioEffectsState = audioEffectsController.apply(effective)
        if (modeChanged || transitionChanged) armNearEndPreload()
        refreshSnapshot()
        publishSnapshot()
    }

    private fun applyVolumeModeTransitionInternal(value: PlayerPreferencesState, systemVolumeIndex: Int) {
        // A pause/focus fade owns MediaPlayer volume for only a short bounded
        // interval. Deferring preserves its completion callback and avoids
        // raising the system layer before the transferred app gain is active.
        if (fadeInProgress) {
            playbackHandler.postDelayed(
                { applyVolumeModeTransitionInternal(value, systemVolumeIndex) },
                VOLUME_FADE_STEP_MS
            )
            return
        }
        if (value.volumeMode == VolumeMode.PERCEPTUAL) {
            applyPreferencesInternal(value)
            setMusicStreamVolume(systemVolumeIndex)
        } else {
            setMusicStreamVolume(systemVolumeIndex)
            applyPreferencesInternal(value)
        }
    }

    /** Keeps Android from silently limiting the top of the in-app fader. */
    private fun normalizeSystemVolumeForAppControl() {
        if (currentPreferences.volumeMode != VolumeMode.PERCEPTUAL || fadeInProgress) return
        val maximum = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            .onFailure { logger.warn("Volume", "unable to read music-stream maximum: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return
        setMusicStreamVolume(maximum)
    }

    private fun setMusicStreamVolume(requestedIndex: Int) {
        val maximum = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            .getOrElse {
                logger.warn("Volume", "unable to read music-stream maximum: ${it.javaClass.simpleName}")
                return
            }
        val target = requestedIndex.coerceIn(0, maximum.coerceAtLeast(0))
        val current = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        if (current == target) return
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
            .onSuccess { logger.info("Volume", "music stream transferred ${current ?: "?"}/$maximum -> $target/$maximum") }
            .onFailure { logger.warn("Volume", "unable to set music-stream volume: ${it.javaClass.simpleName}") }
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
                    // Preferences can change during a fade. Its originally
                    // captured target is stale in that case, so finish at the
                    // current steady-state gain before touching system volume.
                    setOutputVolume(effectiveVolume())
                    normalizeSystemVolumeForAppControl()
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
        // Android may restore a different remembered stream index for each
        // output route. App-controlled mode must not become capped again after
        // a wired/Bluetooth route switch.
        normalizeSystemVolumeForAppControl()
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

    /**
     * Pauses without speaker fallback after the private output disappeared.
     *
     * [pauseInternal] already builds the snapshot, persists the session and
     * publishes; passing the message straight through means one DB write, one
     * RemoteControlClient update and one UI wake per disconnect. Disconnects
     * arrive in bursts (AUDIO_BECOMING_NOISY, then A2DP CONNECTION_STATE_CHANGED),
     * so doing this work twice per event was multiplied by every burst.
     *
     * [PlaybackSafetyPolicy.onRoutesChanged] has already latched the loss when it
     * returned true, so it is not latched again here.
     */
    private fun handlePrivateRouteLoss(action: String) {
        cancelVolumeFade()
        duckedForFocus = false
        logger.warn("Playback", "private output lost action=$action; pausing without speaker fallback")
        clearPreload()
        pauseInternal(
            PauseReason.OUTPUT_DISCONNECTED,
            useFade = false,
            errorMessage = ROUTE_LOSS_MESSAGE
        )
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
        // Claimed here, on the playback thread, rather than inside the executor.
        // Recording it only after the write completed left a window in which
        // further edits saw "not yet persisted" and scheduled another full
        // rewrite of the same list — repeating a delete-and-reinsert of up to
        // MAX_QUEUE_ITEMS rows that the debounce exists to prevent. On failure
        // the claim is released so the next edit rewrites it for real.
        lastPersistedQueueItems = items
        submitPersistence("atomic queue persistence") {
            runCatching { database.saveQueueState(items, session) }
                .onFailure {
                    lastPersistedQueueItems = null
                    throw it
                }
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

    /**
     * Material update: track/status/route/queue/sleep-timer changes. Pushes the
     * RemoteControlClient metadata and state, wakes every UI listener, and
     * refreshes the notification. This is the full path — not for periodic ticks.
     */
    private fun publishSnapshot() {
        val value = snapshot
        if (::remoteControl.isInitialized) remoteControl.update(value, currentTrack)
        mainHandler.post {
            listeners.forEach { it.onPlaybackChanged(value) }
            updateNotificationIfNeeded(value)
        }
    }

    /** Keeps the internal snapshot position current without publishing anything. */
    private fun updateInternalProgress(position: Long, duration: Long) {
        snapshot = buildSnapshot(PlaybackStatus.PLAYING, position, duration, PauseReason.NONE)
    }

    /**
     * Delivers a periodic progress tick to bound UI only. When nothing is bound
     * (screen off), it does no work: no listener wake, no RemoteControlClient
     * rewrite (its position comes from the position provider), no notification.
     */
    private fun publishUiProgressIfBound() {
        if (boundClients == 0) return
        val value = snapshot
        mainHandler.post { listeners.forEach { it.onPlaybackChanged(value) } }
    }

    /**
     * A UI client just bound: pull the live engine position, publish a full
     * snapshot so the screen is current immediately instead of waiting for the
     * next background tick, and restore the tighter foreground cadence.
     */
    private fun refreshProgressForNewClient() {
        if (!::engine.isInitialized) return
        if (snapshot.status == PlaybackStatus.PLAYING && engine.isPlaying()) {
            val position = engine.currentPositionMs()
            val duration = engine.durationMs()
            updateInternalProgress(position, duration)
            lastPublishedProgressSecond = position / 1_000L
            scheduleProgress()
        }
        publishSnapshot()
    }

    /**
     * Enters the foreground and records the key describing what was posted.
     *
     * The notification and [lastNotificationKey] must move together: the key is
     * what stops [updateNotificationIfNeeded] from re-posting an identical
     * notification. Keeping the pair in one place removes the possibility of a
     * caller updating one without the other.
     */
    private fun enterForeground() {
        startForeground(NOTIFICATION_ID, createNotification())
        lastNotificationKey = notificationKey()
    }

    /** Leaves the foreground and forgets the posted key. Counterpart of [enterForeground]. */
    private fun leaveForeground() {
        stopForeground(true)
        lastNotificationKey = null
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
        if (boundClients == 0 && snapshot.status !in ACTIVE_STATUSES && !safetyPolicy.hasPendingFocusResume()) {
            logger.info("Playback", "idle with no bound clients: stopping service")
            stopSelf()
        }
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
        const val EXTRA_MEDIA_KEY_CODE = "com.schulzcode.y2player.extra.MEDIA_KEY_CODE"
        private const val NOTIFICATION_ID = 19
        private const val PROGRESS_INTERVAL_MS = 1_000L
        // Screen off with no UI bound: poll every 5 s and persist at most every 10 s.
        private const val BACKGROUND_PROGRESS_INTERVAL_MS = 5_000L
        private const val BACKGROUND_POSITION_PERSIST_INTERVAL_MS = 10_000L
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
        private const val MAX_TRACK_RETRIES = 1
        private const val STABLE_PLAYBACK_RESET_MS = 2_000L
        private const val MIN_CROSSFADE_MS = 100L
        private const val EFFECTIVE_DUCK_VOLUME = 0.2f
        private const val SHORT_FOCUS_FADE_MS = 100L
        private const val VOLUME_FADE_STEP_MS = 25L
        private const val ROUTE_LOSS_MESSAGE = "Private audio output disconnected - playback paused"
        private val ACTIVE_STATUSES = setOf(PlaybackStatus.PLAYING, PlaybackStatus.PREPARING)
        // Hoisted: these were allocated fresh on every evaluation, on paths that
        // run per key press and per track transition.
        private val RESUMABLE_ENGINE_STATES = setOf(EngineState.READY, EngineState.PAUSED)
        private val PASS_BOUNDED_SLEEP_MODES = setOf(SleepTimerMode.END_ALBUM, SleepTimerMode.END_QUEUE)
    }
}
