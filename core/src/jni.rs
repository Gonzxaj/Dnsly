//! JNI entry points for `app.dnsly.core.NativeCore`.
//! Probe results cross the boundary as one string so Kotlin needs no JNI object building:
//! `ok <rtt_us> <rcode>` or `err <reason>`. The forwarder is an opaque `jlong` handle.
//! The engine crosses it the same way: `engineHandle` takes an event name plus two long
//! arguments and returns the actions encoded as `;`-separated tokens (see `encode_actions`).

use std::fs::File;
use std::os::fd::FromRawFd;
use std::time::Duration;

use ::jni::objects::{GlobalRef, JClass, JObject, JString};
use ::jni::sys::{jboolean, jint, jlong, jstring};
use ::jni::{JNIEnv, JavaVM};

use crate::dns::DnsError;
use crate::engine::{Action, Config, Engine, Event, State};
use crate::forwarder::Forwarder;
use crate::probe::{probe, ProbeConfig, ProbeError, ProbeOk, Protocol};

/// Blocking: Kotlin calls it off the main thread.
#[no_mangle]
pub extern "system" fn Java_app_dnsly_core_NativeCore_probeRaw<'local>(
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

/// Takes ownership of `tun_fd` (closed on every path) and starts the forwarder thread.
/// `listener` may be null; when present its `onQueryTimeout()` is called on expired queries.
/// Returns a handle for `forwarderStop`, or 0 if it could not start.
#[no_mangle]
pub extern "system" fn Java_app_dnsly_core_NativeCore_forwarderStart<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    tun_fd: jint,
    server: JString<'local>,
    port: jint,
    timeout_ms: jint,
    listener: JObject<'local>,
) -> jlong {
    if tun_fd < 0 {
        return 0;
    }
    // SAFETY: Kotlin hands over a detached fd (ParcelFileDescriptor.detachFd) it no longer uses.
    let tun = unsafe { File::from_raw_fd(tun_fd) };
    let Ok(server) = env.get_string(&server).map(String::from) else {
        return 0;
    };
    let (Ok(port @ 1..), 1..) = (u16::try_from(port), timeout_ms) else {
        return 0;
    };
    let on_timeout = if listener.as_raw().is_null() {
        None
    } else {
        match TimeoutNotifier::new(&mut env, &listener) {
            Ok(n) => Some(Box::new(move || n.notify()) as Box<dyn Fn() + Send>),
            Err(_) => None,
        }
    };
    match Forwarder::start(
        tun,
        server.trim(),
        port,
        Duration::from_millis(timeout_ms as u64),
        on_timeout,
    ) {
        Ok(f) => Box::into_raw(Box::new(f)) as jlong,
        Err(_) => 0,
    }
}

/// Stops the forwarder, waits for its thread and closes the TUN fd. Call once per handle.
#[no_mangle]
pub extern "system" fn Java_app_dnsly_core_NativeCore_forwarderStop<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: `handle` came from Box::into_raw in forwarderStart and is stopped only once.
        drop(unsafe { Box::from_raw(handle as *mut Forwarder) });
    }
}

/// Creates the state machine. Returns an opaque handle, or 0 if the config is invalid.
#[no_mangle]
pub extern "system" fn Java_app_dnsly_core_NativeCore_engineNew<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    fail_threshold: jint,
    recover_threshold: jint,
    check_interval_ms: jlong,
    retry_delay_ms: jlong,
    backoff_base_ms: jlong,
    backoff_max_ms: jlong,
    auto_recover: jboolean,
) -> jlong {
    let (Ok(fail), Ok(recover)) = (
        u32::try_from(fail_threshold),
        u32::try_from(recover_threshold),
    ) else {
        return 0;
    };
    let (Ok(check), Ok(retry), Ok(base), Ok(max)) = (
        u64::try_from(check_interval_ms),
        u64::try_from(retry_delay_ms),
        u64::try_from(backoff_base_ms),
        u64::try_from(backoff_max_ms),
    ) else {
        return 0;
    };
    match Engine::new(Config {
        fail_threshold: fail,
        recover_threshold: recover,
        check_interval_ms: check,
        retry_delay_ms: retry,
        backoff_base_ms: base,
        backoff_max_ms: max,
        auto_recover: auto_recover != 0,
    }) {
        Ok(e) => Box::into_raw(Box::new(e)) as jlong,
        Err(_) => 0,
    }
}

/// Feeds one event to the engine and returns the resulting actions encoded for Kotlin.
/// Unknown event names produce an empty action list.
#[no_mangle]
pub extern "system" fn Java_app_dnsly_core_NativeCore_engineHandle<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    event: JString<'local>,
    p1: jlong,
    p2: jlong,
) -> jstring {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let Ok(name) = env.get_string(&event).map(String::from) else {
        return std::ptr::null_mut();
    };
    // SAFETY: `handle` came from Box::into_raw in engineNew; Kotlin serializes calls on one thread.
    let engine = unsafe { &mut *(handle as *mut Engine) };
    let Some(ev) = decode_event(&name, p1, p2) else {
        return env
            .new_string("")
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut());
    };
    let actions = engine.handle(ev);
    env.new_string(encode_actions(&actions))
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Current engine state as a lowercase name (`dns_active`, `server_unavailable`, ...).
#[no_mangle]
pub extern "system" fn Java_app_dnsly_core_NativeCore_engineState<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    // SAFETY: same as engineHandle.
    let engine = unsafe { &*(handle as *mut Engine) };
    env.new_string(encode_state(engine.state()))
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Frees the engine. Call once per handle.
#[no_mangle]
pub extern "system" fn Java_app_dnsly_core_NativeCore_engineFree<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: `handle` came from Box::into_raw in engineNew and is freed only once.
        drop(unsafe { Box::from_raw(handle as *mut Engine) });
    }
}

