. "$PSScriptRoot\tool-common.ps1"
Invoke-CatalogTool {
    $items=@(Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3' | ForEach-Object {[ordered]@{drive=$_.DeviceID;free_gb=[Math]::Round($_.FreeSpace/1GB,1);total_gb=[Math]::Round($_.Size/1GB,1);free_pct=$(if($_.Size){[Math]::Round(100*$_.FreeSpace/$_.Size,1)}else{0})}})
    return [ordered]@{status='success';disks=$items}
}
