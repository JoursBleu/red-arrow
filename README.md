# Red Arrow

Android SSH 隧道 + SOCKS5/HTTP 代理应用。通过 SSH 连接到远程服务器，在本地创建加密代理，所有流量经由 SSH 隧道转发。

## 功能

- **SSH 隧道**: 连接远程 SSH 服务器，建立加密隧道穿透 NAT
- **SOCKS5 代理**: 本地 SOCKS5 代理 (默认 :1080)，监听 0.0.0.0
- **HTTP 代理**: 本地 HTTP 代理 (默认 :8080)，监听 0.0.0.0
- **代理鉴权**: 可选的代理用户名 + 密码认证 (SOCKS5 RFC 1929 / HTTP Basic)
- **密码 / 密钥认证**: 支持两种 SSH 认证方式
- **密钥管理**: 生成 Ed25519/RSA 密钥对，导入私钥，发送公钥到服务器 (ssh-copy-id)
- **前台服务**: WakeLock 保活，隧道长时间稳定运行
- **实时日志**: 连接/代理/错误日志实时显示
- **活跃连接**: 按 IP 分组显示当前代理连接数
- **日夜主题**: Material Design 3，支持浅色/深色/跟随系统
- **中英双语**: 支持中文和英文界面
- **底部导航**: 首页 / 密钥 / 设置 三页切换
- **自动保存配置**: 重启 App 无需重新填写
- **卸载清理**: 密钥和配置存储在应用内部目录，卸载时自动删除

## 截图

```
┌─────────────────────────┐
│  Red Arrow               │
│  ● 已连接 192.168.1.1    │
│                          │
│  ┌─ SSH 服务器 ─────────┐│
│  │ 主机: xxx  端口: 22   ││
│  │ 用户名: root          ││
│  │ [密码] [密钥]         ││
│  │ [选择密钥] my_key     ││
│  │ [发送公钥到服务器]    ││
│  └───────────────────────┘│
│  ┌─ 代理端口 ───────────┐│
│  │ SOCKS5: 1080          ││
│  │ HTTP:   8080          ││
│  └───────────────────────┘│
│  [========= 断开 =========]│
│                          │
│  ┌─ 活跃连接 ───────────┐│
│  │ 192.168.1.5 — 3 conn  ││
│  │ 10.0.0.1 — 1 conn     ││
│  └───────────────────────┘│
│                          │
│  [首页]  [密钥]  [设置]   │
└──────────────────────────┘
```

## 使用

### 1. 下载安装

从 Release 下载 APK 或自行构建。

### 2. 配置 SSH 服务器

填写主机地址、端口、用户名，选择密码或密钥认证。

密钥认证流程：
1. 进入「密钥」页面，生成 Ed25519/RSA 密钥对（或导入已有私钥）
2. 回到首页，选择已存储的密钥
3. 点击「发送公钥到服务器」将公钥追加到远程 `~/.ssh/authorized_keys`
4. 之后即可使用密钥认证连接

### 3. 连接

点击「连接」，代理信息会显示在界面上：

```
SOCKS5  0.0.0.0:1080
HTTP    0.0.0.0:8080
```

局域网内其他设备可直接使用手机 IP 作为代理地址。

### 4. 代理鉴权（可选）

在代理端口区域填写用户名和密码，即可为 SOCKS5/HTTP 代理启用认证。留空则不鉴权。

### 5. 后台运行

> **重要**：为确保隧道长时间稳定运行，需要手动设置允许 App 后台活动：
>
> - **小米/红米**: 设置 → 应用设置 → 应用管理 → Red Arrow → 省电策略 → 无限制
> - **华为/荣耀**: 设置 → 电池 → 启动管理 → Red Arrow → 手动管理 → 允许后台活动
> - **OPPO/一加**: 设置 → 电池 → 更多电池设置 → 优化电池使用 → Red Arrow → 不优化
> - **vivo**: 设置 → 电池 → 后台高耗电 → 允许 Red Arrow
> - **三星**: 设置 → 电池 → 后台使用限制 → 将 Red Arrow 从限制列表移除
> - **原生 Android**: 设置 → 应用 → Red Arrow → 电池 → 不受限制

## 构建

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## 技术栈

- **语言**: Kotlin
- **UI**: Material Design 3 + ViewBinding
- **SSH**: [mwiede/jsch](https://github.com/mwiede/jsch) 0.2.18
- **异步**: Kotlin Coroutines + StateFlow
- **构建**: Gradle 8.7, AGP 8.5.2, compileSdk 35, minSdk 26

## 架构

```
MainActivity (首页)
├── SSH 配置 + 代理配置
├── 连接/断开控制
├── 实时日志 (AppLog → StateFlow)
└── 活跃连接 (ConnectionTracker → StateFlow)

KeysActivity (密钥)
├── 生成 Ed25519 / RSA 密钥对
├── 导入私钥文件 (自动提取公钥)
└── 复制/分享公钥, 删除密钥

SettingsActivity (设置)
├── 主题切换 (浅色/深色/系统)
└── 语言切换 (中文/English/系统)

TunnelService (前台服务)
├── SSH 连接 (JSch)
├── SOCKS5Server (支持用户名密码鉴权)
├── HttpProxyServer (支持 Basic 鉴权)
└── ConnectionTracker (活跃连接追踪)

KeyStoreManager
└── SharedPreferences + JSON (私钥/公钥/密码 持久化)
```

## License

CC BY-NC-SA 4.0 — 非商业使用，商业授权请联系作者
