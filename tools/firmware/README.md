# System-image tools

- `build_firmware.sh` validates, integrates, verifies, and packages system.img.
- `integrate_launcher.py` replaces the stock launcher, installs its native
  runtime, applies the guarded CS43131 frequency hook to the exact stock
  primary-audio HAL, and removes the optional packages selected by the shared
  conservative appliance policy.
- `system_package_policy.py` is the single audited list of optional packages
  to prune and critical packages that must remain. Both integration and
  independent verification consume this policy.
- `patch_primary_audio_hal.py` validates and patches the exact stock
  `/system/lib/libaudio.primary.default.so`; it refuses unknown or already
  modified inputs. `primary_audio_hal_hook.S` is the auditable ARM source for
  its independently assembled payload.
- `patch_mtk_keylayout.py` validates the exact stock `mtk-kpd.kl` and remaps
  only physical scan codes 115/114 to media surrogates. Y2Player recognizes
  those surrogates only when the original scan code came from the local
  keypad, leaving actual headset and Bluetooth transport keys unchanged.
- `verify_images.py` independently reopens and verifies the finished image.
- `sparse.py` converts Android sparse/ext4 representations.
- `restore_stock_launcher.py` is a separate recovery utility, not a normal
  pipeline stage.

The minimized profile removes 35 standalone media, utility, wallpaper, phone
UI, and engineering packages plus the private VideoEditor libraries. It keeps
Android provisioning, Settings/SystemUI/Keyguard, storage and media providers,
Bluetooth, the input method, package handling, thermal/battery components, and
the underlying telephony services. Freed ext4 blocks are explicitly cleared
and independently verified as zero before the image is repacked, so removed
payloads do not inflate the sparse output.

These tools operate on `system.img` only. The immutable
`OriginalFirmware/system.img` remains the recovery base; every generated image
receives the HAL and keypad patches and checks both against audited SHA-256
values. The normal entry point is `.\tools\build-firmware.ps1`; none of these
tools flashes a device.
