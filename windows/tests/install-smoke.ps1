param(
    [string]$SetupPath = (Join-Path $PSScriptRoot '..\dist\RedArrow-Windows-Setup-1.2.2-x64.exe')
)

$ErrorActionPreference = 'Stop'
$SetupPath = [System.IO.Path]::GetFullPath($SetupPath)
$testDir = Join-Path $env:ProgramFiles 'Red Arrow'
$configDir = Join-Path $env:LOCALAPPDATA 'RedArrow'
$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$oldRun = (Get-ItemProperty -Path $runKey -Name RedArrow -ErrorAction SilentlyContinue).RedArrow
$configBackup = $null
$internetSettings = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
$proxyStateBefore = Get-ItemProperty $internetSettings |
    Select-Object ProxyEnable,ProxyServer,ProxyOverride,AutoConfigURL,AutoDetect |
    ConvertTo-Json -Compress

if (-not (Test-Path $SetupPath -PathType Leaf)) {
    throw "Installer not found: $SetupPath"
}
if (Test-Path $testDir) {
    throw "Test install directory already exists: $testDir"
}
if (Test-Path $configDir) {
    $configBackup = $configDir + '.installtest-backup.' + (Get-Date -Format 'yyyyMMddHHmmss')
    Move-Item $configDir $configBackup
}

