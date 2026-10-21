param([string]$Directory='C:\Users\David\.toolsexecutiosn', [switch]$TestDesktop)
$ErrorActionPreference='Stop'
$workspace=Split-Path $PSScriptRoot -Parent
$testRoot=Join-Path $workspace ('.cache/catalog-smoke/'+[guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $testRoot -Force)
$passed=0
function Invoke-Check([string]$Script,[string[]]$Values=@(),[int]$ExpectedExit=0) {
    $scriptPath=Join-Path $Directory $Script
    $command='& '+"'"+$scriptPath.Replace("'","''")+"'"
    foreach($value in $Values){$command+=' '+"'"+$value.Replace("'","''")+"'"}
    $encoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $start=[Diagnostics.ProcessStartInfo]::new('powershell.exe',('-NoLogo -NoProfile -NonInteractive -EncodedCommand '+$encoded))
    $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    $start.StandardOutputEncoding=[Text.Encoding]::UTF8;$start.StandardErrorEncoding=[Text.Encoding]::UTF8
    $process=[Diagnostics.Process]::Start($start)
    $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
    if(-not $process.WaitForExit(45000)){$process.Kill();throw "Timeout $Script"}
    $out=$stdout.GetAwaiter().GetResult();$err=$stderr.GetAwaiter().GetResult()
    if($process.ExitCode -ne $ExpectedExit){throw "$Script exit $($process.ExitCode): $err $out"}
    if($ExpectedExit -eq 0){if($err.Trim()){throw "stderr inesperado $Script : $err"};$data=$out | ConvertFrom-Json}
    else{if($out.Trim()){throw "stdout inesperado en error $Script"};$data=$err | ConvertFrom-Json;if($data.status -ne 'error'){throw 'Contrato JSON error invalido'}}
    $script:passed++
    return $data
}
[void](Invoke-Check disks.ps1)
[void](Invoke-Check network.ps1 @('adapters'))
[void](Invoke-Check network.ps1 @('ping','127.0.0.1'))
[void](Invoke-Check network.ps1 @('dns','localhost'))
[void](Invoke-Check network.ps1 @('tcp','127.0.0.1','8000'))
[void](Invoke-Check network.ps1 @('tcp','127.0.0.1','0') 1)
[void](Invoke-Check processes.ps1 @('list','powershell','2'))
[void](Invoke-Check processes.ps1 @('stop','0') 1)
$child=Start-Process -FilePath "$env:WINDIR\System32\PING.EXE" -ArgumentList @('-t','127.0.0.1') -WindowStyle Hidden -PassThru
try{[void](Invoke-Check processes.ps1 @('stop',[string]$child.Id));if(-not $child.WaitForExit(3000)){throw 'El proceso de prueba no termino'}}finally{if(-not $child.HasExited){$child.Kill()};$child.Dispose()}
[void](Invoke-Check services.ps1 @('list','sshd','2'))
[void](Invoke-Check services.ps1 @('stop','sshd') 1)
[void](Invoke-Check events.ps1 @('System','24','1'))
$folder=Join-Path $testRoot 'Folder with spaces'
[void](Invoke-Check files.ps1 @('mkdir',$folder))
$file=Join-Path $folder 'note.txt';$copy=Join-Path $folder 'copy.txt';$moved=Join-Path $folder 'renamed.txt'
$text='Hola desde el catalogo; texto literal $env:USERNAME'
[void](Invoke-Check files.ps1 @('write',$file,$text))
$read=Invoke-Check files.ps1 @('read',$file,'_','200')
if($read.text -ne $text){throw 'El texto no fue tratado como datos literales'}
[void](Invoke-Check files.ps1 @('append',$file,' OK'))
$read=Invoke-Check files.ps1 @('read',$file,'_','200')
if($read.text -ne ($text+' OK')){throw 'Append no conservo contenido'}
[void](Invoke-Check files.ps1 @('write',$file,'sobrescribir') 1)
[void](Invoke-Check files.ps1 @('append',(Join-Path $folder 'missing.txt'),'no crear') 1)
[void](Invoke-Check files.ps1 @('copy',$file,$copy))
[void](Invoke-Check files.ps1 @('move',$copy,$moved))
$list=Invoke-Check files.ps1 @('list',$folder,'_','5')
if(@($list.items).Count -ne 2){throw 'Contenido inesperado de carpeta'}
$find=Invoke-Check files.ps1 @('find',$folder,'renamed','5')
if(@($find.items).Count -ne 1){throw 'Busqueda por nombre fallo'}
[void](Invoke-Check files.ps1 @('write',('C:\ProgramData\catalog-'+[guid]::NewGuid().ToString('N')+'.txt'),'blocked') 1)
[void](Invoke-Check files.ps1 @('read','C:\Users\David\.ssh\id_ed25519','_','200') 1)
[void](Invoke-Check files.ps1 @('list','relative-path') 1)
if($TestDesktop){[void](Invoke-Check desktop-open.ps1 @('calculator'))}
if(Test-Path -LiteralPath (Join-Path $Directory 'catalog-access.enc')) {
    $summary=Invoke-Check help.ps1 @('summary')
    if($summary.total -lt 1 -or $summary.groups.ayuda -lt 1){throw 'Ayuda no detecta el catalogo activo'}
    $first=Invoke-Check help.ps1 @('list','','1','2')
    $second=Invoke-Check help.ps1 @('list','','2','2')
    if($first.next -ne 2 -or @($first.names).Count -ne 2 -or $first.names[0] -in $second.names){throw 'Paginacion invalida'}
    $network=Invoke-Check help.ps1 @('search','red','1','10')
    if('network_ping' -notin $network.names){throw 'Busqueda de ayuda por categoria fallo'}
    $detail=Invoke-Check help.ps1 @('detail','files_read_text')
    if('path' -notin $detail.required -or -not $detail.example){throw 'Ayuda de parametros incompleta'}
    [void](Invoke-Check help.ps1 @('detail','tool_inexistente') 1)
    [void](Invoke-Check help.ps1 @('list','','0') 1)
}
[ordered]@{status='success';passed=$passed;test_directory=$testRoot;runtime='Windows PowerShell 5.1'} | ConvertTo-Json -Compress
