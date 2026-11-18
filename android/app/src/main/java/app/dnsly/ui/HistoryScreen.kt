package app.dnsly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.dnsly.data.history.HistoryEntry
import app.dnsly.data.history.HistoryKind
import app.dnsly.data.history.HistoryStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val ranges = listOf("Hoy", "Ayer", "7 días", "30 días")
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateTimeFormat = DateTimeFormatter.ofPattern("dd/MM HH:mm")

@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    // Collected here, not at the root: inserts only recompose this tab.
    val entries by remember { HistoryStore.ensure(ctx); HistoryStore.events() }
        .collectAsState(emptyList())
    var range by rememberSaveable { mutableIntStateOf(0) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ranges.forEachIndexed { i, r -> GreenChip(r, i == range) { range = i } }
    }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val cutoffMs = when (range) {
        0 -> today.atStartOfDay(zone).toInstant().toEpochMilli()
        1 -> today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        2 -> Instant.now().minus(7, ChronoUnit.DAYS).toEpochMilli()
        else -> Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli()
    }
    val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val visible = entries.filter { it.timestampMs >= cutoffMs }
    if (visible.isEmpty()) {
        GlassCard { Caption("No hay eventos en este período") }
    }
    visible.forEach { e ->
        val ui = e.toUiEvent(zone, todayStartMs)
        GlassCard {
            Row(verticalAlignment = Alignment.Top) {
                Text(ui.time, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(ui.kind.title, style = MaterialTheme.typography.titleSmall, color = Palette.text)
                    Caption(ui.detail)
                }
                Dot(ui.kind.color, 9.dp)
            }
        }
    }
}

private fun HistoryEntry.toUiEvent(zone: ZoneId, todayStartMs: Long): HistoryEvent {
    val time = Instant.ofEpochMilli(timestampMs).atZone(zone)
    val label = if (timestampMs >= todayStartMs) timeFormat else dateTimeFormat
    return HistoryEvent(label.format(time), kind(), detail)
}

private fun HistoryEntry.kind(): EventKind = when (kind) {
    HistoryKind.SERVER_DOWN -> EventKind.ServerDown
    HistoryKind.SERVER_BACK -> EventKind.ServerBack
    HistoryKind.ENTER_AREA -> EventKind.EnterArea
    HistoryKind.LEAVE_AREA -> EventKind.LeaveArea
    HistoryKind.VPN_ERROR -> EventKind.VpnError
    else -> EventKind.ServerDown
}

@Composable
fun GreenChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected, onClick, { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Palette.surfaceHigh,
            labelColor = Palette.text,
            selectedContainerColor = Palette.green,
            selectedLabelColor = Palette.background,
        ),
        border = FilterChipDefaults.filterChipBorder(true, selected, borderColor = Palette.outline, selectedBorderColor = Palette.green),
    )
}
