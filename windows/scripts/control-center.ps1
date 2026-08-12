param(
    [switch]$SmokeTest,
    [switch]$KeygenSmokeTest,
    [switch]$ConfigSerializationSmokeTest,
    [switch]$ConfigRoundTripSmokeTest,
    [string]$ScreenshotPath,
    [ValidateSet('Overview', 'Server', 'Routing', 'Logs')]
    [string]$ScreenshotTab = 'Server'
)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$ErrorActionPreference = 'Stop'
$appDir = $PSScriptRoot
$executable = Join-Path $appDir 'RedArrow.exe'
$iconPath = Join-Path $appDir 'red-arrow.ico'
if (-not (Test-Path $iconPath -PathType Leaf)) {
    $iconPath = Join-Path (Split-Path $appDir -Parent) 'assets\red-arrow.ico'
}
$logoPath = Join-Path $appDir 'red-arrow.png'
if (-not (Test-Path $logoPath -PathType Leaf)) {
    $logoPath = Join-Path (Split-Path $appDir -Parent) 'assets\red-arrow.png'
}
$configDir = Join-Path $env:LOCALAPPDATA 'RedArrow'
$configPath = Join-Path $configDir 'config.json'
$legacyConfigPath = Join-Path $env:LOCALAPPDATA 'LatexToolsSshProxy\config.json'
$logPath = Join-Path $configDir 'proxy.log'
$runKeyPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$runValueName = 'RedArrow'
$internetSettingsPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
$powershellExe = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
$wscriptExe = Join-Path $env:WINDIR 'System32\wscript.exe'
$script:allowClose = $false
$script:lastLogLength = -1

$colors = @{
    Background = [System.Drawing.Color]::FromArgb(245, 246, 248)
    Surface = [System.Drawing.Color]::White
    Border = [System.Drawing.Color]::FromArgb(220, 222, 228)
    Text = [System.Drawing.Color]::FromArgb(35, 37, 42)
    Muted = [System.Drawing.Color]::FromArgb(100, 104, 114)
    Red = [System.Drawing.Color]::FromArgb(211, 47, 47)
    RedDark = [System.Drawing.Color]::FromArgb(183, 28, 28)
    Green = [System.Drawing.Color]::FromArgb(31, 139, 76)
    Amber = [System.Drawing.Color]::FromArgb(190, 120, 0)
    SoftRed = [System.Drawing.Color]::FromArgb(255, 235, 238)
    SoftGreen = [System.Drawing.Color]::FromArgb(232, 245, 233)
}

$defaults = [ordered]@{
    ssh_host = ''
    ssh_port = 22
    ssh_user = ''
    identity_file = ''
    proxy_jump = $null
    socks_bind = '127.0.0.1'
    socks_port = 1080
    http_bind = '127.0.0.1'
    http_port = 8118
    connect_timeout_seconds = 10
    server_alive_interval_seconds = 30
    reconnect_delay_seconds = 3
    log_file = $logPath
    system_proxy_mode = 'bypass_cn'
    cn_rules_files = @('rules\china.txt', 'rules\china6.txt')
    direct_domains = @('.cn')
    direct_cidrs = @()
    force_proxy_domains = @()
}

function Load-Configuration {
    $values = [ordered]@{}
    foreach ($name in $defaults.Keys) {
        $values[$name] = $defaults[$name]
    }
    if ($SmokeTest) {
        return $values
    }

    $loadPath = if (Test-Path $configPath -PathType Leaf) {
        $configPath
    }
    elseif (Test-Path $legacyConfigPath -PathType Leaf) {
        $legacyConfigPath
    }
    else {
        $null
    }
    if (-not $loadPath) {
        return $values
    }

    try {
        $loaded = Get-Content -Raw $loadPath | ConvertFrom-Json
        foreach ($name in $defaults.Keys) {
            if ($null -ne $loaded.$name) {
                $values[$name] = $loaded.$name
            }
        }
        if ($loadPath -eq $legacyConfigPath) {
            $values.log_file = $logPath
        }
    }
    catch {
        [System.Windows.Forms.MessageBox]::Show(
            "The saved configuration could not be loaded.`r`n`r`n$($_.Exception.Message)",
            'Red Arrow',
            'OK',
            'Error'
        ) | Out-Null
    }
    return $values
}

$config = Load-Configuration

function New-Label {
    param(
        [System.Windows.Forms.Control]$Parent,
        [string]$Text,
        [int]$X,
        [int]$Y,
        [int]$Width,
        [int]$Height = 24,
        [System.Drawing.Font]$Font,
        [System.Drawing.Color]$Color = $colors.Text
    )
    $label = New-Object System.Windows.Forms.Label
    $label.Text = $Text
    $label.Location = New-Object System.Drawing.Point($X, $Y)
    $label.Size = New-Object System.Drawing.Size($Width, $Height)
    $label.ForeColor = $Color
    if ($Font) { $label.Font = $Font }
    $Parent.Controls.Add($label)
    return $label
}

function New-Button {
    param(
        [System.Windows.Forms.Control]$Parent,
        [string]$Text,
        [int]$X,
        [int]$Y,
        [int]$Width,
        [int]$Height = 34,
        [System.Drawing.Color]$BackColor = $colors.Surface,
        [System.Drawing.Color]$ForeColor = $colors.Text
    )
    $button = New-Object System.Windows.Forms.Button
    $button.Text = $Text
    $button.Location = New-Object System.Drawing.Point($X, $Y)
    $button.Size = New-Object System.Drawing.Size($Width, $Height)
    $button.BackColor = $BackColor
    $button.ForeColor = $ForeColor
    $button.FlatStyle = 'Flat'
    $button.FlatAppearance.BorderColor = if ($BackColor -eq $colors.Surface) { $colors.Border } else { $BackColor }
    $button.FlatAppearance.BorderSize = 1
    $button.Cursor = 'Hand'
    $Parent.Controls.Add($button)
    return $button
}