try {
    $installArgs = @(
        '/VERYSILENT',
        '/SUPPRESSMSGBOXES',
        '/NORESTART',
        '/SP-',
        '/TASKS='
    )
    $install = Start-Process -FilePath $SetupPath -ArgumentList $installArgs -Wait -PassThru
    Write-Output "INSTALL_EXIT=$($install.ExitCode)"
    if ($install.ExitCode -ne 0) {
        throw 'Installer failed.'
    }

    $required = @(
        'RedArrow.exe',
        'control-center-hidden.vbs',
        'start-hidden.vbs',
        'start-proxy.ps1',
        'stop-proxy.ps1',
        'system-proxy.ps1',
        'control-center.ps1',
        'show-status.ps1',
        'open-log-folder.ps1',
        'red-arrow.ico',
        'red-arrow.png',
        'config.example.json',
        'rules\china.txt',
        'rules\china6.txt',
        'rules\LICENSE',
        'unins000.exe'
    )
    foreach ($name in $required) {
        $path = Join-Path $testDir $name
        if (-not (Test-Path $path -PathType Leaf)) {
            throw "Missing installed file: $name"
        }
        Write-Output "INSTALLED=$name"
    }

    $forbiddenPatterns = @(
        'root@',
        '"ssh_user"',
        '"identity_file"',
        'Latex Tools SSH Proxy'
    )
    foreach ($pattern in $forbiddenPatterns) {
        $matches = Get-ChildItem -Recurse -File $testDir |
            Select-String -SimpleMatch $pattern -ErrorAction SilentlyContinue
        if ($matches) {
            throw "Installed package contains forbidden private/stale value: $pattern"
        }
        Write-Output "PRIVACY_AUDIT_OK=$pattern"
    }
    $publicIpv4Matches = Get-ChildItem -Recurse -File $testDir |
        Where-Object {
            $_.FullName -notmatch '\\rules\\' -and
            $_.Extension -in @('.json', '.ps1', '.vbs', '.txt', '.md')
        } |
        Select-String -Pattern '(?<![0-9])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])' -AllMatches |
        ForEach-Object { $_.Matches.Value } |
        Where-Object { $_ -notmatch '^127\.' } |
        Sort-Object -Unique
    if ($publicIpv4Matches) {
        throw "Installed package contains non-loopback IPv4 defaults: $($publicIpv4Matches -join ', ')"
    }
    Write-Output 'PRIVACY_AUDIT_OK=no non-loopback IPv4 defaults'

    if (Test-Path $configDir) {
        throw 'Silent machine-wide installation unexpectedly created per-user data.'
    }
    & (Join-Path $testDir 'RedArrow.exe') `
        --config (Join-Path $testDir 'config.example.json') `
        --check-config
    if ($LASTEXITCODE -ne 0) {
        throw 'Installed binary rejected the generated configuration.'
    }

    $example = Get-Content -Raw (Join-Path $testDir 'config.example.json') | ConvertFrom-Json
    if ($example.ssh_target -ne 'red-arrow-tunnel') {
        throw 'Installed example configuration is not generic.'
    }
    & (Join-Path $testDir 'control-center.ps1') -SmokeTest
    if ($LASTEXITCODE -ne 0) {
        throw 'Installed control center smoke test failed.'
    }
    & (Join-Path $testDir 'control-center.ps1') -ConfigSerializationSmokeTest
    if ($LASTEXITCODE -ne 0) {
        throw 'Installed config-serialization smoke test failed.'
    }
    & (Join-Path $testDir 'control-center.ps1') -ConfigRoundTripSmokeTest
    if ($LASTEXITCODE -ne 0) {
        throw 'Installed candidate-config round-trip smoke test failed.'
    }

    $newRun = (Get-ItemProperty -Path $runKey -Name RedArrow -ErrorAction SilentlyContinue).RedArrow
    if ($newRun -ne $oldRun) {
        throw 'Silent test unexpectedly changed startup registration.'
    }
    $running = Get-CimInstance Win32_Process | Where-Object {
        $_.ExecutablePath -and $_.ExecutablePath.StartsWith(
            $testDir,
            [System.StringComparison]::OrdinalIgnoreCase
        )
    }
    if ($running) {
        throw 'Silent installer unexpectedly started the application.'
    }
    $proxyStateAfterInstall = Get-ItemProperty $internetSettings |
        Select-Object ProxyEnable,ProxyServer,ProxyOverride,AutoConfigURL,AutoDetect |
        ConvertTo-Json -Compress
    if ($proxyStateAfterInstall -ne $proxyStateBefore) {
        throw 'Silent installer unexpectedly changed the Windows system proxy.'
    }
    Write-Output 'INSTALL_VERIFY_OK'

    $uninstall = Start-Process `
        -FilePath (Join-Path $testDir 'unins000.exe') `
        -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART') `
        -Wait `
        -PassThru
    Write-Output "UNINSTALL_EXIT=$($uninstall.ExitCode)"
    if ($uninstall.ExitCode -ne 0) {
        throw 'Uninstaller failed.'
    }
    for ($attempt = 0; $attempt -lt 20 -and (Test-Path $testDir); $attempt++) {
        Start-Sleep -Milliseconds 250
    }
    if (Test-Path $testDir) {
        throw 'Install directory remained after uninstall.'
    }
    $proxyStateAfterUninstall = Get-ItemProperty $internetSettings |
        Select-Object ProxyEnable,ProxyServer,ProxyOverride,AutoConfigURL,AutoDetect |
        ConvertTo-Json -Compress
    if ($proxyStateAfterUninstall -ne $proxyStateBefore) {
        throw 'Uninstall did not preserve the original Windows system proxy.'
    }
    Write-Output 'UNINSTALL_VERIFY_OK'
}
finally {
    Remove-Item -Recurse -Force $testDir -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $configDir -ErrorAction SilentlyContinue
    if ($configBackup -and (Test-Path $configBackup)) {
        Move-Item $configBackup $configDir
    }
    if ($null -eq $oldRun) {
        Remove-ItemProperty -Path $runKey -Name RedArrow -ErrorAction SilentlyContinue
    }
    else {
        Set-ItemProperty -Path $runKey -Name RedArrow -Value $oldRun
    }
    $leftover = Get-CimInstance Win32_Process | Where-Object {
        $_.Name -eq 'RedArrow.exe' -or
        ($_.Name -eq 'ssh.exe' -and $_.CommandLine -match '21080|RedArrow')
    }
    Write-Output "LEFTOVER_PROCESS_COUNT=$(@($leftover).Count)"
}