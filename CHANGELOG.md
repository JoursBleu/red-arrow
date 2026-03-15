# Changelog

All notable changes to Red Arrow will be documented in this file.

## [Unreleased]

### Added
- **Auto-reconnect**: Automatic reconnection on SSH disconnect with exponential backoff (max 10 attempts)
- **SSH health check**: Periodic session health monitoring (every 10s) with automatic recovery
- **Live uptime counter**: Real-time uptime display updates every second
- **Traffic statistics**: Real-time upload/download byte counters displayed in header
- **GitHub Actions CI**: Automated build and artifact upload on push/PR

### Improved
- **ProGuard rules**: Refined from blanket keep to targeted rules, release builds strip verbose logs
- **Service stability**: Better lifecycle management with proper cleanup on reconnect/disconnect

## [0.1.0] - 2024

### Added
- SSH tunnel with SOCKS5 and HTTP proxy
- Password and public key authentication
- Ed25519 and RSA key generation
- Key management (generate, import, delete, copy/share public key)
- Send public key to server (ssh-copy-id equivalent)
- Proxy username/password authentication (SOCKS5 RFC 1929 + HTTP Basic)
- Listen on 0.0.0.0 for LAN access
- Active connection display grouped by client IP
- Day/Night theme support
- Chinese/English i18n
- Bottom navigation (Home / Keys / Settings)
- Foreground service with notification
- WakeLock for persistent connection
- Application log viewer
