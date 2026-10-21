[CmdletBinding()]
param([string]$PcAddress = '192.168.9.6', [string]$TabletAddress = '192.168.9.2', [switch]$InstallKey)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path $PSScriptRoot -Parent
$sshDir = Join-Path $workspace '.cache/tablet-ssh'
$keyPath = Join-Path $sshDir 'id_ed25519'
$publicPath = "$keyPath.pub"
$authPath = 'C:\ProgramData\ssh\administrators_authorized_keys'
if ($InstallKey) {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not ([Security.Principal.WindowsPrincipal]::new($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Se requiere ejecutar como administrador' }
    $publicKey = (Get-Content -LiteralPath $publicPath -Raw).Trim()
    if ($publicKey -notmatch '^ssh-ed25519 [A-Za-z0-9+/=]+ ') { throw 'Clave publica invalida' }
    $existing = if (Test-Path -LiteralPath $authPath) { [IO.File]::ReadAllText($authPath) } else { '' }
    $material = ($publicKey -split ' ')[1]
    if (-not $existing.Contains($material)) {
        if ($existing) { Copy-Item -LiteralPath $authPath -Destination "$authPath.inference-backup-$(Get-Date -Format yyyyMMddHHmmss)" }
        $line = 'from="'+$TabletAddress+','+$PcAddress+',127.0.0.1",no-agent-forwarding,no-port-forwarding,no-pty '+$publicKey
        [IO.File]::WriteAllText($authPath, $existing.TrimEnd()+[Environment]::NewLine+$line+[Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    }
    & icacls.exe $authPath /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'No se pudieron aplicar permisos de authorized_keys' }
    Copy-Item -LiteralPath 'C:\ProgramData\ssh\ssh_host_ed25519_key.pub' -Destination (Join-Path $sshDir 'verified-host.pub') -Force
    return
}
[void](New-Item -ItemType Directory -Path $sshDir -Force)
if (-not (Test-Path -LiteralPath $keyPath)) {
    & ssh-keygen.exe -t ed25519 -N '' -C 'InferenceGemma-TabS9' -f $keyPath -q
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo generar la clave SSH' }
}
$taskSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $keyPath /inheritance:r /grant:r "*$($taskSid):F" '*S-1-5-18:F' | Out-Null
$currentIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
if (([Security.Principal.WindowsPrincipal]::new($currentIdentity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    & $PSCommandPath -InstallKey -PcAddress $PcAddress -TabletAddress $TabletAddress
} else {
    $admin = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$PSCommandPath+'"'),'-InstallKey','-PcAddress',$PcAddress,'-TabletAddress',$TabletAddress) -Verb RunAs -WindowStyle Hidden -Wait -PassThru
    if ($admin.ExitCode -ne 0) { throw 'La instalacion de la clave SSH no se completo' }
}
$hostKey = ((Get-Content (Join-Path $sshDir 'verified-host.pub') -Raw).Trim() -split '\s+')
$configPath = Join-Path $workspace 'agent-config.local.json'
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$config.private_key = [IO.File]::ReadAllText($keyPath)
$config.known_hosts = "$PcAddress $($hostKey[0]) $($hostKey[1])`n127.0.0.1 $($hostKey[0]) $($hostKey[1])`n"
$config.hosts = @([ordered]@{host=$PcAddress;user=[Environment]::UserName;port=22;shell='powershell'})
$config.tool_store_url = "http://${PcAddress}:8000"
[IO.File]::WriteAllText($configPath, ($config | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
& icacls.exe $configPath /inheritance:r /grant:r "*$($taskSid):F" '*S-1-5-18:F' | Out-Null
$knownPath = Join-Path $sshDir 'known_hosts'
[IO.File]::WriteAllText($knownPath, $config.known_hosts, [Text.UTF8Encoding]::new($false))
Write-Output 'Clave exclusiva instalada. Configuracion local actualizada sin mostrar credenciales.'
