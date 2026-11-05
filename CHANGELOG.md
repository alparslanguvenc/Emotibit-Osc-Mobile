# Changelog

## 1.0.0 (2026-11-05)

First public release.

- Discover and connect to EmotiBit over a phone hotspot or shared 2.4 GHz Wi-Fi (broadcast, unicast sweep, manual IP).
- Start / stop recording with timestamped file names, like EmotiBit Oscilloscope.
- Answers EmotiBit time syncs within its 100 ms window and verifies the echoed `RD → TL → AK` packets with EmotiBit DataParser's rule.
- Background foreground service with automatic reconnection when the device comes back in range.
- Safe stop: collects fresh time syncs before ending a recording; can be requested while out of range.
- Live signal plots: EDA, PPG (IR / red / green), heart rate, temperature, accelerometer, gyroscope, magnetometer.
- Sync marker (screen flash + beep + note in the EmotiBit file) for video alignment; free-text notes.
- Wi-Fi-off and hibernate commands; battery, mode and data-rate status.
- Shareable JSON Lines session log.
- English and Turkish UI, light and dark theme.
- Tools: EmotiBit simulator and SD-card time-sync checker.
