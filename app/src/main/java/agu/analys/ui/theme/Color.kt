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
    val border: Color,
    val green: Color,
    val greenLight: Color,
    val red: Color,
    val redLight: Color,
    val blue: Color,
    val amber: Color,
    val orange: Color
)

val DarkAppColors = AppColors(
    background = Color(0xFF12161C),       // Dark Canvas Utama (Tokocrypto style)
    surface = Color(0xFF181A20),          // Surface & Kartu
    surfaceVariant = Color(0xFF2B313A),   // Surface Sekunder
    cardBackground = Color(0xFF181A20),
    textPrimary = Color(0xFFEAECF0),       // Teks Utama Kontras Tinggi
    textSecondary = Color(0xFF848E9C),     // Teks Sekunder
    border = Color(0xFF2B313A),           // Border Pembatas
    green = Color(0xFF0ECB81),            // Bullish Green
    greenLight = Color(0xFF00C076),
    red = Color(0xFFF6465D),              // Bearish Red
    redLight = Color(0xFFFF5364),
    blue = Color(0xFF90CAF9),             // Pastel Blue (Aksen Terang di Dark Mode)
    amber = Color(0xFFFCD535),
    orange = Color(0xFFF0B90B)
)

val LightAppColors = AppColors(
    background = Color(0xFFF5F6FA),       // Light Canvas Utama (Soft Off-White)
    surface = Color(0xFFFFFFFF),          // Surface & Kartu Terang
    surfaceVariant = Color(0xFFEAECEF),   // Surface Sekunder
    cardBackground = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF1E2329),       // Teks Utama Gelap (Kontras Tinggi)
    textSecondary = Color(0xFF707A8A),     // Teks Sekunder Gray
    border = Color(0xFFE6E8EA),           // Border Pembatas Light
    green = Color(0xFF00B074),            // Bullish Green High Contrast
    greenLight = Color(0xFF00C076),
    red = Color(0xFFE03A50),              // Bearish Red High Contrast
    redLight = Color(0xFFFF5364),
    blue = Color(0xFF1565C0),             // Darker Pastel Blue (Aksen Terbaca di Light Mode)
    amber = Color(0xFFD4AF37),
    orange = Color(0xFFE0A000)
)

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

val TvBackground: Color @Composable get() = LocalAppColors.current.background
val TvSurface: Color @Composable get() = LocalAppColors.current.surface
val TvSurfaceVariant: Color @Composable get() = LocalAppColors.current.surfaceVariant
val TvCardBackground: Color @Composable get() = LocalAppColors.current.cardBackground

val TvTextPrimary: Color @Composable get() = LocalAppColors.current.textPrimary
val TvTextSecondary: Color @Composable get() = LocalAppColors.current.textSecondary
val TvBorder: Color @Composable get() = LocalAppColors.current.border

val TvGreen: Color @Composable get() = LocalAppColors.current.green
val TvGreenLight: Color @Composable get() = LocalAppColors.current.greenLight
val TvRed: Color @Composable get() = LocalAppColors.current.red
val TvRedLight: Color @Composable get() = LocalAppColors.current.redLight
val TvBlue: Color @Composable get() = LocalAppColors.current.blue
val TvAmber: Color @Composable get() = LocalAppColors.current.amber
val TvOrange: Color @Composable get() = LocalAppColors.current.orange
