package app.dnsly.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.dnsly.core.EngineDriver

/**
 * Re-derives the engine state after boot or app update (ARCHITECTURE §6). The engine itself
 * is not persisted: replaying the facts (enabled, inside, network) rebuilds it, and if the
 * server is reachable the VPN comes up again without user intervention.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EngineDriver.ensure(context)
    }
}
