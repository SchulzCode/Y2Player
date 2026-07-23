# System-image tools

- `build_firmware.sh` validates, integrates, verifies, and packages system.img.
- `integrate_launcher.py` replaces the stock launcher in a clean sparse image.
- `verify_images.py` independently reopens and verifies the finished image.
- `sparse.py` converts Android sparse/ext4 representations.
- `restore_stock_launcher.py` is a separate recovery utility, not a normal
  pipeline stage.

These tools operate on `system.img` only. The normal entry point is
`.\tools\build-firmware.ps1`; none of these tools flashes a device.
