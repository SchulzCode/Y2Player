# System-image tools

- `build_firmware.sh` validates, integrates, verifies, and packages system.img.
- `integrate_launcher.py` replaces the stock launcher, installs its native
  runtime, and applies the guarded CS43131 frequency hook to the exact stock
  primary-audio HAL in a clean sparse image.
- `patch_primary_audio_hal.py` validates and patches the exact stock
  `/system/lib/libaudio.primary.default.so`; it refuses unknown or already
  modified inputs. `primary_audio_hal_hook.S` is the auditable ARM source for
  its independently assembled payload.
- `verify_images.py` independently reopens and verifies the finished image.
- `sparse.py` converts Android sparse/ext4 representations.
- `restore_stock_launcher.py` is a separate recovery utility, not a normal
  pipeline stage.

These tools operate on `system.img` only. The immutable
`OriginalFirmware/system.img` remains the recovery base; every generated image
receives the HAL patch and is checked against its audited SHA-256. The normal
entry point is `.\tools\build-firmware.ps1`; none of these tools flashes a
device.
