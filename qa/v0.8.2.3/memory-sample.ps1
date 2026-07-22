param(
    [int]$DurationMinutes = 30,
    [int]$IntervalMinutes = 10,
    [string]$PackageName = "com.example.catlifepet",
    [string]$OutputPath = "memory-samples-30min.txt"
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found: $adb" }

function Get-MemorySample([string]$Label) {
    $AppPid = (& $adb shell pidof $PackageName).Trim()
    if ([string]::IsNullOrWhiteSpace($AppPid)) {
        throw "Process not found: $PackageName"
    }
    $meminfo = (& $adb shell dumpsys meminfo $PackageName) -join "`n"
    $pssMatch = [regex]::Match($meminfo, "TOTAL PSS:\s*([0-9,]+)")
    $rssMatch = [regex]::Match($meminfo, "TOTAL RSS:\s*([0-9,]+)")
    if (-not $pssMatch.Success -or -not $rssMatch.Success) {
        throw "Could not parse TOTAL PSS/RSS for PID $AppPid"
    }
    $timestamp = Get-Date -Format o
    "$Label`t$timestamp`t$AppPid`t$($pssMatch.Groups[1].Value.Replace(',', ''))`t$($rssMatch.Groups[1].Value.Replace(',', ''))"
}

"TIME`tTIMESTAMP`tPID`tPSS_KB`tRSS_KB" | Set-Content -Encoding UTF8 $OutputPath
$start = Get-Date
$index = 0
Get-MemorySample "T+0m" | Add-Content -Encoding UTF8 $OutputPath
$nextMinute = $IntervalMinutes
while ($nextMinute -le $DurationMinutes) {
    Start-Sleep -Seconds ($IntervalMinutes * 60)
    Get-MemorySample "T+$nextMinute`m" | Add-Content -Encoding UTF8 $OutputPath
    $nextMinute += $IntervalMinutes
}
Get-Content $OutputPath
