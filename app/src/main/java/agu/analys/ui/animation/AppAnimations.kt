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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import agu.analys.util.PriceFormatter

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
    ) {
        content()
    }
}

/** Soft pulse for live/connection indicators. */
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
 * Smooth live price value. Interpolates toward the real target only.
 * Duration ~160ms, FastOutSlowIn — no bounce / flash / restart flicker.
 * Returns the animated Double to feed into formatters or Canvas Y calc.
 */
@Composable
fun rememberSmoothPrice(target: Double, durationMs: Int = AppAnimations.PRICE_MS): Double {
    val anim = remember { Animatable(target.toFloat()) }
    LaunchedEffect(target) {
        if (target <= 0.0 || target.isNaN() || target.isInfinite()) {
            anim.snapTo(0f)
            return@LaunchedEffect
        }
        // Skip huge jumps (pair switch) — snap instead of long tween
        val current = anim.value.toDouble()
        val rel = if (current > 0) kotlin.math.abs(target - current) / current else 1.0
        if (rel > 0.25) {
            anim.snapTo(target.toFloat())
        } else {
            anim.animateTo(
                targetValue = target.toFloat(),
                animationSpec = tween(durationMs, easing = FastOutSlowInEasing)
            )
        }
    }
    return anim.value.toDouble()
}

/** Convenience Text that shows a smoothly animated formatted price. */
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
