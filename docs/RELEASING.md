# Releasing

Releases are built and signed by `.github/workflows/release.yml` when a tag `v<version>` is pushed.

## One-time: signing secrets

The release key is a PKCS#12 keystore with alias `emotibitosc`. It is **not** stored in the repository. Add two repository secrets (Settings → Secrets and variables → Actions → New repository secret):

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | the keystore file, base64-encoded (`base64 -w0 emotibit-osc-mobile-release.p12`) |
| `SIGNING_PASSWORD` | the keystore/key password |

Keep an offline backup of the keystore and its password. Android only installs an update over an existing app if it is signed with the **same** key, so losing the key means users have to uninstall before installing a new build.

The workflow refuses to publish an APK whose certificate differs from the fingerprint in `README.md` / `SECURITY.md`.

## Each release

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add a section to `CHANGELOG.md` and a file `docs/release-notes/v<version>.md`.
3. Regenerate screenshots if the UI changed: `./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*' -Pscreenshots` (needs `python3`; images go to `docs/screenshots/`).
4. Commit and push, then either push a tag (`git tag v<version> && git push origin v<version>`) or run the **Release** workflow from the Actions tab (*Run workflow*, enter the version). The workflow checks that the version matches `versionName`.

## Building a signed APK locally

```bash
SIGNING_KEYSTORE=/path/to/emotibit-osc-mobile-release.p12 SIGNING_PASSWORD=... ./gradlew :app:assembleRelease
```
