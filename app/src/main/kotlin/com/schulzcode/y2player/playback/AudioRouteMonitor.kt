package com.schulzcode.y2player.playback

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager

@Suppress("DEPRECATION")
class AudioRouteMonitor(
    context: Context,
    private val listener: (Event) -> Unit
) {
    data class Event(
        val routes: PrivateRouteSnapshot,
        val becomingNoisy: Boolean,
        val action: String
    )

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            var routes = queryRoutes()
            routes = when (action) {
                Intent.ACTION_HEADSET_PLUG -> when (intent.getIntExtra("state", -1)) {
                    0 -> routes.copy(wired = false)
                    1 -> routes.copy(wired = true)
                    else -> routes
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> when (
                    intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                ) {
                    BluetoothProfile.STATE_CONNECTED -> routes.copy(bluetooth = true)
                    BluetoothProfile.STATE_DISCONNECTED -> routes.copy(bluetooth = false)
                    else -> routes
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> if (
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_OFF
                ) routes.copy(bluetooth = false) else routes
                else -> routes
            }
            latest = routes
            listener(Event(routes, action == AudioManager.ACTION_AUDIO_BECOMING_NOISY, action))
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        appContext.registerReceiver(receiver, filter)
        registered = true
        latest = queryRoutes()
        listener(Event(latest, false, "initial"))
    }

    fun stop() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
    }

    @Volatile private var latest = PrivateRouteSnapshot()

    fun snapshot(): PrivateRouteSnapshot = latest

    private fun queryRoutes(): PrivateRouteSnapshot = PrivateRouteSnapshot(
        wired = runCatching { audioManager.isWiredHeadsetOn }.getOrDefault(false),
        bluetooth = runCatching { audioManager.isBluetoothA2dpOn }.getOrDefault(false)
    )
}
