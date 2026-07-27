# Changelog

## 2.0 — unified FFmpeg engine

- Replaced playback and format diagnostics with one FFmpeg 8.1.2 engine for MP3, AAC/M4A, ALAC/M4A, FLAC, WAV/PCM, and Ogg Vorbis.
- Added a reproducible NDK r25c/API-19/armeabi-v7a build that statically consolidates the allowlisted FFmpeg runtime into one `liby2audio.so`.
- Installed that runtime at `/system/lib/liby2audio.so` in generated firmware, with byte-for-byte APK/image verification and stock Android 4.4 file metadata.
- Added persistent AudioTrack PCM output, native seek/abort handling, playback-head position accounting, PCM gapless promotion, and PCM crossfade mixing.
- Added explicit application wake-lock ownership and retained queue, focus, Bluetooth, media-control, storage, persistence, sleep-timer, and effects policies.
- Removed playback and diagnostic framework-decoder paths and invalidated their stale unsupported-file verdicts.
- Direct DAC now reports the audited stock-HAL limitation and falls back to standard AudioTrack without speculative device access.

## 1.2

Mostly things people asked for. The menu has been reorganised, so a few settings
have moved.

### New

- **Artists now open that artist's albums** instead of jumping straight to every
  track, so browsing goes Artists → album → songs. "All Songs" is still the first
  row, which is what pressing an artist used to do.
- **Light theme**, under Settings → Display → Theme. The dark design is unchanged
  and remains the default. On a transflective LCD like the Y2's, a light theme is
  also the more readable choice outdoors, because a pale background lets the
  backlight through instead of blocking it.
- **Left/right balance**, under Sound Effects, for asymmetric hearing loss.
  Centred by default. It attenuates the far channel rather than boosting the near
  one, so total loudness drops as you move off centre.
- **Wheel When Screen Off**, under Controls, off by default. With it on, the click
  wheel and its buttons keep working with the display asleep — which also means
  they can be pressed in a pocket, which is why it is off unless you ask for it.

### Changed

- **Settings is reordered** by how often each entry is actually opened. Bluetooth
  is first, because connecting a headset is a recurring task rather than a setting.
  Sound Effects is now a top-level entry rather than living inside Playback, so the
  equalizer and balance are three presses away instead of four. Sort Order sits
  next to Storage, since both concern the library.
- **Haptics and System UI Sounds moved off Display** onto a new **Controls** screen,
  together with the setting above. They are feedback for input, not display
  settings.
- Playback's settings are grouped by what they do: what plays next, transitions,
  seeking, volume, then interruptions.
- Sound Effects lists the things you can change before the things you can only
  read.
- Track info states whether the file carries a track number.

### Fixed

- **Choppy playback during a library scan** should be much rarer. The scan already
  paused for audio, but only once per 64 files, which left roughly three seconds
  of uninterrupted work between pauses — long enough to starve the playback
  buffer. It now yields every 8 files, for a share of what those files actually
  cost.
- **A Bluetooth headset reported as "not connected"** on every screen except the
  Bluetooth screen.
- **The DAC and output rows were hidden** when the device's effect framework was
  missing — which is exactly the firmware whose behaviour those rows explain.
- **Two artists with an identically named album** (a *Greatest Hits* each) opened
  one merged track list. An album reached through an artist is now scoped to that
  artist. The global Albums list still merges shared names on purpose, so a
  compilation stays one album.
- **ALAC files in an `.m4a` were reported as AAC.** In the 1.2 framework engine they could not be played —
  see below — but they are now identified correctly and labelled, instead of
  failing silently when you press play.

### Why ALAC did not play in 1.2

Apple Lossless needs a decoder, and the Y2 runs Android 4.4. ALAC decoding was
added to Android in version 12, eight years later. An app cannot supply the
missing decoder: the framework decoder and `MediaCodec` could only use codecs the operating
system already has, so there is no setting or update to this app that would make
an ALAC file play on this hardware.

What 1.2 does instead is stop pretending. The codec is now read out of the file
itself rather than guessed from the container, so an ALAC track is labelled
**not playable** in the list rather than appearing normal and then failing.

**If you have ALAC files, convert them to FLAC.** Both are lossless, so the
conversion is bit-exact for the audio — you lose nothing but Apple's container —
and FLAC plays natively on the Y2:

```
ffmpeg -i input.m4a -c:a flac -compression_level 5 output.flac
```

Supporting ALAC properly would mean bundling a decoder and building a second
playback path to feed it, duplicating seeking, gapless and crossfade. That is a
large amount of machinery for one format that converts losslessly in one command,
so it is not planned.

### Also still true

- Formats some firmware adds, such as WMA and APE, are deliberately left
  unlabelled rather than guessed at, because they may well play on your device.

## 1.1

A maintenance release. Everything here comes from bug reports and device logs from 1.0 users.

### Fixed

- **Albums copied from a Mac appeared twice.** macOS writes a hidden `._Track.flac` companion beside every file it copies to the player. These were indexed as tracks, so you got a second copy of the album with no metadata that skipped silently when played — and could not be deleted, because both the desktop and the player hide dot-files. Hidden files are now skipped. One rescan removes the leftovers.
- **Albums listed out of order** when only some files carried a track number, for example 5, 1, 2, 6, 3, 4. Track numbers are now trusted only when every track on the album has one; otherwise the filenames decide the order. Browsing a folder follows album order too, instead of sorting by title.
- **"Library needs attention" could not be cleared.** A single unreadable or zero-byte file on the card made every rescan report a problem, permanently. The same fault also stopped deleted files from disappearing from the library, and stopped the last-scan time from updating. The alert now names its cause, and a scan you cancel yourself raises nothing.
- **Bluetooth stem controls needed playback to be started on the player first.** Media-button ownership is now re-taken whenever a headset connects. The first press after connecting is also no longer swallowed.
- **Triple press to skip back did not start playback** when nothing was playing.
- **A connected Bluetooth headset was reported as "not connected"** on every screen except the Bluetooth screen.
- **Pressing the track that is already playing restarted it** and rebuilt the queue from whatever list you were looking at, losing your position and your album or queue order. It now just opens Now Playing.
- **ALAC files in an `.m4a` were reported as AAC.** The codec is now read from the container itself, so the format shown is the real one.
- **The on-device log only ever showed the last 12 lines.** It now shows considerably more, and reads them from the end of the file instead of loading the whole thing.

### New

- Tracks in a format this device cannot decode are labelled **"not playable"** before you press play. A file that fails to play once is remembered, and the note is withdrawn if it ever does play or if you replace the file.
- Technical metadata is now read directly for MP3, M4A, AAC, OGG, Opus and AMR as well, alongside the existing WAV, FLAC, AIFF, WavPack and DSD support.
- **Battery percentage is always shown**, not only while charging or when low.
- Track info states whether the file carries a track number.

### Performance

- Playback stutters far less while the library is scanning. Scanning now yields briefly to audio and runs at background priority.
- Less repeated work during playback: audio route, DAC status and progress updates no longer rebuild themselves on every tick.

### Known limitations

- **ALAC could not be played by the 1.1 framework backend.** Converting to FLAC was the lossless workaround at that time.
- Formats some firmware adds, such as WMA and APE, are deliberately left unlabelled rather than guessed at, since they may well play on your device.

## 1.0

First released version.
