# CatLifePet Server Recovery Runbook

This runbook is for the Aliyun lightweight server when SSH hangs at:

```text
Connection timed out during banner exchange
```

Observed state on 2026-08-14:

- `http://47.100.9.190/health` returned 200.
- `http://47.100.9.190/privacy` returned 404 because the latest server package was not deployed yet.
- SSH port 22 was reachable at TCP level, but SSH banner exchange timed out.
- Running Gradle directly on the 2 GiB server can saturate CPU or I/O and make SSH unreliable.

## Preferred Deploy Path

Do not compile on the server. Build locally and upload the already-built server distribution:

```powershell
.\ops\deploy-server-installDist.ps1
.\ops\smoke-test.ps1 -BaseUrl http://47.100.9.190
```

After HTTPS is fixed:

```powershell
.\ops\smoke-test.ps1 -BaseUrl https://api.catlifepet.top
```

## Recover SSH From Aliyun Workbench Or VNC

Open Aliyun ECS Workbench/VNC as `admin`, then run:

```bash
uptime
free -h
df -h
ps -eo pid,ppid,pcpu,pmem,cmd --sort=-pcpu | head -30
pgrep -af 'gradle|GradleDaemon|kotlinc|java|ApplicationKt'
```

If Gradle or Kotlin compiler is consuming the machine and the production Java server is still running, stop only the build processes:

```bash
pkill -f 'GradleDaemon|gradle|kotlinc' || true
sudo systemctl restart ssh || sudo systemctl restart sshd || true
```

Do not kill the `ApplicationKt` Java process unless you are ready to redeploy and restart the CatLifePet server.

## Check Current Runtime

```bash
curl -i http://127.0.0.1:8080/health
curl -i http://127.0.0.1:8080/privacy
ps -ef | grep 'ApplicationKt' | grep -v grep
ss -ltnp | grep -E ':80|:443|:8080|:5432'
```

Expected after successful deploy:

- `/health` returns 200.
- `/privacy` returns 200 and contains `CatLifePet`.
- `/privacy.md` returns 200 and contains `https://catlifepet.top/privacy`.

## HTTPS Checks

Nginx should proxy `api.catlifepet.top` to `127.0.0.1:8080`.

```bash
sudo nginx -t
sudo systemctl reload nginx
curl -i https://api.catlifepet.top/health
curl -i https://api.catlifepet.top/privacy
```

The app store privacy URL is `https://catlifepet.top/privacy`. Configure the apex domain to serve or proxy the same `/privacy` content before release.

## Rollback

The lightweight deploy script backs up the previous runtime directory under:

```text
/home/admin/CatLifePet.release-backup.YYYYMMDDHHMMSS/server/build/install/server
```

To roll back from Workbench/VNC:

```bash
cd /home/admin
LATEST_BACKUP="$(ls -dt CatLifePet.release-backup.* | head -1)"
pkill -f 'com.example.catlifepet.server.ApplicationKt' || true
rm -rf CatLifePet/server/build/install/server
cp -a "$LATEST_BACKUP/server/build/install/server" CatLifePet/server/build/install/server
set -a
. CatLifePet/ops/env.production.local
set +a
nohup CatLifePet/server/build/install/server/bin/server > CatLifePet/server.log 2>&1 &
sleep 5
curl --fail --silent http://127.0.0.1:8080/health
```
