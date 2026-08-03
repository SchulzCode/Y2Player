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
import com.schulzcode.y2player.settings.SystemHapticsController
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

    private val storageCoordinator = StorageMonitor.Listener { device ->
        if (container.safeModeManager.isSafeMode()) return@Listener
        val current = device.storageVolumes.associate { it.id to it.available }
        val firstSnapshot = !storageSnapshotSeen
        storageSnapshotSeen = true
        val changes = StorageTransitionPolicy.classify(
            lastStorageAvailability, current, firstSnapshot
        )
        changes.becameUnavailable.forEach(container.libraryRepository::markVolumeUnavailable)
        changes.missingAtStartup.forEach(::scheduleBootReconcile)
        if (changes.becameAvailable.isNotEmpty() &&
            remountScanGate.onVolumesMounted(changes.becameAvailable, SystemClock.elapsedRealtime()) &&
            !container.safeModeManager.isSafeMode()
        ) {
            container.libraryRepository.scan(ScanReason.VOLUME_MOUNTED)
        }
        lastStorageAvailability = current
    }

    private val contentCoordinator = StorageMonitor.ContentListener { reason ->
        if (container.safeModeManager.isSafeMode()) return@ContentListener
        if (!remountScanGate.onContentHint(SystemClock.elapsedRealtime())) {
            container.logger.info("Storage", "external content hint ($reason) coalesced with remount")
            return@ContentListener
        }
        container.logger.info("Storage", "external content changed ($reason); rescanning")
        container.libraryRepository.scan(ScanReason.fromContentHint(reason))
    }

    private val usbCoordinator = UsbStateMonitor.Listener { usb ->
        container.diagnosticsRepository.setUsbState(usb)
    }

    private val bluetoothOwnershipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
            mainHandler.removeCallbacks(mediaButtonReassert)
            if (state != BluetoothProfile.STATE_CONNECTED) return
            reassertMediaButtons("a2dp_connected")
            mainHandler.postDelayed(mediaButtonReassert, AVRCP_SETTLE_MS)
            mainHandler.postDelayed(mediaButtonReassert, AVRCP_SETTLE_MS * 3)
        }
    }

    private val mediaButtonReassert = Runnable { reassertMediaButtons("avrcp_settle") }

    // On API 19 the last registrant of the media-button receiver wins.
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

        val systemHaptics = SystemHapticsController(this).suppress()
        if (systemHaptics.success) {
            container.logger.info(
                "Settings",
                "platform haptics disabled previous=${systemHaptics.previousValue}"
            )
        } else {
            container.logger.warn("Settings", systemHaptics.message)
        }

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
                container.logger.crash("Crash", "uncaught on ${thread.name}", error)
                container.eventLog.crashFlush(error)
            } catch (_: Throwable) {
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }

        val verbose = container.preferences.snapshot().verboseDiagnostics
        container.eventLog.setEnabled(verbose)
        container.logger.setVerbose(verbose)
        container.eventLog.info(
            Sub.APP, Ev.APP_START,
            "safeMode" to safeMode,
            "api" to android.os.Build.VERSION.SDK_INT
        )
        logEnvironment()

        MediaButtonReceiver.register(this, container.logger)
        runCatching {
            registerReceiver(
                bluetoothOwnershipReceiver,
                IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            )
        }.onFailure { container.logger.warn("MediaButton", "A2DP ownership watch unavailable: ${it.message}") }

        Thread({
            runCatching {
                val profile = container.deviceProfile
                container.logger.info("Device", profile.summary())
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
        container.usbStateMonitor.addListener(usbCoordinator, emitImmediately = false)
        container.usbStateMonitor.start()
    }

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
            "filesDir" to runCatching { filesDir?.canWrite() }.getOrNull(),
            "externalStorage" to externalState
        )
    }

    override fun onTerminate() {
        container.storageMonitor.removeListener(storageCoordinator)
        container.storageMonitor.removeContentListener(contentCoordinator)
        container.storageMonitor.stop()
        container.usbStateMonitor.removeListener(usbCoordinator)
        container.usbStateMonitor.stop()
        runCatching { unregisterReceiver(bluetoothOwnershipReceiver) }
        mainHandler.removeCallbacks(mediaButtonReassert)
        container.bluetoothControllerOrNull()?.stop()
        container.hapticControllerOrNull()?.release()
        super.onTerminate()
    }

    override fun onLowMemory() {
        container.libraryRepository.cancelScan("system low memory")
        container.artworkLoaderOrNull()?.trimMemory()
        ScreenContent.clearCachedRows()
        super.onLowMemory()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
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
        private const val AVRCP_SETTLE_MS = 2_000L
    }
}
