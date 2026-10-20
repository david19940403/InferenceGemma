param(
    [Parameter(Mandatory=$true,Position=0)]
    [ValidateSet('vpn','git','adb','servicios','imagenes','n8n','apks')]
    [string]$Action,
    [Parameter(Position=1)] [string]$Subject = ''
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
try {
    $python = (Get-Command python.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $entry = Join-Path $PSScriptRoot 'daily_tools.py'
    & $python $entry $Action $Subject
    exit $LASTEXITCODE
} catch {
    [Console]::Error.WriteLine('{"status":"error","code":"DEPENDENCY_ERROR","message":"No se pudo iniciar Python para las tools de esta PC"}')
    exit 1
}
