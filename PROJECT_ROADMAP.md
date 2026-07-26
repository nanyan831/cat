# CatLifePet v1.0 Development Roadmap

Last updated: 2026-07-26

## Execution Status

- [x] M0.1 Roadmap and repository baseline - build passed on 2026-07-22.
- [x] M1.1 Produce and validate CUDDLE assets - strict alpha/anchor checks and contact-sheet review passed on 2026-07-22.
- [x] M1.2 Integrate CUDDLE runtime behavior - build, lint, Pixel 6 matrix, and PCRM00 device run passed on 2026-07-22.
- [x] M2.1 Scaffold the Ktor service - 10 tests, real health check, production fail-fast, and Android regression build passed on 2026-07-22.
- [x] M2.2 Add PostgreSQL and migrations - fresh/repeat migration, repository integration, rollback, and Android regression checks passed on 2026-07-22.
- [x] M3.1 Implement passwordless email authentication - code policy, SMTP, JWT, rotation, replay defense, deletion, and secret-log checks passed on 2026-07-22.
- [x] M3.2 Add Android authentication UI - refresh/logout unit tests, encrypted-storage checks, lint, and Pixel/PCRM00 end-to-end login passed on 2026-07-22.
- [x] M4.1 Add the server AI gateway - fake contract, OpenAI request/error mapping, timeout/cancellation, no-key startup, and secret scans passed on 2026-07-22.
- [x] M4.2 Add conversations and SSE streaming - ownership, ordering, retry, timeout, disconnect cancellation, single-finalization, and real PostgreSQL restart checks passed on 2026-07-22.
- [x] M4.3 Add Android chat UI - fake SSE, Room reopen, rotation, interruption/retry, lint, Pixel 6, and PCRM00 end-to-end checks passed on 2026-07-22.
- [x] M5.1 Add controlled companion memory - explicit-only memory CRUD, bounded prompt context, summary, deletion, lint, Pixel emulator, and PCRM00 checks passed on 2026-07-22.
- [x] M5.2 Add safety, quotas, and graceful degradation - moderation/crisis routing, persistent quota, burst limits, circuit breaker, fallback, audit, concurrency, and dual-device checks passed on 2026-07-22.
- [x] M6.1 Make reminder scheduling deterministic - unique one-shot chains, setting resync, cross-midnight/DST policy, stale mute rollover, system-event receiver, and dual-device WorkManager checks passed on 2026-07-22.
- [x] M6.2 Harden overlay service lifecycle - persisted hide restoration, duplicate-view guards, explicit special-use FGS startup, notification-denied behavior, process death, full regression, and dual-device checks passed on 2026-07-22.
- [x] M7.1 Replace final visual and release resources - 90 pet PNGs passed canvas/alpha/anchor contracts; launcher, monochrome, notification, and splash resources plus three-size edge/bubble checks passed on 2026-07-22.
- [x] M7.2 Complete account, privacy, and accessibility UI - settings entries, privacy/data page, deletion routes, accessibility contracts, large-font screenshot, release debuggable check, and Pixel emulator tests passed on 2026-07-26; PCRM00 install channel timed out and needs phone-side USB install confirmation before rerun.
- [ ] M8.1 Automated regression suite.
- [ ] M8.2 Device endurance and compatibility qualification.
- [ ] M9.1 Deploy staging and production services.
- [ ] M9.2 Produce signed Android release.

## 1. Product Goal

CatLifePet v1.0 is an Android life-companion product with three layers:

1. A local floating cat that remains useful without an account or network.
2. A cloud account that owns conversations, profile data, and companion memory.
3. A server-mediated AI chat experience with streaming replies, safety controls,
   usage limits, and explicit data deletion.

The existing floating-window implementation remains the foundation. The project must
not rewrite `CatFloatingService`, `PetWindowController`, `CatPetView`, local reminders,
or the frame-animation system merely to add online features.

## 2. Current Baseline

The current Android application already provides:

- Foreground overlay service and overlay permission handling.
- Click, drag, edge snap, peek placement, three pet sizes, and temporary hide.
- Local reminder scheduling, do-not-disturb time, mute-today, and debug reminders.
- Local settings and pet status persistence.
- Random dialogue and reminder confirmation feedback.
- Real IDLE, HAPPY, EATING, DRINKING, SLEEPING, STRETCHING, DRAGGING, BLINKING,
  LICKING, YAWNING, and CURIOUS frame animations.
