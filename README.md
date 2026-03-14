# Red Arrow

Android SSH 反向隧道应用 — 通过手机 SSH 连接到公网服务器，建立反向端口转发，将内网服务（如本地 LLM Serving）暴露给外部访问。

## 场景

在本地 GPU 机器上部署了 vLLM / Ollama / llama.cpp 等 LLM 推理服务，但机器在 NAT 内网中没有公网 IP。通过 Red Arrow：

1. 手机连接公网 SSH 服务器，建立 SSH 隧道
2. 内网 LLM 服务端口通过隧道映射到公网服务器
3. 外部用户通过公网服务器地址即可调用 LLM API

```
                        ┌───────────────────┐
                        │   公网 SSH 服务器   │
                        │  (有公网 IP)        │
                        │                   │
                        │  :8000 ← 隧道映射  │
                        └────────┬──────────┘
                                 │ SSH 隧道
                                 │
┌──────────────┐      ┌──────────┴──────────┐
│ 内网 GPU 机器 │      │   Android 手机       │
│              │      │   Red Arrow App     │
│ vLLM :8000   │←LAN→│                     │
│ Ollama :11434│      │  SSH + 端口转发      │
└──────────────┘      └─────────────────────┘
                                 ↕
                        外部用户通过公网 IP
                        访问 LLM API
```

## 功能

- **SSH 隧道**: 连接远程 SSH 服务器，建立加密隧道穿透 NAT
- **反向端口转发**: 将内网服务端口映射到公网（类似 `ssh -R`）
- **SOCKS5 代理**: 本地 SOCKS5 代理 (默认 :1080)
- **HTTP/HTTPS 代理**: 自动将流量通过 SSH 隧道转发 (默认 :8080)
- **密码 / 密钥认证**: 支持两种 SSH 认证方式
- **前台服务**: WakeLock 保活，隧道长时间稳定运行
- **自动保存配置**: 重启 App 无需重新填写

## 典型用法：暴露本地 LLM 服务

### 1. 本地启动 LLM 服务

```bash
# vLLM
vllm serve Qwen/Qwen2-7B --host 0.0.0.0 --port 8000

# 或 Ollama
ollama serve  # 默认 :11434
```

### 2. 手机端配置 Red Arrow

| 字段 | 值 |
|------|-----|
| SSH 主机 | 你的公网服务器 IP |
| SSH 端口 | 22 |
| 用户名 | your_user |
| 认证方式 | 密码 或 密钥 |
| SOCKS5 端口 | 1080 |
| HTTP 端口 | 8080 |

点击「连接」建立隧道。

### 3. 在公网服务器上设置端口转发

在公网服务器的 `sshd_config` 中启用：

```
GatewayPorts yes
```

这样外部用户就可以通过 `http://公网IP:8000/v1/chat/completions` 调用你的 LLM API。

## 构建

```bash
# 需要 Android SDK
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug

# APK 输出路径
# app/build/outputs/apk/debug/app-debug.apk
```

## 技术架构

```
┌─────────────────────────────────────────┐
│              Red Arrow App              │
│                                         │
│  ┌───────────┐                          │
│  │ MainActivity │  ← Material Design 3  │
│  └──────┬────┘                          │
│         │                               │
│  ┌──────┴──────┐                        │
│  │ TunnelService│  ← 前台 Service       │
│  └──────┬──────┘                        │
│         │                               │
│  ┌──────┴──────┐                        │
│  │  SshManager  │  ← JSch SSH 连接      │
│  └──┬───────┬──┘                        │
│     │       │                           │
│  ┌──┴───┐ ┌─┴──────────┐               │
│  │SOCKS5│ │ HTTP Proxy  │               │
│  │:1080 │ │ :8080       │               │
│  └──────┘ └─────────────┘               │
└─────────────────────────────────────────┘
```

## 依赖

- [mwiede/jsch](https://github.com/mwiede/jsch) - SSH 连接库 (JSch 活跃维护分支)
- AndroidX / Material Design 3
- Kotlin Coroutines

## License

CC BY-NC-SA 4.0 — 非商业使用，商业授权请联系作者
