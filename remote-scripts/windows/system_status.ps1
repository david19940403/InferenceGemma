$ErrorActionPreference = 'Stop'
try {
    $os = Get-CimInstance Win32_OperatingSystem
    $disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'"
    [ordered]@{
        status = 'success'
        hostname = [Environment]::MachineName
        os = $os.Caption
        free_memory_bytes = [long]$os.FreePhysicalMemory * 1024
        disk_free_bytes = [long]$disk.FreeSpace
        disk_total_bytes = [long]$disk.Size
    } | ConvertTo-Json -Compress
    exit 0
} catch {
    [Console]::Error.WriteLine((@{ status='error'; message=$_.Exception.Message } | ConvertTo-Json -Compress))
    exit 1
}
