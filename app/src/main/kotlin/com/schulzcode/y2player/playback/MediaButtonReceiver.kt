package com.schulzcode.y2player.playback

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import com.schulzcode.y2player.Y2Application
import com.schulzcode.y2player.diagnostics.DiagnosticLogger
import com.schulzcode.y2player.input.HardwareKeyGate
import kotlin.math.abs

class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON && intent.action != ACTION_Y2_KEY) return
        val event = extractKeyEvent(intent)
        logIncomingEvent(context, intent.action, event)
        event ?: return
        val source = if (intent.action == ACTION_Y2_KEY) HardwareKeyGate.Source.Y2_BROADCAST else HardwareKeyGate.Source.MEDIA_BROADCAST
        val serviceRequest = MediaButtonPolicy.serviceRequest(event.keyCode, source) ?: return
        // A nonzero scan code is the kernel evdev code of a real key on this
        // device. An AVRCP command synthesized by the framework carries none, so
        // this separates the Y2's own play button from a headset stem press even
        // when the platform routes both through ACTION_MEDIA_BUTTON.
        val fromLocalHardware = event.scanCode != 0
        if (!HardwareKeyGate.isInputAllowed(context, event.keyCode, source, fromLocalHardware)) return
        if (!HardwareKeyGate.accept(event, source)) return
        if (!MediaButtonPressGate.shouldDispatch(
                keyCode = event.keyCode,
                action = event.action,
                eventTime = event.eventTime,
                downTime = event.downTime,
                deviceId = event.deviceId,
                repeatCount = event.repeatCount,
                source = source
            )
        ) return
        val serviceIntent = Intent(context, PlaybackService::class.java).apply {
            action = serviceRequest.action
            putExtra(PlaybackService.EXTRA_MEDIA_KEY_CODE, serviceRequest.keyCode)
        }
        context.startService(serviceIntent)
    }

    private fun logIncomingEvent(context: Context, intentAction: String?, event: KeyEvent?) {
        if (!MediaButtonDiagnosticBudget.take()) return
        val logger = (context.applicationContext as? Y2Application)?.container?.logger ?: return
        // scanCode and inputSource are what separate a local key from a headset
        // command; without them a report of "the play button still works with
        // the screen off" cannot be told apart from a stem press.
        logger.info(
            "MediaButtonInput",
            "intentAction=$intentAction keyCode=${event?.keyCode ?: KeyEvent.KEYCODE_UNKNOWN} " +
                "eventAction=${event?.action ?: -1} repeat=${event?.repeatCount ?: -1} " +
                "deviceId=${event?.deviceId ?: -1} scanCode=${event?.scanCode ?: -1} " +
                "inputSource=${event?.source ?: -1} flags=${event?.flags ?: -1} " +
                "downTime=${event?.downTime ?: -1L} eventTime=${event?.eventTime ?: -1L}"
        )
    }

    @Suppress("DEPRECATION")
    private fun extractKeyEvent(intent: Intent): KeyEvent? {
        val parcelableKeys = arrayOf(Intent.EXTRA_KEY_EVENT, "keyevent", "key_event", "KeyEvent", "event")
        parcelableKeys.forEach { key ->
            intent.getParcelableExtra<KeyEvent>(key)?.let { return it }
        }
        val integerKeys = arrayOf("keyCode", "key_code", "keycode")
        integerKeys.forEach { key ->
            if (intent.hasExtra(key)) {
                val code = intent.getIntExtra(key, KeyEvent.KEYCODE_UNKNOWN)
                if (code != KeyEvent.KEYCODE_UNKNOWN) return KeyEvent(KeyEvent.ACTION_UP, code)
            }
        }
        return null
    }

    companion object {
        const val ACTION_Y2_KEY = "com.innioasis.y2.key"

        /** Process-lifetime API-19 media-button ownership; safe to re-assert on playback start. */
        @Suppress("DEPRECATION")
        fun register(context: Context, logger: DiagnosticLogger) {
            val appContext = context.applicationContext
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val component = ComponentName(appContext, MediaButtonReceiver::class.java)
            runCatching { audioManager.registerMediaButtonEventReceiver(component) }
                .onFailure { logger.warn("MediaButton", "registration failed: ${it.message}") }
        }
    }
}

