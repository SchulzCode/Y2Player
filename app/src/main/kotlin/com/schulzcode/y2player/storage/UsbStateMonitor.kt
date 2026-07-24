package com.schulzcode.y2player.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import com.schulzcode.y2player.diagnostics.Ev
import com.schulzcode.y2player.diagnostics.EventLog
import com.schulzcode.y2player.diagnostics.Sub
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Reports USB gadget state. Reports only — see [UsbState] for why nothing here
 * can switch the device into mass storage mode.
 *
 * ## Why it never polls
 * Every transition the app cares about already arrives as a broadcast:
 * `USB_STATE` for connect/configure, `ACTION_POWER_CONNECTED/DISCONNECTED` and
 * `ACTION_BATTERY_CHANGED` for charging. Polling sysfs on a timer would keep the
 * CPU out of idle on a battery-powered player to learn nothing new. The sysfs
 * nodes are read once per relevant transition, on a worker thread, and only to
 * refine what the broadcast already said. Unchanged battery broadcasts are ignored.
 *
 * ## Why the reads are guarded so heavily
 * The nodes under `/sys/class/android_usb/android0` are frequently unreadable to an
 * unprivileged app on this firmware, and on some units the node exists but
 * blocks. Reads are bounded to [UsbSysfs.MAX_NODE_BYTES], wrapped in
 * `runCatching`, never touch the main thread, and an unreadable node is a
 * reported fact rather than an error.
 */
class UsbStateMonitor(
    context: Context,
    private val eventLog: EventLog? = null
) {
    fun interface Listener { fun onUsbStateChanged(state: UsbState) }

    private val appContext = context.applicationContext
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "y2-usb").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private var registered = false

    @Volatile private var latest = UsbState()
    @Volatile private var broadcastConnected = false
    @Volatile private var broadcastConfigured = false
    @Volatile private var charging = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // A plain battery broadcast (voltage/temperature/percentage) fires every
            // few seconds and never changes the USB picture. The policy refreshes on
            // connect/configure and on a genuine charging flip only, so those noisy
            // broadcasts no longer schedule a worker or read sysfs.
            val decision = when (intent?.action) {
                ACTION_USB_STATE -> {
                    broadcastConnected = intent.getBooleanExtra(EXTRA_CONNECTED, false)
                    broadcastConfigured = intent.getBooleanExtra(EXTRA_CONFIGURED, false)
                    UsbRefreshPolicy.onUsbState(charging)
                }
                Intent.ACTION_POWER_CONNECTED ->
                    UsbRefreshPolicy.onChargingSignal(charging, reportedCharging = true)
                Intent.ACTION_POWER_DISCONNECTED ->
                    UsbRefreshPolicy.onChargingSignal(charging, reportedCharging = false)
                Intent.ACTION_BATTERY_CHANGED -> {
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val reported = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                    UsbRefreshPolicy.onChargingSignal(charging, reported)
                }
                else -> return
            }
            charging = decision.charging
            if (decision.refresh) refresh()
        }
    }

    fun snapshot(): UsbState = latest

    fun addListener(listener: Listener, emitImmediately: Boolean = true) {
        listeners += listener
        if (emitImmediately) listener.onUsbStateChanged(latest)
    }

    fun removeListener(listener: Listener) { listeners -= listener }

    fun start() {
        if (registered) return
        runCatching {
            appContext.registerReceiver(receiver, IntentFilter(ACTION_USB_STATE))
            appContext.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                }
            )
        }
        registered = true
        refresh()
    }

    fun stop() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
    }

    /** Reads sysfs off the main thread and publishes only on an actual change. */
    fun refresh() {
        executor.execute {
            val state = UsbSysfs.build(
                broadcastConnected = broadcastConnected,
                broadcastConfigured = broadcastConfigured,
                charging = charging,
                rawFunctions = readNode(UsbSysfs.FUNCTIONS_PATH),
                rawState = readNode(UsbSysfs.STATE_PATH)
            )
            val previous = latest
            if (state == previous) return@execute
            latest = state
            // Transitions only: a state that has not changed is not an event.
            eventLog?.info(
                Sub.USB, Ev.USB_STATE,
                "connected" to state.connected,
                "configured" to state.configured,
                "charging" to state.charging,
                "mtp" to state.mtp,
                "adb" to state.adb,
                "ums" to state.massStorage,
                "gadget" to state.gadgetState,
                "sysfs" to !state.sysfsUnavailable
            )
            if (state.functions != previous.functions) {
                eventLog?.info(Sub.USB, Ev.USB_FUNCTIONS, "functions" to state.functions)
            }
            handler.post { listeners.forEach { it.onUsbStateChanged(state) } }
        }
    }

    /**
     * Bounded, non-throwing read. Reads at most [UsbSysfs.MAX_NODE_BYTES]: these
     * are sysfs attributes, so anything longer means the node is not what we
     * think it is, and reading it wholesale would be a mistake.
     */
    private fun readNode(path: String): String? = runCatching {
        val file = File(path)
        if (!file.canRead()) return@runCatching null
        val buffer = ByteArray(UsbSysfs.MAX_NODE_BYTES)
        val read = file.inputStream().use { it.read(buffer) }
        if (read <= 0) null else String(buffer, 0, read).trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private companion object {
        // @hide on API 19; the action and extra names are stable across MTK 4.4.
        const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_CONFIGURED = "configured"
    }
}

/** Pure transition policy kept beside its only Android consumer. */
internal object UsbRefreshPolicy {
    data class Decision(val charging: Boolean, val refresh: Boolean)

    fun onUsbState(currentCharging: Boolean): Decision =
        Decision(charging = currentCharging, refresh = true)

    fun onChargingSignal(currentCharging: Boolean, reportedCharging: Boolean): Decision =
        Decision(charging = reportedCharging, refresh = reportedCharging != currentCharging)
}
