# Contributing to Mercury

Thanks for helping improve Mercury — a Hermes companion.

## Product boundary

Mercury is a native Android client for the official interfaces of an unchanged shared `hermes serve` process. Direct mode must never add, require, or assume custom server routes, forks, dashboard extensions, or gateway workers, and must keep working with no Mercury plugin installed.

**Mercury Relay** is an optional, separately paired transport that carries the official Hermes JSON-RPC session contract end-to-end encrypted through the Mercury Relay host plugin and an opaque hosted router. The Android app, core library, host plugin, router, and Mercury management surfaces may evolve together, with versioned capabilities and compatibility tests. Relay code must stay isolated from direct-mode connection, credential, and catalog state; it never changes Hermes itself, tunnels no private Hermes route, and is never a requirement for any direct-mode feature.

The Android app (`app/`) consumes the Kotlin core library (`shared/mercury-core`). Deterministic client decisions (protocol decoding, transcript reduction, attachment and origin policy, Relay framing) belong in the core; Compose UI, navigation, credential storage, notifications, and process lifecycle belong in the app. See [AGENTS.md](AGENTS.md) for the full module boundaries.

The app must remain an observer by default: it may not resume, take control of, or close another remote client’s runtime without explicit user action.

## Before you start

1. Search existing issues and discussions before opening a duplicate.
2. For a significant change, open an issue describing the user problem and the released Hermes contract it relies on.
3. Keep a change focused. Avoid unrelated reformatting or generated build artifacts.
4. Never commit `local.properties`, signing material, credentials, server URLs, captured prompts/transcripts, or build output.

## Local development

See [docs/setup.md](docs/setup.md) for host setup on Linux and macOS.

**Android.** Install JDK 17 and the required Android SDK, add your SDK path to an untracked `local.properties`, then run at least:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Add `validateDebugScreenshotTest` when UI or screenshot references change.

**Core library.** For changes touching `shared/mercury-core`, also run:

```bash
./gradlew :shared:mercury-core:check
```

The core library's local unit tests run on the JVM on Linux or macOS.

Use proportionate RED → GREEN → REFACTOR for reproducible bugs and testable behavior. Documentation, build configuration, and purely visual changes should use the most relevant validation instead—for example, a build/resource validation or inspected screenshots.

## Pull requests

- Explain the problem, approach, and verification in the pull-request template.
- Add focused tests for behavior, protocol parsing, state reconciliation, lifecycle, or security changes. A core change ships with a focused core test; app changes use the appropriate unit, screenshot, UI, or device verification.
- For adaptive UI changes, verify compact, medium, and expanded windows. Do not update screenshot references without visual review.
- Keep all authentication data, WebSocket tickets, prompts, transcripts, attachments, and connection strings out of source, tests, logs, and screenshots.

## Code of conduct

Be constructive and respectful. Harassment, discrimination, and sharing another person’s private data are not acceptable.
