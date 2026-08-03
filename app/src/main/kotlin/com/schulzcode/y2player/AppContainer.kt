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
import com.schulzcode.y2player.diagnostics.LogWriter
import com.schulzcode.y2player.storage.Y2StoragePaths
import java.io.File
import com.schulzcode.y2player.library.LibraryDatabase
import com.schulzcode.y2player.library.LibraryRepository
import com.schulzcode.y2player.input.HapticController
import com.schulzcode.y2player.safe.SafeModeManager
import com.schulzcode.y2player.settings.AppPreferences
import com.schulzcode.y2player.storage.StorageMonitor
import com.schulzcode.y2player.storage.UsbStateMonitor

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val logWriter = LogWriter()

    val logger: DiagnosticLogger by lazy { DiagnosticLogger(appContext, logWriter) }

    val eventLog: EventLog by lazy {
        EventLog(
            primaryDirectory = File(appContext.filesDir, "logs"),
            mirrorProvider = {
                Y2StoragePaths.roots
                    .firstOrNull { it.id == "sdcard" && Y2StoragePaths.isAvailable(it) }
                    ?.let { File(it.directory, "Y2Player/logs") }
            },
            appVersion = BuildConfig.VERSION_NAME,
            buildId = BuildConfig.BUILD_ID,
            writer = logWriter
        )
    }
    val database: LibraryDatabase by lazy { LibraryDatabase(appContext) }
    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(appContext, database, logger = logger, eventLog = eventLog)
    }
    val usbStateMonitor: UsbStateMonitor by lazy { UsbStateMonitor(appContext, eventLog) }
    val diagnosticsRepository: DiagnosticsRepository by lazy { DiagnosticsRepository(logger, eventLog) }
    val appStore: AppStore by lazy { AppStore() }
    val preferences: AppPreferences by lazy { AppPreferences(appContext) }
    private val hapticControllerLazy = lazy {
        HapticController(appContext, eventLog).also { it.setLevel(preferences.snapshot().hapticLevel) }
    }
    val hapticController: HapticController by hapticControllerLazy
    val safeModeManager: SafeModeManager by lazy { SafeModeManager(appContext, logger) }
    val storageMonitor: StorageMonitor by lazy { StorageMonitor(appContext, eventLog) }
    private val bluetoothControllerLazy = lazy { BluetoothController(appContext, logger, eventLog) }
    val bluetoothController: BluetoothController by bluetoothControllerLazy

    fun bluetoothControllerOrNull(): BluetoothController? =
        if (bluetoothControllerLazy.isInitialized()) bluetoothControllerLazy.value else null

    private val artworkLoaderLazy = lazy { AlbumArtworkLoader() }
    val artworkLoader: AlbumArtworkLoader by artworkLoaderLazy

    fun artworkLoaderOrNull(): AlbumArtworkLoader? =
        if (artworkLoaderLazy.isInitialized()) artworkLoaderLazy.value else null

    fun hapticControllerOrNull(): HapticController? =
        if (hapticControllerLazy.isInitialized()) hapticControllerLazy.value else null

    val deviceProfile: DeviceProfile by lazy { DeviceProfileLoader.load(appContext) }
}
