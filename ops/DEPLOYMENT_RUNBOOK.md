# CatLifePet Deployment Runbook

This runbook covers staging and production deployment for the Ktor server. The Android app remains usable offline; cloud features require this server.

## Required Secrets

Store these in the deployment platform secret manager, not in Git:

- `POSTGRES_PASSWORD`
- `CATLIFEPET_PUBLIC_BASE_URL`
- `CATLIFEPET_JWT_SECRET`
- `CATLIFEPET_TOKEN_PEPPER`
- `SMTP_HOST`
- `SMTP_PORT`
- `SMTP_USERNAME`
- `SMTP_PASSWORD`
- `SMTP_FROM`
- `SMTP_STARTTLS=true`
- `DEEPSEEK_API_KEY`

Optional tuning:

- `DEEPSEEK_MODEL`
- `DEEPSEEK_BASE_URL`
- `CATLIFEPET_AI_DAILY_REQUEST_LIMIT`
- `CATLIFEPET_AI_USER_REQUESTS_PER_MINUTE`
- `CATLIFEPET_AI_IP_REQUESTS_PER_MINUTE`
- `CATLIFEPET_AI_TIMEOUT_SECONDS`

## Local Production-Like Start

1. Copy `ops/env.production.example` to `ops/env.production.local`.
2. Fill all secrets with staging-safe values.
3. Start the stack:

```powershell
docker compose -f docker-compose.production.yml --env-file ops/env.production.local up -d --build
```

4. Check health:

```powershell
.\ops\smoke-test.ps1 -BaseUrl http://localhost:8080
```

## Staging Deployment

1. Create a managed PostgreSQL database.
2. Configure all secrets in the platform.
3. Deploy the Docker image built from the repository root `Dockerfile`.
4. Set `CATLIFEPET_ENV=production` even for staging. This keeps production fail-fast behavior active.
5. Set `CATLIFEPET_PUBLIC_BASE_URL` to the staging HTTPS URL.
6. Run:

```powershell
.\ops\smoke-test.ps1 -BaseUrl https://staging-api.example.com
```

7. Run one authenticated smoke test with a short-lived access token:

```powershell
.\ops\smoke-test.ps1 -BaseUrl https://staging-api.example.com -AccessToken "<token>"
```

## Production Deployment

1. Confirm staging is green.
2. Confirm a fresh database backup exists.
3. Promote the same image digest that passed staging.
4. Set `CATLIFEPET_PUBLIC_BASE_URL` to the production HTTPS URL.
5. Run the unauthenticated smoke test immediately after deploy.
6. Run authenticated smoke with a test account.
7. Watch logs for 30 minutes for:
   - 5xx spikes
   - `rate_limited` spikes
   - DeepSeek provider errors
   - SMTP delivery errors
   - database connection pool exhaustion

## Database Migrations

Flyway migrations in `server/src/main/resources/db/migration` run at server startup. A failed migration should stop deployment. Do not run multiple migration jobs against the same database unless the platform coordinates single-instance rollout.

## Backup And Restore

Docker Compose local backup:

```powershell
.\ops\backup-postgres.ps1
```

Restore only into a disposable restore environment or after taking another backup:

```powershell
.\ops\restore-postgres.ps1 -BackupFile .\ops\backups\catlifepet-yyyyMMdd-HHmmss.dump
```

Managed PostgreSQL providers should use provider-native scheduled backups plus this manual restore exercise before release.

## Secret Rotation

Rotate in this order:

1. SMTP password.
2. DeepSeek key.
3. `CATLIFEPET_TOKEN_PEPPER` only with a planned logout window, because existing refresh token hashes depend on it.
4. `CATLIFEPET_JWT_SECRET` only with a planned short access-token expiry window.

## Log Hygiene

Server tests verify that auth logs do not contain email codes or tokens. Production logs must still be reviewed after deployment. Do not add request bodies, prompts, AI responses, refresh tokens, email codes, or API keys to application logs.

## Current External Inputs Needed

The repository contains deployable configuration, but real staging/production deployment still needs:

- Hosting target or Kubernetes/Docker host.
- Managed PostgreSQL connection details.
- HTTPS domain and certificate.
- SMTP account.
- DeepSeek API key.
- Operator contact details for privacy policy and account deletion support.
