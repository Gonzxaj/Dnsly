package app.dnsly.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// GlassCard, IconTile, Pill and Dot follow go-data-android's ui/Components.kt.

/**
 * Base surface of every card: a faint diagonal gradient, a hairline border lit from the top and
 * large corners. [tint] washes the card with a state color.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.cardRadius)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Palette.surfaceHigh, Palette.surface)))
            .then(if (tint != null) Modifier.background(Brush.linearGradient(listOf(tint.copy(alpha = 0.14f), Color.Transparent))) else Modifier)
            .border(1.dp, Brush.verticalGradient(listOf(tint?.copy(alpha = 0.35f) ?: Palette.outline, Palette.outline.copy(alpha = 0.35f))), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(Dimens.cardPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Rounded square holding a line icon, tinted with [tint]. */
@Composable
fun IconTile(icon: ImageVector, tint: Color, size: Dp = 44.dp, circle: Boolean = false) {
    val shape = if (circle) CircleShape else RoundedCornerShape(12.dp)
    Box(
        Modifier.size(size).clip(shape).background(tint.copy(alpha = 0.14f)).border(1.dp, tint.copy(alpha = 0.25f), shape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, tint, size * 0.5f) }
}

/** Small rounded label, tinted with [color]. */
@Composable
fun Pill(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

@Composable
fun Caption(text: String, color: Color = Palette.muted) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.muted)
}

@Composable
fun Value(text: String, color: Color = Palette.text) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = color)
}

/** Ring that fills to [fraction], with [content] in the middle. */
@Composable
fun Ring(fraction: Float, color: Color, size: Dp = 52.dp, content: @Composable () -> Unit) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            drawArc(Palette.outline, 0f, 360f, false, style = stroke)
            drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, style = stroke)
        }
        content()
    }
}

/** Title row of a sub-screen: back arrow, centered title, optional action icon. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, action: ImageVector? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).then(if (onBack != null) Modifier.clickable(onClick = onBack) else Modifier), contentAlignment = Alignment.Center) {
            if (onBack != null) Icon(Ic.Back, Palette.text, 22.dp)
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Palette.text, textAlign = TextAlign.Center)
        Box(Modifier.size(40.dp).clip(CircleShape).then(if (action != null) Modifier.clickable(onClick = onAction) else Modifier), contentAlignment = Alignment.Center) {
            if (action != null) Icon(action, Palette.text, 22.dp)
        }
    }
}

/** Card row with a title, a caption and a switch. */
@Composable
fun SwitchCard(title: String, caption: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.text)
                Caption(caption)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked, onChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Palette.green,
                    checkedThumbColor = Color.White,
                    uncheckedTrackColor = Palette.outline,
                    uncheckedBorderColor = Palette.outline,
                    uncheckedThumbColor = Palette.muted,
                ),
            )
        }
    }
}
