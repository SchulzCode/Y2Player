package com.schulzcode.y2player.diagnostics

/**
 * Closed vocabulary for the structured event log.
 *
 * Event names are enums rather than free strings so the schema stays stable and
 * greppable: a field report can be analysed with fixed queries instead of
 * guessing at whatever string a call site happened to use.
 *
 * The vocabulary contains only events that are actually emitted somewhere: an
 * analyst grepping for a documented event must be able to find it in a real log.
 * Add an entry the moment a call site exists, not before.
 */

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
    // app / lifecycle
    APP_START("app_start"),
    APP_ENVIRONMENT("app_environment"),
    CRASH("crash"),
    ACTIVITY_CREATE("activity_create"),
    ACTIVITY_START("activity_start"),
    ACTIVITY_RESUME("activity_resume"),
    ACTIVITY_PAUSE("activity_pause"),
    ACTIVITY_STOP("activity_stop"),
    ACTIVITY_DESTROY("activity_destroy"),

    // bluetooth
    BT_ADAPTER_STATE("bt_adapter_state"),
    BT_A2DP_STATE("bt_a2dp_state"),
    BT_PLAYING_STATE("bt_playing_state"),
    BT_OPERATION("bt_operation"),

    // playback
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

    // input
    HAPTIC_SUMMARY("haptic_summary"),
    HAPTIC_FAIL("haptic_fail"),
    HAPTIC_LEVEL("haptic_level"),

    // storage / usb / scanner
    USB_STATE("usb_state"),
    USB_FUNCTIONS("usb_functions"),
    SCAN_START("scan_start"),
    SCAN_COMPLETE("scan_complete"),
    SCAN_CANCELLED("scan_cancelled"),
    SCAN_ERROR("scan_error"),
    RESCAN_REQUESTED("rescan_requested"),
    STORAGE_BROADCAST("storage_broadcast"),
    STORAGE_VOLUME_CHANGE("storage_volume_change"),

    // diagnostics plumbing
    LOG_MIRROR_STARTED("log_mirror_started"),
    LOG_MIRROR_STOPPED("log_mirror_stopped"),
    DIAGNOSTICS_EXPORT("diagnostics_export"),

    // state machine / device identity
    ACTION("action"),
    DEVICE_PROFILE("device_profile")
}
