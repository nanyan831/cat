# CatLifePet Server

Ktor service for CatLifePet account, chat, and AI features. The Android pet and local reminders remain independent of this module.

## Local Run

Start PostgreSQL, load the development variables, and run Ktor:

```powershell
docker compose up -d postgres
$env:DATABASE_URL = "jdbc:postgresql://localhost:5432/catlifepet"
$env:DATABASE_USER = "catlifepet"
$env:DATABASE_PASSWORD = "local-catlifepet-only"
.\gradlew.bat :server:run
```

The development server listens on `http://localhost:8080` by default. Check it with:

```powershell
Invoke-RestMethod http://localhost:8080/health
```

Flyway applies the versioned scripts in `server/src/main/resources/db/migration` at
startup whenever database configuration is present. `:server:test` starts a real,
temporary PostgreSQL process for migration and repository integration tests, so the
database checks do not silently fall back to H2.

## Production-like Docker

Deployment artifacts live at the repository root:

- `Dockerfile`
- `docker-compose.production.yml`
- `ops/env.production.example`
- `ops/DEPLOYMENT_RUNBOOK.md`
- `ops/smoke-test.ps1`
- `ops/backup-postgres.ps1`
- `ops/restore-postgres.ps1`

Start a local production-like stack after filling `ops/env.production.local`:

```powershell
docker compose -f docker-compose.production.yml --env-file ops/env.production.local up -d --build
.\ops\smoke-test.ps1 -BaseUrl http://localhost:8080
```

Real staging and production deployment require an HTTPS host, managed PostgreSQL,
SMTP credentials, and an OpenAI API key stored in the deployment secret manager.
See `ops/DEPLOYMENT_RUNBOOK.md` for rollout, backup, restore, smoke, and rotation
steps.

## Environments

- `development`: local defaults are allowed.
- `test`: used by automated tests with in-memory Ktor test hosting.
- `production`: fails fast unless all required variables are present.

Production variables:

- `CATLIFEPET_ENV=production`
- `CATLIFEPET_PUBLIC_BASE_URL=https://...`
- `DATABASE_URL`
- `DATABASE_USER`
- `DATABASE_PASSWORD`
- `CATLIFEPET_JWT_SECRET`
- `CATLIFEPET_TOKEN_PEPPER`
- `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM`
- `SMTP_STARTTLS=true`
- `OPENAI_API_KEY`
- Optional `OPENAI_MODEL` (defaults to `gpt-5.6-luna`)
- Optional `CATLIFEPET_AI_PROVIDER=openai` (automatically selected when a key exists)
- Optional `HOST` and `PORT`

Secrets are never written to normal application logs or configuration errors. Keep them in the deployment platform's secret manager and never in Git.

## Authentication

Passwordless account endpoints are available when the database is configured:

```text
POST   /v1/auth/code/request
POST   /v1/auth/code/verify
POST   /v1/auth/refresh
POST   /v1/auth/logout
GET    /v1/me
PATCH  /v1/me
DELETE /v1/me
```

Development login messages are written to `server/build/dev-mailbox` and are never
printed in logs. Production sends them through authenticated SMTP and requires:

- `CATLIFEPET_TOKEN_PEPPER` independent from the JWT signing secret
- `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, and `SMTP_FROM`
- `SMTP_STARTTLS=true`

Access tokens expire after 15 minutes. Refresh tokens expire after 30 days, rotate on
every use, and revoke the entire token family when an already-rotated token is replayed.

## Conversations and streaming

All conversation endpoints require a valid access token:

```text
POST   /v1/conversations
GET    /v1/conversations
DELETE /v1/conversations
GET    /v1/conversations/{id}/messages
POST   /v1/conversations/{id}/messages/stream
DELETE /v1/conversations/{id}
```

The streaming endpoint accepts `content` and a client-generated UUID in
`clientMessageId`. Responses use `text/event-stream` and emit ordered `delta`,
`completed`, or `error` events. Retrying the same client ID replays an already completed
reply or resumes a failed/cancelled turn without inserting duplicate message rows.

Conversation ownership is checked on every read, write, and delete. Missing and
cross-account conversations both return `404`. The server cancels model generation when
the client disconnects, stores final replies with a compare-and-set update, and orders
history by a per-conversation sequence number.

## User-controlled companion memory

Memory is created only from an explicit user action. The server does not persist inferred
sensitive facts from chat messages. All endpoints require a valid access token:

```text
POST   /v1/memories
GET    /v1/memories
DELETE /v1/memories
DELETE /v1/memories/{id}
```

Allowed kinds are `nickname`, `preferred_address`, `routine`, and `preference`.
Cross-account reads or deletes return `404`. Prompt construction includes at most 20
recent messages, a bounded conversation summary, and up to eight active memories in a
deterministic order. Deleting a memory excludes it from the next model request.

## AI Gateway

The server owns all model credentials. Never add `OPENAI_API_KEY` to Android Gradle
properties, resources, `BuildConfig`, APKs, or client logs.

Development without an API key uses `DeterministicFakeAiProvider`; production requires
the OpenAI provider and a server-side key. The OpenAI adapter uses the Responses API and
defaults to `gpt-5.6-luna`, which is appropriate for a cost-sensitive, high-volume
companion flow. Override the model with `OPENAI_MODEL` after running representative
quality and cost evaluations.

Privacy and limits:

- `OPENAI_STORE_RESPONSES=false` by default. The app stores its own user-visible chat
  history and does not rely on provider-side response storage.
- Every request must include a stable, privacy-preserving `safety_identifier`; do not
  send an email address or raw account ID.
- Defaults: 30 second timeout, 12,000 input characters, and 500 output tokens.
- Defaults: 100 model turns per user/day, 12 turns per user/minute, and 40 turns per
  source IP/minute. Daily reservations are atomic and survive process restarts.
- Retryable provider failures and an open circuit breaker return a persisted local gentle
  reply. Crisis phrases receive a fixed safety-oriented response without calling AI.
- `ai_request_audit` stores model, outcome, error category, token counts, and latency; it
  deliberately has no prompt or response-content column.
- Override with `CATLIFEPET_AI_TIMEOUT_SECONDS`,
  `CATLIFEPET_AI_MAX_INPUT_CHARACTERS`, and `CATLIFEPET_AI_MAX_OUTPUT_TOKENS`.
- Quota and circuit settings use `CATLIFEPET_AI_DAILY_REQUEST_LIMIT`,
  `CATLIFEPET_AI_USER_REQUESTS_PER_MINUTE`, `CATLIFEPET_AI_IP_REQUESTS_PER_MINUTE`,
  `CATLIFEPET_AI_CIRCUIT_FAILURE_THRESHOLD`, and `CATLIFEPET_AI_CIRCUIT_OPEN_SECONDS`.
- `OPENAI_BASE_URL` is configurable for controlled testing; production requires HTTPS.

OpenAI references:

- Responses and streaming: https://developers.openai.com/api/docs/guides/streaming-responses
- Model guidance: https://developers.openai.com/api/docs/guides/latest-model
- Data controls: https://developers.openai.com/api/docs/guides/your-data

An optional paid smoke test runs only when both variables are present:

```powershell
$env:OPENAI_API_KEY = "..."
$env:CATLIFEPET_RUN_OPENAI_SMOKE = "true"
.\gradlew.bat :server:test --tests "*OpenAiResponsesProviderTest.optional real provider smoke test"
```
