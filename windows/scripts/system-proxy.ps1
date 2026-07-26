param(
    [ValidateSet('off', 'global', 'bypass_cn')]
    [string]$Mode = 'off',
    [ValidateRange(1, 65535)]
    [int]$HttpPort = 8118,
    [string]$ConfigPath,
    [switch]$Restore,
    [switch]$InitializeDirect
)

$ErrorActionPreference = 'Stop'
$settingsPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
$connectionsPath = Join-Path $settingsPath 'Connections'
$stateDir = Join-Path $env:LOCALAPPDATA 'RedArrow'
$backupPath = Join-Path $stateDir 'system-proxy-backup.json'
$settingsValues = @('ProxyEnable', 'ProxyServer', 'ProxyOverride', 'AutoConfigURL', 'AutoDetect')
$connectionValues = @('DefaultConnectionSettings', 'SavedLegacySettings')

if ($ConfigPath) {
    if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
        throw "Proxy configuration not found: $ConfigPath"
    }
    $config = Get-Content -Raw -LiteralPath $ConfigPath | ConvertFrom-Json
    $Mode = [string]$config.system_proxy_mode
    $HttpPort = [int]$config.http_port
}

function Get-RegistrySnapshot {
    param([string]$Path, [string[]]$Names)
    $snapshot = [ordered]@{}
    if (-not (Test-Path -LiteralPath $Path)) {
        foreach ($name in $Names) {
            $snapshot[$name] = [ordered]@{ exists = $false; value = $null; kind = $null }
        }
        return $snapshot
    }
    $key = Get-Item -LiteralPath $Path
    foreach ($name in $Names) {
        $exists = $key.GetValueNames() -contains $name
        $value = $null
        if ($exists) {
            $value = $key.GetValue($name, $null, 'DoNotExpandEnvironmentNames')
        }
        if ($value -is [byte[]]) {
            $value = [Convert]::ToBase64String($value)
        }
        $snapshot[$name] = [ordered]@{
            exists = $exists
            value = $value
            kind = if ($exists) { [string]$key.GetValueKind($name) } else { $null }
        }
    }
    return $snapshot
}

