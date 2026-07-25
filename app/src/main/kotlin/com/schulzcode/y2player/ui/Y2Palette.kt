package com.schulzcode.y2player.ui

/**
 * The twelve colours the renderer draws with, as a swappable set.
 *
 * Separate from [Y2UiTheme] — which keeps the dimensions and type scale — because
 * only the colours change between themes. Held as a value rather than looked up
 * per draw call: [Y2PlayerView] keeps one reference and reads fields off it, so a
 * repaint costs exactly what it did when these were compile-time constants.
 *
 * Written as `0xAARRGGBB.toInt()` rather than the negative-hex form the constants
 * used. That form exists only to satisfy `const val`, which these no longer are,
 * and it is easy to get wrong by hand — `Y2PaletteTest` pins [DARK] against the
 * original literals to prove the transcription was exact.
 */
data class Y2Palette(
    val background: Int,
    val surface: Int,
    val surfaceRaised: Int,
    val focusSurface: Int,
    val activeSurface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val mutedText: Int,
    val accent: Int,
    val divider: Int,
    val warning: Int,
    val success: Int
) {
    companion object {
        /** The original design, unchanged. Still the default. */
        val DARK = Y2Palette(
            background = 0xFF0A0D12.toInt(),
            surface = 0xFF131821.toInt(),
            surfaceRaised = 0xFF1A202B.toInt(),
            focusSurface = 0xFF2B3545.toInt(),
            activeSurface = 0xFF1F2832.toInt(),
            primaryText = 0xFFF4F1EA.toInt(),
            secondaryText = 0xFFB9BFC6.toInt(),
            mutedText = 0xFF8E949F.toInt(),
            accent = 0xFFD6AC53.toInt(),
            divider = 0xFF262D37.toInt(),
            warning = 0xFFE1836A.toInt(),
            success = 0xFF77B597.toInt()
        )

        /**
         * The same design read the other way up.
         *
         * The tonal ladder is inverted and the hue families are kept, so the warm
         * off-white that was the text colour becomes the background and the cool
         * near-black becomes the text. What could *not* simply be swapped is the
         * accent and the two semantic colours: gold, salmon and sage are mid-tones
         * that sit at 9:1, 7:1 and 8:1 against near-black but collapse to under
         * 2:1 against paper. Each is darkened until it matches what the dark
         * palette achieves, measured against both the background and a focused
         * row — the focused row being where the eye actually is, and the harder of
         * the two because its fill is closer to the text.
         *
         * The single accent is kept as one token rather than split into fill and
         * text variants. A darker gold reads as sepia when used as the progress
         * fill, which is legible and looks deliberate; splitting it would mean
         * auditing every one of its uses for a change nobody asked for.
         */
        val LIGHT = Y2Palette(
            background = 0xFFF4F1EA.toInt(),
            surface = 0xFFE9E4DA.toInt(),
            surfaceRaised = 0xFFDFD9CC.toInt(),
            focusSurface = 0xFFCFC7B6.toInt(),
            activeSurface = 0xFFE4DDCE.toInt(),
            primaryText = 0xFF0A0D12.toInt(),
            secondaryText = 0xFF474E58.toInt(),
            mutedText = 0xFF555C66.toInt(),
            accent = 0xFF715018.toInt(),
            divider = 0xFFD5CEC1.toInt(),
            warning = 0xFFA3421F.toInt(),
            success = 0xFF2E6B4C.toInt()
        )

        fun of(lightTheme: Boolean): Y2Palette = if (lightTheme) LIGHT else DARK
    }
}
