$ErrorActionPreference = 'SilentlyContinue'
$configPath = Join-Path $env:LOCALAPPDATA 'RedArrow\config.json'
if (-not (Test-Path $configPath)) {
    Write-Host "Configuration not found: $configPath"
    Read-Host 'Press Enter to close'
    exit 1
}

$config = Get-Content -Raw $configPath | ConvertFrom-Json
$app = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'RedArrow.exe' }
$ssh = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'ssh.exe' -and $_.CommandLine -match (':{0}(\s|$)' -f $config.socks_port)
}

Write-Host 'Red Arrow for Windows' -ForegroundColor Cyan
Write-Host ('Application: {0}' -f $(if ($app) { 'running' } else { 'stopped' }))
Write-Host ('SSH tunnel:  {0}' -f $(if ($ssh) { 'running' } else { 'stopped' }))
Write-Host "SOCKS5:      $($config.socks_bind):$($config.socks_port)"
Write-Host "HTTP proxy:  $($config.http_bind):$($config.http_port)"
Write-Host "SSH target:  $($config.ssh_target) (from $env:USERPROFILE\.ssh\config)"
$modeName = switch ([string]$config.system_proxy_mode) {
    'global' { 'Global proxy' }
    'bypass_cn' { 'Bypass mainland China' }
    default { 'Local proxy only' }
}
$internetSettings = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
$systemProxy = if ([int]$internetSettings.ProxyEnable -eq 1) {
    "enabled ($($internetSettings.ProxyServer))"
}
else {
    'disabled'
}
Write-Host "Mode:         $modeName"
Write-Host "System proxy: $systemProxy"
Write-Host ''

try {
    $result = & curl.exe -fsS --ssl-no-revoke --connect-timeout 8 --max-time 15 -x "http://$($config.http_bind):$($config.http_port)" https://api.ipify.org
    if ($LASTEXITCODE -ne 0) { throw "curl exited with $LASTEXITCODE" }
    Write-Host "Proxy test:   OK (exit IP $result)" -ForegroundColor Green
}
catch {
    Write-Host "Proxy test:   FAILED ($($_.Exception.Message))" -ForegroundColor Red
}

Write-Host ''
Write-Host "Log: $($config.log_file)"
Read-Host 'Press Enter to close'
