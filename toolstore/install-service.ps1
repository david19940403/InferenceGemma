param(
    [ValidateSet('Install', 'Uninstall', 'Start', 'Stop', 'Status')]
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
    Start-Process powershell.exe -Verb RunAs -ArgumentList $arguments -Wait
    exit $LASTEXITCODE
}

if ($Action -eq 'Status') {
    Get-Service -Name $ServiceName -ErrorAction Stop
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
