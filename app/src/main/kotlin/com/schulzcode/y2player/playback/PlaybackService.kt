package com.schulzcode.y2player.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Build
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
import com.schulzcode.y2player.core.model.PlaybackExitReason
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
import com.schulzcode.y2player.diagnostics.PlaybackHistory
import com.schulzcode.y2player.diagnostics.PlaybackSession
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

        fun playCollection(
            trackIds: List<Long>,
            startIndex: Int,
            shuffled: Boolean = false,
            fromStart: Boolean = false
        ) = post {
            if (trackIds.isEmpty()) return@post
            releaseCurrentTrack(PlaybackExitReason.QUEUE_REPLACED)
            beginExplicitPlaybackRequest()
            if (shuffled) queue.replaceShuffled(trackIds, repeatAll = false)
            else queue.replace(trackIds, startIndex)
            currentRetryCount = 0
            consecutiveErrors = 0
            syncReplayGain()
            syncTransition()
            prepareCurrent(autoPlay = true, positionMs = prepareAudiobookStart(ignoreSavedPosition = fromStart))
        }

        fun clearAudiobookProgress(folderKey: String) = post {
            submitPersistence("audiobook progress cleared") { database.deleteAudiobookProgress(folderKey) }
        }

        fun playCollectionShuffled(trackIds: List<Long>) = post {
            if (trackIds.isEmpty()) return@post
            releaseCurrentTrack(PlaybackExitReason.QUEUE_REPLACED)
            beginExplicitPlaybackRequest()
            queue.replaceShuffled(trackIds)
            currentRetryCount = 0
            consecutiveErrors = 0
            prepareCurrent(autoPlay = true, positionMs = 0)
        }

        fun playQueueIndex(index: Int) = post {
            releaseCurrentTrack(PlaybackExitReason.MANUAL_SELECT)
            if (queue.moveToQueueIndex(index) != null) {
                beginExplicitPlaybackRequest()
                currentRetryCount = 0
                consecutiveErrors = 0
                prepareCurrent(autoPlay = true, positionMs = prepareAudiobookStart(forceOrdered = false))
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

        fun applyVolumeModeTransition(value: PlayerPreferencesState, systemVolumeIndex: Int) = post {
            applyVolumeModeTransitionInternal(value, systemVolumeIndex)
        }

        fun reconcileAvailability(availableTrackIds: Set<Long>) = post {
            reconcileAvailabilityInternal(availableTrackIds)
        }

        internal fun historySummary(): PlaybackHistory.Summary = this@PlaybackService.historySummary()

        fun clearHistory(): Boolean = this@PlaybackService.clearHistory()

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
    @Volatile private var lastStartId = 0
    private var requestedPreferences = PlayerPreferencesState()
    private var currentPreferences = PlayerPreferencesState()
    private var audioEffectsState = AudioEffectsState()
    private var pendingPositionMs = 0L
    private var pendingAutoPlay = false
    private var duckedForFocus = false
    private var fadeInProgress = false
    private var currentRetryCount = 0
    private var preloadRetryCount = 0
    private var preloadAttemptedForRequestId = 0L
    private var lastEligibleNextTrackId: Long? = null
    private var lastPromotedRequestId = 0L
    private var consecutiveErrors = 0
    private var lastReleasedRequestId = 0L
    private lateinit var playbackHistory: PlaybackHistory
    private var listeningSession: ListeningSession? = null

    private var appliedCrossfadeMs = -1L
    private var appliedGapless: Boolean? = null
    private var lastPeriodicPersistedPositionMs = Long.MIN_VALUE
    private var lastPublishedProgressSecond = -1L
    private var currentPreparationRecorded = false
    private var requestCounter = 0L
    private var activeRequestId = 0L
    private var preloadedRequestId: Long? = null
    private var preloadedTrack: Track? = null
    private var lastNotificationKey: NotificationKey? = null
    @Volatile private var lastPersistedQueueItems: List<Long>? = null
    private var queuePersistScheduled = false
    private val queuePersistRunnable = Runnable {
        queuePersistScheduled = false
        flushQueuePersist()
    }
    private var currentWatchdogRequest: Long? = null
    private var awaitingStartRequest: Long? = null
    private var nextWatchdogRequest: Long? = null
    private var audioEffectsSessionApplied = false
    private val fadeGeneration = GenerationGuard()
    private var outputVolume = 1f
    private var transientOutputGain = 1f
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
        playbackHistory = PlaybackHistory(
            directoryProvider = {
                Y2StoragePaths.availableRoots().firstOrNull()?.let { File(it.directory, "Y2Player") }
            },
            appVersion = com.schulzcode.y2player.BuildConfig.VERSION_NAME,
            onWarning = { message -> logger.warn("History", message) }
        )
        storageMonitor = container.storageMonitor
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        playbackThread = HandlerThread("y2-playback").apply { start() }
        playbackHandler = Handler(playbackThread.looper)
        routeMonitor = AudioRouteMonitor(this) { event -> post { handleRouteEvent(event) } }
        routeMonitor.start()
        storageMonitor.addListener(storageListener)

        post {
            dacController.applyDirectMode(requestedPreferences.audioQualityMode == AudioQualityMode.DIRECT_DAC)
            engine = runCatching<PlaybackEngine> { FfmpegPlaybackEngine(this, logger) }
                .onFailure(::logEngineInitializationFailure)
                .getOrElse { UnavailablePlaybackEngine("FFmpeg audio engine is unavailable") }
                .also { it.setListener(this) }
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
            // After restorePersistedState, which settles the shuffle state the effective
            // crossfade depends on, and outside it, since it returns early in safe mode.
            syncTransition()
            syncReplayGain()
            publishSnapshot()
        }
    }

    private fun logEngineInitializationFailure(error: Throwable) {
        val linkerError = generateSequence(error) { it.cause }
            .filterIsInstance<UnsatisfiedLinkError>()
            .firstOrNull()
        if (linkerError == null) {
            logger.error("Playback", "FFmpeg engine initialization failed", error)
            return
        }
        val systemLibrary = File("/system/lib/liby2audio.so")
        val message = buildString {
            append("FFmpeg engine initialization failed: ")
            append(linkerError.message ?: linkerError.toString())
            append("; java.library.path=")
            append(System.getProperty("java.library.path") ?: "<unset>")
            append("; /system/lib/liby2audio.so exists=")
            append(systemLibrary.exists())
            append("; size=")
            append(if (systemLibrary.isFile) systemLibrary.length() else -1L)
            append("; ABI=")
            append(Build.CPU_ABI ?: "<unset>")
            append("; ABI2=")
            append(Build.CPU_ABI2 ?: "<unset>")
        }
        logger.error("Playback", message, linkerError)
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
        return true
    }

    override fun onRebind(intent: Intent?) {
        boundClients += 1
        logger.info("Playback", "client rebound count=$boundClients")
        post(::refreshProgressForNewClient)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_MEDIA_BUTTON) {
            val keyCode = intent.getIntExtra(EXTRA_MEDIA_KEY_CODE, KeyEvent.KEYCODE_UNKNOWN)
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
        if (::libraryRepository.isInitialized) libraryRepository.setPlaybackActive(false)
        if (::routeMonitor.isInitialized) routeMonitor.stop()
        if (::storageMonitor.isInitialized) storageMonitor.removeListener(storageListener)
        mainHandler.removeCallbacksAndMessages(null)

        if (::playbackHandler.isInitialized) {
            playbackHandler.removeCallbacksAndMessages(null)
            persistenceExecutor.queue.clear()
            val cleanup = Runnable {
                runCatching { releaseCurrentTrack(PlaybackExitReason.SERVICE_SHUTDOWN) }
                if (::queue.isInitialized) runCatching {
                    playbackHandler.removeCallbacks(queuePersistRunnable)
                    queuePersistScheduled = false
                    if (queue.snapshot().items === lastPersistedQueueItems) persistSession() else flushQueuePersist()
                }
                if (::audioFocus.isInitialized) audioFocus.abandon()
                if (::remoteControl.isInitialized) remoteControl.release()
                if (::audioEffectsController.isInitialized) audioEffectsController.release()
                if (::engine.isInitialized) engine.release()
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
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            releaseTransientPlaybackResources("trim memory level=$level")
        }
    }

    private fun releaseTransientPlaybackResources(reason: String) = post {
        cancelVolumeFade()
        clearPreload()
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
        if (playRequested) {
            awaitingStartRequest = requestId
            startEngineWithFade()
            scheduleCurrentWatchdog(requestId)
        }
        snapshot = buildSnapshot(
            status = if (playRequested) PlaybackStatus.PREPARING else PlaybackStatus.PAUSED,
            positionMs = requestedPosition,
            durationMs = durationMs,
            pauseReason = when {
                playRequested -> PauseReason.NONE
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
            "started" to playRequested,
            "focus" to focusGranted
        )
        pendingPositionMs = 0
        pendingAutoPlay = false
        persistSession()
        revalidatePreload()
        publishSnapshot()
    }

    override fun onStarted(requestId: Long) = post {
        if (!PlaybackRequestGate.accepts(requestId, activeRequestId)) return@post
        cancelCurrentWatchdog(requestId)
        awaitingStartRequest = null
        listeningSession?.resume()
        val fadeMs = currentPreferences.pauseResumeFadeMs.toLong()
        if (fadeMs > 0) fadeOutputGain(1f, fadeMs)
        else setTransientOutputGain(1f)
        recordCurrentPlaybackStart()
        snapshot = buildSnapshot(
            PlaybackStatus.PLAYING,
            engine.currentPositionMs(),
            currentDuration(),
            PauseReason.NONE
        )
        enterForeground()
        eventLog.info(
            Sub.PLAYBACK, Ev.PLAYBACK_START,
            "request" to requestId,
            "track" to currentTrack?.id,
            "codec" to currentTrack?.codec,
            "sampleRate" to currentTrack?.sampleRate,
            "reason" to "confirmed"
        )
        scheduleProgress()
        revalidatePreload()
        persistSession()
        publishSnapshot()
    }

    override fun onNextTrackNeeded(currentRequestId: Long) = post {
        if (!PlaybackRequestGate.accepts(currentRequestId, activeRequestId)) return@post
        if (preloadedRequestId != null) return@post
        if (preloadAttemptedForRequestId == currentRequestId) return@post
        val nextTrack = eligibleNextTrack() ?: return@post
        preloadAttemptedForRequestId = currentRequestId
        preloadRetryCount = 0
        logger.info(
            "Playback",
            "engine requested the next track request=$currentRequestId next=${nextTrack.id}"
        )
        preloadTrack(nextTrack)
    }

    override fun onNextPrepared(requestId: Long, durationMs: Long) = post {
        if (!PlaybackRequestGate.accepts(requestId, preloadedRequestId ?: 0)) return@post
        cancelNextWatchdog(requestId)
        logger.info("Playback", "next ready request=$requestId duration=$durationMs track=${preloadedTrack?.id}")
        refreshSnapshot()
        publishSnapshot()
    }

    override fun onTrackPromoted(
        previousRequestId: Long,
        promotedRequestId: Long,
        durationMs: Long
    ) = post {
        if (!PlaybackRequestGate.accepts(promotedRequestId, preloadedRequestId ?: 0)) {
            logger.warn(
                "Playback",
                "ignored stale promotion request=$promotedRequestId expected=$preloadedRequestId"
            )
            return@post
        }
        val promotedTrack = preloadedTrack ?: return@post
        cancelNextWatchdog(promotedRequestId)
        releaseCurrentTrack(PlaybackExitReason.COMPLETED)

        val advanced = when (sleepTimerMode) {
            SleepTimerMode.END_ALBUM, SleepTimerMode.END_QUEUE -> queue.nextInCurrentPass()
            else -> queue.next()
        }
        if (advanced != promotedTrack.id) {
            logger.warn(
                "Playback",
                "queue transition mismatch expected=${promotedTrack.id} actual=$advanced"
            )
            queue.snapshot().items.indexOf(promotedTrack.id).takeIf { it >= 0 }
                ?.let(queue::moveToQueueIndex)
        }
        currentTrack = promotedTrack
        activeRequestId = promotedRequestId
        lastPromotedRequestId = promotedRequestId
        preloadedRequestId = null
        preloadedTrack = null
        preloadAttemptedForRequestId = 0L
        lastEligibleNextTrackId = null
        currentPreparationRecorded = false
        currentRetryCount = 0
        logger.info(
            "Playback",
            "ownership promoted request=$previousRequestId -> $promotedRequestId " +
                "track=${promotedTrack.id}"
        )
    }

    override fun onTransitioned(requestId: Long, durationMs: Long) = post {
        handleTransitioned(requestId, durationMs)
    }

    private fun handleTransitioned(requestId: Long, durationMs: Long) {
        if (requestId <= 0L || requestId != lastPromotedRequestId) {
            logger.warn(
                "Playback",
                "ignored stale transition request=$requestId promoted=$lastPromotedRequestId"
            )
            return
        }
        lastPromotedRequestId = 0L
        val audibleTrack = currentTrack ?: return
        recordCurrentPlaybackStart()
        snapshot = buildSnapshot(
            status = PlaybackStatus.PLAYING,
            positionMs = engine.currentPositionMs(),
            durationMs = durationMs,
            pauseReason = PauseReason.NONE
        )
        enterForeground()
        eventLog.info(
            Sub.PLAYBACK, Ev.PLAYBACK_START,
            "request" to requestId,
            "track" to audibleTrack.id,
            "codec" to audibleTrack.codec,
            "sampleRate" to audibleTrack.sampleRate,
            "reason" to if (currentPreferences.crossfadeMs > 0) "crossfade" else "gapless"
        )
        persistSession(positionOverride = snapshot.positionMs)
        revalidatePreload()
        scheduleProgress()
        publishSnapshot()
    }

    override fun onCompleted(requestId: Long) = post {
        if (!PlaybackRequestGate.accepts(requestId, activeRequestId) || snapshot.status != PlaybackStatus.PLAYING) return@post
        if (shouldStopAfterCurrentTrack()) {
            stopForSleepTimer()
            return@post
        }
        nextInternal(userInitiated = false)
    }

    override fun onError(requestId: Long, message: String, failure: PlaybackFailure) = post {
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
        audioEffectsSessionApplied = false

        if (failure == PlaybackFailure.UNSUPPORTED && track != null) {
            libraryRepository.recordPlaybackFailure(track.id, message)
            logger.warn("Playback", "track=${track.id} marked undecodable: $message")
            eventLog.warn(
                Sub.PLAYBACK, Ev.PLAYBACK_ERROR,
                "track" to track.id,
                "format" to track.extension,
                "codec" to track.codec,
                "verdict" to "undecodable"
            )
        } else if (currentRetryCount < MAX_TRACK_RETRIES && track?.let(::resolvePlayableTrack) != null) {
            currentRetryCount += 1
            logger.warn("Playback", "retrying track=${track.id} attempt=$currentRetryCount")
            prepareCurrent(shouldAutoPlay, snapshot.positionMs, preserveRetry = true)
            return@post
        }

        releaseCurrentTrack(PlaybackExitReason.ERROR)
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
            clearPreload(preserveAttemptGuard = true)
            refreshSnapshot()
            publishSnapshot()
        }
    }

    override fun onPermanentLoss() = post {
        safetyPolicy.onPermanentFocusLoss()
        duckedForFocus = false
        if (snapshot.status in ACTIVE_STATUSES) pauseInternal(PauseReason.AUDIO_FOCUS, abandonFocus = false)
    }

    override fun onTransientLoss() = post {
        val wasPlaying = engine.isPlaying()
        safetyPolicy.onTransientFocusLoss(wasPlaying)
        duckedForFocus = false
        if (snapshot.status in ACTIVE_STATUSES) pauseInternal(PauseReason.AUDIO_FOCUS, abandonFocus = false)
    }

    override fun onDuck() = post {
        if (!engine.isPlaying()) return@post
        if (currentPreferences.duckOnFocusLoss) {
            duckedForFocus = true
            fadeToVolume(effectiveVolume(), SHORT_FOCUS_FADE_MS)
        } else {
            safetyPolicy.onTransientFocusLoss(wasPlaying = true)
            pauseInternal(PauseReason.AUDIO_FOCUS, abandonFocus = false)
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
                if (snapshot.status in ACTIVE_STATUSES) pauseInternal(PauseReason.USER)
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
        awaitingStartRequest = activeRequestId
        startEngineWithFade()
        scheduleCurrentWatchdog(activeRequestId)
        snapshot = buildSnapshot(
            PlaybackStatus.PREPARING,
            currentPosition(),
            currentDuration(),
            PauseReason.NONE
        )
        publishSnapshot()
    }

    private fun startEngineWithFade() {
        // Re-asserted on every start: on API 19 the last registrant wins.
        MediaButtonReceiver.register(this, logger)
        cancelVolumeFade()
        setOutputVolume(effectiveVolume())
        if (currentPreferences.pauseResumeFadeMs > 0) setTransientOutputGain(0f)
        engine.start()
    }

    private fun releasePlaybackResources(
        cancelPreparation: Boolean,
        abandonFocus: Boolean = true,
        cancelFade: Boolean = true
    ) {
        pendingAutoPlay = false
        awaitingStartRequest = null
        if (abandonFocus) duckedForFocus = false
        if (cancelFade) cancelVolumeFade()
        playbackHandler.removeCallbacks(progressRunnable)
        if (cancelPreparation) cancelActivePreparation()
        if (abandonFocus && ::audioFocus.isInitialized) audioFocus.abandon()
        leaveForeground()
    }

    private fun pauseInternal(
        reason: PauseReason,
        abandonFocus: Boolean = true,
        errorMessage: String? = null
    ) {
        if (reason == PauseReason.USER) safetyPolicy.onManualPause()
        val position = currentPosition()
        val duration = currentDuration()
        val preparing = snapshot.status == PlaybackStatus.PREPARING
        releasePlaybackResources(
            cancelPreparation = preparing,
            abandonFocus = abandonFocus,
            cancelFade = preparing
        )

        if (!preparing && ::engine.isInitialized) {
            cancelVolumeFade(resetOutputGain = false)
            if (!engine.isTransitioning) clearPreload()
            engine.pause()
            setTransientOutputGain(1f)
            setOutputVolume(effectiveVolume())
        }
        listeningSession?.pause()
        snapshot = buildSnapshot(PlaybackStatus.PAUSED, position, duration, reason, errorMessage)
        currentTrack?.let { saveAudiobookProgress(it, position, duration) }
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
        val duration = currentDuration()
        if (duration <= 0) return
        seekAbsoluteInternal((currentPosition() + deltaMs).coerceIn(0, duration))
    }

    private fun seekAbsoluteInternal(positionMs: Long) {
        val duration = currentDuration()
        if (duration <= 0) return
        val target = positionMs.coerceIn(0, duration)
        if (snapshot.status == PlaybackStatus.PREPARING) {
            pendingPositionMs = target
            snapshot = buildSnapshot(PlaybackStatus.PREPARING, target, duration, snapshot.pauseReason, snapshot.errorMessage)
            persistSession(positionOverride = target)
            publishSnapshot()
            return
        }
        if (engine.isTransitioning) clearPreload()
        engine.seekTo(target)
        snapshot = buildSnapshot(snapshot.status, target, duration, snapshot.pauseReason, snapshot.errorMessage)
        persistSession(positionOverride = target)
        revalidatePreload()
        publishSnapshot()
    }

    private fun previousInternal() {
        releaseCurrentTrack(PlaybackExitReason.MANUAL_PREVIOUS)
        beginExplicitPlaybackRequest()
        consecutiveErrors = 0
        currentRetryCount = 0
        val autoPlay = safetyPolicy.canAutomaticallyStart()
        val threshold = currentPreferences.previousRestartThresholdMs.toLong()
        if (threshold > 0 && engine.currentPositionMs() > threshold) {
            if (snapshot.status == PlaybackStatus.PLAYING) seekAbsoluteInternal(0)
            else prepareCurrent(autoPlay, 0)
            return
        }
        if (queue.previousIgnoringRepeatOne() != null) {
            prepareCurrent(autoPlay, 0)
        }
    }

    private fun nextInternal(userInitiated: Boolean) {
        releaseCurrentTrack(
            if (userInitiated) PlaybackExitReason.MANUAL_NEXT else PlaybackExitReason.COMPLETED
        )
        if (userInitiated) beginExplicitPlaybackRequest()
        if (userInitiated) consecutiveErrors = 0
        val shouldAutoPlay = (userInitiated || snapshot.status == PlaybackStatus.PLAYING) && safetyPolicy.canAutomaticallyStart()
        if (preloadedRequestId != null && (!shouldAutoPlay || audioFocus.request()) &&
            engine.skipToPreparedNext()
        ) {
            return
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
        releaseCurrentTrack(
            if (endedBySleepTimer) PlaybackExitReason.SLEEP_TIMER else PlaybackExitReason.QUEUE_END
        )
        if (!endedBySleepTimer) clearAudiobookProgressForCurrent()
        safetyPolicy.onManualPause()
        releasePlaybackResources(cancelPreparation = false)
        clearPreload()
        engine.pause()
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
            if (track != null && !track.decodeFailed && resolvePlayableTrack(track) != null) return true
            logger.warn(
                "Playback",
                "skipping track=$next reason=${if (track?.decodeFailed == true) "undecodable" else "unavailable"}"
            )
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
        releaseCurrentTrack(PlaybackExitReason.EXPLICIT_STOP)
        clearSleepTimer()
        safetyPolicy.onSessionCleared()
        releasePlaybackResources(cancelPreparation = true)
        clearPreload()
        queue.clear()
        currentTrack = null
        pendingPositionMs = 0
        snapshot = PlaybackSnapshot(audioEffects = audioEffectsState, sleepTimerMode = sleepTimerMode)
        playbackHandler.removeCallbacks(queuePersistRunnable)
        queuePersistScheduled = false
        flushQueuePersist()
        eventLog.info(Sub.PLAYBACK, Ev.PLAYBACK_STOP, "reason" to "queue_cleared")
        publishSnapshot()
    }

    private fun refreshSnapshot() {
        snapshot = buildSnapshot(snapshot.status, snapshot.positionMs, snapshot.durationMs, snapshot.pauseReason, snapshot.errorMessage)
    }

    private fun afterQueueMutation() {
        syncReplayGain()
        syncTransition()
        refreshSnapshot()
        persistQueueState()
        revalidatePreload()
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
            if (!preserveRetry) releaseCurrentTrack(PlaybackExitReason.SOURCE_REMOVED)
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
        if (!preserveRetry) releaseCurrentTrack(PlaybackExitReason.UNKNOWN)
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
        preloadAttemptedForRequestId = 0L
        lastEligibleNextTrackId = null
        lastPromotedRequestId = 0L
        engine.prepare(track, activeRequestId)
        syncReplayGain()
        syncTransition()
        scheduleCurrentWatchdog(activeRequestId)
    }

    private fun expectedNextTrackId(): Long? = when (sleepTimerMode) {
        SleepTimerMode.END_TRACK -> null
        SleepTimerMode.END_ALBUM, SleepTimerMode.END_QUEUE -> queue.peekNextInCurrentPass()
        else -> queue.peekNext()
    }

    private fun eligibleNextTrack(): Track? {
        if (currentTrack == null) return null
        if (queue.snapshot().repeatMode == RepeatMode.ONE) return null
        val nextId = expectedNextTrackId() ?: return null
        val nextTrack = libraryRepository.findTrack(nextId)
            ?.takeIf { !it.decodeFailed }
            ?.let(::resolvePlayableTrack)
            ?: return null
        if (shouldStopBefore(nextTrack)) return null
        return nextTrack
    }

    private fun revalidatePreload() {
        if (!::engine.isInitialized) return
        val expected = eligibleNextTrack()
        val expectedId = expected?.id
        val targetChanged = expectedId != lastEligibleNextTrackId
        lastEligibleNextTrackId = expectedId

        if (expected == null) {
            if (preloadedTrack != null) clearPreload()
            preloadAttemptedForRequestId = 0L
            return
        }
        if (preloadedTrack != null) {
            if (preloadedTrack?.id != expected.id) {
                clearPreload()
                preloadAttemptedForRequestId = 0L
            }
            return
        }
        if (targetChanged) {
            preloadAttemptedForRequestId = 0L
            engine.clearNext()
        }
    }

    private fun preloadTrack(track: Track) {
        clearPreload(preserveAttemptGuard = true)
        preloadedTrack = track
        preloadedRequestId = ++requestCounter
        val requestId = preloadedRequestId ?: return
        engine.prepareNext(track, requestId)
        scheduleNextWatchdog(requestId)
        refreshSnapshot()
    }

    private fun clearPreload(preserveAttemptGuard: Boolean = false) {
        nextWatchdogRequest = null
        if (::playbackHandler.isInitialized) playbackHandler.removeCallbacks(nextWatchdogRunnable)
        if (::engine.isInitialized) engine.clearNext()
        preloadedRequestId = null
        preloadedTrack = null
        if (!preserveAttemptGuard) preloadAttemptedForRequestId = 0L
    }

    private fun cancelActivePreparation() {
        awaitingStartRequest = null
        preloadAttemptedForRequestId = 0L
        lastEligibleNextTrackId = null
        lastPromotedRequestId = 0L
        activeRequestId = ++requestCounter
        currentWatchdogRequest = null
        playbackHandler.removeCallbacks(currentWatchdogRunnable)
        pendingAutoPlay = false
        if (::engine.isInitialized) engine.cancel()
    }

    // Must run before currentTrack is replaced, or the outgoing position is lost.
    private fun releaseCurrentTrack(reason: PlaybackExitReason) {
        val track = currentTrack ?: return
        val requestId = activeRequestId
        if (requestId == 0L || requestId == lastReleasedRequestId) return
        lastReleasedRequestId = requestId
        val positionMs = currentPosition()
        val durationMs = currentDuration()
        eventLog.debug(
            Sub.PLAYBACK, Ev.TRACK_RELEASED,
            "request" to requestId,
            "track" to track.id,
            "reason" to reason.code,
            "positionMs" to positionMs,
            "durationMs" to durationMs
        )
        // Consumed here, so a track that was prepared but never started cannot inherit
        // the previous track's listening time.
        val session = listeningSession
        listeningSession = null
        saveAudiobookProgress(track, positionMs, durationMs)
        recordPlaybackHistory(track, positionMs, reason, session)
    }

    private fun recordPlaybackHistory(
        track: Track,
        endPositionMs: Long,
        reason: PlaybackExitReason,
        session: ListeningSession?
    ) {
        if (session == null) return
        session.pause()
        val listened = session.listenedMs()
        if (listened < PlaybackHistory.MIN_SESSION_MS) return
        val queueState = queue.snapshot()
        val record = PlaybackSession(
            track = track,
            startedAtUtcMs = session.startedAtUtcMs,
            endedAtUptimeMs = SystemClock.elapsedRealtime(),
            startPositionMs = session.startPositionMs,
            endPositionMs = endPositionMs,
            listenedMs = listened,
            exitReason = reason,
            shuffleEnabled = queueState.shuffleEnabled,
            repeatMode = queueState.repeatMode
        )
        submitPersistence("playback history") { playbackHistory.append(record) }
    }

    internal fun historySummary(): PlaybackHistory.Summary =
        if (::playbackHistory.isInitialized) playbackHistory.summary() else PlaybackHistory.Summary()

    fun clearHistory(): Boolean =
        ::playbackHistory.isInitialized && playbackHistory.clear()

    private fun saveAudiobookProgress(track: Track, positionMs: Long, durationMs: Long) {
        val folderKey = track.audiobookFolderKey ?: return
        val storedPosition = PlaybackPositionPolicy.audiobookSavePosition(positionMs, durationMs)
        submitPersistence("audiobook progress") {
            database.saveAudiobookProgress(folderKey, track.id, storedPosition)
        }
    }

    private fun clearAudiobookProgressForCurrent() {
        val folderKey = currentTrack?.audiobookFolderKey ?: return
        logger.info("Playback", "audiobook finished key=$folderKey")
        submitPersistence("audiobook progress cleared") {
            database.deleteAudiobookProgress(folderKey)
        }
    }

    private fun prepareAudiobookStart(
        forceOrdered: Boolean = true,
        ignoreSavedPosition: Boolean = false
    ): Long {
        val trackId = queue.currentTrackId() ?: return 0
        val track = libraryRepository.findTrack(trackId) ?: return 0
        val folderKey = track.audiobookFolderKey ?: return 0
        if (forceOrdered && queue.applyOrderedPlayback()) {
            syncReplayGain()
            syncTransition()
        }
        if (ignoreSavedPosition) return 0
        val progress = runCatching { database.loadAudiobookProgress(folderKey) }
            .onFailure { logger.warn("Playback", "audiobook progress unreadable: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return 0
        if (libraryRepository.findTrack(progress.trackId) == null) {
            logger.info("Playback", "audiobook progress dropped; track ${progress.trackId} is gone")
            submitPersistence("audiobook progress cleanup") {
                database.deleteAudiobookProgress(folderKey)
            }
            return 0
        }
        if (progress.trackId != trackId) return 0
        val resume = PlaybackPositionPolicy.audiobookResumePosition(progress.positionMs, track.durationMs)
        if (resume > 0) {
            logger.info("Playback", "audiobook resume key=$folderKey track=$trackId positionMs=$resume")
        }
        return resume
    }

    private fun recordCurrentPlaybackStart() {
        if (currentPreparationRecorded) return
        val track = currentTrack ?: return
        currentPreparationRecorded = true
        listeningSession = ListeningSession(
            startedAtUtcMs = System.currentTimeMillis(),
            startPositionMs = currentPosition(),
            uptimeMs = SystemClock::uptimeMillis
        ).apply { resume() }
        libraryRepository.recordRecentlyPlayed(track.id)
        saveAudiobookProgress(track, currentPosition(), currentDuration())
        if (track.decodeFailed) libraryRepository.clearPlaybackFailure(track.id)
        logger.info("Playback", "playback started track=${track.id} ${track.title}")
    }

    private fun scheduleProgress() {
        playbackHandler.removeCallbacks(progressRunnable)
        playbackHandler.postDelayed(progressRunnable, progressInterval())
    }

    private fun progressInterval(): Long =
        if (boundClients == 0) BACKGROUND_PROGRESS_INTERVAL_MS else PROGRESS_INTERVAL_MS

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (snapshot.status != PlaybackStatus.PLAYING || !engine.isPlaying()) return
            val position = engine.currentPositionMs()
            val duration = engine.durationMs()
            if (consecutiveErrors > 0 && position >= STABLE_PLAYBACK_RESET_MS) consecutiveErrors = 0
            val progressSecond = position / 1_000L
            if (progressSecond != lastPublishedProgressSecond) {
                lastPublishedProgressSecond = progressSecond
                updateInternalProgress(position, duration)
                publishUiProgressIfBound()
            }
            val persistInterval = if (boundClients == 0) BACKGROUND_POSITION_PERSIST_INTERVAL_MS else POSITION_PERSIST_INTERVAL_MS
            val lastPosition = lastPeriodicPersistedPositionMs
            if (lastPosition == Long.MIN_VALUE || position < lastPosition || position - lastPosition >= persistInterval) {
                lastPeriodicPersistedPositionMs = position
                submitPersistence("position persistence") {
                    database.updatePlaybackPosition(if (currentPreferences.resumePosition) position else 0)
                }
                // An audiobook must survive an unclean stop. Without this the only
                // writes are chapter start, pause and release, so a kill mid-chapter
                // left the saved position at the chapter-start value of zero.
                currentTrack?.let { saveAudiobookProgress(it, position, duration) }
            }
            playbackHandler.postDelayed(this, progressInterval())
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
            val message = if (awaitingStartRequest == requestId) {
                "Playback did not start"
            } else {
                "Preparation timed out"
            }
            awaitingStartRequest = null
            logger.error("Playback", "$message request=$requestId track=${currentTrack?.absolutePath}")
            engine.cancel()
            onError(requestId, message)
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
        if (requestId == preloadedRequestId) {
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
        revalidatePreload()
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
        revalidatePreload()
    }

    private fun releaseStorageSource() {
        releaseCurrentTrack(PlaybackExitReason.SOURCE_REMOVED)
        val position = currentPosition()
        val duration = currentDuration()
        releasePlaybackResources(cancelPreparation = true)
        clearPreload()
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
        releaseCurrentTrack(PlaybackExitReason.SAFE_MODE)
        clearSleepTimer()
        safetyPolicy.onSessionCleared()
        releasePlaybackResources(cancelPreparation = true)
        clearPreload()
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
        syncTransition()
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

    private fun currentPosition(): Long = when {
        snapshot.status == PlaybackStatus.PREPARING ->
            pendingPositionMs.takeIf { it > 0 } ?: snapshot.positionMs
        !::engine.isInitialized -> snapshot.positionMs
        engine.durationMs() > 0 -> engine.currentPositionMs()
        else -> snapshot.positionMs
    }

    private fun currentDuration(): Long {
        if (!::engine.isInitialized) return snapshot.durationMs
        return engine.durationMs().takeIf { it > 0 } ?: snapshot.durationMs
    }

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
                    pauseInternal(PauseReason.SLEEP_TIMER)
                }
            }
            sleepTimerCallback = callback
            playbackHandler.postDelayed(callback, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        }
        revalidatePreload()
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
        releaseCurrentTrack(PlaybackExitReason.SLEEP_TIMER)
        clearSleepTimer()
        safetyPolicy.onManualPause()
        releasePlaybackResources(cancelPreparation = false)
        clearPreload()
        engine.pause()
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
        if (modeChanged) clearPreload()
        requestedPreferences = value
        currentPreferences = effective
        syncTransition()
        syncReplayGain()
        if (appVolumeGain() != previousGain && !fadeInProgress) setOutputVolume(effectiveVolume())
        engine.setBalance(effective.balance)
        dacController.applyDirectMode(value.audioQualityMode == AudioQualityMode.DIRECT_DAC)
        audioEffectsState = audioEffectsController.apply(effective)
        if (modeChanged) revalidatePreload()
        refreshSnapshot()
        publishSnapshot()
    }

    private fun applyVolumeModeTransitionInternal(value: PlayerPreferencesState, systemVolumeIndex: Int) {
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

    private fun appVolumeGain(): Float =
        if (currentPreferences.volumeMode == VolumeMode.PERCEPTUAL) {
            VolumeCurve.gainForLevel(currentPreferences.volumeLevel)
        } else 1f

    private fun effectiveVolume(): Float =
        appVolumeGain() * (if (duckedForFocus) EFFECTIVE_DUCK_VOLUME else 1f)

    private fun fadeToVolume(target: Float, durationMs: Long, onComplete: (() -> Unit)? = null) {
        if (transientOutputGain != 1f) setTransientOutputGain(1f)
        val from = outputVolume
        val safeTarget = target.coerceIn(0f, 1f)
        val generation = fadeGeneration.advance()
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
                if (!fadeGeneration.isCurrent(generation)) return
                val fraction = ((SystemClock.uptimeMillis() - startedAt).toFloat() / durationMs).coerceIn(0f, 1f)
                setOutputVolume(from + (safeTarget - from) * fraction)
                if (fraction >= 1f) {
                    fadeInProgress = false
                    onComplete?.invoke()
                    setOutputVolume(effectiveVolume())
                    normalizeSystemVolumeForAppControl()
                } else playbackHandler.postDelayed(this, VOLUME_FADE_STEP_MS)
            }
        }
        playbackHandler.post(runnable)
    }

    private fun fadeOutputGain(target: Float, durationMs: Long) {
        val from = transientOutputGain
        val safeTarget = target.coerceIn(0f, 1f)
        val generation = fadeGeneration.advance()
        if (durationMs <= 0) {
            fadeInProgress = false
            setTransientOutputGain(safeTarget)
            return
        }
        fadeInProgress = true
        val startedAt = SystemClock.uptimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                if (!fadeGeneration.isCurrent(generation)) return
                val fraction = ((SystemClock.uptimeMillis() - startedAt).toFloat() / durationMs)
                    .coerceIn(0f, 1f)
                setTransientOutputGain(from + (safeTarget - from) * fraction)
                if (fraction >= 1f) {
                    fadeInProgress = false
                    setOutputVolume(effectiveVolume())
                    normalizeSystemVolumeForAppControl()
                } else playbackHandler.postDelayed(this, VOLUME_FADE_STEP_MS)
            }
        }
        playbackHandler.post(runnable)
    }

    private fun cancelVolumeFade(resetOutputGain: Boolean = true) {
        fadeGeneration.advance()
        fadeInProgress = false
        if (resetOutputGain && ::engine.isInitialized) {
            setTransientOutputGain(1f)
            setOutputVolume(effectiveVolume())
        }
    }

    private fun setOutputVolume(value: Float) {
        outputVolume = value.coerceIn(0f, 1f)
        engine.setVolume(outputVolume)
    }

    private fun setTransientOutputGain(value: Float) {
        transientOutputGain = value.coerceIn(0f, 1f)
        engine.setOutputGain(transientOutputGain)
    }

    private fun beginExplicitPlaybackRequest() {
        safetyPolicy.onExplicitPlaybackRequest(routeMonitor.snapshot())
    }

    private fun handleRouteEvent(event: AudioRouteMonitor.Event) {
        logger.info(
            "AudioRoute",
            "action=${event.action} wired=${event.routes.wired} bluetooth=${event.routes.bluetooth} noisy=${event.becomingNoisy}"
        )
        normalizeSystemVolumeForAppControl()
        val mustPause = safetyPolicy.onRoutesChanged(
            event.routes,
            becomingNoisy = event.becomingNoisy,
            speakerFallbackAllowed = !currentPreferences.pauseOnDisconnect
        )
        if (mustPause) {
            handlePrivateRouteLoss(event.action)
        } else if (::queue.isInitialized) {
            refreshSnapshot()
            publishSnapshot()
        }
    }

    private fun handlePrivateRouteLoss(action: String) {
        cancelVolumeFade()
        duckedForFocus = false
        logger.warn("Playback", "private output lost action=$action; pausing without speaker fallback")
        clearPreload()
        pauseInternal(
            PauseReason.OUTPUT_DISCONNECTED,
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

    private fun syncTransition() {
        if (!::engine.isInitialized || !::queue.isInitialized) return
        val crossfadeMs = currentPreferences.crossfadeMode.effectiveMs(
            currentPreferences.crossfadeMs,
            queue.snapshot().shuffleEnabled
        )
        val gapless = currentPreferences.gaplessEnabled
        if (crossfadeMs == appliedCrossfadeMs && gapless == appliedGapless) return
        val firstApplication = appliedGapless == null
        appliedCrossfadeMs = crossfadeMs
        appliedGapless = gapless
        engine.configureTransition(gaplessEnabled = gapless, crossfadeMs = crossfadeMs)
        if (firstApplication) return
        logger.info("Playback", "transition changed gapless=$gapless crossfadeMs=$crossfadeMs")
        if (!engine.isTransitioning) clearPreload()
        revalidatePreload()
    }

    private fun syncReplayGain() {
        if (!::engine.isInitialized || !::queue.isInitialized) return
        engine.configureReplayGain(
            currentPreferences.replayGainMode,
            queue.snapshot().shuffleEnabled
        )
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
            queuePersistScheduled = true
            playbackHandler.postDelayed(queuePersistRunnable, QUEUE_PERSIST_DEBOUNCE_MS)
        }
    }

    private fun flushQueuePersist() {
        if (!::queue.isInitialized) return
        val items = queue.snapshot().items
        val session = queue.session(currentPersistPosition())
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
        return currentPosition()
    }

    private fun addListener(listener: Listener) {
        listeners += listener
        mainHandler.post { listener.onPlaybackChanged(snapshot) }
    }

    private fun removeListener(listener: Listener) { listeners -= listener }

    private fun publishSnapshot() {
        val value = snapshot
        libraryRepository.setPlaybackActive(value.status == PlaybackStatus.PLAYING)
        if (::remoteControl.isInitialized) remoteControl.update(value, currentTrack)
        mainHandler.post {
            listeners.forEach { it.onPlaybackChanged(value) }
            updateNotificationIfNeeded(value)
        }
    }

    private fun updateInternalProgress(position: Long, duration: Long) {
        snapshot = snapshot.copy(
            status = PlaybackStatus.PLAYING,
            positionMs = position.coerceAtLeast(0),
            durationMs = duration.coerceAtLeast(0),
            pauseReason = PauseReason.NONE,
            errorMessage = null,
            sleepTimerRemainingMs = sleepTimerDeadlineElapsed
                ?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0) }
        )
    }

    private fun publishUiProgressIfBound() {
        if (boundClients == 0) return
        val value = snapshot
        mainHandler.post { listeners.forEach { it.onPlaybackChanged(value) } }
    }

    private fun refreshProgressForNewClient() {
        if (!::engine.isInitialized) return
        if (snapshot.status == PlaybackStatus.PLAYING && engine.isPlaying()) {
            val position = engine.currentPositionMs()
            val duration = engine.durationMs()
            updateInternalProgress(position, duration)
            lastPublishedProgressSecond = position / 1_000L
            scheduleProgress()
        }
        refreshSnapshot()
        publishSnapshot()
    }

    private fun enterForeground() {
        startForeground(NOTIFICATION_ID, createNotification())
        lastNotificationKey = notificationKey()
    }

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
        if (boundClients != 0 || snapshot.status in ACTIVE_STATUSES || safetyPolicy.hasPendingFocusResume()) return
        val startId = lastStartId
        val stopped = if (startId == 0) { stopSelf(); true } else stopSelfResult(startId)
        logger.info("Playback", "idle with no bound clients: startId=$startId stopped=$stopped")
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
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val BACKGROUND_PROGRESS_INTERVAL_MS = 5_000L
        private const val BACKGROUND_POSITION_PERSIST_INTERVAL_MS = 10_000L
        private const val POSITION_PERSIST_INTERVAL_MS = 5_000L
        private const val QUEUE_PERSIST_DEBOUNCE_MS = 500L
        private const val SHUTDOWN_TIMEOUT_MS = 2_000L
        private const val PREPARE_TIMEOUT_MS = 15_000L
        private const val PRELOAD_TIMEOUT_MS = 15_000L
        private const val MAX_TRACK_RETRIES = 1
        private const val STABLE_PLAYBACK_RESET_MS = 2_000L
        private const val EFFECTIVE_DUCK_VOLUME = 0.2f
        private const val SHORT_FOCUS_FADE_MS = 100L
        private const val VOLUME_FADE_STEP_MS = 25L
        private const val ROUTE_LOSS_MESSAGE = "Private audio output disconnected - playback paused"
        private val ACTIVE_STATUSES = setOf(PlaybackStatus.PLAYING, PlaybackStatus.PREPARING)
        private val RESUMABLE_ENGINE_STATES = setOf(
            EngineState.READY,
            EngineState.PAUSED,
            EngineState.PLAYING
        )
        private val PASS_BOUNDED_SLEEP_MODES = setOf(SleepTimerMode.END_ALBUM, SleepTimerMode.END_QUEUE)
    }
}
