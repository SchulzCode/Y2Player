package com.schulzcode.y2player.ui

/**
 * Compact, allocation-free visual tokens for the 480 x 360 landscape mdpi Y2 display.
 * The layout code derives everything from the runtime width/height, so a portrait
 * panel still renders correctly; these targets are only pre-layout fallbacks.
 */
object Y2UiTheme {
    const val TARGET_WIDTH_PX = 480
    const val TARGET_HEIGHT_PX = 360

    const val BACKGROUND = -0x00f5f2ee // #FF0A0D12
    const val SURFACE = -0x00ece7df // #FF131821
    const val SURFACE_RAISED = -0x00e5dfd5 // #FF1A202B
    const val FOCUS_SURFACE = -0x00d4cabb // #FF2B3545
    const val ACTIVE_SURFACE = -0x00e0d7ce // #FF1F2832
    const val PRIMARY_TEXT = -0x000b0e16 // #FFF4F1EA
    const val SECONDARY_TEXT = -0x0046403a // #FFB9BFC6
    const val MUTED_TEXT = -0x00716b61 // #FF8E949F
    const val ACCENT = -0x002953ad // #FFD6AC53
    const val DIVIDER = -0x00d9d2c9 // #FF262D37
    const val WARNING = -0x001e7c96 // #FFE1836A
    const val SUCCESS = -0x00884a69 // #FF77B597

    // Authored for the real 480 x 360 mdpi panel. Four comfortably readable rows
    // are preferable to six cramped ones when the click wheel scrolls predictably.
    const val HEADER_HEIGHT_DP = 42f
    const val COMPACT_FOOTER_HEIGHT_DP = 30f
    const val MINI_PLAYER_HEIGHT_DP = 58f
    const val ROW_HEIGHT_DP = 58f
    const val DETAIL_HEADER_HEIGHT_DP = 76f
    const val EDGE_DP = 12f
    const val ROW_RADIUS_DP = 10f
    const val PANEL_RADIUS_DP = 10f
    const val ICON_SIZE_DP = 24f
    const val PRIMARY_ICON_SIZE_DP = 30f
    const val MINI_ART_SIZE_DP = 42f
    const val FOCUS_OUTLINE_DP = 1.0f
    const val FOCUS_BAR_WIDTH_DP = 2f
    const val ROW_FOCUS_INSET_DP = EDGE_DP
    const val ROW_SCROLLBAR_GAP_DP = 6f
    const val SCROLLBAR_WIDTH_DP = 2f
    const val SCROLLBAR_END_INSET_DP = 4f
    const val PROGRESS_HEIGHT_DP = 5f
    const val HOME_PROGRESS_BOTTOM_INSET_DP = 32f
    const val HOME_PROGRESS_TIME_BOTTOM_INSET_DP = 8f
    const val SCREEN_TITLE_SP = 19f
    const val SECTION_TITLE_SP = 18f
    const val BODY_SP = 14f
    const val MINI_TITLE_SP = 15f
    const val ROW_TITLE_SP = 17f
    const val ROW_SUBTITLE_SP = 13.5f
    const val NAV_LABEL_SP = 12f
    const val NOW_TITLE_SP = 22f
    const val NOW_ARTIST_SP = 15.5f
    const val NOW_ALBUM_SP = 13.5f
    const val META_SP = 12f
    const val BADGE_SP = 11.5f
}
