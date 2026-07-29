#!/usr/bin/env python3
"""Generate Y2Player's deterministic media compatibility corpus.

The expected manifest is assembled from the declarations in this file.  It is
never populated from Y2Player or from ffprobe.  ffprobe output is saved beside
the manifest only as an independent construction audit.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import math
import os
import shutil
import struct
import subprocess
import sys
import wave
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
GENERATOR_VERSION = "1.0"
DEFAULT_DURATION_MS = 500
TEXT_LIMIT_BYTES = 16 * 1024

FULL_TAGS = {
    "title": "Y2 Metadata 🎵 東京 Москва موسيقى",
    "artist": "Fixture Artist",
    "album": "Compatibility Album",
    "album_artist": "Various Fixture Artists",
    "composer": "Ada Lovelace",
    "genre": "Regression",
    "date": "2026-07-29",
    "comment": "Independent corpus manifest; not parsed from application output.",
    "track": "7/12",
    "disc": "2/3",
    "replaygain_track_gain": "-7.25 dB",
    "replaygain_track_peak": "0.923400",
    "replaygain_album_gain": "+1.50 dB",
    "replaygain_album_peak": "1.000000",
}


@dataclass(frozen=True)
class FormatDefinition:
    key: str
    extension: str
    codec: str
    container: str
    audio_args: tuple[str, ...]
    artwork_mode: str = "none"  # stream, vorbis_comment, or none
    metadata_support: frozenset[str] = frozenset(FULL_TAGS)


@dataclass
class Fixture:
    fixture_id: str
    group: str
    format_key: str
    expected_result: str = "PLAY"
    sample_rate: int = 44_100
    bit_depth: int = 16
    sample_format: str = "s16"
    channels: int = 2
    duration_ms: int = DEFAULT_DURATION_MS
    tags: dict[str, str] = field(default_factory=dict)
    artwork_count: int = 0
    notes: list[str] = field(default_factory=list)
    audio_args: tuple[str, ...] | None = None


FORMATS: dict[str, FormatDefinition] = {
    "mp3": FormatDefinition(
        "mp3", ".mp3", "mp3", "mp3",
        ("-c:a", "libmp3lame", "-b:a", "192k", "-id3v2_version", "3", "-write_id3v1", "1"),
        "stream",
    ),
    "flac": FormatDefinition(
        "flac", ".flac", "flac", "flac", ("-c:a", "flac", "-compression_level", "5"), "stream"
    ),
    "m4a_aac": FormatDefinition(
        "m4a_aac", ".m4a", "aac", "mov,mp4,m4a,3gp,3g2,mj2",
        ("-c:a", "aac", "-b:a", "192k"),
        "stream",
        frozenset(set(FULL_TAGS) - {"replaygain_track_gain", "replaygain_track_peak", "replaygain_album_gain", "replaygain_album_peak"}),
    ),
    "adts_aac": FormatDefinition(
        "adts_aac", ".aac", "aac", "aac", ("-c:a", "aac", "-b:a", "128k", "-f", "adts"), "none",
        frozenset(),
    ),
    "wav": FormatDefinition(
        "wav", ".wav", "pcm_s16le", "wav", ("-c:a", "pcm_s16le"), "none",
        frozenset({"title", "artist", "album", "genre", "comment", "track", "date"}),
    ),
    "ogg_vorbis": FormatDefinition(
        "ogg_vorbis", ".ogg", "vorbis", "ogg", ("-c:a", "vorbis", "-q:a", "5", "-strict", "experimental"),
        "vorbis_comment",
    ),
    "ogg_opus": FormatDefinition(
        "ogg_opus", ".opus", "opus", "ogg", ("-c:a", "opus", "-b:a", "128k", "-strict", "experimental"),
        "vorbis_comment",
    ),
    "m4a_alac": FormatDefinition(
        "m4a_alac", ".m4a", "alac", "mov,mp4,m4a,3gp,3g2,mj2",
        ("-c:a", "alac"), "stream",
        frozenset(set(FULL_TAGS) - {"replaygain_track_gain", "replaygain_track_peak", "replaygain_album_gain", "replaygain_album_peak"}),
    ),
    "aiff": FormatDefinition(
        "aiff", ".aiff", "pcm_s16be", "aiff", ("-c:a", "pcm_s16be"), "none",
        frozenset({"title", "comment"}),
    ),
}


def supported_fixtures() -> list[Fixture]:
    fixtures: list[Fixture] = []
    for key in FORMATS:
        rate = 48_000 if key == "ogg_opus" else 44_100
        fixtures.extend([
            Fixture(f"{key}_full", "metadata", key, sample_rate=rate, tags=dict(FULL_TAGS), artwork_count=1),
            Fixture(f"{key}_no_artwork", "metadata", key, sample_rate=rate, tags={
                "title": f"{key} no artwork", "artist": "Fixture Artist", "album": "No Artwork"
            }),
            Fixture(f"{key}_missing_metadata", "metadata", key, sample_rate=rate),
        ])

    fixtures.extend([
        Fixture("mp3_utf16", "metadata", "mp3", tags={
            "title": "UTF-16 雪 🎶", "artist": "Исполнитель", "comment": "ID3v2.3 text"
        }, audio_args=("-c:a", "libmp3lame", "-q:a", "2", "-id3v2_version", "3")),
        Fixture("flac_long_title", "metadata", "flac", tags={"title": "L" * (TEXT_LIMIT_BYTES + 4096)},
                notes=["Application contract truncates a metadata value to 16384 UTF-8 bytes."]),
        Fixture("ogg_conflicting_aliases", "metadata", "ogg_vorbis", tags={
            "title": "Alias precedence", "album_artist": "Canonical Album Artist", "albumartist": "Conflicting Alias"
        }, notes=["album_artist has deterministic priority over albumartist."]),
        Fixture("mp3_empty_values", "metadata", "mp3", tags={"title": "Empty values", "artist": "", "album": ""}),
        Fixture("flac_replaygain_positive", "metadata", "flac", tags={
            "title": "Positive gain", "replaygain_track_gain": "+3.00 dB", "replaygain_track_peak": "0.500000"
        }),
        Fixture("flac_replaygain_zero", "metadata", "flac", tags={
            "title": "Zero gain", "replaygain_track_gain": "0.00 dB", "replaygain_track_peak": "1.000000"
        }),
        Fixture("flac_replaygain_malformed", "metadata", "flac", tags={
            "title": "Malformed gain", "replaygain_track_gain": "loud", "replaygain_track_peak": "not-a-number"
        }),
        Fixture("mp3_multiple_artwork", "metadata", "mp3", tags={"title": "Multiple artwork"}, artwork_count=2),
    ])
    return fixtures


def technical_fixtures() -> list[Fixture]:
    return [
        Fixture("wav_44100_s16_mono", "technical", "wav", sample_rate=44_100, bit_depth=16, channels=1),
        Fixture("wav_48000_s24_stereo", "technical", "wav", sample_rate=48_000, bit_depth=24, sample_format="s32", audio_args=("-c:a", "pcm_s24le")),
        Fixture("wav_88200_s32_mono", "technical", "wav", sample_rate=88_200, bit_depth=32, sample_format="s32", channels=1, audio_args=("-c:a", "pcm_s32le")),
        Fixture("wav_96000_f32_stereo", "technical", "wav", sample_rate=96_000, bit_depth=32, sample_format="flt", audio_args=("-c:a", "pcm_f32le")),
        Fixture("wav_192000_s24_stereo", "technical", "wav", sample_rate=192_000, bit_depth=24, sample_format="s32", audio_args=("-c:a", "pcm_s24le")),
        Fixture("flac_level0_44100_s16", "technical", "flac", audio_args=("-c:a", "flac", "-compression_level", "0")),
        Fixture("flac_level5_96000_s24", "technical", "flac", sample_rate=96_000, bit_depth=24, sample_format="s32", audio_args=("-c:a", "flac", "-compression_level", "5")),
        Fixture("flac_level8_192000_s24", "technical", "flac", sample_rate=192_000, bit_depth=24, sample_format="s32", audio_args=("-c:a", "flac", "-compression_level", "8")),
        Fixture("mp3_cbr64_mono", "technical", "mp3", channels=1, audio_args=("-c:a", "libmp3lame", "-b:a", "64k")),
        Fixture("mp3_cbr128_stereo", "technical", "mp3", audio_args=("-c:a", "libmp3lame", "-b:a", "128k")),
        Fixture("mp3_cbr320_stereo", "technical", "mp3", audio_args=("-c:a", "libmp3lame", "-b:a", "320k")),
        Fixture("mp3_vbr_q2", "technical", "mp3", audio_args=("-c:a", "libmp3lame", "-q:a", "2")),
        Fixture("mp3_vbr_q7", "technical", "mp3", audio_args=("-c:a", "libmp3lame", "-q:a", "7")),
        Fixture("m4a_aac_lc_96k", "technical", "m4a_aac", audio_args=("-c:a", "aac", "-profile:a", "aac_low", "-b:a", "96k")),
        Fixture("m4a_aac_lc_256k", "technical", "m4a_aac", audio_args=("-c:a", "aac", "-profile:a", "aac_low", "-b:a", "256k")),
        Fixture("adts_aac_lc_128k", "technical", "adts_aac", audio_args=("-c:a", "aac", "-profile:a", "aac_low", "-b:a", "128k", "-f", "adts")),
        Fixture("vorbis_q2", "technical", "ogg_vorbis", audio_args=("-c:a", "vorbis", "-q:a", "2", "-strict", "experimental")),
        Fixture("vorbis_q8", "technical", "ogg_vorbis", audio_args=("-c:a", "vorbis", "-q:a", "8", "-strict", "experimental")),
        Fixture("opus_64k", "technical", "ogg_opus", sample_rate=48_000, audio_args=("-c:a", "opus", "-b:a", "64k", "-strict", "experimental")),
        Fixture("opus_160k", "technical", "ogg_opus", sample_rate=48_000, audio_args=("-c:a", "opus", "-b:a", "160k", "-strict", "experimental")),
        Fixture("alac_96000_s24", "technical", "m4a_alac", sample_rate=96_000, bit_depth=24, sample_format="s32", audio_args=("-c:a", "alac", "-sample_fmt", "s32p")),
        Fixture("aiff_44100_s16", "technical", "aiff"),
        Fixture("aiff_96000_s24", "technical", "aiff", sample_rate=96_000, bit_depth=24, sample_format="s32", audio_args=("-c:a", "pcm_s24be")),
        Fixture("flac_extremely_short", "technical", "flac", duration_ms=1, channels=1),
    ]


def png_bytes(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    def chunk(kind: bytes, payload: bytes) -> bytes:
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
    row = b"\x00" + bytes(rgb) * width
    raw = row * height
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def flac_picture_comment(image: bytes, description: str = "Front cover") -> str:
    mime = b"image/jpeg"
    desc = description.encode("utf-8")
    payload = (struct.pack(">I", 3) + struct.pack(">I", len(mime)) + mime + struct.pack(">I", len(desc)) + desc
               + struct.pack(">IIIII", 32, 24, 24, 0, len(image)) + image)
    return base64.b64encode(payload).decode("ascii")


def run(command: list[str], quiet: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(command)}\n{result.stderr}")
    if not quiet and result.stderr:
        print(result.stderr, file=sys.stderr)
    return result


def metadata_args(tags: dict[str, str]) -> list[str]:
    result: list[str] = []
    for key, value in tags.items():
        result.extend(["-metadata", f"{key}={value}"])
    return result


def expected_metadata(fixture: Fixture, fmt: FormatDefinition) -> dict[str, Any]:
    tags = {key: value for key, value in fixture.tags.items() if key in fmt.metadata_support}
    title = tags.get("title")
    if title is not None and len(title.encode("utf-8")) > TEXT_LIMIT_BYTES:
        title = title.encode("utf-8")[:TEXT_LIMIT_BYTES].decode("utf-8", "replace")
    track_text = tags.get("track")
    disc_text = tags.get("disc")
    def number(text: str | None, part: int) -> int | None:
        if not text:
            return None
        pieces = text.split("/", 1)
        if part >= len(pieces):
            return None
        try:
            value = int(pieces[part].strip())
            return value if value > 0 else None
        except ValueError:
            return None
    def gain(text: str | None) -> float | None:
        if not text:
            return None
        try:
            return float(text.lower().replace("db", "").strip())
        except ValueError:
            return None
    def peak(text: str | None) -> float | None:
        try:
            value = float(text) if text is not None else 0.0
            return value if value > 0 else None
        except ValueError:
            return None
    return {
        "title": title,
        "artist": tags.get("artist") or None,
        "album": tags.get("album") or None,
        "albumArtist": (
            f"{tags['album_artist']};{fixture.tags['albumartist']}"
            if tags.get("album_artist") and fmt.container == "ogg" and fixture.tags.get("albumartist")
            else tags.get("album_artist") or tags.get("albumartist") or None
        ),
        "composer": tags.get("composer") or None,
        "genre": tags.get("genre") or None,
        "date": tags.get("date") or None,
        "year": int(tags["date"][:4]) if tags.get("date", "")[:4].isdigit() else None,
        "comment": tags.get("comment") or None,
        "trackNumber": number(track_text, 0),
        "trackTotal": number(track_text, 1),
        "discNumber": number(disc_text, 0),
        "discTotal": number(disc_text, 1),
        "replayGainTrackDb": gain(tags.get("replaygain_track_gain")),
        "replayGainTrackPeak": peak(tags.get("replaygain_track_peak")),
        "replayGainAlbumDb": gain(tags.get("replaygain_album_gain")),
        "replayGainAlbumPeak": peak(tags.get("replaygain_album_peak")),
        "hasArtwork": fixture.artwork_count > 0 and fmt.artwork_mode != "none",
        "artworkCountMinimum": min(fixture.artwork_count, 1) if fmt.artwork_mode != "none" else 0,
    }


def generate_encoded(ffmpeg: Path, fixture: Fixture, destination: Path, front: Path, back: Path) -> None:
    fmt = FORMATS[fixture.format_key]
    source = [
        str(ffmpeg), "-hide_banner", "-loglevel", "error", "-y", "-bitexact",
        "-f", "lavfi", "-i", f"sine=frequency={300 + (sum(fixture.fixture_id.encode()) % 900)}:sample_rate={fixture.sample_rate}:duration={fixture.duration_ms / 1000:.6f}",
    ]
    artwork_args: list[str] = []
    maps = ["-map", "0:a:0"]
    if fixture.artwork_count and fmt.artwork_mode == "stream":
        source.extend(["-i", str(front)])
        maps.extend(["-map", "1:v:0"])
        artwork_args.extend(["-c:v:0", "copy", "-disposition:v:0", "attached_pic", "-metadata:s:v:0", "title=Front cover"])
        if fixture.artwork_count > 1:
            source.extend(["-i", str(back)])
            maps.extend(["-map", "2:v:0"])
            artwork_args.extend(["-c:v:1", "copy", "-disposition:v:1", "attached_pic", "-metadata:s:v:1", "title=Back cover"])
    tags = dict(fixture.tags)
    if fixture.artwork_count and fmt.artwork_mode == "vorbis_comment":
        tags["METADATA_BLOCK_PICTURE"] = flac_picture_comment(front.read_bytes())
    args = list(fixture.audio_args or fmt.audio_args)
    command = source + maps + ["-ac", str(fixture.channels), "-ar", str(fixture.sample_rate)]
    if fixture.sample_format != "s16" and "-sample_fmt" not in args:
        command += ["-sample_fmt", fixture.sample_format]
    command += args + artwork_args + metadata_args(tags) + [str(destination)]
    run(command)


def mutate_corpus(root: Path, entries: list[dict[str, Any]]) -> None:
    corrupt = root / "corrupt"
    unsupported = root / "unsupported"
    corrupt.mkdir(parents=True, exist_ok=True)
    unsupported.mkdir(parents=True, exist_ok=True)
    sources = root / "supported"

    def write_case(case_id: str, filename: str, data: bytes, result: str, source: str | None, notes: str) -> None:
        directory = unsupported if result in {"UNSUPPORTED", "INDEX_ONLY"} else corrupt
        target = directory / filename
        target.write_bytes(data)
        entries.append({
            "id": case_id, "group": "malformed", "path": target.relative_to(root).as_posix(),
            "expectedResult": result, "sourceFixture": source, "expected": {}, "notes": [notes],
            "sha256": hashlib.sha256(data).hexdigest(), "sizeBytes": len(data),
        })

    mp3 = (sources / "metadata/mp3_full.mp3").read_bytes()
    flac = (sources / "metadata/flac_full.flac").read_bytes()
    wav = (sources / "technical/wav_44100_s16_mono.wav").read_bytes()
    write_case("zero_byte", "zero-byte.mp3", b"", "CORRUPT", None, "Filesystem discovery rejects zero-byte files; direct probe must fail deterministically.")
    write_case("truncated_header", "truncated-header.flac", flac[:3], "CORRUPT", "flac_full", "FLAC magic is incomplete.")
    write_case("truncated_metadata", "truncated-metadata.flac", flac[:80], "CORRUPT", "flac_full", "STREAMINFO/comment data is incomplete.")
    write_case("truncated_audio", "truncated-audio.mp3", mp3[: max(256, len(mp3) // 2)], "PLAY_WITH_WARNINGS", "mp3_full", "Header and tags survive; decoder reaches premature EOF.")
    write_case("invalid_footer", "invalid-footer.mp3", mp3[:-128] + b"TAG" + b"\xff" * 125, "PLAY", "mp3_full", "Corrupt ID3v1 footer is ignored because the audio and primary ID3v2 metadata remain valid.")
    write_case("random_appended", "random-appended.flac", flac + bytes(range(256)) * 4, "PLAY_WITH_WARNINGS", "flac_full", "Trailing bytes are ignored with a warning.")
    midpoint = len(mp3) // 2
    write_case("random_inserted", "random-inserted.mp3", mp3[:midpoint] + b"Y2CORRUPTION" * 64 + mp3[midpoint:], "PLAY_WITH_WARNINGS", "mp3_full", "Some frames are damaged but later PCM remains.")
    damaged_art = bytearray(mp3)
    signature = damaged_art.find(b"\xff\xd8\xff")
    if signature >= 0:
        damaged_art[signature:signature + 3] = b"BAD"
    write_case("corrupt_artwork", "corrupt-artwork.mp3", bytes(damaged_art), "PLAY_WITH_WARNINGS", "mp3_full", "Audio and tags remain usable; artwork bytes are invalid.")
    write_case("invalid_utf", "invalid-utf.mp3", mp3.replace(b"Fixture Artist", b"Bad UTF \xc3(".ljust(len(b"Fixture Artist"), b" "), 1), "PLAY", "mp3_full", "Invalid UTF becomes replacement text without affecting playable audio.")
    write_case("invalid_container", "invalid-container.mp3", b"not audio\x00" + bytes(range(128)), "CORRUPT", None, "Supported extension with no valid container.")
    write_case("renamed_extension", "renamed-flac.mp3", flac, "PLAY", "flac_full", "Container probing, not extension, identifies FLAC.")
    write_case("valid_pcm_garbage", "pcm-followed-by-garbage.wav", wav + b"GARBAGE" * 1024, "PLAY", "wav_44100_s16_mono", "Bytes beyond the declared RIFF data are ignored; declared PCM remains valid.")
    write_case("partial_pcm", "partial-pcm.wav", wav[: max(64, len(wav) * 3 // 4)], "PLAY_WITH_WARNINGS", "wav_44100_s16_mono", "Header declares more PCM than exists.")
    empty_wav = bytearray(wav[:44])
    if len(empty_wav) >= 44:
        empty_wav[4:8] = struct.pack("<I", 36)
        empty_wav[40:44] = struct.pack("<I", 0)
    write_case("no_pcm", "no-pcm.wav", bytes(empty_wav), "CORRUPT", "wav_44100_s16_mono", "Valid-looking PCM header produces zero frames.")
    write_case("large_metadata", "large-metadata.flac", (sources / "metadata/flac_long_title.flac").read_bytes(), "PLAY", "flac_long_title", "Valid metadata exceeds the application's bounded text contract.")
    write_case("unsupported_codec", "unsupported-pcm-alaw.wav", wav, "INDEX_ONLY", None, "Replaced after generation with a valid WAV codec absent from the target decoder allowlist.")
    write_case("unsupported_container", "unsupported-ac3.ac3", b"", "UNSUPPORTED", None, "Replaced after generation with a valid AC-3 stream absent from the target demuxer/decoder allowlists.")


def replace_unsupported_codec(ffmpeg: Path, root: Path) -> None:
    target = root / "unsupported/unsupported-pcm-alaw.wav"
    run([str(ffmpeg), "-hide_banner", "-loglevel", "error", "-y", "-bitexact", "-f", "lavfi", "-i",
         "sine=frequency=777:sample_rate=8000:duration=0.5", "-ac", "1", "-c:a", "pcm_alaw", str(target)])
    ac3 = root / "unsupported/unsupported-ac3.ac3"
    run([str(ffmpeg), "-hide_banner", "-loglevel", "error", "-y", "-bitexact", "-f", "lavfi", "-i",
         "sine=frequency=778:sample_rate=48000:duration=0.5", "-ac", "2", "-c:a", "ac3", "-f", "ac3", str(ac3)])


def probe(ffprobe: Path, path: Path) -> Any:
    result = subprocess.run([str(ffprobe), "-v", "error", "-show_format", "-show_streams", "-of", "json", str(path)],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        return {"error": result.stderr.strip(), "exitCode": result.returncode}
    return json.loads(result.stdout)


def generate(args: argparse.Namespace) -> None:
    repo = Path(args.repo).resolve()
    ffmpeg = Path(args.ffmpeg or repo / "build/host-ffmpeg/install/bin/ffmpeg")
    ffprobe = Path(args.ffprobe or repo / "build/host-ffmpeg/install/bin/ffprobe")
    output = Path(args.output or repo / "tools/media/corpus").resolve()
    if not ffmpeg.is_file() or not ffprobe.is_file():
        raise SystemExit("host ffmpeg/ffprobe missing; see tools/media/README.md")
    if output.exists():
        shutil.rmtree(output)
    (output / "supported/metadata").mkdir(parents=True)
    (output / "supported/technical").mkdir(parents=True)
    assets = output / ".assets"
    assets.mkdir()
    front = assets / "front.jpg"
    back = assets / "back.jpg"
    # JPEG is generated by the independent host tool so its parser supplies
    # dimensions before stream-copying it into MP3/FLAC/MP4 artwork streams.
    # A hand-built PNG is unsuitable here because this deliberately minimal
    # host build has no zlib-backed PNG encoder/parser dimension propagation.
    for target, colour in ((front, "0x227ac4"), (back, "0xd25a30")):
        run([str(ffmpeg), "-hide_banner", "-loglevel", "error", "-y", "-bitexact",
             "-f", "lavfi", "-i", f"color=c={colour}:s=32x24", "-frames:v", "1",
             "-c:v", "mjpeg", str(target)])

    entries: list[dict[str, Any]] = []
    fixtures = supported_fixtures() + technical_fixtures()
    for fixture in fixtures:
        fmt = FORMATS[fixture.format_key]
        relative = Path("supported") / fixture.group / f"{fixture.fixture_id}{fmt.extension}"
        target = output / relative
        generate_encoded(ffmpeg, fixture, target, front, back)
        data = target.read_bytes()
        entries.append({
            "id": fixture.fixture_id,
            "group": fixture.group,
            "path": relative.as_posix(),
            "expectedResult": fixture.expected_result,
            "expected": {
                **expected_metadata(fixture, fmt),
                "durationMs": fixture.duration_ms,
                "durationToleranceMs": 80 if fmt.codec in {"mp3", "aac", "vorbis", "opus"} else 2,
                "sampleRate": fixture.sample_rate,
                "bitDepth": None if fmt.codec in {"mp3", "aac", "vorbis", "opus"} else fixture.bit_depth,
                "channels": fixture.channels,
                "codec": fmt.codec if fixture.audio_args is None else None,
                "container": fmt.container,
                "minimumDecodedFrames": 1,
            },
            "notes": fixture.notes,
            "sha256": hashlib.sha256(data).hexdigest(),
            "sizeBytes": len(data),
        })

    mutate_corpus(output, entries)
    replace_unsupported_codec(ffmpeg, output)
    for unsupported_id in ("unsupported_codec", "unsupported_container"):
        unsupported_entry = next(item for item in entries if item["id"] == unsupported_id)
        unsupported_data = (output / unsupported_entry["path"]).read_bytes()
        unsupported_entry["sha256"] = hashlib.sha256(unsupported_data).hexdigest()
        unsupported_entry["sizeBytes"] = len(unsupported_data)
    shutil.rmtree(assets)

    entries.sort(key=lambda item: item["path"])
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "generatorVersion": GENERATOR_VERSION,
        "contract": {
            "classification": ["PLAY", "PLAY_WITH_WARNINGS", "INDEX_ONLY", "UNSUPPORTED", "CORRUPT"],
            "metadataTextLimitBytes": TEXT_LIMIT_BYTES,
            "manifestSource": "declarative fixture definitions; never Y2Player output",
        },
        "toolchain": {
            "ffmpeg": run([str(ffmpeg), "-version"]).stdout.splitlines()[0],
            "lameSourceSha256": "ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e",
        },
        "fixtureCount": len(entries),
        "fixtures": entries,
    }
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    probes = {item["id"]: probe(ffprobe, output / item["path"]) for item in entries}
    (output / "reference-probes.json").write_text(json.dumps(probes, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"generated {len(entries)} fixtures in {output}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output")
    parser.add_argument("--ffmpeg")
    parser.add_argument("--ffprobe")
    generate(parser.parse_args())


if __name__ == "__main__":
    main()
