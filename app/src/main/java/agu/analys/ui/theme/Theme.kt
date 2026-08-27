package agu.analys.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun TradingViewAITheme(
    isDarkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val appColors = if (isDarkTheme) DarkAppColors else LightAppColors
    
    val colorScheme = if (isDarkTheme) {
        darkColorScheme(
            primary = appColors.green,
            onPrimary = appColors.background,
            primaryContainer = appColors.surfaceVariant,
            onPrimaryContainer = appColors.textPrimary,
            secondary = appColors.blue,
            onSecondary = appColors.textPrimary,
            tertiary = appColors.red,
            background = appColors.background,
            onBackground = appColors.textPrimary,
            surface = appColors.surface,
            onSurface = appColors.textPrimary,
            surfaceVariant = appColors.surfaceVariant,
            onSurfaceVariant = appColors.textSecondary,
            outline = appColors.border
        )
    } else {
        lightColorScheme(
            primary = appColors.green,
            onPrimary = appColors.background,
            primaryContainer = appColors.surfaceVariant,
            onPrimaryContainer = appColors.textPrimary,
            secondary = appColors.blue,
            onSecondary = appColors.textPrimary,
            tertiary = appColors.red,
            background = appColors.background,
            onBackground = appColors.textPrimary,
            surface = appColors.surface,
            onSurface = appColors.textPrimary,
            surfaceVariant = appColors.surfaceVariant,
            onSurfaceVariant = appColors.textSecondary,
            outline = appColors.border
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDarkTheme
                insetsController.isAppearanceLightNavigationBars = !isDarkTheme
                window.statusBarColor = appColors.background.toArgb()
                window.navigationBarColor = appColors.surface.toArgb()
            }
        }
    }

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
