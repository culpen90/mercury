# Release checklist

Mercury releases a signed Android APK and AAB. The `versionName` in
`app/build.gradle.kts` is the source of truth for the release version. Optional
Relay features may require coordinated Mercury host-plugin and router releases;
nothing here changes Hermes itself.

## Before cutting a release

1. `main` is green in `Android CI` (`.github/workflows/android-ci.yml`).
2. The vendored Relay protocol vectors match the pinned public plugin release
   (`tools/check-relay-vectors.sh`; CI runs it on every PR).
3. `PRIVACY.md` and `SECURITY.md` still describe what the build does — new
   permissions, storage, or network surfaces need a matching paragraph.
4. Bump `versionName = "X.Y.Z"` in `app/build.gradle.kts` in one PR.
   `versionCode` is CI-driven from the workflow run number; do not hand-edit it.

## Android

Gates (run locally, also enforced by CI):

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug validateDebugScreenshotTest
./gradlew :shared:mercury-core:check
```

Release build:

- Merging the version bump to `main` runs `.github/workflows/release.yml`,
  which re-runs the unit, lint, assemble, and shared-core gates, builds a
  signed APK + AAB with the `UPLOAD_KEYSTORE_*` / `UPLOAD_KEY_*` repository
  secrets, and verifies the APK signature, package, version, and production
  manifest flags. The release workflow also runs screenshot validation, the
  fake Hermes contract rig, and Relay vector checks.
- The workflow creates a **draft** GitHub release `v<versionName>` bound to
  the source commit, containing `mercury-<versionName>.apk` and `SHA256SUMS`.
  The AAB is attached as a workflow artifact. An existing tag or release is
  never overwritten; bump the version for a new candidate.
- Download the draft APK and checksums, verify them, perform the device check
  below, then publish the draft as the latest stable release. Confirm the tag
  resolves to the workflow's source commit before publication.
- The Play upload is manual: run the `Release` workflow with
  `publish_to_play` checked once the `PLAY_SERVICE_ACCOUNT_JSON` secret
  exists. It targets the internal track for package
  `com.unsupportedpastels.hermesandroid` (kept for Play continuity).
- Release builds are not minified (`isMinifyEnabled = false`); enabling R8
  requires reviewing `app/proguard-rules.pro` first.
- Device check before publishing: install the signed APK on a phone and, if
  available, a foldable; connect, send a prompt, background the app during a
  turn, and confirm the completion notification opens the right session. Use
  a disposable emulator when a physical device is unavailable or its installed
  app uses a different signing key, and record that limitation in the release.
  Never uninstall an authenticated installation just to bypass a key mismatch.

## Signing continuity

This repository's releases use a dedicated production signing key, separate
from the original upstream project's key, debug keys, and Play signing keys.
Keep the same key for future updates and retain a private backup outside Git.
Installing over a build signed by another key requires a separate migration;
Android cannot perform an in-place update across those keys. Do not replace
the key to resolve a build or CI configuration problem.

## After the release

- Confirm the GitHub release page lists the APK and that
  `apksigner verify --verbose` passes on the downloaded file.
- Run `shasum -a 256 -c SHA256SUMS` alongside the downloaded APK (or
  `sha256sum -c SHA256SUMS` on Linux), and compare its certificate SHA-256
  fingerprint with the published release notes.
- Update the placeholder version in `.github/ISSUE_TEMPLATE/bug_report.yml`.
