param([string]$Action='summary',[string]$Query='',[string]$Page='1',[string]$Limit='5')
. "$PSScriptRoot\tool-common.ps1"
Invoke-CatalogTool {
    Assert-ToolChoice $Action @('summary','list','search','detail')
    $pageNumber=Get-ToolNumber $Page 1 10000
    $pageSize=Get-ToolNumber $Limit 1 10
    $accessPath=Join-Path $PSScriptRoot 'catalog-access.enc'
    if(-not (Test-Path -LiteralPath $accessPath)){throw 'Ejecuta install-windows-catalog.ps1 para configurar el acceso de ayuda'}
    Add-Type -AssemblyName System.Security
    $protected=[IO.File]::ReadAllBytes($accessPath)
    $clear=[Security.Cryptography.ProtectedData]::Unprotect($protected,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
    try { $access=[Text.Encoding]::UTF8.GetString($clear) | ConvertFrom-Json } finally { [Array]::Clear($clear,0,$clear.Length) }
    $uri=[uri]$access.url
    if($uri.Scheme -notin @('http','https') -or $uri.UserInfo -or $uri.Query -or $uri.Fragment){throw 'URL del catalogo invalida'}
    $all=Invoke-RestMethod -Uri ($access.url.TrimEnd('/')+'/api/tools') -Headers @{Authorization='Bearer '+$access.token} -TimeoutSec 8 -ErrorAction Stop
    $tools=@($all | Sort-Object name)
    $examples=@{
        tools_help='Que herramientas tienes; lista tools; ayuda de archivos'
        sysinfo_windows='Consulta la memoria del sistema Windows'
        windows_disk_space='Cuanto espacio libre queda en el disco C'
        windows_network_adapters='Muestra las direcciones IP de la VPN'
        network_ping='Haz ping a 192.168.9.2'
        network_tcp_port='Comprueba el puerto 8000 de 192.168.9.6'
        network_dns_lookup='Resuelve localhost'
        windows_process_list='Muestra los procesos que consumen mas memoria'
        windows_process_stop='Cierra el proceso con PID 12345'
        windows_service_list='Consulta el estado de Spooler'
        windows_service_control='Reinicia el servicio Spooler'
        windows_event_errors='Muestra los ultimos errores del registro System'
        files_list='Lista C:\Users\David\Documents'
        files_read_text='Lee C:\Users\David\Documents\nota.txt'
        files_find_name='Busca informe en C:\Users\David\Documents'
        files_create_directory='Crea C:\Users\David\Documents\Pruebas'
        files_create_text='Crea C:\Users\David\Documents\nota.txt con el texto hola'
        files_append_text='Agrega hola al final de C:\Users\David\Documents\nota.txt'
        files_copy='Copia C:\Users\David\Documents\a.txt a C:\Users\David\Documents\b.txt'
        files_move='Renombra C:\Users\David\Documents\a.txt como C:\Users\David\Documents\b.txt'
        windows_open_application='Abre la calculadora'
    }
    if($Action -eq 'summary') {
        $groups=[ordered]@{archivos=0;red=0;windows=0;ayuda=0;otros=0}
        foreach($tool in $tools){switch -Regex ($tool.name){'^files_'{$groups.archivos++;break};'^network_|^windows_network'{$groups.red++;break};'^windows_|^sysinfo_'{$groups.windows++;break};'^tools_help$'{$groups.ayuda++;break};default{$groups.otros++}}}
        return [ordered]@{status='success';total=$tools.Count;groups=$groups;help='Usa list para nombres, search con tema y detail con nombre exacto. Listados por paginas.'}
    }
    if($Query.Length -gt 200){throw 'Consulta demasiado larga; maximo 200 caracteres'}
    if($Action -eq 'detail') {
        $tool=$tools | Where-Object {$_.name -eq $Query.Trim()} | Select-Object -First 1
        if(-not $tool){throw 'Tool no encontrada; busca por tema con search o consulta list'}
        $props=$tool.parameters.properties.PSObject.Properties
        $params=@($props | ForEach-Object {$_.Name})
        $choices=[ordered]@{}
        foreach($prop in $props){if($prop.Value.PSObject.Properties['enum']){$choices[$prop.Name]=@($prop.Value.enum)}}
        $example=if($examples.ContainsKey($tool.name)){$examples[$tool.name]}else{$tool.description.Substring(0,[Math]::Min(100,$tool.description.Length))}
        return [ordered]@{status='success';name=$tool.name;params=$params;required=@($tool.parameters.required);choices=$choices;example=$example;host=$tool.target_host}
    }
    if($Action -eq 'search' -and [string]::IsNullOrWhiteSpace($Query)){throw 'Introduce un tema o nombre para buscar'}
    if(-not [string]::IsNullOrWhiteSpace($Query)){
        $normalized=$Query.ToLowerInvariant().Normalize([Text.NormalizationForm]::FormD) -replace '\p{Mn}',''
        $aliases=@{archivos='files';carpetas='files';red='network';procesos='process';servicios='service';ayuda='help';discos='disk';eventos='event';aplicaciones='application'}
        if($aliases.ContainsKey($normalized)){$normalized=$aliases[$normalized]}
        $terms=@($normalized -split '\s+' | Where-Object {$_.Length -gt 1})
        $tools=@($tools | Where-Object {$haystack=($_.name+' '+$_.description).ToLowerInvariant().Normalize([Text.NormalizationForm]::FormD) -replace '\p{Mn}',''; $matched=$true;foreach($term in $terms){if($haystack.IndexOf($term,[StringComparison]::Ordinal) -lt 0){$matched=$false;break}};$matched})
    }
    $offset=($pageNumber-1)*$pageSize
    $names=@($tools | Select-Object -Skip $offset -First $pageSize | ForEach-Object {$_.name})
    return [ordered]@{status='success';total=$tools.Count;page=$pageNumber;next=$(if($offset+$names.Count -lt $tools.Count){$pageNumber+1}else{$null});names=$names}
}
