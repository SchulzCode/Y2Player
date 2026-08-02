#!/usr/bin/env python3
"""Offline decoder for Y2 /proc/audio runtime captures.

Consumes a session directory produced by tools/collect-afe-runtime.ps1 and emits
a state-by-state register table, per-transition diffs, decoded bit fields, and a
pass/fail check of the phase-2 static-analysis predictions.

Purely offline. Reads files, touches no device.

Usage:
    python3 tools/analyze-afe-captures.py out/afe-runtime/2026-07-30_120000
    python3 tools/analyze-afe-captures.py <session> --markdown report.md
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

# Verified in both kernel (AudDrv_SampleRateIndexConvert) and HAL
# (AudioPlatformDevice::GetDLFrequency). Indices 3, 7 and >10 are gaps.
RATE_INDEX_HZ = {
    0: 8000, 1: 11025, 2: 12000, 3: None, 4: 16000, 5: 22050,
    6: 24000, 7: None, 8: 32000, 9: 44100, 10: 48000,
}

# Verified in AudioDigitalControl::SetI2SDacOut, AFE_ADDA_DL_SRC2_CON0[31:28].
SRC_CODE_HZ = {
    0: 8000, 1: 11025, 2: 12000, 3: 16000, 4: 22050,
    5: 24000, 6: 32000, 7: 44100, 8: 48000,
}

# Registers we always want in the comparison table.
KEY_REGISTERS = [
    "AUDIO_TOP_CON0", "AUDIO_TOP_CON1",
    "AFE_DAC_CON0", "AFE_DAC_CON1",
    "AFE_I2S_CON", "AFE_I2S_CON1", "AFE_I2S_CON2", "AFE_I2S_CON3",
    "AFE_CONN4",
    "AFE_DL1_BASE", "AFE_DL1_CUR", "AFE_DL1_END",
    "AFE_ADDA_DL_SRC2_CON0", "AFE_ADDA_DL_SRC2_CON1",
    "AFE_ADDA_UL_DL_CON0", "AFE_ADDA_TOP_CON0",
    "AFE_IRQ_MCU_CON", "AFE_IRQ_STATUS", "AFE_IRQ_CNT1", "AFE_IRQ_CNT2",
    "AFE_MEMIF_PBUF_SIZE",
    "AFE_ASRC_CON0", "AFE_ASRC_CON13", "AFE_ASRC_CON14",
    "AFE_ASRC_CON15", "AFE_ASRC_CON16", "AFE_ASRC_CON17", "AFE_ASRC_CON19",
]

LINE_RE = re.compile(r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(0x[0-9a-fA-F]+|\d+)\s*$")

def parse_proc_audio(text: str) -> dict[str, int]:
    """Parse 'NAME = 0xVALUE' lines out of a /proc/audio dump."""
    out: dict[str, int] = {}
    for raw in text.splitlines():
        if raw.startswith("#"):
            continue
        m = LINE_RE.match(raw)
        if not m:
            continue
        name, val = m.group(1), m.group(2)
        try:
            out[name] = int(val, 16) if val.lower().startswith("0x") else int(val, 10)
        except ValueError:
            continue
    return out

def rate_from_index(idx: int | None) -> str:
    if idx is None:
        return "?"
    hz = RATE_INDEX_HZ.get(idx)
    if hz is None:
        return f"idx {idx} = RESERVED/GAP"
    return f"idx {idx} = {hz} Hz"

def decode_i2s_con1(v: int) -> dict[str, str]:
    idx = (v >> 8) & 0xF
    wlen = (v >> 1) & 1
    return {
        "raw": f"0x{v:08x}",
        "bit0  I2S_EN": "enabled" if v & 1 else "disabled",
        "bit1  I2S_WLEN": f"{wlen}  ({'wide / 32-bit (per MTK convention)' if wlen else 'narrow / 16-bit (per MTK convention)'})",
        "bit2  I2S_SRC": "slave" if (v >> 2) & 1 else "master",
        "bit3  I2S_FMT": "I2S" if (v >> 3) & 1 else "EIAJ",
        "bit5  INV_LRCK": "inverted" if (v >> 5) & 1 else "normal",
        "bits[11:8] RATE": rate_from_index(idx),
        "bit31 LR_SWAP": "swapped" if (v >> 31) & 1 else "normal",
    }

def decode_irq_mcu_con(v: int) -> dict[str, str]:
    return {
        "raw": f"0x{v:08x}",
        "bit0  IRQ1_EN": "on" if v & 1 else "off",
        "bit1  IRQ2_EN": "on" if (v >> 1) & 1 else "off",
        "bits[7:4]  IRQ1 rate": rate_from_index((v >> 4) & 0xF),
        "bits[11:8] IRQ2 rate": rate_from_index((v >> 8) & 0xF),
    }

def decode_dl_src2_con0(v: int) -> dict[str, str]:
    code = (v >> 28) & 0xF
    hz = SRC_CODE_HZ.get(code)
    return {
        "raw": f"0x{v:08x}",
        "bit0  SRC_EN": "enabled" if v & 1 else "disabled",
        "bits[31:28] out rate": f"code {code} = {hz} Hz" if hz is not None else f"code {code} = UNMAPPED",
    }

def decode_conn4(v: int) -> dict[str, str]:
    return {
        "raw": f"0x{v:08x}",
        "bit30 ASRC route": "ASRC BYPASSED (bit set)" if (v >> 30) & 1 else "ASRC IN PATH (bit clear)",
    }

DECODERS = {
    "AFE_I2S_CON1": decode_i2s_con1,
    "AFE_I2S_CON2": decode_i2s_con1,
    "AFE_IRQ_MCU_CON": decode_irq_mcu_con,
    "AFE_ADDA_DL_SRC2_CON0": decode_dl_src2_con0,
    "AFE_CONN4": decode_conn4,
}

def active_i2s_register(regs: dict[str, int]) -> tuple[str | None, int | None]:
    """Return whichever I2S output register is actually enabled.

    Runtime capture 2026-07-30 established that the Y2 drives the CS43131 from
    the SECOND I2S output port (AFE_I2S_CON3 @0x4c), not the primary I2S DAC-out
    port (AFE_I2S_CON1 @0x34) that the function name SetI2SDacOut suggests.
    Prefer whichever has its enable bit set so this works on either wiring.
    """
    for name in ("AFE_I2S_CON3", "AFE_I2S_CON1", "AFE_I2S_CON2"):
        v = regs.get(name)
        if v is not None and (v & 1):
            return name, v
    for name in ("AFE_I2S_CON3", "AFE_I2S_CON1"):
        if regs.get(name):
            return name, regs[name]
    return None, None

def check_predictions(regs: dict[str, int]) -> list[tuple[str, str, str, bool]]:
    """Phase-2 hypotheses for wired 44.1 kHz playback. Returns (name, expected, actual, ok)."""
    rows = []

    name, v = active_i2s_register(regs)
    if v is None:
        rows.append(("active I2S output reg", "one port enabled", "NONE ENABLED", False))
    else:
        # Structural check: enable set, I2S format, rate index 9 (44.1 kHz).
        # WLEN is reported, not asserted: the static prediction of 0 was wrong.
        ok = (v & 1) == 1 and ((v >> 3) & 1) == 1 and ((v >> 8) & 0xF) == 9
        rows.append((f"{name} (active port)", "EN=1, FMT=1, idx=9", f"0x{v:08x}", ok))
        rows.append((f"{name} I2S_WLEN", "reported, not asserted",
                     f"{(v >> 1) & 1}", True))

    v = regs.get("AFE_ADDA_DL_SRC2_CON0")
    if v is None:
        rows.append(("AFE_ADDA_DL_SRC2_CON0", "0x73001803", "ABSENT", False))
    else:
        ok = ((v >> 28) & 0xF) == 7 and (v & 1) == 1
        rows.append(("AFE_ADDA_DL_SRC2_CON0", "0x73001803 (code=7, EN=1)", f"0x{v:08x}", ok))

    v = regs.get("AFE_ADDA_DL_SRC2_CON1")
    if v is None:
        rows.append(("AFE_ADDA_DL_SRC2_CON1", "0x4F7F0000", "ABSENT", False))
    else:
        rows.append(("AFE_ADDA_DL_SRC2_CON1", "0x4F7F0000", f"0x{v:08x}", v == 0x4F7F0000))

    v = regs.get("AFE_IRQ_MCU_CON")
    if v is None:
        rows.append(("AFE_IRQ_MCU_CON[7:4]", "0x9", "ABSENT", False))
    else:
        got = (v >> 4) & 0xF
        rows.append(("AFE_IRQ_MCU_CON[7:4]", "0x9 (44100 Hz)", f"0x{got:x}", got == 9))

    b, e = regs.get("AFE_DL1_BASE"), regs.get("AFE_DL1_END")
    if b is None or e is None:
        rows.append(("AFE_DL1_BASE/END", "both non-zero", "ABSENT", False))
    else:
        rows.append(("AFE_DL1_BASE/END", "both non-zero",
                     f"0x{b:08x} / 0x{e:08x}", b != 0 and e != 0))
    return rows

def sha256(p: Path) -> str:
    h = hashlib.sha256()
    h.update(p.read_bytes())
    return h.hexdigest()

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("session", type=Path, help="session directory from collect-afe-runtime.ps1")
    ap.add_argument("--markdown", type=Path, default=None, help="write a markdown report here")
    args = ap.parse_args()

    session: Path = args.session
    if not session.is_dir():
        print(f"not a directory: {session}", file=sys.stderr)
        return 2

    # discover states
    states: dict[str, dict[str, int]] = {}
    raw_files: dict[str, Path] = {}
    for d in sorted(session.iterdir()):
        if not d.is_dir():
            continue
        pa = d / "proc-audio.txt"
        if pa.is_file():
            regs = parse_proc_audio(pa.read_text(errors="replace"))
            if regs:
                states[d.name] = regs
                raw_files[d.name] = pa

    if not states:
        print("No parseable proc-audio.txt found. Was /proc/audio readable?", file=sys.stderr)
        return 1

    meta = {}
    mp = session / "manifest.json"
    if mp.is_file():
        try:
            meta = json.loads(mp.read_text(errors="replace"))
        except Exception:
            meta = {}

    out: list[str] = []
    w = out.append

    w("# Y2 AFE runtime capture analysis\n")
    w(f"Session: `{session.name}`  ")
    w(f"States parsed: {len(states)}  ")
    w(f"Registers per state: {min(len(v) for v in states.values())}–{max(len(v) for v in states.values())}\n")

    # capture integrity
    w("## Capture integrity\n")
    w("| State | proc-audio SHA-256 | registers |")
    w("| --- | --- | ---: |")
    for name, regs in states.items():
        w(f"| {name} | `{sha256(raw_files[name])[:32]}…` | {len(regs)} |")
    w("")

    # declared state metadata
    if meta.get("States"):
        w("## Declared state metadata\n")
        w("| State | Route | Source file | Format | Device time | Notes |")
        w("| --- | --- | --- | --- | --- | --- |")
        for s in meta["States"]:
            if s.get("Skipped"):
                continue
            w("| {} | {} | `{}` | {} | {} | {} |".format(
                s.get("State", ""), s.get("Route", ""), s.get("SourceFile", ""),
                s.get("SourceFormat", ""), s.get("DeviceDate", ""), s.get("Notes", "")))
        w("")

    # main comparison table
    w("## State-by-state register comparison\n")
    names = list(states.keys())
    present = [r for r in KEY_REGISTERS if any(r in states[n] for n in names)]
    w("| Register | " + " | ".join(names) + " |")
    w("| --- | " + " | ".join("---" for _ in names) + " |")
    for reg in present:
        cells = []
        for n in names:
            v = states[n].get(reg)
            cells.append(f"`0x{v:08x}`" if v is not None else "—")
        w(f"| `{reg}` | " + " | ".join(cells) + " |")
    w("")

    # registers seen but not in our key list
    extra = sorted({k for n in names for k in states[n]} - set(KEY_REGISTERS))
    varying_extra = [r for r in extra
                     if len({states[n].get(r) for n in names if r in states[n]}) > 1]
    if varying_extra:
        w("### Registers outside the key set that VARY across states\n")
        w("These were not predicted by the static analysis and deserve attention.\n")
        w("| Register | " + " | ".join(names) + " |")
        w("| --- | " + " | ".join("---" for _ in names) + " |")
        for reg in varying_extra:
            cells = []
            for n in names:
                v = states[n].get(reg)
                cells.append(f"`0x{v:08x}`" if v is not None else "—")
            w(f"| `{reg}` | " + " | ".join(cells) + " |")
        w("")

    # decoded fields per state
    w("## Decoded bit fields\n")
    for n in names:
        w(f"### {n}\n")
        for reg, fn in DECODERS.items():
            v = states[n].get(reg)
            if v is None:
                continue
            w(f"**{reg}**\n")
            for k, val in fn(v).items():
                w(f"- {k}: {val}")
            w("")

    # predictions
    w("## Prediction check (phase-2 hypotheses, wired 44.1 kHz)\n")
    target = next((n for n in names if "44k1" in n or "44100" in n), None)
    if target is None:
        w("_No 44.1 kHz playback state captured; predictions not evaluated._\n")
    else:
        w(f"Evaluated against state `{target}`.\n")
        w("| Prediction | Expected | Actual | Result |")
        w("| --- | --- | --- | :-: |")
        allok = True
        for nm, exp, act, ok in check_predictions(states[target]):
            allok &= ok
            w(f"| {nm} | `{exp}` | `{act}` | {'PASS' if ok else '**FAIL**'} |")
        w("")
        w(f"**Overall: {'all predictions confirmed' if allok else 'AT LEAST ONE PREDICTION FAILED — static model needs revision'}**\n")

    # diffs
    w("## Transition diffs\n")

    def diff(a: str, b: str) -> None:
        if a not in states or b not in states:
            return
        ra, rb = states[a], states[b]
        keys = sorted(set(ra) | set(rb))
        rows = [(k, ra.get(k), rb.get(k)) for k in keys if ra.get(k) != rb.get(k)]
        w(f"### `{a}` -> `{b}`\n")
        if not rows:
            w("_No register changed._\n")
            return
        w("| Register | before | after |")
        w("| --- | --- | --- |")
        for k, x, y in rows:
            fx = f"`0x{x:08x}`" if x is not None else "—"
            fy = f"`0x{y:08x}`" if y is not None else "—"
            w(f"| `{k}` | {fx} | {fy} |")
        w("")

    def find(*frags: str) -> str | None:
        for n in names:
            if any(f in n for f in frags):
                return n
        return None

    pairs = [
        (find("01-idle"), find("03-wired-44k1")),
        (find("02-wired-idle"), find("03-wired-44k1")),
        (find("06-paused"), find("07-resumed")),
        (find("03-wired-44k1"), find("06-paused")),
        (find("03-wired-44k1"), find("08-stopped")),
        (find("03-wired-44k1"), find("04-wired-48k")),
        (find("03-wired-44k1"), find("05-bluetooth")),
        (find("03-wired-44k1"), find("09-wired-disconnect")),
    ]
    for a, b in pairs:
        if a and b and a != b:
            diff(a, b)

    # word-length invariance
    w("## I2S_WLEN invariance check\n")
    w("Evaluated on whichever I2S output port is live in each state.\n")
    w("| State | port | value | EN | WLEN | rate idx |")
    w("| --- | --- | --- | :-: | :-: | --- |")
    wlens = set()
    for n in names:
        reg, v = active_i2s_register(states[n])
        if v is None:
            continue
        wl = (v >> 1) & 1
        wlens.add(wl)
        w(f"| {n} | `{reg}` | `0x{v:08x}` | {v & 1} | {wl} | {rate_from_index((v >> 8) & 0xF)} |")
    w("")
    if len(wlens) == 1:
        wl = wlens.pop()
        w(f"**I2S_WLEN is constant at {wl} across every captured state**, consistent with it "
          "being a hardcoded attribute rather than anything negotiated per stream.\n")
        if wl == 1:
            w("Note: the live value is **1 (wide)**, set by "
              "`AudioMTKStreamOut::Set2ndI2SOutAttribute()`. It is NOT the 0 that "
              "`AudioDigitalControl::SetI2SDacOutAttribute` writes — that function drives "
              "`AFE_I2S_CON1`, which this board leaves disabled. No word-length patch is "
              "required.\n")
    elif wlens:
        w("**I2S_WLEN VARIES across states.** This contradicts the static analysis "
          "and must be explained before any word-length patch.\n")

    text = "\n".join(out)
    if args.markdown:
        args.markdown.write_text(text, encoding="utf-8")
        print(f"wrote {args.markdown}")
    else:
        print(text)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
