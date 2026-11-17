package app.dnsflow.core

/** JNI bridge to the Rust core (core/src/jni.rs). */
object NativeCore {
    init {
        System.loadLibrary("dnsflow_core")
    }

    /** Blocking: sends one real DNS query and validates the answer. Call off the main thread. */
    fun probe(server: String, port: Int, tcp: Boolean, domain: String, timeoutMs: Int): ProbeResult =
        ProbeResult.parse(probeRaw(server, port, tcp, domain, timeoutMs))

    @JvmStatic
    private external fun probeRaw(server: String, port: Int, tcp: Boolean, domain: String, timeoutMs: Int): String
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
