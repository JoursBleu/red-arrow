param(
    [string]$Root = (Join-Path $PSScriptRoot '..'),
    [Parameter(Mandatory = $true)]
    [string]$SshHost,
    [int]$SshPort = 22,
    [Parameter(Mandatory = $true)]
    [string]$SshUser,
    [Parameter(Mandatory = $true)]
    [string]$IdentityFile,
    [int]$SocksPort = 22080,
    [int]$HttpPort = 22118,
    [Parameter(Mandatory = $true)]
    [string]$ExpectedProxyIp,
    [Parameter(Mandatory = $true)]
    [string]$ExpectedDirectIp
)

$ErrorActionPreference = 'Stop'
$Root = [System.IO.Path]::GetFullPath($Root)
$executable = Join-Path $Root 'dist\RedArrow.exe'
$systemProxyScript = Join-Path $Root 'scripts\system-proxy.ps1'
$tempDir = Join-Path $env:TEMP 'RedArrow-mode-e2e'
$internetSettings = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
$connectionsPath = Join-Path $internetSettings 'Connections'

function Get-ProxyState {
    $settingsKey = Get-Item $internetSettings
    $top = [ordered]@{}
    foreach ($name in @('ProxyEnable', 'ProxyServer', 'ProxyOverride', 'AutoConfigURL', 'AutoDetect')) {
        $exists = $settingsKey.GetValueNames() -contains $name
        $top[$name] = @($exists, $(if ($exists) { $settingsKey.GetValue($name) } else { $null }))
    }

    $connectionsKey = Get-Item $connectionsPath
    $blobs = [ordered]@{}
    foreach ($name in @('DefaultConnectionSettings', 'SavedLegacySettings')) {
        $exists = $connectionsKey.GetValueNames() -contains $name
        $value = if ($exists) {
            [Convert]::ToBase64String([byte[]]$connectionsKey.GetValue($name))
        }
        else {
            $null
        }
        $blobs[$name] = @($exists, $value)
    }
    return ([ordered]@{ top = $top; blobs = $blobs } | ConvertTo-Json -Depth 5 -Compress)
}

function Wait-ForListeners {
    param([System.Diagnostics.Process]$Process)
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        Start-Sleep -Milliseconds 500
        $httpReady = Get-NetTCPConnection -State Listen -LocalPort $HttpPort -ErrorAction SilentlyContinue
        $socksReady = Get-NetTCPConnection -State Listen -LocalPort $SocksPort -ErrorAction SilentlyContinue
        if ($httpReady -and $socksReady) {
            return
        }
        if ($Process.HasExited) {
            throw "Red Arrow exited early with code $($Process.ExitCode)."
        }
    }
    throw 'Red Arrow did not open its listeners within 20 seconds.'
}

function Invoke-ExplicitProxy {
    param([string]$Url, [switch]$NoRevoke)
    $arguments = @(
        '-fsS',
        '--connect-timeout', '8',
        '--max-time', '25',
        '-x', "http://127.0.0.1:$HttpPort"
    )
    if ($NoRevoke) {
        $arguments += '--ssl-no-revoke'
    }
    $arguments += $Url
    $result = & curl.exe @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "curl failed for $Url with exit code $LASTEXITCODE."
    }
    return ([string]$result).Trim()
}

function Invoke-WinInetProxy {
    param([string]$Url)
    $request = [System.Net.HttpWebRequest]::Create($Url)
    $request.Timeout = 25000
    $request.ReadWriteTimeout = 25000
    $request.UserAgent = 'RedArrow-Mode-Test/1.1'
    $response = $request.GetResponse()
    try {
        $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
        try {
            return $reader.ReadToEnd().Trim()
        }
        finally {
            $reader.Dispose()
        }
    }
    finally {
        $response.Dispose()
    }
}

