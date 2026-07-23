# Y2Player

**Y2Player is an offline music player and HOME launcher built specifically for the Innioasis Y2.** It turns the Android 4.4 device into a focused, click-wheel-driven music player with local library browsing, a persistent playback queue, album artwork, Bluetooth audio, and a compact interface designed for the Y2's 480 × 360 landscape display.

Y2Player is for Y2 owners, firmware modders, and contributors who want a lightweight music-first replacement for the stock launcher. It works without an internet connection and does not include streaming, search, video, or cloud services.

## Highlights

- Browse local music by **Songs, Albums, Artists, Folders, Playlists, Favorites, and Recently Played**.
- Play individual tracks or collections, shuffle the library, and manage a persistent queue with Play Next, reordering, and removal.
- Use repeat-one/repeat-all, configurable seeking, playback resume, gapless transitions, crossfade, and pause/resume fades.
- Set a sleep timer for 15, 30, or 60 minutes, or stop at the end of the current track, album, or queue.
- Navigate entirely with the Y2 click wheel and hardware buttons
- View embedded album artwork, metadata, progress, output status, and playback controls on Home and Now Playing.
- Scan internal storage and removable SD cards, including M3U/M3U8 playlist import and export.
- Pair and manage Bluetooth A2DP audio devices.

## Music library and playback

The library is built from audio files on the Y2's internal music storage and removable SD card. Metadata is stored locally in SQLite, and incremental rescans update changed files without loading the complete library into the UI at once.

Library views include:

- all songs, albums, artists, and folders;
- Favorites and Recently Played smart lists;
- user-created playlists;
- M3U and M3U8 playlist import/export;
- configurable title, artist, album, recently-added, or file-modified sorting where applicable.

Playback includes a persistent queue, shuffle, repeat one/all, previous/next behavior, short and held seeking, saved resume position, audio-focus handling, and safe pause when storage or a protected audio route disappears. Gapless playback preloads the next track; configurable crossfade takes priority when enabled.

## Controls

| Control | Library and menus | Now Playing |
| --- | --- | --- |
| Wheel counterclockwise / clockwise | Move focus up / down | Adjust volume |
| Center | Open or confirm | Play / pause |
| Hold Center | Open contextual track options | Open playback options |
| Left / Right | Back / open contextual destination | Previous / next track |
| Hold Left / Right | — | Seek backward / forward repeatedly |
| Back button | Return to the previous screen | Return to the previous screen |
| Play button | Play / pause | Play / pause |
| Volume Up / Down | Adjust the configured system or in-app volume | Adjust the configured system or in-app volume |

### Screen-off and lock behavior

When the display is off or Android's keyguard is locked, Y2Player blocks click-wheel, navigation, media, and headset-button input. **Only the system Power and Volume controls remain active.** Playback itself continues with the display off; the input block prevents accidental pocket presses.

## Interface and artwork

The main interface is a low-overhead custom-drawn view sized around the Y2's landscape panel and physical focus navigation. Home combines the library menu with a compact playback pane. Now Playing shows embedded artwork when available, a fallback graphic otherwise, title/artist/album information, progress and time, playback state, output-route warnings, and cautious DAC information.

### Screenshots

| Library | Now Playing | Empty library |
| --- | --- | --- |
| ![Y2Player library](docs/screenshots/y2-ui-after-main.png) | ![Y2Player Now Playing](docs/screenshots/y2-ui-after-now-playing.png) | ![Y2Player empty library](docs/screenshots/y2-ui-after-empty.png) |

## Bluetooth audio

Y2Player integrates with Android 4.4's Bluetooth A2DP and legacy remote-control APIs. The in-app Bluetooth screen can enable Bluetooth, discover audio devices, pair, connect/disconnect, forget bonds, and refresh the A2DP service.

Transport buttons and AVRCP metadata are supported through the API 19 media-button and remote-control interfaces. Losing an active Bluetooth route pauses playback rather than leaking audio to the speaker; reconnecting requires an explicit Play command.

## Audio quality and sound effects

Two audio-quality modes are available:

- **Balanced** uses the normal Android audio path and can enable supported app-session effects.
- **Direct DAC** makes a best-effort request for the Y2 firmware's MediaTek Hi-Fi route and bypasses app-side equalizer, bass boost, loudness, crossfade, and pause/resume fades while active. Stored settings are preserved for returning to Balanced mode.

