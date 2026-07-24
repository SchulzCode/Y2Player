package com.schulzcode.y2player.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import android.widget.Toast
import com.schulzcode.y2player.Y2Application
import com.schulzcode.y2player.bluetooth.BluetoothController
import com.schulzcode.y2player.core.model.AudioQualityMode
import com.schulzcode.y2player.core.model.PlaybackStatus
import com.schulzcode.y2player.core.state.AppAction
import com.schulzcode.y2player.core.state.AppEffect
import com.schulzcode.y2player.core.state.AppStore
import com.schulzcode.y2player.core.state.Screen
import com.schulzcode.y2player.core.state.ScreenContent
import com.schulzcode.y2player.diagnostics.DiagnosticsRepository
import com.schulzcode.y2player.diagnostics.Ev
import com.schulzcode.y2player.diagnostics.EventLog
import com.schulzcode.y2player.diagnostics.FormatProbeController
import com.schulzcode.y2player.diagnostics.Sev
import com.schulzcode.y2player.diagnostics.Sub
import com.schulzcode.y2player.input.HapticController
import com.schulzcode.y2player.input.HapticLevel
import com.schulzcode.y2player.input.HapticPolicy
import com.schulzcode.y2player.input.HardwareKeyGate
import com.schulzcode.y2player.input.Y2InputController
import com.schulzcode.y2player.library.LibraryRepository
import com.schulzcode.y2player.playback.PlaybackService
import com.schulzcode.y2player.playback.VolumeCurve
import com.schulzcode.y2player.playback.VolumeMode
import com.schulzcode.y2player.playback.VolumeModeTransfer
import com.schulzcode.y2player.safe.SafeModeManager
import com.schulzcode.y2player.settings.AppPreferences
import com.schulzcode.y2player.settings.DisplayController
import com.schulzcode.y2player.settings.UiSoundEffectsController
import com.schulzcode.y2player.storage.StorageMonitor
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
    private lateinit var uiSoundEffectsController: UiSoundEffectsController
    private lateinit var bluetoothController: BluetoothController
    private var bluetoothUiActive = false
    private var uiStarted = false
    private lateinit var safeModeManager: SafeModeManager
    private lateinit var formatProbeController: FormatProbeController
    private lateinit var playerView: Y2PlayerView
    private lateinit var inputController: Y2InputController
    private lateinit var hapticController: HapticController
    private lateinit var storageMonitor: StorageMonitor
    private lateinit var eventLog: EventLog
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
    @Volatile private var destroyed = false

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

    /**
     * Records the action name and resulting screen only — never the full state,
     * which would be large, noisy and full of track metadata. This is enough to
     * replay a navigation sequence from a field log.
     *
     * Wheel detents are rate-limited: a fast spin emits dozens of actions per
     * second, which would flood the bounded event queue and evict more useful
     * events. One wheel event per window (with the final index) still shows
     * where the spin landed.
     */
    private val actionLogger = AppStore.ActionListener { action, state ->
        val wheel = action == AppAction.WheelClockwise || action == AppAction.WheelCounterClockwise
        if (wheel) {
            eventLog.logRateLimited(
                "wheel_action", WHEEL_LOG_WINDOW_MS,
                Sev.DEBUG, Sub.REDUCER, Ev.ACTION,
                "action" to action::class.java.simpleName,
                "screen" to state.currentScreen::class.java.simpleName,
                "index" to state.selectedIndex
            )
        } else {
            eventLog.debug(
                Sub.REDUCER, Ev.ACTION,
                "action" to action::class.java.simpleName,
                "screen" to state.currentScreen::class.java.simpleName,
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
        // The storage monitor knows nothing about the motor, and DeviceChanged
        // replaces the whole DeviceState, so the flag is re-attached here rather
        // than being silently reset to false on every mount event.
        store.dispatch(AppAction.DeviceChanged(device.copy(hapticsAvailable = hapticController.available)))
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // A callback can race with onStop/unbind on old vendor framework
            // builds. Never attach a listener to a no-longer-visible Activity.
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
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE
        // Hardware volume keys must always target the music stream, not the ringer.
        volumeControlStream = AudioManager.STREAM_MUSIC

        val container = (application as Y2Application).container
        // Assigned first: everything below may log, and an event dropped during
        // onCreate is one lost from exactly the window a startup failure lives in.
        eventLog = container.eventLog
        store = container.appStore
        libraryRepository = container.libraryRepository
        diagnosticsRepository = container.diagnosticsRepository
        preferences = container.preferences
        safeModeManager = container.safeModeManager
        val startupSafeMode = safeModeManager.beginUiStartup()
        displayController = DisplayController(this)
        uiSoundEffectsController = UiSoundEffectsController(this)
        bluetoothController = container.bluetoothController
        if (startupSafeMode) bluetoothController.stop()
        formatProbeController = FormatProbeController(
            container.logger,
            onFinished = { results ->
                backgroundExecutor.execute { runCatching { container.database.saveFormatProbe(results) } }
                diagnosticsRepository.setProbeResults(results)
                showMessage("Format test finished")
            },
            onCancelled = {
                diagnosticsRepository.setProbeRunning(false)
                showMessage("Format test cancelled")
            },
            onError = { message -> diagnosticsRepository.setError(message); showMessage(message) }
        )
        hapticController = HapticController(this, container.eventLog)
        hapticController.setLevel(preferences.snapshot().hapticLevel)
        inputController = Y2InputController(::dispatchInput)
        playerView = Y2PlayerView(this, store::dispatch, container.artworkLoader)
        playerView.isSoundEffectsEnabled = preferences.snapshot().uiSoundEffectsEnabled
        setContentView(playerView)
        startupSafeModeDeadline = SystemClock.uptimeMillis() + SAFE_MODE_KEY_WINDOW_MS

        storageMonitor = container.storageMonitor

        // The state snapshot attached to every event. Kept small and cheap: it is
        // built once per logged event, on the caller thread, from volatile reads.
        //
        // `snapshotStore` is a local rather than the `store` field on purpose.
        // EventLog lives in AppContainer for the life of the process and holds
        // this provider forever, so referencing the field would capture
        // MainActivity — leaking the activity, its view tree and any decoded
        // artwork across every recreation. The AppStore is container-scoped and
        // is the same instance a new activity would use, so closing over it
        // directly is both leak-free and correct after recreation.
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

        // The UI-facing state listener is registered per visible lifecycle in
        // onStart/onStop so a stopped Activity never renders. Effect and action
        // listeners stay process-long: effects can be produced by background
        // dispatches (route loss, storage) that must not be dropped while stopped.
        store.addEffectListener(effectListener)
        store.addActionListener(actionLogger)
        libraryRepository.addListener(libraryListener, emitImmediately = false)
        diagnosticsRepository.addListener(diagnosticsListener, emitImmediately = false)
        bluetoothController.addListener(bluetoothListener)
        storageMonitor.addListener(storageListener)
        store.dispatch(AppAction.SafeModeChanged(safeModeManager.isSafeMode()))
        store.dispatch(AppAction.PreferencesChanged(preferences.snapshot()))
        store.dispatch(AppAction.DisplayChanged(displayController.snapshot()))
        val database = container.database
        val diagnostics = diagnosticsRepository
        backgroundExecutor.execute { runCatching { database.loadFormatProbe() }.onSuccess(diagnostics::setProbeResults) }

        if (!startupSafeMode) libraryRepository.loadCached()

        // The playback service is started and bound in onStart, so a stopped and
        // unbound Activity stops being a bound client and an idle service can exit.
        mainHandler.postDelayed(markStableRunnable, STARTUP_STABLE_DELAY_MS)
    }

    /**
     * As the HOME activity (singleTask) this receives the HOME intent instead of
     * being recreated. A launcher always returns to its top level when HOME is
     * pressed; without this the user would be left wherever they had navigated.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (HardwareKeyGate.isInputAllowed(this, KeyEvent.KEYCODE_HOME)) {
            store.dispatch(AppAction.NavigateHome)
        }
    }

    override fun onStart() {
        super.onStart()
        eventLog.debug(Sub.ACTIVITY, Ev.ACTIVITY_START)
        uiStarted = true
        // Visible again: render current state, then (re)start and bind the service.
        registerVisibleStateListeners()
        bindPlaybackServiceIfNeeded()
        // Re-arm Bluetooth management if the screen came back while on the
        // Bluetooth screen; rebuilds the bonded/connected snapshot from scratch.
        evaluateBluetoothUi()
        backgroundExecutor.execute {
            diagnosticsRepository.refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        eventLog.debug(Sub.ACTIVITY, Ev.ACTIVITY_RESUME)
        val currentPreferences = preferences.snapshot()
        storageMonitor.publish()
        store.dispatch(AppAction.DisplayChanged(displayController.snapshot()))
        store.dispatch(AppAction.PreferencesChanged(currentPreferences))
        if (!safeModeManager.isSafeMode()) store.dispatch(AppAction.BluetoothChanged(bluetoothController.snapshot()))
        playerView.isSoundEffectsEnabled = currentPreferences.uiSoundEffectsEnabled
        val uiSounds = currentPreferences.uiSoundEffectsEnabled
        val controller = uiSoundEffectsController
        backgroundExecutor.execute { controller.apply(uiSounds) }
        hapticController.setLevel(currentPreferences.hapticLevel)
        hapticController.resume()
    }

    override fun onStop() {
        eventLog.debug(Sub.ACTIVITY, Ev.ACTIVITY_STOP)
        uiStarted = false
        // The Activity is no longer visible: release Bluetooth management (deferred
        // if an operation is mid-flight). Never disconnects an active headset.
        evaluateBluetoothUi()
        // Stop being a bound client and stop rendering. Music keeps playing: the
        // started foreground service outlives the unbind. An idle (paused) service
        // sees zero bound clients and can now stop itself.
        unbindPlaybackServiceIfNeeded()
        unregisterVisibleStateListeners()
        inputController.resetHeldKeys()
        // No motor activity while the UI is gone, and none left running into a
        // shutdown or reboot.
        hapticController.suspend()
        super.onStop()
    }


    override fun onLowMemory() {
        super.onLowMemory()
        playerView.trimMemory()
        ScreenContent.clearCachedRows()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Unlike the playback service (see its onTrimMemory), trimming here on
        // TRIM_MEMORY_UI_HIDDEN is intentional: row and artwork caches are cheap to
        // rebuild and worthless while no UI is visible.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            playerView.trimMemory()
            ScreenContent.clearCachedRows()
        }
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacks(clearMessageRunnable)
        mainHandler.removeCallbacks(markStableRunnable)
        formatProbeController.shutdown()
        // Guarded: onStop normally already unbound and unregistered these, so both
        // are no-ops here. Safe if the Activity is destroyed without a prior onStop.
        unbindPlaybackServiceIfNeeded()
        libraryRepository.removeListener(libraryListener)
        diagnosticsRepository.removeListener(diagnosticsListener)
        bluetoothController.removeListener(bluetoothListener)
        storageMonitor.removeListener(storageListener)
        eventLog.info(Sub.ACTIVITY, Ev.ACTIVITY_DESTROY, "finishing" to isFinishing)
        unregisterVisibleStateListeners()
        store.removeEffectListener(effectListener)
        store.removeActionListener(actionLogger)
        playerView.release()
        hapticController.release()
        backgroundExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // In perceptual mode the hardware keys must drive the same level as
            // the wheel, otherwise the device would have two volume controls
            // that disagree. In system mode the keys fall through to Android's
            // default handling.
            if (store.state.preferences.volumeMode != VolumeMode.PERCEPTUAL) return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN) {
                handleEffect(AppEffect.AdjustVolume(if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1))
            }
            return true
        }
        if (!HardwareKeyGate.isInputAllowed(this, event.keyCode)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount >= 3 && SystemClock.uptimeMillis() <= startupSafeModeDeadline
        ) {
            handleEffect(AppEffect.EnterSafeMode)
            return true
        }
        if (!HardwareKeyGate.accept(event, HardwareKeyGate.Source.ACTIVITY)) return true
        return if (inputController.handle(event)) true else super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (HardwareKeyGate.isInputAllowed(this, KeyEvent.KEYCODE_BACK)) store.dispatch(AppAction.Back)
    }

    private fun handleEffect(effect: AppEffect) {
        when (effect) {
            is AppEffect.PlayCollection -> requirePlayback { it.playCollection(effect.trackIds, effect.startIndex) }
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
                val tracks = store.state.library.availableTracks
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
                // Always recorded: this is a WARN-level marker so the transition
                // is visible in the log even when verbose mode is being turned off.
                eventLog.warn(Sub.DIAG, Ev.ACTION, "verbose" to value.verboseDiagnostics)
                showMessage(if (value.verboseDiagnostics) "Verbose diagnostics on" else "Verbose diagnostics off")
            }
            AppEffect.ToggleKeepScreenOn -> applyPlaybackPreferences(preferences.toggleKeepScreenOn())
            AppEffect.TogglePauseOnDisconnect -> applyPlaybackPreferences(preferences.togglePauseOnDisconnect())
            AppEffect.ToggleResumePosition -> applyPlaybackPreferences(preferences.toggleResumePosition())
            AppEffect.ToggleGapless -> applyPlaybackPreferences(preferences.toggleGapless())
            AppEffect.CycleCrossfade -> applyPlaybackPreferences(preferences.cycleCrossfade())
            AppEffect.CyclePauseFade -> applyPlaybackPreferences(preferences.cyclePauseFade())
            AppEffect.CycleSeekStep -> applyPlaybackPreferences(preferences.cycleSeekStep())
            AppEffect.CycleLongSeekStep -> applyPlaybackPreferences(preferences.cycleLongSeekStep())
            AppEffect.CyclePreviousThreshold -> applyPlaybackPreferences(preferences.cyclePreviousThreshold())
            AppEffect.ToggleDuckOnFocusLoss -> applyPlaybackPreferences(preferences.toggleDuckOnFocusLoss())
            AppEffect.CycleHapticLevel -> {
                val value = preferences.cycleHapticLevel()
                applyPlaybackPreferences(value)
                hapticController.setLevel(value.hapticLevel)
                // One confirming pulse so the user feels the level they picked.
                hapticController.wheelDetent()
                showMessage(
                    if (value.hapticLevel == HapticLevel.OFF) "Wheel haptics off"
                    else "Wheel haptics ${value.hapticLevel.label.lowercase()} · ${value.hapticLevel.durationMs} ms pulse (API 19 has no amplitude control)"
                )
            }
            AppEffect.CycleVolumeMode -> cycleVolumeMode()
            AppEffect.CycleSleepTimer -> requirePlayback { it.cycleSleepTimer() }
            AppEffect.CycleAudioQuality -> {
                val value = preferences.cycleAudioQuality()
                applyPlaybackPreferences(value)
                showMessage(if (value.audioQualityMode == AudioQualityMode.DIRECT_DAC) "Direct DAC mode requested" else "Balanced audio mode enabled")
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

            AppEffect.ExportDiagnostics -> backgroundExecutor.execute {
                diagnosticsRepository.export().onSuccess { file -> showMessage("Saved ${file.name}") }
                    .onFailure { showMessage(it.message ?: "Export failed") }
            }
            AppEffect.RunFormatProbe -> {
                val state = store.state
                if (state.playback.status in setOf(PlaybackStatus.PLAYING, PlaybackStatus.PREPARING)) {
                    showMessage("Pause playback before running the format test")
                } else {
                    diagnosticsRepository.setProbeRunning(true)
                    if (!formatProbeController.start(state.library.availableTracks)) diagnosticsRepository.setProbeRunning(false)
                }
            }
            AppEffect.ResetLibrary -> {
                requirePlayback { it.clearQueue() }
                libraryRepository.resetLibrary()
                showMessage("Library reset")
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
                // Bluetooth stays UI-scoped: it re-arms only if the user is on the
                // Bluetooth screen, which the state change above re-evaluates.
                requirePlayback { it.setSafeMode(false) }
                libraryRepository.scan()
                showMessage("Safe Mode disabled")
            }

            AppEffect.OpenAndroidSettings -> {
                // NEW_TASK keeps Settings out of the launcher's task, so Back from
                // Settings returns to the system, not into a mixed Y2Player stack.
                val settings = Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(settings) }
                    .onFailure { showMessage("Android Settings is not available on this firmware") }
            }

        }
    }

    /**
     * One volume step. Exactly one layer responds: either Android's music stream
     * (with the system volume panel as feedback) or the in-app gain. Stacking
     * both would square the attenuation and make the bottom of the range unusable.
     *
     * The SharedPreferences write is a small `apply()` — asynchronous commit, no
     * blocking I/O on the main thread — and only happens when the level actually
     * moves, so holding a key at either end writes nothing.
     */
    /**
     * Input entry point. Everything reaching here has already survived
     * [HardwareKeyGate], so duplicates and bounces are gone.
     *
     * A wheel detent earns a pulse only if the reduction actually did something:
     * the reducer returns the *same* state instance when nothing moved, so
     * reference inequality is a free, allocation-free "did it change" signal. The
     * vibrate call itself happens on the haptics thread, so this adds a couple of
     * field reads to the scroll path and nothing more.
     */
    private fun dispatchInput(action: AppAction) {
        if (action != AppAction.WheelClockwise && action != AppAction.WheelCounterClockwise) {
            store.dispatch(action)
            return
        }
        val before = store.state
        store.dispatch(action)
        val pulse = HapticPolicy.shouldPulse(
            before.preferences.hapticLevel,
            hapticController.available,
            before.currentScreen == Screen.NowPlaying,
            store.state !== before
        )
        if (pulse) hapticController.wheelDetent()
    }

    private fun adjustVolume(direction: Int) {
        if (store.state.preferences.volumeMode != VolumeMode.PERCEPTUAL) {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0
            )
            return
        }
        val before = store.state.preferences.volumeLevel
        val value = preferences.adjustVolumeLevel(direction)
        if (value.volumeLevel == before) return
        applyPlaybackPreferences(value)
        // Replaces the transient message rather than queueing, so a fast wheel
        // spin costs one state field write per step and no Toast backlog.
        showMessage("Volume ${VolumeCurve.percentForLevel(value.volumeLevel)}%")
        // Rate limited: a wheel spin can emit dozens of steps per second and the
        // interesting fact is where the level ended up, not every intermediate.
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

    private fun applyPlaybackPreferences(value: com.schulzcode.y2player.core.state.PlayerPreferencesState) {
        playerView.isSoundEffectsEnabled = value.uiSoundEffectsEnabled
        store.dispatch(AppAction.PreferencesChanged(value))
        playbackBinder?.applyPreferences(value)
    }

    private fun requirePlayback(block: (PlaybackService.LocalBinder) -> Unit) {
        val binder = playbackBinder
        if (binder == null) Toast.makeText(this, "Playback is starting", Toast.LENGTH_SHORT).show() else block(binder)
    }

    private fun showOperation(result: BluetoothController.OperationResult) {
        showMessage(result.message)
        store.dispatch(AppAction.BluetoothChanged(bluetoothController.snapshot()))
    }

    /**
     * Starts Bluetooth management only while the user is on the Bluetooth screen
     * and the Activity is visible, and never in Safe Mode. Called on every state
     * change and on start/stop; the tracked flag keeps it to real transitions so
     * repeated states are cheap no-ops.
     */
    private fun bindPlaybackServiceIfNeeded() {
        if (playbackBound) return
        val serviceIntent = Intent(this, PlaybackService::class.java)
        startService(serviceIntent)
        // Set the guard before calling into the framework so even a synchronous
        // vendor callback observes a live binding request.
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
        // emitImmediately renders the latest store state as soon as we re-register.
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