function New-TextBox {
    param(
        [System.Windows.Forms.Control]$Parent,
        [int]$X,
        [int]$Y,
        [int]$Width,
        [string]$Text = '',
        [switch]$Multiline,
        [int]$Height = 28
    )
    $box = New-Object System.Windows.Forms.TextBox
    $box.Location = New-Object System.Drawing.Point($X, $Y)
    $box.Size = New-Object System.Drawing.Size($Width, $Height)
    $box.Text = $Text
    $box.BorderStyle = 'FixedSingle'
    if ($Multiline) {
        $box.Multiline = $true
        $box.AcceptsReturn = $true
        $box.ScrollBars = 'Vertical'
    }
    $Parent.Controls.Add($box)
    return $box
}

function Split-Rules {
    param([string]$Text)
    [string[]]$rules = @(
        $Text -split '[\r\n,;]+' |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ }
    )
    Write-Output -NoEnumerate $rules
}

function ConvertTo-StringArray {
    param($Value)
    [string[]]$values = @($Value | ForEach-Object { [string]$_ })
    return ,$values
}

if ($ConfigSerializationSmokeTest) {
    [string[]]$directDomains = ConvertTo-StringArray (Split-Rules '.cn')
    [string[]]$directCidrs = ConvertTo-StringArray (Split-Rules '')
    [string[]]$forceProxyDomains = ConvertTo-StringArray (Split-Rules '')
    $probe = [ordered]@{
        cn_rules_files = [string[]]@('rules\china.txt', 'rules\china6.txt')
        direct_domains = $directDomains
        direct_cidrs = $directCidrs
        force_proxy_domains = $forceProxyDomains
    }
    $json = $probe | ConvertTo-Json -Depth 4 -Compress
    $roundTrip = $json | ConvertFrom-Json
    if ($roundTrip.direct_domains -isnot [System.Array] -or
        @($roundTrip.direct_domains).Count -ne 1 -or
        $roundTrip.direct_cidrs -isnot [System.Array] -or
        @($roundTrip.direct_cidrs).Count -ne 0 -or
        $roundTrip.force_proxy_domains -isnot [System.Array] -or
        @($roundTrip.force_proxy_domains).Count -ne 0) {
        throw "Rule-list JSON serialization is invalid: $json"
    }
    Write-Output "CONFIG_SERIALIZATION_SMOKE_OK json=$json"
    exit 0
}

if ($ConfigRoundTripSmokeTest) {
    $testDirectory = Join-Path $env:TEMP ('RedArrow-config-smoke-' + [Guid]::NewGuid().ToString('N'))
    $testConfig = Join-Path $testDirectory 'config.candidate.json'
    try {
        New-Item -ItemType Directory -Force $testDirectory | Out-Null
        [string[]]$directDomains = ConvertTo-StringArray (Split-Rules '.cn')
        [string[]]$directCidrs = ConvertTo-StringArray (Split-Rules '')
        [string[]]$forceProxyDomains = ConvertTo-StringArray (Split-Rules '')
        $probe = [ordered]@{
            ssh_host = 'ssh.example.com'
            ssh_port = 22
            ssh_user = 'username'
            identity_file = ''
            proxy_jump = $null
            socks_bind = '127.0.0.1'
            socks_port = 1080
            http_bind = '127.0.0.1'
            http_port = 8118
            connect_timeout_seconds = 10
            server_alive_interval_seconds = 30
            reconnect_delay_seconds = 3
            log_file = (Join-Path $testDirectory 'proxy.log')
            system_proxy_mode = 'bypass_cn'
            cn_rules_files = [string[]]@('rules\china.txt', 'rules\china6.txt')
            direct_domains = $directDomains
            direct_cidrs = $directCidrs
            force_proxy_domains = $forceProxyDomains
        }
        $probe | ConvertTo-Json | Set-Content -Encoding UTF8 $testConfig
        & $executable --config $testConfig --check-config
        if ($LASTEXITCODE -ne 0) {
            throw 'Red Arrow rejected the candidate config generated by the control center.'
        }
        $roundTrip = Get-Content -Raw $testConfig | ConvertFrom-Json
        foreach ($name in @('cn_rules_files', 'direct_domains', 'direct_cidrs', 'force_proxy_domains')) {
            if ($roundTrip.$name -isnot [System.Array]) {
                throw "$name was not serialized as a JSON array."
            }
        }
        Write-Output 'CONFIG_ROUNDTRIP_SMOKE_OK arrays=4 core=accepted candidate=cleaned'
    }
    finally {
        Remove-Item -Recurse -Force $testDirectory -ErrorAction SilentlyContinue
    }
    if (Test-Path $testDirectory) {
        throw 'Temporary config round-trip directory was not removed.'
    }
    exit 0
}

function Get-ModeValue {
    switch ($modeCombo.SelectedIndex) {
        0 { return 'off' }
        2 { return 'global' }
        default { return 'bypass_cn' }
    }
}

function Get-CoreProcess {
    if (-not (Test-Path $executable -PathType Leaf)) { return $null }
    $fullPath = [System.IO.Path]::GetFullPath($executable)
    return Get-CimInstance Win32_Process -Filter "Name='RedArrow.exe'" -ErrorAction SilentlyContinue |
        Where-Object {
            $_.ExecutablePath -and
            [System.IO.Path]::GetFullPath($_.ExecutablePath) -eq $fullPath
        } |
        Select-Object -First 1
}

function Get-SshProcess {
    $port = [int]$socksPortInput.Value
    $core = Get-CoreProcess
    if (-not $core) { return $null }
    return Get-CimInstance Win32_Process -Filter "Name='ssh.exe'" -ErrorAction SilentlyContinue |
        Where-Object {
            [int]$_.ParentProcessId -eq [int]$core.ProcessId -and
            $_.CommandLine -match (':{0}(\s|$)' -f $port)
        } |
        Select-Object -First 1
}

