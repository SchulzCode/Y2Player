# Y2Player media regression corpus

`generate-corpus.py` creates the local compatibility corpus and its
machine-readable expected-value manifest. Expectations are declared in the
generator; neither Y2Player nor its native parser is used as an oracle.

The generated `tools/media/corpus/` tree is intentionally ignored by Git. Run
the generator locally before executing the device regression suite.

The generated layout is:

```text
corpus/
  supported/metadata/
  supported/technical/
  unsupported/
  corrupt/
  manifest.json
  reference-probes.json
```

`reference-probes.json` is construction evidence from an independent upstream
FFmpeg 8.1.2 host build. It is diagnostic only and is never copied into
`manifest.json`.

The local generator expects `build/host-ffmpeg/install/bin/ffmpeg` and
`ffprobe`. The host build used for the v2.1 validation corpus is upstream FFmpeg
8.1.2 plus the official LAME 3.100 static library. LAME's source SHA-256 is
`ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e`.

Run on Linux:

```sh
python3 tools/media/generate-corpus.py
```

All audio signals are synthetic, deterministic, and at most 500 ms except for
the explicit 1 ms short-file case. No user media or copyrighted recording is
part of the corpus.

Run the complete device gate (build, install, clean data, push, scan, decode,
compare, and report) with:

```bash
./tools/media/run-media-regression.sh
```

`--probe-bytes` and `--analyze-us` override metadata probe limits for controlled
experiments without rebuilding native code. Production defaults are 32 KiB
and 100 ms; every candidate must still pass the full `all` mode before use.
