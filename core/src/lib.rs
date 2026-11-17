//! DnsFlow core: DNS wire helpers, health probes and the activation state machine.
//! Android-specific integration lives in Kotlin; this crate has no platform code.

pub mod dns;
pub mod engine;
pub mod probe;
