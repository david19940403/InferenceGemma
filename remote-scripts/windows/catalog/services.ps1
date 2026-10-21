param([string]$Action = 'list', [string]$Name = '', [string]$Limit = '5')
. "$PSScriptRoot\tool-common.ps1"
Invoke-CatalogTool {
    Assert-ToolChoice $Action @('list','start','stop','restart')
    if($Action -eq 'list') {
        $count=Get-ToolNumber $Limit 1 15
        $items=@(Get-Service | Where-Object {[string]::IsNullOrWhiteSpace($Name) -or $_.Name.IndexOf($Name,[StringComparison]::OrdinalIgnoreCase) -ge 0 -or $_.DisplayName.IndexOf($Name,[StringComparison]::OrdinalIgnoreCase) -ge 0} | Select-Object -First $count | ForEach-Object {[ordered]@{name=$_.Name;state=$_.Status.ToString()}})
        return [ordered]@{status='success';services=$items}
    }
    if($Name -notmatch '^[A-Za-z0-9_.-]{1,128}$') {throw 'Usa el nombre exacto de un servicio; no se admiten comodines'}
    $service=Get-Service -Name $Name
    if($service.Name -match '(?i)sshd|InferenceGemma|wireguard|tailscale|openvpn' -or $service.Name -in @('RpcSs','DcomLaunch','EventLog','WinDefend','mpssvc','BFE','SamSs','LanmanWorkstation','Dhcp','Dnscache','nsi','Schedule')) {throw 'Servicio protegido: mantiene Windows, la seguridad o la conexion remota'}
    switch($Action) {
        'start' {Start-Service -InputObject $service}
        'stop' {Stop-Service -InputObject $service}
        'restart' {Restart-Service -InputObject $service}
    }
    $expected=if($Action -eq 'stop'){'Stopped'}else{'Running'}
    $service.WaitForStatus([ServiceProcess.ServiceControllerStatus]::$expected,[TimeSpan]::FromSeconds(20))
    $service.Refresh()
    return [ordered]@{status='success';action=$Action;name=$Name;state=$service.Status.ToString()}
}
