package agu.analys.ui.animation

import androidx.compose.animation.AnimatedVisibility
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

/** Shared Compose animation helpers — keep screens free of animation boilerplate. */
object AppAnimations {
    const val FAST_MS = 180
    const val NORMAL_MS = 280
    const val SLOW_MS = 420
    /** Live price transition: short, soft, no bounce. */
    const val PRICE_MS = 160
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
 * Smooth live price interpolation without converting the actual price to Float.
 * Only the visual value is animated. Market data remains the real Double value.
 * The animation is keyed only by target, so ordinary recomposition does not restart it.
 */
@Composable
fun rememberSmoothPrice(target: Double, durationMs: Int = AppAnimations.PRICE_MS): Double {
    var displayed by remember { mutableStateOf(target) }

    LaunchedEffect(target) {
        if (target <= 0.0 || target.isNaN() || target.isInfinite()) return@LaunchedEffect

        val start = displayed
        if (start <= 0.0 || !start.isFinite()) {
            displayed = target
            return@LaunchedEffect
        }

        val relativeDelta = abs(target - start) / start
        if (relativeDelta > 0.25) {
            displayed = target
            return@LaunchedEffect
        }

        val steps = 8
        val stepDelay = (durationMs.toLong() / steps).coerceAtLeast(1L)
        repeat(steps) { index ->
            val t = (index + 1).toDouble() / steps.toDouble()
            val eased = t * t * (3.0 - 2.0 * t)
            displayed = start + (target - start) * eased
            kotlinx.coroutines.delay(stepDelay)
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
