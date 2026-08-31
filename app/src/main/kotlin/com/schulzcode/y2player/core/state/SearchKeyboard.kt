package com.schulzcode.y2player.core.state

internal object SearchKeyboard {
    const val SPACE = "space"
    const val DELETE = "delete"
    const val CLEAR = "clear"
    const val RESULTS = "results"

    val rows: List<List<String>> = listOf(
        "QWERTYUIOP".map(Char::toString),
        "ASDFGHJKL".map(Char::toString),
        "ZXCVBNM".map(Char::toString) + DELETE,
        listOf(SPACE, CLEAR, RESULTS)
    )

    fun key(screen: Screen.Search): String = rows[screen.keyboardRow][screen.keyboardColumn]

    fun moveHorizontal(screen: Screen.Search, delta: Int): Screen.Search {
        val row = rows[screen.keyboardRow]
        val column = ((screen.keyboardColumn + delta) % row.size + row.size) % row.size
        return screen.copy(keyboardColumn = column)
    }

    fun moveVertical(screen: Screen.Search, delta: Int): Screen.Search {
        val nextRow = (screen.keyboardRow + delta).coerceIn(0, rows.lastIndex)
        if (nextRow == screen.keyboardRow) return screen
        val oldSize = rows[screen.keyboardRow].size
        val nextSize = rows[nextRow].size
        val fraction = if (oldSize <= 1) 0f else screen.keyboardColumn.toFloat() / (oldSize - 1)
        return screen.copy(
            keyboardRow = nextRow,
            keyboardColumn = (fraction * (nextSize - 1)).toInt().coerceIn(0, nextSize - 1)
        )
    }

    fun label(key: String): String = when (key) {
        SPACE -> "SPACE"
        DELETE -> "⌫"
        CLEAR -> "CLEAR"
        RESULTS -> "RESULTS"
        else -> key
    }
}
