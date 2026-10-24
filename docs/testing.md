# Testing Strategy

## Fast local gate

```bash
./gradlew testDebugUnitTest
```

Use local tests for reducers, origin normalization/persistence, server setup UI, protocol parsing, observer/controller policy, request correlation, refresh classification, concurrent controller generations, reconnect reconciliation, notification routing, working-state policy, and state restoration models. Prefer explicit fakes over mocks.

## Build and static gate

```bash
./gradlew lintDebug assembleDebug
```

The complete pre-handoff gate is:

```bash
git diff --check && \
./gradlew testDebugUnitTest lintDebug assembleDebug validateDebugScreenshotTest :shared:mercury-core:check
```

`:shared:mercury-core:check` runs the core Android library's local JVM unit tests and checks on Linux or macOS. Contract fixtures are kept under `shared/mercury-core/src/test/resources/`.

## Fake Hermes contract rig

`tools/fake-hermes/fake_hermes.py` is a stdlib-only fake `hermes serve` backend that speaks just enough of the dashboard HTTP + WebSocket JSON-RPC contract (status probe, password login, sessions, transcript, WS ticket, `session.resume`, `prompt.submit`, streaming deltas, `session.interrupt`) to drive the Android app end to end. It is a contract fixture for the client, not a Hermes reimplementation.

- `python3 tools/fake-hermes/fake_hermes.py [PORT]` starts it (default 8787, cleartext HTTP; any username with password `e2epass`). Set `MERCURY_E2E_VIDEO_FILE` to a local synthetic MP4 to exercise managed-video download.
- `python3 tools/fake-hermes/verify.py [PORT] [--video]` walks the full probe → login → resume → prompt → interrupt path against a running instance.
- CI (`android-ci.yml`) runs `python3 -m unittest discover -s tools/fake-hermes`, starts the fake on 8787, and runs `verify.py` against it before the Gradle gates, so the rig itself cannot drift silently.
- `FakeHermesEndToEndTest` and `ManagedVideoEndToEndTest` (`app/src/androidTest`) drive the real UI against the fake at `10.0.2.2:8787` from an emulator.

## Adaptive UI matrix

Every screen-level adaptive screenshot suite must cover compact, medium, and expanded widths and representative heights, including 400x500, 610x1000, and 900x1000 dp. Add dark theme and 1.5 font-scale variants for core screens.

Behavior checks must cover:

- compact one-pane list -> detail -> back;
- configure and edit a canonical HTTPS server origin, including inline rejection of cleartext and credential-bearing input;
- unfolded list/detail selection;
- fold/unfold with selected session and composer draft preserved;
- resize while streaming and while blocking input is pending;
- two concurrent Mercury-started turns with independent completion and Stop behavior;
- Back from a running session, opening another session, and reopening the first without losing its partial output or amber working state;
- idle attached controllers never appearing as active work;
- cold- and warm-notification taps opening the exact durable session;
- reconnect replacing authoritative inflight text/tool state without duplication;
- origin/profile changes preventing stale refresh, metadata, socket, and controller results from publishing;
- edge-to-edge system bars and IME visibility;
- predictive back;
- keyboard focus and navigation;
- split screen, freeform, and DeX-sized windows.

## Device gate

Use a disposable foldable emulator for instrumentation and process-restoration tests. Before milestone completion, install and exercise the debug APK on the standard/non-Ultra Galaxy Z Fold 8 with a data-preserving install. Capture the live layout tree and settled screenshots for cover, unfolded portrait, and unfolded landscape. Do not run uninstalling/clearing instrumentation against the user's authenticated primary installation. A missing or locked physical device blocks only real-device verification, not local development.

Do not regenerate screenshot references without inspecting the visual diff.