Equalizer presets, custom equalizer bands, bass boost, and loudness appear only when Android reports compatible effects for the playback session. Availability and behavior therefore depend on the installed firmware.

Direct DAC mode is not a bit-perfect or native-DSD guarantee. Final sample format, resampling, clocks, and CS43131 programming remain controlled by AudioFlinger, the MediaTek audio HAL, and the kernel driver. Y2Player reports limitations instead of claiming capabilities the app cannot verify.

## Storage, diagnostics, and recovery

- Scans common Y2 internal-storage and removable-SD mount points and retains metadata when a volume is temporarily unavailable.
- Reacts safely to Android mount, unmount, and media-scanner events. USB/storage status is diagnostic and read-only; Y2Player does not switch USB modes.
- Provides manual library rescanning, bounded structured logs, local diagnostic export, and muted prepare/play/seek format probes.
- Includes PowerShell helpers for collecting and watching device diagnostics over ADB.
- Enters Safe Mode after repeated incomplete launcher starts, or on request. Safe Mode suppresses automatic scanning, Bluetooth management, and session restoration so the UI can recover.

## Privacy and offline operation

Y2Player has no `INTERNET` permission. Music, metadata, preferences, playlists, playback state, artwork, and diagnostics stay on the device. There is no account, analytics SDK, telemetry service, cloud library, or automatic upload. Exported diagnostics are files created locally for the user to inspect or copy.

## Compatibility

| Item | Support |
| --- | --- |
| Primary device | Innioasis Y2, MediaTek MT6582 family |
| Display target | 480 × 360 landscape |
| Android | Android 4.4.2 / API 19 (`minSdk` and `targetSdk` 19) |
| Navigation | Y2 click wheel and physical buttons |
| Storage | Internal music storage and removable SD card |
| Network | Not required; the app has no internet permission |

The scanner recognizes common extensions including MP3, FLAC, WAV, Ogg/Opus, M4A/AAC, APE, WMA, AMR, WavPack, AIFF, AC-3, MKA, DSF, and DFF. Actual decoding support comes from the stock Android `MediaPlayer` stack and can be narrower than the scanner's file list. The built-in format probe is the authoritative test for a particular firmware/device combination.

## Build the app

### Requirements

- JDK 17 or newer;
- Android SDK Platform 36 and Android Build Tools;
- an SDK path in `local.properties` (see `local.properties.example`);
- PowerShell on Windows for the verified release and firmware helper scripts.

Build, test, lint, and package a debug APK:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

On macOS or Linux, the equivalent app-only command is:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

### Build a signed release APK

Copy `keystore.properties.example` to `keystore.properties`, create and back up a release keystore, and replace every example value with your own path and credentials. Android 4.4 requires the v1/JAR signature produced by the configured release build.

