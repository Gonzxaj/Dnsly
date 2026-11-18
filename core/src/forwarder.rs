//! DNS-only VPN forwarder. Reads IP packets from the TUN fd, forwards UDP/53 queries sent to
//! the virtual DNS address to the upstream server, and writes the answers back into the TUN.
//! One thread, `poll(2)` over the TUN fd, the upstream UDP socket and a wake eventfd; the loop
//! only wakes for packets, query deadlines or shutdown — never on a fixed period.

use std::collections::HashMap;
use std::fs::File;
use std::io::{self, ErrorKind, Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, ToSocketAddrs, UdpSocket};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

/// Virtual DNS server addresses. Must match the VpnService.Builder setup in DnslyVpnService.kt.
pub const DNS_V4: Ipv4Addr = Ipv4Addr::new(10, 111, 222, 1);
pub const DNS_V6: Ipv6Addr = Ipv6Addr::new(0xfd00, 0x111, 0x222, 0, 0, 0, 0, 1);
/// Queries in flight; more are dropped (the client retries).
const MAX_PENDING: usize = 1024;
/// Minimum interval between `on_timeout` notifications, so a flood of expiring queries
/// cannot turn into a flood of JNI calls.
const NOTIFY_TIMEOUT: Duration = Duration::from_millis(500);
const DNS_HEADER: usize = 12;
const UDP: u8 = 17;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Flow {
    pub src: IpAddr,
    pub dst: IpAddr,
    pub sport: u16,
    pub dport: u16,
}

/// Running forwarder thread. Dropping it stops the thread, waits for it and closes the TUN fd.
pub struct Forwarder {
    stop: Arc<AtomicBool>,
    /// Written to on drop so the poll loop wakes immediately instead of waiting for a timeout.
    wake: OwnedFd,
    thread: Option<JoinHandle<()>>,
}

impl Forwarder {
    // shortcut: upstream is always UDP, even when probes use TCP; add TCP upstream if a
    // network blocks UDP/53 to the server in practice.
    /// `on_timeout` fires (at most every [`NOTIFY_TIMEOUT`]) when forwarded queries expire
    /// unanswered: the passive failure signal the engine uses while the VPN is active.
    pub fn start(
        tun: File,
        server: &str,
        port: u16,
        timeout: Duration,
        on_timeout: Option<Box<dyn Fn() + Send>>,
    ) -> io::Result<Self> {
        let upstream = (server, port)
            .to_socket_addrs()?
            .next()
            .ok_or(ErrorKind::NotFound)?;
        let bind: SocketAddr = if upstream.is_ipv4() {
            (Ipv4Addr::UNSPECIFIED, 0).into()
        } else {
            (Ipv6Addr::UNSPECIFIED, 0).into()
        };
        let sock = UdpSocket::bind(bind)?;
        sock.connect(upstream)?;
        sock.set_nonblocking(true)?;
        let wake = eventfd()?;
        let stop = Arc::new(AtomicBool::new(false));
        let flag = stop.clone();
        let wake_fd = wake.as_raw_fd();
        let thread = thread::Builder::new()
            .name("dnsly-forwarder".into())
            .spawn(move || run(tun, sock, wake_fd, timeout, on_timeout, &flag))?;
        Ok(Forwarder {
            stop,
            wake,
            thread: Some(thread),
        })
    }
}

