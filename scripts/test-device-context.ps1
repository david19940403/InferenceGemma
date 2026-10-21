[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Serial,
    [Parameter(Mandatory)][ValidatePattern('^/[A-Za-z0-9_./-]+\.litertlm$')][string]$ModelPath,
    [ValidateSet('GPU','CPU')][string]$Backend = 'GPU',
    [int[]]$ContextTokens = @(2048,4096,6144,8192),
    [switch]$RecallOnly,
    [switch]$AllowInterrupt
)
$ErrorActionPreference = 'Stop'
if (!$AllowInterrupt) { throw 'La prueba detiene temporalmente la app y su servidor. Usa -AllowInterrupt cuando no haya trabajos activos.' }
foreach ($budget in $ContextTokens) {
    if ($budget -lt 1024 -or $budget -gt 131072) { throw 'ContextTokens: 1024..131072' }
}
$projectRoot = Split-Path $PSScriptRoot -Parent
$reportDir = Join-Path $projectRoot '.cache/context-probe'
$sessionStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
Push-Location $projectRoot
try {
    & .\gradlew.bat :app:assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo compilar la prueba' }
    & adb -s $Serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo instalar la prueba' }
    foreach ($budget in $ContextTokens) {
        Write-Host "Midiendo $Backend / $budget tokens"
        $recallFlag = if ($RecallOnly) { 'true' } else { 'false' }
        & adb -s $Serial shell am force-stop hn.fredi.inferencelocal
        $output = & adb -s $Serial shell am instrument -w -r `
            -e class hn.fredi.inferencelocal.EngineContextProbeTest `
            -e runNativeProbe true -e recallOnly $recallFlag -e timeout_msec 300000 -e contextTokens $budget -e backend $Backend -e modelPath $ModelPath `
            hn.fredi.inferencelocal.test/androidx.test.runner.AndroidJUnitRunner 2>&1
        $output | Set-Content -Encoding UTF8 (Join-Path $reportDir "$sessionStamp-$Backend-$budget-instrumentation.txt")
        $output | Write-Output
        & adb -s $Serial shell run-as hn.fredi.inferencelocal cat files/context-probe.jsonl |
            Set-Content -Encoding UTF8 (Join-Path $reportDir 'results.jsonl')
        if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'OK \(1 test\)') { throw 'Prueba interrumpida. Revisa los registros antes de aumentar el contexto.' }
        $records = @(Get-Content (Join-Path $reportDir 'results.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
        $latest = $records | Where-Object { $_.event -eq 'starting' } | Select-Object -Last 1
        $runRecords = @($records | Where-Object { $_.run_id -eq $latest.run_id })
        $loaded = $runRecords | Where-Object { $_.event -eq 'loaded' }
        $near = $runRecords | Where-Object { $_.event -eq 'request_ok' -and $_.case -eq $(if ($RecallOnly) { 'recall' } else { 'near_limit' }) }
        $lowMemory = $runRecords | Where-Object { $_.event -ne 'closed' -and $_.available_ram_mb -lt 768 }
        if (!$loaded -or !$near -or $lowMemory) { throw 'Carga/entrada grande fallida o margen de RAM bajo. No se aumenta el contexto.' }
    }
} finally {
    & adb -s $Serial shell am start -n hn.fredi.inferencelocal/.MainActivity | Out-Null
    Pop-Location
    Write-Host "Resultados: $reportDir. Reinicia el acceso remoto y carga el modelo desde la app; las configuraciones guardadas no se modificaron."
}
