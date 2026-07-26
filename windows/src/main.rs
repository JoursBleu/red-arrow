use serde::{Deserialize, Deserializer};
use std::env;
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{IpAddr, Shutdown, SocketAddr, TcpListener, TcpStream, ToSocketAddrs};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const APP_VERSION: &str = env!("CARGO_PKG_VERSION");
const MAX_HEADER_BYTES: usize = 64 * 1024;

#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum SystemProxyMode {
    Off,
    Global,
    #[default]
    BypassCn,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(default)]
struct Config {
    ssh_host: String,
    ssh_port: u16,
    ssh_user: String,
    identity_file: String,
    proxy_jump: Option<String>,
    socks_bind: String,
    socks_port: u16,
    http_bind: String,
    http_port: u16,
    connect_timeout_seconds: u64,
    server_alive_interval_seconds: u64,
    reconnect_delay_seconds: u64,
    log_file: String,
    system_proxy_mode: SystemProxyMode,
    #[serde(deserialize_with = "deserialize_string_list")]
    cn_rules_files: Vec<String>,
    #[serde(deserialize_with = "deserialize_string_list")]
    direct_domains: Vec<String>,
    #[serde(deserialize_with = "deserialize_string_list")]
    direct_cidrs: Vec<String>,
    #[serde(deserialize_with = "deserialize_string_list")]
    force_proxy_domains: Vec<String>,
}

fn deserialize_string_list<'de, D>(deserializer: D) -> Result<Vec<String>, D::Error>
where
    D: Deserializer<'de>,
{
    let value = serde_json::Value::deserialize(deserializer)?;
    match value {
        serde_json::Value::Null => Ok(Vec::new()),
        serde_json::Value::String(value) => Ok(vec![value]),
        serde_json::Value::Array(values) => values
            .into_iter()
            .map(|value| {
                value
                    .as_str()
                    .map(str::to_owned)
                    .ok_or_else(|| serde::de::Error::custom("rule lists must contain only strings"))
            })
            .collect(),
        serde_json::Value::Object(values) if values.is_empty() => Ok(Vec::new()),
        _ => Err(serde::de::Error::custom(
            "rule list must be an array of strings",
        )),
    }
}

impl Default for Config {
    fn default() -> Self {
        Self {
            ssh_host: String::new(),
            ssh_port: 22,
            ssh_user: String::new(),
            identity_file: String::new(),
            proxy_jump: None,
            socks_bind: "127.0.0.1".to_owned(),
            socks_port: 1080,
            http_bind: "127.0.0.1".to_owned(),
            http_port: 8118,
            connect_timeout_seconds: 10,
            server_alive_interval_seconds: 30,
            reconnect_delay_seconds: 3,
            log_file: "%LOCALAPPDATA%\\RedArrow\\proxy.log".to_owned(),
            system_proxy_mode: SystemProxyMode::BypassCn,
            cn_rules_files: vec![
                "rules\\china.txt".to_owned(),
                "rules\\china6.txt".to_owned(),
            ],
            direct_domains: vec![".cn".to_owned()],
            direct_cidrs: Vec::new(),
            force_proxy_domains: Vec::new(),
        }
    }
}

#[derive(Clone, Debug)]
struct RoutingRules {
    mode: SystemProxyMode,
    direct_domains: Vec<String>,
    force_proxy_domains: Vec<String>,
    direct_networks: Vec<ipnet::IpNet>,
}

