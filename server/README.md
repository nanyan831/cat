# CatLifePet Server

Ktor service for CatLifePet account, chat, and AI features. The Android pet and local reminders remain independent of this module.

## Local Run

```powershell
.\gradlew.bat :server:run
```

The development server listens on `http://localhost:8080` by default. Check it with:

```powershell
Invoke-RestMethod http://localhost:8080/health
```

## Environments

- `development`: local defaults are allowed.
- `test`: used by automated tests with in-memory Ktor test hosting.
- `production`: fails fast unless all required variables are present.

Production variables:

- `CATLIFEPET_ENV=production`
- `CATLIFEPET_PUBLIC_BASE_URL=https://...`
- `DATABASE_URL`
- `CATLIFEPET_JWT_SECRET`
- `OPENAI_API_KEY`
- Optional `HOST` and `PORT`

Secrets are never written to normal application logs or configuration errors. Keep them in the deployment platform's secret manager and never in Git.
