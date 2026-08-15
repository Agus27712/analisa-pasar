package agu.analys.ui.animation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Shared Compose animation helpers — feedback visual saja, bukan data palsu. */
object AppAnimations {
    const val FAST_MS = 180
    const val NORMAL_MS = 280
    const val SLOW_MS = 420
    /** Tick price: terasa bergerak, tetap cepat untuk scalper (~200–280ms). */
    const val PRICE_MS = 240
    /** Dashboard metrics: ringan agar live data terasa hidup tanpa mengganggu pembacaan. */
    const val METRIC_MS = 220
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
fun rememberLivePulseAlpha(min: Float = 0.55f, max: Float = 1f): Float {
    val transition = rememberInfiniteTransition(label = "live_pulse")
    val alpha by transition.animateFloat(
        initialValue = min,
        targetValue = max,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_pulse_alpha"
    )
    return alpha
}

fun Modifier.livePulse(alpha: Float): Modifier =
    this.graphicsLayer { this.alpha = alpha }

/**
 * Smooth live price: interpolasi dari nilai yang sedang tampil → target baru.
 * Tick baru saat animasi jalan: lanjut dari posisi visual saat itu (no teleport).
 * Jump relatif > 25% → snap.
 */
@Composable
fun rememberSmoothPrice(target: Double, durationMs: Int = AppAnimations.PRICE_MS): Double {
    val progress = remember { Animatable(1f) }
    var startPrice by remember { mutableStateOf(target) }

    val progressValue = progress.value.coerceIn(0f, 1f)
    val displayed = startPrice + (target - startPrice) * progressValue.toDouble()

    LaunchedEffect(target) {
        if (!target.isFinite() || target <= 0.0) return@LaunchedEffect

        val current = if (startPrice.isFinite() && startPrice > 0.0) {
            startPrice + (target - startPrice) * progress.value.toDouble()
        } else {
            target
        }

        if (!current.isFinite() || current <= 0.0) {
            startPrice = target
            progress.snapTo(1f)
            return@LaunchedEffect
        }

        if (current == target) {
            startPrice = target
            progress.snapTo(1f)
            return@LaunchedEffect
        }

        val relativeDelta = abs(target - current) / current
        if (relativeDelta > 0.25) {
            startPrice = target
            progress.snapTo(1f)
            return@LaunchedEffect
        }

        startPrice = current
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = durationMs.coerceIn(180, 320),
                easing = FastOutSlowInEasing
            )
        )
        startPrice = target
        progress.snapTo(1f)
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
    var pulse by remember { mutableStateOf(false) }
    // Pulse sangat subtle — terminal trading, bukan arcade
    val scale by animateFloatAsState(
        targetValue = if (pulse) 1.012f else 1f,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "price_change_scale"
    )

    LaunchedEffect(price) {
        if (!price.isFinite() || price <= 0.0) return@LaunchedEffect
        pulse = true
        delay(160)
        pulse = false
    }

    Text(
        text = PriceFormatter.formatPrice(smooth, showSymbol = showSymbol),
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        maxLines = maxLines
    )
}

/**
 * Lightweight live metric animation for counters/percentages/aggregates.
 * Uses Compose AnimatedContent, so no React or extra animation library is needed.
 */
@Composable
fun AnimatedMetricText(
    value: String,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight = FontWeight.Bold,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            fadeIn(tween(AppAnimations.METRIC_MS)) togetherWith
                fadeOut(tween(AppAnimations.FAST_MS))
        },
        label = "live_metric"
    ) { animatedValue ->
        Text(
            text = animatedValue,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = maxLines
        )
    }
}