impl RoutingRules {
    fn from_config(config: &Config) -> Result<Self, String> {
        let mut direct_networks = Vec::new();
        for cidr in &config.direct_cidrs {
            direct_networks.push(
                cidr.parse::<ipnet::IpNet>()
                    .map_err(|error| format!("invalid direct CIDR {cidr:?}: {error}"))?,
            );
        }
        if config.system_proxy_mode == SystemProxyMode::BypassCn {
            for rules_file in &config.cn_rules_files {
                let path = resolve_runtime_path(rules_file)?;
                let content = fs::read_to_string(&path)
                    .map_err(|error| format!("cannot read {}: {error}", path.display()))?;
                for (index, line) in content.lines().enumerate() {
                    let cidr = line.split('#').next().unwrap_or_default().trim();
                    if cidr.is_empty() {
                        continue;
                    }
                    direct_networks.push(cidr.parse::<ipnet::IpNet>().map_err(|error| {
                        format!("invalid CIDR at {}:{}: {error}", path.display(), index + 1)
                    })?);
                }
            }
        }
        Ok(Self {
            mode: config.system_proxy_mode,
            direct_domains: normalize_domain_rules(&config.direct_domains),
            force_proxy_domains: normalize_domain_rules(&config.force_proxy_domains),
            direct_networks,
        })
    }

    fn should_bypass_host(&self, host: &str) -> bool {
        if self.mode != SystemProxyMode::BypassCn {
            return false;
        }
        let normalized = host.trim().trim_end_matches('.').to_ascii_lowercase();
        if domain_matches_any(&normalized, &self.force_proxy_domains) {
            return false;
        }
        if domain_matches_any(&normalized, &self.direct_domains) {
            return true;
        }
        normalized
            .parse::<IpAddr>()
            .is_ok_and(|address| self.should_bypass_ip(address))
    }

    fn should_bypass_ip(&self, address: IpAddr) -> bool {
        is_local_or_private(address)
            || self
                .direct_networks
                .iter()
                .any(|network| network.contains(&address))
    }
}

fn resolve_runtime_path(value: &str) -> Result<PathBuf, String> {
    let path = PathBuf::from(expand_environment(value));
    if path.is_absolute() {
        return Ok(path);
    }
    let executable =
        env::current_exe().map_err(|error| format!("cannot resolve executable path: {error}"))?;
    let directory = executable
        .parent()
        .ok_or_else(|| "executable path has no parent directory".to_owned())?;
    Ok(directory.join(path))
}

fn normalize_domain_rules(rules: &[String]) -> Vec<String> {
    rules
        .iter()
        .map(|rule| {
            rule.trim()
                .trim_start_matches("*.")
                .trim_start_matches('.')
                .trim_end_matches('.')
                .to_ascii_lowercase()
        })
        .filter(|rule| !rule.is_empty())
        .collect()
}

fn domain_matches_any(host: &str, rules: &[String]) -> bool {
    rules
        .iter()
        .any(|rule| host == rule || host.ends_with(&format!(".{rule}")))
}

fn is_local_or_private(address: IpAddr) -> bool {
    match address {
        IpAddr::V4(address) => {
            address.is_private()
                || address.is_loopback()
                || address.is_link_local()
                || address.is_unspecified()
                || address.is_broadcast()
                || address.is_multicast()
        }
        IpAddr::V6(address) => {
            address.is_loopback()
                || address.is_unique_local()
                || address.is_unicast_link_local()
                || address.is_unspecified()
                || address.is_multicast()
        }
    }
}

impl Config {
    fn load(path: &Path) -> Result<Self, String> {
        let content = fs::read_to_string(path)
            .map_err(|error| format!("cannot read {}: {error}", path.display()))?;
        let mut config: Self = serde_json::from_str(content.trim_start_matches('\u{feff}'))
            .map_err(|error| format!("invalid JSON in {}: {error}", path.display()))?;
        config.identity_file = expand_environment(&config.identity_file);
        config.log_file = expand_environment(&config.log_file);
        config.validate()?;
        Ok(config)
    }