- Debug and release builds that pass on Pixel 6 and the connected PCRM00 phone.

Known unfinished product work:

- CUDDLE has validated eight-frame assets, relationship dialogue, and safe IDLE return.
- Temporary hide and reminder scheduling require production lifecycle hardening.
- No server, account, AI chat, cloud history, or cloud memory exists yet.
- Final icon, notification icon, splash, privacy copy, signing, and release bundle remain.

## 3. Non-Negotiable Architecture Rules

- Local pet and reminders work without login and without network access.
- Login is required only for AI chat, cloud history, and cloud memory.
- The OpenAI API key exists only on the server and never in the APK or client logs.
- Device-specific values such as overlay position and size remain local.
- Account, conversation, message, memory, and usage data live on the server.
- Every network API is versioned under `/v1`.
- The Android client receives AI output from CatLifePet Server, never directly from
  an AI provider.
- AI providers are hidden behind a server interface so the implementation can change.
- Users can delete conversations, memories, and their account.
- Secrets, signing files, local databases, screenshots, and build output are not
  committed to Git.

## 4. Target Architecture

```text
Android app
|- Existing overlay pet, reminders, settings, and animations
|- AuthActivity and AuthRepository
|- ChatActivity, ChatRepository, and streaming message renderer
|- Room cache for recent conversations and pending messages
|- DataStore for non-secret device settings
|- Android secure storage for refresh credentials
`- Retrofit/OkHttp over HTTPS and SSE
             |
             v
CatLifePet Ktor server
|- Authentication and rotating token sessions
|- User profile and account deletion
|- Conversation, message, and companion-memory APIs
|- AI provider gateway and OpenAI Responses adapter
|- Streaming SSE relay
|- Moderation, rate limits, quotas, and usage records
`- PostgreSQL with versioned migrations
```

Repository layout after the server is introduced:

```text
CatLifePet/
|- app/
|- server/
|- docker-compose.yml
|- docs/api/
|- design-source/
|- PROJECT_ROADMAP.md
`- README.md
```

## 5. Required Development Workflow

Every checkpoint follows this exact order:

1. Implement only the scoped checkpoint.
2. Run the checkpoint-specific automated tests.
3. Run the relevant build or device smoke test.
4. Record meaningful QA output when visual or device behavior is involved.
5. Review the diff and confirm no secret or unrelated generated file is staged.
6. Create the named Git commit only after all required checks pass.
7. Update this roadmap by marking the checkpoint complete.
8. Continue to the next checkpoint.

A failed test means no commit and no progression. Fix or document the blocker first.

## 6. Milestones And Commit Gates

### M0 - Version-control baseline and execution plan

#### M0.1 Roadmap and repository baseline

Scope:

- Add this roadmap and repository hygiene rules.
- Initialize Git because the existing project has no repository.
- Capture the current working Android project as the baseline.

Required checks:

- `gradlew.bat assembleDebug`
- Confirm ignored build output, local properties, screenshots, and secrets are not staged.

Commit:

- `chore: establish CatLifePet development baseline`

Exit criteria:

- A clean Git worktree exists and the roadmap is tracked.

### M1 - v0.8.4 complete CUDDLE behavior

#### M1.1 Produce and validate CUDDLE assets

Scope:

- Produce 8-10 independent 512 x 512 transparent PNG frames.
- Keep bottom anchor at y=451 and maintain a stable visual center.
- Make the motion work from both screen edges, using a neutral frontal nuzzle or
  reviewed directional variants.
- Add manifest, asset notes, strict validation, and contact sheet.

Required checks:

- Strict asset validation returns READY.
- Full-size contact-sheet review passes.

Commit:

- `feat(animation): add validated cuddle frame assets`

#### M1.2 Integrate CUDDLE runtime behavior

Scope:

- Add `CatState.CUDDLE` and ONCE animation playback.
- Connect relationship dialogue and safe automatic return to IDLE.
- Remove the HAPPY fallback wording from the Debug screen.
- Preserve click, drag, reminder, hide, and stop-service interruption behavior.

Required checks:

- `gradlew.bat clean assembleDebug assembleRelease lintDebug`
- Pixel 6 at 80, 120, and 160dp.
- Both screen edges.
- 20 preview runs followed by click, drag, reminder, hide, and stop interruptions.
- One complete run on PCRM00.

Commit:

- `feat(pet): integrate cuddle relationship animation`

### M2 - v0.10.0 server foundation

#### M2.1 Scaffold the Ktor service

Scope:

- Add the `server` Gradle module.
- Add JSON serialization, configuration loading, request IDs, structured errors,
  sanitized logging, and `GET /health`.
- Add development, test, and production configuration boundaries.

Required checks:

- `gradlew.bat :server:test`
- Server starts locally and `/health` returns 200.
- Missing required production configuration fails fast without printing secrets.

Commit:

- `feat(server): scaffold Ktor service and health endpoint`

#### M2.2 Add PostgreSQL and migrations

Scope:

- Add Docker Compose for local PostgreSQL.
- Add versioned migrations for users, login codes, refresh sessions,
  conversations, messages, memories, and daily usage.
- Add transaction and repository boundaries.

Required checks:

- Fresh database migration passes.
- Repeat migration is idempotent.
- Repository integration tests pass against PostgreSQL.

Commit:

- `feat(server): add PostgreSQL schema and repositories`

### M3 - v0.11.0 account and session system

#### M3.1 Implement passwordless email authentication

Initial v1 login method: email verification code. Passwords, SMS, Google login, and
passkeys are deferred until the core account flow is stable.

Endpoints:

```text
POST   /v1/auth/code/request
POST   /v1/auth/code/verify
POST   /v1/auth/refresh
POST   /v1/auth/logout
GET    /v1/me
PATCH  /v1/me
DELETE /v1/me
```

Scope:

- Hash verification codes and refresh tokens in the database.
- Use short-lived access tokens and rotating refresh tokens.
- Add expiry, attempt limits, resend limits, IP limits, and generic responses that do
  not disclose whether an email is registered.
- Provide a development email sender and a production SMTP interface.
- Revoke all sessions during account deletion.

Required checks:

- Unit tests for valid, expired, incorrect, reused, and rate-limited codes.
- Integration tests for login, refresh rotation, replay rejection, logout, and deletion.
- No raw code, token, email body, or signing secret appears in logs.

Commit:

- `feat(auth): add email code login and rotating sessions`

#### M3.2 Add Android authentication UI

Scope:

- Add `AuthActivity`, auth models, API client, repository, and session interceptor.
- Add request-code, verify-code, loading, retry, logout, expired-session, and delete-account states.
- Keep the local pet usable while logged out.
- Store only the minimum refresh credential using Android secure storage.

Required checks:

- Android unit tests for session refresh and logout.
- Mock-server tests for success, 401 refresh, network failure, and invalid code.
- Pixel 6 and PCRM00 login/logout smoke tests.
- `gradlew.bat assembleDebug lintDebug`

Commit:

- `feat(android): add account login and session management`

### M4 - v0.12.0 AI conversation vertical slice

#### M4.1 Add the server AI gateway

Scope:

- Define an `AiProvider` interface and deterministic fake provider.
- Add the OpenAI Responses API adapter on the server.
- Keep API key and model selection in server environment configuration.
- Set explicit timeout, cancellation, input limit, output limit, and provider error mapping.
- Default to the intended data-storage setting and document it.

Required checks:

- Provider contract tests pass with the fake provider.
- Missing API key does not prevent fake-provider development mode.
- Optional real-provider smoke test passes only when a developer key is supplied.
- Repository scan confirms no API key is tracked.

Commit:

- `feat(ai): add server-side AI provider gateway`

#### M4.2 Add conversations and SSE streaming

Endpoints:

```text
POST   /v1/conversations
GET    /v1/conversations
GET    /v1/conversations/{id}/messages
POST   /v1/conversations/{id}/messages/stream
DELETE /v1/conversations/{id}
```

Scope:

- Persist user and assistant messages with stable ordering.
- Stream assistant deltas over SSE.
- Cancel provider work when the client disconnects.
- Prevent users from reading or deleting another user's conversations.
- Persist a final message only once.

Required checks:

- Authorization and ownership integration tests.
- SSE ordering, disconnect, retry, timeout, and duplicate-finalization tests.
- PostgreSQL restart preserves conversation history.

Commit:

- `feat(chat): add persisted conversations and SSE streaming`

#### M4.3 Add Android chat UI

Scope:

- Add `ChatActivity` and `RecyclerView` without migrating the existing app to Compose.
- Add conversation history, streaming assistant text, stop, retry, delete, loading,
  empty, offline, and expired-session states.
- Add Room cache for recent messages and pending user sends.
- Add a home-screen entry named `和小猫聊聊`.
- Do not open the keyboard from a normal floating-pet click.

Required checks:

- View-model/repository tests with a fake SSE stream.
- Rotation/recreation does not duplicate messages.
- Network interruption and retry preserve the user's text.
- Pixel 6 and PCRM00 end-to-end chat tests.
- Existing overlay and reminders continue to work while chat is open.

Commit:

- `feat(android): add streaming cat chat experience`

### M5 - v0.13.0 companion memory, safety, and cost controls

#### M5.1 Add controlled companion memory

Scope:

- Send only recent messages, a conversation summary, and selected memories.
- Store explicit preferences such as nickname, preferred address, and routine.
- Never store inferred sensitive facts as durable memory without a clear rule.
- Add list, delete-one, delete-all, and conversation-clear controls.

Required checks:

- Memory ownership and deletion integration tests.
- Prompt-context tests verify deterministic ordering and size bounds.
- Deleted memories no longer appear in later prompts.

Commit:

- `feat(memory): add user-controlled companion memory`

#### M5.2 Add safety, quotas, and graceful degradation

Scope:

- Add moderation and crisis-response handling.
- Add per-user, per-IP, and daily usage limits.
- Store model, latency, token usage, outcome, and error category without storing secrets.
- Return local gentle fallback text when AI is unavailable.
- Add an AI-provider circuit breaker and request cancellation.

Required checks:

- Rate-limit, quota, timeout, moderation, and fallback tests.
- Load test the streaming endpoint with bounded concurrency.
- Verify usage limits survive a server restart.

Commit:

- `feat(safety): add AI safeguards quotas and fallback behavior`

### M6 - v0.14.0 Android lifecycle and reminder hardening

#### M6.1 Make reminder scheduling deterministic

Scope:

- Use unique WorkManager identities and prevent duplicate jobs.
- Cancel and rebuild jobs when settings change.
- Cover cross-midnight do-not-disturb, mute-today rollover, timezone changes,
  and device reboot/reschedule behavior.

Required checks:

- Unit tests for all time-window calculations.
- WorkManager integration tests for unique replacement and cancellation.
- Debug-minute scheduling smoke test on Pixel 6 and PCRM00.

Commit:

- `fix(reminders): make scheduling unique and timezone safe`

#### M6.2 Harden overlay service lifecycle

Scope:

- Persist a temporary-hidden-until timestamp and restore after process death.
- Handle Android 13+ notification permission behavior.
- Verify Android 14/15 foreground-service declarations and startup paths.
- Ensure repeated start, process recreation, and restore cannot duplicate `addView`.

Required checks:

- Repeated start/stop/restart automation.
- Process-kill temporary-hide restore test.
- Android 8, 12, 14, and 15 emulator smoke tests.
- PCRM00 background, lock-screen, screen-off, and reboot tests.

Commit:

- `fix(service): harden overlay lifecycle across Android versions`

### M7 - v0.15.0 final product resources and user experience

#### M7.1 Replace final visual and release resources

Scope:

- Review and replace all final cat PNGs.
- Add production app icon, monochrome/adaptive icon, notification icon, and splash.
- Verify transparent backgrounds, common canvas, center, bottom anchor, and no cropping.
- Adjust bubble placement at both edges and all pet sizes.

Required checks:

- Asset validation and contact sheets.
- Small-screen and large-screen screenshots.
- 80, 120, and 160dp tests at both edges.

Commit:

- `feat(design): finalize pet and application assets`

#### M7.2 Complete account, privacy, and accessibility UI

Scope:

- Add account, conversation history, memory management, privacy, and deletion entries.
- Remove user-visible debug or fallback wording from production screens.
- Add content descriptions, touch-target checks, long-text wrapping, and dynamic-font testing.
- Explain overlay, notifications, account data, and AI processing in plain language.

Required checks:

- Accessibility scan and large-font screenshots.
- Login and logged-out navigation tests.
- No Debug-only tool is visible in release builds.

Commit:

- `feat(ui): finalize account privacy and accessibility flows`

### M8 - v0.16.0 system qualification

#### M8.1 Automated regression suite

Scope:

- Add unit tests for state transitions, reminder time logic, authentication,
  streaming, memory selection, and quota decisions.
- Add server integration tests against PostgreSQL.
- Add Android UI smoke tests for core flows.

Required checks:

- `gradlew.bat test assembleDebug assembleRelease lintDebug`
- Server tests and migrations pass from a clean environment.

Commit:

- `test: add CatLifePet v1 regression coverage`

#### M8.2 Device endurance and compatibility qualification

Scope:

- Trigger every animation at least 100 times.
- Test click, drag, reminder, hide, chat, logout, token expiry, and service-stop interruptions.
- Run the overlay for 2-4 hours while exercising chat and reminders.
- Cover Android 8, 12, 14, and 15 emulators plus PCRM00.

Required checks:

- Zero crashes.
- Zero duplicate pet windows.
- Zero `WindowLeaked` errors.
- No unbounded memory growth.
- No token or API-key leakage in APK, logs, or reports.

Commit:

- `test: qualify v1 device compatibility and endurance`

### M9 - v1.0.0 deployment and release

#### M9.1 Deploy staging and production services

Scope:

- Deploy HTTPS Ktor service and managed PostgreSQL.
- Configure migrations, backups, health checks, sanitized logs, alerts, quotas,
  SMTP credentials, AI credentials, and secret rotation procedures.
- Run smoke tests against staging before production.

Required checks:

- Health, auth, refresh, chat stream, history, deletion, quota, and restart tests.
- Backup restore exercise succeeds.
- TLS and secret scans pass.

Commit:

- `ops: add production deployment configuration`

#### M9.2 Produce signed Android release

Scope:

- Set final `versionCode` and `versionName`.
- Create and securely archive the release signing key outside Git.
- Build signed APK and AAB.
- Finalize privacy policy, permissions explanation, account-deletion instructions,
  store description, screenshots, and icons.
- Generate checksums and the final QA report.

Required checks:

- Signed release installs and upgrades over the last test build.
- Release build connects only to the production HTTPS endpoint.
- Login, AI chat, local pet, reminders, logout, and account deletion pass on PCRM00.
- APK/AAB secret scan passes.

Commit:

- `release: prepare CatLifePet v1.0.0`

## 7. Initial Database Model

```text
users
- id, email_normalized, display_name, created_at, deleted_at

