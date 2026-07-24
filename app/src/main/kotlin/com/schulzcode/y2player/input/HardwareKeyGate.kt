package com.schulzcode.y2player.input

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import kotlin.math.abs

/** Prevents one physical key from being handled by both the Activity and a vendor/media broadcast. */
object HardwareKeyGate {
    enum class Source { ACTIVITY, MEDIA_BROADCAST, Y2_BROADCAST }

    private var lastKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var lastAction = -1
    private var lastEventTime = Long.MIN_VALUE
    private var lastSource: Source? = null
    private var lastDownTime = Long.MIN_VALUE
    private var lastDeviceId = 0
    private var lastRepeatCount = 0

    /**
     * API-19 screen/lock policy shared by Activity and broadcast input paths.
     *
     * `isScreenOn` and `inKeyguardRestrictedInputMode` are both binder calls, and
     * the wheel arrives here as DPAD_UP/DOWN — two key events per detent, dozens
     * of detents per second during a spin. Asking the system server four times
     * per detent put IPC directly in the input-latency path on a Cortex-A7, so
     * the answer is cached for [SCREEN_STATE_CACHE_MS]: far shorter than any
     * screen-off or unlock transition a user can produce, long enough that a
     * whole spin costs a handful of calls instead of hundreds. The service
     * handles are held rather than looked up per call for the same reason.
     */
    @Suppress("DEPRECATION")
    fun isInputAllowed(
        context: Context,
        keyCode: Int,
        source: Source = Source.ACTIVITY,
        fromLocalHardware: Boolean = false
    ): Boolean {
        // Cheap, purely static answers first: these never depend on screen state,
        // so the common wheel and transport cases can skip the cache entirely.
        if (isPowerOrVolume(keyCode) || isRemoteTransport(keyCode, source, fromLocalHardware)) return true
        val state = screenState(context)
        return isInputAllowed(
            keyCode = keyCode,
            screenOn = state.screenOn,
            keyguardLocked = state.keyguardLocked,
            source = source,
            fromLocalHardware = fromLocalHardware
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
        if (cached != null && now - screenStateReadAt <= SCREEN_STATE_CACHE_MS) return cached
        val appContext = context.applicationContext
        val power = powerManager
            ?: (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.also { powerManager = it }
        val keyguard = keyguardManager
            ?: (appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.also { keyguardManager = it }
        val state = ScreenState(
            screenOn = runCatching { power?.isScreenOn == true }.getOrDefault(false),
            // Absent service means "assume locked": the conservative answer, and
            // the same one the previous `!= false` comparison produced.
            keyguardLocked = runCatching { keyguard?.inKeyguardRestrictedInputMode() != false }.getOrDefault(true)
        )
        cachedScreenState = state
        screenStateReadAt = now
        return state
    }

    /** Drops the cached screen/lock answer; used by tests and on explicit resets. */
    @Synchronized
    fun invalidateScreenState() {
        cachedScreenState = null
        screenStateReadAt = Long.MIN_VALUE
    }

    /**
     * Remote transport commands remain usable while locked. Activity keys and
     * vendor wheel/navigation broadcasts still require an awake, unlocked UI.
     */
    internal fun isInputAllowed(
        keyCode: Int,
        screenOn: Boolean,
        keyguardLocked: Boolean,
        source: Source = Source.ACTIVITY,
        fromLocalHardware: Boolean = false
    ): Boolean = isPowerOrVolume(keyCode) ||
        isRemoteTransport(keyCode, source, fromLocalHardware) ||
        (screenOn && !keyguardLocked)

    private fun isPowerOrVolume(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE -> true
        else -> false
    }

    /**
     * Whether this command came from something other than the device's own keys.
     *
     * Only a genuine remote — a Bluetooth headset's transport control — may act
     * while the screen is off. The player's own play button must not, or it
     * fires from a pocket.
     *
     * Two things arrive as `KEYCODE_MEDIA_PLAY_PAUSE` with the screen off: an
     * AirPods stem press, and the Y2's own play button. They are separated by
     * where they arrive from, not by what they are:
     *
     * - [Source.Y2_BROADCAST] is `com.innioasis.y2.key`, the vendor broadcast
     *   carrying this device's physical keys. It is never remote, whatever
     *   keycode it holds. Treating it as remote is what let the local play
     *   button work while the screen was off — the other vendor keys were
     *   unaffected because DPAD_LEFT/RIGHT already fall through to the
     *   screen-on test below.
     * - [Source.MEDIA_BROADCAST] is normally a real remote, but the platform
     *   also re-dispatches local media keys through it on some builds.
     *   [fromLocalHardware] carries the evdev scan code's presence for that
     *   case: a synthesized AVRCP event has no scan code, a physical key does.
     */
    private fun isRemoteTransport(keyCode: Int, source: Source, fromLocalHardware: Boolean): Boolean {
        if (source == Source.ACTIVITY) return false
        if (source == Source.Y2_BROADCAST) return false
        if (fromLocalHardware) return false
        if (source == Source.MEDIA_BROADCAST &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        ) return true
        return when (keyCode) {
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
    }

    @Synchronized
    fun accept(event: KeyEvent, source: Source): Boolean {
        val eventTime = event.eventTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
        return accept(event.keyCode, event.action, eventTime, source, event.downTime, event.deviceId, event.repeatCount)
    }

    /** Primitive overload keeps the timing rule independently testable on the host JVM. */
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
