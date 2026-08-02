#!/usr/bin/env python3
"""Conservative package policy for the Y2Player appliance system image.

The removable set contains standalone user-facing applications and engineering
tools that are not part of Y2Player's boot, setup, storage, input, Bluetooth,
audio, package-management, or settings paths.  Critical packages are listed
separately so both the integrator and independent verifier fail closed if a
future edit removes one accidentally.
"""

PROFILE_NAME = "conservative Y2Player appliance"

# (partition-relative directory, package stem).  Android 4.4 stores APKs and
# their pre-optimized ODEX files directly in /system/app or /system/priv-app.
PRUNED_PACKAGE_GROUPS = {
    "stock media applications": (
        ("/app", "FMRadio"),
        ("/app", "Gallery2"),
        ("/app", "Music"),
        ("/app", "SoundRecorder"),
        ("/app", "VideoEditor"),
        ("/app", "VideoFavorites"),
        ("/app", "Videos"),
        ("/app", "MusicFX"),
    ),
    "standalone utilities": (
        ("/app", "ApplicationGuide"),
        ("/app", "Calculator"),
        ("/app", "DeskClock"),
        ("/app", "FileManager"),
        ("/app", "PrintSpooler"),
        ("/app", "Protips"),
        ("/app", "QuickSearchBox"),
        ("/app", "Todos"),
    ),
    "wallpapers and dreams": (
        ("/app", "BasicDreams"),
        ("/app", "Galaxy4"),
        ("/app", "HoloSpiralWallpaper"),
        ("/app", "LiveWallpapers"),
        ("/app", "LiveWallpapersPicker"),
        ("/app", "MagicSmokeWallpapers"),
        ("/app", "NoiseField"),
        ("/app", "PhaseBeam"),
        ("/app", "VisualizationWallpapers"),
        ("/priv-app", "MtkVideoLiveWallpaper"),
    ),
    "unused phone user interfaces": (
        ("/priv-app", "Contacts"),
        ("/priv-app", "Dialer"),
        ("/priv-app", "Mms"),
    ),
    "engineering and desktop tools": (
        ("/app", "EngineerCode"),
        ("/app", "EngineerMode"),
        ("/app", "EngineerModeSim"),
        ("/app", "MTKAndroidSuiteDaemon"),
        ("/app", "MTKLogger"),
        ("/priv-app", "CDS_INFO"),
    ),
}

# Private libraries used by the removed stock VideoEditor application.
PRUNED_SUPPORT_FILES = (
    "/lib/libvideoeditor_core.so",
    "/lib/libvideoeditor_jni.so",
    "/lib/libvideoeditor_osal.so",
    "/lib/libvideoeditor_videofilters.so",
    "/lib/libvideoeditorplayer.so",
)

# This is deliberately a minimum safety set, not a complete list of retained
# packages.  It covers the paths most likely to break boot, first-run setup,
# wheel/key input, settings, storage/SD scanning, Bluetooth, package handling,
# thermal handling, and Android framework assumptions.
REQUIRED_APKS = (
    "/app/BatteryWarning.apk",
    "/app/Bluetooth.apk",
    "/app/CellConnService.apk",
    "/app/CertInstaller.apk",
    "/app/DocumentsUI.apk",
    "/app/DownloadProviderUi.apk",
    "/app/KeyChain.apk",
    "/app/LatinIME.apk",
    "/app/MTKThermalManager.apk",
    "/app/MtkBt.apk",
    "/app/PackageInstaller.apk",
    "/app/PermissionControl.apk",
    "/app/Provision.apk",
    "/app/TelephonyProvider.apk",
    "/priv-app/DefaultContainerService.apk",
    "/priv-app/DownloadProvider.apk",
    "/priv-app/ExternalStorageProvider.apk",
    "/priv-app/InputDevices.apk",
    "/priv-app/Keyguard.apk",
    "/priv-app/MediaProvider.apk",
    "/priv-app/OneTimeInitializer.apk",
    "/priv-app/Settings.apk",
    "/priv-app/SettingsProvider.apk",
    "/priv-app/Shell.apk",
    "/priv-app/SystemUI.apk",
    "/priv-app/TeleService.apk",
)


def pruned_packages():
    """Return all (directory, stem) entries in stable group order."""
    return tuple(
        package
        for packages in PRUNED_PACKAGE_GROUPS.values()
        for package in packages
    )


def package_files(directory, stem):
    """Return the APK and optional stock ODEX paths for one package."""
    return (f"{directory}/{stem}.apk", f"{directory}/{stem}.odex")


def pruned_package_files():
    return tuple(
        path
        for directory, stem in pruned_packages()
        for path in package_files(directory, stem)
    )


def validate_policy():
    packages = pruned_packages()
    if len(packages) != len(set(packages)):
        raise ValueError("duplicate package in pruning policy")
    if len(REQUIRED_APKS) != len(set(REQUIRED_APKS)):
        raise ValueError("duplicate APK in required-package policy")
    overlap = set(pruned_package_files()) & set(REQUIRED_APKS)
    if overlap:
        raise ValueError("required APK is also pruned: " + ", ".join(sorted(overlap)))
    if "/app/Provision.apk" not in REQUIRED_APKS:
        raise ValueError("Provision.apk must remain required for blank-userdata first boot")


validate_policy()
