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