    fn validate(&self) -> Result<(), String> {
        if self.ssh_host.trim().is_empty() || self.ssh_user.trim().is_empty() {
            return Err("ssh_host and ssh_user must not be empty".to_owned());
        }
        if self.ssh_port == 0 || self.socks_port == 0 || self.http_port == 0 {
            return Err("ports must be between 1 and 65535".to_owned());
        }
        let socks_bind = self
            .socks_bind
            .parse::<IpAddr>()
            .map_err(|_| "socks_bind must be an IP address".to_owned())?;
        let http_bind = self
            .http_bind
            .parse::<IpAddr>()
            .map_err(|_| "http_bind must be an IP address".to_owned())?;
        if !socks_bind.is_loopback() || !http_bind.is_loopback() {
            return Err("socks_bind and http_bind must be loopback addresses".to_owned());
        }
        if self.socks_bind == self.http_bind && self.socks_port == self.http_port {
            return Err("SOCKS and HTTP listeners cannot use the same address and port".to_owned());
        }
        if !self.identity_file.is_empty() && !Path::new(&self.identity_file).is_file() {
            return Err(format!(
                "SSH identity file does not exist: {}",
                self.identity_file
            ));
        }
        Ok(())
    }

    fn socks_address(&self) -> String {
        format_host_port(&self.socks_bind, self.socks_port)
    }

    fn http_address(&self) -> String {
        format_host_port(&self.http_bind, self.http_port)
    }
}

fn format_host_port(host: &str, port: u16) -> String {
    if host.contains(':') && !host.starts_with('[') {
        format!("[{host}]:{port}")
    } else {
        format!("{host}:{port}")
    }
}

#[derive(Clone)]
struct Logger {
    file: Arc<Mutex<File>>,
}

impl Logger {
    fn new(path: &Path) -> io::Result<Self> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let file = OpenOptions::new().create(true).append(true).open(path)?;
        Ok(Self {
            file: Arc::new(Mutex::new(file)),
        })
    }

    fn log(&self, level: &str, message: impl AsRef<str>) {
        let timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        let line = format!("{timestamp} [{level}] {}\n", message.as_ref());
        if let Ok(mut file) = self.file.lock() {
            let _ = file.write_all(line.as_bytes());
            let _ = file.flush();
        }
        eprint!("{line}");
    }
}

fn expand_environment(input: &str) -> String {
    let mut output = String::with_capacity(input.len());
    let mut remaining = input;
    while let Some(start) = remaining.find('%') {
        output.push_str(&remaining[..start]);
        let after_start = &remaining[start + 1..];
        let Some(end) = after_start.find('%') else {
            output.push_str(&remaining[start..]);
            return output;
        };
        let name = &after_start[..end];
        match env::var(name) {
            Ok(value) => output.push_str(&value),
            Err(_) => output.push_str(&remaining[start..start + end + 2]),
        }
        remaining = &after_start[end + 1..];
    }
    output.push_str(remaining);
    output
}

fn default_config_path() -> PathBuf {
    let base = env::var("LOCALAPPDATA").unwrap_or_else(|_| ".".to_owned());
    Path::new(&base).join("RedArrow").join("config.json")
}

fn parse_arguments() -> Result<(PathBuf, bool), String> {
    let mut arguments = env::args().skip(1);
    let mut config_path = default_config_path();
    let mut check_only = false;
    while let Some(argument) = arguments.next() {
        match argument.as_str() {
            "--config" => {
                let value = arguments
                    .next()
                    .ok_or_else(|| "--config requires a path".to_owned())?;
                config_path = PathBuf::from(value);
            }
            "--check-config" => check_only = true,
            "--version" => {
                println!("Red Arrow for Windows {APP_VERSION}");
                std::process::exit(0);
            }
            "--help" | "-h" => {
                println!(
                    "Red Arrow for Windows {APP_VERSION}\n\n\
                     Usage: RedArrow.exe [--config PATH] [--check-config]\n\n\
                     The app supervises an OpenSSH dynamic tunnel and exposes a local HTTP proxy."
                );
                std::process::exit(0);
            }
            _ => return Err(format!("unknown argument: {argument}")),
        }
    }
    Ok((config_path, check_only))
}

