param(
    [switch]$RemoveStartup,
    [switch]$KeepSystemProxy,
    [switch]$RestoreSystemProxy
)

$ErrorActionPreference = 'SilentlyContinue'

$executable = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'RedArrow.exe'))
$processes = Get-CimInstance Win32_Process | Where-Object {
    $_.ExecutablePath -and [System.IO.Path]::GetFullPath($_.ExecutablePath) -eq $executable
}

foreach ($process in $processes) {
    & taskkill.exe /PID $process.ProcessId /T /F | Out-Null
}

if (-not $KeepSystemProxy -or $RestoreSystemProxy) {
    & (Join-Path $PSScriptRoot 'system-proxy.ps1') -Mode off -Restore
}

if ($RemoveStartup) {
    Remove-ItemProperty `
        -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' `
        -Name 'RedArrow' `
        -ErrorAction SilentlyContinue

    Get-ChildItem Registry::HKEY_USERS -ErrorAction SilentlyContinue |
        Where-Object { $_.PSChildName -match '^S-1-5-21-' } |
        ForEach-Object {
            Remove-ItemProperty `
                -Path ("Registry::HKEY_USERS\{0}\Software\Microsoft\Windows\CurrentVersion\Run" -f $_.PSChildName) `
                -Name 'RedArrow' `
                -ErrorAction SilentlyContinue
        }
}