impl Drop for Forwarder {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        let byte = 1u64.to_ne_bytes();
        // SAFETY: `wake` stays open until the fields drop, after the join below.
        unsafe { libc::write(self.wake.as_raw_fd(), byte.as_ptr().cast(), 8) };
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

fn eventfd() -> io::Result<OwnedFd> {
    // SAFETY: no preconditions; the flags are valid on Linux/Android.
    let fd = unsafe { libc::eventfd(0, libc::EFD_CLOEXEC | libc::EFD_NONBLOCK) };
    if fd < 0 {
        Err(io::Error::last_os_error())
    } else {
        // SAFETY: `fd` is a fresh descriptor owned by this call.
        Ok(unsafe { OwnedFd::from_raw_fd(fd) })
    }
}

struct Pending {
    flow: Flow,
    orig_id: u16,
    deadline: Instant,
}

fn run(
    mut tun: File,
    sock: UdpSocket,
    wake: RawFd,
    timeout: Duration,
    on_timeout: Option<Box<dyn Fn() + Send>>,
    stop: &AtomicBool,
) {
    let mut pending: HashMap<u16, Pending> = HashMap::new();
    let mut next_id = crate::probe::random_id();
    // TUN packets are bounded by the interface MTU (1500); upstream replies can be large.
    let mut tun_buf = vec![0u8; 2048];
    let mut up_buf = vec![0u8; 65535];
    let mut out = Vec::with_capacity(2048);
    let mut last_notify = Instant::now() - NOTIFY_TIMEOUT;
    while !stop.load(Ordering::Relaxed) {
        // Sleep until the earliest query deadline (or forever when idle): the loop only wakes
        // for packets, a deadline or the stop eventfd, never on a fixed period.
        let poll_ms = pending
            .values()
            .map(|p| p.deadline)
            .min()
            .map(|d| d.saturating_duration_since(Instant::now()))
            .map_or(-1, |d| d.as_millis().min(i32::MAX as u128) as i32);
        let mut fds = [
            libc::pollfd {
                fd: tun.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            },
            libc::pollfd {
                fd: sock.as_raw_fd(),
                events: libc::POLLIN,
                revents: 0,
            },
            libc::pollfd {
                fd: wake,
                events: libc::POLLIN,
                revents: 0,
            },
        ];
        // SAFETY: `fds` is a valid array of 3 pollfd for the duration of the call.
        if unsafe { libc::poll(fds.as_mut_ptr(), 3, poll_ms) } < 0 {
            if io::Error::last_os_error().kind() == ErrorKind::Interrupted {
                continue;
            }
            return;
        }
        if fds[0].revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL) != 0 {
            return; // VPN interface torn down (revoked or closed by the system)
        }

        if fds[0].revents & libc::POLLIN != 0 {
            match tun.read(&mut tun_buf) {
                Ok(n) => {
                    if let Some((flow, query)) = parse_query(&tun_buf[..n]) {
                        if pending.len() < MAX_PENDING {
                            let orig_id = u16::from_be_bytes([query[0], query[1]]);
                            // Offset of the DNS payload inside tun_buf, so the ID can be
                            // rewritten in place and the query sent upstream without copying.
                            let off = query.as_ptr() as usize - tun_buf.as_ptr() as usize;
                            let qlen = query.len();
                            while pending.contains_key(&next_id) {
                                next_id = next_id.wrapping_add(1);
                            }
                            tun_buf[off..off + 2].copy_from_slice(&next_id.to_be_bytes());
                            // Send errors (no route, refused) just leave the query to time out.
                            if sock.send(&tun_buf[off..off + qlen]).is_ok() {
                                pending.insert(
                                    next_id,
                                    Pending {
                                        flow,
                                        orig_id,
                                        deadline: Instant::now() + timeout,
                                    },
                                );
                                next_id = next_id.wrapping_add(1);
                            }
                        }
                    }
                }
                Err(e) if matches!(e.kind(), ErrorKind::Interrupted | ErrorKind::WouldBlock) => {}
                Err(_) => return,
            }
        }

        if fds[1].revents & libc::POLLIN != 0 {
            loop {
                match sock.recv(&mut up_buf) {
                    Ok(n) if n >= DNS_HEADER => {
                        let id = u16::from_be_bytes([up_buf[0], up_buf[1]]);
                        if let Some(p) = pending.remove(&id) {
                            up_buf[0..2].copy_from_slice(&p.orig_id.to_be_bytes());
                            write_reply(&p.flow, &up_buf[..n], &mut out);
                            if tun.write(&out).is_err() {
                                return;
                            }
                        }
                    }
                    Ok(_) => {}
                    Err(e) if e.kind() == ErrorKind::WouldBlock => break,
                    // ICMP unreachable surfaces here as ConnectionRefused: ignore, queries time out.
                    Err(_) => break,
                }
            }
        }

        let now = Instant::now();
        let mut expired = 0usize;
        pending.retain(|_, p| {
            let alive = p.deadline > now;
            if !alive {
                expired += 1;
            }
            alive
        });
        if expired > 0 && now.duration_since(last_notify) >= NOTIFY_TIMEOUT {
            last_notify = now;
            if let Some(cb) = &on_timeout {
                cb();
            }
        }
    }
}