fn spawn_ssh(config: &Config, logger: &Logger) -> io::Result<Child> {
    let mut command = Command::new("ssh.exe");
    command
        .arg("-N")
        .arg("-T")
        .arg("-D")
        .arg(config.socks_address())
        .arg("-p")
        .arg(config.ssh_port.to_string())
        .arg("-o")
        .arg("BatchMode=yes")
        .arg("-o")
        .arg("ExitOnForwardFailure=yes")
        .arg("-o")
        .arg(format!("ConnectTimeout={}", config.connect_timeout_seconds))
        .arg("-o")
        .arg(format!(
            "ServerAliveInterval={}",
            config.server_alive_interval_seconds
        ))
        .arg("-o")
        .arg("ServerAliveCountMax=3")
        .arg("-o")
        .arg("StrictHostKeyChecking=accept-new")
        .arg("-o")
        .arg("IdentitiesOnly=yes");

    if !config.identity_file.is_empty() {
        command.arg("-i").arg(&config.identity_file);
    }
    if let Some(proxy_jump) = config
        .proxy_jump
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        command.arg("-J").arg(proxy_jump);
    }
    command
        .arg(format!("{}@{}", config.ssh_user, config.ssh_host))
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    logger.log(
        "INFO",
        format!(
            "starting SSH tunnel {} -> {}@{}:{}",
            config.socks_address(),
            config.ssh_user,
            config.ssh_host,
            config.ssh_port
        ),
    );
    command.spawn()
}

fn log_pipe<R: Read + Send + 'static>(reader: R, logger: Logger, stream: &'static str) {
    thread::spawn(move || {
        for line in BufReader::new(reader).lines().map_while(Result::ok) {
            logger.log("SSH", format!("{stream}: {line}"));
        }
    });
}

fn supervise_ssh(config: Config, logger: Logger) {
    thread::spawn(move || loop {
        match spawn_ssh(&config, &logger) {
            Ok(mut child) => {
                if let Some(stdout) = child.stdout.take() {
                    log_pipe(stdout, logger.clone(), "stdout");
                }
                if let Some(stderr) = child.stderr.take() {
                    log_pipe(stderr, logger.clone(), "stderr");
                }
                match child.wait() {
                    Ok(status) => logger.log("WARN", format!("SSH exited with {status}")),
                    Err(error) => logger.log("ERROR", format!("cannot wait for SSH: {error}")),
                }
            }
            Err(error) => logger.log("ERROR", format!("cannot start ssh.exe: {error}")),
        }
        thread::sleep(Duration::from_secs(config.reconnect_delay_seconds.max(1)));
    });
}

fn read_http_header(stream: &mut TcpStream) -> io::Result<(Vec<u8>, Vec<u8>)> {
    let mut buffer = Vec::with_capacity(4096);
    let mut chunk = [0_u8; 2048];
    loop {
        let count = stream.read(&mut chunk)?;
        if count == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "client closed before sending an HTTP header",
            ));
        }
        buffer.extend_from_slice(&chunk[..count]);
        if let Some(index) = buffer.windows(4).position(|window| window == b"\r\n\r\n") {
            let body = buffer.split_off(index + 4);
            return Ok((buffer, body));
        }
        if buffer.len() > MAX_HEADER_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "HTTP header exceeds 64 KiB",
            ));
        }
    }
}

fn parse_authority(authority: &str, default_port: u16) -> Result<(String, u16), String> {
    let authority = authority.trim();
    if authority.is_empty() {
        return Err("empty target authority".to_owned());
    }
    if let Some(rest) = authority.strip_prefix('[') {
        let end = rest
            .find(']')
            .ok_or_else(|| "invalid bracketed IPv6 address".to_owned())?;
        let host = rest[..end].to_owned();
        let suffix = &rest[end + 1..];
        let port = if suffix.is_empty() {
            default_port
        } else {
            suffix
                .strip_prefix(':')
                .ok_or_else(|| "invalid IPv6 authority".to_owned())?
                .parse::<u16>()
                .map_err(|_| "invalid target port".to_owned())?
        };
        return Ok((host, port));
    }
    if authority.matches(':').count() == 1 {
        let (host, port) = authority.rsplit_once(':').unwrap();
        let port = port
            .parse::<u16>()
            .map_err(|_| "invalid target port".to_owned())?;
        if host.is_empty() {
            return Err("empty target host".to_owned());
        }
        return Ok((host.to_owned(), port));
    }
    Ok((authority.to_owned(), default_port))
}

