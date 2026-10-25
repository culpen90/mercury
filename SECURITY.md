# Security policy

## Supported versions

Security fixes are applied to the latest development version of Mercury. The first public Play release has not yet been published.

## Reporting a vulnerability

Please **do not** open a public issue for a suspected vulnerability, credential exposure, authentication flaw, or sensitive privacy report.

After this repository is public, use GitHub’s private vulnerability-reporting flow once it has been enabled. Until then, contact the repository owner privately through their [GitHub profile](https://github.com/unsupportedpastels). Include:

- a concise description of the impact;
- reproducible steps or a minimal proof of concept;
- the affected Mercury version or commit; and
- any mitigation you identified.

Do not include real passwords, access tokens, refresh tokens, cookies, WebSocket tickets, private server addresses, prompts, transcripts, or attachments in the report.

## Local offline cache

Mercury caches a bounded set of session metadata in app-private storage so the session list can paint while Hermes Serve is unreachable. Cached metadata is never treated as authoritative and is marked cached/offline until the server replaces it.

Transcript tails are **disabled by default**. A user may explicitly opt in from Settings. When enabled, transcript tails are encrypted with an Android Keystore-backed Tink key, and are bounded to 200 messages per session, 128 KiB per message body, 100 sessions, 4 MiB total, and 30 days. The cache authority boundary is normalized server origin + profile + durable session ID.

The cache never stores access or refresh tokens, WebSocket tickets, transient runtime IDs, secret or sudo input, attachments, or connection strings. Corrupt or tampered cache rows are ignored and removed without exposing their contents. Transcript tails associated with an origin are cleared on logout or origin removal; explicit cache clearing removes all rows, and deleting a server session removes its local row. Android Auto Backup remains disabled.

Managed artifact downloads remain bounded by the client transport. Explicit sharing writes only the selected artifact to app-private cache and grants read access through a non-exported `FileProvider` content URI. Mercury does not execute or render HTML/SVG artifacts in a WebView.

## Credential storage

Access/refresh tokens, cookies, and Relay pairings are encrypted with Tink AEAD keys held in the Android Keystore, each store under its own master key, scoped to the normalized server origin. Auto Backup is disabled.

## Mercury Relay

Relay is an optional transport, never a requirement for direct mode, and it never changes Hermes itself. Pairing starts from a one-time QR code produced by the Mercury Relay host plugin; the host operator approves the device by comparing a short fingerprint on both screens before it is admitted. Chat then runs end-to-end encrypted with Noise XK between the phone and the host; the hosted router sees only opaque routing metadata plus ciphertext and cannot read session content. Relay framing, Noise state, and target validation are implemented once in `shared/mercury-core` and validated against the public interop vectors from <https://github.com/unsupportedpastels/mercury-relay-plugin> (`tools/check-relay-vectors.sh` fails CI if the vendored vectors drift from the pinned release). Relay pairings, keys, and connection state are isolated from direct-mode credentials and the server catalog. Reports about the host plugin or router belong to that repository; reports about the client side of the channel belong here.

## Voice

Composer dictation uses Google's official Gemini Live transcription API through a dedicated client with fixed HTTPS/WSS endpoints and API-key headers. Google credentials are encrypted separately from Hermes and Relay credentials. Audio is streamed from memory only after an explicit microphone action and permission; stopping, changing the session/profile, or leaving the app stops capture. Dictation updates an unsent draft and never sends a message automatically.

Optional Google account setup requests Cloud authorization only when the user chooses setup. Its operations are limited to a dedicated, labeled dictation project, and managed keys require matching account identity and disabled billing before each session. Mercury does not attach billing. The optional Cloudflare enrollment service receives a separate identity-only grant and accepts enrollment only after Google OAuth readiness is enabled. It never receives Cloud administration tokens, Gemini keys, audio, or transcripts. Its live enrollment records can be withdrawn; provider recovery backups follow the retention disclosed in [PRIVACY.md](PRIVACY.md).

Server-backed voice uses released Hermes routes (`/api/audio/transcribe`, `/api/audio/speak`, `/api/audio/speak-stream`, `/api/audio/elevenlabs/voices`) over the authenticated origin-scoped transport; the streaming speech WebSocket authenticates with a fresh single-use ticket per connection, never a bearer token in a URL. Servers without the audio routes hide those controls. Microphone recordings and synthesized audio are held in memory or short-lived app-cache temporary files, deleted on completion, and excluded from the offline cache, logs, and error messages (errors are bounded category messages without audio data URLs, prompt text, config bodies, or tickets). The opt-in screen-off voice service is non-exported, uses `foregroundServiceType="microphone"`, always shows a stoppable notification without transcript content, and never starts or restarts from the background.

## Scope

Mercury is a native client for released Hermes interfaces. Reports affecting Hermes Agent itself, a server deployment, or another upstream dependency may need coordinated disclosure with that project or vendor. We will acknowledge valid reports, assess the client impact, and coordinate a fix where appropriate.
