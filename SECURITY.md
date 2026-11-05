# Security

## Verifying a download

Each release lists the APK's SHA-256 checksum (also attached as `<apk>.sha256`). All releases are signed with the same key. Its certificate SHA-256 fingerprint is:

```
7D:D0:78:99:09:BA:47:34:F1:3A:F7:DF:20:A3:1A:87:BF:5B:8D:BF:FB:E2:87:8B:24:F6:98:ED:E0:72:3F:A8
```

To check it with the Android SDK build tools:

```bash
apksigner verify --print-certs emotibit-osc-mobile-<version>.apk
```

Only install APKs from this repository's [Releases](https://github.com/alparslanguvenc/Emotibit-Osc-Mobile/releases) page.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting (**Security → Report a vulnerability**) rather than a public issue.

## Notes

- The EmotiBit Wi-Fi protocol has no authentication or encryption. Anyone on the same Wi-Fi network can see the data stream and send commands, which also applies to EmotiBit Oscilloscope. Use your own phone hotspot with a strong WPA2 password, not shared or public networks.
- Release builds are not debuggable, are minified with R8, and block cleartext HTTP. The app makes no internet requests.
