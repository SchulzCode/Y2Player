package com.schulzcode.y2player.ui

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
