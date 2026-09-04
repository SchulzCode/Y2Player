# Pinned FFmpeg source

Y2Player uses FFmpeg 8.1.2 from the official release archive. The archive is
not checked into Git. `tools/build-linux.sh native` downloads it into the
ignored build cache, verifies its pinned SHA-256, and runs the Linux NDK
cross-build.

The release archive was additionally verified against
`ffmpeg-8.1.2.tar.xz.asc` with the official FFmpeg release key whose full
fingerprint is recorded in `source.properties`.

The native configuration is deliberately LGPL-only. It does not enable GPL or
nonfree components, networking, programs, encoders, muxers, devices, filters,
or video decoders. The exact configure invocation is maintained in
`tools/native/build-ffmpeg.sh` and is copied into the native build report.

For source compliance, release tooling must ship the exact verified archive,
this metadata, the configure/build scripts, any patches, the FFmpeg copyright
and license notices, and the relinking material documented by the project.
Static-link compliance must be reviewed before public distribution.