login_codes
- id, email_normalized, code_hash, expires_at, attempts, consumed_at

refresh_sessions
- id, user_id, token_hash, device_label, expires_at, revoked_at, replaced_by

conversations
- id, user_id, title, summary, created_at, updated_at, deleted_at

messages
- id, conversation_id, role, content, sequence, provider_response_id,
  input_tokens, output_tokens, created_at

memories
- id, user_id, category, content, source_message_id, created_at, deleted_at

daily_usage
- user_id, usage_date, request_count, input_tokens, output_tokens
```

## 8. External Inputs And Blocking Gates

Development can continue with local fakes until these checkpoints:

| Required input | Needed by | Local fallback |
| --- | --- | --- |
| SMTP sender/domain credentials | M3 production verification | Development email sender |
| OpenAI API key and approved model | M4 real-provider smoke test | Deterministic fake provider |
| Hosting region, domain, and cloud account | M9 deployment | Docker Compose localhost |
| Release signing identity and secure backup location | M9 Android release | Debug signing only |
| Final privacy/operator information | M7/M9 legal copy | Draft placeholders outside release |

No secret value is written into this document or committed to the repository.

## 9. Definition Of Done For v1.0

CatLifePet v1.0 is complete only when all of the following are true:

- Logged-out users can use the local pet and reminders.
- Users can request a code, log in, refresh a session, log out, and delete their account.
- AI replies stream reliably through CatLifePet Server and conversation history survives restart.
- Users can inspect and delete conversations and memories.
- The AI key is absent from APKs and client logs.
- Safety, timeout, quota, cancellation, and local fallback paths are verified.
- Temporary hide and reminder scheduling survive required lifecycle events.
- All required animations and final visual resources are approved.
- Android compatibility and endurance gates pass with zero crash, duplicate pet, and leak defects.
- Production server, signed APK/AAB, privacy materials, checksums, and final QA report exist.

Estimated remaining effort for one senior engineer: approximately 22-32 focused working
days, plus any external review, store approval, email-domain setup, or public-release
compliance work.
