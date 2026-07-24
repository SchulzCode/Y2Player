package com.schulzcode.y2player.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.schulzcode.y2player.core.state.BluetoothAdapterMode
import com.schulzcode.y2player.core.state.BluetoothDeviceEntry
import com.schulzcode.y2player.core.state.BluetoothLinkState
import com.schulzcode.y2player.core.state.BluetoothUiState
import com.schulzcode.y2player.diagnostics.DiagnosticLogger
import com.schulzcode.y2player.diagnostics.Ev
import com.schulzcode.y2player.diagnostics.EventLog
import com.schulzcode.y2player.diagnostics.Sub
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArraySet

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BluetoothController(
    context: Context,
    private val logger: DiagnosticLogger,
    private val eventLog: EventLog? = null
) {
    fun interface Listener { fun onBluetoothChanged(state: BluetoothUiState) }
    data class OperationResult(val accepted: Boolean, val message: String)

    /**
     * The operation currently awaiting a framework callback.
     *
     * Previously these were bare strings compared across four methods and handed
     * straight to the UI as [BluetoothUiState.pendingOperation]. Rewording the
     * user-visible text silently broke pair-then-connect and the operation
     * timeout, with nothing to catch it. The label stays presentational; the
     * identity is now the enum constant.
     */
    enum class Operation(val label: String) {
        PAIRING("Pairing"),
        CONNECTION("Connection"),
        DISCONNECTION("Disconnection"),
        FORGETTING("Forgetting")
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val discovered = LinkedHashMap<String, BluetoothDevice>()
    private val playingAddresses = linkedSetOf<String>()
    private val hiddenMethods = HashMap<String, java.lang.reflect.Method?>()
    private var a2dp: BluetoothA2dp? = null
    private var registered = false
    private var started = false
    private var pendingAddress: String? = null
    private var pendingOperation: Operation? = null
    private var lastError: String? = null
    private var proxyGeneration = 0L
    private var proxyRequestPending = false
    private var proxyRetryCount = 0
    private var uiActive = false
    private var stopWhenIdle = false

    private val proxyReconnectRunnable = Runnable {
        if (started) requestA2dpProxy()
    }

    private val timeoutRunnable = Runnable {
        val address = pendingAddress
        val operation = pendingOperation
        if (address != null && operation != null) {
            lastError = "${operation.label} timed out for ${deviceName(address)}"
            logger.warn("Bluetooth", lastError.orEmpty())
            eventLog?.warn(
                Sub.BLUETOOTH, Ev.BT_OPERATION,
                "operation" to operation.name,
                "device" to maskedAddress(address),
                "result" to "timeout"
            )
            clearPending()
            // If the UI already left, clearPending has queued the deferred stop;
            // do not open a fresh proxy just to close it on the next main-loop turn.
            if (!stopWhenIdle) refreshA2dpProxy()
            publish()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            when (action) {
                BluetoothDevice.ACTION_FOUND,
                BluetoothDevice.ACTION_NAME_CHANGED -> if (device != null && isAudioDevice(device)) {
                    discovered[device.address] = device
                    val diagnosticName = device.name?.takeIf { it.isNotBlank() } ?: "unnamed audio device"
                    logger.info("Bluetooth", "found $diagnosticName/${maskedAddress(device.address)}")
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> if (device != null) handleBondState(device, intent)
                BluetoothAdapter.ACTION_STATE_CHANGED -> handleAdapterState()
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> logger.info("Bluetooth", "discovery started")
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> logger.info("Bluetooth", "discovery finished")
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> if (device != null) handleA2dpState(device, intent)
                BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED -> if (device != null) handlePlayingState(device, intent)
            }
            publish()
        }
    }

    fun addListener(listener: Listener, emitImmediately: Boolean = true) {
        listeners += listener
        if (emitImmediately) listener.onBluetoothChanged(buildSnapshot())
    }

    fun removeListener(listener: Listener) { listeners -= listener }

    fun start() {
        if (started) { publish(); return }
        started = true
        proxyRetryCount = 0
        if (adapter == null) { publish(); return }
        registerReceiver()
        requestA2dpProxy()
        logger.info("Bluetooth", "controller started enabled=${adapter.isEnabled}")
        publish()
    }

    /**
     * Ties Bluetooth management to the Bluetooth screen. Activating opens the
     * receiver and A2DP proxy; deactivating closes them — which never disconnects
     * the headset, since the system owns the A2DP link. A deactivation requested
     * while an operation is in flight is deferred so the proxy is never torn down
     * mid pair/connect/disconnect/forget.
     */
    fun setUiActive(active: Boolean) {
        uiActive = active
        if (active) {
            stopWhenIdle = false
            start()
            logger.info("Bluetooth", "UI active: management started")
        } else if (pendingOperation == null) {
            stop()
        } else {
            stopWhenIdle = true
            logger.info("Bluetooth", "UI inactive: stop deferred until '${pendingOperation?.label}' finishes")
        }
    }

    private fun maybeStopAfterIdle() {
        if (stopWhenIdle && !uiActive && pendingOperation == null) {
            stopWhenIdle = false
            logger.info("Bluetooth", "stopping after deferred operation finished")
            stop()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        stopWhenIdle = false
        mainHandler.removeCallbacks(proxyReconnectRunnable)
        mainHandler.removeCallbacks(publishRunnable)
        proxyRetryCount = 0
        clearPending()
        if (registered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            registered = false
        }
        runCatching { adapter?.cancelDiscovery() }
        playingAddresses.clear()
        closeProxy()
        logger.info("Bluetooth", "controller stopped")
        publish()
    }

    fun snapshot(): BluetoothUiState = buildSnapshot()

    fun setEnabled(enabled: Boolean): OperationResult {
        val local = adapter ?: return OperationResult(false, "Bluetooth hardware is unavailable")
        lastError = null
        val accepted = runCatching { if (enabled) local.enable() else local.disable() }.getOrDefault(false)
        logger.info("Bluetooth", "set enabled=$enabled accepted=$accepted")
        publish()
        return OperationResult(
            accepted,
            if (accepted) {
                if (enabled) "Turning Bluetooth on" else "Turning Bluetooth off"
            } else {
                if (enabled) "Bluetooth could not be enabled" else "Bluetooth could not be disabled"
            }
        )
    }

    fun startScan(): OperationResult {
        val local = adapter ?: return OperationResult(false, "Bluetooth hardware is unavailable")
        if (!local.isEnabled) return OperationResult(false, "Turn Bluetooth on first")
        // Single-radio hardware: inquiry scanning while A2DP is connected or playing
        // wastes power and can glitch the audio, so refuse rather than scan over it.
        if (hasActiveA2dpConnection()) {
            logger.info("Bluetooth", "scan blocked: active A2DP audio present")
            return OperationResult(false, SCAN_BLOCKED_MESSAGE)
        }
        lastError = null
        runCatching { if (local.isDiscovering) local.cancelDiscovery() }
        discovered.entries.removeAll { (_, value) -> value.bondState != BluetoothDevice.BOND_BONDED }
        val accepted = runCatching { local.startDiscovery() }.getOrDefault(false)
        logger.info("Bluetooth", "scan accepted=$accepted")
        publish()
        return OperationResult(accepted, if (accepted) "Scanning for audio devices" else "Bluetooth scan could not start")
    }

    fun stopScan(): OperationResult {
        val accepted = runCatching { adapter?.cancelDiscovery() == true }.getOrDefault(false)
        publish()
        return OperationResult(accepted, if (accepted) "Bluetooth scan stopped" else "No Bluetooth scan was active")
    }

    fun refreshA2dpProxy(): OperationResult {
        if (adapter == null) return OperationResult(false, "Bluetooth hardware is unavailable")
        if (!started) return OperationResult(false, "Bluetooth is disabled in Safe Mode")
        mainHandler.removeCallbacks(proxyReconnectRunnable)
        proxyRetryCount = 0
        closeProxy()
        requestA2dpProxy()
        logger.info("Bluetooth", "A2DP service refresh requested")
        return OperationResult(true, "Restarting Bluetooth audio service")
    }

    fun activateDevice(address: String): OperationResult {
        val local = adapter ?: return OperationResult(false, "Bluetooth hardware is unavailable")
        if (!local.isEnabled) return OperationResult(false, "Turn Bluetooth on first")
        val device = runCatching { local.getRemoteDevice(address) }.getOrNull()
            ?: return OperationResult(false, "Bluetooth device is unavailable")
        return when {
            a2dpState(device) == BluetoothProfile.STATE_CONNECTED -> disconnectA2dp(device)
            device.bondState == BluetoothDevice.BOND_BONDING -> OperationResult(false, "Pairing is already in progress")
            device.bondState == BluetoothDevice.BOND_BONDED -> connectA2dp(device)
            else -> pair(device)
        }
    }

    fun forgetDevice(address: String): OperationResult {
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
            ?: return OperationResult(false, "Bluetooth device is unavailable")
        if (device.bondState == BluetoothDevice.BOND_NONE) return OperationResult(true, "Device is already forgotten")
        if (findHiddenMethod(device.javaClass, "removeBond") == null) {
            lastError = "Automatic forget is unavailable; remove the bond in Android Bluetooth settings"
            logger.warn("Bluetooth", lastError.orEmpty())
            publish()
            return OperationResult(false, lastError.orEmpty())
        }
        setPending(device.address, Operation.FORGETTING, CONNECT_TIMEOUT_MS)
        val accepted = invokeHiddenBoolean(device, "removeBond") == true
        if (!accepted) clearPending()
        logger.info("Bluetooth", "forget ${device.displayName()} accepted=$accepted")
        if (!accepted) lastError = "Could not forget ${device.displayName()}"
        publish()
        return OperationResult(accepted, if (accepted) "Forgetting ${device.displayName()}" else lastError.orEmpty())
    }

    private fun pair(device: BluetoothDevice): OperationResult {
        runCatching { adapter?.cancelDiscovery() }
        setPending(device.address, Operation.PAIRING, PAIR_TIMEOUT_MS)
        val accepted = runCatching { device.createBond() }.getOrDefault(false)
        if (!accepted) {
            lastError = "Pairing request was rejected"
            clearPending()
        }
        logger.info("Bluetooth", "pair ${device.displayName()} accepted=$accepted")
        publish()
        return OperationResult(accepted, if (accepted) "Pairing with ${device.displayName()}" else lastError.orEmpty())
    }

    private fun connectA2dp(device: BluetoothDevice): OperationResult {
        val proxy = a2dp ?: run {
            lastError = "A2DP service is still starting; try Refresh Audio Service or Android Bluetooth settings"
            scheduleProxyReconnect()
            return OperationResult(false, lastError.orEmpty())
        }
        if (findHiddenMethod(proxy.javaClass, "connect", BluetoothDevice::class.java) == null) {
            lastError = "Automatic A2DP connection is unavailable; connect in Android Bluetooth settings"
            logger.warn("Bluetooth", lastError.orEmpty())
            return OperationResult(false, lastError.orEmpty())
        }
        runCatching { adapter?.cancelDiscovery() }
        lastError = null
        runCatching {
            findHiddenMethod(proxy.javaClass, "setPriority", BluetoothDevice::class.java, Integer.TYPE)
                ?.invoke(proxy, device, 100)
                ?: error("setPriority method unavailable")
        }.onFailure { logger.warn("Bluetooth", "setPriority failed: ${it.javaClass.simpleName}") }
        setPending(device.address, Operation.CONNECTION, CONNECT_TIMEOUT_MS)
        val accepted = invokeBoolean(proxy, "connect", device) == true
        if (!accepted) {
            lastError = "A2DP connection request was rejected for ${device.displayName()}"
            clearPending()
        }
        logger.info("Bluetooth", "connect ${device.displayName()} accepted=$accepted")
        publish()
        return OperationResult(accepted, if (accepted) "Connecting to ${device.displayName()}" else lastError.orEmpty())
    }

    private fun disconnectA2dp(device: BluetoothDevice): OperationResult {
        val proxy = a2dp ?: return OperationResult(false, "A2DP service is unavailable")
        if (findHiddenMethod(proxy.javaClass, "disconnect", BluetoothDevice::class.java) == null) {
            lastError = "Automatic A2DP disconnection is unavailable; disconnect in Android Bluetooth settings"
            logger.warn("Bluetooth", lastError.orEmpty())
            return OperationResult(false, lastError.orEmpty())
        }
        setPending(device.address, Operation.DISCONNECTION, CONNECT_TIMEOUT_MS)
        val accepted = invokeBoolean(proxy, "disconnect", device) == true
        if (!accepted) {
            lastError = "A2DP disconnect request was rejected for ${device.displayName()}"
            clearPending()
        }
        logger.info("Bluetooth", "disconnect ${device.displayName()} accepted=$accepted")
        publish()
        return OperationResult(accepted, if (accepted) "Disconnecting ${device.displayName()}" else lastError.orEmpty())
    }

    private fun handleBondState(device: BluetoothDevice, intent: Intent) {
        discovered[device.address] = device
        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
        logger.info("Bluetooth", "bond ${device.displayName()} state=$state")
        when (state) {
            BluetoothDevice.BOND_BONDED -> if (pendingAddress == device.address && pendingOperation == Operation.PAIRING) {
                clearPending()
                connectA2dp(device)
            }
            BluetoothDevice.BOND_NONE -> if (pendingAddress == device.address) {
                if (pendingOperation == Operation.FORGETTING) {
                    lastError = null
                    discovered.remove(device.address)
                } else {
                    lastError = "Pairing failed for ${device.displayName()}"
                }
                clearPending()
            }
        }
    }

    private fun handleAdapterState() {
        logger.info("Bluetooth", "adapter state=${adapter?.state}")
        eventLog?.info(Sub.BLUETOOTH, Ev.BT_ADAPTER_STATE, "state" to adapter?.state)
        if (adapter?.state == BluetoothAdapter.STATE_ON) {
            proxyRetryCount = 0
            requestA2dpProxy()
        }
        if (adapter?.state == BluetoothAdapter.STATE_OFF) {
            discovered.clear()
            playingAddresses.clear()
            clearPending()
            closeProxy()
        }
    }

    private fun handleA2dpState(device: BluetoothDevice, intent: Intent) {
        discovered[device.address] = device
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
        logger.info("Bluetooth", "A2DP ${device.displayName()} state=$state")
        // Addresses are masked: a field log should be shareable without
        // identifying the reporter's headset.
        eventLog?.info(
            Sub.BLUETOOTH, Ev.BT_A2DP_STATE,
            "device" to maskedAddress(device.address),
            "state" to state,
            "pending" to pendingOperation?.name
        )
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> {
                if (pendingAddress == device.address && pendingOperation == Operation.CONNECTION) clearPending()
                lastError = null
                cancelDiscoveryForActiveAudio("A2DP connected")
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                if (pendingAddress == device.address && pendingOperation == Operation.DISCONNECTION) clearPending()
                playingAddresses.remove(device.address)
            }
        }
    }

    private fun handlePlayingState(device: BluetoothDevice, intent: Intent) {
        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothA2dp.STATE_NOT_PLAYING)
        if (state == BluetoothA2dp.STATE_PLAYING) playingAddresses += device.address else playingAddresses -= device.address
        logger.info("Bluetooth", "A2DP playing ${device.displayName()} state=$state")
        eventLog?.info(
            Sub.BLUETOOTH, Ev.BT_PLAYING_STATE,
            "device" to maskedAddress(device.address),
            "playing" to (state == BluetoothA2dp.STATE_PLAYING)
        )
        if (state == BluetoothA2dp.STATE_PLAYING) cancelDiscoveryForActiveAudio("A2DP playing")
    }

    /**
     * True when an A2DP device is connected or streaming. The connected-devices
     * query is a binder call that can fail if the proxy is mid-restart, so it is
     * guarded and treated as "not connected" on failure rather than crashing.
     */
    private fun hasActiveA2dpConnection(): Boolean {
        val proxyConnected = runCatching { a2dp?.connectedDevices?.isNotEmpty() == true }.getOrDefault(false)
        // The aggregate adapter state remains available while the UI-scoped
        // profile proxy is still connecting, closing a race that could otherwise
        // allow inquiry over an already-connected headset.
        val profileConnected = runCatching {
            adapter?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothAdapter.STATE_CONNECTED
        }.getOrDefault(false)
        return proxyConnected || profileConnected || playingAddresses.isNotEmpty()
    }

    /**
     * Cancels an in-flight inquiry when audio takes over the radio. Only touches
     * discovery — never the connection, codec, or transmit power — and does
     * nothing when no scan is running, so repeat events do not spam cancel calls.
     */
    private fun cancelDiscoveryForActiveAudio(reason: String) {
        val local = adapter ?: return
        if (!runCatching { local.isDiscovering }.getOrDefault(false)) return
        val cancelled = runCatching { local.cancelDiscovery() }.getOrDefault(false)
        if (cancelled) logger.info("Bluetooth", "discovery cancelled: $reason")
    }

    private fun requestA2dpProxy() {
        if (!started) return
        val local = adapter ?: return
        if (!local.isEnabled || a2dp != null || proxyRequestPending) return
        val generation = ++proxyGeneration
        proxyRequestPending = true
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile != BluetoothProfile.A2DP) return
                val connected = proxy as? BluetoothA2dp
                if (generation != proxyGeneration || !started) {
                    connected?.let { runCatching { local.closeProfileProxy(BluetoothProfile.A2DP, it) } }
                    return
                }
                proxyRequestPending = false
                a2dp = connected
                if (connected == null) {
                    logger.warn("Bluetooth", "A2DP service returned no proxy")
                    scheduleProxyReconnect()
                } else {
                    proxyRetryCount = 0
                    logger.info("Bluetooth", "A2DP service connected")
                }
                publish()
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile != BluetoothProfile.A2DP || generation != proxyGeneration) return
                proxyRequestPending = false
                a2dp = null
                logger.warn("Bluetooth", "A2DP service disconnected")
                scheduleProxyReconnect()
                publish()
            }
        }
        val accepted = runCatching { local.getProfileProxy(appContext, listener, BluetoothProfile.A2DP) }
            .onFailure { logger.error("Bluetooth", "getProfileProxy failed", it) }
            .getOrDefault(false)
        if (!accepted && generation == proxyGeneration) {
            proxyRequestPending = false
            logger.warn("Bluetooth", "A2DP proxy request was rejected")
            scheduleProxyReconnect()
            publish()
        }
    }

    private fun closeProxy() {
        proxyGeneration += 1
        proxyRequestPending = false
        val proxy = a2dp
        a2dp = null
        if (proxy != null) runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
    }

    private fun scheduleProxyReconnect() {
        val local = adapter ?: return
        if (!started || !local.isEnabled || proxyRetryCount >= MAX_PROXY_RETRIES) {
            if (proxyRetryCount >= MAX_PROXY_RETRIES) logger.warn("Bluetooth", "A2DP proxy retry limit reached")
            return
        }
        val delay = (PROXY_RETRY_BASE_MS shl proxyRetryCount.coerceAtMost(5)).coerceAtMost(PROXY_RETRY_MAX_MS)
        proxyRetryCount += 1
        mainHandler.removeCallbacks(proxyReconnectRunnable)
        mainHandler.postDelayed(proxyReconnectRunnable, delay)
    }

    private fun registerReceiver() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
        }
        appContext.registerReceiver(receiver, filter)
        registered = true
    }

    private fun buildSnapshot(): BluetoothUiState {
        val local = adapter ?: return BluetoothUiState(lastError = lastError)
        val devices = LinkedHashMap<String, BluetoothDevice>()
        runCatching { local.bondedDevices }.getOrDefault(emptySet()).forEach { if (isAudioDevice(it)) devices[it.address] = it }
        discovered.values.forEach { if (isAudioDevice(it)) devices[it.address] = it }
        runCatching { a2dp?.connectedDevices.orEmpty() }.getOrDefault(emptyList()).forEach { devices[it.address] = it }
        val rows = devices.values.map { device ->
            BluetoothDeviceEntry(
                address = device.address,
                name = device.displayName(),
                bonded = device.bondState == BluetoothDevice.BOND_BONDED,
                bonding = device.bondState == BluetoothDevice.BOND_BONDING,
                linkState = when (a2dpState(device)) {
                    BluetoothProfile.STATE_CONNECTING -> BluetoothLinkState.CONNECTING
                    BluetoothProfile.STATE_CONNECTED -> BluetoothLinkState.CONNECTED
                    BluetoothProfile.STATE_DISCONNECTING -> BluetoothLinkState.DISCONNECTING
                    else -> BluetoothLinkState.DISCONNECTED
                },
                audioStreaming = device.address in playingAddresses
            )
        }.sortedWith(compareByDescending<BluetoothDeviceEntry> { it.linkState == BluetoothLinkState.CONNECTED }.thenBy { it.name.lowercase() })
        return BluetoothUiState(
            adapterMode = when (local.state) {
                BluetoothAdapter.STATE_OFF -> BluetoothAdapterMode.OFF
                BluetoothAdapter.STATE_TURNING_ON -> BluetoothAdapterMode.TURNING_ON
                BluetoothAdapter.STATE_ON -> BluetoothAdapterMode.ON
                BluetoothAdapter.STATE_TURNING_OFF -> BluetoothAdapterMode.TURNING_OFF
                else -> BluetoothAdapterMode.OFF
            },
            isDiscovering = local.isDiscovering,
            devices = rows,
            pendingOperation = pendingOperation?.label,
            lastError = lastError
        )
    }

    private fun a2dpState(device: BluetoothDevice): Int = runCatching {
        val proxy = a2dp ?: return@runCatching BluetoothProfile.STATE_DISCONNECTED
        proxy.getConnectionState(device)
    }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED)

    private fun invokeBoolean(target: Any, methodName: String, device: BluetoothDevice): Boolean? = runCatching {
        findHiddenMethod(target.javaClass, methodName, BluetoothDevice::class.java)
            ?.invoke(target, device) as? Boolean
    }.onFailure { logger.error("Bluetooth", "$methodName failed", it) }.getOrNull()

    private fun invokeHiddenBoolean(target: Any, methodName: String): Boolean? = runCatching {
        findHiddenMethod(target.javaClass, methodName)?.invoke(target) as? Boolean
    }.onFailure { logger.error("Bluetooth", "$methodName failed", it) }.getOrNull()

    private fun findHiddenMethod(type: Class<*>, name: String, vararg parameters: Class<*>): java.lang.reflect.Method? {
        val cacheKey = buildString {
            append(type.name).append('#').append(name)
            parameters.forEach { append(':').append(it.name) }
        }
        if (hiddenMethods.containsKey(cacheKey)) return hiddenMethods[cacheKey]
        runCatching { type.getMethod(name, *parameters) }.getOrNull()?.let {
            hiddenMethods[cacheKey] = it
            return it
        }
        var current: Class<*>? = type
        while (current != null) {
            val candidate = current
            runCatching { candidate.getDeclaredMethod(name, *parameters).apply { isAccessible = true } }
                .getOrNull()?.let {
                    hiddenMethods[cacheKey] = it
                    return it
                }
            current = candidate.superclass
        }
        hiddenMethods[cacheKey] = null
        return null
    }

    private fun isAudioDevice(device: BluetoothDevice): Boolean = runCatching {
        device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO || device.bondState == BluetoothDevice.BOND_BONDED
    }.getOrDefault(device.bondState == BluetoothDevice.BOND_BONDED)

    private fun BluetoothDevice.displayName(): String = name?.takeIf { it.isNotBlank() } ?: address
    private fun maskedAddress(address: String): String = "**:**:**:${address.takeLast(8)}"
    private fun deviceName(address: String): String = runCatching { adapter?.getRemoteDevice(address)?.displayName() }.getOrNull() ?: address

    private fun setPending(address: String, operation: Operation, timeoutMs: Long) {
        clearPending()
        pendingAddress = address
        pendingOperation = operation
        eventLog?.info(
            Sub.BLUETOOTH, Ev.BT_OPERATION,
            "operation" to operation.name,
            "device" to maskedAddress(address),
            "timeoutMs" to timeoutMs
        )
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)
    }

    private fun clearPending() {
        mainHandler.removeCallbacks(timeoutRunnable)
        pendingAddress = null
        pendingOperation = null
        // Posted, not called inline: a chained operation (e.g. auto-connect right
        // after pairing) re-sets a pending op synchronously after this returns, so
        // by the time the check runs it correctly sees the follow-up in flight.
        if (stopWhenIdle) mainHandler.post { maybeStopAfterIdle() }
    }

    /**
     * Coalesces snapshot publishing. [buildSnapshot] performs several binder
     * round-trips (bonded devices, connected devices, per-device connection
     * state), so running it for every broadcast — including one ACTION_FOUND per
     * device discovered — would stall the main thread exactly while the user is
     * turning the wheel on the Bluetooth screen. Debouncing collapses a burst of
     * broadcasts into a single rebuild.
     */
    private fun publish() {
        mainHandler.removeCallbacks(publishRunnable)
        mainHandler.postDelayed(publishRunnable, PUBLISH_DEBOUNCE_MS)
    }

    private val publishRunnable = Runnable {
        val value = buildSnapshot()
        listeners.forEach { it.onBluetoothChanged(value) }
    }

    companion object {
        const val SCAN_BLOCKED_MESSAGE = "Disconnect Bluetooth audio before scanning"
        private const val PUBLISH_DEBOUNCE_MS = 250L
        private const val PAIR_TIMEOUT_MS = 45_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val MAX_PROXY_RETRIES = 7
        private const val PROXY_RETRY_BASE_MS = 1_000L
        private const val PROXY_RETRY_MAX_MS = 30_000L
    }
}
