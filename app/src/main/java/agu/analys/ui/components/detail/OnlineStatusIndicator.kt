package agu.analys.ui.components.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun OnlineStatusIndicator(
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    sizeDp: Int = 10
) {
    // LED Status Indicator: Kaku & Solid seperti lampu LED fisik (tanpa denyut / pulse)
    val ledColor = if (isOnline) Color(0xFF00E676) else Color(0xFFFF3B30)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(sizeDp.dp)
    ) {
        // Lingkaran luar border tipis LED
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .background(ledColor.copy(alpha = 0.25f), CircleShape)
        )
        // Inti lampu LED padat / solid
        Box(
            modifier = Modifier
                .size((sizeDp - 2.5).coerceAtLeast(5.0).dp)
                .background(ledColor, CircleShape)
        )
    }
}