struct HttpRequest {
    method: String,
    host: String,
    port: u16,
    rewritten_header: Vec<u8>,
}

fn parse_http_request(header: &[u8]) -> Result<HttpRequest, String> {
    let text = std::str::from_utf8(header).map_err(|_| "HTTP header is not UTF-8".to_owned())?;
    let mut lines = text.split("\r\n");
    let request_line = lines
        .next()
        .ok_or_else(|| "missing request line".to_owned())?;
    let mut parts = request_line.split_whitespace();
    let method = parts
        .next()
        .ok_or_else(|| "missing HTTP method".to_owned())?;
    let target = parts
        .next()
        .ok_or_else(|| "missing HTTP target".to_owned())?;
    let version = parts
        .next()
        .ok_or_else(|| "missing HTTP version".to_owned())?;
    if parts.next().is_some() || !version.starts_with("HTTP/") {
        return Err("invalid HTTP request line".to_owned());
    }

    if method.eq_ignore_ascii_case("CONNECT") {
        let (host, port) = parse_authority(target, 443)?;
        return Ok(HttpRequest {
            method: method.to_owned(),
            host,
            port,
            rewritten_header: Vec::new(),
        });
    }

    let mut host_header = None;
    let mut kept_headers = Vec::new();
    for line in lines {
        if line.is_empty() {
            continue;
        }
        let Some((name, value)) = line.split_once(':') else {
            return Err("invalid HTTP header line".to_owned());
        };
        if name.eq_ignore_ascii_case("host") {
            host_header = Some(value.trim().to_owned());
        }
        if !name.eq_ignore_ascii_case("proxy-connection")
            && !name.eq_ignore_ascii_case("proxy-authorization")
        {
            kept_headers.push(line);
        }
    }

    let (authority, path) = if let Some(rest) = target.strip_prefix("http://") {
        match rest.find('/') {
            Some(index) => (&rest[..index], &rest[index..]),
            None => (rest, "/"),
        }
    } else if target.starts_with('/') || target == "*" {
        (
            host_header
                .as_deref()
                .ok_or_else(|| "origin-form request is missing Host".to_owned())?,
            target,
        )
    } else {
        return Err("only HTTP absolute-form, origin-form, and CONNECT are supported".to_owned());
    };
    let (host, port) = parse_authority(authority, 80)?;
    let mut rewritten = format!("{method} {path} {version}\r\n");
    for line in kept_headers {
        rewritten.push_str(line);
        rewritten.push_str("\r\n");
    }
    rewritten.push_str("\r\n");
    Ok(HttpRequest {
        method: method.to_owned(),
        host,
        port,
        rewritten_header: rewritten.into_bytes(),
    })
}

fn read_exact_array<const N: usize>(stream: &mut TcpStream) -> io::Result<[u8; N]> {
    let mut bytes = [0_u8; N];
    stream.read_exact(&mut bytes)?;
    Ok(bytes)
}

