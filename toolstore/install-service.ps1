param(
    [ValidateSet('Install', 'Uninstall', 'Start', 'Stop', 'Status', 'Update')]
    [string]$Action = 'Install',
    [string]$ServiceName = 'InferenceGemmaToolStore'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$exe = Join-Path $root 'toolstore.exe'
$envFile = Join-Path $root 'toolstore.env'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]$identity
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $MyInvocation.MyCommand.Path, '-Action', $Action, '-ServiceName', $ServiceName)
    $elevated = Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden -ArgumentList $arguments -Wait -PassThru
    exit $elevated.ExitCode
}

if ($Action -eq 'Status') {
    Get-Service -Name $ServiceName -ErrorAction Stop
    exit 0
}

if ($Action -eq 'Update') {
    $cacheDir = Join-Path (Split-Path $root -Parent) '.cache'
    $staged = Join-Path $cacheDir 'toolstore-next.exe'
    if (-not (Test-Path -LiteralPath $staged -PathType Leaf)) { throw 'Compila primero .cache/toolstore-next.exe' }
    $backup = Join-Path $cacheDir ('toolstore-before-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.exe')
    Copy-Item -LiteralPath $exe -Destination $backup
    Stop-Service -Name $ServiceName -Force
    try {
        Copy-Item -LiteralPath $staged -Destination $exe -Force
        Start-Service -Name $ServiceName
        'Updated successfully' | Set-Content -Encoding UTF8 (Join-Path $cacheDir 'toolstore-update-result.txt')
    } catch {
        Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue
        Copy-Item -LiteralPath $backup -Destination $exe -Force
        Start-Service -Name $ServiceName
        throw
    }
    exit 0
}

if ($Action -eq 'Uninstall') {
    $service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    if ($service) {
        if ($service.Status -ne 'Stopped') { Stop-Service -Name $ServiceName -Force }
        sc.exe delete $ServiceName | Out-Host
    }
    exit 0
}

if (-not (Test-Path $exe)) { throw "No existe $exe. Compila primero toolstore.exe." }
if (-not (Test-Path $envFile)) { throw "Copia toolstore.env.example como toolstore.env y configura sus valores." }

if ($Action -eq 'Install') {
    $existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    if ($existing) { throw "El servicio ya existe. Usa -Action Uninstall para recrearlo." }
    New-Service -Name $ServiceName -BinaryPathName "`"$exe`" --service" -DisplayName 'InferenceGemma Tool Store' -Description 'Catálogo local SQLite para el motor Android' -StartupType Automatic | Out-Null
    Write-Output "Servicio instalado: $ServiceName"
}

if ($Action -eq 'Start' -or $Action -eq 'Install') { Start-Service -Name $ServiceName }
if ($Action -eq 'Stop') { Stop-Service -Name $ServiceName -Force }
