# Y2Player v2.5 is now available — Search, FM Radio, faster navigation, and many fixes

Hi everyone!

Y2Player v2.5 is now available.

I originally planned to release most of these changes earlier as version 2.4. While I was preparing that release, Innioasis published its new FM-enabled firmware and confirmed that newer Y2 batches contain FM radio hardware. Since an experimental FM implementation was already part of my plans, I decided to delay the release, add it properly, and make this version 2.5 instead.

The two biggest additions are whole-device Search and FM Radio, alongside improvements to alphabet scrolling, sleep timers, screen-off playback, equalizer controls, queue behavior, and navigation.

## Important note about FM Radio

Innioasis has now confirmed that there are two Y2 hardware revisions:

- Original Y2 devices do not contain the required FM hardware.
- Newer Y2 devices include the FM circuitry and use the FM-enabled stock firmware.

Unfortunately, my own Y2 is from the original hardware batch and does not support radio reception. I was able to implement the software side using the MediaTek FM interface and the new Innioasis firmware, but I cannot test actual reception on compatible hardware myself.

The FM screen supports frequency tuning, station seeking, signal information, headphone antenna detection, and switching safely between music playback and FM audio. On my original Y2, the interface works but only produces static because the physical FM hardware is missing.

For that reason, FM Radio is hidden by default. If you own one of the newer FM-capable Y2 units, enable it under:

**Settings → Interface → FM Radio**

I would really appreciate feedback from anyone with a newer Y2. Please let me know:

- Whether FM reception works
- Whether station seeking finds real stations
- Whether the displayed signal strength behaves correctly
- Whether headphones are detected and work as the antenna
- Whether audio routing and volume behave correctly
- Whether music playback resumes normally after leaving FM Radio

If possible, please confirm that FM also works in the official FM-enabled Innioasis firmware. That will help distinguish a Y2Player problem from an incompatible hardware revision.

Flashing the new Innioasis firmware onto an original Y2 does not add the missing FM circuitry. Original units will still receive only static.

## Whole-device Search

Search is now available directly from the main menu.

It can find:

- Songs
- Albums
- Artists
- Playlists
- Audiobooks

Track searches also cover album names, filenames, genres, composers, and other indexed metadata. Matching ignores accents, supports multiple words, and tries to rank exact and prefix matches first.

The keyboard was built specifically for the Y2. You can control it using the wheel and buttons or tap keys and results directly on the screen.

I want to be transparent that Search is still a little rough around the edges. Entering text with a click wheel on such a small display will never feel exactly like typing on a phone, and there may still be navigation, ranking, layout, or performance cases that I have not encountered with my own library.

The search engine is indexed and cached, so it should remain responsive with large collections. Still, I would appreciate feedback about:

- Libraries where Search feels slow
- Results that are missing or ranked strangely
- Metadata that cannot be found
- Confusing keyboard navigation
- Text or result rows that do not fit correctly
- Accidental button actions while Search is open

Please include an example query and the expected result when reporting a Search problem.

## Faster alphabet scrolling

Fast, sustained wheel movement can now jump through `#` and `A–Z` in large alphabetically sorted lists.

Normal wheel movement remains precise, while the faster alphabet mode is used only in suitable views such as title-sorted songs, artists, albums, playlists, and Favorites. It stays disabled in Queue, settings, folders, Recently Played, album track lists, and other screens where alphabetical jumping would not make sense.

## Improved Sleep Timer

The Sleep Timer now has a proper selection screen with:

- Off
- End of track
- End of album
- End of queue
- Every exact duration from 1 to 60 minutes

Minute timers now display a live countdown, and expired timers are cleared correctly.

## Better screen-off playback and controls

This release fixes playback sometimes stopping between tracks while the display is asleep. The playback service now keeps the CPU awake across the short handoff between one completed track and the next, while still releasing the wake lock when playback is paused or stopped.

There is also a new optional setting for audiobook listeners:

**Settings → Audio → Seeking → Seek When Locked**

When enabled, the Y2’s Left and Right buttons seek while the device is locked instead of changing tracks or chapters. It is disabled by default.

Another fix prevents a held seek button from being misinterpreted as multiple chapter skips when the display wakes during the gesture.

## Queue and Shuffle fixes

- Disabling Shuffle now restores the remaining ordered playlist or collection around the current track.
- Manually added Up Next tracks remain ahead of that restored continuation.
- Queue Center-button behavior now matches other track lists.
- Short and held Center presses no longer accidentally perform conflicting queue actions.

## Redesigned Equalizer controls

Equalizer presets now appear in an explicit list instead of cycling blindly.

Selecting an individual band opens a dedicated dB value picker. The current value is preselected, Center applies the new value, and Back cancels. A Custom entry appears when individual band values are active.

## Navigation and interface improvements

- Holding Back returns directly to the main menu.
- A short Back press still returns one screen.
- Back dismisses temporary messages before navigating away.
- Wrap Lists now behaves consistently across normal lists, settings, confirmation screens, equalizer selectors, Search, and circular menus.
- The permanent transition-mode label was removed from the Now Playing footer for a cleaner layout. Gapless and Crossfade are still available and unchanged.
- Connecting USB now produces one haptic response when haptics are enabled, without vibrating repeatedly for duplicate broadcasts.

## Library and artwork fixes

- **Browse Track → Go to Artist** now opens that artist’s album list instead of a flat list of every song.
- Oversized or malformed embedded artwork is rejected safely instead of potentially crashing Y2Player.

## Download and installation

The release includes:

- A system-only `system.img`
- A complete `rom_y2.zip` package for the Innioasis Updater
- The signed Y2Player APK

The system-only image is intended for the **ANDROID partition only** using Download Only mode.

The complete `rom_y2.zip` performs a full-device installation and **erases user data**. Export a Y2Player backup and copy important files off the device before installing it.

Please verify the supplied SHA-256 checksums before flashing and make sure you are installing the Y2 release, not a Y1 package.

**Download:** [GitHub release link]

## Feedback and bug reports

This release contains two comparatively large new features, and both will benefit from testing across different libraries and Y2 hardware revisions.

FM Radio especially needs testing by owners of the new FM-capable Y2 because my own device lacks the required hardware. Search is working and has extensive automated coverage, but its interaction design and result behavior may still need refinement based on real-world libraries.

If you encounter a problem, please enable diagnostics before reproducing it:

**Settings → System → Diagnostics → Verbose Diagnostics**

Then export the report from:

**Settings → System → Diagnostics → Export Diagnostics**

Please attach the report to a GitHub issue and include the relevant audio format, output method, settings, hardware-button sequence, and whether your Y2 is from the original or FM-enabled batch.

Thank you to everyone who opened issues, tested previous versions, shared diagnostics, and suggested improvements. A large part of version 2.5 came directly from your reports and feedback. I hope you enjoy the update, and I am looking forward to hearing how FM Radio and Search behave on your devices!
