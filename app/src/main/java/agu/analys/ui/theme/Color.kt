package agu.analys.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val cardBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val border: Color,
    val green: Color,
    val greenLight: Color,
    val red: Color,
    val redLight: Color,
    val blue: Color,
    val blueSoft: Color,
    val amber: Color,
    val orange: Color
)

/** Dark: navy-blue slate canvas ala Indodax / TradingView Dark, high-contrast crisp text & vibrant accents. */
val DarkAppColors = AppColors(
    background = Color(0xFF0B1220),
    surface = Color(0xFF111A2E),
    surfaceVariant = Color(0xFF172239),
    cardBackground = Color(0xFF111A2E),
    textPrimary = Color(0xFFF1F5F9),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    border = Color(0xFF1E2D4A),
    green = Color(0xFF10B981),
    greenLight = Color(0xFF34D399),
    red = Color(0xFFEF4444),
    redLight = Color(0xFFF87171),
    blue = Color(0xFF3B82F6),
    blueSoft = Color(0xFF60A5FA),
    amber = Color(0xFFF59E0B),
    orange = Color(0xFFFB923C)
)

/** Light: clean slate-50 canvas, pure white cards, soft border & high-contrast deep slate text. */
val LightAppColors = AppColors(
    background = Color(0xFFF1F5F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF8FAFC),
    cardBackground = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF64748B),
    border = Color(0xFFE2E8F0),
    green = Color(0xFF059669),
    greenLight = Color(0xFF10B981),
    red = Color(0xFFDC2626),
    redLight = Color(0xFFEF4444),
    blue = Color(0xFF2563EB),
    blueSoft = Color(0xFF3B82F6),
    amber = Color(0xFFD97706),
    orange = Color(0xFFEA580C)
)

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

val TvBackground: Color @Composable get() = LocalAppColors.current.background
val TvSurface: Color @Composable get() = LocalAppColors.current.surface
val TvSurfaceVariant: Color @Composable get() = LocalAppColors.current.surfaceVariant
val TvCardBackground: Color @Composable get() = LocalAppColors.current.cardBackground

val TvTextPrimary: Color @Composable get() = LocalAppColors.current.textPrimary
val TvTextSecondary: Color @Composable get() = LocalAppColors.current.textSecondary
val TvTextMuted: Color @Composable get() = LocalAppColors.current.textMuted
val TvBorder: Color @Composable get() = LocalAppColors.current.border

val TvGreen: Color @Composable get() = LocalAppColors.current.green
val TvGreenLight: Color @Composable get() = LocalAppColors.current.greenLight
val TvRed: Color @Composable get() = LocalAppColors.current.red
val TvRedLight: Color @Composable get() = LocalAppColors.current.redLight
val TvBlue: Color @Composable get() = LocalAppColors.current.blue
val TvBlueSoft: Color @Composable get() = LocalAppColors.current.blueSoft
val TvAmber: Color @Composable get() = LocalAppColors.current.amber
val TvOrange: Color @Composable get() = LocalAppColors.current.orange
