param([string]$App = 'notepad')
. "$PSScriptRoot\tool-common.ps1"
Invoke-CatalogTool {
    Assert-ToolChoice $App @('notepad','calculator','explorer','taskmanager')
    $user=[Security.Principal.WindowsIdentity]::GetCurrent().Name
    $interactive=(Get-CimInstance Win32_ComputerSystem).UserName
    if(-not $interactive -or $interactive -ne $user) {throw 'La cuenta SSH debe tener una sesion de escritorio iniciada en Windows'}
    $programs=@{notepad="$env:WINDIR\System32\notepad.exe";calculator="$env:WINDIR\System32\calc.exe";explorer="$env:WINDIR\explorer.exe";taskmanager="$env:WINDIR\System32\taskmgr.exe"}
    $taskName='InferenceGemma-Open-'+[guid]::NewGuid().ToString('N')
    $principal=New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
    $action=New-ScheduledTaskAction -Execute $programs[$App]
    try {
        Register-ScheduledTask -TaskName $taskName -Action $action -Principal $principal -ErrorAction Stop | Out-Null
        Start-ScheduledTask -TaskName $taskName -ErrorAction Stop
        $deadline=(Get-Date).AddSeconds(8)
        do { Start-Sleep -Milliseconds 200; $info=Get-ScheduledTaskInfo -TaskName $taskName } while($info.LastRunTime.Year -lt 2000 -and (Get-Date) -lt $deadline)
        if($info.LastRunTime.Year -lt 2000 -or $info.LastTaskResult -notin @(0,267009)) {throw "Windows no inicio la aplicacion: resultado $($info.LastTaskResult)"}
        return [ordered]@{status='accepted';app=$App;message='Windows inicio la tarea en tu escritorio; comprueba la ventana'}
    } finally { Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue }
}
