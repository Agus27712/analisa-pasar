package agu.analys.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = TvGreen,
    onPrimary = TvBackground,
    primaryContainer = TvSurfaceVariant,
    onPrimaryContainer = TvTextPrimary,
    secondary = TvBlue,
    onSecondary = TvTextPrimary,
    tertiary = TvRed,
    background = TvBackground,
    onBackground = TvTextPrimary,
    surface = TvSurface,
    onSurface = TvTextPrimary,
    surfaceVariant = TvSurfaceVariant,
    onSurfaceVariant = TvTextSecondary,
    outline = TvBorder
)

@Composable
fun TradingViewAITheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
