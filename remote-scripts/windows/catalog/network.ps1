param([string]$Action, [string]$Target = '', [string]$Port = '443')
. "$PSScriptRoot\tool-common.ps1"
Invoke-CatalogTool {
    Assert-ToolChoice $Action @('adapters','ping','dns','tcp')
    if($Action -eq 'adapters') {
        $items=@([Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() | Where-Object {$_.OperationalStatus -eq 'Up'} | ForEach-Object { $props=$_.GetIPProperties();$ips=@($props.UnicastAddresses | Where-Object {$_.Address.AddressFamily -eq 'InterNetwork'} | ForEach-Object {$_.Address.ToString()}); if($ips.Count){[ordered]@{interface=$_.Name;ipv4=$ips;gateway=@($props.GatewayAddresses | ForEach-Object {$_.Address.ToString()})}} } | Select-Object -First 6)
        return [ordered]@{status='success';adapters=$items}
    }
    if($Target.Length -gt 253 -or $Target -notmatch '^[A-Za-z0-9][A-Za-z0-9.:-]*$') {throw 'Nombre DNS o direccion IP invalida'}
    switch($Action) {
        'ping' {
            $ping=[Net.NetworkInformation.Ping]::new()
            try { $reply=$ping.Send($Target,3000) } finally {$ping.Dispose()}
            if($reply.Status -ne 'Success') {throw "El destino no respondio al ping: $($reply.Status)"}
            return [ordered]@{status='success';target=$Target;address=$reply.Address.ToString();rtt_ms=$reply.RoundtripTime}
        }
        'dns' { return [ordered]@{status='success';target=$Target;addresses=@([Net.Dns]::GetHostAddresses($Target) | Select-Object -First 8 | ForEach-Object {$_.ToString()})} }
        'tcp' {
            $number=Get-ToolNumber $Port 1 65535
            $client=[Net.Sockets.TcpClient]::new()
            try { $pending=$client.BeginConnect($Target,$number,$null,$null); if(-not $pending.AsyncWaitHandle.WaitOne(4000)){throw 'Tiempo agotado al conectar'}; $client.EndConnect($pending) } finally {$client.Dispose()}
            return [ordered]@{status='success';target=$Target;port=$number;reachable=$true}
        }
    }
}