function Run-Mode {
    param([ValidateSet('global', 'bypass_cn')][string]$Mode)

    $configPath = Join-Path $tempDir "config-$Mode.json"
    $logPath = Join-Path $tempDir "proxy-$Mode.log"
    $config = [ordered]@{
        ssh_host = $SshHost
        ssh_port = $SshPort
        ssh_user = $SshUser
        identity_file = $IdentityFile
        proxy_jump = $null
        socks_bind = '127.0.0.1'
        socks_port = $SocksPort
        http_bind = '127.0.0.1'
        http_port = $HttpPort
        connect_timeout_seconds = 10
        server_alive_interval_seconds = 30
        reconnect_delay_seconds = 3
        log_file = $logPath
        system_proxy_mode = $Mode
        cn_rules_files = @(
            (Join-Path $Root 'rules\china.txt'),
            (Join-Path $Root 'rules\china6.txt')
        )
        direct_domains = @('.cn')
        direct_cidrs = @()
        force_proxy_domains = @()
    }
    $config | ConvertTo-Json | Set-Content -Encoding UTF8 $configPath
    & $executable --config $configPath --check-config
    if ($LASTEXITCODE -ne 0) {
        throw "Config validation failed for $Mode."
    }

    $process = Start-Process `
        -FilePath $executable `
        -ArgumentList @('--config', $configPath) `
        -WindowStyle Hidden `
        -PassThru
    try {
        Wait-ForListeners -Process $process
        & $systemProxyScript -Mode $Mode -HttpPort $HttpPort

        $settings = Get-ItemProperty $internetSettings
        if ([int]$settings.ProxyEnable -ne 1) {
            throw "WinINET was not enabled for $Mode."
        }
        Write-Output "MODE=$Mode WININET=$($settings.ProxyServer)"

        $foreign = Invoke-ExplicitProxy -Url 'https://api.ipify.org' -NoRevoke
        $china = Invoke-ExplicitProxy -Url 'http://ip.3322.net'
        $automatic = Invoke-WinInetProxy -Url 'http://ip.3322.net'
        Write-Output "MODE=$Mode FOREIGN=$foreign CHINA=$china WININET_CHINA=$automatic"

        if ($foreign -ne $ExpectedProxyIp) {
            throw "Foreign route mismatch in $Mode mode: $foreign"
        }
        if ($Mode -eq 'global' -and $china -ne $ExpectedProxyIp) {
            throw "China route mismatch in global mode: $china"
        }
        if ($Mode -eq 'bypass_cn' -and $china -ne $ExpectedDirectIp) {
            throw "China route mismatch in bypass mode: $china"
        }
        if ($automatic -ne $china) {
            throw "WinINET automatic proxy result differs from explicit proxy: $automatic vs $china"
        }

        Start-Sleep -Milliseconds 500
        $routeLines = Get-Content $logPath | Select-String 'route=' | ForEach-Object { $_.Line }
        $routeLines | ForEach-Object { Write-Output "ROUTE=$($_)" }
        if ($Mode -eq 'global' -and -not ($routeLines -match 'route=Proxy')) {
            throw 'Global mode did not log a proxy route.'
        }
        if ($Mode -eq 'bypass_cn' -and -not ($routeLines -match 'route=Direct')) {
            throw 'Bypass mode did not log a direct route.'
        }
        Write-Output "MODE_OK=$Mode"
    }
    finally {
        & taskkill.exe /PID $process.Id /T /F | Out-Null
        & $systemProxyScript -Mode off -Restore
        Start-Sleep -Milliseconds 500
        $remaining = Get-NetTCPConnection `
            -State Listen `
            -LocalPort $SocksPort, $HttpPort `
            -ErrorAction SilentlyContinue
        Write-Output "CLEAN=$Mode PORTS=$(@($remaining).Count)"
    }
}

if (-not (Test-Path $executable -PathType Leaf)) {
    throw "Red Arrow executable not found: $executable"
}
if (-not (Test-Path $IdentityFile -PathType Leaf)) {
    throw "SSH identity file not found: $IdentityFile"
}

$initialState = Get-ProxyState
New-Item -ItemType Directory -Force $tempDir | Out-Null
try {
    Run-Mode -Mode global
    if ((Get-ProxyState) -ne $initialState) {
        throw 'WinINET state changed after global-mode cleanup.'
    }

    Run-Mode -Mode bypass_cn
    if ((Get-ProxyState) -ne $initialState) {
        throw 'WinINET state changed after bypass-mode cleanup.'
    }
    Write-Output 'ALL_MODES_OK'
}
finally {
    & $systemProxyScript -Mode off -Restore
    Remove-Item -Recurse -Force $tempDir -ErrorAction SilentlyContinue
}