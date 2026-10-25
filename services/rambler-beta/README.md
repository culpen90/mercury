# Rambler beta enrollment

An isolated Cloudflare Worker and D1 database for automatic Mercury beta enrollment. It never receives microphone audio, prompts, Gemini keys, or Google Cloud administration credentials. The native client requests only `openid` and `https://www.googleapis.com/auth/userinfo.email` for this service.

Successful signup immediately returns `{"status":"enrolled"}`. There is no pending queue, per-person approval, CSV admission process, or public administration API.

**Enrollment is disabled in the checked-in configuration.** `GOOGLE_OAUTH_READY=false` makes signup and status return HTTP 503 with `{"error":"oauth_not_ready"}`. The operator may enable it only after the native app's production Google OAuth access is ready, including any applicable Google verification. An enrollment record cannot add a Google test user, bypass Google's OAuth limits, or grant Google API access. Publishing an unverified OAuth application does not remove Google's lifetime 100-user limit for affected scopes. See [Google's production requirements](https://developers.google.com/identity/protocols/oauth2/policies) and [verification exceptions](https://support.google.com/cloud/answer/13464323).

## API v1

All endpoints require HTTPS and `Authorization: Bearer <Google identity access token>`. The token is used transiently for one request to the fixed `https://openidconnect.googleapis.com/v1/userinfo` endpoint, with redirects disabled. Only a bounded response with a verified email and a valid `sub` establishes identity. This is an identity-only enrollment action, so tokens from another Google OAuth client can establish the same subject; no OAuth client audience is treated as an enrollment privilege.

Never send a token carrying Mercury's Google Cloud provisioning grant. The app must obtain a separate identity-only authorization for enrollment. The Worker never calls Cloud Resource Manager, IAM, Service Usage, API Keys, Google Console private endpoints, or a browser automation service.

| Request | Body | Success |
| --- | --- | --- |
| `POST /v1/signup` | `{"consentVersion":"rambler-beta-v1"}` | 200 `{"status":"enrolled"}` |
| `GET /v1/status` | None | 200 `{"status":"enrolled"}`, or 404 `{"error":"not_registered"}` |
| `POST /v1/withdraw` | `{}` | 204, including an already-absent registration |

Withdraw remains available when enrollment is disabled. It deletes the authenticated subject's live registration; it does not revoke the user's Google grants or delete their independently created Google projects/keys. No email, subject, queue position, or another person's state is returned. Requests cannot select an identity through a body or query parameter. Unknown fields, query strings, and methods are rejected. There is no browser CORS policy because the caller is the native Android app.

Request bodies are limited to 1,024 bytes, bearer values to 8,192 characters, and Google userinfo responses to 16,384 bytes. Reads and the Google request have deadlines. Responses use `Cache-Control: no-store`. Public failures contain only a fixed error code:

| HTTP | Errors |
| --- | --- |
| 400 | `invalid_request` |
| 401 | `auth_required`, `invalid_identity` |
| 404 | `not_registered`, `not_found` |
| 405 | `method_not_allowed` |
| 409 | `consent_required` |
| 413 | `request_too_large` |
| 415 | `unsupported_media_type` |
| 503 | `oauth_not_ready`, `capacity_reached`, `auth_unavailable`, `unavailable` |

## Data and consent

Purpose version `rambler-beta-v1`: retain the verified Google subject, verified email, enrollment state, consent version, and creation/update times solely to provide Mercury's Rambler beta enrollment and withdrawal. Retain the live record until the user withdraws. Signup is idempotent by Google subject; a changed verified email updates that same record. Google access tokens, IP addresses, user agents, audio, transcripts, and API keys are never stored by this service.

The app must explain this purpose before the user chooses signup. Google authenticates the account; its consent screen does not replace Mercury's enrollment/privacy notice. A new purpose requires a new consent version and an explicit migration rather than silently widening this purpose.

The Worker has no logging calls. Workers observability, invocation logs, and preview URLs are disabled in configuration. Do not enable request/header logging, `wrangler tail`, Logpush, or a third-party tracing tool for enrollment traffic. Cloudflare still processes network traffic as the hosting provider. Deleting a live row does not instantly erase D1's recovery history; the Free plan currently retains up to seven days of [Time Travel backups](https://developers.cloudflare.com/d1/platform/limits/).

## Free plan and capacity

Deploy only to a Cloudflare account confirmed to be on **Workers Free**. The configuration cannot force a paid account to become free. Do not activate Workers Paid, attach paid products, or upgrade to get around a quota. This service uses only one Worker and one D1 database; it has no paid AI, email, queue, browser, storage, or domain dependency. A free `workers.dev` hostname is sufficient.

Current included limits are 100,000 Workers requests/day, 10 ms CPU/request, 5 million D1 rows read/day, 100,000 rows written/day, 500 MB per D1 database, and 5 GB D1 storage/account. Requests and D1 queries stop at Free-plan limits; this service returns generic unavailability for D1 failures. Cloudflare may return its own quota error before Worker code executes. Usage of other Workers/databases on the account shares those allowances. [Workers limits](https://developers.cloudflare.com/workers/platform/limits/), [D1 pricing and stop behavior](https://developers.cloudflare.com/d1/platform/pricing/), [D1 limits](https://developers.cloudflare.com/d1/platform/limits/).

Enrollment has an additional hard cap of 10,000 records and returns 503 at capacity. An atomic SQLite trigger checks a single counter row and an indexed subject lookup, so signup does not scan the enrollment table or race a separate `COUNT(*)` request. Repeated signup still succeeds at capacity. Withdrawal decrements the counter and frees one place. Each public lookup uses the subject primary key. Do not remove or manually modify the capacity triggers/counter.

## Local verification

Use Node 24 or newer. Exact dependency versions and `package-lock.json` are checked in. Miniflare is pinned to the version used by the pinned Wrangler release so local D1/runtime behavior matches the deployment tool. The compatibility date is pinned to a recent supported runtime date.

```sh
npm ci
npm run types
npm run typecheck
npm test
```

`npm test` bundles with `wrangler deploy --dry-run` and runs real local Miniflare/D1 tests with a mocked Google userinfo service. Nothing is deployed and no Google/Cloudflare credentials are needed. Tests cover readiness, automatic enrollment, ownership, idempotency, withdrawal, consent, stream bounds, provider failures/redirects, privacy-safe responses, the 10,000-row cap, and concurrent competing registrations. Generated Worker types come from `wrangler types --strict-vars=false`; regenerate them after configuration changes. No handwritten binding interface is used.

## Operator deployment

These are operator steps; no deployment is performed by the test/build scripts.

1. Confirm the selected Cloudflare account uses Workers Free. Stop if enabling the service would require payment or a paid upgrade.
2. Authenticate using `npm exec wrangler login`. Keep Cloudflare account authorization on the operator's computer; it is never a Worker binding or public API credential.
3. Create the free database using `npm exec wrangler d1 create mercury-rambler-beta`. Replace the all-zero `database_id` placeholder in `wrangler.jsonc` with the returned ID. Confirm the account before creating anything.
4. Apply the checked-in migration with `npm exec wrangler d1 migrations apply mercury-rambler-beta --remote`. It contains no user data. Keep local development bindings local (`remote:false`).
5. Choose the free public hostname by explicitly setting `workers_dev:true` (or configure an already-owned HTTPS route with fail-closed behavior). `workers_dev:false` currently prevents accidental public exposure. Leave previews/logs disabled and `GOOGLE_OAUTH_READY:false` until Google production access is ready.
6. Run the local checks and `npm run build`, review the configuration, then deploy using `npm exec wrangler deploy`. Supply that HTTPS origin to the native build; it must contain no path, query, fragment, or credentials.
7. Once the native application's production Google OAuth setup is genuinely available to intended users, set `GOOGLE_OAUTH_READY:true`, regenerate types, validate, and redeploy. This is a one-time global readiness decision, not per-person approval. Verify signup/status/withdraw with the intended native identity-only authorization.

There are no Worker secrets, scheduled jobs, account mutation endpoints, or enrollment administration scripts to configure. Do not paste user access tokens into shell commands or use production identities in automated tests. Monitor aggregate Cloudflare quota metrics without enabling request logs; if quotas or capacity are exhausted, stop accepting signups until capacity is available rather than enabling billing.
