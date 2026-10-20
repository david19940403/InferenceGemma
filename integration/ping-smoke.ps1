$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot '..\remote-scripts\windows\test-ping.ps1'
$output = Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString() + '.json')
$errors = $output + '.stderr'
try {
    foreach ($shell in @('powershell.exe', 'pwsh.exe')) {
        if (!(Get-Command $shell -ErrorAction SilentlyContinue)) { continue }
        & $shell -NoLogo -NoProfile -NonInteractive -File $script -Destination 127.0.0.1 -Limit 2 -IntervalSec 0 > $output 2> $errors
        if ($LASTEXITCODE -ne 0) { throw "${shell}: loopback ping failed" }
        $json = Get-Content $output -Raw
        $result = $json | ConvertFrom-Json
        if ($result.successful -ne 2 -or $result.status -ne 'success') { throw 'Invalid success summary' }
        if ($json.Trim().Length -gt 400) { throw 'Summary exceeds agent feedback budget' }
        # TEST-NET-1 is reserved for documentation; it must not report successful replies.
        & $shell -NoLogo -NoProfile -NonInteractive -File $script -Destination 192.0.2.1 -Limit 1 -TimeoutMs 100 -IntervalSec 0 > $output 2> $errors
        if ($LASTEXITCODE -eq 0) { throw 'Unreachable ping incorrectly reported success' }
        $result = Get-Content $errors -Raw | ConvertFrom-Json
        if ($result.status -ne 'error' -or $result.successful -ne 0) { throw 'Invalid error summary' }
        Write-Output "PASS $shell success, failure and bounded JSON feedback"
    }
} finally {
    Remove-Item -LiteralPath $output, $errors -ErrorAction SilentlyContinue
}
