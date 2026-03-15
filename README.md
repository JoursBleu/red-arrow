# Red Arrow

Android SSH 隧道 + SOCKS5/HTTP 代理应用。通过 SSH 连接到远程服务器，在本地创建加密代理，所有流量经由 SSH 隧道转发。

Android SSH tunnel + SOCKS5/HTTP proxy app. Connects to a remote SSH server and creates local encrypted proxies, forwarding all traffic through the SSH tunnel.

## 功能 / Features

- **SSH 隧道 / SSH Tunnel**: 连接远程 SSH 服务器，建立加密隧道穿透 NAT / Connect to remote SSH server, establish encrypted tunnel through NAT
- **SOCKS5 代理 / SOCKS5 Proxy**: 本地 SOCKS5 代理 (默认 :1080)，监听 0.0.0.0 / Local SOCKS5 proxy (default :1080), listens on 0.0.0.0
- **HTTP 代理 / HTTP Proxy**: 本地 HTTP 代理 (默认 :8080)，监听 0.0.0.0 / Local HTTP proxy (default :8080), listens on 0.0.0.0
- **代理鉴权 / Proxy Auth**: 可选的代理用户名 + 密码认证 (SOCKS5 RFC 1929 / HTTP Basic) / Optional username + password auth
- **密码 / 密钥认证 / Auth Methods**: 支持密码和密钥两种 SSH 认证方式 / Password and public key SSH authentication
- **密钥管理 / Key Management**: 生成 Ed25519/RSA 密钥对，导入私钥，发送公钥到服务器 / Generate Ed25519/RSA key pairs, import private keys, send public key to server (ssh-copy-id)
- **前台服务 / Foreground Service**: WakeLock 保活，隧道长时间稳定运行 / WakeLock keeps tunnel alive
- **实时日志 / Live Log**: 连接/代理/错误日志实时显示 / Real-time connection, proxy, and error logs
- **活跃连接 / Active Connections**: 按 IP 分组显示当前代理连接数 / Proxy connections grouped by IP
- **日夜主题 / Theme**: Material Design 3，支持浅色/深色/跟随系统 / Light, dark, and system themes
- **中英双语 / i18n**: 支持中文和英文界面 / Chinese and English UI
- **底部导航 / Navigation**: 首页 / 密钥 / 设置 三页切换 / Home, Keys, Settings tabs
- **自动保存 / Auto Save**: 重启 App 无需重新填写 / Config persisted across restarts
- **卸载清理 / Uninstall Cleanup**: 密钥和配置存储在应用内部目录，卸载时自动删除 / All data in app-internal storage, auto-deleted on uninstall

## 使用 / Usage

### 1. 下载安装 / Install

从 Release 下载 APK 或自行构建。

Download APK from Release or build from source.

### 2. 配置 SSH 服务器 / Configure SSH Server

填写主机地址、端口、用户名，选择密码或密钥认证。

Enter host, port, username, and choose password or key authentication.

**密钥认证流程 / Key Authentication Flow:**

1. 进入「密钥」页面，生成 Ed25519/RSA 密钥对（或导入已有私钥） / Go to "Keys" tab, generate Ed25519/RSA key pair (or import existing private key)
2. 回到首页，选择已存储的密钥 / Back to Home, select stored key
3. 点击「发送公钥到服务器」将公钥追加到远程 `~/.ssh/authorized_keys` / Tap "Send Public Key" to append to remote `~/.ssh/authorized_keys`
4. 之后即可使用密钥认证连接 / Now connect using key authentication

### 3. 连接 / Connect

点击「连接」，代理信息会显示在界面上：

Tap "Connect", proxy info will be displayed:

```
SOCKS5  0.0.0.0:1080
HTTP    0.0.0.0:8080
```

局域网内其他设备可直接使用手机 IP 作为代理地址。

Other devices on the LAN can use the phone's IP as proxy address.

### 4. 代理鉴权（可选）/ Proxy Auth (Optional)

在代理端口区域填写用户名和密码，即可为 SOCKS5/HTTP 代理启用认证。留空则不鉴权。

Set username and password in the proxy section to enable SOCKS5/HTTP auth. Leave blank for no auth.

### 5. 后台运行 / Background Running

> **重要 / Important**：为确保隧道长时间稳定运行，需要手动设置允许 App 后台活动：
> To keep the tunnel alive, manually allow background activity:
>
> - **小米/Xiaomi**: 设置 → 应用设置 → 应用管理 → Red Arrow → 省电策略 → 无限制
> - **华为/Huawei**: 设置 → 电池 → 启动管理 → Red Arrow → 手动管理 → 允许后台活动
> - **OPPO/OnePlus**: 设置 → 电池 → 更多电池设置 → 优化电池使用 → Red Arrow → 不优化
> - **vivo**: 设置 → 电池 → 后台高耗电 → 允许 Red Arrow
> - **Samsung**: Settings → Battery → Background usage limits → Remove Red Arrow
> - **Stock Android**: Settings → Apps → Red Arrow → Battery → Unrestricted

## 构建 / Build

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## 技术栈 / Tech Stack

- **语言 / Language**: Kotlin
- **UI**: Material Design 3 + ViewBinding
- **SSH**: [mwiede/jsch](https://github.com/mwiede/jsch) 0.2.18
- **异步 / Async**: Kotlin Coroutines + StateFlow
- **构建 / Build**: Gradle 8.7, AGP 8.5.2, compileSdk 35, minSdk 26

## 架构 / Architecture

```
MainActivity (首页 / Home)
├── SSH 配置 + 代理配置 / SSH & proxy config
├── 连接/断开控制 / Connect/disconnect
├── 实时日志 / Live log (AppLog → StateFlow)
└── 活跃连接 / Active connections (ConnectionTracker → StateFlow)

KeysActivity (密钥 / Keys)
├── 生成 Ed25519 / RSA 密钥对 / Generate key pairs
├── 导入私钥文件 (自动提取公钥) / Import private key (auto-extract public key)
└── 复制/分享公钥, 删除密钥 / Copy/share public key, delete key

SettingsActivity (设置 / Settings)
├── 主题切换 / Theme toggle (Light/Dark/System)
└── 语言切换 / Language toggle (中文/English/System)

TunnelService (前台服务 / Foreground Service)
├── SSH 连接 / SSH connection (JSch)
├── SOCKS5Server (用户名密码鉴权 / username+password auth)
├── HttpProxyServer (Basic 鉴权 / Basic auth)
└── ConnectionTracker (活跃连接追踪 / active connection tracking)

KeyStoreManager (密钥存储 / Key Storage)
└── SharedPreferences + JSON (私钥/公钥/密码 持久化 / private key, public key, passphrase persistence)
```

## Buy Me a Coffee ☕

`0x809EC3201f6bdFb3d428Ca7f0E10F3b55476a1c4` (ETH/ERC-20)

## License

CC BY-NC-SA 4.0 — 非商业使用，商业授权请联系作者 / Non-commercial use, contact author for commercial license
