# Development Setup

The Android app and core library build on Linux or macOS with JDK 17 and the Android SDK. The Linux emulator setup below uses KVM; on macOS, install Android Studio and use its emulator images for your Mac architecture.

## Host prerequisites (Linux, Android)

- Linux x86_64
- JDK 17
- Android SDK rooted at `$ANDROID_HOME`
- KVM acceleration for x86_64 emulators

The bootstrap host now uses Android SDK command-line tools 22.0. If `/dev/kvm` is group-owned by `kvm`, add the development account once and sign out/in:

```bash
sudo usermod -aG kvm "$USER"
```

## Official Android CLI

Install or update the official CLI:

```bash
curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/install.sh | bash
android --no-metrics update
android --version
```

The project-local official skills live under `.agents/skills/`:

- `android-cli`
- `adaptive`
- `navigation-3`
- `edge-to-edge`
- `testing-setup`
- `android-intent-security`
- `agp-9-upgrade`

Refresh one by reinstalling the current published copy:

```bash
android --no-metrics skills add adaptive --project=.
```

## SDK and emulators

Required SDK packages:

```bash
android --no-metrics sdk install \
  platform-tools \
  emulator \
  platforms/android-37 \
  build-tools/37.0.0 \
  system-images/android-37.0/google_apis/x86_64 \
  system-images/android-36/google_apis_playstore/x86_64
```

Create general resizable profiles:

```bash
android --no-metrics emulator create medium_phone
android --no-metrics emulator create medium_tablet
```

The Android CLI currently exposes phone/tablet/desktop presets but not its SDK's 7.6-inch foldable hardware profile. Create the foldable AVD with the SDK manager's device catalog:

```bash
avdmanager create avd \
  -n Hermes_ZFold8_Adaptive_API36 \
  -k 'system-images;android-36;google_apis_playstore;x86_64' \
  -d '7.6in Foldable' \
  --force
```

This is a generic 7.6-inch fold-in profile with a separating hinge and an 884 x 2208 cover region. App code must never branch on that AVD name or a Samsung model name.

For a headless boot after joining the `kvm` group:

```bash
$ANDROID_HOME/emulator/emulator \
  -avd Hermes_ZFold8_Adaptive_API36 \
  -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader
```

The API-37 Google APIs image installed during bootstrap currently crashes its system compositor in headless software-rendering mode. Current AndroidX dependencies require API 37 at compile time, but API 36 remains the stable target and verified runtime-test image.

## Verification

```bash
./gradlew clean testDebugUnitTest validateDebugScreenshotTest lintDebug assembleDebug
adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W --user 0 \
  -n com.unsupportedpastels.hermesandroid/.MainActivity
```

Use `adb shell cmd device_state state 1|2|3` for closed, half-open, and open posture signals. If the image does not switch to the hardware profile's cover region, exercise the exact cover width in place with `adb shell wm size 884x2208`, then restore it with `adb shell wm size reset`.

## Rambler dictation

Mercury uses Google's official `gemini-3.5-transcribe-live` API with `SMART` transcription. Google identifies Gemini 3.5 Transcribe as the model powering Rambler in [its announcement](https://blog.google/innovation-and-ai/models-and-research/gemini-models/gemini-3-5-transcribe/).

