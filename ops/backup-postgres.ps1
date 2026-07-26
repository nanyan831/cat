param(
    [string] $ComposeFile = "docker-compose.production.yml",
    [string] $Service = "postgres",
    [string] $Database = "catlifepet",
    [string] $User = "catlifepet",
    [string] $OutputDirectory = "ops/backups"
)

$ErrorActionPreference = "Stop"
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outDir = (Resolve-Path -LiteralPath (New-Item -ItemType Directory -Force $OutputDirectory)).Path
$backup = Join-Path $outDir "catlifepet-$timestamp.dump"
$containerBackup = "/tmp/catlifepet-$timestamp.dump"

docker compose -f $ComposeFile exec -T $Service pg_dump -U $User -d $Database -Fc -f $containerBackup
docker compose -f $ComposeFile cp "${Service}:$containerBackup" $backup
docker compose -f $ComposeFile exec -T $Service rm -f $containerBackup
if ((Get-Item $backup).Length -le 0) {
    throw "Backup file is empty: $backup"
}

Write-Output "PostgreSQL backup written to $backup"
