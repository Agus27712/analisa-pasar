package agu.analys.ui.components.detail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout boundary for the detail-screen chart.
 * Height aligned with chart layout pass (~360dp).
 * Rendering stays in SimpleComposeChart; engine stays untouched.
 */
@Composable
fun ChartLayout(
    modifier: Modifier = Modifier,
    height: Dp = 360.dp,
    content: @Composable (Modifier) -> Unit
) {
    content(
        modifier
            .fillMaxWidth()
            .height(height)
    )
}