function Assert-ListenerPortAvailable {
    param([int]$Port, [string]$Label)
    $listener = Get-NetTCPConnection `
        -State Listen `
        -LocalPort $Port `
        -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $listener) { return }

    $core = Get-CoreProcess
    if ($core -and [int]$listener.OwningProcess -eq [int]$core.ProcessId) { return }
    $ssh = Get-SshProcess
    if ($ssh -and [int]$listener.OwningProcess -eq [int]$ssh.ProcessId) { return }
    $processName = try {
        (Get-Process -Id $listener.OwningProcess -ErrorAction Stop).ProcessName
    }
    catch {
        "PID $($listener.OwningProcess)"
    }
    throw "$Label port $Port is already used by $processName. Choose another port."
}

function Invoke-Ed25519Keygen {
    param([Parameter(Mandatory = $true)][string]$KeyPath)

    $keygen = Join-Path $env:WINDIR 'System32\OpenSSH\ssh-keygen.exe'
    if (-not (Test-Path $keygen -PathType Leaf)) {
        throw 'Windows OpenSSH Client is required to generate a key.'
    }
    if ((Test-Path -LiteralPath $KeyPath) -or (Test-Path -LiteralPath ($KeyPath + '.pub'))) {
        throw 'The private or public key file already exists. Choose a different name.'
    }
    $parentDirectory = Split-Path -Parent $KeyPath
    if ($parentDirectory) {
        New-Item -ItemType Directory -Force $parentDirectory | Out-Null
    }

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $keygen
    $startInfo.Arguments = (
        '-q -t ed25519 -a 64 -N "" -C "Red Arrow" -f "{0}"' -f $KeyPath.Replace('"', '\"')
    )
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($startInfo)
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0 -or
        -not (Test-Path -LiteralPath $KeyPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath ($KeyPath + '.pub') -PathType Leaf)) {
        Remove-Item -Force $KeyPath, ($KeyPath + '.pub') -ErrorAction SilentlyContinue
        $detail = (($stderr, $stdout) | Where-Object { $_ }) -join "`r`n"
        throw "ssh-keygen failed. $detail"
    }
    return $KeyPath
}

function Generate-Ed25519Key {
    $confirmation = [System.Windows.Forms.MessageBox]::Show(
        "Red Arrow will create a dedicated Ed25519 private key without a passphrase so it can connect automatically at sign-in.`r`n`r`nUse this key only for the intended SSH account and protect the private key file.`r`n`r`nContinue?",
        'Generate SSH key',
        'YesNo',
        'Warning'
    )
    if ($confirmation -ne 'Yes') { return $null }

    $sshDirectory = Join-Path $env:USERPROFILE '.ssh'
    New-Item -ItemType Directory -Force $sshDirectory | Out-Null
    $dialog = New-Object System.Windows.Forms.SaveFileDialog
    $dialog.Title = 'Save a new Red Arrow Ed25519 private key'
    $dialog.InitialDirectory = $sshDirectory
    $dialog.FileName = 'red_arrow_ed25519'
    $dialog.Filter = 'SSH private key (no extension)|*'
    $dialog.OverwritePrompt = $false
    if ($dialog.ShowDialog() -ne 'OK') { return $null }
    return Invoke-Ed25519Keygen -KeyPath $dialog.FileName
}

if ($KeygenSmokeTest) {
    $testDirectory = Join-Path $env:TEMP ('RedArrow-keygen-smoke-' + [Guid]::NewGuid().ToString('N'))
    $testKey = Join-Path $testDirectory 'id_ed25519'
    try {
        Invoke-Ed25519Keygen -KeyPath $testKey | Out-Null
        $publicKey = Get-Content -Raw -LiteralPath ($testKey + '.pub')
        if (-not $publicKey.StartsWith('ssh-ed25519 ')) {
            throw 'Generated public key is not Ed25519.'
        }
        Write-Output "KEYGEN_SMOKE_OK private=$testKey public=$testKey.pub"
    }
    finally {
        Remove-Item -Recurse -Force $testDirectory -ErrorAction SilentlyContinue
    }
    if (Test-Path $testDirectory) {
        throw 'Temporary key-generation directory was not removed.'
    }
    exit 0
}

function Invoke-ControlScript {
    param([string]$ScriptName, [string[]]$Arguments = @(), [switch]$Wait)
    $scriptPath = Join-Path $appDir $ScriptName
    $argumentList = @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-WindowStyle', 'Hidden',
        '-File', $scriptPath
    ) + $Arguments

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $powershellExe
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
    $startInfo.Arguments = (($argumentList | ForEach-Object {
        '"' + ([string]$_).Replace('"', '\"') + '"'
    }) -join ' ')
    $process = [System.Diagnostics.Process]::Start($startInfo)
    if ($Wait) { $process.WaitForExit() }
    return $process
}

function Save-Configuration {
    [int]$sshPort = $sshPortInput.Value
    [int]$socksPort = $socksPortInput.Value
    [int]$httpPort = $httpPortInput.Value
    $hostValue = $hostInput.Text.Trim()
    $userValue = $userInput.Text.Trim()
    $identityValue = $identityInput.Text.Trim()

    if ([string]::IsNullOrWhiteSpace($hostValue)) { throw 'SSH host is required.' }
    if ([string]::IsNullOrWhiteSpace($userValue)) { throw 'SSH user is required.' }
    if ($socksPort -eq $httpPort) { throw 'SOCKS5 and HTTP ports must be different.' }
    Assert-ListenerPortAvailable -Port $socksPort -Label 'SOCKS5'
    Assert-ListenerPortAvailable -Port $httpPort -Label 'HTTP proxy'
    if ($identityValue -and -not (Test-Path -LiteralPath $identityValue -PathType Leaf)) {
        throw 'The selected private key does not exist.'
    }

    [string[]]$directDomains = ConvertTo-StringArray (Split-Rules $directDomainsInput.Text)
    [string[]]$directCidrs = ConvertTo-StringArray (Split-Rules $directCidrsInput.Text)
    [string[]]$forceProxyDomains = ConvertTo-StringArray (Split-Rules $forceProxyDomainsInput.Text)
    foreach ($cidr in $directCidrs) {
        if ($cidr -notmatch '^[0-9a-fA-F:.]+/[0-9]{1,3}$') {
            throw "Invalid direct CIDR: $cidr"
        }
    }

    $newConfig = [ordered]@{
        ssh_host = $hostValue
        ssh_port = $sshPort
        ssh_user = $userValue
        identity_file = $identityValue
        proxy_jump = if ($proxyJumpInput.Text.Trim()) { $proxyJumpInput.Text.Trim() } else { $null }
        socks_bind = '127.0.0.1'
        socks_port = $socksPort
        http_bind = '127.0.0.1'
        http_port = $httpPort
        connect_timeout_seconds = 10
        server_alive_interval_seconds = 30
        reconnect_delay_seconds = 3
        log_file = $logPath
        system_proxy_mode = Get-ModeValue
        cn_rules_files = @('rules\china.txt', 'rules\china6.txt')
        direct_domains = $directDomains
        direct_cidrs = $directCidrs
        force_proxy_domains = $forceProxyDomains
    }

    New-Item -ItemType Directory -Force $configDir | Out-Null
    $candidatePath = Join-Path $configDir 'config.candidate.json'
    try {
        $newConfig | ConvertTo-Json | Set-Content -Encoding UTF8 $candidatePath
        $checkOutput = & $executable --config $candidatePath --check-config 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw ($checkOutput -join "`r`n")
        }
        Move-Item -Force $candidatePath $configPath
    }
    catch {
        Remove-Item -Force $candidatePath -ErrorAction SilentlyContinue
        throw
    }

    if ($startupCheck.Checked) {
        New-Item -Path $runKeyPath -Force | Out-Null
        $launcher = Join-Path $appDir 'start-hidden.vbs'
        Set-ItemProperty `
            -Path $runKeyPath `
            -Name $runValueName `
            -Value ('"' + $wscriptExe + '" "' + $launcher + '"')
    }
    else {
        Remove-ItemProperty `
            -Path $runKeyPath `
            -Name $runValueName `
            -ErrorAction SilentlyContinue
    }
    Remove-ItemProperty `
        -Path $runKeyPath `
        -Name 'LatexToolsSshProxy' `
        -ErrorAction SilentlyContinue

    $script:config = $newConfig
    return $newConfig
}

