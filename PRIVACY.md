# Privacy

EmotiBit Osc Mobile has no servers, no accounts, no analytics, no ads and no crash reporting.

**What leaves the phone:** nothing goes to the internet. The app only talks to your EmotiBit on the local Wi-Fi network (the phone's hotspot or a shared Wi-Fi):

- UDP port 3131 (discovery and keep-alive)
- the UDP data port (3132 by default)
- the TCP control port (3133 by default)

**What is stored on the phone:**

- the EmotiBit you chose to auto-connect to (device ID);
- a session log (`Android/data/<app id>/files/logs/emotibit-YYYY-MM-DD.jsonl`) with connection events, notes and markers you sent, and time-sync round-trip times. It contains no sensor data. You can share it from the app; it is never sent automatically. Uninstalling the app deletes it.

Live sensor data is kept in memory only for plotting and is discarded. Recordings are written by EmotiBit to its own SD card, not by the app. Android cloud backup and device-to-device transfer are disabled for the app.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Required by Android to open any network socket, including local Wi-Fi sockets to EmotiBit. |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Find the hotspot / Wi-Fi network to search for EmotiBit on. |
| `CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE` | Keep a low-latency Wi-Fi lock so time-sync replies reach EmotiBit within its 100 ms window. Android also requires one of these for a "connected device" foreground service. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Keep the connection, auto-reconnect and safe stop running with the screen off. |
| `WAKE_LOCK` | Keep the CPU awake for the connection while the screen is off. |
| `POST_NOTIFICATIONS` | Show the ongoing status notification and "back in range" / "recording stopped" alerts. |

The app asks for no location, camera, microphone, contacts, phone, SMS or storage permissions.