function Set-RegistryValueFromSnapshot {
    param([string]$Path, [string]$Name, $Entry)
    if (-not $Entry.exists) {
        Remove-ItemProperty -LiteralPath $Path -Name $Name -ErrorAction SilentlyContinue
        return
    }
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -Path $Path | Out-Null
    }
    $propertyType = switch ([string]$Entry.kind) {
        'DWord' { 'DWord' }
        'QWord' { 'QWord' }
        'ExpandString' { 'ExpandString' }
        'MultiString' { 'MultiString' }
        'Binary' { 'Binary' }
        default { 'String' }
    }
    $value = if ($propertyType -eq 'Binary' -and $Entry.value -is [string]) {
        [Convert]::FromBase64String([string]$Entry.value)
    }
    elseif ($propertyType -eq 'Binary') {
        [byte[]]$Entry.value
    }
    else {
        $Entry.value
    }
    New-ItemProperty `
        -LiteralPath $Path `
        -Name $Name `
        -Value $value `
        -PropertyType $propertyType `
        -Force | Out-Null
}

if (-not ('RedArrow.WinInetProxy' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

namespace RedArrow {
    public static class WinInetProxy {
        private const int INTERNET_OPTION_REFRESH = 37;
        private const int INTERNET_OPTION_SETTINGS_CHANGED = 39;
        private const int INTERNET_OPTION_PER_CONNECTION_OPTION = 75;
        private const int INTERNET_PER_CONN_FLAGS = 1;
        private const int INTERNET_PER_CONN_PROXY_SERVER = 2;
        private const int INTERNET_PER_CONN_PROXY_BYPASS = 3;
        private const int PROXY_TYPE_DIRECT = 0x1;
        private const int PROXY_TYPE_PROXY = 0x2;

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
        private struct INTERNET_PER_CONN_OPTION_LIST {
            public int dwSize;
            public IntPtr pszConnection;
            public int dwOptionCount;
            public int dwOptionError;
            public IntPtr pOptions;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
        private struct INTERNET_PER_CONN_OPTION {
            public int dwOption;
            public INTERNET_PER_CONN_OPTION_VALUE Value;
        }

        [StructLayout(LayoutKind.Explicit)]
        private struct INTERNET_PER_CONN_OPTION_VALUE {
            [FieldOffset(0)] public int dwValue;
            [FieldOffset(0)] public IntPtr pszValue;
        }

        [DllImport("wininet.dll", SetLastError = true, CharSet = CharSet.Auto)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool InternetSetOption(
            IntPtr hInternet,
            int dwOption,
            IntPtr lpBuffer,
            int dwBufferLength
        );

        public static void SetNamedProxy(string proxy, string bypass) {
            INTERNET_PER_CONN_OPTION[] options = new INTERNET_PER_CONN_OPTION[3];
            options[0].dwOption = INTERNET_PER_CONN_FLAGS;
            options[0].Value.dwValue = PROXY_TYPE_DIRECT | PROXY_TYPE_PROXY;
            options[1].dwOption = INTERNET_PER_CONN_PROXY_SERVER;
            options[1].Value.pszValue = Marshal.StringToHGlobalAuto(proxy);
            options[2].dwOption = INTERNET_PER_CONN_PROXY_BYPASS;
            options[2].Value.pszValue = Marshal.StringToHGlobalAuto(bypass);
            SetOptions(options);
        }

        public static void SetDirect() {
            INTERNET_PER_CONN_OPTION[] options = new INTERNET_PER_CONN_OPTION[1];
            options[0].dwOption = INTERNET_PER_CONN_FLAGS;
            options[0].Value.dwValue = PROXY_TYPE_DIRECT;
            SetOptions(options);
        }

        public static void Refresh() {
            InternetSetOption(IntPtr.Zero, INTERNET_OPTION_SETTINGS_CHANGED, IntPtr.Zero, 0);
            InternetSetOption(IntPtr.Zero, INTERNET_OPTION_REFRESH, IntPtr.Zero, 0);
        }

        private static void SetOptions(INTERNET_PER_CONN_OPTION[] options) {
            int optionSize = Marshal.SizeOf(typeof(INTERNET_PER_CONN_OPTION));
            IntPtr optionsPointer = Marshal.AllocCoTaskMem(optionSize * options.Length);
            IntPtr listPointer = IntPtr.Zero;
            try {
                for (int index = 0; index < options.Length; index++) {
                    IntPtr target = new IntPtr(optionsPointer.ToInt64() + index * optionSize);
                    Marshal.StructureToPtr(options[index], target, false);
                }

                INTERNET_PER_CONN_OPTION_LIST list = new INTERNET_PER_CONN_OPTION_LIST();
                list.dwSize = Marshal.SizeOf(typeof(INTERNET_PER_CONN_OPTION_LIST));
                list.pszConnection = IntPtr.Zero;
                list.dwOptionCount = options.Length;
                list.dwOptionError = 0;
                list.pOptions = optionsPointer;
                listPointer = Marshal.AllocCoTaskMem(list.dwSize);
                Marshal.StructureToPtr(list, listPointer, false);

                if (!InternetSetOption(
                    IntPtr.Zero,
                    INTERNET_OPTION_PER_CONNECTION_OPTION,
                    listPointer,
                    list.dwSize
                )) {
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                }
                Refresh();
            }
            finally {
                for (int index = 0; index < options.Length; index++) {
                    if (options[index].dwOption == INTERNET_PER_CONN_PROXY_SERVER ||
                        options[index].dwOption == INTERNET_PER_CONN_PROXY_BYPASS) {
                        Marshal.FreeHGlobal(options[index].Value.pszValue);
                    }
                }
                if (listPointer != IntPtr.Zero) Marshal.FreeCoTaskMem(listPointer);
                Marshal.FreeCoTaskMem(optionsPointer);
            }
        }
    }
}
'@
}

New-Item -ItemType Directory -Force $stateDir | Out-Null
if (-not (Test-Path -LiteralPath $settingsPath)) {
    New-Item -Path $settingsPath | Out-Null
}

if ($InitializeDirect) {
    [RedArrow.WinInetProxy]::SetDirect()
    New-ItemProperty `
        -LiteralPath $settingsPath `
        -Name ProxyEnable `
        -Value 0 `
        -PropertyType DWord `
        -Force | Out-Null
    Remove-ItemProperty `
        -LiteralPath $settingsPath `
        -Name ProxyServer, ProxyOverride, AutoConfigURL, AutoDetect `
        -ErrorAction SilentlyContinue
    [RedArrow.WinInetProxy]::Refresh()
    Write-Output 'SYSTEM_PROXY=direct_initialized'
    exit 0
}

if ($Restore -or $Mode -eq 'off') {
    if (Test-Path -LiteralPath $backupPath -PathType Leaf) {
        $backup = Get-Content -Raw -LiteralPath $backupPath | ConvertFrom-Json
        foreach ($name in $settingsValues) {
            Set-RegistryValueFromSnapshot -Path $settingsPath -Name $name -Entry $backup.settings.$name
        }
        foreach ($name in $connectionValues) {
            Set-RegistryValueFromSnapshot -Path $connectionsPath -Name $name -Entry $backup.connections.$name
        }
        [RedArrow.WinInetProxy]::Refresh()
        Remove-Item -Force -LiteralPath $backupPath
    }
    Write-Output 'SYSTEM_PROXY=off'
    exit 0
}

if (-not (Test-Path -LiteralPath $backupPath -PathType Leaf)) {
    $backup = [ordered]@{
        settings = Get-RegistrySnapshot -Path $settingsPath -Names $settingsValues
        connections = Get-RegistrySnapshot -Path $connectionsPath -Names $connectionValues
    }
    $backup | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -LiteralPath $backupPath
}

$proxyAddress = "127.0.0.1:$HttpPort"
[RedArrow.WinInetProxy]::SetNamedProxy(
    "http=$proxyAddress;https=$proxyAddress",
    '<local>;localhost;127.*;[::1]'
)
Write-Output "SYSTEM_PROXY=$Mode $proxyAddress"