/// Calls `listener.onQueryTimeout()` from the forwarder thread. The forwarder thread is a
/// plain Rust thread, so each call attaches to the JVM; the `GlobalRef` deletes itself on drop.
struct TimeoutNotifier {
    vm: JavaVM,
    listener: GlobalRef,
}

impl TimeoutNotifier {
    fn new(env: &mut JNIEnv, listener: &JObject) -> ::jni::errors::Result<Self> {
        Ok(Self {
            vm: env.get_java_vm()?,
            listener: env.new_global_ref(listener)?,
        })
    }

    fn notify(&self) {
        let Ok(mut env) = self.vm.attach_current_thread() else {
            return;
        };
        let _ = env.call_method(self.listener.as_obj(), "onQueryTimeout", "()V", &[]);
    }
}

/// Maps the Kotlin event name + args to an engine event.
fn decode_event(name: &str, p1: i64, p2: i64) -> Option<Event> {
    let flag = |v: i64| v != 0;
    match name {
        "set_enabled" => Some(Event::SetEnabled(flag(p1))),
        "geofence" => Some(Event::Geofence { inside: flag(p1) }),
        "network" => Some(Event::Network {
            available: flag(p1),
        }),
        "probe_done" => Some(Event::ProbeDone {
            generation: u64::try_from(p1).unwrap_or(0),
            ok: flag(p2),
        }),
        "query_timeout" => Some(Event::QueryTimeout),
        "vpn_failed" => Some(Event::VpnFailed),
        "retry_vpn" => Some(Event::RetryVpn),
        _ => None,
    }
}

/// `start_vpn;stop_vpn;schedule_probe:<delay_ms>:<generation>;cancel_probe`, `;`-joined.
fn encode_actions(actions: &[Action]) -> String {
    actions
        .iter()
        .map(|a| match a {
            Action::StartVpn => "start_vpn".to_string(),
            Action::StopVpn => "stop_vpn".to_string(),
            Action::CancelProbe => "cancel_probe".to_string(),
            Action::ScheduleProbe {
                delay_ms,
                generation,
            } => format!("schedule_probe:{delay_ms}:{generation}"),
        })
        .collect::<Vec<_>>()
        .join(";")
}

fn encode_state(state: State) -> &'static str {
    match state {
        State::Disabled => "disabled",
        State::OutsideGeofence => "outside_geofence",
        State::CheckingServer => "checking_server",
        State::DnsActive => "dns_active",
        State::ServerUnavailable => "server_unavailable",
        State::VpnError => "vpn_error",
    }
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
    use crate::engine::State;

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

    #[test]
    fn decodes_every_event() {
        assert_eq!(
            decode_event("set_enabled", 1, 0),
            Some(Event::SetEnabled(true))
        );
        assert_eq!(
            decode_event("geofence", 0, 0),
            Some(Event::Geofence { inside: false })
        );
        assert_eq!(
            decode_event("network", 1, 0),
            Some(Event::Network { available: true })
        );
        assert_eq!(
            decode_event("probe_done", 7, 1),
            Some(Event::ProbeDone {
                generation: 7,
                ok: true
            })
        );
        assert_eq!(
            decode_event("query_timeout", 0, 0),
            Some(Event::QueryTimeout)
        );
        assert_eq!(decode_event("vpn_failed", 0, 0), Some(Event::VpnFailed));
        assert_eq!(decode_event("retry_vpn", 0, 0), Some(Event::RetryVpn));
        assert_eq!(decode_event("bogus", 0, 0), None);
        // A negative generation must not wrap into a huge one.
        assert_eq!(
            decode_event("probe_done", -1, 0),
            Some(Event::ProbeDone {
                generation: 0,
                ok: false
            })
        );
    }

    #[test]
    fn encodes_actions() {
        let actions = [
            Action::StopVpn,
            Action::ScheduleProbe {
                delay_ms: 2000,
                generation: 3,
            },
            Action::CancelProbe,
            Action::StartVpn,
        ];
        assert_eq!(
            encode_actions(&actions),
            "stop_vpn;schedule_probe:2000:3;cancel_probe;start_vpn"
        );
        assert_eq!(encode_actions(&[]), "");
    }

    #[test]
    fn encodes_states() {
        for (state, name) in [
            (State::Disabled, "disabled"),
            (State::OutsideGeofence, "outside_geofence"),
            (State::CheckingServer, "checking_server"),
            (State::DnsActive, "dns_active"),
            (State::ServerUnavailable, "server_unavailable"),
            (State::VpnError, "vpn_error"),
        ] {
            assert_eq!(encode_state(state), name);
        }
    }
}
