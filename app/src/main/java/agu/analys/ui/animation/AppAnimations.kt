package agu.analys.ui.animation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import agu.analys.util.PriceFormatter
import kotlin.math.abs

/** Shared Compose animation helpers. Keep animation behavior consistent and lightweight. */
object AppAnimations {
    const val FAST_MS = 180
    const val NORMAL_MS = 280
    const val SLOW_MS = 420
    /** Deliberately visible live-price transition without making the UI feel slow. */
    const val PRICE_MS = 300
}

@Composable
fun FadeSlideIn(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(AppAnimations.NORMAL_MS)) +
            slideInVertically(tween(AppAnimations.NORMAL_MS)) { it / 8 },
        exit = fadeOut(tween(AppAnimations.FAST_MS)) +
            slideOutVertically(tween(AppAnimations.FAST_MS)) { it / 10 }
    ) { content() }
}

@Composable
fun rememberLivePulseAlpha(min: Float = 0.45f, max: Float = 1f): Float {
    val transition = rememberInfiniteTransition(label = "live_pulse")
    val alpha by transition.animateFloat(
        initialValue = min,
        targetValue = max,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_pulse_alpha"
    )
    return alpha
}

fun Modifier.livePulse(alpha: Float): Modifier =
    this.graphicsLayer { this.alpha = alpha }

/**
 * Smooth live price interpolation while retaining Double precision.
 * The animation uses a normalized Float progress only. The actual market value
 * is always calculated as Double, avoiding price precision loss.
 * A new target cancels the old animation and continues from the currently
 * displayed value, so frequent live refreshes do not make the number jump.
 */
@Composable
fun rememberSmoothPrice(target: Double, durationMs: Int = AppAnimations.PRICE_MS): Double {
    var displayed by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(target) {
        if (!target.isFinite() || target <= 0.0) return@LaunchedEffect

        val start = displayed
        if (!start.isFinite() || start <= 0.0) {
            displayed = target
            progress.snapTo(1f)
            return@LaunchedEffect
        }

        if (start == target) {
            displayed = target
            progress.snapTo(1f)
            return@LaunchedEffect
        }

        val relativeDelta = abs(target - start) / start
        if (relativeDelta > 0.25) {
            displayed = target
            progress.snapTo(1f)
            return@LaunchedEffect
        }

        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = durationMs.coerceIn(220, 420),
                easing = FastOutSlowInEasing
            )
        ) {
            val t = value.toDouble()
            displayed = start + (target - start) * t
        }
        displayed = target
    }

    return displayed
}

@Composable
fun SmoothPriceText(
    price: Double,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight = FontWeight.Bold,
    modifier: Modifier = Modifier,
    showSymbol: Boolean = true,
    maxLines: Int = 1
) {
    val smooth = rememberSmoothPrice(price)
    Text(
        text = PriceFormatter.formatPrice(smooth, showSymbol = showSymbol),
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        modifier = modifier,
        maxLines = maxLines
    )
}
