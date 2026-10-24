# Requirements

## Runtime and build

### Android app and core library

| Requirement | Selected baseline |
|---|---|
| JDK | 17 |
| Android Gradle Plugin | 9.3.1 (required for current AndroidX artifacts compiled against API 37) |
| Gradle wrapper | 9.7.1 (`gradle/wrapper/gradle-wrapper.properties`, distribution SHA-256 pinned) |
| Kotlin / Compose compiler plugin | 2.4.10 |
| AndroidX Core / Activity Compose / Fragment | 1.19.0 / 1.13.0 / 1.9.0 |
| Lifecycle / ViewModel | 2.11.0 |
| Compile / target SDK | 37 / 36 (current AndroidX requires API 37 to compile; stable API 36 runtime behavior remains the target) |
| Minimum SDK | 29 (working baseline; review before publication) |
| Compose BOM | 2026.06.01 |
| Navigation 3 | 1.1.5 |
| Material 3 Adaptive Navigation 3 | 1.3.0-rc01 (no stable `adaptive-navigation3` release exists; required by the August 2026 official adaptive skill) |
| Coroutines | 1.11.0 |
| Kotlin serialization JSON | 1.11.0 |
| Ktor client / OkHttp engine / WebSockets | 3.5.2 |
| DataStore Preferences | 1.2.1 |
| Tink Android | 1.23.0 (Keystore-backed AEAD for tokens, cookies, Relay pairings, transcript cache) |
| Media3 ExoPlayer / UI | 1.8.0 (managed-video playback) |
| Play services code scanner | 16.1.0 (Relay pairing QR; on-device, no camera frames reach Mercury) |
| Turbine | 1.2.1 |
| Robolectric | 4.16.1 |
| AndroidX Test core / runner / ext-junit / Espresso | 1.7.0 / 1.7.0 / 1.3.0 / 3.7.0 |
| Compose screenshot testing plugin | 0.0.1-alpha16 |

### Core library (`shared/mercury-core`)

| Requirement | Selected baseline |
|---|---|
| Target | Android library (`minSdk` 29, `compileSdk` 37) |
| Plugin | `com.android.library` (AGP 9.3.1, built-in Kotlin) |
| cryptography-kotlin | 0.6.0 — `cryptography-core`, `cryptography-random`, and the JDK provider plus BouncyCastle `bcprov-jdk18on` 1.85.2 (Relay Noise XK primitives) |
| Kotlin serialization JSON | 1.11.0 |
| Sources and tests | Kotlin under `src/main/kotlin`; local JVM tests under `src/test/kotlin`; contract fixtures under `src/test/resources/` |

The version catalog (`gradle/libs.versions.toml`) is authoritative for resolved library versions. `local.properties` is machine-local and untracked.

## Product

- Connect to official REST and ticketed JSON-RPC/WebSocket endpoints exposed by an unchanged `hermes serve` process.
- Let users configure and edit a canonical HTTPS server origin without embedding credentials, endpoint paths, queries, fragments, or WebSocket tickets.
- Persist only the normalized origin as connection metadata; derive REST and WebSocket endpoint paths in the client.
- Keep authentication, storage, cached data, and TLS/trust choices isolated per normalized server origin.
- Browse durable stored sessions separately from transient process-local live runtimes.
- Released-server default is observer mode. Any action that transfers a live session transport requires explicit confirmation.
- Never send `session.close` as generic connection or lifecycle cleanup.
- Controller-only operations include prompt submission, approvals, clarification, secret/sudo/terminal input, steering, and interruption.
- Reconnect through status, active-session, inflight, and durable-transcript reconciliation rather than blind replay.

Mercury is an Android app with a Kotlin core library. Protocol decoding, transcript reduction, origin and attachment policy, notification text, slash-command policy, and Relay framing live in `shared/mercury-core`; Compose UI, navigation, credential storage, notifications, and process lifecycle live in `app/`. **Mercury Relay** is an optional, separately paired transport that carries the official Hermes JSON-RPC session contract end-to-end encrypted (Noise XK) through the Mercury Relay host plugin and an opaque hosted router. Mercury-owned contracts and host/router services may evolve together with explicit versioning and compatibility tests. Relay code stays isolated from direct-mode connection, credential, and catalog state, never changes Hermes itself, and is never a requirement for any direct-mode feature; Relay features are limited to what the paired transport advertises.

## Foldable and adaptive UI

Primary physical target: standard/non-Ultra Samsung Galaxy Z Fold 8.

- No device-name, orientation-only, or fixed-resolution branching.
- Compact windows use one pane; suitable wider windows use list/detail scenes automatically.
- Preserve selected session, navigation stack, composer draft, scroll position, inflight state, and origin across fold/unfold, resize, rotation, multi-window, and process recreation.
- Support cover screen, unfolded portrait/landscape, split screen, freeform windows, DeX, keyboard, mouse/trackpad, touch, and predictive back.
- Respect separating hinges and display features provided by adaptive APIs.
- Use edge-to-edge drawing with correct system bar, cutout, and IME insets.

## Local security

- No plaintext credentials in files, preferences, logs, backups, or crash reports.
- Native access/refresh tokens persist only in Android Keystore-backed encrypted storage scoped to the normalized HTTPS origin; refresh rotation replaces the encrypted record atomically.
- Relay pairings and device static keys live in their own encrypted store, separate from direct-mode credentials and the server catalog.
- Never persist authorization codes, PKCE verifiers, WebSocket tickets, or credential-bearing URLs.
- Disable backup for secret-bearing state unless a reviewed encrypted backup design is introduced.
- Export only the launcher activity; add no exported component without an explicit threat model and tests.
- Production network security must reject cleartext by default.
- Debug-only development trust exceptions, if needed, must remain in debug resources and never contain a private server address.