/** Source-scoped key mapping; it contains no screen, timing or playback state. */
internal object MediaButtonPolicy {
    data class ServiceRequest(val action: String, val keyCode: Int)

    fun serviceRequest(keyCode: Int, source: HardwareKeyGate.Source): ServiceRequest? =
        playbackKeyCode(keyCode, source)?.let {
            ServiceRequest(PlaybackService.ACTION_MEDIA_BUTTON, it)
        }

    fun playbackKeyCode(keyCode: Int, source: HardwareKeyGate.Source): Int? {
        if (source == HardwareKeyGate.Source.MEDIA_BROADCAST &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        ) return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

        val mediaKey = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> source == HardwareKeyGate.Source.Y2_BROADCAST
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
        return if (mediaKey) keyCode else null
    }
}

/**
 * Normalizes API-19 vendor delivery to one command for DOWN+UP, DOWN-only or
 * UP-only presses. A DOWN is dispatched immediately so a missing release cannot
 * lose the command; its matching UP is then consumed.
 */
internal object MediaButtonPressGate {
    private data class Edge(
        val keyCode: Int,
        val eventTime: Long,
        val downTime: Long,
        val deviceId: Int,
        val source: HardwareKeyGate.Source
    )

    private var pendingDown: Edge? = null
    private var lastDispatched: Edge? = null
    private var lastDispatchedAction = -1

    @Synchronized
    fun shouldDispatch(
        keyCode: Int,
        action: Int,
        eventTime: Long,
        downTime: Long,
        deviceId: Int,
        repeatCount: Int,
        source: HardwareKeyGate.Source
    ): Boolean {
        val edge = Edge(keyCode, eventTime, downTime, deviceId, source)
        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (repeatCount > 0 || isRepeatedDown(edge)) return false
                pendingDown = edge
                dispatchUnlessBounce(edge, action)
            }
            KeyEvent.ACTION_UP -> {
                val down = pendingDown
                if (down != null && isMatchingRelease(down, edge)) {
                    pendingDown = null
                    false
                } else {
                    if (down?.keyCode == keyCode && down.source == source) pendingDown = null
                    dispatchUnlessBounce(edge, action)
                }
            }
            else -> false
        }
    }

    private fun isRepeatedDown(edge: Edge): Boolean {
        val previous = pendingDown ?: return false
        if (!sameKeySourceDevice(previous, edge)) return false
        val sameDownTime = previous.downTime > 0L && edge.downTime > 0L && previous.downTime == edge.downTime
        return sameDownTime || abs(edge.eventTime - previous.eventTime) <= BOUNCE_WINDOW_MS
    }

    private fun isMatchingRelease(down: Edge, up: Edge): Boolean {
        if (!sameKeySourceDevice(down, up)) return false
        if (down.downTime > 0L && up.downTime > 0L) return down.downTime == up.downTime
        return abs(up.eventTime - down.eventTime) <= RELEASE_WINDOW_MS
    }

    private fun dispatchUnlessBounce(edge: Edge, action: Int): Boolean {
        val previous = lastDispatched
        val bounce = previous != null && lastDispatchedAction == action &&
            sameKeySourceDevice(previous, edge) &&
            abs(edge.eventTime - previous.eventTime) <= BOUNCE_WINDOW_MS
        if (!bounce) {
            lastDispatched = edge
            lastDispatchedAction = action
        }
        return !bounce
    }

    private fun sameKeySourceDevice(first: Edge, second: Edge): Boolean =
        first.keyCode == second.keyCode && first.source == second.source &&
            (first.deviceId == 0 || second.deviceId == 0 || first.deviceId == second.deviceId)

    @Synchronized
    fun reset() {
        pendingDown = null
        lastDispatched = null
        lastDispatchedAction = -1
    }

    private const val BOUNCE_WINDOW_MS = 80L
    private const val RELEASE_WINDOW_MS = 1_000L
}

/** Caps raw input diagnostics per process; progress and ordinary playback are never logged here. */
private object MediaButtonDiagnosticBudget {
    private var remaining = 64

    @Synchronized
    fun take(): Boolean {
        if (remaining <= 0) return false
        remaining -= 1
        return true
    }
}