fn connect_through_socks(config: &Config, host: &str, port: u16) -> io::Result<TcpStream> {
    let mut stream = TcpStream::connect(config.socks_address())?;
    stream.set_read_timeout(Some(Duration::from_secs(config.connect_timeout_seconds)))?;
    stream.set_write_timeout(Some(Duration::from_secs(config.connect_timeout_seconds)))?;
    stream.write_all(&[5, 1, 0])?;
    if read_exact_array::<2>(&mut stream)? != [5, 0] {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "SOCKS server rejected no-authentication mode",
        ));
    }

    let mut request = vec![5, 1, 0];
    match host.parse::<IpAddr>() {
        Ok(IpAddr::V4(address)) => {
            request.push(1);
            request.extend_from_slice(&address.octets());
        }
        Ok(IpAddr::V6(address)) => {
            request.push(4);
            request.extend_from_slice(&address.octets());
        }
        Err(_) => {
            if host.len() > u8::MAX as usize {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "target hostname exceeds 255 bytes",
                ));
            }
            request.push(3);
            request.push(host.len() as u8);
            request.extend_from_slice(host.as_bytes());
        }
    }
    request.extend_from_slice(&port.to_be_bytes());
    stream.write_all(&request)?;

    let response = read_exact_array::<4>(&mut stream)?;
    if response[0] != 5 || response[1] != 0 {
        return Err(io::Error::new(
            io::ErrorKind::ConnectionRefused,
            format!("SOCKS connect failed with reply code {}", response[1]),
        ));
    }
    let address_length = match response[3] {
        1 => 4,
        4 => 16,
        3 => read_exact_array::<1>(&mut stream)?[0] as usize,
        value => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("SOCKS server returned invalid address type {value}"),
            ))
        }
    };
    let mut ignored = vec![0_u8; address_length + 2];
    stream.read_exact(&mut ignored)?;
    stream.set_read_timeout(None)?;
    stream.set_write_timeout(None)?;
    Ok(stream)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum RouteKind {
    Direct,
    Proxy,
}

fn resolve_target(host: &str, port: u16) -> io::Result<Vec<SocketAddr>> {
    (host, port)
        .to_socket_addrs()
        .map(|addresses| addresses.collect())
}

fn connect_direct_addresses(
    addresses: impl IntoIterator<Item = SocketAddr>,
    timeout: Duration,
) -> io::Result<TcpStream> {
    let mut last_error = None;
    for address in addresses {
        match TcpStream::connect_timeout(&address, timeout) {
            Ok(stream) => return Ok(stream),
            Err(error) => last_error = Some(error),
        }
    }
    Err(last_error.unwrap_or_else(|| {
        io::Error::new(io::ErrorKind::NotFound, "target resolved to no addresses")
    }))
}

fn connect_target(
    config: &Config,
    rules: &RoutingRules,
    host: &str,
    port: u16,
) -> io::Result<(TcpStream, RouteKind)> {
    if rules.mode != SystemProxyMode::BypassCn {
        return connect_through_socks(config, host, port).map(|stream| (stream, RouteKind::Proxy));
    }

    let normalized = host.trim().trim_end_matches('.').to_ascii_lowercase();
    if domain_matches_any(&normalized, &rules.force_proxy_domains) {
        return connect_through_socks(config, host, port).map(|stream| (stream, RouteKind::Proxy));
    }

    let timeout = Duration::from_secs(config.connect_timeout_seconds);
    if rules.should_bypass_host(&normalized) {
        let addresses = if let Ok(address) = normalized.parse::<IpAddr>() {
            vec![SocketAddr::new(address, port)]
        } else {
            resolve_target(host, port)?
        };
        return connect_direct_addresses(addresses, timeout)
            .map(|stream| (stream, RouteKind::Direct));
    }

    if normalized.parse::<IpAddr>().is_ok() {
        return connect_through_socks(config, host, port).map(|stream| (stream, RouteKind::Proxy));
    }

    if let Ok(addresses) = resolve_target(host, port) {
        let direct_addresses: Vec<_> = addresses
            .into_iter()
            .filter(|address| rules.should_bypass_ip(address.ip()))
            .collect();
        if !direct_addresses.is_empty() {
            return connect_direct_addresses(direct_addresses, timeout)
                .map(|stream| (stream, RouteKind::Direct));
        }
    }

    connect_through_socks(config, host, port).map(|stream| (stream, RouteKind::Proxy))
}

fn write_error(stream: &mut TcpStream, status: &str, detail: &str) {
    let safe_detail = detail.replace(['\r', '\n'], " ");
    let body = format!("{status}: {safe_detail}\n");
    let response = format!(
        "HTTP/1.1 {status}\r\nContent-Type: text/plain; charset=utf-8\r\n\
         Content-Length: {}\r\nConnection: close\r\n\r\n{}",
        body.len(),
        body
    );
    let _ = stream.write_all(response.as_bytes());
}

