package com.schulzcode.y2player.ui

import android.app.Activity
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import com.schulzcode.y2player.BuildConfig
import com.schulzcode.y2player.R
import com.schulzcode.y2player.Y2Application
import com.schulzcode.y2player.bluetooth.BluetoothController
import com.schulzcode.y2player.backup.UserDataBackupManager
import com.schulzcode.y2player.core.model.AudioQualityMode
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.state.AppAction
import com.schulzcode.y2player.core.state.AppEffect
import com.schulzcode.y2player.core.state.AppStore
import com.schulzcode.y2player.core.state.ListNavigationPolicy
import com.schulzcode.y2player.core.state.code
import com.schulzcode.y2player.core.state.Screen
import com.schulzcode.y2player.core.state.ScreenContent
import com.schulzcode.y2player.diagnostics.DiagnosticLogger
import com.schulzcode.y2player.diagnostics.DiagnosticsRepository
import com.schulzcode.y2player.diagnostics.Ev
import com.schulzcode.y2player.diagnostics.EventLog
import com.schulzcode.y2player.diagnostics.Sev
import com.schulzcode.y2player.diagnostics.Sub
import com.schulzcode.y2player.input.HapticController
import com.schulzcode.y2player.input.HapticLevel
import com.schulzcode.y2player.input.HapticPolicy
import com.schulzcode.y2player.input.HardwareKeyGate
import com.schulzcode.y2player.input.InputProbe
import com.schulzcode.y2player.input.Y2InputController
import com.schulzcode.y2player.library.LibraryRepository
import com.schulzcode.y2player.playback.LocalVolumeKeyPolicy
import com.schulzcode.y2player.playback.PlaybackService
import com.schulzcode.y2player.playback.VolumeCurve
import com.schulzcode.y2player.playback.VolumeMode
import com.schulzcode.y2player.playback.VolumeModeTransfer
import com.schulzcode.y2player.safe.SafeModeManager
import com.schulzcode.y2player.settings.AppPreferences
import com.schulzcode.y2player.settings.DisplayController
import com.schulzcode.y2player.settings.SystemHapticsController
import com.schulzcode.y2player.settings.UiSoundEffectsController
import com.schulzcode.y2player.storage.StorageMonitor
import com.schulzcode.y2player.storage.Y2StoragePaths
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var store: AppStore
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var diagnosticsRepository: DiagnosticsRepository
    private lateinit var preferences: AppPreferences
    private lateinit var displayController: DisplayController
    private lateinit var systemHapticsController: SystemHapticsController
    private lateinit var uiSoundEffectsController: UiSoundEffectsController
    private lateinit var bluetoothController: BluetoothController
    private var bluetoothUiActive = false
    private var uiStarted = false
    private lateinit var safeModeManager: SafeModeManager
    private lateinit var playerView: Y2PlayerView
    private lateinit var inputController: Y2InputController
    private lateinit var hapticController: HapticController
    private lateinit var storageMonitor: StorageMonitor
    private lateinit var eventLog: EventLog
    private lateinit var logger: DiagnosticLogger
    private lateinit var backupManager: UserDataBackupManager
    @Volatile private var pendingBackupPreview: UserDataBackupManager.Preview? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(8),
        { runnable -> Thread(runnable, "y2-ui-work").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private var playbackBinder: PlaybackService.LocalBinder? = null
    private var playbackBound = false
    private var stateListenerRegistered = false
    private var startupSafeModeDeadline = 0L
    private var lastTransientMessage: String? = null
    private var lastKeepScreenOn = false
    private var lastAvailabilityRevision = Long.MIN_VALUE
    private var screenReceiverRegistered = false
    @Volatile private var destroyed = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            playbackBinder?.cancelVolumeKeyRepeat()
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> playerView.setTextAnimationsVisible(false)
                Intent.ACTION_SCREEN_ON -> playerView.setTextAnimationsVisible(true)
            }
        }
    }

    private val stateListener = AppStore.StateListener { state ->
        playerView.render(state)
        val keepScreenOn = state.preferences.keepScreenOnWhilePlaying && state.playback.status == PlaybackStatus.PLAYING
        if (keepScreenOn != lastKeepScreenOn) {
            lastKeepScreenOn = keepScreenOn
            if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (state.transientMessage != lastTransientMessage) {
            lastTransientMessage = state.transientMessage
            mainHandler.removeCallbacks(clearMessageRunnable)
            val timeoutMs = Y2UiLogic.statusMessageTimeoutMs(state.transientMessage)
            if (timeoutMs > 0L) mainHandler.postDelayed(clearMessageRunnable, timeoutMs)
        }
        evaluateBluetoothUi()
    }

    private val clearMessageRunnable = Runnable { store.dispatch(AppAction.ShowMessage(null)) }
    private val markStableRunnable = Runnable { safeModeManager.markStartupStable() }
    private val effectListener = AppStore.EffectListener(::handleEffect)

    private val actionLogger = AppStore.ActionListener { action, state ->
        val wheel = action is AppAction.WheelMoved
        if (wheel) {
            eventLog.logRateLimited(
                "wheel_action", WHEEL_LOG_WINDOW_MS,
                Sev.DEBUG, Sub.REDUCER, Ev.ACTION,
                "action" to action.code,
                "screen" to state.currentScreen.code,
                "index" to state.selectedIndex
            )
        } else {
            eventLog.debug(
                Sub.REDUCER, Ev.ACTION,
                "action" to action.code,
                "screen" to state.currentScreen.code,
                "index" to state.selectedIndex
            )
        }
    }

    private val libraryListener = LibraryRepository.Listener { state ->
        store.dispatch(AppAction.LibraryChanged(state))
        if (state.availabilityRevision != lastAvailabilityRevision) {
            lastAvailabilityRevision = state.availabilityRevision
            playbackBinder?.reconcileAvailability(state.availableTrackIds)
        }
        if (!state.isScanning && !safeModeManager.isSafeMode()) {
            libraryRepository.requestInitialScan()
        }
    }

    private val diagnosticsListener = DiagnosticsRepository.Listener { state ->
        store.dispatch(AppAction.DiagnosticsChanged(state))
    }

    private val playbackListener = PlaybackService.Listener { snapshot ->
        store.dispatch(AppAction.PlaybackChanged(snapshot))
    }

    private val bluetoothListener = BluetoothController.Listener { state ->
        store.dispatch(AppAction.BluetoothChanged(state))
    }

    private val storageListener = StorageMonitor.Listener { device ->
        store.dispatch(AppAction.DeviceChanged(device.copy(hapticsAvailable = hapticController.available)))
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (!playbackBound || !uiStarted) return
            playbackBinder = service as? PlaybackService.LocalBinder
            playbackBinder?.addListener(playbackListener)
            playbackBinder?.applyPreferences(preferences.snapshot())
            val library = libraryRepository.snapshot()
            lastAvailabilityRevision = library.availabilityRevision
            playbackBinder?.reconcileAvailability(library.availableTrackIds)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackBinder?.removeListener(playbackListener)
            playbackBinder = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemeResource()
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE
        volumeControlStream = AudioManager.STREAM_MUSIC

        val container = (application as Y2Application).container
        eventLog = container.eventLog
        logger = container.logger
        store = container.appStore
        libraryRepository = container.libraryRepository
        diagnosticsRepository = container.diagnosticsRepository
        preferences = container.preferences
        backupManager = UserDataBackupManager(
            database = container.database,
            preferences = preferences,
            history = object : UserDataBackupManager.HistoryStore {
                override fun records(): List<String> = playbackBinder?.historyRecords()
                    ?: error("Playback is starting")
                override fun replace(records: List<String>): Boolean = playbackBinder?.replaceHistoryRecords(records)
                    ?: false
            },
            appVersion = BuildConfig.VERSION_NAME,
            rootsProvider = Y2StoragePaths::availableRoots
        )
        safeModeManager = container.safeModeManager
        val startupSafeMode = safeModeManager.beginUiStartup()
        displayController = DisplayController(this)
        systemHapticsController = SystemHapticsController(this)
        uiSoundEffectsController = UiSoundEffectsController(this)
        bluetoothController = container.bluetoothController
        if (startupSafeMode) bluetoothController.stop()
        hapticController = container.hapticController
        hapticController.setLevel(preferences.snapshot().hapticLevel)
        inputController = Y2InputController(::dispatchInput) {
            val current = store.state
            ListNavigationPolicy.allowsAcceleration(current.currentScreen, ScreenContent.rows(current).size)
        }
        playerView = Y2PlayerView(this, { action -> store.dispatch(action) }, container.artworkLoader)
        playerView.isSoundEffectsEnabled = preferences.snapshot().uiSoundEffectsEnabled
        setContentView(playerView)
        screenReceiverRegistered = runCatching {
            registerReceiver(
                screenStateReceiver,
                IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
            )
            true
        }.getOrDefault(false)
        startupSafeModeDeadline = SystemClock.uptimeMillis() + SAFE_MODE_KEY_WINDOW_MS

        storageMonitor = container.storageMonitor

        val snapshotStore = container.appStore
        eventLog.setStateProvider {
            val current = snapshotStore.state
            linkedMapOf<String, Any?>(
                "status" to current.playback.status.name,
                "route" to current.playback.outputRoute.name,
                "shuffle" to current.playback.shuffleEnabled,
                "repeat" to current.playback.repeatMode.name,
                "queueLen" to current.playback.queue.size,
                "charging" to current.device.charging,
                "battery" to current.device.batteryPercent,
                "scanning" to current.library.isScanning,
                "safeMode" to current.safeMode
            )
        }
        eventLog.info(Sub.ACTIVITY, Ev.ACTIVITY_CREATE, "safeMode" to startupSafeMode)

        store.addEffectListener(effectListener)
        store.addActionListener(actionLogger)
        libraryRepository.addListener(libraryListener, emitImmediately = false)
        diagnosticsRepository.addListener(diagnosticsListener, emitImmediately = false)
        bluetoothController.addListener(bluetoothListener)
        storageMonitor.addListener(storageListener)
        store.dispatch(AppAction.SafeModeChanged(safeModeManager.isSafeMode()))
        store.dispatch(AppAction.PreferencesChanged(preferences.snapshot()))
        store.dispatch(AppAction.DisplayChanged(displayController.snapshot()))
        if (!startupSafeMode) libraryRepository.loadCached()

        mainHandler.postDelayed(markStableRunnable, STARTUP_STABLE_DELAY_MS)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (inputAllowed(KeyEvent.KEYCODE_HOME)) {
            store.dispatch(AppAction.NavigateHome)
        }
    }

    override fun onStart() {
        super.onStart()
        eventLog.debug(Sub.ACTIVITY, Ev.ACTIVITY_START)
        uiStarted = true
        registerVisibleStateListeners()
        bindPlaybackServiceIfNeeded()
        evaluateBluetoothUi()
        backgroundExecutor.execute {
            diagnosticsRepository.refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        eventLog.debug(Sub.ACTIVITY, Ev.ACTIVITY_RESUME)
        val currentPreferences = preferences.snapshot()
        val systemHaptics = systemHapticsController.suppress()
        if (!systemHaptics.success) logger.warn("Settings", systemHaptics.message)
        storageMonitor.publish()
        store.dispatch(AppAction.DisplayChanged(displayController.snapshot()))
        store.dispatch(AppAction.PreferencesChanged(currentPreferences))
        if (!safeModeManager.isSafeMode()) store.dispatch(AppAction.BluetoothChanged(bluetoothController.snapshot()))
        playerView.isSoundEffectsEnabled = currentPreferences.uiSoundEffectsEnabled
        val uiSounds = currentPreferences.uiSoundEffectsEnabled
        val controller = uiSoundEffectsController
        backgroundExecutor.execute { controller.apply(uiSounds) }
        hapticController.setLevel(currentPreferences.hapticLevel)
        playerView.setTextAnimationsVisible(true)
    }

    override fun onPause() {
        eventLog.debug(Sub.ACTIVITY, Ev.ACTIVITY_PAUSE)
        playerView.setTextAnimationsVisible(false)
        super.onPause()
    }

    override fun onStop() {
        eventLog.debug(Sub.ACTIVITY, Ev.ACTIVITY_STOP)
        uiStarted = false
        evaluateBluetoothUi()
        unbindPlaybackServiceIfNeeded()
        unregisterVisibleStateListeners()
        inputController.resetHeldKeys()
        HardwareKeyGate.invalidateScreenState()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        playerView.trimMemory()
        ScreenContent.clearCachedRows()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            playerView.trimMemory()
            ScreenContent.clearCachedRows()
        }
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacks(clearMessageRunnable)
        mainHandler.removeCallbacks(markStableRunnable)
        unbindPlaybackServiceIfNeeded()
        libraryRepository.removeListener(libraryListener)
        diagnosticsRepository.removeListener(diagnosticsListener)
        bluetoothController.removeListener(bluetoothListener)
        storageMonitor.removeListener(storageListener)
        eventLog.info(Sub.ACTIVITY, Ev.ACTIVITY_DESTROY, "finishing" to isFinishing)
        unregisterVisibleStateListeners()
        store.removeEffectListener(effectListener)
        store.removeActionListener(actionLogger)
        if (screenReceiverRegistered) runCatching { unregisterReceiver(screenStateReceiver) }
        playerView.release()
        backgroundExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val remappedVolumeDirection = LocalVolumeKeyPolicy.direction(
            event.keyCode,
            event.scanCode,
            HardwareKeyGate.isLocalKeypad(event.deviceId)
        )
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            remappedVolumeDirection != null
        ) {
            // A hold can cross the display boundary. Foreground framework
            // repeats take over immediately so the screen-off fallback cannot
            // add a second stream of adjustments.
            playbackBinder?.cancelVolumeKeyRepeat()
            if (remappedVolumeDirection != null &&
                !HardwareKeyGate.accept(event, HardwareKeyGate.Source.ACTIVITY)
            ) return true
            if (event.action == KeyEvent.ACTION_DOWN) {
                val direction = remappedVolumeDirection
                    ?: if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
                handleEffect(AppEffect.AdjustVolume(direction))
            }
            return true
        }
        if (!inputAllowed(event.keyCode)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount >= 3 && SystemClock.uptimeMillis() <= startupSafeModeDeadline
        ) {
            handleEffect(AppEffect.EnterSafeMode)
            return true
        }
        if (!HardwareKeyGate.accept(event, HardwareKeyGate.Source.ACTIVITY)) return true
        if (BuildConfig.DEBUG) InputProbe.log("ACTIVITY", event)
        return if (inputController.handle(event)) true else super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (inputAllowed(KeyEvent.KEYCODE_BACK)) store.dispatch(AppAction.Back)
    }

    private fun handleEffect(effect: AppEffect) {
        when (effect) {
            is AppEffect.PlayCollection ->
                requirePlayback { it.playCollection(effect.trackIds, effect.startIndex, effect.shuffled, effect.fromStart) }
            is AppEffect.PlayQueueIndex -> requirePlayback { it.playQueueIndex(effect.index) }
            is AppEffect.RemoveQueueIndex -> requirePlayback { it.removeQueueIndex(effect.index) }
            is AppEffect.MoveQueueItem -> requirePlayback { it.moveQueueItem(effect.index, effect.delta) }
            is AppEffect.PlayNext -> { requirePlayback { it.playNext(effect.trackId) }; showMessage("Added to play next") }
            is AppEffect.AddToQueue -> { requirePlayback { it.addToQueue(effect.trackId) }; showMessage("Added to queue") }
            AppEffect.ClearUpcoming -> requirePlayback { it.clearUpcoming() }
            AppEffect.ClearQueue -> requirePlayback { it.clearQueue() }
            AppEffect.TogglePlayback -> requirePlayback { it.togglePlayback() }
            AppEffect.NextTrack -> requirePlayback { it.next() }
            AppEffect.PreviousTrack -> requirePlayback { it.previous() }
            AppEffect.ToggleShuffle -> requirePlayback { it.toggleShuffle() }
            AppEffect.CycleRepeat -> requirePlayback { it.cycleRepeat() }
            is AppEffect.SeekBy -> requirePlayback { it.seekBy(effect.deltaMs) }
            is AppEffect.AdjustVolume -> adjustVolume(effect.direction)
            AppEffect.RequestLibraryScan -> when {
                safeModeManager.isSafeMode() -> showMessage("Scanning is disabled in Safe Mode")
                else -> { libraryRepository.scan(); showMessage("Library scan started") }
            }
            AppEffect.ShuffleAll -> {
                val tracks = store.state.library.availableTracks.filterNot { it.decodeFailed }
                if (tracks.isEmpty()) showMessage("No music found") else requirePlayback { binder ->
                    binder.playCollectionShuffled(tracks.map { track -> track.id })
                }
            }
            is AppEffect.ToggleFavorite -> { libraryRepository.toggleFavorite(effect.trackId); showMessage("Favorite updated") }
            AppEffect.CreatePlaylist -> { libraryRepository.createPlaylist(); showMessage("Playlist created") }
            is AppEffect.CreatePlaylistWithTrack -> { libraryRepository.createPlaylistWithTrack(effect.trackId); showMessage("Playlist created") }
            is AppEffect.AddTrackToPlaylist -> { libraryRepository.addTrackToPlaylist(effect.playlistId, effect.trackId); showMessage("Added to playlist") }
            is AppEffect.RemoveTrackFromPlaylist -> libraryRepository.removeTrackFromPlaylist(effect.playlistId, effect.trackId)
            is AppEffect.DeletePlaylist -> { libraryRepository.deletePlaylist(effect.playlistId); showMessage("Playlist deleted") }
            AppEffect.ImportM3uPlaylists -> libraryRepository.importM3uPlaylists { result ->
                showMessage("Imported ${result.imported} playlists · ${result.matchedTracks} tracks")
            }
            AppEffect.ExportM3uPlaylists -> libraryRepository.exportM3uPlaylists { result ->
                showMessage(if (result.directory == null) "No writable music storage" else "Exported ${result.exported} playlists")
            }

            is AppEffect.SetBluetoothEnabled -> showOperation(bluetoothController.setEnabled(effect.enabled))
            AppEffect.StartBluetoothScan -> showOperation(bluetoothController.startScan())
            AppEffect.StopBluetoothScan -> showOperation(bluetoothController.stopScan())
            AppEffect.RefreshBluetoothService -> showOperation(bluetoothController.refreshA2dpProxy())
            is AppEffect.ActivateBluetoothDevice -> showOperation(bluetoothController.activateDevice(effect.address))
            is AppEffect.ForgetBluetoothDevice -> showOperation(bluetoothController.forgetDevice(effect.address))

            is AppEffect.SetBrightness -> {
                val result = displayController.setBrightness(effect.percent)
                store.dispatch(AppAction.DisplayChanged(displayController.snapshot()))
                showMessage(result.message)
            }
            is AppEffect.SetScreenTimeout -> {
                val result = displayController.setTimeout(effect.timeoutMs)
                store.dispatch(AppAction.DisplayChanged(displayController.snapshot()))
                showMessage(result.message)
            }
            AppEffect.ToggleUiSoundEffects -> {
                val value = preferences.toggleUiSoundEffects()
                applyPlaybackPreferences(value)
                showMessage(uiSoundEffectsController.apply(value.uiSoundEffectsEnabled).message)
            }
            AppEffect.ToggleVerboseDiagnostics -> {
                val value = preferences.toggleVerboseDiagnostics()
                applyPlaybackPreferences(value)
                eventLog.setEnabled(value.verboseDiagnostics)
                logger.setVerbose(value.verboseDiagnostics)
                eventLog.warn(Sub.DIAG, Ev.ACTION, "verbose" to value.verboseDiagnostics)
                showMessage(if (value.verboseDiagnostics) "Verbose diagnostics on" else "Verbose diagnostics off")
            }
            AppEffect.ToggleKeepScreenOn -> applyPlaybackPreferences(preferences.toggleKeepScreenOn())
            AppEffect.ToggleExtraTrackInfo -> applyPlaybackPreferences(preferences.toggleExtraTrackInfo())
            AppEffect.ToggleLightTheme -> applyPlaybackPreferences(preferences.toggleLightTheme())
            is AppEffect.SetBalance -> applyPlaybackPreferences(preferences.setBalance(effect.balance))
            AppEffect.ToggleLocalKeysWhileScreenOff ->
                applyPlaybackPreferences(preferences.toggleLocalKeysWhileScreenOff())
            AppEffect.TogglePauseOnDisconnect -> applyPlaybackPreferences(preferences.togglePauseOnDisconnect())
            AppEffect.ToggleResumePosition -> applyPlaybackPreferences(preferences.toggleResumePosition())
            AppEffect.ToggleGapless -> applyPlaybackPreferences(preferences.toggleGapless())
            AppEffect.CycleCrossfade -> applyPlaybackPreferences(preferences.cycleCrossfade())
            AppEffect.CycleCrossfadeMode -> applyPlaybackPreferences(preferences.cycleCrossfadeMode())
            AppEffect.CyclePauseFade -> applyPlaybackPreferences(preferences.cyclePauseFade())
            AppEffect.CycleSeekStep -> applyPlaybackPreferences(preferences.cycleSeekStep())
            AppEffect.CycleLongSeekStep -> applyPlaybackPreferences(preferences.cycleLongSeekStep())
            AppEffect.CyclePreviousThreshold -> applyPlaybackPreferences(preferences.cyclePreviousThreshold())
            AppEffect.ToggleDuckOnFocusLoss -> applyPlaybackPreferences(preferences.toggleDuckOnFocusLoss())
            AppEffect.CycleHapticLevel -> {
                val value = preferences.cycleHapticLevel()
                applyPlaybackPreferences(value)
                hapticController.setLevel(value.hapticLevel)
                showMessage(
                    if (value.hapticLevel == HapticLevel.OFF) "Haptics off"
                    else "Haptics ${value.hapticLevel.label.lowercase()} · ${value.hapticLevel.durationMs} ms pulse (API 19 has no amplitude control)"
                )
            }
            AppEffect.ToggleWrapLists -> applyPlaybackPreferences(preferences.toggleWrapLists())
            AppEffect.CycleVolumeMode -> cycleVolumeMode()
            AppEffect.CycleReplayGain -> applyPlaybackPreferences(preferences.cycleReplayGain())
            AppEffect.CycleSleepTimer -> requirePlayback { it.cycleSleepTimer() }
            AppEffect.CycleAudioQuality -> {
                val value = preferences.cycleAudioQuality()
                applyPlaybackPreferences(value)
                showMessage(
                    if (value.audioQualityMode == AudioQualityMode.DIRECT_DAC) {
                        "Direct DAC unavailable; using standard AudioTrack output"
                    } else {
                        "Balanced AudioTrack output enabled"
                    }
                )
            }
            AppEffect.ToggleAudioEffects -> applyPlaybackPreferences(preferences.toggleAudioEffects())
            AppEffect.CycleEqualizerPreset -> {
                val presetCount = store.state.playback.audioEffects.presetNames.size
                applyPlaybackPreferences(preferences.cycleEqualizerPreset(presetCount))
            }
            is AppEffect.AdjustEqualizerBand -> {
                val effects = store.state.playback.audioEffects
                applyPlaybackPreferences(
                    preferences.adjustEqualizerBand(
                        effect.index,
                        effect.deltaSteps,
                        effects.bandMinMb,
                        effects.bandMaxMb,
                        effects.bandFrequenciesHz.size
                    )
                )
            }
            AppEffect.CycleBassStrength -> applyPlaybackPreferences(preferences.cycleBassStrength())
            AppEffect.CycleLoudnessGain -> applyPlaybackPreferences(preferences.cycleLoudnessGain())
            is AppEffect.SetSortOrder -> applyPlaybackPreferences(preferences.setSortOrder(effect.order))

            AppEffect.RefreshPlaybackHistory -> refreshPlaybackHistory()
            AppEffect.RefreshAudiobooks -> libraryRepository.refreshAudiobookProgress()
            is AppEffect.ClearAudiobookProgress -> {
                val binder = playbackBinder
                if (binder == null) showMessage("Playback is starting") else binder.clearAudiobookProgress(effect.folderKey) { success ->
                    if (success) {
                        libraryRepository.refreshAudiobookProgress()
                        showMessage("Progress cleared")
                    } else showMessage("Could not clear progress")
                }
            }
            AppEffect.ClearPlaybackHistory -> {
                val binder = playbackBinder
                if (binder == null) showMessage("Playback is starting") else binder.clearHistory { cleared ->
                    showMessage(if (cleared) "Playback history cleared" else "No history to clear")
                    diagnosticsRepository.setPlaybackHistory(0, 0)
                }
            }
            AppEffect.ExportDiagnostics -> backgroundExecutor.execute {
                diagnosticsRepository.export().onSuccess { file -> showMessage("Saved ${file.name}") }
                    .onFailure { showMessage(it.message ?: "Export failed") }
            }
            AppEffect.ClearDiagnostics -> backgroundExecutor.execute {
                diagnosticsRepository.clear()
                    .onSuccess { showMessage("Diagnostic log cleared") }
                    .onFailure { showMessage(it.message ?: "Could not clear diagnostic log") }
            }
            AppEffect.ExportBackup -> {
                val binder = playbackBinder
                if (binder == null) showMessage("Playback is starting") else binder.prepareBackupExport {
                    backgroundExecutor.execute {
                        backupManager.export().onSuccess { result ->
                            mainHandler.post {
                                store.dispatch(AppAction.BackupChanged(
                                    store.state.backup.copy(exportedPath = result.file.absolutePath)
                                ))
                            }
                            showMessage("Backup saved · ${result.references} references")
                        }.onFailure { showMessage(it.message ?: "Backup export failed") }
                    }
                }
            }
            AppEffect.InspectBackup -> backgroundExecutor.execute {
                backupManager.preview().onSuccess { preview ->
                    pendingBackupPreview = preview
                    mainHandler.post { store.dispatch(AppAction.BackupImportReady(preview.summary)) }
                }.onFailure { showMessage(it.message ?: "Backup is invalid") }
            }
            AppEffect.ImportBackup -> {
                val binder = playbackBinder
                val preview = pendingBackupPreview
                when {
                    binder == null -> showMessage("Playback is starting")
                    preview == null -> showMessage("Select Import Backup again to validate the file")
                    else -> binder.prepareBackupImport {
                        backgroundExecutor.execute {
                            backupManager.import(preview).onSuccess { result ->
                                pendingBackupPreview = null
                                libraryRepository.reloadUserData {
                                    val restored = preferences.snapshot()
                                    applyPlaybackPreferences(restored)
                                    eventLog.setEnabled(restored.verboseDiagnostics)
                                    logger.setVerbose(restored.verboseDiagnostics)
                                    hapticController.setLevel(restored.hapticLevel)
                                    playerView.isSoundEffectsEnabled = restored.uiSoundEffectsEnabled
                                    binder.finishBackupImport(restored) {
                                        val unresolved = if (result.unresolvedReferences == 0) "all tracks resolved"
                                        else "${result.unresolvedReferences} tracks missing"
                                        showMessage("Backup restored · ${result.restoredReferences} references · $unresolved")
                                    }
                                }
                            }.onFailure { error ->
                                pendingBackupPreview = null
                                binder.finishBackupImport(preferences.snapshot()) {
                                    showMessage(error.message ?: "Backup import failed; previous data kept")
                                }
                            }
                        }
                    }
                }
            }
            AppEffect.ResetLibrary -> {
                val binder = playbackBinder
                if (binder == null) showMessage("Playback is starting") else binder.prepareLibraryReset {
                    libraryRepository.resetLibrary(rescan = !safeModeManager.isSafeMode()) {
                        diagnosticsRepository.setPlaybackHistory(0, 0)
                        showMessage(if (safeModeManager.isSafeMode()) "Library reset" else "Library reset · scanning")
                    }
                }
            }
            AppEffect.EnterSafeMode -> {
                safeModeManager.forceSafeMode()
                libraryRepository.cancelScan("safe mode")
                bluetoothUiActive = false
                bluetoothController.stop()
                requirePlayback { it.setSafeMode(true) }
                store.dispatch(AppAction.SafeModeChanged(true))
                showMessage("Safe Mode enabled")
            }
            AppEffect.ExitSafeMode -> {
                safeModeManager.exitSafeMode()
                store.dispatch(AppAction.SafeModeChanged(false))
                requirePlayback { it.setSafeMode(false) }
                libraryRepository.scan()
                showMessage("Safe Mode disabled")
            }

            AppEffect.OpenAndroidSettings -> {
                val settings = Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(settings) }
                    .onFailure { showMessage("Android Settings is not available on this firmware") }
            }
        }
    }

    private fun dispatchInput(action: AppAction) {
        val wheel = action is AppAction.WheelMoved
        val adjustsNowPlayingVolume = wheel && store.state.currentScreen == Screen.NowPlaying
        if (wheel) playerView.onNavigationInput()
        val accepted = store.dispatch(action)
        if (accepted && !adjustsNowPlayingVolume) pulseAcceptedAction()
    }

    private fun adjustVolume(direction: Int) {
        if (store.state.preferences.volumeMode != VolumeMode.PERCEPTUAL) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val before = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != before) pulseAcceptedAction()
            return
        }
        val before = store.state.preferences.volumeLevel
        val value = preferences.adjustVolumeLevel(direction)
        if (value.volumeLevel == before) return
        applyPlaybackPreferences(value)
        pulseAcceptedAction()
        showMessage("Volume ${VolumeCurve.percentForLevel(value.volumeLevel)}%")
        eventLog.logRateLimited(
            "volume_level",
            VOLUME_LOG_WINDOW_MS,
            Sev.DEBUG,
            Sub.PLAYBACK,
            Ev.VOLUME_LEVEL,
            "level" to value.volumeLevel,
            "pct" to VolumeCurve.percentForLevel(value.volumeLevel)
        )
    }

    private fun pulseAcceptedAction() {
        if (HapticPolicy.shouldPulse(
                store.state.preferences.hapticLevel,
                hapticController.available,
                accepted = true
            )
        ) hapticController.acceptedAction()
    }

    private fun refreshPlaybackHistory() {
        val binder = playbackBinder ?: return
        backgroundExecutor.execute {
            val summary = binder.historySummary()
            diagnosticsRepository.setPlaybackHistory(summary.sessions, summary.bytes)
        }
    }

    private fun cycleVolumeMode() {
        val binder = playbackBinder
        if (binder == null) {
            showMessage("Playback is starting")
            return
        }
        val current = preferences.snapshot()
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val systemMaximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(0)
        val nextMode = current.volumeMode.next()
        val transferredAppLevel: Int
        val targetSystemIndex: Int
        if (nextMode == VolumeMode.PERCEPTUAL) {
            transferredAppLevel = VolumeModeTransfer.appLevelFromSystemIndex(
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                systemMaximum
            )
            targetSystemIndex = systemMaximum
        } else {
            transferredAppLevel = VolumeCurve.STEPS
            targetSystemIndex = VolumeModeTransfer.systemIndexFromAppLevel(
                current.volumeLevel,
                systemMaximum
            )
        }

        val value = preferences.setVolumeMode(nextMode, transferredAppLevel)
        playerView.isSoundEffectsEnabled = value.uiSoundEffectsEnabled
        store.dispatch(AppAction.PreferencesChanged(value))
        binder.applyVolumeModeTransition(value, targetSystemIndex)
        eventLog.info(
            Sub.PLAYBACK,
            Ev.VOLUME_MODE,
            "mode" to value.volumeMode.storageId,
            "app_level" to value.volumeLevel,
            "system_target" to targetSystemIndex,
            "system_max" to systemMaximum
        )
        showMessage(
            if (value.volumeMode == VolumeMode.PERCEPTUAL) {
                "In-app volume · transferred to ${VolumeCurve.percentForLevel(value.volumeLevel)}%"
            } else {
                "System volume · transferred to Android music stream"
            }
        )
    }

    private fun inputAllowed(keyCode: Int): Boolean = HardwareKeyGate.isInputAllowed(
        context = this,
        keyCode = keyCode,
        localKeysWhileScreenOff = this::preferences.isInitialized &&
            preferences.snapshot().localKeysWhileScreenOff
    )

    private fun applyThemeResource() {
        val lightTheme = runCatching {
            (application as Y2Application).container.preferences.snapshot().lightTheme
        }.getOrDefault(false)
        if (lightTheme) setTheme(R.style.AppTheme_Light)
    }

    private fun applyPlaybackPreferences(value: com.schulzcode.y2player.core.state.PlayerPreferencesState) {
        playerView.isSoundEffectsEnabled = value.uiSoundEffectsEnabled
        store.dispatch(AppAction.PreferencesChanged(value))
        playbackBinder?.applyPreferences(value)
    }

    private fun requirePlayback(block: (PlaybackService.LocalBinder) -> Unit) {
        val binder = playbackBinder
        if (binder == null) showMessage("Playback is starting") else block(binder)
    }

    private fun showOperation(result: BluetoothController.OperationResult) {
        showMessage(result.message)
        store.dispatch(AppAction.BluetoothChanged(bluetoothController.snapshot()))
    }

    private fun bindPlaybackServiceIfNeeded() {
        if (playbackBound) return
        val serviceIntent = Intent(this, PlaybackService::class.java)
        startService(serviceIntent)
        playbackBound = true
        val accepted = runCatching {
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!accepted) playbackBound = false
    }

    private fun unbindPlaybackServiceIfNeeded() {
        if (!playbackBound) return
        playbackBinder?.removeListener(playbackListener)
        playbackBinder = null
        runCatching { unbindService(serviceConnection) }
        playbackBound = false
    }

    private fun registerVisibleStateListeners() {
        if (stateListenerRegistered) return
        store.addStateListener(stateListener)
        stateListenerRegistered = true
    }

    private fun unregisterVisibleStateListeners() {
        if (!stateListenerRegistered) return
        store.removeStateListener(stateListener)
        stateListenerRegistered = false
    }

    private fun evaluateBluetoothUi() {
        val wantActive = uiStarted &&
            !safeModeManager.isSafeMode() &&
            store.state.currentScreen == Screen.Bluetooth
        if (wantActive == bluetoothUiActive) return
        bluetoothUiActive = wantActive
        bluetoothController.setUiActive(wantActive)
    }

    private fun showMessage(message: String) {
        if (!destroyed) mainHandler.post { if (!destroyed) store.dispatch(AppAction.ShowMessage(message)) }
    }

    companion object {
        private const val SAFE_MODE_KEY_WINDOW_MS = 5_000L
        private const val VOLUME_LOG_WINDOW_MS = 5_000L
        private const val WHEEL_LOG_WINDOW_MS = 1_000L
        private const val STARTUP_STABLE_DELAY_MS = 10_000L
    }
}
