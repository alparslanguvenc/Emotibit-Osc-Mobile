#!/usr/bin/env python3
"""Check the time syncs in a raw EmotiBit SD-card recording (the .csv written by the device).

Applies the rule EmotiBit DataParser uses (ofxEmotiBit/EmotiBitDataParser/src/ofApp.cpp,
PARSING_TIMESTAMPS): a time sync is an RD packet requesting TL, then a TL packet, then an AK whose
first payload element is that RD's packet number. DataParser needs at least 2 syncs and corrects clock
drift best when syncs exist near both the start and the end of the recording.

Works on real EmotiBit files as well as on files produced by emotibit_sim.py.

Usage: check_sd_file.py RECORDING.csv [--json] [--edge-seconds 120]
Exit code: 0 = syncs at start and end, 1 = fewer than 2 syncs, 2 = syncs only on one side.
"""
from __future__ import annotations

import argparse
import json
import sys


def analyse(path: str, edge_seconds: float) -> dict:
    syncs = []
    notes = []
    first_ts = None
    last_ts = None
    rd_number = -1
    rd_ts = 0
    tl_ts = 0
    tl_sent = ""
    pending = False
    malformed = 0
    overflow = 0
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            parts = raw.rstrip("\r\n").split(",")
            if len(parts) < 6:
                if raw.strip():
                    malformed += 1
                continue
            try:
                ts = int(parts[0])
                pn = int(parts[1])
            except ValueError:
                malformed += 1
                continue
            tag = parts[3]
            first_ts = ts if first_ts is None else min(first_ts, ts)
            last_ts = ts if last_ts is None else max(last_ts, ts)
            payload = parts[6:]
            if tag == "RD" and "TL" in payload:
                rd_number, rd_ts, tl_ts, tl_sent, pending = pn, ts, 0, "", True
            elif tag == "TL" and pending:
                tl_ts = ts
                tl_sent = payload[0] if payload else ""
            elif tag == "AK" and pending and payload:
                try:
                    ack_for = int(payload[0])
                except ValueError:
                    continue
                if ack_for == rd_number and tl_ts:
                    syncs.append({"emotibitMs": rd_ts, "rttMs": tl_ts - rd_ts, "hostTime": tl_sent})
                    pending = False
                elif ack_for != rd_number:
                    tl_ts, tl_sent = 0, ""
            elif tag == "UN":
                notes.append({"emotibitMs": ts, "payload": payload})
            elif tag == "DO":
                overflow += 1
    result = {
        "file": path,
        "emotibitStartMs": first_ts,
        "emotibitEndMs": last_ts,
        "durationS": None if first_ts is None else round((last_ts - first_ts) / 1000, 1),
        "timeSyncs": len(syncs),
        "syncs": syncs,
        "notes": notes,
        "malformedLines": malformed,
        "dataOverflowPackets": overflow,
    }
    if first_ts is not None and syncs:
        edge = edge_seconds * 1000
        result["syncsNearStart"] = sum(1 for s in syncs if s["emotibitMs"] - first_ts <= edge)
        result["syncsNearEnd"] = sum(1 for s in syncs if last_ts - s["emotibitMs"] <= edge)
    else:
        result["syncsNearStart"] = result["syncsNearEnd"] = 0
    return result


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--edge-seconds", type=float, default=120)
    a = ap.parse_args()
    r = analyse(a.file, a.edge_seconds)
    if a.json:
        print(json.dumps(r, ensure_ascii=False, indent=2))
    else:
        print(f"File:             {r['file']}")
        print(f"Duration:         {r['durationS']} s (EmotiBit clock)")
        print(f"Time syncs:       {r['timeSyncs']}  (near start: {r['syncsNearStart']}, near end: {r['syncsNearEnd']})")
        if r["syncs"]:
            rtts = [s["rttMs"] for s in r["syncs"]]
            print(f"Round trip (ms):  min {min(rtts)}, max {max(rtts)}")
        print(f"Notes (UN):       {len(r['notes'])}")
        for n in r["notes"]:
            print(f"  {n['emotibitMs']:>10} ms  {','.join(n['payload'])}")
        if r["dataOverflowPackets"]:
            print(f"Data overflow:    {r['dataOverflowPackets']} DO packets")
    if r["timeSyncs"] < 2:
        return 1
    if r["syncsNearStart"] == 0 or r["syncsNearEnd"] == 0:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