/// Flow and DNS payload of a UDP/53 packet addressed to the virtual DNS server, or `None`
/// for anything else (TCP, other destinations, fragments, malformed packets).
pub fn parse_query(pkt: &[u8]) -> Option<(Flow, &[u8])> {
    let (flow, payload) = parse_udp(pkt)?;
    let to_dns = match flow.dst {
        IpAddr::V4(a) => a == DNS_V4,
        IpAddr::V6(a) => a == DNS_V6,
    };
    (to_dns && flow.dport == 53 && payload.len() >= DNS_HEADER).then_some((flow, payload))
}

fn parse_udp(pkt: &[u8]) -> Option<(Flow, &[u8])> {
    let (src, dst, udp): (IpAddr, IpAddr, &[u8]) = match pkt.first()? >> 4 {
        4 => {
            let ihl = usize::from(pkt[0] & 0x0F) * 4;
            let total = usize::from(u16::from_be_bytes([*pkt.get(2)?, *pkt.get(3)?]));
            let frag = u16::from_be_bytes([*pkt.get(6)?, *pkt.get(7)?]) & 0x3FFF;
            if ihl < 20 || total < ihl || total > pkt.len() || frag != 0 || pkt[9] != UDP {
                return None;
            }
            let src: [u8; 4] = pkt[12..16].try_into().ok()?;
            let dst: [u8; 4] = pkt[16..20].try_into().ok()?;
            (src.into(), dst.into(), &pkt[ihl..total])
        }
        6 => {
            if pkt.len() < 40 || pkt[6] != UDP {
                return None; // extension headers are not followed
            }
            let end = 40 + usize::from(u16::from_be_bytes([pkt[4], pkt[5]]));
            let src: [u8; 16] = pkt[8..24].try_into().ok()?;
            let dst: [u8; 16] = pkt[24..40].try_into().ok()?;
            (src.into(), dst.into(), pkt.get(40..end)?)
        }
        _ => return None,
    };
    if udp.len() < 8 {
        return None;
    }
    let len = usize::from(u16::from_be_bytes([udp[4], udp[5]]));
    let payload = udp.get(8..len)?;
    let flow = Flow {
        src,
        dst,
        sport: u16::from_be_bytes([udp[0], udp[1]]),
        dport: u16::from_be_bytes([udp[2], udp[3]]),
    };
    Some((flow, payload))
}

/// Answer packet for `flow` (the query's flow): addresses and ports swapped.
pub fn build_reply(flow: &Flow, payload: &[u8]) -> Vec<u8> {
    build_packet(flow.dst, flow.src, flow.dport, flow.sport, payload)
}

fn build_packet(src: IpAddr, dst: IpAddr, sport: u16, dport: u16, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    build_packet_into(src, dst, sport, dport, payload, &mut out);
    out
}

/// Writes the answer packet for `flow` into `out` (cleared first) so the hot path reuses one buffer.
fn write_reply(flow: &Flow, payload: &[u8], out: &mut Vec<u8>) {
    build_packet_into(flow.dst, flow.src, flow.dport, flow.sport, payload, out)
}

fn build_packet_into(
    src: IpAddr,
    dst: IpAddr,
    sport: u16,
    dport: u16,
    payload: &[u8],
    out: &mut Vec<u8>,
) {
    out.clear();
    out.reserve(60 + payload.len());
    let udp_len = (8 + payload.len()) as u16;
    match (src, dst) {
        (IpAddr::V4(s), IpAddr::V4(d)) => {
            out.extend_from_slice(&[0x45, 0]);
            out.extend_from_slice(&(20 + udp_len).to_be_bytes());
            out.extend_from_slice(&[0, 0, 0x40, 0, 64, UDP, 0, 0]); // id 0, DF, TTL 64
            out.extend_from_slice(&s.octets());
            out.extend_from_slice(&d.octets());
            let c = checksum(&out[..20], 0);
            out[10..12].copy_from_slice(&c.to_be_bytes());
        }
        (IpAddr::V6(s), IpAddr::V6(d)) => {
            out.extend_from_slice(&[0x60, 0, 0, 0]);
            out.extend_from_slice(&udp_len.to_be_bytes());
            out.extend_from_slice(&[UDP, 64]);
            out.extend_from_slice(&s.octets());
            out.extend_from_slice(&d.octets());
        }
        _ => unreachable!("flow addresses are always the same IP version"),
    }
    let udp_start = out.len();
    out.extend_from_slice(&sport.to_be_bytes());
    out.extend_from_slice(&dport.to_be_bytes());
    out.extend_from_slice(&udp_len.to_be_bytes());
    out.extend_from_slice(&[0, 0]); // checksum filled below
    out.extend_from_slice(payload);
    // 0 means "no checksum" in UDP.
    let c = match checksum(&out[udp_start..], pseudo_sum(src, dst, udp_len)) {
        0 => 0xFFFF,
        c => c,
    };
    out[udp_start + 6..udp_start + 8].copy_from_slice(&c.to_be_bytes());
}

