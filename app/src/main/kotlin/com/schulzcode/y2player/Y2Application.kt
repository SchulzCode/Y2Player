package com.schulzcode.y2player

import android.app.Application
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.schulzcode.y2player.diagnostics.Ev
import com.schulzcode.y2player.diagnostics.Sub
import com.schulzcode.y2player.core.state.ScreenContent
import com.schulzcode.y2player.library.ScanReason
import com.schulzcode.y2player.playback.MediaButtonReceiver
import com.schulzcode.y2player.playback.NativeAudio
import com.schulzcode.y2player.storage.StorageMonitor
import com.schulzcode.y2player.storage.RemountScanGate
import com.schulzcode.y2player.storage.StorageTransitionPolicy
import com.schulzcode.y2player.storage.UsbStateMonitor
import java.io.File

class Y2Application : Application() {
    lateinit var container: AppContainer
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastStorageAvailability: Map<String, Boolean> = emptyMap()
    private var storageSnapshotSeen = false
    private val remountScanGate = RemountScanGate()

    /**
     * Reconciles library availability with mount transitions. Scans remain
     * suppressed in Safe Mode and while storage is unavailable to Android.
     */
    private val storageCoordinator = StorageMonitor.Listener { device ->
        if (container.safeModeManager.isSafeMode()) return@Listener
        val current = device.storageVolumes.associate { it.id to it.available }
        val firstSnapshot = !storageSnapshotSeen
        storageSnapshotSeen = true
        val changes = StorageTransitionPolicy.classify(
            lastStorageAvailability, current, firstSnapshot
        )
        changes.becameUnavailable.forEach(container.libraryRepository::markVolumeUnavailable)
        // As the HOME app this process can start before a card mounts. Defer the
        // first missing snapshot so slow boot storage does not trigger a full DB
        // availability rewrite and immediate recovery scan.
        changes.missingAtStartup.forEach(::scheduleBootReconcile)
        if (changes.becameAvailable.isNotEmpty() &&
            remountScanGate.onVolumesMounted(changes.becameAvailable, SystemClock.elapsedRealtime()) &&
            !container.safeModeManager.isSafeMode()
        ) {
            container.libraryRepository.scan(ScanReason.VOLUME_MOUNTED)
        }
        lastStorageAvailability = current
    }

    /**
     * A PC file transfer (MTP) never unmounts storage, so without this the newly
     * copied music stays invisible until a manual rescan. The scan is incremental
     * — unchanged files cost a stat, not a metadata read — so reacting to the
     * hint is cheap even when nothing actually changed.
     */
    private val contentCoordinator = StorageMonitor.ContentListener { reason ->
        if (container.safeModeManager.isSafeMode()) return@ContentListener
        if (!remountScanGate.onContentHint(SystemClock.elapsedRealtime())) {
            container.logger.info("Storage", "external content hint ($reason) coalesced with remount")
            return@ContentListener
        }
        // A content hint during an export describes a volume this process must
        // not touch; the post-remount scan covers whatever the host changed.
        container.logger.info("Storage", "external content changed ($reason); rescanning")
        container.libraryRepository.scan(ScanReason.fromContentHint(reason))
    }

    private val usbCoordinator = UsbStateMonitor.Listener { usb ->
        container.diagnosticsRepository.setUsbState(usb)
    }

