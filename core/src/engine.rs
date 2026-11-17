//! Activation state machine. Pure: no clock, no I/O.
//! The host (Kotlin) feeds `Event`s and executes the returned `Action`s.
//! State is never persisted; after a restart the host replays the current facts
//! (enabled, inside geofence, network) and the engine re-derives the state.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    Disabled,
    OutsideGeofence,
    CheckingServer,
    DnsActive,
    /// Inside the area, server down, probing with backoff until it recovers.
    ServerUnavailable,
    /// VPN could not start or was revoked. No automatic retry.
    VpnError,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Event {
    SetEnabled(bool),
    /// Host sends `true` when the geofence condition is disabled by the user.
    Geofence {
        inside: bool,
    },
    Network {
        available: bool,
    },
    /// Result of the probe scheduled with the same `generation`.
    ProbeDone {
        generation: u64,
        ok: bool,
    },
    /// A forwarded user query timed out (passive signal, VPN active only).
    QueryTimeout,
    /// VPN failed to establish, or `onRevoke` was called.
    VpnFailed,
    /// User asked to retry after `VpnError`.
    RetryVpn,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    StartVpn,
    StopVpn,
    /// Run one probe after `delay_ms`, then report `ProbeDone` with this generation.
    /// Replaces any previously scheduled probe.
    ScheduleProbe {
        delay_ms: u64,
        generation: u64,
    },
    CancelProbe,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Config {
    /// Consecutive failures before the server is considered down.
    pub fail_threshold: u32,
    /// Consecutive successes before reactivating after a failure.
    pub recover_threshold: u32,
    /// Probe period while the DNS is active and healthy.
    pub check_interval_ms: u64,
    /// Delay between probes while confirming a failure or a recovery.
    pub retry_delay_ms: u64,
    /// Recovery backoff: base * 2^n, capped at max.
    pub backoff_base_ms: u64,
    pub backoff_max_ms: u64,
    /// When false, a recovered server does not reactivate the DNS by itself:
    /// the engine stays in `ServerUnavailable` until the conditions reset
    /// (automation toggled, area re-entered or network flapped).
    pub auto_recover: bool,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            fail_threshold: 3,
            recover_threshold: 2,
            check_interval_ms: 60_000,
            retry_delay_ms: 2_000,
            backoff_base_ms: 15_000,
            backoff_max_ms: 15 * 60_000,
            auto_recover: true,
        }
    }
}

impl Config {
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.fail_threshold == 0 || self.recover_threshold == 0 {
            return Err("thresholds must be >= 1");
        }
        if self.check_interval_ms == 0 || self.backoff_base_ms == 0 {
            return Err("intervals must be > 0");
        }
        if self.backoff_max_ms < self.backoff_base_ms {
            return Err("backoff_max_ms must be >= backoff_base_ms");
        }
        Ok(())
    }
}

#[derive(Debug)]
pub struct Engine {
    cfg: Config,
    state: State,
    enabled: bool,
    inside: bool,
    network: bool,
    vpn_error: bool,
    fails: u32,
    oks: u32,
    backoff_step: u32,
    generation: u64,
}

