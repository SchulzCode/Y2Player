package com.schulzcode.y2player.diagnostics

enum class Sev(val code: String) { DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error") }

enum class Sub(val code: String) {
    APP("app"),
    ACTIVITY("activity"),
    PLAYBACK("playback"),
    BLUETOOTH("bluetooth"),
    INPUT("input"),
    USB("usb"),
    SCANNER("scanner"),
    REDUCER("reducer"),
    DEVICE("device"),
    DIAG("diag"),
    STORAGE("storage")
}

enum class Ev(val code: String) {
    APP_START("app_start"),
    APP_ENVIRONMENT("app_environment"),
    CRASH("crash"),
    ACTIVITY_CREATE("activity_create"),
    ACTIVITY_START("activity_start"),
    ACTIVITY_RESUME("activity_resume"),
    ACTIVITY_PAUSE("activity_pause"),
    ACTIVITY_STOP("activity_stop"),
    ACTIVITY_DESTROY("activity_destroy"),

    BT_ADAPTER_STATE("bt_adapter_state"),
    BT_A2DP_STATE("bt_a2dp_state"),
    BT_PLAYING_STATE("bt_playing_state"),
    BT_OPERATION("bt_operation"),

    VOLUME_MODE("volume_mode"),
    VOLUME_LEVEL("volume_level"),
    PLAYBACK_OPEN("playback_open"),
    PLAYBACK_PREPARED("playback_prepared"),
    PLAYBACK_START("playback_start"),
    PLAYBACK_PAUSE("playback_pause"),
    PLAYBACK_STOP("playback_stop"),
    PLAYBACK_RELEASE("playback_release"),
    PLAYBACK_ERROR("playback_error"),
    PLAYBACK_SOURCE_LOST("playback_source_lost"),
    TRACK_RELEASED("track_released"),

    HAPTIC_SUMMARY("haptic_summary"),
    HAPTIC_FAIL("haptic_fail"),
    HAPTIC_LEVEL("haptic_level"),

    USB_STATE("usb_state"),
    USB_FUNCTIONS("usb_functions"),
    SCAN_START("scan_start"),
    SCAN_COMPLETE("scan_complete"),
    SCAN_PROFILE("scan_profile"),
    SCAN_CANCELLED("scan_cancelled"),
    SCAN_ERROR("scan_error"),
    RESCAN_REQUESTED("rescan_requested"),
    STORAGE_BROADCAST("storage_broadcast"),
    STORAGE_VOLUME_CHANGE("storage_volume_change"),

    LOG_MIRROR_STARTED("log_mirror_started"),
    LOG_MIRROR_STOPPED("log_mirror_stopped"),
    DIAGNOSTICS_EXPORT("diagnostics_export"),

    ACTION("action"),
    DEVICE_PROFILE("device_profile")
}