Then run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\build-release-apk.ps1
```

The verified APK and build report are staged under `dist\firmware\`. Signing material and generated release artifacts are intentionally ignored by Git.

## Install on a device

### Flash the modified system image with SP Flash Tool

> [!WARNING]
> Flashing modified firmware can permanently damage or brick your device if the wrong file, partition, or flashing mode is selected. Proceed entirely at your own risk. I am not responsible for damaged devices, lost data, failed flashes, or any other consequences.
>
> **Read this entire guide carefully before starting. Do not continue until you understand every step. Work slowly, verify every selection, and never guess.**

#### Requirements

- an **Innioasis Y2** with the matching stock firmware version;
- the modified `system.img` from the Y2Player GitHub Release;
- the matching original `MT6582_Android_scatter.txt` file for your Y2 firmware;
- SP Flash Tool;
- the required MediaTek USB drivers;
- a verified copy of the complete original firmware so the device can be restored if necessary.

#### Before flashing

1. Back up any important files from the Y2.
2. Keep the complete original firmware and matching scatter file in a safe location.
3. Confirm that the downloaded release is intended for the Innioasis Y2 and your firmware base.
4. Verify the SHA-256 checksum supplied with the release.
5. Fully charge the Y2 before beginning.
6. Read all remaining steps before opening SP Flash Tool.

If `userdata` was recently reset or flashed, first boot the original firmware and complete the initial Android setup. After setup is complete, shut the device down and flash only the modified `system.img`. Skipping the original setup may leave required device settings uninitialized.

#### Flashing steps

1. Extract the Y2Player release ZIP.
2. Open SP Flash Tool.
3. Load the matching original `MT6582_Android_scatter.txt` file.
4. Select **Download Only** from the flashing-mode menu.
5. Remove every partition checkmark.
6. Enable **only** the `ANDROID` partition.
7. In the `ANDROID` row, select the modified Y2Player `system.img`.
8. Carefully verify the configuration before continuing:
   - mode is **Download Only**;
   - only `ANDROID` is checked;
   - `ANDROID` points to the modified Y2Player `system.img`;
   - no other partition is selected.
9. Turn the Y2 completely off.
10. Click **Download** in SP Flash Tool.
11. Connect the powered-off Y2 to the computer with a reliable USB data cable.
12. Wait until SP Flash Tool displays the green success indicator.
13. Disconnect the USB cable and start the Y2.
14. Allow extra time for the first boot.

#### Never select these partitions

Do not flash or format any unrelated partition, including:

```text
PRELOADER
MBR
EBR1
EBR2
UBOOT
BOOTIMG
RECOVERY
SEC_RO
LOGO
CACHE
USRDATA
NVRAM
```

Do not use **Format All + Download** or **Firmware Upgrade**. For this installation, use **Download Only** and flash only `ANDROID`.

<img width="1880" height="820" alt="image" src="https://github.com/user-attachments/assets/b1a7c479-ff63-4ce0-995a-f83b826f535c" />

#### Restoring the original firmware

If the modified system does not boot or function correctly:

1. Turn the device off.
2. Open SP Flash Tool.
3. Load the matching original scatter file.
4. Select **Download Only**.
5. Select only the original `ANDROID` partition and original `system.img`.
6. Flash it using the same careful procedure described above.

Do not restore additional partitions unless a verified device-specific recovery guide explicitly requires them.

### Build the system image yourself

The repository can also build a system-partition-only image from your own matching stock firmware. It installs the signed APK at `/system/priv-app/Y2Player.apk` and removes the stock launcher from the copied system image.

Provide:

```text
OriginalFirmware/system.img
OriginalFirmware/MT6582_Android_scatter.txt
```

Validate the host environment without producing an image:

```powershell
.\build-firmware.ps1 -ValidateOnly
```

Build and verify the release APK and replacement system image:

```powershell
powershell -ExecutionPolicy Bypass -File .\build-firmware.ps1
```

This requires WSL, Python 3, and Linux `e2fsprogs` in addition to the Android build requirements. Successful outputs are written to `out\firmware\`, including `Y2Player.apk`, `system.img`, checksums, a manifest, logs, and an independent verification report.

The build script only creates files. It never flashes, pushes, reboots, or modifies the original firmware inputs.

## Known limitations

- Y2Player is designed for the Innioasis Y2 and is not presented as a general-purpose Android player or launcher.
- Decoder and container support varies with the stock Android 4.4 media framework; recognition of a file extension does not guarantee playback.
- Bluetooth on stock KitKat is limited by the firmware stack: typically SBC A2DP, one sink at a time, no BLE Audio, and no synchronized absolute volume. Some connection-management operations depend on hidden OEM APIs and may require Android Settings.
- Bluetooth discovery can briefly degrade active A2DP audio on older hardware.
- DAC detection and the vendor Hi-Fi request are best-effort. Native DSD, bit-perfect output, and high-rate PCM are not promised.
- Equalizer, bass boost, loudness, haptics, storage aliases, route reporting, and exact decoder behavior are hardware/firmware dependent.
- The firmware pipeline requires the correct stock Y2 `system.img` and scatter file; these proprietary inputs are not generated by the project.
- Current checked-in screenshots are emulator references, not up-to-date photographs of the 480 × 360 hardware UI.
- Prebuilt system images are firmware-specific and must only be flashed on the supported Innioasis Y2 firmware base identified by the release.

## Contributing

Contributions should preserve Android 4.4/API 19 compatibility, physical-control navigation, offline operation, and the project's small CPU/heap footprint. Before submitting a change, run:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Hardware-dependent changes should describe the Y2 firmware version and include diagnostic evidence where possible.

## License

Y2Player is available under the [MIT License](LICENSE). Copyright © 2026 Luca Schulz.
