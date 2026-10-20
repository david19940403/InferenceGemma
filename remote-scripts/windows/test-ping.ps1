param (
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateNotNullOrEmpty()]
    [string]$Destination,

    [Parameter(Position = 1)]
    [ValidateRange(1, 10)]
    [int]$Limit = 4,

    [ValidateRange(1, 65500)]
    [int]$BufferSize = 32,

    [ValidateRange(100, 5000)]
    [int]$TimeoutMs = 1000,

    [ValidateRange(0, 5)]
    [int]$IntervalSec = 1,

    [switch]$IncludeHistory
)

$ErrorActionPreference = 'Stop'
$ping = $null
try {
    # .NET Ping works with both Windows PowerShell 5.1 and PowerShell 7.
    # Test-Connection has different parameter names, timeout units and result types.
    $ping = [System.Net.NetworkInformation.Ping]::new()
    $buffer = New-Object byte[] $BufferSize
    $successful = 0
    $history = [System.Collections.Generic.List[object]]::new()
    $rtts = [System.Collections.Generic.List[long]]::new()

    for ($attempt = 1; $attempt -le $Limit; $attempt++) {
        $rtt = $null
        $packetBytes = $null
        $failure = $null
        $replyStatus = 'failed'
        try {
            $reply = $ping.Send($Destination, $TimeoutMs, $buffer)
            $replyStatus = $reply.Status.ToString()
            if ($reply.Status -eq [System.Net.NetworkInformation.IPStatus]::Success) {
                $successful++
                $rtt = [long]$reply.RoundtripTime
                $packetBytes = $reply.Buffer.Length
                $rtts.Add($rtt)
                $replyStatus = 'success'
            }
        } catch {
            # Keep the reason instead of hiding parameter, DNS or network errors.
            $failure = $_.Exception.GetBaseException().Message
        }
        $history.Add([ordered]@{
            attempt = $attempt
            timestamp = (Get-Date).ToString('o')
            status = $replyStatus
            rtt_ms = $rtt
            packet_bytes = $packetBytes
            error = $failure
        })
        if ($attempt -lt $Limit -and $IntervalSec -gt 0) { Start-Sleep -Seconds $IntervalSec }
    }

    $status = if ($successful -eq $Limit) { 'success' } elseif ($successful -gt 0) { 'partial' } else { 'error' }
    $summary = [ordered]@{
        status = $status
        destination = $Destination
        total_attempts = $Limit
        successful = $successful
        failed = $Limit - $successful
        packet_loss_pct = [math]::Round((($Limit - $successful) / $Limit) * 100, 2)
        rtt_min_ms = $(if ($rtts.Count) { ($rtts | Measure-Object -Minimum).Minimum } else { $null })
        rtt_max_ms = $(if ($rtts.Count) { ($rtts | Measure-Object -Maximum).Maximum } else { $null })
        rtt_avg_ms = $(if ($rtts.Count) { [math]::Round(($rtts | Measure-Object -Average).Average, 2) } else { $null })
    }
    # Keep the default result within the agent's 400-character feedback budget.
    if ($IncludeHistory) { $summary['history'] = $history }
    $json = $summary | ConvertTo-Json -Compress -Depth 4
    # The agent reads stdout for exit 0, stderr otherwise.
    if ($status -eq 'success') { Write-Output $json; exit 0 }
    [Console]::Error.WriteLine($json)
    exit 1
} catch {
    [Console]::Error.WriteLine((@{ status = 'error'; message = $_.Exception.GetBaseException().Message } | ConvertTo-Json -Compress))
    exit 1
} finally {
    if ($null -ne $ping) { $ping.Dispose() }
}
