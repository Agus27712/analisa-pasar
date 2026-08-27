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

/** Dark: navy-blue canvas ala Indodax, aksen cyan/blue, bukan pure black. */
val DarkAppColors = AppColors(
    background = Color(0xFF0B1220),
    surface = Color(0xFF121A2B),
    surfaceVariant = Color(0xFF1A2438),
    cardBackground = Color(0xFF151E30),
    textPrimary = Color(0xFFE8EEF8),
    textSecondary = Color(0xFF8B9BB5),
    textMuted = Color(0xFF5C6B84),
    border = Color(0xFF243047),
    green = Color(0xFF22C55E),
    greenLight = Color(0xFF4ADE80),
    red = Color(0xFFEF4444),
    redLight = Color(0xFFF87171),
    blue = Color(0xFF3B82F6),
    blueSoft = Color(0xFF60A5FA),
    amber = Color(0xFFFBBF24),
    orange = Color(0xFFF59E0B)
)

/** Light: putih kebiruan, teks gelap kontras tinggi (hindari gray pudar). */
val LightAppColors = AppColors(
    background = Color(0xFFF0F4FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE4EBF5),
    cardBackground = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF64748B),
    border = Color(0xFFCBD5E1),
    green = Color(0xFF059669),
    greenLight = Color(0xFF10B981),
    red = Color(0xFFDC2626),
    redLight = Color(0xFFEF4444),
    blue = Color(0xFF1D4ED8),
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
