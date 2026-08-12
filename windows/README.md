# Red Arrow for Windows

Windows edition of [Red Arrow](https://github.com/JoursBleu/red-arrow). It
creates local SOCKS5 and HTTP proxies over an SSH tunnel and can automatically
manage the signed-in user's Windows system proxy.

```text
WinINET application
  -> HTTP proxy 127.0.0.1:8118
  -> routing decision: DIRECT or SSH
  -> SOCKS5 127.0.0.1:1080
  -> OpenSSH dynamic tunnel
  -> remote SSH server
  -> Internet
```

The proxy listeners are loopback-only. No Node.js, Python, or WSL is required;
Windows OpenSSH Client is the only runtime dependency.

## Proxy modes

- **Local proxy only**: do not change Windows system proxy settings. Programs
  can manually use SOCKS5 `127.0.0.1:1080` or HTTP `127.0.0.1:8118`.
- **Bypass mainland China**: enable the Windows system proxy; `.cn` domains,
  private networks, and bundled mainland China IPv4/IPv6 CIDRs connect directly.
  Other traffic uses the SSH tunnel.
- **Global proxy**: enable the Windows system proxy and send all non-local proxy
  traffic through SSH.

Custom direct domains, direct CIDRs, and forced-proxy domains can be edited in
the Red Arrow control center. Domain names are resolved locally in bypass mode so
that non-`.cn` domains hosted on mainland China addresses can still go direct.

System proxy control uses the WinINET per-connection API. Red Arrow saves the
previous user's proxy/PAC state before taking control and restores it when Red
Arrow is stopped, uninstalled, or exits unexpectedly.

> Windows system proxy is not a TUN adapter. Edge, Chrome, and applications that
> honor WinINET/system proxy settings are covered automatically. Applications
> that ignore the system proxy must use the local SOCKS5/HTTP ports manually.

## Install

1. Run `RedArrow-Windows-Setup-1.2.2-x64.exe`.
2. Approve the UAC prompt.
3. Select **Open Red Arrow** when setup completes.
4. Enter the SSH host, user, and authorized private key.
5. Choose a system proxy mode. **Bypass mainland China** is the default.

The application is installed in:

```text
C:\Program Files\Red Arrow
```

Setup creates Red Arrow shortcuts on the desktop and in the Start menu.

Writable configuration and logs stay in the signed-in user's profile:

```text
%LOCALAPPDATA%\RedArrow\config.json
%LOCALAPPDATA%\RedArrow\proxy.log
```

The persistent Red Arrow control center provides connection status, connect and
disconnect commands, mode switching, SSH settings, routing rules, live logs,
proxy testing, and start-at-sign-in. Minimizing it sends it to the notification
area while the proxy continues running.

The SSH command uses `BatchMode=yes`. A passphrase-protected key must already be
loaded into Windows `ssh-agent`; a dedicated unencrypted tunnel key works without
an agent.

`ProxyJump` is optional. Leave it blank for a direct SSH connection, or enter an
OpenSSH jump specification such as `user@jump-host:port`.

The Server tab can generate a dedicated Ed25519 key pair using Windows
`ssh-keygen`. Red Arrow refuses to overwrite existing key files, stores the key
at the location selected by the user, and copies the new public key to the
clipboard for installation in the server's `authorized_keys` file.

The default ports are SOCKS5 `1080` and HTTP `8118`. IANA registers `1080` for
SOCKS and `8118` for HTTP proxy use. Port `1081` is assigned to another service
(`pvuniwien`), so Red Arrow does not use it by default. Both ports remain
editable, and Red Arrow checks for listener conflicts before saving or starting.

## Mainland China rules

The installer includes IPv4 and IPv6 CIDR snapshots from
[`gaoyifan/china-operator-ip`](https://github.com/gaoyifan/china-operator-ip),
distributed under the MIT License. Source and license details are in `rules/`.

## Build on Windows

Prerequisites:

- Rust stable x64 MSVC toolchain
- Inno Setup 6
- Windows OpenSSH Client

Build:

```powershell
.\build.ps1
```

The installer, portable ZIP, standalone executable, and SHA-256 manifest are
written to `dist\`.