package com.schulzcode.y2player.playback

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import com.schulzcode.y2player.BuildConfig
import com.schulzcode.y2player.Y2Application
import com.schulzcode.y2player.diagnostics.DiagnosticLogger
import com.schulzcode.y2player.input.HardwareKeyGate
import com.schulzcode.y2player.input.InputProbe
import com.schulzcode.y2player.input.isMediaTransportKey
import kotlin.math.abs

class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON && intent.action != ACTION_Y2_KEY) return
        val event = extractKeyEvent(intent)
        logIncomingEvent(context, intent.action, event)
        event ?: return
        val source = if (intent.action == ACTION_Y2_KEY) HardwareKeyGate.Source.Y2_BROADCAST else HardwareKeyGate.Source.MEDIA_BROADCAST
        val fromLocalKeypad = HardwareKeyGate.isLocalKeypad(event.deviceId)
        val serviceRequest = MediaButtonPolicy.serviceRequest(
            keyCode = event.keyCode,
            source = source,
            scanCode = event.scanCode,
            fromLocalKeypad = fromLocalKeypad
        ) ?: return logRejected(context, "unmapped_key", source, event)
        // The vendor rebroadcasts the same physical keypad stream delivered to the foreground
        // activity. Letting that fallback path act while the display is usable turns the first
        // edge of a hold into Previous/Next before the activity can classify it as a seek.
        if (HardwareKeyGate.shouldDeferLocalBroadcastToActivity(context, source, fromLocalKeypad)) {
            return logRejected(context, "activity_owns_local_key", source, event)
        }
        val localKeysWhileScreenOff = (context.applicationContext as? Y2Application)
            ?.container?.preferences?.snapshot()?.localKeysWhileScreenOff ?: false
        if (!serviceRequest.isVolumeAdjustment && !HardwareKeyGate.isInputAllowed(
                context, event.keyCode, source, fromLocalKeypad, localKeysWhileScreenOff
            )
        ) {
            return logRejected(context, "screen_gate", source, event)
        }
        if (!HardwareKeyGate.accept(event, source)) {
            return logRejected(context, "cross_source_duplicate", source, event)
        }
        val pressDecision = MediaButtonPressGate.dispatchDecision(
                keyCode = event.keyCode,
                action = event.action,
                eventTime = event.eventTime,
                downTime = event.downTime,
                deviceId = event.deviceId,
                repeatCount = event.repeatCount,
                source = source,
                allowRepeats = serviceRequest.isVolumeAdjustment
            )
        if (pressDecision == MediaButtonPressGate.Decision.REJECT) {
            return logRejected(context, "press_gate", source, event)
        }
        if (BuildConfig.DEBUG) {
            InputProbe.log(
                "BROADCAST",
                event,
                "intent=${intent.action} mapped=${serviceRequest.keyCode} volume=${serviceRequest.volumeDirection}"
            )
        }
        val serviceIntent = Intent(context, PlaybackService::class.java).apply {
            action = serviceRequest.action
            putExtra(PlaybackService.EXTRA_MEDIA_KEY_CODE, serviceRequest.keyCode)
            serviceRequest.volumeDirection?.let {
                putExtra(PlaybackService.EXTRA_VOLUME_DIRECTION, it)
                putExtra(PlaybackService.EXTRA_VOLUME_KEY_CODE, event.keyCode)
                putExtra(PlaybackService.EXTRA_VOLUME_KEY_ACTION, event.action)
                putExtra(PlaybackService.EXTRA_VOLUME_REPEAT_COUNT, event.repeatCount)
                putExtra(PlaybackService.EXTRA_VOLUME_DOWN_TIME, event.downTime)
                putExtra(PlaybackService.EXTRA_VOLUME_EVENT_TIME, event.eventTime)
                putExtra(PlaybackService.EXTRA_VOLUME_DEVICE_ID, event.deviceId)
                putExtra(
                    PlaybackService.EXTRA_VOLUME_ONE_SHOT,
                    pressDecision == MediaButtonPressGate.Decision.DISPATCH_ONE_SHOT
                )
            }
        }
        context.startService(serviceIntent)
    }

    private fun logRejected(
        context: Context,
        reason: String,
        source: HardwareKeyGate.Source,
        event: KeyEvent
    ) {
        if (!MediaButtonDiagnosticBudget.take()) return
        val logger = (context.applicationContext as? Y2Application)?.container?.logger ?: return
        logger.info(
            "MediaButtonInput",
            "rejected=$reason source=$source keyCode=${event.keyCode} " +
                "eventAction=${event.action} scanCode=${event.scanCode} " +
                "inputSource=${event.source} deviceId=${event.deviceId} " +
                "localKeypad=${HardwareKeyGate.isLocalKeypad(event.deviceId)}"
        )
    }

    private fun logIncomingEvent(context: Context, intentAction: String?, event: KeyEvent?) {
        if (!MediaButtonDiagnosticBudget.take()) return
        val logger = (context.applicationContext as? Y2Application)?.container?.logger ?: return
        logger.info(
            "MediaButtonInput",
            "intentAction=$intentAction keyCode=${event?.keyCode ?: KeyEvent.KEYCODE_UNKNOWN} " +
                "eventAction=${event?.action ?: -1} repeat=${event?.repeatCount ?: -1} " +
                "deviceId=${event?.deviceId ?: -1} scanCode=${event?.scanCode ?: -1} " +
                "inputSource=${event?.source ?: -1} flags=${event?.flags ?: -1} " +
                "localKeypad=${event?.let { HardwareKeyGate.isLocalKeypad(it.deviceId) }} " +
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

internal object MediaButtonPolicy {
    data class ServiceRequest(
        val action: String,
        val keyCode: Int,
        val volumeDirection: Int? = null
    ) {
        val isVolumeAdjustment: Boolean get() = volumeDirection != null
    }

    fun serviceRequest(
        keyCode: Int,
        source: HardwareKeyGate.Source,
        scanCode: Int = 0,
        fromLocalKeypad: Boolean = false
    ): ServiceRequest? {
        LocalVolumeKeyPolicy.direction(keyCode, scanCode, fromLocalKeypad)?.let { direction ->
            return ServiceRequest(PlaybackService.ACTION_ADJUST_VOLUME, keyCode, direction)
        }
        return playbackKeyCode(keyCode, source)?.let {
            ServiceRequest(PlaybackService.ACTION_MEDIA_BUTTON, it)
        }
    }

    fun playbackKeyCode(keyCode: Int, source: HardwareKeyGate.Source): Int? {
        if (source == HardwareKeyGate.Source.MEDIA_BROADCAST &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        ) return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

        val mediaKey = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            source == HardwareKeyGate.Source.Y2_BROADCAST
        } else {
            isMediaTransportKey(keyCode)
        }
        return if (mediaKey) keyCode else null
    }
}

/**
 * The firmware maps only mtk-kpd scan codes 115/114 away from Android's
 * framework-owned volume keys. Both the originating keypad and the unchanged
 * Linux scan code are required here so real headset/Bluetooth transport keys
 * retain their normal fast-forward/rewind behavior.
 */
internal object LocalVolumeKeyPolicy {
    fun direction(keyCode: Int, scanCode: Int, fromLocalKeypad: Boolean): Int? {
        if (!fromLocalKeypad) return null
        return when {
            keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD && scanCode == SCAN_VOLUME_UP -> 1
            keyCode == KeyEvent.KEYCODE_MEDIA_REWIND && scanCode == SCAN_VOLUME_DOWN -> -1
            else -> null
        }
    }

    private const val SCAN_VOLUME_UP = 115
    private const val SCAN_VOLUME_DOWN = 114
}

// Vendor delivery is not guaranteed to be a DOWN/UP pair. A DOWN dispatches
// immediately so a missing release cannot lose the press. A short UP is
// consumed; after a repeat it is forwarded only to cancel the held-key loop.
internal object MediaButtonPressGate {
    enum class Decision { DISPATCH, DISPATCH_ONE_SHOT, RELEASE, REJECT }

    private data class Edge(
        val keyCode: Int,
        val eventTime: Long,
        val downTime: Long,
        val deviceId: Int,
        val source: HardwareKeyGate.Source
    )

    private var pendingDown: Edge? = null
    private var pendingRepeatSeen = false
    private var lastDispatched: Edge? = null
    private var lastDispatchedAction = -1

    @Synchronized
    fun dispatchDecision(
        keyCode: Int,
        action: Int,
        eventTime: Long,
        downTime: Long,
        deviceId: Int,
        repeatCount: Int,
        source: HardwareKeyGate.Source,
        allowRepeats: Boolean = false
    ): Decision {
        val edge = Edge(keyCode, eventTime, downTime, deviceId, source)
        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (repeatCount > 0) {
                    if (!allowRepeats) return Decision.REJECT
                    pendingDown = edge
                    pendingRepeatSeen = true
                    lastDispatched = edge
                    lastDispatchedAction = action
                    return Decision.DISPATCH
                }
                if (isRepeatedDown(edge)) return Decision.REJECT
                pendingDown = edge
                pendingRepeatSeen = false
                if (dispatchUnlessBounce(edge, action)) Decision.DISPATCH else Decision.REJECT
            }
            KeyEvent.ACTION_UP -> {
                val down = pendingDown
                if (down != null && isMatchingRelease(down, edge)) {
                    pendingDown = null
                    val repeated = pendingRepeatSeen
                    pendingRepeatSeen = false
                    if (allowRepeats && repeated) Decision.RELEASE else Decision.REJECT
                } else {
                    if (down?.keyCode == keyCode && down.source == source) {
                        pendingDown = null
                        pendingRepeatSeen = false
                    }
                    if (dispatchUnlessBounce(edge, action)) Decision.DISPATCH_ONE_SHOT else Decision.REJECT
                }
            }
            else -> Decision.REJECT
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
        pendingRepeatSeen = false
        lastDispatched = null
        lastDispatchedAction = -1
    }

    private const val BOUNCE_WINDOW_MS = 80L
    private const val RELEASE_WINDOW_MS = 1_000L
}

private object MediaButtonDiagnosticBudget {
    private var remaining = 512

    @Synchronized
    fun take(): Boolean {
        if (remaining <= 0) return false
        remaining -= 1
        return true
    }
}
