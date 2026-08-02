package com.schulzcode.y2player.input

import android.util.Log
import android.view.KeyEvent
import com.schulzcode.y2player.BuildConfig

// Delete this and its two call sites once Q2 is recorded in
// docs/MEASURED_RESULTS_2026-07-31.md.
internal object InputProbe {
    private const val TAG = "Y2Input"

    fun log(source: String, event: KeyEvent, extra: String = "") {
        if (!BuildConfig.DEBUG) return
        Log.i(
            TAG,
            "$source key=${event.keyCode} act=${event.action} rpt=${event.repeatCount}" +
                " lp=${event.isLongPress} dev=${event.deviceId}" +
                " local=${HardwareKeyGate.isLocalKeypad(event.deviceId)}" +
                " down=${event.downTime} evt=${event.eventTime}" +
                " held=${(event.eventTime - event.downTime).coerceAtLeast(0)}" +
                if (extra.isEmpty()) "" else " $extra"
        )
    }
}
