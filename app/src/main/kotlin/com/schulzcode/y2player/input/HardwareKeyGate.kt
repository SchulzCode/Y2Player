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

    /** API-19 screen/lock policy shared by Activity and broadcast input paths. */
    @Suppress("DEPRECATION")
    fun isInputAllowed(context: Context, keyCode: Int, source: Source = Source.ACTIVITY): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return isInputAllowed(
            keyCode = keyCode,
            screenOn = powerManager?.isScreenOn == true,
            keyguardLocked = keyguardManager?.inKeyguardRestrictedInputMode() != false,
            source = source
        )
    }

    /**
     * Remote transport commands remain usable while locked. Activity keys and
     * vendor wheel/navigation broadcasts still require an awake, unlocked UI.
     */
    internal fun isInputAllowed(
        keyCode: Int,
        screenOn: Boolean,
        keyguardLocked: Boolean,
        source: Source = Source.ACTIVITY
    ): Boolean = isPowerOrVolume(keyCode) ||
        isRemoteTransport(keyCode, source) ||
        (screenOn && !keyguardLocked)

    private fun isPowerOrVolume(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE -> true
        else -> false
    }

    private fun isRemoteTransport(keyCode: Int, source: Source): Boolean {
        if (source == Source.ACTIVITY) return false
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
    }

    private const val DUPLICATE_WINDOW_MS = 180L
    private const val BOUNCE_WINDOW_MS = 45L
}
