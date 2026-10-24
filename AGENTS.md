# Repository Instructions

## Product boundary

Mercury is a native Android client for the official interfaces of an unchanged shared `hermes serve` process. Direct mode must never add, require, or assume custom server routes, forks, dashboard extensions, or gateway workers, and must keep working with no Mercury plugin installed.

**We own the Mercury stack.** The Android app, core library, Mercury Relay host plugin, hosted router, and Mercury management surfaces may be changed together to deliver product features. A missing Mercury capability is an implementation gap, not a permanent product limitation. The boundary is the actual Hermes API: do not require changes to Hermes itself, alter its official API contracts, or depend on a Hermes fork or private route.

**Mercury Relay** is an optional, separately paired transport that carries the official Hermes JSON-RPC session contract end-to-end encrypted through the Mercury Relay host plugin and an opaque hosted router. New Mercury-owned relay operations and host-plugin capabilities are permitted, including folder browsing and creation, provided they do not require changing the Hermes API. Implement host-plugin operations as Mercury-owned services or adapters over existing official Hermes interfaces; do not disguise private Hermes routes as a relay API. Keep direct-mode connection, credential, and catalog state isolated, and never make Relay or its plugin a requirement for a direct-mode feature.

Evolve Mercury-owned contracts with explicit versioning/capability advertisement and compatibility tests across the affected stack. Preserve end-to-end encryption and router opacity, pairing authorization, host filesystem permissions and applicable managed-root restrictions. Older clients or hosts must receive a supported fallback or clear upgrade guidance, not a misleading authentication error.

Released Hermes compatibility is conservative: observe durable/live metadata without implicit transport takeover. Resume or activate another remote connected client's runtime only after explicit user action. Never close a shared runtime merely because this client disconnects. Capability-gate multi-subscriber streaming until a safe released transport advertises it.

## Module boundaries

Mercury is an Android app (`app/`) with a Kotlin core library
(`shared/mercury-core`). Both modules use the Android Gradle build:

- **Core decisions.** Protocol decoding, transcript reduction, origin and
  attachment policy, notification text, slash-command policy, relay framing,
  and any other deterministic client decision live in `shared/mercury-core`
  and are consumed by the app. Fix a bug there once; keep deterministic
  policy out of UI components and platform adapters.
- **Native UI and integration.** Compose/Material 3 and the Android app own
  UI, navigation, adaptive layout, accessibility, ViewModels and observable
  state, credential storage, browser auth presentation, notifications, foreground
  services, share targets, pickers, voice, and process lifecycle. Use native
  Android controls and platform APIs.
- **Tests travel with the behavior.** A core change ships with a focused
  core test; app changes use the appropriate unit, screenshot, UI, or device
  verification. The required gates below must be green before merge.
- **Mercury Relay capabilities evolve across the stack.** Add or extend
  Mercury-owned relay contracts and host-plugin support when a feature needs
  them; coordinate the core contract, Android consumers, and affected host/router
  components. At runtime, enable features only when the paired transport
  advertises support. Keep older peers compatible and direct mode independent;
  do not change the actual Hermes API to deliver a feature.

## Android architecture

- Kotlin, single-activity Jetpack Compose, Material 3.
- Navigation 3 with serializable `NavKey`s and saveable back stacks.
- Adaptive list/detail uses `ListDetailSceneStrategy` from `adaptive-navigation3`; do not use legacy `ListDetailPaneScaffold` or `NavigableListDetailPaneScaffold`.
- Make decisions from current window metrics/posture, never device model names or orientation alone.
- Edge-to-edge is mandatory. Apply insets at individual screen/list/composer boundaries and avoid double IME/system-bar padding.
- Keep durable stored-session IDs separate from transient live runtime-session IDs.
- Keep observer and controller roles explicit.

## Security

- Scope credentials, cookies, trust decisions, cached transcripts, and connection settings by normalized server origin.
- Treat WebSocket tickets as fresh, single-use, in-memory values.
- Never log credentials, tokens, cookies, tickets, prompts, transcripts, attachments, secrets, sudo/terminal input, or connection strings.
- Export only the launcher activity. Add no exported component without an explicit threat model and tests.
- Do not commit `local.properties`, signing material, credentials, server URLs, or generated build output.

## Development workflow

Use proportionate RED -> GREEN -> REFACTOR. Reproducible bug fixes and testable behavior changes should start with a focused regression test that fails for the expected reason, especially for reducers, protocol parsing, reconciliation, authentication/security, lifecycle/concurrency, and policy decisions. Keep those decisions independent of Android framework state where practical and test them locally.

Do not manufacture failing tests for documentation, `.gitignore`, configuration, generated code, dependency-only changes, exploratory spikes, purely visual work, or behavior that can only be meaningfully exercised on a device. Use the most relevant evidence instead: existing tests before and after a refactor, builds and validators for configuration, screenshot and interaction checks for UI, or device/integration verification for platform behavior. State which verification strategy was used and do not claim strict TDD when RED was not observed.

Required gates for changed Android code:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

For changes touching `shared/mercury-core` (the Android core library), also run:

```bash
./gradlew :shared:mercury-core:check
```

The core library's local unit tests run on the JVM on Linux or macOS.

For adaptive UI changes, also run the configured screenshot/UI test gates and test compact, medium, and expanded windows. Do not update screenshot references without visual review.

## Official project-local skills

The current Google-authored skills live under `.agents/skills/`. Consult matching skills before changes, especially `adaptive`, `navigation-3`, `edge-to-edge`, `testing-setup`, `android-intent-security`, and `android-cli`.
