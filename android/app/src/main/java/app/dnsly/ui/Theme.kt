package app.dnsly.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Dark-only palette taken from docs/ui-reference.png. */
object Palette {
    val background = Color(0xFF07110E)
    val surface = Color(0xFF0D1915)
    /** Lighter end of the card gradient. */
    val surfaceHigh = Color(0xFF13231E)
    val outline = Color(0xFF1C2E27)
    val text = Color(0xFFE7EFEB)
    val muted = Color(0xFF8B9C95)
    val green = Color(0xFF3DD47A)
    val orange = Color(0xFFF2994A)
    val red = Color(0xFFEF5350)
    val blue = Color(0xFF5B9DFF)
    val gray = Color(0xFF6E7C76)
}

object Dimens {
    val gutter = 16.dp
    val cardRadius = 20.dp
    val cardPadding = 16.dp
}

@Composable
fun DnslyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.green,
            onPrimary = Palette.background,
            secondary = Palette.green,
            background = Palette.background,
            onBackground = Palette.text,
            surface = Palette.background,
            onSurface = Palette.text,
            surfaceVariant = Palette.surfaceHigh,
            onSurfaceVariant = Palette.muted,
            outline = Palette.outline,
            outlineVariant = Palette.outline,
            error = Palette.red,
        ),
        content = content,
    )
}
