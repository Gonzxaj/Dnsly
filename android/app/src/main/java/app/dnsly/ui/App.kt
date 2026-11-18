package app.dnsly.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.dnsly.core.EngineDriver
import app.dnsly.core.EngineState
import app.dnsly.data.ServerConfig
import app.dnsly.data.ServerPrefs
import app.dnsly.net.privateDnsStrict
import app.dnsly.service.DnslyVpnService
import app.dnsly.service.VpnStatus
import kotlinx.coroutines.delay

enum class Screen(val title: String, val parent: Screen? = null) {
    Dashboard("Dashboard"),
    History("Historial"),
    Location("Ubicación"),
    Settings("Ajustes"),
    Server("Servidor DNS", Settings),
    Automation("Automatización", Settings),
}

private enum class Tab(val screen: Screen, val label: String, val icon: ImageVector) {
    Dashboard(Screen.Dashboard, "Dashboard", Ic.Home),
    History(Screen.History, "Historial", Ic.History),
    Location(Screen.Location, "Ubicación", Ic.MapPin),
    Settings(Screen.Settings, "Ajustes", Ic.Settings),
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.Dashboard) }
    var server by remember { mutableStateOf(ServerPrefs.load(ctx)) }
    val engineState by EngineDriver.state.collectAsState()
    val automationOn by EngineDriver.enabled.collectAsState()
    val autoReactivate by EngineDriver.autoReactivate.collectAsState()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) EngineDriver.setEnabled(true)
    }
    val enableAutomation = {
        if (!server.isConfigured) screen = Screen.Server
        else {
            val prepare = VpnService.prepare(ctx)
            if (prepare == null) EngineDriver.setEnabled(true) else consent.launch(prepare)
        }
    }

    BackHandler(screen != Screen.Dashboard) { screen = screen.parent ?: Screen.Dashboard }

    Column(Modifier.fillMaxSize().background(Palette.background).statusBarsPadding()) {
        Column(Modifier.padding(horizontal = Dimens.gutter)) {
            if (screen == Screen.Dashboard) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Dnsly", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, color = Palette.text)
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { screen = Screen.Settings }, contentAlignment = Alignment.Center) {
                        Icon(Ic.Settings, Palette.text, 22.dp)
                    }
                }
            } else {
                TopBar(screen.title, onBack = { screen = screen.parent ?: Screen.Dashboard })
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (screen) {
                Screen.Dashboard -> DashboardPanel(server, engineState) { screen = Screen.Server }
                Screen.History -> HistoryScreen()
                Screen.Location -> LocationScreen()
                Screen.Settings -> SettingsScreen(
                    onServer = { screen = Screen.Server },
                    onLocation = { screen = Screen.Location },
                    onAutomation = { screen = Screen.Automation },
                )
                Screen.Server -> ServerScreen(server) {
                    ServerPrefs.save(ctx, it)
                    server = it
                    EngineDriver.reloadConfig(ctx)
                    screen = Screen.Settings
                }
                Screen.Automation -> AutomationScreen(
                    enabled = automationOn,
                    autoReactivate = autoReactivate,
                    onEnabled = { on -> if (on) enableAutomation() else EngineDriver.setEnabled(false) },
                    onAutoReactivate = EngineDriver::setAutoReactivate,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        BottomNav(
            selected = (screen.parent ?: screen),
            enabled = automationOn,
            onSelect = { screen = it },
            // Etapa 4: the power button is the automation switch; the engine drives the VPN.
            onPower = {
                when {
                    engineState == EngineState.VpnError -> EngineDriver.retryVpn()
                    automationOn -> EngineDriver.setEnabled(false)
                    else -> enableAutomation()
                }
            },
        )
    }
}

/**
 * Dashboard subtree: owns the flows and the 1 s countdown ticker only it renders, so the other
 * tabs do not recompose every second (and the private-DNS callback is not registered either).
 */
@Composable
private fun DashboardPanel(server: ServerConfig, engineState: EngineState, onOpenServer: () -> Unit) {
    val ctx = LocalContext.current
    val vpn by DnslyVpnService.status.collectAsState()
    val strict by remember { privateDnsStrict(ctx.applicationContext) }.collectAsState(false)
    val inside by EngineDriver.insideArea.collectAsState()
    val geo by EngineDriver.geo.collectAsState()
    val probeStats by EngineDriver.probeStats.collectAsState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val (state, subtitle) = when (engineState) {
        EngineState.DnsActive -> DnsState.DnsActive to DnsState.DnsActive.subtitle
        EngineState.CheckingServer -> DnsState.CheckingServer to "Probando el servidor DNS"
        EngineState.ServerUnavailable ->
            DnsState.ServerUnavailable to "Usando el DNS de la red · reintentando"
        EngineState.OutsideGeofence -> DnsState.OutsideGeofence to "Usando el DNS de la red"
        EngineState.VpnError ->
            DnsState.VpnError to ((vpn as? VpnStatus.Error)?.message ?: DnsState.VpnError.subtitle)
        EngineState.Disabled -> DnsState.Disabled to "La automatización está apagada"
    }
    DashboardScreen(
        dashboard(
            state = state,
            subtitle = subtitle,
            privateDnsStrict = strict,
            insideArea = inside,
            areaName = if (geo.geofenceEnabled && geo.hasLocation) "Área configurada" else "Sin área",
            server = server,
            rttMs = probeStats.rttMs,
            nextCheckAtMs = probeStats.nextCheckAtMs,
            lastCheckAtMs = probeStats.lastCheckAtMs,
            nowMs = nowMs,
        ),
        onOpenServer,
    )
}

/** go-data-android's bottom nav, split in two around the power button. */
@Composable
private fun BottomNav(selected: Screen, enabled: Boolean, onSelect: (Screen) -> Unit, onPower: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(Modifier.navigationBarsPadding().padding(horizontal = Dimens.gutter, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.fillMaxWidth().clip(shape)
                .background(Brush.verticalGradient(listOf(Palette.surfaceHigh, Palette.surface)))
                .border(1.dp, Palette.outline, shape).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEachIndexed { i, t ->
                if (i == Tab.entries.size / 2) Spacer(Modifier.size(72.dp, 1.dp))
                val sel = t.screen == selected
                val tint = if (sel) Palette.green else Palette.muted
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                        .selectable(sel, onClick = { onSelect(t.screen) }, role = Role.Tab)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, tint, 22.dp)
                    Spacer(Modifier.height(3.dp))
                    Text(t.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
                }
            }
        }
        val color = if (enabled) Palette.green else Palette.gray
        Box(
            Modifier.size(62.dp).shadow(16.dp, CircleShape, ambientColor = color, spotColor = color).clip(CircleShape)
                .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.75f))))
                .border(3.dp, Palette.background, CircleShape)
                .clickable(onClick = onPower),
            contentAlignment = Alignment.Center,
        ) { Icon(Ic.Power, if (enabled) Palette.background else Color.White, 28.dp) }
    }
}
