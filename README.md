# Red Arrow

[中文文档](README_zh.md)

An open-source SSH tunnel proxy for **Android and Windows**. Both editions expose local SOCKS5 and HTTP proxies and forward proxied traffic through a remote SSH server.

| Platform | Download | Highlights |
|---|---|---|
| Windows 10/11 x64 | [Installer 1.2.2](https://github.com/JoursBleu/red-arrow/releases/download/windows-v1.2.2/RedArrow-Windows-Setup-1.2.2-x64.exe) | Native control center, WinINET system proxy, global/mainland-China-bypass modes, optional ProxyJump |
| Android 8.0+ | [APK beta](https://github.com/JoursBleu/red-arrow/releases/download/v0.1.0-beta/app-debug.apk) | Foreground service, LAN proxy sharing, password/key authentication, traffic statistics |

## Screenshots

### Windows

| Overview | SSH server |
|:---:|:---:|
| ![Windows overview](docs/windows-overview.png) | ![Windows server](docs/windows-server.png) |

### Android

| Home | Keys | Settings |
|:---:|:---:|:---:|
| ![Home](docs/home_en.jpg) | ![Keys](docs/keys_en.jpg) | ![Settings](docs/settings_en.jpg) |

## Shared Features

- **SSH Tunnel** — Connect to a remote SSH server, encrypted tunnel through NAT
- **Dual Proxy** — Local SOCKS5 and HTTP proxy endpoints
- **Key Management** — Generate or import SSH keys and copy/install public keys
- **Auto-Reconnect** — Automatic reconnection with exponential backoff on disconnect

### Windows

- **System Proxy** — Manage the signed-in user's WinINET proxy and restore the previous proxy/PAC settings on stop or failure
- **Routing Modes** — Local only, global proxy, or bypass mainland China using bundled IPv4/IPv6 rules
- **Custom Rules** — Always-direct domains/CIDRs and always-proxy domains
- **Optional ProxyJump** — Connect directly or through an OpenSSH jump host
- **Local Listeners** — SOCKS5 `127.0.0.1:1080` and HTTP `127.0.0.1:8118` by default
- **Native Control Center** — Status, connect/disconnect, routing editor, logs, proxy test, and notification-area controls
- **Ed25519 Generation** — Generate a dedicated key without overwriting existing key files

### Android

- **LAN Proxy Sharing** — SOCKS5 `:1080` and HTTP `:8080` listen on `0.0.0.0`
- **Proxy Authentication** — Optional username + password (SOCKS5 RFC 1929 / HTTP Basic)
- **SSH Auth** — Password and public key authentication
- **Health Check** — Periodic SSH session monitoring with automatic recovery
- **Traffic Stats** — Real-time upload/download byte counters
- **Foreground Service** — WakeLock keeps the tunnel alive
- **Live Log** — Real-time connection, proxy, and error logs
- **Active Connections** — Current proxy connections grouped by client IP
- **Theme** — Material Design 3, light / dark / system
- **i18n** — Chinese and English
- **Bottom Navigation** — Home / Keys / Settings
- **Auto Save** — Config persisted across restarts
- **Uninstall Cleanup** — All data stored in app-internal directory, auto-deleted on uninstall

## Usage

### Windows Quick Start

1. Download and run [`RedArrow-Windows-Setup-1.2.2-x64.exe`](https://github.com/JoursBleu/red-arrow/releases/download/windows-v1.2.2/RedArrow-Windows-Setup-1.2.2-x64.exe).
2. Open Red Arrow and enter your SSH host, port, user, and private key. `ProxyJump` is optional.
3. Select **Local proxy only**, **Bypass mainland China**, or **Global proxy**, then connect.
4. Applications that honor the Windows system proxy are configured automatically. Other applications can use `127.0.0.1:1080` or `127.0.0.1:8118` manually.

> The installer is currently unsigned, so Windows SmartScreen may display an unknown-publisher warning.

### Android Quick Start

#### 1. Install

Download [`app-debug.apk`](https://github.com/JoursBleu/red-arrow/releases/download/v0.1.0-beta/app-debug.apk) or build from source.

#### 2. Configure SSH Server

Enter host, port, username, and choose password or key authentication.

**Key Authentication Flow:**

1. Go to the **Keys** tab, generate an Ed25519/RSA key pair (or import an existing private key)
2. Back to **Home**, select the stored key
3. Tap **Send Public Key** to append it to the remote `~/.ssh/authorized_keys`
4. Now connect using key authentication

#### 3. Connect

Tap **Connect**. Proxy info will be displayed:

```
SOCKS5  0.0.0.0:1080
HTTP    0.0.0.0:8080
```

Other devices on the LAN can use the phone's IP as the proxy address.

#### 4. Proxy Auth (Optional)

Set username and password in the proxy section to enable SOCKS5/HTTP authentication. Leave blank for open access.

#### 5. Background Running

> **Important**: To keep the tunnel alive long-term, allow background activity for the app:
>
> - **Xiaomi**: Settings → Apps → Manage apps → Red Arrow → Battery saver → No restrictions
> - **Huawei**: Settings → Battery → App launch → Red Arrow → Manage manually → Allow background
> - **OPPO/OnePlus**: Settings → Battery → More battery settings → Optimize battery usage → Red Arrow → Don't optimize
> - **Samsung**: Settings → Battery → Background usage limits → Remove Red Arrow
> - **Stock Android**: Settings → Apps → Red Arrow → Battery → Unrestricted

## Build

### Android

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Windows

Requirements: Rust stable MSVC, Windows OpenSSH Client, and Inno Setup 6.

```powershell
cd windows
.\build.ps1
# Artifacts: windows\dist\
```

## Tech Stack

### Android

- **Language**: Kotlin
- **UI**: Material Design 3 + ViewBinding
- **SSH**: [mwiede/jsch](https://github.com/mwiede/jsch) 0.2.18
- **Async**: Kotlin Coroutines + StateFlow
- **Build**: Gradle 8.7, AGP 8.5.2, compileSdk 35, minSdk 26

### Windows

- **Core**: Rust
- **SSH**: Windows OpenSSH dynamic forwarding
- **UI**: Native WinForms control center
- **System Proxy**: WinINET per-connection API
- **Packaging**: Inno Setup 6

## Architecture

### Windows

```text
WinINET application
	-> HTTP proxy 127.0.0.1:8118
	-> routing decision: DIRECT or SSH
	-> SOCKS5 127.0.0.1:1080
	-> OpenSSH dynamic tunnel
	-> remote SSH server
```

### Android

```
MainActivity (Home)
├── SSH & proxy configuration
├── Connect / disconnect
├── Live log (AppLog → StateFlow)
└── Active connections (ConnectionTracker → StateFlow)

KeysActivity (Keys)
├── Generate Ed25519 / RSA key pairs
├── Import private key (auto-extract public key)
└── Copy / share public key, delete key

SettingsActivity (Settings)
├── Theme toggle (Light / Dark / System)
└── Language toggle (Chinese / English / System)

TunnelService (Foreground Service)
├── SSH connection (JSch) with auto-reconnect
├── Socks5Server (RFC 1929 auth)
├── HttpProxyServer (Basic auth)
├── ConnectionTracker
└── TrafficCounter

KeyStoreManager (Key Storage)
└── SharedPreferences + JSON
```

## Buy Me a Coffee ☕

`0x809EC3201f6bdFb3d428Ca7f0E10F3b55476a1c4` (ETH/ERC-20)

## License

Apache License 2.0
