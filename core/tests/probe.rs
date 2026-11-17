//! Probes against fake DNS servers on localhost.

use std::io::{Read, Write};
use std::net::{TcpListener, UdpSocket};
use std::thread;
use std::time::{Duration, Instant};

use dnsly_core::dns::DnsError;
use dnsly_core::probe::{probe, ProbeConfig, ProbeError, Protocol};

/// Turns a received query into a response with `rcode`.
fn reply(query: &[u8], rcode: u8) -> Vec<u8> {
    let mut r = query.to_vec();
    r[2] |= 0x80;
    r[3] = 0x80 | rcode;
    r
}

fn cfg(port: u16, protocol: Protocol) -> ProbeConfig {
    ProbeConfig {
        server: "127.0.0.1".into(),
        port,
        protocol,
        test_domain: "pi.hole".into(),
        timeout: Duration::from_millis(500),
    }
}

/// UDP server answering one query with whatever `respond` returns (`None` = stay silent).
fn udp_server(respond: impl Fn(&[u8]) -> Vec<Vec<u8>> + Send + 'static) -> u16 {
    let sock = UdpSocket::bind("127.0.0.1:0").unwrap();
    let port = sock.local_addr().unwrap().port();
    thread::spawn(move || {
        let mut buf = [0u8; 512];
        let (n, peer) = sock.recv_from(&mut buf).unwrap();
        for pkt in respond(&buf[..n]) {
            sock.send_to(&pkt, peer).unwrap();
        }
        thread::sleep(Duration::from_secs(1)); // keep socket open: silence, not ICMP
    });
    port
}

#[test]
fn udp_server_up() {
    let port = udp_server(|q| vec![reply(q, 0)]);
    let ok = probe(&cfg(port, Protocol::Udp)).unwrap();
    assert_eq!(ok.rcode, 0);
}

#[test]
fn udp_nxdomain_counts_as_up() {
    let port = udp_server(|q| vec![reply(q, 3)]);
    assert_eq!(probe(&cfg(port, Protocol::Udp)).unwrap().rcode, 3);
}

#[test]
fn udp_servfail_is_down() {
    let port = udp_server(|q| vec![reply(q, 2)]);
    let err = probe(&cfg(port, Protocol::Udp)).unwrap_err();
    assert!(
        matches!(err, ProbeError::Dns(DnsError::Rcode(2))),
        "{err:?}"
    );
}

#[test]
fn udp_garbage_is_invalid() {
    let port = udp_server(|q| {
        let mut r = q[..12].to_vec(); // header only, question missing
        r[2] |= 0x80;
        vec![r]
    });
    let err = probe(&cfg(port, Protocol::Udp)).unwrap_err();
    assert!(
        matches!(err, ProbeError::Dns(DnsError::QuestionMismatch)),
        "{err:?}"
    );
}

#[test]
fn udp_skips_stray_packet_with_wrong_id() {
    let port = udp_server(|q| {
        let mut stray = reply(q, 0);
        stray[0] ^= 0xFF;
        vec![stray, reply(q, 0)]
    });
    assert!(probe(&cfg(port, Protocol::Udp)).is_ok());
}

#[test]
fn udp_silent_server_times_out_within_budget() {
    let port = udp_server(|_| vec![]);
    let start = Instant::now();
    let err = probe(&cfg(port, Protocol::Udp)).unwrap_err();
    assert!(matches!(err, ProbeError::Timeout), "{err:?}");
    assert!(start.elapsed() < Duration::from_millis(900));
}

#[test]
fn udp_closed_port_fails_fast() {
    // Bind then drop to get a port with nothing listening: kernel answers ICMP unreachable.
    let port = UdpSocket::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port();
    let start = Instant::now();
    let err = probe(&cfg(port, Protocol::Udp)).unwrap_err();
    assert!(matches!(err, ProbeError::Io(_)), "{err:?}");
    assert!(start.elapsed() < Duration::from_millis(400));
}

#[test]
fn tcp_server_up() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    thread::spawn(move || {
        let (mut s, _) = listener.accept().unwrap();
        let mut len = [0u8; 2];
        s.read_exact(&mut len).unwrap();
        let mut q = vec![0u8; u16::from_be_bytes(len) as usize];
        s.read_exact(&mut q).unwrap();
        let r = reply(&q, 0);
        s.write_all(&(r.len() as u16).to_be_bytes()).unwrap();
        s.write_all(&r).unwrap();
    });
    assert_eq!(probe(&cfg(port, Protocol::Tcp)).unwrap().rcode, 0);
}

#[test]
fn tcp_refused_is_down() {
    let port = TcpListener::bind("127.0.0.1:0")
        .unwrap()
        .local_addr()
        .unwrap()
        .port();
    let err = probe(&cfg(port, Protocol::Tcp)).unwrap_err();
    assert!(matches!(err, ProbeError::Io(_)), "{err:?}");
}

#[test]
fn tcp_server_closing_early_is_down() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    thread::spawn(move || drop(listener.accept().unwrap()));
    assert!(probe(&cfg(port, Protocol::Tcp)).is_err());
}

#[test]
fn ipv6_literal_works() {
    let Ok(sock) = UdpSocket::bind("[::1]:0") else {
        return;
    }; // host without IPv6
    let port = sock.local_addr().unwrap().port();
    thread::spawn(move || {
        let mut buf = [0u8; 512];
        let (n, peer) = sock.recv_from(&mut buf).unwrap();
        sock.send_to(&reply(&buf[..n], 0), peer).unwrap();
    });
    let c = ProbeConfig {
        server: "::1".into(),
        ..cfg(port, Protocol::Udp)
    };
    assert!(probe(&c).is_ok());
}

#[test]
fn invalid_test_domain_is_rejected() {
    let c = ProbeConfig {
        test_domain: "bad name".into(),
        ..cfg(53, Protocol::Udp)
    };
    assert!(matches!(
        probe(&c),
        Err(ProbeError::Dns(DnsError::InvalidName))
    ));
}

#[test]
fn unresolvable_host_is_reported() {
    let c = ProbeConfig {
        server: "no-such-host.invalid".into(),
        ..cfg(53, Protocol::Udp)
    };
    assert!(matches!(probe(&c), Err(ProbeError::Resolve)));
}
