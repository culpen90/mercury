## Summary

<!-- What user problem does this solve? -->

## Hermes compatibility

<!-- Confirm this uses released, official Hermes interfaces and does not require a custom server route, plugin, dashboard extension, worker, or fork. Mercury Relay changes must stay isolated from direct-mode connection, credential, and catalog state. -->

## Module boundaries

<!-- Deterministic client decisions belong in shared/mercury-core; native UI and Android integration belong in app/. For Relay changes, describe capability advertisement and compatibility with older hosts. -->

## Verification

- [ ] `./gradlew testDebugUnitTest lintDebug assembleDebug`
- [ ] `./gradlew :shared:mercury-core:check` (when `shared/mercury-core` changes)
- [ ] `./gradlew validateDebugScreenshotTest` (when UI/reference changes apply)
- [ ] I checked compact, medium, and expanded layouts (when adaptive UI changes apply).
- [ ] I did not add credentials, cookies, tickets, server addresses, prompts, transcripts, attachments, signing material, or generated build output.

## Screenshots

<!-- Add sanitized before/after screenshots for user-visible UI changes. -->
