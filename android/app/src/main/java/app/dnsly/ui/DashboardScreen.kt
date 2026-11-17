package app.dnsly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DashboardScreen(s: DashboardState, onOpenServer: () -> Unit) {
    if (s.privateDnsStrict) {
        GlassCard(tint = Palette.orange) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Ic.Alert, Palette.orange, 40.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("DNS privado activo", style = MaterialTheme.typography.titleMedium, color = Palette.orange)
                    Caption("Android usa el DNS privado configurado e ignora a Dnsly. Desactivalo en Ajustes › Red › DNS privado.")
                }
            }
        }
    }
    GlassCard(tint = s.state.color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(s.state.icon, s.state.color, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Caption("Estado actual")
                Text(s.state.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = s.state.color)
                Caption(s.subtitle, Palette.text)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HalfCard(
                Modifier.weight(1f), "Dentro del área",
                if (s.insideArea) "Sí" else "No", if (s.insideArea) Palette.green else Palette.gray, s.areaName,
            )
            val (value, color, caption) = when {
                s.state == DnsState.Disabled || s.state == DnsState.OutsideGeofence -> Triple("—", Palette.gray, "Sin comprobar")
                s.state == DnsState.CheckingServer -> Triple("Probando", Palette.blue, "Consultando…")
                s.serverUp -> Triple("Disponible", Palette.green, "Responde OK")
                else -> Triple("Caído", Palette.orange, "No responde")
            }
            HalfCard(Modifier.weight(1f), "Servidor DNS", value, color, caption)
        }
    }

    GlassCard(onClick = onOpenServer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label("Servidor DNS")
                Text(s.serverName, style = MaterialTheme.typography.titleLarge, color = Palette.text)
                Text(s.serverAddress, style = MaterialTheme.typography.titleMedium, color = Palette.text)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (s.serverUp) Palette.green else Palette.gray, 7.dp)
                    Spacer(Modifier.width(6.dp))
                    Caption("${s.protocol} · Puerto ${s.port}")
                }
            }
            GlassCard(Modifier.width(120.dp)) {
                Column(Modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                    Value(s.avgRttMs?.let { "${it}ms" } ?: "—", Palette.green)
                    Caption("Respuesta promedio")
                }
            }
        }
    }

    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val hasNext = s.nextCheckS >= 0
            Ring(if (hasNext) (s.nextCheckS / s.checkIntervalS.toFloat()).coerceIn(0f, 1f) else 0f, Palette.green) {
                Text(if (hasNext) "${s.nextCheckS}" else "—", style = MaterialTheme.typography.titleMedium, color = Palette.text)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Label("Próxima comprobación")
                Text(
                    if (hasNext) "cada ${s.checkIntervalS} segundos" else "Sin comprobación programada",
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.text,
                )
                Caption(if (s.lastCheckAgoS >= 0) "Última: hace ${s.lastCheckAgoS} s" else "Última: nunca")
            }
        }
    }

    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("Estado de la VPN")
                Value(if (s.vpnConnected) "Conectada" else "Desconectada", if (s.vpnConnected) Palette.green else Palette.gray)
                // Honest wording (PLAN §2): a local VPN captures DNS; the system DNS is not changed.
                Caption(if (s.vpnConnected) "VPN local de DNS activa" else "Se usa el DNS de la red")
            }
            IconTile(Ic.Lock, if (s.vpnConnected) Palette.green else Palette.gray, 48.dp, circle = true)
        }
    }
}

@Composable
private fun HalfCard(modifier: Modifier, label: String, value: String, color: androidx.compose.ui.graphics.Color, caption: String) {
    GlassCard(modifier) {
        Label(label)
        Value(value, color)
        Caption(caption)
    }
}