function Start-Connection {
    try {
        Save-Configuration | Out-Null
        Invoke-ControlScript -ScriptName 'stop-proxy.ps1' -Wait | Out-Null
        Invoke-ControlScript -ScriptName 'start-proxy.ps1' | Out-Null
        $bottomStatus.Text = 'Starting SSH tunnel...'
        $bottomStatus.ForeColor = $colors.Amber
    }
    catch {
        [System.Windows.Forms.MessageBox]::Show(
            $_.Exception.Message,
            'Cannot start Red Arrow',
            'OK',
            'Warning'
        ) | Out-Null
        $tabControl.SelectedTab = $serverTab
    }
}

function Stop-Connection {
    try {
        Invoke-ControlScript -ScriptName 'stop-proxy.ps1' -Wait | Out-Null
        $bottomStatus.Text = 'Disconnected. Previous Windows proxy settings restored.'
        $bottomStatus.ForeColor = $colors.Muted
    }
    catch {
        [System.Windows.Forms.MessageBox]::Show(
            $_.Exception.Message,
            'Cannot stop Red Arrow',
            'OK',
            'Error'
        ) | Out-Null
    }
}

function Refresh-Log {
    if (-not (Test-Path $logPath -PathType Leaf)) {
        $logBox.Text = 'No log has been written yet.'
        return
    }
    $file = Get-Item $logPath
    if ($file.Length -eq $script:lastLogLength) { return }
    $script:lastLogLength = $file.Length
    $lines = @(Get-Content $logPath -Tail 400 -ErrorAction SilentlyContinue)
    $logBox.Lines = $lines
    $logBox.SelectionStart = $logBox.TextLength
    $logBox.ScrollToCaret()
}

function Refresh-Status {
    $core = Get-CoreProcess
    $ssh = Get-SshProcess
    $settings = Get-ItemProperty $internetSettingsPath -ErrorAction SilentlyContinue
    $proxyEnabled = $settings -and [int]$settings.ProxyEnable -eq 1
    $proxyAddress = if ($proxyEnabled) { [string]$settings.ProxyServer } else { 'Off' }

    if ($core -and $ssh) {
        $connectionStatus.Text = '  Connected  '
        $connectionStatus.BackColor = $colors.SoftGreen
        $connectionStatus.ForeColor = $colors.Green
        $connectButton.Text = 'Restart'
        $connectButton.Enabled = $true
        $disconnectButton.Enabled = $true
        $overviewConnectionValue.Text = 'Connected'
        $overviewConnectionValue.ForeColor = $colors.Green
        $notifyIcon.Text = 'Red Arrow - Connected'
    }
    elseif ($core) {
        $connectionStatus.Text = '  Connecting  '
        $connectionStatus.BackColor = [System.Drawing.Color]::FromArgb(255, 248, 225)
        $connectionStatus.ForeColor = $colors.Amber
        $connectButton.Text = 'Restart'
        $disconnectButton.Enabled = $true
        $overviewConnectionValue.Text = 'Connecting'
        $overviewConnectionValue.ForeColor = $colors.Amber
        $notifyIcon.Text = 'Red Arrow - Connecting'
    }
    else {
        $connectionStatus.Text = '  Disconnected  '
        $connectionStatus.BackColor = $colors.SoftRed
        $connectionStatus.ForeColor = $colors.RedDark
        $connectButton.Text = 'Connect'
        $connectButton.Enabled = $true
        $disconnectButton.Enabled = $false
        $overviewConnectionValue.Text = 'Disconnected'
        $overviewConnectionValue.ForeColor = $colors.RedDark
        $notifyIcon.Text = 'Red Arrow - Disconnected'
    }

    $overviewSystemProxyValue.Text = if ($proxyEnabled) { $proxyAddress } else { 'Off' }
    $overviewSshValue.Text = if ($hostInput.Text.Trim()) {
        "$($userInput.Text.Trim())@$($hostInput.Text.Trim()):$([int]$sshPortInput.Value)"
    }
    else {
        'Not configured'
    }
    $overviewPortsValue.Text = "SOCKS5 $([int]$socksPortInput.Value)  |  HTTP $([int]$httpPortInput.Value)"
    $modeSummary.Text = switch ($modeCombo.SelectedIndex) {
        0 { 'Local proxy only' }
        2 { 'Global proxy' }
        default { 'Bypass mainland China' }
    }
    if ($tabControl.SelectedTab -eq $logsTab) { Refresh-Log }
}

