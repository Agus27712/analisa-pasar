package agu.analys.ui.components.detail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Compact portrait chart boundary so the market-condition and entry cards remain
 * visible without sacrificing the larger chart introduced by the UI refactor.
 * Rendering stays in SimpleComposeChart; engine stays untouched.
 */
@Composable
fun ChartLayout(
    modifier: Modifier = Modifier,
    height: Dp = 300.dp,
    content: @Composable (Modifier) -> Unit
) {
    content(
        modifier
            .fillMaxWidth()
            .height(height)
    )
}
