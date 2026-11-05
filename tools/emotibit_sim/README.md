# EmotiBit simulator and recording checker

Python 3 standard library only.

## `check_sd_file.py`: time syncs in a recording

Scans a raw EmotiBit SD-card recording (the unparsed `.csv`) with EmotiBit DataParser's time-sync rule: an `RD` requesting `TL`, then a `TL`, then an `AK` for that `RD`.

```bash
python3 tools/emotibit_sim/check_sd_file.py 2026-11-05_10-15-30-123456.csv
python3 tools/emotibit_sim/check_sd_file.py recording.csv --json --edge-seconds 60
```

Reports the number of syncs, how many fall near the start and the end (`--edge-seconds`, default 120), round-trip times, `UN` notes (including `SYNC_MARKER_n`) and `DO` data-overflow packets.

Exit codes: `0` = syncs near both ends, `1` = fewer than 2 syncs (DataParser cannot correct drift), `2` = syncs near one end only.

## `emotibit_sim.py`: test without hardware

Reproduces what a host sees from EmotiBit firmware 1.14.x:

- discovery (`HE` → `HH`), connection (`EC` → TCP → `PO`), keep-alive (`PN`/`PO`) with a 10 s timeout;
- time syncs every ~5.1 s with the firmware's 100 ms reply window, logged to the "SD card" and echoed in the data stream;
- recording (`RB`/`RE`, `<timestamp>.csv` + `_info.json`), notes (`UN`), Wi-Fi off (`MO`), hibernate (`MH`);
- signals at stock rates: EDA 15 Hz, PPG and IMU 25 Hz, temperature 7.5 Hz, heart rate.

To try the app without an EmotiBit, run the simulator on a laptop that is on the phone's hotspot:

```bash
python3 tools/emotibit_sim/emotibit_sim.py --verbose
```

The app finds `MD-V5-SIM0001`. Recordings go to `sim_sd/`.

Useful options: `--offline 60:180` (leave Wi-Fi range from 60 s to 180 s after start, while recording continues), `--wifi-off-seconds 30` (after `MO`, simulate a button press after 30 s), `--sync-interval`, `--connection-timeout`, `--device-id`, `--adv-port`.

The protocol tests in `protocol/` start this simulator for their end-to-end scenarios.