$form = New-Object System.Windows.Forms.Form
$form.Text = 'Red Arrow'
$form.StartPosition = 'CenterScreen'
$form.ClientSize = New-Object System.Drawing.Size(920, 700)
$form.MinimumSize = New-Object System.Drawing.Size(936, 739)
$form.BackColor = $colors.Background
$form.Font = New-Object System.Drawing.Font('Segoe UI', 9)
$form.AutoScaleMode = 'Dpi'
if (Test-Path $iconPath -PathType Leaf) {
    $form.Icon = New-Object System.Drawing.Icon($iconPath)
}

$header = New-Object System.Windows.Forms.Panel
$header.Location = New-Object System.Drawing.Point(0, 0)
$header.Size = New-Object System.Drawing.Size(920, 94)
$header.BackColor = $colors.Surface
$header.Anchor = 'Top,Left,Right'
$form.Controls.Add($header)

$logo = New-Object System.Windows.Forms.PictureBox
$logo.Location = New-Object System.Drawing.Point(24, 20)
$logo.Size = New-Object System.Drawing.Size(52, 52)
$logo.SizeMode = 'Zoom'
if (Test-Path $logoPath -PathType Leaf) {
    $logoBytes = [System.IO.File]::ReadAllBytes($logoPath)
    $logoStream = New-Object System.IO.MemoryStream(, $logoBytes)
    try {
        $loadedLogo = [System.Drawing.Image]::FromStream($logoStream)
        try {
            $logo.Image = New-Object System.Drawing.Bitmap($loadedLogo)
        }
        finally {
            $loadedLogo.Dispose()
        }
    }
    finally {
        $logoStream.Dispose()
    }
}
$header.Controls.Add($logo)

$titleFont = New-Object System.Drawing.Font('Segoe UI Semibold', 18)
$subheadingFont = New-Object System.Drawing.Font('Segoe UI Semibold', 10)
$valueFont = New-Object System.Drawing.Font('Segoe UI Semibold', 13)
[void](New-Label -Parent $header -Text 'Red Arrow' -X 88 -Y 18 -Width 240 -Height 34 -Font $titleFont)
[void](New-Label -Parent $header -Text 'SSH system proxy for Windows' -X 90 -Y 52 -Width 260 -Height 24 -Color $colors.Muted)

$connectionStatus = New-Label -Parent $header -Text '  Disconnected  ' -X 380 -Y 31 -Width 122 -Height 30 -Font $subheadingFont
$connectionStatus.TextAlign = 'MiddleCenter'
$connectionStatus.BackColor = $colors.SoftRed
$connectionStatus.ForeColor = $colors.RedDark

[void](New-Label -Parent $header -Text 'Proxy mode' -X 520 -Y 11 -Width 140 -Height 18 -Color $colors.Muted)
$modeCombo = New-Object System.Windows.Forms.ComboBox
$modeCombo.Name = 'modeCombo'
$modeCombo.DropDownStyle = 'DropDownList'
$modeCombo.Location = New-Object System.Drawing.Point(520, 31)
$modeCombo.Size = New-Object System.Drawing.Size(190, 28)
[void]$modeCombo.Items.Add('Local proxy only')
[void]$modeCombo.Items.Add('Bypass mainland China')
[void]$modeCombo.Items.Add('Global proxy')
$modeCombo.SelectedIndex = switch ([string]$config.system_proxy_mode) {
    'off' { 0 }
    'global' { 2 }
    default { 1 }
}
$header.Controls.Add($modeCombo)

$connectButton = New-Button -Parent $header -Text 'Connect' -X 726 -Y 26 -Width 82 -Height 38 -BackColor $colors.Red -ForeColor ([System.Drawing.Color]::White)
$connectButton.Name = 'connectButton'
$disconnectButton = New-Button -Parent $header -Text 'Disconnect' -X 814 -Y 26 -Width 82 -Height 38
$disconnectButton.Name = 'disconnectButton'
$disconnectButton.Enabled = $false

$tabControl = New-Object System.Windows.Forms.TabControl
$tabControl.Name = 'mainTabs'
$tabControl.Location = New-Object System.Drawing.Point(20, 110)
$tabControl.Size = New-Object System.Drawing.Size(880, 548)
$tabControl.Anchor = 'Top,Bottom,Left,Right'
$form.Controls.Add($tabControl)

$overviewTab = New-Object System.Windows.Forms.TabPage
$overviewTab.Text = 'Overview'
$overviewTab.BackColor = $colors.Background
$serverTab = New-Object System.Windows.Forms.TabPage
$serverTab.Text = 'Server'
$serverTab.BackColor = $colors.Background
$routingTab = New-Object System.Windows.Forms.TabPage
$routingTab.Text = 'Routing'
$routingTab.BackColor = $colors.Background
$logsTab = New-Object System.Windows.Forms.TabPage
$logsTab.Text = 'Logs'
$logsTab.BackColor = $colors.Background
$tabControl.TabPages.AddRange(@($overviewTab, $serverTab, $routingTab, $logsTab))

# Overview
[void](New-Label -Parent $overviewTab -Text 'Connection' -X 30 -Y 32 -Width 180 -Font $subheadingFont -Color $colors.Muted)
$overviewConnectionValue = New-Label -Parent $overviewTab -Text 'Disconnected' -X 30 -Y 60 -Width 360 -Height 32 -Font $valueFont -Color $colors.RedDark
[void](New-Label -Parent $overviewTab -Text 'SSH server' -X 30 -Y 125 -Width 180 -Font $subheadingFont -Color $colors.Muted)
$overviewSshValue = New-Label -Parent $overviewTab -Text 'Not configured' -X 30 -Y 153 -Width 360 -Height 32 -Font $valueFont
[void](New-Label -Parent $overviewTab -Text 'System proxy' -X 450 -Y 32 -Width 180 -Font $subheadingFont -Color $colors.Muted)
$overviewSystemProxyValue = New-Label -Parent $overviewTab -Text 'Off' -X 450 -Y 60 -Width 360 -Height 32 -Font $valueFont
[void](New-Label -Parent $overviewTab -Text 'Local listeners' -X 450 -Y 125 -Width 180 -Font $subheadingFont -Color $colors.Muted)
$overviewPortsValue = New-Label -Parent $overviewTab -Text '' -X 450 -Y 153 -Width 360 -Height 32 -Font $valueFont