impl Engine {
    pub fn new(cfg: Config) -> Result<Self, &'static str> {
        cfg.validate()?;
        Ok(Self {
            cfg,
            state: State::Disabled,
            enabled: false,
            inside: false,
            network: true,
            vpn_error: false,
            fails: 0,
            oks: 0,
            backoff_step: 0,
            generation: 0,
        })
    }

    pub fn state(&self) -> State {
        self.state
    }

    pub fn handle(&mut self, ev: Event) -> Vec<Action> {
        let mut out = Vec::new();
        match ev {
            Event::SetEnabled(on) => {
                self.enabled = on;
                if on {
                    self.vpn_error = false;
                }
                self.evaluate(&mut out);
            }
            Event::Geofence { inside } => {
                self.inside = inside;
                if !inside {
                    self.vpn_error = false; // leaving and re-entering is a fresh attempt
                }
                self.evaluate(&mut out);
            }
            Event::RetryVpn => {
                self.vpn_error = false;
                self.evaluate(&mut out);
            }
            Event::VpnFailed => {
                if self.enabled && self.inside {
                    self.vpn_error = true;
                    self.evaluate(&mut out);
                }
            }
            Event::Network { available } => self.on_network(available, &mut out),
            Event::ProbeDone { generation, ok } => {
                if generation == self.generation && self.network && self.probing() {
                    self.on_probe(ok, &mut out);
                }
            }
            Event::QueryTimeout => {
                // Only accelerate the first failure; once failing, retry pacing applies.
                if self.state == State::DnsActive && self.fails == 0 && self.network {
                    self.schedule(0, &mut out);
                }
            }
        }
        out
    }

    fn probing(&self) -> bool {
        matches!(
            self.state,
            State::CheckingServer | State::DnsActive | State::ServerUnavailable
        )
    }

    /// Derives the state from the external conditions. Keeps the current
    /// probing state if conditions still allow it.
    fn evaluate(&mut self, out: &mut Vec<Action>) {
        let target = if !self.enabled {
            State::Disabled
        } else if !self.inside {
            State::OutsideGeofence
        } else if self.vpn_error {
            State::VpnError
        } else if self.probing() {
            return;
        } else {
            State::CheckingServer
        };
        if target == self.state {
            return;
        }
        self.transition(target, out);
        if target == State::CheckingServer {
            self.fails = 0;
            self.oks = 0;
            if self.network {
                self.schedule(0, out);
            }
        } else {
            self.cancel(out);
        }
    }

    fn on_network(&mut self, available: bool, out: &mut Vec<Action>) {
        self.network = available;
        if !self.probing() {
            return;
        }
        if available {
            // New network (e.g. back on home Wi-Fi): old failures are meaningless.
            self.fails = 0;
            self.oks = 0;
            self.backoff_step = 0;
            self.schedule(0, out);
        } else {
            self.cancel(out);
        }
    }

    fn on_probe(&mut self, ok: bool, out: &mut Vec<Action>) {
        let cfg = self.cfg;
        match (self.state, ok) {
            (State::CheckingServer, true) => self.activate(out),
            (State::DnsActive, true) => {
                self.fails = 0;
                self.schedule(cfg.check_interval_ms, out);
            }
            (State::CheckingServer | State::DnsActive, false) => {
                self.fails += 1;
                if self.fails >= cfg.fail_threshold {
                    self.transition(State::ServerUnavailable, out);
                    self.oks = 0;
                    self.backoff_step = 0;
                    self.schedule(self.backoff(), out);
                } else {
                    self.schedule(cfg.retry_delay_ms, out);
                }
            }
            (State::ServerUnavailable, true) => {
                if !self.cfg.auto_recover {
                    // Manual reactivation only: stop probing and keep the network DNS.
                    return;
                }
                self.oks += 1;
                if self.oks >= cfg.recover_threshold {
                    self.activate(out);
                } else {
                    self.schedule(cfg.retry_delay_ms, out);
                }
            }
            (State::ServerUnavailable, false) => {
                self.oks = 0;
                self.backoff_step = self.backoff_step.saturating_add(1);
                self.schedule(self.backoff(), out);
            }
            _ => {}
        }
    }

    fn activate(&mut self, out: &mut Vec<Action>) {
        self.transition(State::DnsActive, out);
        self.fails = 0;
        self.schedule(self.cfg.check_interval_ms, out);
    }

    /// Sets the state, emitting VPN start/stop only on DnsActive edges.
    fn transition(&mut self, to: State, out: &mut Vec<Action>) {
        if self.state == State::DnsActive && to != State::DnsActive {
            out.push(Action::StopVpn);
        }
        if to == State::DnsActive && self.state != State::DnsActive {
            out.push(Action::StartVpn);
        }
        // A failed VPN may be half-up: make sure it is torn down.
        if to == State::VpnError && self.state != State::DnsActive {
            out.push(Action::StopVpn);
        }
        self.state = to;
    }

    fn schedule(&mut self, delay_ms: u64, out: &mut Vec<Action>) {
        self.generation += 1;
        out.push(Action::ScheduleProbe {
            delay_ms,
            generation: self.generation,
        });
    }

    fn cancel(&mut self, out: &mut Vec<Action>) {
        self.generation += 1; // invalidates in-flight results
        out.push(Action::CancelProbe);
    }

    fn backoff(&self) -> u64 {
        let factor = 1u64.checked_shl(self.backoff_step).unwrap_or(u64::MAX);
        self.cfg
            .backoff_base_ms
            .saturating_mul(factor)
            .min(self.cfg.backoff_max_ms)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use Action::*;
    use State::*;

    fn engine() -> Engine {
        Engine::new(Config::default()).unwrap()
    }

    /// Engine enabled and inside, with the initial probe scheduled.
    fn checking() -> Engine {
        let mut e = engine();
        e.handle(Event::SetEnabled(true));
        e.handle(Event::Geofence { inside: true });
        assert_eq!(e.state(), CheckingServer);
        e
    }

    /// Reports a result for the currently scheduled probe.
    fn probe(e: &mut Engine, ok: bool) -> Vec<Action> {
        let generation = e.generation;
        e.handle(Event::ProbeDone { generation, ok })
    }

    fn active() -> Engine {
        let mut e = checking();
        assert_eq!(probe(&mut e, true)[0], StartVpn);
        e
    }

    fn sched(delay_ms: u64, generation: u64) -> Action {
        ScheduleProbe {
            delay_ms,
            generation,
        }
    }

    #[test]
    fn rejects_invalid_config() {
        let bad = Config {
            fail_threshold: 0,
            ..Config::default()
        };
        assert!(Engine::new(bad).is_err());
        let bad = Config {
            backoff_max_ms: 1,
            ..Config::default()
        };
        assert!(Engine::new(bad).is_err());
    }

    #[test]
    fn starts_disabled_and_ignores_conditions() {
        let mut e = engine();
        assert_eq!(e.handle(Event::Geofence { inside: true }), vec![]);
        assert_eq!(e.state(), Disabled);
    }

    #[test]
    fn enable_outside_then_enter_checks_immediately() {
        let mut e = engine();
        e.handle(Event::SetEnabled(true));
        assert_eq!(e.state(), OutsideGeofence);
        let acts = e.handle(Event::Geofence { inside: true });
        assert_eq!(e.state(), CheckingServer);
        assert_eq!(acts, vec![sched(0, e.generation)]);
    }

    #[test]
    fn server_up_activates_vpn() {
        let mut e = checking();
        let acts = probe(&mut e, true);
        assert_eq!(e.state(), DnsActive);
        assert_eq!(acts, vec![StartVpn, sched(60_000, e.generation)]);
    }

    #[test]
    fn server_down_at_start_never_starts_vpn() {
        let mut e = checking();
        assert_eq!(probe(&mut e, false), vec![sched(2_000, e.generation)]);
        probe(&mut e, false);
        let acts = probe(&mut e, false);
        assert_eq!(e.state(), ServerUnavailable);
        assert_eq!(acts, vec![sched(15_000, e.generation)]);
    }

    #[test]
    fn power_cut_deactivates_after_threshold() {
        let mut e = active();
        assert_eq!(probe(&mut e, false), vec![sched(2_000, e.generation)]);
        assert_eq!(probe(&mut e, false), vec![sched(2_000, e.generation)]);
        assert_eq!(e.state(), DnsActive);
        let acts = probe(&mut e, false);
        assert_eq!(e.state(), ServerUnavailable);
        assert_eq!(acts, vec![StopVpn, sched(15_000, e.generation)]);
    }

    #[test]
    fn intermittent_failures_do_not_flap() {
        let mut e = active();
        for _ in 0..20 {
            for _ in 0..2 {
                assert!(!probe(&mut e, false).contains(&StopVpn));
            }
            probe(&mut e, true);
        }
        assert_eq!(e.state(), DnsActive);
    }

    #[test]
    fn recovery_needs_consecutive_successes() {
        let mut e = active();
        for _ in 0..3 {
            probe(&mut e, false);
        }
        assert_eq!(e.state(), ServerUnavailable);

        // A single success followed by a failure is not a recovery.
        assert_eq!(probe(&mut e, true), vec![sched(2_000, e.generation)]);
        probe(&mut e, false);
        assert_eq!(e.state(), ServerUnavailable);

        probe(&mut e, true);
        let acts = probe(&mut e, true);
        assert_eq!(e.state(), DnsActive);
        assert_eq!(acts, vec![StartVpn, sched(60_000, e.generation)]);
    }

    #[test]
    fn recovery_backoff_grows_and_caps() {
        let mut e = checking();
        let mut delays = vec![];
        for _ in 0..3 {
            probe(&mut e, false);
        }
        for _ in 0..10 {
            match probe(&mut e, false)[..] {
                [ScheduleProbe { delay_ms, .. }] => delays.push(delay_ms),
                ref other => panic!("unexpected {other:?}"),
            }
        }
        assert_eq!(&delays[..4], &[30_000, 60_000, 120_000, 240_000]);
        assert_eq!(*delays.last().unwrap(), 900_000);
    }

    #[test]
    fn backoff_does_not_overflow() {
        let mut e = checking();
        e.state = ServerUnavailable;
        e.backoff_step = 200;
        assert_eq!(e.backoff(), 900_000);
    }

    #[test]
    fn leaving_area_stops_vpn_and_probes() {
        let mut e = active();
        let acts = e.handle(Event::Geofence { inside: false });
        assert_eq!(e.state(), OutsideGeofence);
        assert_eq!(acts, vec![StopVpn, CancelProbe]);
    }

    #[test]
    fn leaving_area_while_unavailable_only_cancels() {
        let mut e = checking();
        for _ in 0..3 {
            probe(&mut e, false);
        }
        assert_eq!(
            e.handle(Event::Geofence { inside: false }),
            vec![CancelProbe]
        );
    }

    #[test]
    fn disabling_stops_everything() {
        let mut e = active();
        assert_eq!(
            e.handle(Event::SetEnabled(false)),
            vec![StopVpn, CancelProbe]
        );
        assert_eq!(e.state(), Disabled);
    }

    #[test]
    fn repeated_geofence_enter_does_not_restart() {
        let mut e = active();
        assert_eq!(e.handle(Event::Geofence { inside: true }), vec![]);
        assert_eq!(e.state(), DnsActive);
    }

    #[test]
    fn stale_probe_results_are_ignored() {
        let mut e = active();
        let old = e.generation;
        e.handle(Event::Geofence { inside: false });
        e.handle(Event::Geofence { inside: true });
        assert_eq!(
            e.handle(Event::ProbeDone {
                generation: old,
                ok: true
            }),
            vec![]
        );
        assert_eq!(e.state(), CheckingServer);
    }

    #[test]
    fn probe_result_after_leaving_is_ignored() {
        let mut e = active();
        let g = e.generation;
        e.handle(Event::Geofence { inside: false });
        assert_eq!(
            e.handle(Event::ProbeDone {
                generation: g,
                ok: false
            }),
            vec![]
        );
        assert_eq!(e.state(), OutsideGeofence);
    }

    #[test]
    fn network_loss_pauses_without_counting_failures() {
        let mut e = active();
        probe(&mut e, false);
        let g = e.generation;
        assert_eq!(
            e.handle(Event::Network { available: false }),
            vec![CancelProbe]
        );
        // A probe that was in flight when the network dropped must not count.
        assert_eq!(
            e.handle(Event::ProbeDone {
                generation: g,
                ok: false
            }),
            vec![]
        );
        assert_eq!(e.state(), DnsActive);

        let acts = e.handle(Event::Network { available: true });
        assert_eq!(acts, vec![sched(0, e.generation)]);
        assert_eq!(e.fails, 0);
    }

    #[test]
    fn network_change_while_unavailable_probes_now_and_resets_backoff() {
        let mut e = checking();
        for _ in 0..6 {
            probe(&mut e, false);
        }
        assert!(e.backoff_step > 0);
        let acts = e.handle(Event::Network { available: true });
        assert_eq!(acts, vec![sched(0, e.generation)]);
        assert_eq!(e.backoff_step, 0);
    }

    #[test]
    fn entering_area_without_network_waits_for_network() {
        let mut e = engine();
        e.handle(Event::Network { available: false });
        e.handle(Event::SetEnabled(true));
        assert_eq!(e.handle(Event::Geofence { inside: true }), vec![]);
        assert_eq!(e.state(), CheckingServer);
        assert_eq!(
            e.handle(Event::Network { available: true }),
            vec![sched(0, e.generation)]
        );
    }

    #[test]
    fn query_timeout_triggers_immediate_probe_once() {
        let mut e = active();
        assert_eq!(e.handle(Event::QueryTimeout), vec![sched(0, e.generation)]);
        probe(&mut e, false);
        assert_eq!(e.handle(Event::QueryTimeout), vec![]); // already failing
    }

    #[test]
    fn query_timeout_ignored_when_not_active() {
        let mut e = checking();
        assert_eq!(e.handle(Event::QueryTimeout), vec![]);
    }

    #[test]
    fn vpn_failure_goes_to_error_without_retry() {
        let mut e = active();
        let acts = e.handle(Event::VpnFailed);
        assert_eq!(e.state(), VpnError);
        assert_eq!(acts, vec![StopVpn, CancelProbe]);
        // Network changes and geofence re-entries while inside do not retry.
        assert_eq!(e.handle(Event::Network { available: true }), vec![]);
        assert_eq!(e.handle(Event::Geofence { inside: true }), vec![]);
        assert_eq!(e.state(), VpnError);
    }

    #[test]
    fn vpn_error_cleared_by_retry_or_reentry() {
        let mut e = active();
        e.handle(Event::VpnFailed);
        e.handle(Event::RetryVpn);
        assert_eq!(e.state(), CheckingServer);

        e.handle(Event::VpnFailed);
        e.handle(Event::Geofence { inside: false });
        e.handle(Event::Geofence { inside: true });
        assert_eq!(e.state(), CheckingServer);
    }

    #[test]
    fn vpn_failed_while_disabled_is_ignored() {
        let mut e = engine();
        assert_eq!(e.handle(Event::VpnFailed), vec![]);
        assert_eq!(e.state(), Disabled);
    }

    #[test]
    fn manual_recovery_waits_for_condition_reset() {
        let cfg = Config {
            auto_recover: false,
            ..Config::default()
        };
        let mut e = Engine::new(cfg).unwrap();
        e.handle(Event::SetEnabled(true));
        e.handle(Event::Geofence { inside: true });
        for _ in 0..3 {
            probe(&mut e, false);
        }
        assert_eq!(e.state(), ServerUnavailable);

        // The server is back but auto-recovery is off: no activation, no more probes.
        assert_eq!(probe(&mut e, true), vec![]);
        assert_eq!(e.state(), ServerUnavailable);

        // Toggling the automation off and on re-checks and activates normally.
        e.handle(Event::SetEnabled(false));
        e.handle(Event::SetEnabled(true));
        assert_eq!(e.state(), CheckingServer);
        let acts = probe(&mut e, true);
        assert_eq!(e.state(), DnsActive);
        assert_eq!(acts, vec![StartVpn, sched(60_000, e.generation)]);
    }

    #[test]
    fn restart_rederives_state_from_facts() {
        // Process died while DNS was active; host replays current facts.
        let mut e = engine();
        e.handle(Event::Network { available: true });
        e.handle(Event::Geofence { inside: true });
        e.handle(Event::SetEnabled(true));
        assert_eq!(e.state(), CheckingServer);
        probe(&mut e, true);
        assert_eq!(e.state(), DnsActive);
    }
}
