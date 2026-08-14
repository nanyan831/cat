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
if ($LASTEXITCODE -ne 0) {
    throw "Server archive upload failed with exit code $LASTEXITCODE."
}

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
SERVICE_NAME="catlifepet-server.service"
rm -rf "$STAGE"
mkdir -p "$STAGE"
python3 - <<PY
import os
import shutil
import zipfile

with zipfile.ZipFile("$ARCHIVE") as archive:
    for member in archive.infolist():
        normalized = member.filename.replace("\\\\", "/").strip("/")
        if not normalized:
            continue
        target = os.path.join("$STAGE", normalized)
        if member.is_dir() or member.filename.endswith(("/", "\\\\")):
            os.makedirs(target, exist_ok=True)
            continue
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with archive.open(member) as source, open(target, "wb") as destination:
            shutil.copyfileobj(source, destination)
PY

chmod +x "$STAGE/bin/server"

server_pids() {
  ps -eo pid=,comm=,args= | awk '$2 == "java" && index($0, "com.example.catlifepet.server.ApplicationKt") {print $1}'
}

HAS_SYSTEMD_SERVICE=0
if command -v systemctl >/dev/null 2>&1 &&
   systemctl list-unit-files "$SERVICE_NAME" --no-legend 2>/dev/null | grep -q "^$SERVICE_NAME"; then
  HAS_SYSTEMD_SERVICE=1
  sudo systemctl stop "$SERVICE_NAME" || true
  sleep 2
fi

PIDS="$(server_pids || true)"
if [ -n "$PIDS" ]; then
  kill $PIDS
  sleep 3
  REMAINING="$(server_pids || true)"
  if [ -n "$REMAINING" ]; then
    kill -9 $REMAINING
    sleep 1
  fi
fi

rm -rf "$BACKUP"
if [ -d "$REMOTE_DIR/server/build/install/server" ]; then
  mkdir -p "$BACKUP/server/build/install"
  cp -a "$REMOTE_DIR/server/build/install/server" "$BACKUP/server/build/install/server"
fi
rm -rf "$REMOTE_DIR/server/build/install/server"
mkdir -p "$REMOTE_DIR/server/build/install"
mv "$STAGE" "$REMOTE_DIR/server/build/install/server"

if [ "$HAS_SYSTEMD_SERVICE" = "1" ]; then
  sudo systemctl daemon-reload
  sudo systemctl start "$SERVICE_NAME"
else
  set -a
  . "$REMOTE_DIR/ops/env.production.local"
  set +a
  nohup "$REMOTE_DIR/server/build/install/server/bin/server" > "$REMOTE_DIR/server.log" 2>&1 &
fi

for attempt in $(seq 1 30); do
  if curl --fail --silent http://127.0.0.1:8080/health >/dev/null &&
     curl --fail --silent http://127.0.0.1:8080/privacy >/dev/null; then
    break
  fi
  sleep 1
done
curl --fail --silent http://127.0.0.1:8080/health >/dev/null
curl --fail --silent http://127.0.0.1:8080/privacy >/dev/null
RUNNING_COUNT="$(server_pids | wc -l | tr -d ' ')"
if [ "$RUNNING_COUNT" != "1" ]; then
  echo "Expected exactly one CatLifePet server process, found $RUNNING_COUNT" >&2
  ps -eo pid=,ppid=,comm=,args= | awk '$3 == "java" && index($0, "com.example.catlifepet.server.ApplicationKt") {print $0}' >&2
  exit 1
fi
echo "CatLifePet server deployment completed."
'@

$remoteScript = $remoteScript.Replace("__REMOTE_DIR__", $RemoteDir).Replace("__REMOTE_ARCHIVE__", $remoteArchive)
$remoteScriptPath = Join-Path $env:TEMP "catlifepet-remote-deploy.sh"
[System.IO.File]::WriteAllText(
    $remoteScriptPath,
    $remoteScript,
    [System.Text.UTF8Encoding]::new($false)
)
scp -P $Port $remoteScriptPath "${remote}:/home/$User/catlifepet-remote-deploy.sh"
if ($LASTEXITCODE -ne 0) {
    throw "Remote deploy script upload failed with exit code $LASTEXITCODE."
}
ssh -p $Port $remote "sed -i 's/\r$//' /home/$User/catlifepet-remote-deploy.sh && bash /home/$User/catlifepet-remote-deploy.sh"
if ($LASTEXITCODE -ne 0) {
    throw "Remote deploy script failed with exit code $LASTEXITCODE."
}

Write-Output "Deploy script finished. Run .\ops\smoke-test.ps1 -BaseUrl http://$HostName to verify public routes."
