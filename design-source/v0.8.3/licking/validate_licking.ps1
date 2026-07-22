param(
    [string]$Python = ""
)

$ErrorActionPreference = "Stop"
$bundledPython = Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
if ([string]::IsNullOrWhiteSpace($Python)) {
    $Python = if (Test-Path $bundledPython) { $bundledPython } else { "python" }
}
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$validator = Join-Path $projectRoot "design-source\v0.8.3\validate_animation_assets.py"
$sheetBuilder = Join-Path $projectRoot "design-source\v0.8.3\build_contact_sheets.py"
$validation = Join-Path $PSScriptRoot "validation\licking-validation.txt"
$anchorReport = Join-Path $PSScriptRoot "validation\licking-anchor-report.txt"
$qaRoot = Join-Path $projectRoot "qa\v0.8.3.1"

New-Item -ItemType Directory -Force -Path (Split-Path $validation), $qaRoot | Out-Null

& $Python $validator `
    --project-root $projectRoot `
    --animation licking `
    --source-stage incoming `
    --strict `
    --output $validation `
    --anchor-output $anchorReport
$validationExit = $LASTEXITCODE

Copy-Item $validation (Join-Path $qaRoot "licking-validation.txt") -Force
Copy-Item $anchorReport (Join-Path $qaRoot "licking-anchor-report.txt") -Force

if ($validationExit -ne 0) {
    Write-Host "LICKING validation did not pass. No files were copied to approved or drawable-nodpi."
    exit $validationExit
}

& $Python $sheetBuilder `
    --project-root $projectRoot `
    --animation licking `
    --source-stage incoming `
    --qa-version v0.8.3.1
if ($LASTEXITCODE -ne 0) {
    Write-Host "Contact sheet generation failed. No files were promoted."
    exit $LASTEXITCODE
}

Write-Host "Automated checks passed. Manual contact-sheet and Pixel 6 approval are still required before promotion."
