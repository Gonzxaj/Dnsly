//! Active DNS health probe: sends a real query and validates the answer.
//! Blocking, std-only. Callers run it on a background thread.

use std::collections::hash_map::RandomState;
use std::collections::HashMap;
use std::hash::{BuildHasher, Hasher};
use std::io::{self, ErrorKind, Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs, UdpSocket};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use crate::dns::{self, DnsError};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Protocol {
    Udp,
    Tcp,
}

#[derive(Debug, Clone)]
pub struct ProbeConfig {
    /// IP literal or hostname.
    pub server: String,
    pub port: u16,
    pub protocol: Protocol,
    pub test_domain: String,
    /// Total budget for connect + send + receive.
    pub timeout: Duration,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProbeOk {
    pub rtt: Duration,
    pub rcode: u8,
}

#[derive(Debug)]
pub enum ProbeError {
    /// Hostname did not resolve to any address.
    Resolve,
    Timeout,
    /// Socket error, e.g. ICMP port unreachable (`ConnectionRefused`) or no route.
    Io(io::Error),
    Dns(DnsError),
}

impl From<io::Error> for ProbeError {
    fn from(e: io::Error) -> Self {
        match e.kind() {
            ErrorKind::WouldBlock | ErrorKind::TimedOut => ProbeError::Timeout,
            _ => ProbeError::Io(e),
        }
    }
}

/// How long a resolved `server:port` is reused before resolving again. A hostname lookup is
/// a real DNS query through the system resolver; probes run every few seconds.
const RESOLVE_TTL: Duration = Duration::from_secs(300);

fn resolved() -> &'static Mutex<HashMap<String, (SocketAddr, Instant)>> {
    static CACHE: OnceLock<Mutex<HashMap<String, (SocketAddr, Instant)>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Resolves `server:port`, reusing a recent answer. `forget` drops the entry so the next
/// probe re-resolves — used when a probe fails, which may mean the IP changed.
fn resolve_cached(server: &str, port: u16) -> Result<SocketAddr, ProbeError> {
    let key = format!("{server}:{port}");
    if let Ok(cache) = resolved().lock() {
        if let Some((addr, at)) = cache.get(&key) {
            if at.elapsed() < RESOLVE_TTL {
                return Ok(*addr);
            }
        }
    }
    // shortcut: the lookup is not bounded by the probe `timeout`; IP literals skip DNS anyway.
    let addr = (server, port)
        .to_socket_addrs()
        .map_err(|_| ProbeError::Resolve)?
        .next()
        .ok_or(ProbeError::Resolve)?;
    if let Ok(mut cache) = resolved().lock() {
        cache.insert(key, (addr, Instant::now()));
    }
    Ok(addr)
}

fn forget(server: &str, port: u16) {
    if let Ok(mut cache) = resolved().lock() {
        cache.remove(&format!("{server}:{port}"));
    }
}

pub fn probe(cfg: &ProbeConfig) -> Result<ProbeOk, ProbeError> {
    let start = Instant::now();
    let deadline = start + cfg.timeout;
    let addr = resolve_cached(&cfg.server, cfg.port)?;
    let query = dns::build_query(random_id(), &cfg.test_domain).map_err(ProbeError::Dns)?;
    let rcode = match cfg.protocol {
        Protocol::Udp => probe_udp(addr, &query, deadline),
        Protocol::Tcp => probe_tcp(addr, &query, deadline),
    };
    match rcode {
        Ok(rc) => Ok(ProbeOk {
            rtt: start.elapsed(),
            rcode: rc,
        }),
        // A failed probe may mean the hostname now points elsewhere: re-resolve next time.
        Err(e) => {
            forget(&cfg.server, cfg.port);
            Err(e)
        }
    }
}

fn probe_udp(addr: SocketAddr, query: &[u8], deadline: Instant) -> Result<u8, ProbeError> {
    let bind: SocketAddr = if addr.is_ipv4() {
        "0.0.0.0:0"
    } else {
        "[::]:0"
    }
    .parse()
    .unwrap();
    let sock = UdpSocket::bind(bind)?;
    sock.connect(addr)?; // connected: ICMP unreachable surfaces as ConnectionRefused
    sock.send(query)?;
    let mut buf = [0u8; 1500];
    loop {
        sock.set_read_timeout(Some(remaining(deadline)?))?;
        let n = sock.recv(&mut buf)?;
        match dns::validate_response(query, &buf[..n]) {
            // Late reply to an earlier probe or stray packet: keep waiting.
            Err(DnsError::IdMismatch) => continue,
            r => return r.map_err(ProbeError::Dns),
        }
    }
}

fn probe_tcp(addr: SocketAddr, query: &[u8], deadline: Instant) -> Result<u8, ProbeError> {
    let mut s = TcpStream::connect_timeout(&addr, remaining(deadline)?)?;
    let mut msg = (query.len() as u16).to_be_bytes().to_vec();
    msg.extend_from_slice(query);
    s.set_write_timeout(Some(remaining(deadline)?))?;
    s.write_all(&msg)?;

    let mut len = [0u8; 2];
    s.set_read_timeout(Some(remaining(deadline)?))?;
    s.read_exact(&mut len)?;
    let mut resp = vec![0u8; u16::from_be_bytes(len) as usize];
    s.set_read_timeout(Some(remaining(deadline)?))?;
    s.read_exact(&mut resp)?;
    dns::validate_response(query, &resp).map_err(ProbeError::Dns)
}

fn remaining(deadline: Instant) -> Result<Duration, ProbeError> {
    deadline
        .checked_duration_since(Instant::now())
        .filter(|d| !d.is_zero())
        .ok_or(ProbeError::Timeout)
}

/// Unpredictable query ID from std's per-process random hash keys (no `rand` dependency).
pub(crate) fn random_id() -> u16 {
    RandomState::new().build_hasher().finish() as u16
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolution_is_cached_and_forgotten() {
        let key = "localhost:53".to_string();
        forget("localhost", 53);
        let addr = resolve_cached("localhost", 53).unwrap();
        assert!(resolved().lock().unwrap().contains_key(&key));
        assert_eq!(resolve_cached("localhost", 53).unwrap(), addr);
        forget("localhost", 53);
        assert!(!resolved().lock().unwrap().contains_key(&key));
    }
}
