param(
    [Parameter(Mandatory = $true)]
    [string] $BackupFile,

    [string] $ComposeFile = "docker-compose.production.yml",
    [string] $Service = "postgres",
    [string] $Database = "catlifepet",
    [string] $User = "catlifepet"
)

$ErrorActionPreference = "Stop"
$backup = (Resolve-Path -LiteralPath $BackupFile).Path
$containerBackup = "/tmp/catlifepet-restore.dump"

Write-Warning "This restores into database '$Database' through docker compose service '$Service'. Make sure the target is a disposable restore environment or has been backed up."
docker compose -f $ComposeFile cp $backup "${Service}:$containerBackup"
docker compose -f $ComposeFile exec -T $Service dropdb -U $User --if-exists $Database
docker compose -f $ComposeFile exec -T $Service createdb -U $User $Database
docker compose -f $ComposeFile exec -T $Service pg_restore -U $User -d $Database --clean --if-exists $containerBackup

Write-Output "PostgreSQL restore completed from $backup"