    /**
     * Re-takes media-button ownership when a Bluetooth audio device connects.
     *
     * On API 19 the last caller of `registerMediaButtonEventReceiver` wins, and
     * this process registers once at startup — long before a headset is paired.
     * Anything that claimed the buttons in between silently owns them, so the
     * first stem press after connecting went elsewhere and the headset appeared
     * dead. Starting playback from the device's own keys fixed it only because
     * [com.schulzcode.y2player.playback.PlaybackService] re-asserts ownership on
     * every engine start; connecting a headset had no equivalent.
     *
     * This lives process-wide rather than in the playback service because the
     * service stops itself when idle: with the screen off and nothing playing,
     * there is no service alive to notice the connection. The receiver does one
     * AudioManager call on a connect transition and nothing otherwise.
     */
    private val bluetoothOwnershipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
            mainHandler.removeCallbacks(mediaButtonReassert)
            if (state != BluetoothProfile.STATE_CONNECTED) return
            reassertMediaButtons("a2dp_connected")
            // A2DP connecting is not AVRCP being ready: the remote-control link
            // is established afterwards, and API 19 exposes no broadcast for it
            // (BluetoothAvrcpController is API 23+, and is the controller role).
            // Device logs showed the claim above landing in the same millisecond
            // as A2DP CONNECTED and the first stem presses still going nowhere,
            // so the claim is repeated as the link settles. Re-registering is one
            // idempotent AudioManager call, so a redundant repeat costs nothing
            // and a needed one restores the headset.
            mainHandler.postDelayed(mediaButtonReassert, AVRCP_SETTLE_MS)
            mainHandler.postDelayed(mediaButtonReassert, AVRCP_SETTLE_MS * 3)
        }
    }

    private val mediaButtonReassert = Runnable { reassertMediaButtons("avrcp_settle") }

    private fun reassertMediaButtons(trigger: String) {
        MediaButtonReceiver.register(this, container.logger)
        container.logger.info("MediaButton", "ownership re-asserted trigger=$trigger")
        container.eventLog.info(
            Sub.BLUETOOTH, Ev.BT_OPERATION,
            "operation" to "media_button_reassert",
            "trigger" to trigger
        )
    }

    private fun scheduleBootReconcile(volumeId: String) {
        mainHandler.postDelayed({
            if (container.safeModeManager.isSafeMode()) return@postDelayed
            if (lastStorageAvailability[volumeId] != true) {
                container.libraryRepository.markVolumeUnavailable(volumeId)
            }
        }, BOOT_STORAGE_GRACE_MS)
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val safeMode = container.safeModeManager.initializeProcess()
        container.logger.info("Application", "Y2 Player starting safeMode=$safeMode")

        runCatching {
            val crashFile = File(filesDir, "diagnostics/y2-native-crash.log")
            crashFile.parentFile?.mkdirs()
            check(NativeAudio.nativeConfigureCrashReporter(crashFile.absolutePath)) {
                "native crash reporter rejected ${crashFile.absolutePath}"
            }
        }.onFailure {
            container.logger.warn("Crash", "native crash reporter unavailable: ${it.message}")
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                // Synchronous write paths: the process is dying, so neither async
                // queue can be relied on to drain on its own.
                container.logger.crash("Crash", "uncaught on ${thread.name}", error)
                container.eventLog.crashFlush(error)
            } catch (_: Throwable) {
                // A logger that throws inside the crash handler must not replace
                // the original exception with its own, or the real fault is lost
                // and the delegate below never runs.
            } finally {
                // Always delegate: the platform handler is what actually kills
                // the process and writes the framework crash record.
                previous?.uncaughtException(thread, error)
            }
        }

        // Both logs follow the one user preference. Applied as early as possible:
        // until this runs the human-readable log stays verbose so that a failure
        // during container construction is still recorded.
        val verbose = container.preferences.snapshot().verboseDiagnostics
        container.eventLog.setEnabled(verbose)
        container.logger.setVerbose(verbose)
        container.eventLog.info(
            Sub.APP, Ev.APP_START,
            "safeMode" to safeMode,
            "api" to android.os.Build.VERSION.SDK_INT
        )
        logEnvironment()

        // Keep only the lightweight API-19 receiver registration process-wide.
        // The playback service may stop while paused without surrendering remote
        // ownership; the manifest receiver can then cold-start it on the next key.
        MediaButtonReceiver.register(this, container.logger)
        // ...and re-asserted whenever a headset connects, because the initial
        // claim above can be superseded before the user ever pairs one.
        runCatching {
            registerReceiver(
                bluetoothOwnershipReceiver,
                IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            )
        }.onFailure { container.logger.warn("MediaButton", "A2DP ownership watch unavailable: ${it.message}") }

        // Resolve measured hardware facts off the main thread and record them once.
        // Every later diagnostic can then be read against a known device identity
        // instead of trusting ro.product.model, which is known to lie on this family.
        Thread({
            runCatching {
                val profile = container.deviceProfile
                container.logger.info("Device", profile.summary())
                // Attach the device identity to every subsequent structured event.
                container.eventLog.setDeviceProvider { profile.summary() }
                container.eventLog.info(
                    Sub.DEVICE, Ev.DEVICE_PROFILE,
                    "family" to profile.family,
                    "confidence" to profile.confidence,
                    "panel" to profile.panel.toString(),
                    "display" to "${profile.displayWidth}x${profile.displayHeight}",
                    "dpi" to profile.densityDpi,
                    "hardware" to profile.hardware,
                    "model" to profile.model,
                    "vibrator" to profile.hasVibrator,
                    "sysfs" to profile.sysfsVirtualSize
                )
            }
        }, "y2-device-profile").apply { isDaemon = true }.start()

        container.storageMonitor.addListener(storageCoordinator, emitImmediately = false)
        container.storageMonitor.addContentListener(contentCoordinator)
        container.storageMonitor.start()
        // Diagnostics only: reports USB state, never changes it.
        container.usbStateMonitor.addListener(usbCoordinator, emitImmediately = false)
        container.usbStateMonitor.start()
        // Bluetooth management is scoped to the Bluetooth screen (see MainActivity),
        // so nothing is started process-wide here. Route safety for playback is
        // handled independently by AudioRouteMonitor.
    }

    /**
     * Recorded once per process so any later event can be read against a known
     * build, a known API level and a known storage situation. Everything here is
     * a cheap property read; nothing probes hardware, and the device profile
     * (which does measure) is logged separately off the main thread.
     */
    private fun logEnvironment() {
        val externalState = runCatching { android.os.Environment.getExternalStorageState() }
            .getOrDefault("unknown")
        container.eventLog.info(
            Sub.APP, Ev.APP_ENVIRONMENT,
            "version" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE,
            "buildId" to BuildConfig.BUILD_ID,
            "debug" to BuildConfig.DEBUG,
            "package" to packageName,
            "api" to android.os.Build.VERSION.SDK_INT,
            "model" to android.os.Build.MODEL,
            "product" to android.os.Build.PRODUCT,
            "fingerprint" to android.os.Build.FINGERPRINT,
            "pid" to android.os.Process.myPid(),
            // Whether the primary log destination is usable at all. If this is
            // false the app is running without persistent diagnostics and every
            // later absence of evidence is explained by this one line.
            "filesDir" to runCatching { filesDir?.canWrite() }.getOrNull(),
            "externalStorage" to externalState
        )
    }

    /**
     * Not called on a real device — the platform kills the process instead — so
     * this exists for emulator and instrumentation runs only. It must therefore
     * not *create* anything: [AppContainer.bluetoothControllerOrNull] stops the
     * controller if the Bluetooth screen ever built one and does nothing if it
     * did not.
     */
    override fun onTerminate() {
        container.storageMonitor.removeListener(storageCoordinator)
        container.storageMonitor.removeContentListener(contentCoordinator)
        container.storageMonitor.stop()
        container.usbStateMonitor.removeListener(usbCoordinator)
        container.usbStateMonitor.stop()
        runCatching { unregisterReceiver(bluetoothOwnershipReceiver) }
        mainHandler.removeCallbacks(mediaButtonReassert)
        container.bluetoothControllerOrNull()?.stop()
        super.onTerminate()
    }

    override fun onLowMemory() {
        container.libraryRepository.cancelScan("system low memory")
        container.artworkLoaderOrNull()?.trimMemory()
        ScreenContent.clearCachedRows()
        super.onLowMemory()
    }

    // TRIM_MEMORY_RUNNING_LOW is deprecated in modern SDKs but is the only
    // granularity API 19 delivers.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        // TRIM_MEMORY_UI_HIDDEN (20) is not memory pressure — it fires whenever another
        // window comes to the front. Cancelling a multi-minute scan for that throws away
        // real work; only genuine pressure levels abort the scan.
        if (level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN &&
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        ) {
            container.libraryRepository.cancelScan("trim memory level=$level")
            container.artworkLoaderOrNull()?.trimMemory()
            ScreenContent.clearCachedRows()
        }
        super.onTrimMemory(level)
    }

    companion object {
        private const val BOOT_STORAGE_GRACE_MS = 10_000L
        /** Repeat offsets are this and 3x this, so ~2 s and ~6 s after connect. */
        private const val AVRCP_SETTLE_MS = 2_000L
    }
}
