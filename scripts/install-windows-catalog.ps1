[CmdletBinding()]
param(
    [string]$Destination = 'C:\Users\David\.toolsexecutiosn',
    [string]$ConfigPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'agent-config.local.json'),
    [string]$CatalogUrl = 'http://127.0.0.1:8000',
    [string]$TargetHost = '',
    [string]$NamePrefix = '',
    [switch]$NoRegister
)
$ErrorActionPreference='Stop'
$workspace=Split-Path $PSScriptRoot -Parent
$source=Join-Path $workspace 'remote-scripts/windows/catalog'
$entries=@(Get-Content (Join-Path $source 'catalog.json') -Raw | ConvertFrom-Json)
$destinationPath=[IO.Path]::GetFullPath($Destination)
if($destinationPath -notmatch '^[A-Za-z]:\\[A-Za-z0-9_.\\-]+$' -or $destinationPath.TrimEnd('\').Length -le 3) {throw 'El destino debe ser una carpeta absoluta local sin espacios'}
if($NamePrefix -and $NamePrefix -notmatch '^[a-z][a-z0-9_]{0,20}_$') {throw 'El prefijo debe terminar en _ y contener solo letras minusculas, numeros o _'}
if(-not (Test-Path -LiteralPath $destinationPath)) {[void](New-Item -ItemType Directory -Path $destinationPath)}
if((Get-Item -LiteralPath $destinationPath).Attributes -band [IO.FileAttributes]::ReparsePoint) {throw 'El destino no puede ser un enlace'}
$backup=Join-Path $workspace ('.cache/catalog-backups/'+[guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $backup -Force)
$config=$null;$headers=@{};$existing=@();$payloads=@()
if(-not $NoRegister) {
    $config=Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
    if(-not $TargetHost) {$TargetHost=$config.hosts[0].host}
    if(-not ($config.hosts | Where-Object {$_.host -eq $TargetHost -and $_.shell -eq 'powershell'})) {throw 'El host debe estar configurado con shell powershell en Android'}
    $node=$config.hosts | Where-Object {$_.host -eq $TargetHost} | Select-Object -First 1
    $headers=@{Authorization='Bearer '+$config.token}
    $existing=Invoke-RestMethod -Uri ($CatalogUrl.TrimEnd('/')+'/api/tools') -Headers $headers -TimeoutSec 10
    foreach($entry in $entries) {
        $name=$NamePrefix+$entry.name
        $old=$existing | Where-Object {$_.name -eq $name} | Select-Object -First 1
        if($old -and $old.target_host -ne $TargetHost) {throw "La tool $name pertenece a otro host; usa -NamePrefix"}
        $template=Join-Path $destinationPath $entry.script
        foreach($arg in $entry.arguments) {$template+=' "'+$arg+'"'}
        $payloads+=,[ordered]@{name=$name;description=$entry.description;target_host=$TargetHost;command_template=$template;parameters=$entry.parameters;user=$node.user;port=$node.port}
    }
    [IO.File]::WriteAllText((Join-Path $backup 'catalog-before.json'),(ConvertTo-Json -InputObject $existing -Depth 15),[Text.UTF8Encoding]::new($false))
}
# Back up only the managed files being updated. Leave other tools untouched.
foreach($file in Get-ChildItem -LiteralPath $source -Filter '*.ps1') {
    $target=Join-Path $destinationPath $file.Name
    if(Test-Path -LiteralPath $target) {Copy-Item -LiteralPath $target -Destination (Join-Path $backup $file.Name)}
    Copy-Item -LiteralPath $file.FullName -Destination $target -Force
}
$published=@()
foreach($payload in $payloads) {
    $old=$existing | Where-Object {$_.name -eq $payload.name} | Select-Object -First 1
    $body=[Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Depth 15))
    $uri=$CatalogUrl.TrimEnd('/')+'/api/tools'; $method='POST'
    if($old) {$uri+='/'+$old.id;$method='PUT'}
    $saved=Invoke-RestMethod -Uri $uri -Headers $headers -Method $method -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 10
    $published+=,[ordered]@{id=$saved.id;name=$saved.name;effect=($entries | Where-Object {($NamePrefix+$_.name) -eq $saved.name}).effect}
}
$manifest=[ordered]@{installed_at=(Get-Date).ToString('o');target_host=$TargetHost;directory=$destinationPath;backup=$backup;tools=$published}
[IO.File]::WriteAllText((Join-Path $destinationPath 'installed-catalog.json'),($manifest | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
[ordered]@{status='success';scripts=@(Get-ChildItem -LiteralPath $source -Filter '*.ps1').Count;registered=$published.Count;destination=$destinationPath;backup=$backup} | ConvertTo-Json -Compress