fn relay(mut client: TcpStream, mut remote: TcpStream) -> io::Result<()> {
    let mut client_reader = client.try_clone()?;
    let mut remote_writer = remote.try_clone()?;
    let upstream = thread::spawn(move || {
        let result = io::copy(&mut client_reader, &mut remote_writer);
        let _ = remote_writer.shutdown(Shutdown::Write);
        result
    });
    let downstream = io::copy(&mut remote, &mut client);
    let _ = client.shutdown(Shutdown::Write);
    let upstream = upstream
        .join()
        .map_err(|_| io::Error::other("relay thread panicked"))?;
    upstream?;
    downstream?;
    Ok(())
}

fn handle_client(
    mut client: TcpStream,
    config: Config,
    rules: RoutingRules,
    logger: Logger,
) -> io::Result<()> {
    client.set_read_timeout(Some(Duration::from_secs(30)))?;
    let (header, body) = match read_http_header(&mut client) {
        Ok(value) => value,
        Err(error) => {
            write_error(&mut client, "400 Bad Request", &error.to_string());
            return Err(error);
        }
    };
    let request = match parse_http_request(&header) {
        Ok(value) => value,
        Err(error) => {
            write_error(&mut client, "400 Bad Request", &error);
            return Err(io::Error::new(io::ErrorKind::InvalidData, error));
        }
    };
    let (mut remote, route) = match connect_target(&config, &rules, &request.host, request.port) {
        Ok(result) => result,
        Err(error) => {
            write_error(&mut client, "502 Bad Gateway", &error.to_string());
            return Err(error);
        }
    };
    logger.log(
        "INFO",
        format!(
            "{} {}:{} route={route:?}",
            request.method, request.host, request.port
        ),
    );

    client.set_read_timeout(None)?;
    if request.method.eq_ignore_ascii_case("CONNECT") {
        client
            .write_all(b"HTTP/1.1 200 Connection Established\r\nProxy-Agent: RedArrow\r\n\r\n")?;
    } else {
        remote.write_all(&request.rewritten_header)?;
        remote.write_all(&body)?;
    }
    relay(client, remote)
}

fn run(config: Config, logger: Logger) -> io::Result<()> {
    let rules = RoutingRules::from_config(&config)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let listener = TcpListener::bind(config.http_address())?;
    logger.log(
        "INFO",
        format!(
            "Red Arrow {APP_VERSION} listening for HTTP proxy traffic on {}",
            config.http_address()
        ),
    );
    supervise_ssh(config.clone(), logger.clone());
    for connection in listener.incoming() {
        match connection {
            Ok(stream) => {
                let config = config.clone();
                let rules = rules.clone();
                let logger = logger.clone();
                thread::spawn(move || {
                    if let Err(error) = handle_client(stream, config, rules, logger.clone()) {
                        logger.log("WARN", format!("proxy request failed: {error}"));
                    }
                });
            }
            Err(error) => logger.log("WARN", format!("HTTP accept failed: {error}")),
        }
    }
    Ok(())
}

fn real_main() -> Result<(), String> {
    let (config_path, check_only) = parse_arguments()?;
    let config = Config::load(&config_path)?;
    if check_only {
        RoutingRules::from_config(&config)?;
        println!("Configuration OK: {}", config_path.display());
        return Ok(());
    }
    let logger = Logger::new(Path::new(&config.log_file))
        .map_err(|error| format!("cannot open log file {}: {error}", config.log_file))?;
    logger.log(
        "INFO",
        format!("loaded configuration from {}", config_path.display()),
    );
    run(config, logger).map_err(|error| format!("proxy stopped: {error}"))
}

