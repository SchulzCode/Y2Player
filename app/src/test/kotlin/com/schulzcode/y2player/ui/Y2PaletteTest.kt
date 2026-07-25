package com.schulzcode.y2player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class Y2PaletteTest {
    /**
     * The dark theme is the original design and must not have shifted by a single
     * bit while being moved out of `Y2UiTheme`. These are the twelve constants as
     * they were written there, in the negative-hex form `const val` required.
     */
    @Test fun darkPaletteIsBitIdenticalToTheOriginalConstants() {
        assertEquals(-0x00f5f2ee, Y2Palette.DARK.background)
        assertEquals(-0x00ece7df, Y2Palette.DARK.surface)
        assertEquals(-0x00e5dfd5, Y2Palette.DARK.surfaceRaised)
        assertEquals(-0x00d4cabb, Y2Palette.DARK.focusSurface)
        assertEquals(-0x00e0d7ce, Y2Palette.DARK.activeSurface)
        assertEquals(-0x000b0e16, Y2Palette.DARK.primaryText)
        assertEquals(-0x0046403a, Y2Palette.DARK.secondaryText)
        assertEquals(-0x00716b61, Y2Palette.DARK.mutedText)
        assertEquals(-0x002953ad, Y2Palette.DARK.accent)
        assertEquals(-0x00d9d2c9, Y2Palette.DARK.divider)
        assertEquals(-0x001e7c96, Y2Palette.DARK.warning)
        assertEquals(-0x00884a69, Y2Palette.DARK.success)
    }

    @Test fun darkIsTheDefaultAndLightIsOptIn() {
        assertSame(Y2Palette.DARK, Y2Palette.of(lightTheme = false))
        assertSame(Y2Palette.LIGHT, Y2Palette.of(lightTheme = true))
    }

    @Test fun everyColourIsFullyOpaque() {
        listOf(Y2Palette.DARK, Y2Palette.LIGHT).forEach { palette ->
            colours(palette).forEach { (name, colour) ->
                assertEquals("$name must be opaque", 0xFF, (colour ushr 24) and 0xFF)
            }
        }
    }

    /**
     * Every text tone must stay legible on both the page and a focused row.
     *
     * The focused row is the harder case and the one that matters, because its fill
     * is closer to the text than the background is and it is where the user is
     * looking. Floors are set from what the dark palette already achieves rather
     * than from a standard, so the light theme cannot ship visibly worse than the
     * design it mirrors — the light accent and semantic colours had to be darkened
     * substantially to clear this, since gold, salmon and sage fall below 2:1 on
     * paper at their original values.
     */
    @Test fun textTonesAreLegibleOnBothPalettes() {
        listOf("DARK" to Y2Palette.DARK, "LIGHT" to Y2Palette.LIGHT).forEach { (name, p) ->
            textTones(p).forEach { (tone, colour) ->
                val onBackground = contrast(colour, p.background)
                val onFocus = contrast(colour, p.focusSurface)
                assertTrue(
                    "$name $tone on background is $onBackground:1",
                    onBackground >= MIN_ON_BACKGROUND
                )
                assertTrue("$name $tone on a focused row is $onFocus:1", onFocus >= MIN_ON_FOCUS)
            }
        }
    }

    /** A focused row that cannot be told from the page makes the wheel unusable. */
    @Test fun theFocusedRowIsDistinguishableFromTheBackground() {
        listOf("DARK" to Y2Palette.DARK, "LIGHT" to Y2Palette.LIGHT).forEach { (name, p) ->
            assertTrue(
                "$name focus fill is ${contrast(p.focusSurface, p.background)}:1 against the page",
                contrast(p.focusSurface, p.background) >= 1.35
            )
        }
    }

    /** Light is an inversion, not a tint: the page and the ink swap ends. */
    @Test fun lightInvertsTheTonalLadder() {
        assertTrue(luminance(Y2Palette.LIGHT.background) > luminance(Y2Palette.LIGHT.primaryText))
        assertTrue(luminance(Y2Palette.DARK.background) < luminance(Y2Palette.DARK.primaryText))
        assertNotEquals(Y2Palette.DARK, Y2Palette.LIGHT)
    }

    private fun colours(p: Y2Palette) = listOf(
        "background" to p.background, "surface" to p.surface, "surfaceRaised" to p.surfaceRaised,
        "focusSurface" to p.focusSurface, "activeSurface" to p.activeSurface,
        "primaryText" to p.primaryText, "secondaryText" to p.secondaryText,
        "mutedText" to p.mutedText, "accent" to p.accent, "divider" to p.divider,
        "warning" to p.warning, "success" to p.success
    )

    private fun textTones(p: Y2Palette) = listOf(
        "primaryText" to p.primaryText, "secondaryText" to p.secondaryText,
        "mutedText" to p.mutedText, "accent" to p.accent,
        "warning" to p.warning, "success" to p.success
    )

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(colour: Int): Double =
        0.2126 * channel((colour shr 16) and 0xFF) +
            0.7152 * channel((colour shr 8) and 0xFF) +
            0.0722 * channel(colour and 0xFF)

    private fun contrast(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private companion object {
        // Set from what the two palettes actually measure (dark 6.38/4.06, light
        // 5.55/3.72), not from a standard, so lightening any tone in either theme
        // fails here rather than shipping.
        const val MIN_ON_BACKGROUND = 5.0
        const val MIN_ON_FOCUS = 3.5
    }
}
