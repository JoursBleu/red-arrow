param(
    [string]$PackagePath = (Join-Path $PSScriptRoot '..\dist\RedArrow-Windows-Portable-1.2.2-x64.zip')
)

$ErrorActionPreference = 'Stop'
$PackagePath = [System.IO.Path]::GetFullPath($PackagePath)
$auditDir = Join-Path $env:TEMP ('RedArrow-portable-audit-' + [Guid]::NewGuid().ToString('N'))

if (-not (Test-Path $PackagePath -PathType Leaf)) {
    throw "Portable package not found: $PackagePath"
}

try {
    Expand-Archive -Path $PackagePath -DestinationPath $auditDir -Force
    $required = @(
        'RedArrow.exe',
        'control-center-hidden.vbs',
        'control-center.ps1',
        'start-proxy.ps1',
        'stop-proxy.ps1',
        'system-proxy.ps1',
        'red-arrow.ico',
        'red-arrow.png',
        'config.example.json',
        'rules\china.txt',
        'rules\china6.txt'
    )
    foreach ($name in $required) {
        if (-not (Test-Path (Join-Path $auditDir $name) -PathType Leaf)) {
            throw "Portable package is missing: $name"
        }
    }

    $example = Get-Content -Raw (Join-Path $auditDir 'config.example.json') | ConvertFrom-Json
    if ($example.ssh_host -ne 'ssh.example.com' -or $example.ssh_user -ne 'username') {
        throw 'Portable example configuration is not generic.'
    }
    if ($example.socks_port -ne 1080 -or $example.http_port -ne 8118) {
        throw 'Portable example proxy ports do not match 1080/8118.'
    }
    if ($null -ne $example.proxy_jump) {
        throw 'ProxyJump must be optional and null by default.'
    }

    & (Join-Path $auditDir 'RedArrow.exe') `
        --config (Join-Path $auditDir 'config.example.json') `
        --check-config
    if ($LASTEXITCODE -ne 0) {
        throw 'Portable executable rejected its example configuration.'
    }
    & (Join-Path $auditDir 'control-center.ps1') -SmokeTest
    if ($LASTEXITCODE -ne 0) {
        throw 'Portable control-center smoke test failed.'
    }
    & (Join-Path $auditDir 'control-center.ps1') -KeygenSmokeTest
    if ($LASTEXITCODE -ne 0) {
        throw 'Portable Ed25519 key-generation smoke test failed.'
    }
    & (Join-Path $auditDir 'control-center.ps1') -ConfigSerializationSmokeTest
    if ($LASTEXITCODE -ne 0) {
        throw 'Portable config-serialization smoke test failed.'
    }
    & (Join-Path $auditDir 'control-center.ps1') -ConfigRoundTripSmokeTest
    if ($LASTEXITCODE -ne 0) {
        throw 'Portable candidate-config round-trip smoke test failed.'
    }

    $forbiddenPatterns = @('root@', '"ssh_user": "root"', 'Latex Tools SSH Proxy')
    foreach ($pattern in $forbiddenPatterns) {
        $matches = Get-ChildItem -Recurse -File $auditDir |
            Select-String -SimpleMatch $pattern -ErrorAction SilentlyContinue
        if ($matches) {
            throw "Portable package contains forbidden private/stale value: $pattern"
        }
    }
    Write-Output 'PORTABLE_SMOKE_OK socks=1080 http=8118 proxyJump=null keygen=ed25519'
}
finally {
    Remove-Item -Recurse -Force $auditDir -ErrorAction SilentlyContinue
}

if (Test-Path $auditDir) {
    throw 'Portable audit directory was not removed.'
}