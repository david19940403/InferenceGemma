param([string]$Action, [string]$Path, [string]$Value = '', [string]$Limit = '5')
. "$PSScriptRoot\tool-common.ps1"
Invoke-CatalogTool {
    Assert-ToolChoice $Action @('list','read','mkdir','write','append','copy','move','find')
    $writing = $Action -in @('mkdir','write','append','move')
    $resolved = Get-ToolPath $Path -Write:$writing
    switch ($Action) {
        'list' {
            $count = Get-ToolNumber $Limit 1 20
            if (-not (Test-Path -LiteralPath $resolved -PathType Container)) { throw 'La carpeta no existe' }
            $items = @(Get-ChildItem -LiteralPath $resolved | Sort-Object Name | Select-Object -First ($count+1))
            return [ordered]@{status='success';path=$resolved;more=($items.Count -gt $count);items=@($items | Select-Object -First $count | ForEach-Object { [ordered]@{name=$_.Name;type=$(if($_.PSIsContainer){'directory'}else{'file'});bytes=$(if($_.PSIsContainer){0}else{$_.Length})} })}
        }
        'read' {
            $count = Get-ToolNumber $Limit 1 2000
            if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) { throw 'El archivo no existe' }
            $reader = [IO.StreamReader]::new($resolved, [Text.Encoding]::UTF8, $true)
            try { $buffer = New-Object char[] ($count+1); $read = $reader.ReadBlock($buffer,0,$buffer.Length); $text = [string]::new($buffer,0,[Math]::Min($read,$count)) } finally { $reader.Dispose() }
            if ($text.Contains([char]0)) { throw 'El archivo parece binario; solo se admite texto' }
            return [ordered]@{status='success';text=$text;truncated=($read -gt $count);path=$resolved}
        }
        'mkdir' {
            if (Test-Path -LiteralPath $resolved) { throw 'El destino ya existe' }
            [void][IO.Directory]::CreateDirectory($resolved)
            return [ordered]@{status='success';action='created_directory';path=$resolved}
        }
        {$_ -in @('write','append')} {
            if($Action -eq 'append' -and -not (Test-Path -LiteralPath $resolved -PathType Leaf)) {throw 'El archivo donde agregar texto no existe'}
            if ([Text.Encoding]::UTF8.GetByteCount($Value) -gt 2048) { throw 'Texto demasiado largo: maximo 2048 bytes' }
            if (-not (Test-Path -LiteralPath (Split-Path $resolved -Parent) -PathType Container)) { throw 'La carpeta de destino no existe' }
            $mode = if ($Action -eq 'write') { [IO.FileMode]::CreateNew } else { [IO.FileMode]::Append }
            $stream = [IO.File]::Open($resolved,$mode,[IO.FileAccess]::Write,[IO.FileShare]::None)
            try { $bytes=[Text.Encoding]::UTF8.GetBytes($Value);$stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
            return [ordered]@{status='success';action=$Action;bytes=$bytes.Length;path=$resolved}
        }
        {$_ -in @('copy','move')} {
            if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) { throw 'Solo se admiten archivos existentes, no carpetas' }
            $destination = Get-ToolPath $Value -Write
            if (Test-Path -LiteralPath $destination) { throw 'El destino ya existe; no se sobrescriben archivos' }
            if ($Action -eq 'copy') { [IO.File]::Copy($resolved,$destination,$false) } else { [IO.File]::Move($resolved,$destination) }
            return [ordered]@{status='success';action=$Action;destination=$destination;source=$resolved}
        }
        'find' {
            $count=Get-ToolNumber $Limit 1 20
            if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 100) { throw 'Introduce una parte del nombre a buscar (maximo 100 caracteres)' }
            if (-not (Test-Path -LiteralPath $resolved -PathType Container)) { throw 'La carpeta no existe' }
            $queue=[Collections.Generic.Queue[object]]::new(); $queue.Enqueue(@{path=$resolved;depth=0})
            $found=[Collections.Generic.List[string]]::new(); $visited=0
            while ($queue.Count -gt 0 -and $found.Count -lt $count -and $visited -lt 500) {
                $entry=$queue.Dequeue();$visited++
                foreach($item in @(Get-ChildItem -LiteralPath $entry.path -ErrorAction SilentlyContinue)) {
                    if($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {continue}
                    try { [void](Get-ToolPath $item.FullName) } catch {continue}
                    if($item.Name.IndexOf($Value,[StringComparison]::OrdinalIgnoreCase) -ge 0) { $found.Add($item.FullName);if($found.Count -ge $count){break} }
                    if($item.PSIsContainer -and $entry.depth -lt 3) {$queue.Enqueue(@{path=$item.FullName;depth=($entry.depth+1)})}
                }
            }
            return [ordered]@{status='success';items=@($found.ToArray());limited=($queue.Count -gt 0);folders_scanned=$visited;max_depth=4}
        }
    }
}
