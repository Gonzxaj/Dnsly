//! Dnsly core: DNS wire helpers, health probes and the activation state machine.
//! Android-specific integration lives in Kotlin; the only platform code here is the
//! thin JNI layer in `jni`.

pub mod dns;
pub mod engine;
pub mod forwarder;
pub mod jni;
pub mod probe;
