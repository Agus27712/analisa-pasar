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
    modifier: Modifier = Modifier
) {
    // Transisi warna halus saat status berpindah antara Online & Offline
    val targetDotColor = if (isOnline) Color(0xFF00E676) else Color(0xFFFF5252)
    val animatedDotColor by animateColorAsState(
        targetValue = targetDotColor,
        animationSpec = tween(durationMillis = 600, easing = LinearOutSlowInEasing),
        label = "dotColor"
    )

    // Animasi pulse (denyut) halus pada titik indikator
    val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    // Indikator MURNI TITIK saja (tanpa kotak background / border)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(12.dp)
    ) {
        // Lingkaran luar berdenyut (pulse aura)
        Box(
            modifier = Modifier
                .size(9.5.dp)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                    alpha = pulseAlpha
                }
                .background(animatedDotColor, CircleShape)
        )
        // Inti titik utama (solid dot)
        Box(
            modifier = Modifier
                .size(7.5.dp)
                .background(animatedDotColor, CircleShape)
        )
    }
}

