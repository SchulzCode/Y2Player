#!/usr/bin/env python3
"""Compare raw Y2 device observations with the independent corpus manifest."""

from __future__ import annotations

import argparse
import json
import math
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


INT_MIN = -(2**31)
METADATA_FIELDS = (
    "title", "artist", "album", "albumArtist", "composer", "genre", "date", "year",
    "comment", "trackNumber", "trackTotal", "discNumber", "discTotal", "durationMs",
    "sampleRate", "bitDepth", "channels", "codec", "container", "hasArtwork",
    "replayGainTrackDb", "replayGainTrackPeak", "replayGainAlbumDb", "replayGainAlbumPeak",
)


def scaled(value: Any, sentinel: int | None = None) -> float | None:
    if value is None or value == sentinel or value == 0 and sentinel is None:
        return None
    return float(value) / 100_000.0


def normalise_metadata(actual: dict[str, Any]) -> dict[str, Any]:
    value = dict(actual)
    value["replayGainTrackDb"] = scaled(actual.get("replayGainTrackGainScaled"), INT_MIN)
    value["replayGainTrackPeak"] = scaled(actual.get("replayGainTrackPeakScaled"))
    value["replayGainAlbumDb"] = scaled(actual.get("replayGainAlbumGainScaled"), INT_MIN)
    value["replayGainAlbumPeak"] = scaled(actual.get("replayGainAlbumPeakScaled"))
    for key in (
        "trackNumber", "trackTotal", "discNumber", "discTotal", "year",
        "bitrate", "sampleRate", "bitDepth", "channels"
    ):
        if value.get(key) == 0:
            value[key] = None
    return value


def values_equal(field: str, expected: Any, actual: Any, fixture: dict[str, Any]) -> bool:
    if field == "durationMs":
        tolerance = fixture["expected"].get("durationToleranceMs", 0)
        return actual is not None and abs(int(actual) - int(expected)) <= tolerance
    if isinstance(expected, float):
        return actual is not None and math.isclose(float(actual), expected, abs_tol=0.00002)
    return actual == expected


def check_fields(fixture: dict[str, Any], actual: dict[str, Any]) -> list[dict[str, Any]]:
    checks: list[dict[str, Any]] = []
    expected_values = fixture.get("expected", {})
    for field in METADATA_FIELDS:
        expected = expected_values.get(field)
        # A null codec in the manifest means the generator overrode the default
        # codec and that field is intentionally not asserted for this row.
        if field == "codec" and expected is None:
            continue
        observed = actual.get(field)
        passed = values_equal(field, expected, observed, fixture)
        checks.append({"field": field, "expected": expected, "actual": observed, "pass": passed})
    return checks


def compare(manifest: dict[str, Any], actual: dict[str, Any]) -> dict[str, Any]:
    decode_stage_ran = "decode" in actual
    metadata_rows = {row["id"]: normalise_metadata(row) for row in actual.get("metadata", {}).get("results", [])}
    decode_rows = {row["id"]: row for row in actual.get("decode", {}).get("results", [])}
    tracks = {row["relativePath"]: row for row in actual.get("scan", {}).get("tracks", [])}
    fixtures_out: list[dict[str, Any]] = []
    field_totals: Counter[str] = Counter()
    field_failures: Counter[str] = Counter()

    for fixture in manifest["fixtures"]:
        fixture_id = fixture["id"]
        expected_result = fixture["expectedResult"]
        metadata = metadata_rows.get(fixture_id)
        decode = decode_rows.get(fixture_id)
        track = tracks.get(fixture["path"])
        checks: list[dict[str, Any]] = []
        problems: list[str] = []

        if metadata is None:
            problems.append("missing direct metadata result")
        else:
            if metadata.get("sha256") != fixture["sha256"]:
                problems.append("fixture SHA-256 differs on device")
            if expected_result in {"PLAY", "PLAY_WITH_WARNINGS", "INDEX_ONLY"} and not metadata.get("success"):
                problems.append(f"metadata failed: {metadata.get('errorDetail')}")
            if expected_result == "UNSUPPORTED" and metadata.get("success"):
                problems.append("unsupported fixture was accepted by metadata probe")
            if fixture["group"] != "malformed" and metadata.get("success"):
                checks = check_fields(fixture, metadata)
                for check in checks:
                    field_totals[check["field"]] += 1
                    if not check["pass"]:
                        field_failures[check["field"]] += 1
                if fixture.get("expected", {}).get("hasArtwork") and not metadata.get("artworkValid", False):
                    problems.append("embedded artwork payload is unreadable")

        actual_classification = decode.get("classification") if decode else None
        if (actual_classification == "PLAY" and metadata and metadata.get("hasArtwork") and
                metadata.get("artworkValid") is False):
            actual_classification = "PLAY_WITH_WARNINGS"
        if decode_stage_ran and decode is None:
            problems.append("missing decode result")
        elif decode_stage_ran and actual_classification != expected_result:
            problems.append(f"classification expected {expected_result}, actual {actual_classification}")
        if decode and decode.get("decodedFrames", 0) > 0 and not decode.get("finitePcm", False):
            problems.append("decoder produced non-finite float PCM")
        if expected_result in {"PLAY", "PLAY_WITH_WARNINGS"} and decode and not decode.get("seekSucceeded", False):
            # The 1 ms fixture has no meaningful midpoint seek target.
            if fixture_id != "flac_extremely_short":
                problems.append("seek did not succeed")

        should_store = Path(fixture["path"]).suffix.lower() in {
            ".mp3", ".flac", ".wav", ".wave", ".ogg", ".oga", ".opus",
            ".m4a", ".m4r", ".aac", ".alac", ".aif", ".aiff", ".aifc",
        } and fixture["sizeBytes"] > 0
        if should_store and actual.get("scan") is not None and track is None:
            problems.append("scanner/database row missing")
        if not should_store and track is not None:
            problems.append("scanner stored a file outside discovery contract")

        failed_checks = [check for check in checks if not check["pass"]]
        if failed_checks:
            problems.append(f"{len(failed_checks)} metadata field mismatch(es)")
        fixtures_out.append({
            "id": fixture_id,
            "path": fixture["path"],
            "group": fixture["group"],
            "expectedResult": expected_result,
            "actualResult": actual_classification,
            "metadataSuccess": metadata.get("success") if metadata else None,
            "decodedFrames": decode.get("decodedFrames") if decode else None,
            "databaseStored": track is not None,
            "fieldChecks": checks,
            "problems": problems,
            "pass": not problems,
        })

    resources_before = actual.get("resourcesBefore", {})
    resources_after = actual.get("resourcesAfter", {})
    resource_deltas = {
        key: resources_after.get(key, 0) - resources_before.get(key, 0)
        for key in sorted(set(resources_before) | set(resources_after))
        if isinstance(resources_before.get(key), (int, float)) and isinstance(resources_after.get(key), (int, float))
    }
    passed = sum(1 for row in fixtures_out if row["pass"])
    return {
        "schemaVersion": 1,
        "complete": bool(actual.get("complete")),
        "summary": {
            "fixtures": len(fixtures_out),
            "passed": passed,
            "failed": len(fixtures_out) - passed,
            "metadataFieldChecks": sum(field_totals.values()),
            "metadataFieldFailures": sum(field_failures.values()),
        },
        "metadataFields": {
            field: {"checks": field_totals[field], "failures": field_failures[field],
                    "passed": field_totals[field] - field_failures[field]}
            for field in METADATA_FIELDS if field_totals[field]
        },
        "resourceDeltas": resource_deltas,
        "nativeMetadataProfile": actual.get("metadata", {}).get("nativeProfile"),
        "scan": actual.get("scan"),
        "fixtures": fixtures_out,
    }


