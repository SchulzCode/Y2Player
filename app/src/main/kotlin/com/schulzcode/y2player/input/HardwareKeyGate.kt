package com.schulzcode.y2player.input

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import kotlin.math.abs

internal fun isMediaTransportKey(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PLAY,
    KeyEvent.KEYCODE_MEDIA_PAUSE,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_MEDIA_NEXT,
    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    KeyEvent.KEYCODE_MEDIA_STOP,
    KeyEvent.KEYCODE_MEDIA_REWIND,
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
    KeyEvent.KEYCODE_HEADSETHOOK -> true
    else -> false
}

object HardwareKeyGate {
    enum class Source { ACTIVITY, MEDIA_BROADCAST, Y2_BROADCAST }

    private var lastKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var lastAction = -1
    private var lastEventTime = Long.MIN_VALUE
    private var lastSource: Source? = null
    private var lastDownTime = Long.MIN_VALUE
    private var lastDeviceId = 0
    private var lastRepeatCount = 0

    @Suppress("DEPRECATION")
    fun isInputAllowed(
        context: Context,
        keyCode: Int,
        source: Source = Source.ACTIVITY,
        fromLocalKeypad: Boolean = false,
        localKeysWhileScreenOff: Boolean = false
    ): Boolean {
        if (isPowerOrVolume(keyCode) || isRemoteTransport(keyCode, source, fromLocalKeypad)) return true
        if (localKeysWhileScreenOff) return true
        val state = screenState(context)
        return isInputAllowed(
            keyCode = keyCode,
            screenOn = state.screenOn,
            keyguardLocked = state.keyguardLocked,
            source = source,
            fromLocalKeypad = fromLocalKeypad,
            localKeysWhileScreenOff = localKeysWhileScreenOff
        )
    }

    private class ScreenState(val screenOn: Boolean, val keyguardLocked: Boolean)

    @Volatile private var cachedScreenState: ScreenState? = null
    @Volatile private var screenStateReadAt = Long.MIN_VALUE
    @Volatile private var powerManager: PowerManager? = null
    @Volatile private var keyguardManager: KeyguardManager? = null

    @Suppress("DEPRECATION")
    private fun screenState(context: Context): ScreenState {
        val now = SystemClock.uptimeMillis()
        val cached = cachedScreenState
        // The wheel sends two key events per detent, so an uncached read puts a binder
        // call in the input path dozens of times a second.
        if (cached != null && now - screenStateReadAt <= SCREEN_STATE_CACHE_MS) return cached
        val appContext = context.applicationContext
        val power = powerManager
            ?: (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.also { powerManager = it }
        val keyguard = keyguardManager
            ?: (appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.also { keyguardManager = it }
        val state = ScreenState(
            screenOn = runCatching { power?.isScreenOn == true }.getOrDefault(false),
            keyguardLocked = runCatching { keyguard?.inKeyguardRestrictedInputMode() != false }.getOrDefault(true)
        )
        cachedScreenState = state
        screenStateReadAt = now
        return state
    }

    fun isLocalKeypad(deviceId: Int): Boolean = runCatching {
        val device = InputDevice.getDevice(deviceId) ?: return@runCatching false
        device.hasKeys(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP).any { it }
    }.getOrDefault(false)

    @Synchronized
    fun invalidateScreenState() {
        cachedScreenState = null
        screenStateReadAt = Long.MIN_VALUE
    }

    internal fun isInputAllowed(
        keyCode: Int,
        screenOn: Boolean,
        keyguardLocked: Boolean,
        source: Source = Source.ACTIVITY,
        fromLocalKeypad: Boolean = false,
        localKeysWhileScreenOff: Boolean = false
    ): Boolean = isPowerOrVolume(keyCode) ||
        isRemoteTransport(keyCode, source, fromLocalKeypad) ||
        localKeysWhileScreenOff ||
        (screenOn && !keyguardLocked)

    private fun isPowerOrVolume(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE -> true
        else -> false
    }

    private fun isRemoteTransport(keyCode: Int, source: Source, fromLocalKeypad: Boolean): Boolean {
        if (source == Source.ACTIVITY) return false
        // The originating device is the only thing separating a headset press from the
        // keypad: the vendor rebroadcasts AVRCP on its own action, and the Bluetooth
        // stack injects scan codes. Revisions keyed on either also blocked the headset.
        if (fromLocalKeypad) return false
        if (source == Source.MEDIA_BROADCAST &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        ) return true
        return isMediaTransportKey(keyCode)
    }

    @Synchronized
    fun accept(event: KeyEvent, source: Source): Boolean {
        val eventTime = event.eventTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
        return accept(event.keyCode, event.action, eventTime, source, event.downTime, event.deviceId, event.repeatCount)
    }

    @Synchronized
    internal fun accept(
        keyCode: Int,
        action: Int,
        eventTime: Long,
        source: Source,
        downTime: Long = 0L,
        deviceId: Int = 0,
        repeatCount: Int = 0
    ): Boolean {
        val age = if (lastEventTime == Long.MIN_VALUE) Long.MAX_VALUE else abs(eventTime - lastEventTime)
        val wheelEvent = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        val samePhysicalIdentity = eventTime == lastEventTime ||
            (downTime > 0 && lastDownTime > 0 && downTime == lastDownTime &&
                (deviceId == 0 || lastDeviceId == 0 || deviceId == lastDeviceId))
        val crossSourceDuplicate = !wheelEvent && source != lastSource && age <= DUPLICATE_WINDOW_MS &&
            (samePhysicalIdentity || downTime == 0L || lastDownTime == 0L)
        val sameSourceBounce = !wheelEvent && source == lastSource && action == KeyEvent.ACTION_UP &&
            repeatCount == 0 && lastRepeatCount == 0 && age <= BOUNCE_WINDOW_MS
        val duplicate = keyCode == lastKeyCode && action == lastAction && (crossSourceDuplicate || sameSourceBounce)
        if (!duplicate) {
            lastKeyCode = keyCode
            lastAction = action
            lastEventTime = eventTime
            lastSource = source
            lastDownTime = downTime
            lastDeviceId = deviceId
            lastRepeatCount = repeatCount
        }
        return !duplicate
    }

    @Synchronized
    fun reset() {
        lastKeyCode = KeyEvent.KEYCODE_UNKNOWN
        lastAction = -1
        lastEventTime = Long.MIN_VALUE
        lastSource = null
        lastDownTime = Long.MIN_VALUE
        lastDeviceId = 0
        lastRepeatCount = 0
        invalidateScreenState()
    }

    private const val DUPLICATE_WINDOW_MS = 180L
    private const val BOUNCE_WINDOW_MS = 45L
    private const val SCREEN_STATE_CACHE_MS = 250L
}
