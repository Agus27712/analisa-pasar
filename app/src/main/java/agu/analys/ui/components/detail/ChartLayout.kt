package agu.analys.ui.components.detail

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single source of truth untuk tinggi chart portrait.
 * Lebih lega dari baseline awal (~300→360), tapi proporsional HP
 * agar card Kondisi/Progress tidak terdorong jauh ke bawah.
 * Fullscreen/landscape tetap fillMaxSize di screen masing-masing.
 */
object ChartLayoutDefaults {
    /** Portrait detail — lega tapi tidak mendominasi scroll. */
    val PortraitHeight: Dp = 280.dp
}

@Composable
fun ChartLayout(
    modifier: Modifier = Modifier,
    height: Dp = ChartLayoutDefaults.PortraitHeight,
    content: @Composable (Modifier) -> Unit
) {
    content(
        modifier
            .fillMaxWidth()
            .height(height)
    )
}
