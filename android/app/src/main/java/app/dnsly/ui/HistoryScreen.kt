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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val ranges = listOf("Hoy", "Ayer", "7 días", "30 días")

@Composable
fun HistoryScreen(events: List<HistoryEvent>) {
    var range by rememberSaveable { mutableIntStateOf(0) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ranges.forEachIndexed { i, r -> GreenChip(r, i == range) { range = i } }
    }
    // shortcut: the range filter is visual only until history is stored (Etapa 6).
    events.forEach { e ->
        GlassCard {
            Row(verticalAlignment = Alignment.Top) {
                Text(e.time, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.kind.title, style = MaterialTheme.typography.titleSmall, color = Palette.text)
                    Caption(e.detail)
                }
                Dot(e.kind.color, 9.dp)
            }
        }
    }
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