$separator = New-Object System.Windows.Forms.Panel
$separator.Location = New-Object System.Drawing.Point(30, 220)
$separator.Size = New-Object System.Drawing.Size(790, 1)
$separator.BackColor = $colors.Border
$overviewTab.Controls.Add($separator)

[void](New-Label -Parent $overviewTab -Text 'Active mode' -X 30 -Y 253 -Width 180 -Font $subheadingFont -Color $colors.Muted)
$modeSummary = New-Label -Parent $overviewTab -Text '' -X 30 -Y 282 -Width 360 -Height 34 -Font $valueFont
$modeDescription = New-Label -Parent $overviewTab -Text 'Traffic from applications that use Windows system proxy settings is routed automatically.' -X 30 -Y 326 -Width 760 -Height 48 -Color $colors.Muted

$testButton = New-Button -Parent $overviewTab -Text 'Test proxy' -X 30 -Y 405 -Width 110
$openConfigButton = New-Button -Parent $overviewTab -Text 'Open config folder' -X 148 -Y 405 -Width 140
$overviewApplyButton = New-Button -Parent $overviewTab -Text 'Apply mode and restart' -X 624 -Y 405 -Width 196 -BackColor $colors.Red -ForeColor ([System.Drawing.Color]::White)

# Server tab
$serverGroup = New-Object System.Windows.Forms.GroupBox
$serverGroup.Text = 'SSH connection'
$serverGroup.Location = New-Object System.Drawing.Point(24, 22)
$serverGroup.Size = New-Object System.Drawing.Size(812, 250)
$serverTab.Controls.Add($serverGroup)

[void](New-Label -Parent $serverGroup -Text 'Host' -X 24 -Y 36 -Width 120)
$hostInput = New-TextBox -Parent $serverGroup -X 150 -Y 32 -Width 420 -Text ([string]$config.ssh_host)
$hostInput.Name = 'sshHostInput'
[void](New-Label -Parent $serverGroup -Text 'Port' -X 592 -Y 36 -Width 52)
$sshPortInput = New-Object System.Windows.Forms.NumericUpDown
$sshPortInput.Location = New-Object System.Drawing.Point(650, 32)
$sshPortInput.Size = New-Object System.Drawing.Size(120, 28)
$sshPortInput.Minimum = 1
$sshPortInput.Maximum = 65535
$sshPortInput.Value = [int]$config.ssh_port
$serverGroup.Controls.Add($sshPortInput)

[void](New-Label -Parent $serverGroup -Text 'User' -X 24 -Y 82 -Width 120)
$userInput = New-TextBox -Parent $serverGroup -X 150 -Y 78 -Width 250 -Text ([string]$config.ssh_user)
$userInput.Name = 'sshUserInput'
$proxyJumpLabel = New-Label -Parent $serverGroup -Text 'ProxyJump (optional)' -X 424 -Y 82 -Width 142
$proxyJumpInput = New-TextBox -Parent $serverGroup -X 570 -Y 78 -Width 200 -Text ([string]$config.proxy_jump)
$proxyJumpToolTip = New-Object System.Windows.Forms.ToolTip
$proxyJumpToolTip.SetToolTip($proxyJumpInput, 'Leave blank for a direct SSH connection. Example: user@jump-host:port')

[void](New-Label -Parent $serverGroup -Text 'Private key' -X 24 -Y 128 -Width 120)
$identityInput = New-TextBox -Parent $serverGroup -X 150 -Y 124 -Width 350 -Text ([string]$config.identity_file)
$generateKeyButton = New-Button -Parent $serverGroup -Text 'Generate' -X 508 -Y 121 -Width 78 -Height 32
$browseButton = New-Button -Parent $serverGroup -Text 'Browse...' -X 592 -Y 121 -Width 82 -Height 32
$copyPublicKeyButton = New-Button -Parent $serverGroup -Text 'Copy .pub' -X 680 -Y 121 -Width 90 -Height 32

[void](New-Label -Parent $serverGroup -Text 'SOCKS5 port' -X 24 -Y 176 -Width 120)
$socksPortInput = New-Object System.Windows.Forms.NumericUpDown
$socksPortInput.Location = New-Object System.Drawing.Point(150, 172)
$socksPortInput.Size = New-Object System.Drawing.Size(120, 28)
$socksPortInput.Minimum = 1
$socksPortInput.Maximum = 65535
$socksPortInput.Value = [int]$config.socks_port
$serverGroup.Controls.Add($socksPortInput)
[void](New-Label -Parent $serverGroup -Text 'HTTP port' -X 310 -Y 176 -Width 90)
$httpPortInput = New-Object System.Windows.Forms.NumericUpDown
$httpPortInput.Location = New-Object System.Drawing.Point(404, 172)
$httpPortInput.Size = New-Object System.Drawing.Size(120, 28)
$httpPortInput.Minimum = 1
$httpPortInput.Maximum = 65535
$httpPortInput.Value = [int]$config.http_port
$serverGroup.Controls.Add($httpPortInput)

$startupCheck = New-Object System.Windows.Forms.CheckBox
$startupCheck.Text = 'Start Red Arrow when I sign in'
$startupCheck.Location = New-Object System.Drawing.Point(548, 172)
$startupCheck.Size = New-Object System.Drawing.Size(230, 26)
$startupCheck.Checked = $null -ne (Get-ItemProperty -Path $runKeyPath -Name $runValueName -ErrorAction SilentlyContinue).$runValueName
$serverGroup.Controls.Add($startupCheck)

$serverSaveButton = New-Button -Parent $serverTab -Text 'Save settings' -X 690 -Y 294 -Width 146 -BackColor $colors.Red -ForeColor ([System.Drawing.Color]::White)
$serverStatus = New-Label -Parent $serverTab -Text '' -X 24 -Y 302 -Width 620 -Height 28 -Color $colors.Green

