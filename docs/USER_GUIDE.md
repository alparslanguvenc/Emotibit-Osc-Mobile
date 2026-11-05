# User guide

EmotiBit Osc Mobile does in the field what EmotiBit Oscilloscope does on a computer: it connects to EmotiBit, starts and stops recordings, answers time syncs and shows live signals. [Türkçe kılavuz](USER_GUIDE.tr.md)

## 1. One-time setup

### 1.1 Phone hotspot

Settings → Network → Hotspot / tethering:

- **Band: 2.4 GHz.** EmotiBit cannot join 5 GHz networks. Some phones call this option "Extend compatibility" or "Maximize compatibility".
- **Security: WPA2** (not WPA3-only).
- Keep the name (SSID) and password fixed.

### 1.2 Tell EmotiBit about the hotspot

Put the EmotiBit SD card in a computer and open `config.txt`. Add the hotspot and keep your other networks. EmotiBit tries the networks in turn.

```json
{"WifiCredentials": [
  {"ssid": "MyPhoneHotspot", "password": "hotspot-password"},
  {"ssid": "HomeWiFi", "password": "home-password"}
]}
```

### 1.3 Install the app

On the phone, open <https://github.com/alparslanguvenc/Emotibit-Osc-Mobile/releases/latest/download/emotibit-osc-mobile.apk> (always the newest version) and see [Install](../README.md#install) in the README. After the first start:

1. Allow notifications. The app uses them for "back in range" and "recording stopped" alerts.
2. On the *Control* screen, tap **Open battery settings** and set the app to **Unrestricted**. Some phones, especially Samsung and Xiaomi, otherwise stop background apps with the screen off.

## 2. In the field

1. Turn on the **hotspot**, then the EmotiBit.
2. Open the app. Your EmotiBit (for example `MD-V5-0000313`) appears under **Devices**; tap **Connect**. The choice is remembered and the app reconnects by itself from then on.
3. Tap **Start recording**. The session card turns red, shows the file name and a running timer.
4. Wait until the card says **Start syncs recorded** (about 10 s, 2 time syncs).
5. *(Optional)* **Sync marker:** point your camera at the phone and tap the button. The screen flashes white and the phone beeps. At the same moment `SYNC_MARKER_1` is written into the EmotiBit file. In your analysis, use that frame and that note as a common event to align video and biosignals.
6. Move away. Losing the connection is expected:
   - EmotiBit **keeps recording** to its SD card.
   - The app keeps searching. Whenever you come back in range it **reconnects by itself** and collects more time syncs. A notification says "EmotiBit is back in range".
7. **To end, choose Stop recording → Safe stop.**
   - Safe stop collects 3 fresh time syncs (about 15 s) and then ends the recording, so the end of the file is synced too and DataParser can correct clock drift.
   - You can press it **while out of range**. The app waits and finishes the job as soon as the device is reachable, then notifies you.
   - *Stop now* is for emergencies only: the end of the file gets no time syncs.
8. *(Optional)* **Hibernate** turns EmotiBit off. It is only available when no recording is running.

**Notes:** type in the note field and tap send. Notes go into the EmotiBit file with EmotiBit's clock, and can only be sent while connected.

### Live signals

The **Live signals** tab shows scrolling plots like the Oscilloscope: EDA, PPG (IR, red and green in separate lanes), heart rate, temperature, accelerometer, gyroscope and magnetometer.

- Turn panels on and off with the chips and pick a 5, 10, 30 or 60 s window. The y axis scales to the visible data, and the latest value is shown at the top right of each panel.
- The screen stays on while this tab is open. The tab also has the **Sync marker** button.
- Plots only move while connected and in normal mode. When the connection drops they freeze; the SD-card recording continues. They restart on reconnection.
- The plots are for checking signal quality: electrode contact, motion, PPG waveform. Analyse the SD-card file.

### Saving battery: Wi-Fi off (optional)

**Wi-Fi off** switches EmotiBit's Wi-Fi off. Recording continues and EmotiBit's battery lasts longer, but:

- no time syncs are recorded and the app cannot reach the device;
- someone has to **short-press the EmotiBit button** to turn Wi-Fi back on. The app then reconnects and you can do a safe stop.

When you are following someone, leaving Wi-Fi **on** is usually better: every time you get close the app collects extra time syncs.

## 3. After the session

1. Copy `<date-time>.csv` and `<date-time>_info.json` from the SD card.
2. *(Optional)* check the time syncs:
   ```bash
   python3 tools/emotibit_sim/check_sd_file.py 2026-11-05_10-15-30-123456.csv
   ```
   It prints the number of syncs, whether there are syncs near the start and the end, and the notes. Exit code 0 means both ends are synced.
3. Parse the file with **EmotiBit DataParser** as usual.
4. The app's session log (**Event log → share**) lists connections, losses, notes and each sync's round-trip time. Keeping it with the recording is useful. On the phone it is in `Android/data/io.github.alparslanguvenc.emotibitosc/files/logs/`.

## 4. Pilot test (before an important session)

1. **At a desk, 5 min:** connect → start → wait for 2 syncs → sync marker → safe stop. Check the file with `check_sd_file.py` and DataParser. DataParser must not warn about "fewer than 2 timesyncs".
2. **Connection loss, 10 min:** walk away with the phone for 2–3 minutes while recording, then come back. Check that the app reconnected by itself, that the file has data for the whole time, and that there are no `DO` (data overflow) packets.
3. **Remote safe stop:** press Safe stop while out of range, then come back. The recording should stop on its own.
4. **Video alignment:** film the sync marker and check that the video frame and the `SYNC_MARKER` note line up.

## 5. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| "No Wi-Fi or hotspot network" | Hotspot is off. Turn it on; the app notices within a few seconds. |
| The device never appears | Hotspot on 5 GHz or WPA3, or a wrong SSID/password in `config.txt`. If EmotiBit has joined, the phone's hotspot screen lists it with an IP address: type that IP into **EmotiBit IP address** and tap **Probe**. |
| "connected to a host" | An Oscilloscope on a laptop is still connected. Close it; the device becomes free after about 10 s. |
| "The device did not confirm the recording" | No SD card, or the card is full. |
| Time-sync count does not grow | Weak link: EmotiBit waits only 100 ms for the reply. Move closer. A safe stop warns you if no sync succeeds for a long time. |
| Connection drops with the screen off | Set the app to **Unrestricted** in the battery settings. |

## 6. Known limitations

- File names and `TL` timestamps come from the phone's **local** clock. Keep automatic date and time enabled.
- The live-plot time axis is built from packet timestamps and an estimated sample rate. It is for display only.
- No phone GPS logging yet.
