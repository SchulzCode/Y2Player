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

    fun moveLinear(screen: Screen.Search, delta: Int, wrap: Boolean): Screen.Search {
        val current = rows.take(screen.keyboardRow).sumOf(List<String>::size) + screen.keyboardColumn
        val total = rows.sumOf(List<String>::size)
        val next = if (wrap) {
            ((current + delta) % total + total) % total
        } else {
            (current + delta).coerceIn(0, total - 1)
        }
        var remaining = next
        rows.forEachIndexed { rowIndex, row ->
            if (remaining < row.size) return screen.copy(keyboardRow = rowIndex, keyboardColumn = remaining)
            remaining -= row.size
        }
        return screen
    }

    fun label(key: String): String = when (key) {
        SPACE -> "SPACE"
        DELETE -> "⌫"
        CLEAR -> "CLEAR"
        RESULTS -> "RESULTS"
        else -> key
    }
}