# Routing tab
[void](New-Label -Parent $routingTab -Text 'Always direct domains' -X 24 -Y 28 -Width 220 -Font $subheadingFont)
$directDomainsInput = New-TextBox -Parent $routingTab -X 24 -Y 56 -Width 812 -Height 100 -Multiline -Text (@($config.direct_domains) -join "`r`n")
$directDomainsInput.Name = 'directDomainsInput'
[void](New-Label -Parent $routingTab -Text 'Additional direct CIDRs' -X 24 -Y 174 -Width 220 -Font $subheadingFont)
$directCidrsInput = New-TextBox -Parent $routingTab -X 24 -Y 202 -Width 390 -Height 160 -Multiline -Text (@($config.direct_cidrs) -join "`r`n")
[void](New-Label -Parent $routingTab -Text 'Always proxy domains' -X 446 -Y 174 -Width 220 -Font $subheadingFont)
$forceProxyDomainsInput = New-TextBox -Parent $routingTab -X 446 -Y 202 -Width 390 -Height 160 -Multiline -Text (@($config.force_proxy_domains) -join "`r`n")
$routingStatus = New-Label -Parent $routingTab -Text 'Bundled mainland China IPv4/IPv6 rules are active in bypass mode.' -X 24 -Y 386 -Width 600 -Height 28 -Color $colors.Muted
$routingSaveButton = New-Button -Parent $routingTab -Text 'Save routing rules' -X 674 -Y 382 -Width 162 -BackColor $colors.Red -ForeColor ([System.Drawing.Color]::White)

# Logs tab
$logBox = New-Object System.Windows.Forms.RichTextBox
$logBox.Location = New-Object System.Drawing.Point(20, 20)
$logBox.Size = New-Object System.Drawing.Size(816, 420)
$logBox.ReadOnly = $true
$logBox.BackColor = [System.Drawing.Color]::FromArgb(30, 32, 36)
$logBox.ForeColor = [System.Drawing.Color]::FromArgb(224, 226, 232)
$logBox.Font = New-Object System.Drawing.Font('Consolas', 9)
$logBox.WordWrap = $false
$logsTab.Controls.Add($logBox)
$refreshLogButton = New-Button -Parent $logsTab -Text 'Refresh' -X 20 -Y 454 -Width 90
$openLogButton = New-Button -Parent $logsTab -Text 'Open folder' -X 118 -Y 454 -Width 110
$clearLogButton = New-Button -Parent $logsTab -Text 'Clear log' -X 746 -Y 454 -Width 90

$bottomStatus = New-Label -Parent $form -Text 'Ready' -X 24 -Y 670 -Width 870 -Height 22 -Color $colors.Muted
$bottomStatus.Anchor = 'Bottom,Left,Right'

# Tray
$notifyIcon = New-Object System.Windows.Forms.NotifyIcon
$notifyIcon.Text = 'Red Arrow'
$notifyIcon.Visible = -not $SmokeTest
if ($form.Icon) { $notifyIcon.Icon = $form.Icon }
$trayMenu = New-Object System.Windows.Forms.ContextMenuStrip
$trayOpen = $trayMenu.Items.Add('Open Red Arrow')
$trayConnect = $trayMenu.Items.Add('Connect / Restart')
$trayDisconnect = $trayMenu.Items.Add('Disconnect')
[void]$trayMenu.Items.Add((New-Object System.Windows.Forms.ToolStripSeparator))
$trayExit = $trayMenu.Items.Add('Exit control center')
$notifyIcon.ContextMenuStrip = $trayMenu