def cell(value: Any) -> str:
    if value is None:
        return "—"
    return str(value).replace("|", "\\|").replace("\n", " ")


def markdown(report: dict[str, Any]) -> str:
    summary = report["summary"]
    out = [
        "# Y2Player media regression report", "",
        f"Overall: **{summary['passed']}/{summary['fixtures']} fixtures passed**; "
        f"{summary['metadataFieldFailures']}/{summary['metadataFieldChecks']} metadata field checks failed.", "",
        "## Compatibility and playback matrix", "",
        "| Fixture | Group | Expected | Actual | Metadata | PCM frames | DB | Result |",
        "|---|---|---|---|---:|---:|---:|---|",
    ]
    for row in report["fixtures"]:
        out.append("| " + " | ".join([
            cell(row["id"]), cell(row["group"]), cell(row["expectedResult"]), cell(row["actualResult"]),
            "PASS" if row["metadataSuccess"] else "FAIL", cell(row["decodedFrames"]),
            "YES" if row["databaseStored"] else "NO", "PASS" if row["pass"] else "FAIL",
        ]) + " |")

    out.extend(["", "## Metadata correctness matrix", "",
                "| Field | Checks | Passed | Failed |", "|---|---:|---:|---:|"])
    for field, value in report["metadataFields"].items():
        out.append(f"| {field} | {value['checks']} | {value['passed']} | {value['failures']} |")

    out.extend(["", "## Malformed-file matrix", "",
                "| Fixture | Expected | Actual | Result | Problems |", "|---|---|---|---|---|"])
    for row in report["fixtures"]:
        if row["group"] != "malformed":
            continue
        out.append(f"| {cell(row['id'])} | {row['expectedResult']} | {cell(row['actualResult'])} | "
                   f"{'PASS' if row['pass'] else 'FAIL'} | {cell('; '.join(row['problems']))} |")

    failures = [row for row in report["fixtures"] if not row["pass"]]
    out.extend(["", "## Confirmed mismatches", ""])
    if not failures:
        out.append("None.")
    else:
        for row in failures:
            out.append(f"- `{row['id']}`: " + "; ".join(row["problems"]))
            for check in row["fieldChecks"]:
                if not check["pass"]:
                    out.append(f"  - {check['field']}: expected `{cell(check['expected'])}`, actual `{cell(check['actual'])}`")

    out.extend(["", "## Resource deltas", "",
                "| Counter | Delta |", "|---|---:|"])
    for key, value in report["resourceDeltas"].items():
        out.append(f"| {key} | {value} |")

    profile = report.get("nativeMetadataProfile") or {}
    if profile:
        out.extend(["", "## Native metadata performance", "",
                    "| Phase | Count | Total µs | Average µs | Maximum µs |", "|---|---:|---:|---:|---:|"])
        for phase in profile.get("phases", []):
            count = phase.get("count", 0)
            total = phase.get("totalUs", 0)
            average = total // count if count else 0
            out.append(f"| {phase.get('phase')} | {count} | {total} | {average} | {phase.get('maximumUs', 0)} |")
    return "\n".join(out) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--actual", required=True)
    parser.add_argument("--json-report", required=True)
    parser.add_argument("--markdown-report", required=True)
    args = parser.parse_args()
    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    actual = json.loads(Path(args.actual).read_text(encoding="utf-8"))
    report = compare(manifest, actual)
    Path(args.json_report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    Path(args.markdown_report).write_text(markdown(report), encoding="utf-8")
    print(json.dumps(report["summary"], sort_keys=True))
    raise SystemExit(0 if report["summary"]["failed"] == 0 else 1)


if __name__ == "__main__":
    main()
