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

1. Open **Settings → Voice**, enter your own Gemini API key, and choose **Save key**. **Get a Gemini API key** opens [Google AI Studio](https://aistudio.google.com/api-keys). The composer microphone also opens manual setup on first use.
2. Tap the composer microphone and grant microphone permission. Audio streams directly to Google and recognized text appears in the draft. Tap Stop and wait for SMART's final corrections before pressing Send. Dictation never submits a message automatically.
3. To replace a key, enter the new key and choose **Save key**. Existing keys are never populated into the editor or restored as saved UI state. **Remove key** removes the local credential.

Mercury does not create Google projects, service accounts, or keys, request Google Cloud authorization, or manage billing. Choose an eligible unbilled project if you intend to use Google's free tier. You are responsible for the key's project settings, [pricing](https://ai.google.dev/gemini-api/docs/pricing), and [quotas](https://ai.google.dev/gemini-api/docs/billing). Google's [terms](https://ai.google.dev/gemini-api/terms) and account/region restrictions apply; required terms are accepted through Google's own surfaces.

No PixelBoard, keyboard selection, extra application, Mercury Relay plugin, or Hermes audio route is needed. Network access is required. Leaving Mercury's foreground stops capture, and each recording is limited to nine minutes. Audio is streamed from memory and never written to a recording file. The separate hands-free conversation and read-aloud controls continue to use the server's voice configuration.

### Upgrading from automatic Google setup

Google account setup and its beta switch have been removed. Existing manually entered Gemini keys retain their encrypted format and remain usable. An old automatically provisioned key is kept encrypted but is not used for dictation: enter a personal key to continue. This prevents the removal of Google authorization from silently bypassing the billing checks associated with the old managed key.

Saving a personal key replaces the local credential and clears old account/project metadata and setup checkpoints in the same write. **Remove key** clears those local records without decrypting the old credential. Neither action revokes Google grants or deletes remote Google projects, service accounts, or keys; manage those resources through your Google account separately.

Never add real keys, OAuth tokens, account details, generated project identifiers, or signing material to source, test fixtures, logs, or issue reports.

For data-preserving testing alongside a release signed with another key, use `./gradlew -Pmercury.sideBySideDebug=true assembleDebug`. This installs separately as **Mercury (Debug)** with the `.debug` application ID suffix. Normal builds retain the existing package ID.
