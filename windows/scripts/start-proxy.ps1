param(
    [string]$ConfigPath = (Join-Path $env:LOCALAPPDATA 'RedArrow\config.json')
)

$ErrorActionPreference = 'Stop'
$executable = Join-Path $PSScriptRoot 'RedArrow.exe'
$controlCenterScript = Join-Path $PSScriptRoot 'control-center.ps1'
$systemProxyScript = Join-Path $PSScriptRoot 'system-proxy.ps1'

if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
    Start-Process `
        -FilePath "$env:WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe" `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden', '-File', $controlCenterScript)
    exit 0
}

$config = Get-Content -Raw -LiteralPath $ConfigPath | ConvertFrom-Json
$fullExecutable = [System.IO.Path]::GetFullPath($executable)
$process = Get-CimInstance Win32_Process | Where-Object {
    $_.ExecutablePath -and
    [System.IO.Path]::GetFullPath($_.ExecutablePath) -eq $fullExecutable
} | Select-Object -First 1

if (-not $process) {
    $started = Start-Process `
        -FilePath $executable `
        -ArgumentList @('--config', $ConfigPath) `
        -WindowStyle Hidden `
        -PassThru
    $processId = $started.Id
}
else {
    $processId = $process.ProcessId
}

$ready = $false
for ($attempt = 0; $attempt -lt 40; $attempt++) {
    Start-Sleep -Milliseconds 500
    $httpReady = Get-NetTCPConnection `
        -State Listen `
        -LocalAddress $config.http_bind `
        -LocalPort $config.http_port `
        -ErrorAction SilentlyContinue
    $socksReady = Get-NetTCPConnection `
        -State Listen `
        -LocalAddress $config.socks_bind `
        -LocalPort $config.socks_port `
        -ErrorAction SilentlyContinue
    if ($httpReady -and $socksReady) {
        $ready = $true
        break
    }
    if (-not (Get-Process -Id $processId -ErrorAction SilentlyContinue)) {
        break
    }
}

if (-not $ready) {
    & (Join-Path $PSScriptRoot 'stop-proxy.ps1') -RestoreSystemProxy
    throw 'Red Arrow did not become ready within 20 seconds. System proxy was not enabled.'
}

try {
    & $systemProxyScript -ConfigPath $ConfigPath
}
catch {
    & taskkill.exe /PID $processId /T /F | Out-Null
    & $systemProxyScript -Mode off -Restore
    throw
}

Wait-Process -Id $processId -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 750
$replacement = Get-CimInstance Win32_Process | Where-Object {
    $_.ExecutablePath -and
    [System.IO.Path]::GetFullPath($_.ExecutablePath) -eq $fullExecutable
} | Select-Object -First 1
if (-not $replacement) {
    & $systemProxyScript -Mode off -Restore
}