fn main() {
    if let Err(error) = real_main() {
        eprintln!("Red Arrow: {error}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_authorities() {
        assert_eq!(
            parse_authority("example.com:8443", 443).unwrap(),
            ("example.com".to_owned(), 8443)
        );
        assert_eq!(
            parse_authority("example.com", 443).unwrap(),
            ("example.com".to_owned(), 443)
        );
        assert_eq!(
            parse_authority("[2001:db8::1]:443", 80).unwrap(),
            ("2001:db8::1".to_owned(), 443)
        );
    }

    #[test]
    fn formats_ipv4_and_ipv6_listeners() {
        assert_eq!(format_host_port("127.0.0.1", 1080), "127.0.0.1:1080");
        assert_eq!(format_host_port("::1", 1080), "[::1]:1080");
    }

    #[test]
    fn accepts_utf8_bom_configuration() {
        let json = "\u{feff}{\"ssh_host\":\"example.com\"}";
        let config: Config = serde_json::from_str(json.trim_start_matches('\u{feff}')).unwrap();
        assert_eq!(config.ssh_host, "example.com");
    }

    #[test]
    fn accepts_legacy_powershell_rule_shapes() {
        let json = r#"{
            "ssh_host": "example.com",
            "ssh_user": "user",
            "direct_domains": ".cn",
            "direct_cidrs": {},
            "force_proxy_domains": {}
        }"#;
        let config: Config = serde_json::from_str(json).unwrap();
        assert_eq!(config.direct_domains, vec![".cn"]);
        assert!(config.direct_cidrs.is_empty());
        assert!(config.force_proxy_domains.is_empty());
    }

    #[test]
    fn parses_connect_request() {
        let request = parse_http_request(
            b"CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n",
        )
        .unwrap();
        assert_eq!(request.method, "CONNECT");
        assert_eq!(request.host, "example.com");
        assert_eq!(request.port, 443);
        assert!(request.rewritten_header.is_empty());
    }

    #[test]
    fn rewrites_absolute_http_request() {
        let request = parse_http_request(
            b"GET http://example.com/a?q=1 HTTP/1.1\r\nHost: example.com\r\nProxy-Connection: keep-alive\r\nUser-Agent: test\r\n\r\n",
        )
        .unwrap();
        assert_eq!(request.host, "example.com");
        assert_eq!(request.port, 80);
        let rewritten = String::from_utf8(request.rewritten_header).unwrap();
        assert!(rewritten.starts_with("GET /a?q=1 HTTP/1.1\r\n"));
        assert!(!rewritten.to_ascii_lowercase().contains("proxy-connection"));
        assert!(rewritten.contains("User-Agent: test\r\n"));
    }

    #[test]
    fn bypass_cn_mode_matches_domains_and_cidrs() {
        let config = Config {
            system_proxy_mode: SystemProxyMode::BypassCn,
            cn_rules_files: Vec::new(),
            direct_domains: vec![".cn".to_owned(), "example.internal".to_owned()],
            direct_cidrs: vec!["1.0.1.0/24".to_owned(), "240e::/16".to_owned()],
            force_proxy_domains: vec!["proxy.cn".to_owned()],
            ..Config::default()
        };
        let rules = RoutingRules::from_config(&config).unwrap();
        assert!(rules.should_bypass_host("www.gov.cn"));
        assert!(rules.should_bypass_host("EXAMPLE.INTERNAL."));
        assert!(!rules.should_bypass_host("proxy.cn"));
        assert!(rules.should_bypass_host("1.0.1.8"));
        assert!(rules.should_bypass_host("240e::1"));
        assert!(rules.should_bypass_host("192.168.1.1"));
        assert!(!rules.should_bypass_host("8.8.8.8"));
    }

    #[test]
    fn global_and_off_modes_do_not_apply_cn_rules() {
        for mode in [SystemProxyMode::Global, SystemProxyMode::Off] {
            let config = Config {
                system_proxy_mode: mode,
                cn_rules_files: Vec::new(),
                direct_domains: vec![".cn".to_owned()],
                direct_cidrs: vec!["1.0.1.0/24".to_owned()],
                ..Config::default()
            };
            let rules = RoutingRules::from_config(&config).unwrap();
            assert!(!rules.should_bypass_host("www.gov.cn"));
            assert!(!rules.should_bypass_host("1.0.1.8"));
        }
    }
}
