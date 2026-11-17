package app.dnsly.core

/** JNI bridge to the Rust core (core/src/jni.rs). */
object NativeCore {
    init {
        System.loadLibrary("dnsly_core")
    }

    /** Blocking: sends one real DNS query and validates the answer. Call off the main thread. */
    fun probe(server: String, port: Int, tcp: Boolean, domain: String, timeoutMs: Int): ProbeResult =
        ProbeResult.parse(probeRaw(server, port, tcp, domain, timeoutMs))

    /**
     * Starts the DNS forwarder on a detached TUN fd; Rust owns and closes the fd.
     * [listener] gets `onQueryTimeout()` when forwarded queries expire unanswered.
     * Returns a handle for [forwarderStop], or 0 if it could not start (fd already closed).
     */
    @JvmStatic
    external fun forwarderStart(tunFd: Int, server: String, port: Int, timeoutMs: Int, listener: ForwarderListener?): Long

    /** Stops the forwarder and closes the TUN fd. Blocks up to ~200 ms. Call once per handle. */
    @JvmStatic
    external fun forwarderStop(handle: Long)

    /** Creates the state machine; returns an opaque handle, or 0 if the config is invalid. */
    @JvmStatic
    external fun engineNew(
        failThreshold: Int,
        recoverThreshold: Int,
        checkIntervalMs: Long,
        retryDelayMs: Long,
        backoffBaseMs: Long,
        backoffMaxMs: Long,
    ): Long

    /**
     * Feeds one event to the engine and returns the actions encoded for [EngineActions.parse].
     * Event names and argument order mirror `decode_event` in core/src/jni.rs.
     */
    @JvmStatic
    external fun engineHandle(handle: Long, event: String, p1: Long, p2: Long): String

    /** Current engine state name, parsed by [EngineActions.parseState]. */
    @JvmStatic
    external fun engineState(handle: Long): String

    /** Frees the engine. Call once per handle. */
    @JvmStatic
    external fun engineFree(handle: Long)

    @JvmStatic
    private external fun probeRaw(server: String, port: Int, tcp: Boolean, domain: String, timeoutMs: Int): String
}

/** Called from the Rust forwarder thread when user queries expire unanswered (passive failure signal). */
fun interface ForwarderListener {
    fun onQueryTimeout()
}

sealed interface ProbeResult {
    data class Ok(val rttMs: Double, val rcode: Int) : ProbeResult
    data class Failed(val reason: String) : ProbeResult

    companion object {
        /** Parses the core's `ok <rtt_us> <rcode>` / `err <reason>` string. */
        fun parse(s: String): ProbeResult {
            val parts = s.split(' ', limit = 3)
            return when {
                parts[0] == "ok" && parts.size == 3 ->
                    Ok(parts[1].toLong() / 1000.0, parts[2].toInt())
                parts[0] == "err" && parts.size >= 2 -> Failed(s.removePrefix("err "))
                else -> error("unexpected probe result: $s")
            }
        }
    }
}
