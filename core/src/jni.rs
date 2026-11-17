//! JNI entry points for `app.dnsflow.core.NativeCore`.
//! Results cross the boundary as one string so Kotlin needs no JNI object building:
//! `ok <rtt_us> <rcode>` or `err <reason>`.

use std::time::Duration;

use ::jni::objects::{JClass, JString};
use ::jni::sys::{jboolean, jint, jstring};
use ::jni::JNIEnv;

use crate::dns::DnsError;
use crate::probe::{probe, ProbeConfig, ProbeError, ProbeOk, Protocol};

/// Blocking: Kotlin calls it off the main thread.
#[no_mangle]
pub extern "system" fn Java_app_dnsflow_core_NativeCore_probeRaw<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    server: JString<'local>,
    port: jint,
    tcp: jboolean,
    domain: JString<'local>,
    timeout_ms: jint,
) -> jstring {
    // On failure a Java exception is pending; returning null lets Kotlin throw it.
    let Ok(server) = env.get_string(&server).map(String::from) else {
        return std::ptr::null_mut();
    };
    let Ok(domain) = env.get_string(&domain).map(String::from) else {
        return std::ptr::null_mut();
    };
    let out = run_probe(server, port, tcp != 0, domain, timeout_ms);
    env.new_string(out)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Validates the raw JNI arguments (trust boundary), runs the probe and encodes the result.
fn run_probe(server: String, port: i32, tcp: bool, domain: String, timeout_ms: i32) -> String {
    let Ok(port @ 1..) = u16::try_from(port) else {
        return "err invalid port".into();
    };
    if timeout_ms <= 0 {
        return "err invalid timeout".into();
    }
    if server.trim().is_empty() {
        return "err empty server".into();
    }
    encode(probe(&ProbeConfig {
        server: server.trim().to_string(),
        port,
        protocol: if tcp { Protocol::Tcp } else { Protocol::Udp },
        test_domain: domain.trim().to_string(),
        timeout: Duration::from_millis(timeout_ms as u64),
    }))
}

fn encode(r: Result<ProbeOk, ProbeError>) -> String {
    match r {
        Ok(ok) => format!("ok {} {}", ok.rtt.as_micros(), ok.rcode),
        Err(e) => format!(
            "err {}",
            match e {
                ProbeError::Resolve => "hostname did not resolve".into(),
                ProbeError::Timeout => "timeout".into(),
                ProbeError::Io(e) => e.to_string(),
                ProbeError::Dns(DnsError::InvalidName) => "invalid test domain".into(),
                ProbeError::Dns(DnsError::Rcode(c)) => format!("server answered rcode {c}"),
                ProbeError::Dns(d) => format!("invalid DNS response ({d:?})"),
            }
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(server: &str, port: i32, domain: &str, timeout_ms: i32) -> String {
        run_probe(server.into(), port, false, domain.into(), timeout_ms)
    }

    #[test]
    fn rejects_bad_arguments() {
        assert_eq!(run("127.0.0.1", 0, "a.com", 100), "err invalid port");
        assert_eq!(run("127.0.0.1", 65536, "a.com", 100), "err invalid port");
        assert_eq!(run("127.0.0.1", -1, "a.com", 100), "err invalid port");
        assert_eq!(run("127.0.0.1", 53, "a.com", 0), "err invalid timeout");
        assert_eq!(run("  ", 53, "a.com", 100), "err empty server");
        assert_eq!(
            run("127.0.0.1", 53, "bad..name", 100),
            "err invalid test domain"
        );
    }

    #[test]
    fn encodes_results() {
        let ok = ProbeOk {
            rtt: Duration::from_micros(1234),
            rcode: 3,
        };
        assert_eq!(encode(Ok(ok)), "ok 1234 3");
        assert_eq!(encode(Err(ProbeError::Timeout)), "err timeout");
        assert_eq!(
            encode(Err(ProbeError::Dns(DnsError::Rcode(2)))),
            "err server answered rcode 2"
        );
    }
}
