package com.schulzcode.y2player.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventJsonTest {
    private fun escaped(value: String): String = StringBuilder().also { EventJson.escape(value, it) }.toString()
    private fun valued(value: Any?): String = StringBuilder().also { EventJson.appendValue(value, it) }.toString()

    @Test fun escapesTheCharactersThatWouldBreakALine() {
        assertEquals("\"plain\"", escaped("plain"))
        assertEquals("\"say \\\"hi\\\"\"", escaped("say \"hi\""))
        assertEquals("\"a\\\\b\"", escaped("a\\b"))
        assertEquals("\"a\\nb\"", escaped("a\nb"))
        assertEquals("\"a\\tb\"", escaped("a\tb"))
    }

    @Test fun escapesControlCharactersAsUnicode() {
        assertEquals("\"\\u0001\"", escaped(""))
    }

    @Test fun truncatesOversizedValuesSoLogsNeverCarryPayloads() {
        val long = "x".repeat(EventJson.MAX_VALUE_CHARS + 500)
        val result = escaped(long)
        assertEquals(EventJson.MAX_VALUE_CHARS + 3, result.length)
        assertTrue(result.endsWith("…\""))
    }

    @Test fun serialisesSupportedPrimitives() {
        assertEquals("null", valued(null))
        assertEquals("true", valued(true))
        assertEquals("false", valued(false))
        assertEquals("42", valued(42))
        assertEquals("42", valued(42L))
        assertEquals("\"INFO\"", valued(Sev.INFO))
        assertEquals("\"text\"", valued("text"))
    }

    @Test fun nonFiniteNumbersBecomeNullRatherThanInvalidJson() {
        assertEquals("null", valued(Double.NaN))
        assertEquals("null", valued(Double.POSITIVE_INFINITY))
        assertEquals("null", valued(Float.NEGATIVE_INFINITY))
    }
}
