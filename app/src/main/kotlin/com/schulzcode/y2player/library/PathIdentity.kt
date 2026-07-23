package com.schulzcode.y2player.library

import java.util.Locale

internal object PathIdentity {
    fun key(path: String): String = path.replace('\\', '/').trimEnd('/').lowercase(Locale.US)
}
