package app.dnsly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.dnsly.core.NativeCore
import app.dnsly.data.ServerConfig
import app.dnsly.core.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onServer: () -> Unit, onLocation: () -> Unit, onAutomation: () -> Unit) {
    SettingsRow(Ic.Server, "Servidor DNS", "Dirección, protocolo y comprobaciones", onServer)
    SettingsRow(Ic.MapPin, "Ubicación", "Área y radio de activación", onLocation)
    SettingsRow(Ic.Zap, "Automatización", "Cuándo se activa y se desactiva", onAutomation)
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, caption: String, onClick: () -> Unit) {
    GlassCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, Palette.green, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.text)
                Caption(caption)
            }
            Icon(Ic.Chevron, Palette.muted, 20.dp)
        }
    }
}

/** Server form. "Probar servidor" runs a real probe through the core; "Guardar" persists it. */
@Composable
fun ServerScreen(initial: ServerConfig, onSave: (ServerConfig) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var server by rememberSaveable { mutableStateOf(initial.address) }
    var port by rememberSaveable { mutableStateOf(initial.port.toString()) }
    var tcp by rememberSaveable { mutableStateOf(initial.tcp) }
    var domain by rememberSaveable { mutableStateOf(initial.domain) }
    var timeout by rememberSaveable { mutableStateOf(initial.timeoutS.toString()) }
    var interval by rememberSaveable { mutableStateOf(initial.intervalS.toString()) }
    var failures by rememberSaveable { mutableStateOf(initial.failures.toString()) }
    // Not saveable: the probe coroutine does not survive a configuration change.
    var running by remember { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var resultOk by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val timeoutS = timeout.toIntOrNull()?.takeIf { it in 1..30 }
    val intervalS = interval.toIntOrNull()?.takeIf { it in 5..3600 }
    val failuresN = failures.toIntOrNull()?.takeIf { it in 1..10 }
    val canProbe = !running && server.isNotBlank() && domain.isNotBlank() && portNum != null && timeoutS != null
    val canSave = canProbe && intervalS != null && failuresN != null

    GlassCard {
        Text("Información básica", style = MaterialTheme.typography.titleMedium, color = Palette.text)
        Field("Nombre", name, { name = it })
        Field("Dirección (IP o hostname)", server, { server = it }, KeyboardType.Uri)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Puerto", port, { port = it }, KeyboardType.Number, portNum == null, Modifier.weight(1f))
            Column(Modifier.weight(1f)) {
                Caption("Protocolo")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GreenChip("UDP", !tcp) { tcp = false }
                    GreenChip("TCP", tcp) { tcp = true }
                }
            }
        }
        Field("Dominio de prueba", domain, { domain = it }, KeyboardType.Uri)
        Caption("Se usa para verificar la disponibilidad del servidor.")
    }

    GlassCard {
        Text("Avanzado", style = MaterialTheme.typography.titleMedium, color = Palette.text)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("Timeout (s)", timeout, { timeout = it }, KeyboardType.Number, timeoutS == null, Modifier.weight(1f))
            Field("Intervalo (s)", interval, { interval = it }, KeyboardType.Number, intervalS == null, Modifier.weight(1f))
        }
        Field("Fallos consecutivos permitidos", failures, { failures = it }, KeyboardType.Number, failuresN == null)
    }

    Button(
        onClick = {
            running = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { NativeCore.probe(server, portNum!!, tcp, domain, timeoutS!! * 1000) }
                resultOk = r is ProbeResult.Ok
                result = describe(r)
                running = false
            }
        },
        enabled = canProbe,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.green, contentColor = Palette.background),
    ) { Text(if (running) "Probando…" else "Probar servidor") }
    OutlinedButton(
        onClick = {
            onSave(ServerConfig(name.trim(), server.trim(), portNum!!, tcp, domain.trim(), timeoutS!!, intervalS!!, failuresN!!))
        },
        enabled = canSave,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) { Text("Guardar", color = if (canSave) Palette.green else Palette.muted) }
    result?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = if (resultOk) Palette.green else Palette.orange) }
}

/** Real switches (Etapa 6): both persist through [EngineDriver]/AutomationPrefs. */
@Composable
fun AutomationScreen(
    enabled: Boolean,
    autoReactivate: Boolean,
    onEnabled: (Boolean) -> Unit,
    onAutoReactivate: (Boolean) -> Unit,
) {
    SwitchCard("Activación automática", "Activa el DNS al cumplirse las condiciones", enabled, onEnabled)
    SwitchCard("Reactivación automática", "Vuelve a activarlo cuando el servidor se recupera", autoReactivate, onAutoReactivate)
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.Info, Palette.muted, 20.dp)
            Spacer(Modifier.width(10.dp))
            // Not configurable (ARCHITECTURE §9.3): keeping the VPN with a dead server leaves the phone without DNS.
            Caption("Si el servidor no responde o salís del área, Dnsly siempre vuelve al DNS de la red.")
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value, onChange, modifier,
        label = { Text(label) }, singleLine = true, isError = isError,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.green,
            unfocusedBorderColor = Palette.outline,
            focusedLabelColor = Palette.green,
            cursorColor = Palette.green,
        ),
    )
}

private fun describe(r: ProbeResult): String = when (r) {
    is ProbeResult.Ok -> {
        val code = when (r.rcode) { 0 -> "NOERROR"; 3 -> "NXDOMAIN"; else -> "rcode ${r.rcode}" }
        "Responde: %.1f ms (%s)".format(r.rttMs, code)
    }
    is ProbeResult.Failed -> "No responde: ${r.reason}"
}
