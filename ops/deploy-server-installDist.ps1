param(
    [string] $HostName = "47.100.9.190",
    [string] $User = "admin",
    [string] $RemoteDir = "/home/admin/CatLifePet",
    [int] $Port = 22
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$archive = Join-Path $repoRoot "qa/catlifepet-server-installDist.zip"
$remote = "${User}@${HostName}"
$remoteArchive = "/home/$User/catlifepet-server-installDist.zip"

Push-Location $repoRoot
try {
    .\gradlew.bat --no-daemon :server:installDist --console=plain
    if (Test-Path $archive) {
        Remove-Item $archive -Force
    }
    Compress-Archive -Path "server/build/install/server/*" -DestinationPath $archive -Force
} finally {
    Pop-Location
}

scp -P $Port $archive "${remote}:${remoteArchive}"

$remoteScript = @'
set -euo pipefail
REMOTE_DIR="__REMOTE_DIR__"
ARCHIVE="__REMOTE_ARCHIVE__"
cd "$(dirname "$REMOTE_DIR")"

if [ ! -f "$REMOTE_DIR/ops/env.production.local" ]; then
  echo "Missing $REMOTE_DIR/ops/env.production.local" >&2
  exit 1
fi

BACKUP="$REMOTE_DIR.release-backup.$(date +%Y%m%d%H%M%S)"
STAGE="$REMOTE_DIR.release-stage"
rm -rf "$STAGE"
mkdir -p "$STAGE"
python3 - <<PY
import zipfile
zipfile.ZipFile("$ARCHIVE").extractall("$STAGE")
PY

chmod +x "$STAGE/bin/server"
rm -rf "$BACKUP"
if [ -d "$REMOTE_DIR/server/build/install/server" ]; then
  mkdir -p "$BACKUP/server/build/install"
  cp -a "$REMOTE_DIR/server/build/install/server" "$BACKUP/server/build/install/server"
fi
rm -rf "$REMOTE_DIR/server/build/install/server"
mkdir -p "$REMOTE_DIR/server/build/install"
mv "$STAGE" "$REMOTE_DIR/server/build/install/server"

PID="$(pgrep -f 'com.example.catlifepet.server.ApplicationKt' || true)"
if [ -n "$PID" ]; then
  kill $PID
  sleep 3
fi

set -a
. "$REMOTE_DIR/ops/env.production.local"
set +a
nohup "$REMOTE_DIR/server/build/install/server/bin/server" > "$REMOTE_DIR/server.log" 2>&1 &
sleep 5
curl --fail --silent http://127.0.0.1:8080/health >/dev/null
curl --fail --silent http://127.0.0.1:8080/privacy >/dev/null
echo "CatLifePet server deployment completed."
'@

$remoteScript = $remoteScript.Replace("__REMOTE_DIR__", $RemoteDir).Replace("__REMOTE_ARCHIVE__", $remoteArchive)
$remoteScriptPath = Join-Path $env:TEMP "catlifepet-remote-deploy.sh"
Set-Content -Path $remoteScriptPath -Value $remoteScript -Encoding UTF8
scp -P $Port $remoteScriptPath "${remote}:/home/$User/catlifepet-remote-deploy.sh"
ssh -p $Port $remote "sed -i 's/\r$//' /home/$User/catlifepet-remote-deploy.sh && bash /home/$User/catlifepet-remote-deploy.sh"

Write-Output "Deploy script finished. Run .\ops\smoke-test.ps1 -BaseUrl http://$HostName to verify public routes."
