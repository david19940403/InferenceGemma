Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'
$global:ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

function Invoke-CatalogTool([scriptblock]$Body) {
    try {
        $result = & $Body
        $json = ConvertTo-Json -InputObject $result -Depth 8 -Compress
        if ([Text.Encoding]::UTF8.GetByteCount($json) -gt 30000) { throw 'Resultado demasiado grande; reduce el limite o filtra la consulta' }
        [Console]::Out.WriteLine($json)
        exit 0
    } catch {
        [Console]::Error.WriteLine((ConvertTo-Json -InputObject ([ordered]@{status='error';message=$_.Exception.Message}) -Compress))
        exit 1
    }
}
function Get-ToolNumber([string]$Value, [int]$Minimum, [int]$Maximum) {
    $number = 0
    if (-not [int]::TryParse($Value, [ref]$number) -or $number -lt $Minimum -or $number -gt $Maximum) { throw "Numero fuera de rango: $Minimum a $Maximum" }
    return $number
}
function Assert-ToolChoice([string]$Value, [string[]]$Allowed) {
    if ($Value -notin $Allowed) { throw ('Opcion invalida; usa '+($Allowed -join ', ')) }
}
function Get-ToolPath([string]$Value, [switch]$Write) {
    if ($Value -notmatch '^[A-Za-z]:[\\/]' -or $Value.StartsWith('\\')) { throw 'Usa una ruta absoluta de una unidad local, por ejemplo C:\Users\David\Documents' }
    $path = [IO.Path]::GetFullPath($Value)
    if ($path -match '[*?]' -or $path.Substring(2).Contains(':')) { throw 'No se admiten comodines ni flujos alternativos NTFS' }
    if ($path -match '(?i)(^|[\\/])(\.ssh|\.aws|\.azure|\.git|\.gnupg|\.codex|credentials|secrets)([\\/]|$)' -or
        $path -match '(?i)(^|[\\/])(\.env(?:\..*)?|agent-config\.local\.json|toolstore\.env|authorized_keys|known_hosts|id_(rsa|ed25519))$' -or
        $path -match '(?i)\.(pem|key|pfx|p12|kdbx|enc)$') { throw 'La ruta contiene credenciales o configuracion privada' }
    if ($Write -and ($path.TrimEnd('\').Length -le 2 -or $path -match '(?i)^[A-Z]:\\(Windows|Program Files(?: \(x86\))?|ProgramData)(\\|$)')) { throw 'No se permiten escrituras en esta ruta del sistema' }
    if($Write -and ($path.Equals($PSScriptRoot,[StringComparison]::OrdinalIgnoreCase) -or $path.StartsWith($PSScriptRoot.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase))) {throw 'Los scripts del catalogo se actualizan desde el instalador, no desde una tool de archivos'}
    $cursor = $path
    while ($cursor -and $cursor.Length -gt 3) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'No se admiten enlaces ni puntos de reanalisis en la ruta' }
        }
        $cursor = Split-Path -Path $cursor -Parent
    }
    return $path
}