# Events
$connectButton.Add_Click({ Start-Connection })
$overviewApplyButton.Add_Click({ Start-Connection })
$disconnectButton.Add_Click({ Stop-Connection })
$trayConnect.Add_Click({ Start-Connection })
$trayDisconnect.Add_Click({ Stop-Connection })
$browseButton.Add_Click({
    $dialog = New-Object System.Windows.Forms.OpenFileDialog
    $dialog.Title = 'Select an OpenSSH private key'
    $dialog.Filter = 'All files (*.*)|*.*'
    if ($dialog.ShowDialog() -eq 'OK') { $identityInput.Text = $dialog.FileName }
})
$generateKeyButton.Add_Click({
    try {
        $keyPath = Generate-Ed25519Key
        if (-not $keyPath) { return }
        $identityInput.Text = $keyPath
        $publicKey = Get-Content -Raw -LiteralPath ($keyPath + '.pub')
        [System.Windows.Forms.Clipboard]::SetText($publicKey.Trim())
        [System.Windows.Forms.MessageBox]::Show(
            "The Ed25519 key pair was generated.`r`n`r`nPrivate key:`r`n$keyPath`r`n`r`nThe public key was copied to the clipboard. Add it to the SSH server's authorized_keys file.",
            'Red Arrow key generated',
            'OK',
            'Information'
        ) | Out-Null
    }
    catch {
        [System.Windows.Forms.MessageBox]::Show(
            $_.Exception.Message,
            'Cannot generate SSH key',
            'OK',
            'Error'
        ) | Out-Null
    }
})
$copyPublicKeyButton.Add_Click({
    try {
        $privatePath = $identityInput.Text.Trim()
        if (-not $privatePath) { throw 'Select or generate a private key first.' }
        $publicPath = $privatePath + '.pub'
        if (-not (Test-Path -LiteralPath $publicPath -PathType Leaf)) {
            throw "Public key file not found: $publicPath"
        }
        $publicKey = Get-Content -Raw -LiteralPath $publicPath
        [System.Windows.Forms.Clipboard]::SetText($publicKey.Trim())
        $bottomStatus.Text = 'Public key copied to the clipboard.'
        $bottomStatus.ForeColor = $colors.Green
    }
    catch {
        [System.Windows.Forms.MessageBox]::Show(
            $_.Exception.Message,
            'Cannot copy public key',
            'OK',
            'Warning'
        ) | Out-Null
    }
})
$serverSaveButton.Add_Click({
    try {
        Save-Configuration | Out-Null
        $serverStatus.Text = 'Settings saved.'
        $serverStatus.ForeColor = $colors.Green
        $bottomStatus.Text = 'Configuration saved.'
    }
    catch {
        $serverStatus.Text = $_.Exception.Message
        $serverStatus.ForeColor = $colors.RedDark
    }
})
$routingSaveButton.Add_Click({
    try {
        Save-Configuration | Out-Null
        $routingStatus.Text = 'Routing rules saved. Restart the connection to apply them.'
        $routingStatus.ForeColor = $colors.Green
    }
    catch {
        $routingStatus.Text = $_.Exception.Message
        $routingStatus.ForeColor = $colors.RedDark
    }
})
$modeCombo.Add_SelectedIndexChanged({ Refresh-Status })
$testButton.Add_Click({
    try {
        $testButton.Enabled = $false
        $bottomStatus.Text = 'Testing HTTP proxy...'
        [System.Windows.Forms.Application]::DoEvents()
        $result = & curl.exe `
            -fsS `
            --ssl-no-revoke `
            --connect-timeout 8 `
            --max-time 15 `
            -x "http://127.0.0.1:$([int]$httpPortInput.Value)" `
            https://api.ipify.org
        if ($LASTEXITCODE -ne 0) { throw "curl exited with code $LASTEXITCODE" }
        $bottomStatus.Text = "Proxy is working. Exit IP: $(([string]$result).Trim())"
        $bottomStatus.ForeColor = $colors.Green
    }
    catch {
        $bottomStatus.Text = "Proxy test failed: $($_.Exception.Message)"
        $bottomStatus.ForeColor = $colors.RedDark
    }
    finally {
        $testButton.Enabled = $true
    }
})
$openConfigButton.Add_Click({
    New-Item -ItemType Directory -Force $configDir | Out-Null
    Start-Process explorer.exe ('"' + $configDir + '"')
})
$refreshLogButton.Add_Click({ $script:lastLogLength = -1; Refresh-Log })
$openLogButton.Add_Click({
    New-Item -ItemType Directory -Force $configDir | Out-Null
    Start-Process explorer.exe ('"' + $configDir + '"')
})
$clearLogButton.Add_Click({
    if (Test-Path $logPath) { Clear-Content $logPath }
    $script:lastLogLength = -1
    Refresh-Log
})
$tabControl.Add_SelectedIndexChanged({ Refresh-Status })
$trayOpen.Add_Click({ $form.Show(); $form.WindowState = 'Normal'; $form.Activate() })
$notifyIcon.Add_DoubleClick({ $form.Show(); $form.WindowState = 'Normal'; $form.Activate() })
$trayExit.Add_Click({
    $script:allowClose = $true
    $notifyIcon.Visible = $false
    $form.Close()
})
$form.Add_Resize({
    if ($form.WindowState -eq 'Minimized') {
        $form.Hide()
        if ($notifyIcon.Visible) {
            $notifyIcon.ShowBalloonTip(1500, 'Red Arrow', 'Red Arrow is still available in the notification area.', 'Info')
        }
    }
})
$form.Add_FormClosing({
    param($sender, $eventArgs)
    if (-not $script:allowClose -and $eventArgs.CloseReason -eq 'UserClosing') {
        $eventArgs.Cancel = $true
        $form.Hide()
    }
})
$form.Add_FormClosed({
    $notifyIcon.Dispose()
    if ($logo.Image) {
        $logo.Image.Dispose()
        $logo.Image = $null
    }
})

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 1500
$timer.Add_Tick({ Refresh-Status })

Refresh-Status
if (-not $hostInput.Text.Trim()) { $tabControl.SelectedTab = $serverTab }

if ($SmokeTest -or $ScreenshotPath) {
    $requiredControls = @(
        $modeCombo,
        $connectButton,
        $disconnectButton,
        $tabControl,
        $hostInput,
        $userInput,
        $identityInput,
        $proxyJumpLabel,
        $generateKeyButton,
        $browseButton,
        $copyPublicKeyButton,
        $directDomainsInput,
        $logBox
    )
    if ($requiredControls -contains $null) { throw 'A required UI control was not created.' }
    if ($hostInput.Text -or $userInput.Text) { throw 'Private server defaults are present in the UI.' }
    if ([int]$socksPortInput.Value -ne 1080 -or [int]$httpPortInput.Value -ne 8118) {
        throw 'Unexpected default proxy ports.'
    }
    if ($proxyJumpLabel.Text -notmatch 'optional' -or -not $proxyJumpToolTip.GetToolTip($proxyJumpInput)) {
        throw 'ProxyJump is not clearly marked as optional.'
    }
    if ($tabControl.TabPages.Count -ne 4) { throw 'Unexpected tab count.' }
    $tabControl.SelectedTab = switch ($ScreenshotTab) {
        'Overview' { $overviewTab }
        'Routing' { $routingTab }
        'Logs' { $logsTab }
        default { $serverTab }
    }
    if ($ScreenshotPath) {
        $screenshotDirectory = Split-Path -Parent $ScreenshotPath
        if ($screenshotDirectory) {
            New-Item -ItemType Directory -Force $screenshotDirectory | Out-Null
        }
        $form.Show()
        [System.Windows.Forms.Application]::DoEvents()
        $bitmap = New-Object System.Drawing.Bitmap($form.Width, $form.Height)
        try {
            $form.DrawToBitmap($bitmap, (New-Object System.Drawing.Rectangle(0, 0, $bitmap.Width, $bitmap.Height)))
            $bitmap.Save($ScreenshotPath, [System.Drawing.Imaging.ImageFormat]::Png)
        }
        finally {
            $bitmap.Dispose()
            $form.Hide()
        }
        Write-Output "UI_SCREENSHOT_OK path=$ScreenshotPath"
    }
    Write-Output "UI_SMOKE_OK title=$($form.Text) tabs=$($tabControl.TabPages.Count) mode=$($modeCombo.SelectedItem)"
    $notifyIcon.Dispose()
    if ($logo.Image) {
        $logo.Image.Dispose()
        $logo.Image = $null
    }
    $form.Dispose()
    exit 0
}

$timer.Start()
[System.Windows.Forms.Application]::EnableVisualStyles()
[System.Windows.Forms.Application]::Run($form)