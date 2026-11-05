# EmotiBit Wi-Fi host protocol, as implemented

This app replaces the host side of EmotiBit Oscilloscope (`ofxEmotiBit/EmotiBitOscilloscope/src/EmotiBitWiFiHost.cpp`). Everything below was taken from the MIT-licensed EmotiBit sources: firmware `EmotiBit_FeatherWing` 1.14.x (`EmotiBit.cpp`, `EmotiBitWiFi.cpp`), `EmotiBit_XPlat_Utils` (`EmotiBitPacket.cpp`) and `ofxEmotiBit` 1.16.x (Oscilloscope and DataParser). No firmware change is needed.

## Packets

Every packet is one line of comma-separated text ending in `\n`:

```
timestamp,packetNumber,dataLength,typeTag,protocolVersion,dataReliability[,payload...]
```

Example: `0,12,1,RB,1,100,2026-11-05_10-15-30-123456`

Host timestamps use the format `%Y-%m-%d_%H-%M-%S-%f`: local time with 6-digit microseconds.

## Session

| Step | Direction | Channel | Packet |
|---|---|---|---|
| Discovery | phone → EmotiBit | UDP 3131, subnet broadcast + /24 unicast sweep | `HE` |
| | EmotiBit → phone | UDP | `HH,…,DP,<port>,DI,<device id>`; `DP = -1` means "no host, available" |
| Connect | phone → EmotiBit | UDP 3131, every 100 ms for up to 5 s | `EC,…,CP,<control port>,DP,<data port>` |
| | EmotiBit → phone | TCP client → phone's TCP server | control channel |
| | EmotiBit → phone | UDP | `PO,…,DP,<data port>` = connected |
| Keep-alive | phone → EmotiBit | UDP 3131, every 500 ms | `PN,…,DP,<data port>`; 10 s without it drops the link on both sides |
| Data | EmotiBit → phone | UDP data port | sensor packets, `EM` (recording status, file name, power mode), `B%` |
| Time sync | EmotiBit → phone | UDP data port, every ~5.1 s while connected | `RD,…,TL,TU` |
| | phone → EmotiBit | UDP, **one packet per datagram**, within **100 ms** | `TL,…,<local time>` then `AK,…,<RD packet number>,RD` |
| Record | phone → EmotiBit | TCP | `RB,…,<local time>` opens `<local time>.csv` + `_info.json`; `RE,…` closes it |
| Note | phone → EmotiBit | TCP | `UN,…,<local time>,<text>` |
| Power | phone → EmotiBit | TCP | `MN` normal, `ML` low power, `MO` Wi-Fi off, `MH` hibernate |
| Disconnect | phone → EmotiBit | TCP | `ED` (recording continues) |

Ports: data 3132, control 3133 by default; if a port is taken, both move up by 2, as in the Oscilloscope.

## Firmware behaviour the app relies on

- **Recording survives disconnection.** Only `RE` (or an SD-card error) clears the recording flag.
- **Time syncs happen only while connected,** and every RD/TL/AK exchange is written to the SD file. DataParser needs at least 2 syncs, and corrects drift best with syncs near both ends of the file.
- **The firmware waits only 100 ms** (`MAX_SYNC_WAIT_INTERVAL`) for the `TL`/`AK` reply, and parses only the first packet of each datagram. The app replies from a dedicated thread with separate datagrams, and keeps a low-latency Wi-Fi lock.
- **Sync packets and control packets are also echoed in the data stream** (normal power mode). The app applies DataParser's rule (`RD` requesting `TL` → `TL` → `AK` whose first payload element is that `RD`'s number) to the echo. This tells it how many syncs really reached the file, and the round-trip time (`TL` time − `RD` time on EmotiBit's clock).
- **A second `RB` while recording switches to a new file.** The app never sends `RB` until an `EM` packet has confirmed the device is not recording, and never resends `RB` blindly.
- `RE` is idempotent, so the app resends it if no `EM` confirms the stop within 5 s.
- Long-pressing the EmotiBit button (or `MH`) puts the device to sleep without closing the file. The app only offers hibernate when no recording is running.

## App behaviour

- **Auto-reconnect:** the chosen device ID is remembered. Whenever it answers `HE` as available, the app connects.
- **Safe stop:** waits until 3 verified syncs exist in the current connection, then sends `RE`. While out of range it waits. If the link drops before `RE` is confirmed, the safe stop is armed again for the next connection.
- **Live plots:** each sensor packet carries N samples and one timestamp. Samples are spread back from the timestamp with a per-channel sample period, estimated from successive packets (starting from the stock rates: EDA 15 Hz, PPG/IMU 25 Hz, temperature 7.5 Hz). Aperiodic channels (`HR`, `BI`, `SA`, `SR`, `SF`) are plotted as points.

## Tests

- `protocol/src/test`: packet format, timestamp format, sync verification, live-signal buffers.
- `HostSimulatorIntegrationTest` drives the host against `tools/emotibit_sim/emotibit_sim.py`, an independent re-implementation of the firmware side. One scenario covers connect → record → leave range → safe stop requested while out of range → automatic reconnection → stop. The other covers Wi-Fi off → button press → reconnection. Both check the resulting SD file with `tools/emotibit_sim/check_sd_file.py` (syncs at both ends, markers and notes present).
