param([string]$Action = 'list', [string]$Value = '', [string]$Limit = '5')
. "$PSScriptRoot\tool-common.ps1"
Invoke-CatalogTool {
    Assert-ToolChoice $Action @('list','stop')
    if($Action -eq 'list') {
        $count=Get-ToolNumber $Limit 1 15
        $items=@(Get-Process | Where-Object {[string]::IsNullOrWhiteSpace($Value) -or $_.ProcessName.IndexOf($Value,[StringComparison]::OrdinalIgnoreCase) -ge 0} | Sort-Object WorkingSet64 -Descending | Select-Object -First $count | ForEach-Object {[ordered]@{pid=$_.Id;name=$_.ProcessName;ram_mb=[Math]::Round($_.WorkingSet64/1MB,1)}})
        return [ordered]@{status='success';processes=$items}
    }
    $number=Get-ToolNumber $Value 5 2147483647
    $process=Get-Process -Id $number
    if($number -eq $PID -or $process.ProcessName -match '^(System|Registry|smss|csrss|wininit|winlogon|services|lsass|svchost|sshd|powershell|pwsh|toolstore|wireguard|tailscale|openvpn|dwm|conhost)$') {throw 'Proceso protegido: no se permite detenerlo desde el catalogo'}
    $cim=Get-CimInstance Win32_Process -Filter "ProcessId=$number"
    $owner=Invoke-CimMethod -InputObject $cim -MethodName GetOwnerSid
    if($owner.ReturnValue -ne 0 -or $owner.Sid -ne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value) {throw 'Solo puedes detener procesos de tu propia cuenta'}
    $name=$process.ProcessName
    Stop-Process -InputObject $process -ErrorAction Stop
    return [ordered]@{status='success';action='stopped';pid=$number;name=$name}
}
