param([string]$Log = 'System', [string]$Hours = '24', [string]$Limit = '3')
. "$PSScriptRoot\tool-common.ps1"
Invoke-CatalogTool {
    Assert-ToolChoice $Log @('System','Application')
    $hoursCount=Get-ToolNumber $Hours 1 168; $count=Get-ToolNumber $Limit 1 10
    try { $records=@(Get-WinEvent -FilterHashtable @{LogName=$Log;Level=@(1,2,3);StartTime=(Get-Date).AddHours(-$hoursCount)} -MaxEvents $count -ErrorAction Stop) }
    catch { if($_.FullyQualifiedErrorId -like 'NoMatchingEventsFound*') {$records=@()} else {throw} }
    $items=@($records | ForEach-Object {$msg=($_.Message -replace '\s+',' '); if($msg.Length -gt 180){$msg=$msg.Substring(0,180)}; [ordered]@{id=$_.Id;time=$_.TimeCreated.ToString('s');source=$_.ProviderName;message=$msg}})
    return [ordered]@{status='success';log=$Log;hours=$hoursCount;events=$items}
}
