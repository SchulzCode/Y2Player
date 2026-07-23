package com.schulzcode.y2player

import android.content.Context
import com.schulzcode.y2player.artwork.AlbumArtworkLoader
import com.schulzcode.y2player.bluetooth.BluetoothController
import com.schulzcode.y2player.core.device.DeviceProfile
import com.schulzcode.y2player.core.device.DeviceProfileLoader
import com.schulzcode.y2player.core.state.AppStore
import com.schulzcode.y2player.diagnostics.DiagnosticLogger
import com.schulzcode.y2player.diagnostics.DiagnosticsRepository
import com.schulzcode.y2player.diagnostics.EventLog
import com.schulzcode.y2player.storage.Y2StoragePaths
import java.io.File
import com.schulzcode.y2player.library.LibraryDatabase
import com.schulzcode.y2player.library.LibraryRepository
import com.schulzcode.y2player.safe.SafeModeManager
import com.schulzcode.y2player.settings.AppPreferences
import com.schulzcode.y2player.storage.StorageMonitor
import com.schulzcode.y2player.storage.UsbStateMonitor

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val logger: DiagnosticLogger by lazy { DiagnosticLogger(appContext) }

    /**
     * Structured NDJSON log.
     *
     * The primary destination is app-internal storage, which is always mounted;
     * the removable card is a best-effort mirror so a field report can also be
     * collected by pulling the card out of a device that will not boot.
     *
     * The card must not be primary. Enabling USB mass storage unmounts it, so a
     * card-primary log stops recording precisely during the transition it would
     * be needed to explain. The mirror directory is therefore re-resolved on
     * every write rather than captured once.
     */
    val eventLog: EventLog by lazy {
        EventLog(
            primaryDirectory = File(appContext.filesDir, "logs"),
            mirrorProvider = {
                Y2StoragePaths.roots
                    .firstOrNull { it.id == "sdcard" && Y2StoragePaths.isAvailable(it) }
                    ?.let { File(it.directory, "Y2Player/logs") }
            },
            appVersion = BuildConfig.VERSION_NAME,
            buildId = BuildConfig.BUILD_ID
        )
    }
    val database: LibraryDatabase by lazy { LibraryDatabase(appContext) }
    val libraryRepository: LibraryRepository by lazy { LibraryRepository(database, logger = logger, eventLog = eventLog) }
    /** Read-only USB gadget reporting. Cannot and does not switch USB modes. */
    val usbStateMonitor: UsbStateMonitor by lazy { UsbStateMonitor(appContext, eventLog) }
    val diagnosticsRepository: DiagnosticsRepository by lazy { DiagnosticsRepository(logger, eventLog) }
    val appStore: AppStore by lazy { AppStore() }
    val preferences: AppPreferences by lazy { AppPreferences(appContext) }
    val safeModeManager: SafeModeManager by lazy { SafeModeManager(appContext, logger) }
    val storageMonitor: StorageMonitor by lazy { StorageMonitor(appContext) }
    val bluetoothController: BluetoothController by lazy { BluetoothController(appContext, logger) }

    /**
     * Process-wide artwork loader shared by the Now Playing view and the
     * RemoteControlClient adapter. Both request the same 256 px decode, so one cache
     * entry and one MediaMetadataRetriever pass serve both per track change.
     * Never shut down: it lives for the process.
     */
    val artworkLoader: AlbumArtworkLoader by lazy { AlbumArtworkLoader() }

    /**
     * Measured hardware facts, resolved once. Y2Application warms this on a
     * background thread at startup so the sysfs read never lands on the main
     * thread; `by lazy` keeps it correct if something reads it earlier.
     */
    val deviceProfile: DeviceProfile by lazy { DeviceProfileLoader.load(appContext) }
}