/// Partial sum of the UDP pseudo-header. For IPv4 it is src+dst+"0,17"+len (12 bytes); for
/// IPv6 src+dst+len32+zeros+next-header — both reduce to this since `udp_len` fits u16.
fn pseudo_sum(src: IpAddr, dst: IpAddr, udp_len: u16) -> u32 {
    let base = match (src, dst) {
        (IpAddr::V4(s), IpAddr::V4(d)) => sum(&s.octets(), sum(&d.octets(), 0)),
        (IpAddr::V6(s), IpAddr::V6(d)) => sum(&s.octets(), sum(&d.octets(), 0)),
        _ => unreachable!("flow addresses are always the same IP version"),
    };
    base + u32::from(UDP) + u32::from(udp_len)
}

fn sum(data: &[u8], mut acc: u32) -> u32 {
    for c in data.chunks(2) {
        acc += u32::from(u16::from_be_bytes([c[0], *c.get(1).unwrap_or(&0)]));
    }
    acc
}

/// Internet checksum (RFC 1071) of `data`, starting from the partial sum `acc`.
fn checksum(data: &[u8], acc: u32) -> u16 {
    let mut acc = sum(data, acc);
    while acc > 0xFFFF {
        acc = (acc & 0xFFFF) + (acc >> 16);
    }
    !(acc as u16)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::fd::OwnedFd;
    use std::os::unix::net::UnixDatagram;

    const CLIENT_V4: Ipv4Addr = Ipv4Addr::new(10, 111, 222, 2);
    const CLIENT_V6: Ipv6Addr = Ipv6Addr::new(0xfd00, 0x111, 0x222, 0, 0, 0, 0, 2);

    fn query(id: u16) -> Vec<u8> {
        crate::dns::build_query(id, "example.com").unwrap()
    }

    fn ip_header_ok(pkt: &[u8]) -> bool {
        checksum(&pkt[..20], 0) == 0
    }

    fn udp_checksum_ok(pkt: &[u8], src: IpAddr, dst: IpAddr) -> bool {
        let udp = if pkt[0] >> 4 == 4 {
            &pkt[20..]
        } else {
            &pkt[40..]
        };
        let len = udp.len() as u16;
        let pseudo = match (src, dst) {
            (IpAddr::V4(s), IpAddr::V4(d)) => {
                [&s.octets()[..], &d.octets(), &[0, UDP], &len.to_be_bytes()].concat()
            }
            (IpAddr::V6(s), IpAddr::V6(d)) => [
                &s.octets()[..],
                &d.octets(),
                &u32::from(len).to_be_bytes(),
                &[0, 0, 0, UDP],
            ]
            .concat(),
            _ => unreachable!(),
        };
        checksum(udp, sum(&pseudo, 0)) == 0
    }

    #[test]
    fn parses_v4_and_v6_queries_with_valid_checksums() {
        let q = query(7);
        for (client, dns) in [
            (IpAddr::V4(CLIENT_V4), IpAddr::V4(DNS_V4)),
            (IpAddr::V6(CLIENT_V6), IpAddr::V6(DNS_V6)),
        ] {
            let pkt = build_packet(client, dns, 40000, 53, &q);
            if client.is_ipv4() {
                assert!(ip_header_ok(&pkt));
            }
            assert!(udp_checksum_ok(&pkt, client, dns));
            let (flow, payload) = parse_query(&pkt).expect("query parsed");
            assert_eq!(
                flow,
                Flow {
                    src: client,
                    dst: dns,
                    sport: 40000,
                    dport: 53
                }
            );
            assert_eq!(payload, &q[..]);

            let reply = build_reply(&flow, &q);
            let (rflow, rpayload) = parse_udp(&reply).unwrap();
            assert_eq!(
                rflow,
                Flow {
                    src: dns,
                    dst: client,
                    sport: 53,
                    dport: 40000
                }
            );
            assert_eq!(rpayload, &q[..]);
            assert!(udp_checksum_ok(&reply, dns, client));
        }
    }

    #[test]
    fn ignores_everything_else() {
        let q = query(7);
        let c = IpAddr::V4(CLIENT_V4);
        let dns = IpAddr::V4(DNS_V4);
        let other = IpAddr::V4(Ipv4Addr::new(8, 8, 8, 8));
        assert!(
            parse_query(&build_packet(c, other, 40000, 53, &q)).is_none(),
            "other destination"
        );
        assert!(
            parse_query(&build_packet(c, dns, 40000, 853, &q)).is_none(),
            "other port"
        );
        assert!(
            parse_query(&build_packet(c, dns, 40000, 53, &q[..5])).is_none(),
            "short DNS payload"
        );

        let pkt = build_packet(c, dns, 40000, 53, &q);
        let mut tcp = pkt.clone();
        tcp[9] = 6;
        assert!(parse_query(&tcp).is_none(), "TCP");
        let mut frag = pkt.clone();
        frag[6] |= 0x20; // more fragments
        assert!(parse_query(&frag).is_none(), "fragment");
        for n in [0, 1, 19, 27, pkt.len() - 1] {
            assert!(parse_query(&pkt[..n]).is_none(), "truncated to {n}");
        }
    }

    /// Full loop: a datagram socket pair stands in for the TUN fd (one packet per read/write).
    #[test]
    fn forwards_queries_and_routes_answers_back() {
        let upstream = UdpSocket::bind("127.0.0.1:0").unwrap();
        let up_addr = upstream.local_addr().unwrap();
        thread::spawn(move || {
            let mut buf = [0u8; 512];
            while let Ok((n, from)) = upstream.recv_from(&mut buf) {
                buf[2] |= 0x80; // echo the query back as a response
                upstream.send_to(&buf[..n], from).unwrap();
            }
        });

        let (tun_end, test_end) = UnixDatagram::pair().unwrap();
        let tun = File::from(OwnedFd::from(tun_end));
        let fwd = Forwarder::start(
            tun,
            "127.0.0.1",
            up_addr.port(),
            Duration::from_secs(2),
            None,
        )
        .unwrap();
        test_end
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();

        // Two clients using the same DNS ID: the forwarder must not mix their answers up.
        let c = IpAddr::V4(CLIENT_V4);
        let dns = IpAddr::V4(DNS_V4);
        for port in [1111u16, 2222] {
            test_end
                .send(&build_packet(c, dns, port, 53, &query(0x1234)))
                .unwrap();
        }
        let mut ports = Vec::new();
        let mut buf = [0u8; 1500];
        for _ in 0..2 {
            let n = test_end.recv(&mut buf).unwrap();
            let (flow, payload) = parse_udp(&buf[..n]).unwrap();
            assert_eq!((flow.src, flow.sport, flow.dst), (dns, 53, c));
            assert_eq!(
                u16::from_be_bytes([payload[0], payload[1]]),
                0x1234,
                "original ID restored"
            );
            assert!(crate::dns::validate_response(&query(0x1234), payload).is_ok());
            ports.push(flow.dport);
        }
        ports.sort();
        assert_eq!(ports, [1111, 2222]);

        let t = Instant::now();
        drop(fwd);
        assert!(t.elapsed() < Duration::from_secs(1), "stop is prompt");
    }

    /// Upstream that never answers: the query expires and `on_timeout` fires.
    #[test]
    fn notifies_when_queries_expire() {
        use std::sync::atomic::AtomicUsize;

        let upstream = UdpSocket::bind("127.0.0.1:0").unwrap();
        let up_addr = upstream.local_addr().unwrap();
        let (tun_end, test_end) = UnixDatagram::pair().unwrap();
        let tun = File::from(OwnedFd::from(tun_end));
        let count = Arc::new(AtomicUsize::new(0));
        let notified = count.clone();
        let fwd = Forwarder::start(
            tun,
            "127.0.0.1",
            up_addr.port(),
            Duration::from_millis(100),
            Some(Box::new(move || {
                notified.fetch_add(1, Ordering::Relaxed);
            })),
        )
        .unwrap();

        test_end
            .send(&build_packet(
                IpAddr::V4(CLIENT_V4),
                IpAddr::V4(DNS_V4),
                1111,
                53,
                &query(1),
            ))
            .unwrap();
        let deadline = Instant::now() + Duration::from_secs(3);
        while count.load(Ordering::Relaxed) == 0 && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(20));
        }
        assert!(
            count.load(Ordering::Relaxed) > 0,
            "timeout was not reported"
        );
        drop(fwd);
    }
}