1. Open **Settings → Voice**, enter a Gemini API key, and choose **Save key**. **Get a Gemini API key** opens [Google AI Studio](https://aistudio.google.com/api-keys). Choose a project without billing to use its free tier. This regular dictation path does not need beta enrollment or Google account login in Mercury. The composer microphone also opens manual setup on first use.
2. Optional **Google setup beta** is disabled by default and adds account login, automatic enrollment, and project/key setup. When an enrollment endpoint is configured, **Join Rambler beta** uses identity-only Google authorization and automatically confirms enrollment when the service is ready. Signup shares your verified Google email/account ID and consent version with Mercury's service hosted by Cloudflare; status checks and withdrawal use the same identity permission. Missing configuration, unavailable networking, or pending Google readiness never produces a success state. Enrollment does not grant Google's Cloud permissions. Accounts already allowed by Google can choose **Continue with Google** for setup separately.
3. Google setup requests OpenID/email and the broad `cloud-platform` permission, which Mercury uses to create or resume a separate dictation project. It verifies that billing is disabled, enables Gemini/API Keys/IAM services, and creates a service account and a Gemini-only auth key bound to it. It never attaches billing. Google identity is verified through userinfo; account/project metadata and the key use Android Keystore-backed encrypted storage, separate from Hermes credentials and excluded from backups. Play services manages authorization; Mercury does not store OAuth access tokens. Enrollment receives only a separate identity-only token, with prior Cloud grants excluded.
4. Tap the composer microphone and grant microphone permission. Stored keys work regardless of the Google setup beta flag. Before each session using a Google-managed key, Mercury rechecks the same Google account and disabled billing **before opening the microphone or transcription connection**. Audio streams directly to Google and recognized text appears in the draft. Tap Stop and wait for SMART's final corrections before pressing Send. Dictation never submits a message automatically.

Google's [terms](https://ai.google.dev/gemini-api/terms), account/region restrictions, and [free-tier quotas](https://ai.google.dev/gemini-api/docs/billing) still apply. Required terms must be accepted through Google's own surfaces. Setup stops if Google requires billing, billing is linked, or its state cannot be verified; it never silently upgrades the project. Automatic free setup is not guaranteed for every account or distribution. A live Google account must be tested separately from fake-provider tests.

Manual key entry and the [Google AI Studio](https://aistudio.google.com/api-keys) link are always available, including with Google setup beta off. Choose a project without a billing account. Manual keys have no managed-project association, so Mercury cannot check their billing state; Google's project settings and [pricing](https://ai.google.dev/gemini-api/docs/pricing) determine charges. Existing keys are never copied into the editor or restored as saved UI state.

No PixelBoard, keyboard selection, extra application, Mercury Relay plugin, or Hermes audio route is needed. Network access is required. Leaving Mercury's foreground stops capture, and each recording is limited to nine minutes. Audio is streamed from memory and never written to a recording file. The separate hands-free conversation and read-aloud controls continue to use the server's voice configuration.

Turning **Google setup beta** off hides Google onboarding and enrollment while retaining saved Gemini setup. It does not interrupt regular dictation. **Leave beta** deletes the hosted enrollment and turns off Google setup beta locally; saved keys remain. A failed local preference write is reported and can be retried using the switch. **Remove key** clears the local key and active Google connection metadata. It does not revoke Google authorization or delete Cloud resources. Encrypted project checkpoints remain so a retry can resume setup; clearing app data removes them. Manage Google authorization and Cloud resources in your Google account when no longer needed. Never add real keys, OAuth tokens, account details, generated project identifiers, or signing material to source, test fixtures, logs, or issue reports.

### Registering a build for Google authorization

Maintainers must complete Google's app branding/consent setup and register an **Android OAuth client** for each distributed package-name/signing-certificate pair. Use `com.unsupportedpastels.hermesandroid` for normal builds and `com.unsupportedpastels.hermesandroid.debug` for the optional side-by-side debug build. Register the SHA-1 of the certificate that actually signs the installed APK; local debug, GitHub release, and Play app-signing certificates can differ. Google documents the [Android registration and consent setup](https://developer.android.com/identity/authorization#setup).

This implementation uses native Google Play services `AuthorizationClient`; it embeds no OAuth client ID or client secret. Registration is configured with Google, not by committing generated credentials or private signing keys. Test audience and permission verification requirements must also be satisfied before wider distribution. Until the installed package/certificate is registered and the account is eligible, Google setup may report a configuration or authorization error; regular manual key entry remains available.

In the OAuth registration project, also enable the Cloud Resource Manager, Cloud Billing, Service Usage, IAM, and API Keys APIs. These are the management APIs used by the setup flow; enabling the Cloud Billing API permits billing-status reads and does not attach a billing account. The app separately enables Gemini, API Keys, and IAM in each dedicated dictation project. A disabled management API can fail after successful Google consent, so check the reported setup stage before changing an otherwise correct Android OAuth registration.

For data-preserving verification on a phone whose release is signed with a different key, build with `./gradlew -Pmercury.sideBySideDebug=true assembleDebug`. This installs separately as **Mercury (Debug)** with the `.debug` application ID suffix; normal builds retain their existing package ID.

### OAuth testing and production enrollment

While Mercury's Google OAuth app is **External / Testing**, maintainers must add each participating Google Account email to **Google Auth platform → Audience → Test users** before that account can authorize the Cloud permission. Google permits up to **100 listed test users**, and test authorizations expire after **seven days**, so testers may need to reconnect. Separately, unapproved sensitive or restricted scopes have a lifetime 100-new-user cap when the unverified-app warning applies. See [Google's audience and quota rules](https://support.google.com/cloud/answer/15549945?hl=en). Enabling the local beta switch does not enroll an account with Google. Regular manual-key setup does not use this OAuth admission list.

Mercury can automate signup and beta approval in a separate service; a local feature switch does not grant Google OAuth access. For Google account setup to work without manual test-user enrollment, use a production OAuth configuration with any required verification. A signup service can verify identity using only OpenID/email scopes; those identity-only scopes are exempt from Testing's prior-admission requirement. Google's documented test-user enrollment workflow uses the Console; no supported public enrollment API has been established. Google Play closed-testing groups and Firebase App Distribution tester APIs control access to app builds, separately from OAuth admission. The optional beta flag may remain after OAuth moves to production.

### Enrollment deployment

The native build reads an optional HTTPS origin in this order: an explicit `-Pmercury.ramblerBetaEndpoint` Gradle property, the `MERCURY_RAMBLER_BETA_ENDPOINT` environment variable, then an empty default. An explicitly empty Gradle property disables the endpoint even when the environment variable is set. Origins with credentials, paths, queries, or fragments are rejected.

For local builds, use either override with your deployed origin:

```sh
MERCURY_RAMBLER_BETA_ENDPOINT=https://enrollment.example ./gradlew assembleDebug
./gradlew -Pmercury.ramblerBetaEndpoint=https://enrollment.example assembleDebug
```

For signed releases, add the public origin as the repository variable **MERCURY_RAMBLER_BETA_ENDPOINT** under **Settings → Secrets and variables → Actions → Variables**. The release workflow passes that variable only to its signed APK/AAB build step. An unset repository variable keeps enrollment unconfigured; no deployment hostname or database identifier is checked into source.

Configure the isolated [enrollment Worker](../services/rambler-beta/README.md) and complete Google production readiness before enabling signup. Enrollment uses fixed versioned API paths, does not follow redirects, and shares no Hermes or Google Cloud provisioning credentials. No beta registration is silently created by enabling the local switch.
