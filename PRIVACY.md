# Privacy policy

**Effective date:** November 5, 2026

Mercury — a Hermes companion — is a native Android client for a Hermes Agent server selected and controlled by the user.

## Data Mercury handles

To operate, Mercury may handle:

- the server address you choose;
- authentication cookies or tokens issued by that server;
- project, session, prompt, response, tool-status, and transcript data returned by that server;
- files or images you explicitly attach for upload to that server;
- microphone audio and recognized text when you choose Rambler dictation;
- the personal Gemini API key you enter for Rambler.

Mercury stores connection and session metadata locally on your device, scoped to the normalized server origin, selected profile, and durable session ID. This metadata cache is bounded, expires after 30 days, and may be shown with a cached/offline marker until Hermes Serve reconciles it. Authentication material is stored using Android Keystore-backed encrypted storage scoped to the normalized server origin. Fresh WebSocket tickets remain in memory only.

Transcript tails are not stored unless you opt in under Settings. Opted-in transcript tails are encrypted with an Android Keystore-backed key and bounded to 200 messages per session, 128 KiB per message body, 100 sessions, and 4 MiB total. Tails associated with an origin are cleared on logout or when that origin is removed; all tails are cleared by the explicit cache control and application data removal. Mercury never caches access or refresh tokens, tickets, transient runtime IDs, secret input, attachments, or connection strings. Android Auto Backup is disabled.

Relay pairings and device keys are encrypted in a separate Android Keystore-backed store, apart from direct-server credentials. Removing a pairing deletes its local keys and connection state.

Your personal Gemini API key uses another Android Keystore-backed encrypted store, scoped to Google's API and separate from Hermes and Relay credentials. The key is never populated into the editor or restored in saved UI state. **Remove key** deletes the local credential. Mercury does not manage the key's Google project, billing, or quotas.

An upgrade from the removed automatic Google setup preserves the old credential ciphertext, but an old managed-key record prevents dictation until you explicitly enter a personal Gemini key. Saving that key replaces the local credential and removes the obsolete encrypted Google account/project metadata and setup checkpoints in the same write. **Remove key** removes those records without decrypting the old credential. Neither action revokes a Google grant or deletes remote Google projects, service accounts, or keys. Existing manually entered keys remain usable.

Host-file contents are downloaded only after an explicit preview, play, save, or share action. Save uses Android's user-selected document destination. Share creates a bounded temporary file in app-private cache and exposes only that file through a one-time Android content-URI grant; Android may later evict the cache file.

## Voice

- **Rambler dictation.** Composer dictation streams microphone audio directly to Google's Gemini 3.5 Transcribe API in SMART mode and receives recognized text in the draft. Capture starts only after you choose dictation and grant microphone permission. The recording stays in memory and is never saved as an audio file. Recognized text becomes ordinary composer text; after you send it, normal Hermes conversation and optional transcript-cache rules apply.
- **Server-backed voice.** When you choose a server-backed voice feature, Mercury sends recorded audio to your configured Hermes server for transcription (`/api/audio/transcribe`). That server may forward the audio to its configured speech-to-text provider. Recording stops according to the selected feature's stop control, silence/server limits, and lifecycle policy.
- **Spoken replies.** Text you ask Mercury to read aloud (or that auto-speak reads, when the server's `voice.auto_tts` is enabled) is sent to your configured Hermes server for synthesis (`/api/audio/speak` and `/api/audio/speak-stream`), which may forward it to its configured text-to-speech provider.
- **Temporary audio.** Server-backed recording and playback may use short-lived app-cache audio files that are deleted when the operation finishes. Audio is not written to the offline transcript cache, saved state, logs, notifications, or analytics. Dictated text follows the composer's normal state and conversation-storage behavior.
- **Lifecycle default.** Voice capture and playback stop when you switch sessions or profiles, log out, lock the device, or leave the app.
- **Screen-off continuation (opt-in).** If you enable "Continue voice with screen off" in Settings, an already-started voice conversation keeps running behind a non-dismissable notification with a Stop action, using Android's microphone foreground service; Android's microphone indicator stays visible, and a partial wake lock keeps capture and network alive. The notification shows only the loop phase (Listening/Thinking/Speaking), never transcript text. After the process is killed, voice never restarts on its own.
- **Server-side processing** of voice audio and speech text — including provider choice, logging, and retention — is controlled by your server's operator and configuration.

Google processes dictation under its [Gemini API terms](https://ai.google.dev/gemini-api/terms) and [privacy policy](https://policies.google.com/privacy). Under Google's unpaid-service terms, submitted content and generated responses may be used to improve Google products and may be reviewed by people. Google sets its own eligibility, retention, and usage rules; Mercury's lack of local audio persistence does not control Google's processing.

## Historical beta enrollment

If you previously enrolled in the retired Rambler beta, contact the maintainer privately to request removal of any historical enrollment record. Upgrading the app does not delete a hosted record.

## Where data goes

Mercury sends conversation data to the Hermes server origin you configure in the app or, when you explicitly enable Relay, through the end-to-end encrypted route described below. Mercury does not include analytics, advertising, tracking, or telemetry SDKs.

If you explicitly pair the optional **Mercury Relay** mode, traffic to your host additionally passes through a hosted relay router. For session transport, that router sees opaque routing metadata (a random installation route and connection role) plus end-to-end encrypted ciphertext; it cannot read prompts, transcripts, credentials, or session content. Relay mode is never required and its device-only encrypted pairings can be removed at any time.

When you choose Rambler dictation, your key authenticates a direct connection to Google's official API and microphone audio streams there. Recognized text becomes ordinary composer content, including its server-backed slash-command completion behavior; dictation does not submit a chat message automatically. The key is not sent to Hermes or Relay.

Your chosen server’s operator and configuration determine how server-side data is processed, retained, logged, and secured. Review that server’s policies before connecting, especially if it is operated by someone else.

## Android permissions

- **Internet** — connect to your configured Hermes server, optional Relay, and Google's dictation API.
- **Notifications** — optional alerts for completed turns and requests requiring your attention, plus the persistent voice-conversation status notification.
- **Foreground service / data sync** — maintain truthful, visible status while an active Mercury-started turn is running.
- **Microphone (`RECORD_AUDIO`)** — voice dictation, voice conversations, and barge-in; requested only when you first start a voice feature, used only while one is active.
- **Foreground service / microphone and wake lock** — only for the opt-in screen-off voice continuation described above.

On Android, Mercury does not request location, contacts, camera, or storage-wide file permissions. Relay QR scanning delegates the camera surface to Google Play services' on-device code scanner; Mercury receives only the decoded QR text and never receives camera frames. Attachments use Android’s user-mediated document picker. Rambler dictation and server-backed voice use Mercury's explicitly requested `RECORD_AUDIO` permission.

## Security

Dictation uses a dedicated HTTPS/WSS client and credential store that do not share Hermes credentials or cookies. Mercury scopes Hermes credentials, connection settings, and cached transcript data by server origin, and it does not carry credentials to a newly selected origin.

No software can guarantee absolute security. Do not connect to a server you do not trust, and protect your device with a screen lock.

## Changes and contact

Material updates to this policy will be documented in this repository. For a security-sensitive privacy concern, follow [SECURITY.md](SECURITY.md) rather than opening a public issue.
