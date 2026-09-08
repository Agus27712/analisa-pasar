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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Animasi UI — handover: perubahan kecil ~160ms, ekstrem >25% snap.
 * Tidak mengubah nilai data Indodax.
 */
object AppAnimations {
    const val FAST_MS = 140
    const val NORMAL_MS = 220
    const val SLOW_MS = 360
    /** Handover: smooth ~160ms untuk tick harga. */
    const val PRICE_MS = 160
    const val METRIC_MS = 160
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
 * Smooth live price: dari nilai tampil → target.
 * Tick baru saat animasi jalan: lanjut dari posisi visual (no teleport).
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
                durationMillis = durationMs.coerceIn(140, 200),
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
    quoteAsset: String = "IDR",
    maxLines: Int = 1
) {
    val smooth = rememberSmoothPrice(price)

    Text(
        text = PriceFormatter.formatPrice(smooth, showSymbol = showSymbol, quoteAsset = quoteAsset),
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        modifier = modifier,
        maxLines = maxLines
    )
}

/**
 * 3D FlipCard Price Text — Animasi flip per angka (per-digit flip).
 * - Setiap digit memiliki lempengan flip mandiri.
 * - Saat digit berubah (misal dari 5 ke 8), hanya digit tersebut yang berputar 3D
 *   (ke atas jika harga naik, ke bawah jika turun).
 * - Karakter statis (Rp, $, titik, koma, spasi) dan digit yang tidak berubah tetap kokoh tanpa jitter.
 * - Loncat langsung (snap) jika terjadi pergerakan ekstrem > 25%.
 */
@Composable
fun FlipCardPriceText(
    price: Double,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight = FontWeight.Bold,
    modifier: Modifier = Modifier,
    showSymbol: Boolean = true,
    quoteAsset: String = "IDR",
    maxLines: Int = 1
) {
    if (!price.isFinite() || price <= 0.0) {
        val placeholder = if (quoteAsset.equals("USDT", true) || quoteAsset.equals("USD", true)) "$ —" else "Rp —"
        Text(
            text = placeholder,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            modifier = modifier,
            maxLines = maxLines
        )
        return
    }

    var previousPrice by remember { mutableDoubleStateOf(price) }
    var isUp by remember { mutableStateOf(true) }

    LaunchedEffect(price) {
        if (price.isFinite() && price > 0.0) {
            if (previousPrice.isFinite() && previousPrice > 0.0 && price != previousPrice) {
                val relativeDelta = abs(price - previousPrice) / previousPrice
                // Jika perubahan ekstrem (>25%), langsung update tanpa flip bertahap
                if (relativeDelta <= 0.25) {
                    isUp = price >= previousPrice
                }
            }
            previousPrice = price
        }
    }

    val formatted = remember(price, showSymbol, quoteAsset) {
        PriceFormatter.formatPrice(price, showSymbol, quoteAsset)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in formatted.indices) {
            val char = formatted[i]
            // Posisi dihitung dari kanan agar angka tetap sinkron nilainya saat digit bertambah
            val indexFromRight = formatted.length - 1 - i
            key(indexFromRight) {
                if (!char.isDigit()) {
                    Text(
                        text = char.toString(),
                        color = color,
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        maxLines = 1
                    )
                } else {
                    DigitFlipCell(
                        digit = char,
                        isUp = isUp,
                        color = color,
                        fontSize = fontSize,
                        fontWeight = fontWeight
                    )
                }
            }
        }
    }
}

@Composable
private fun DigitFlipCell(
    digit: Char,
    isUp: Boolean,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight
) {
    var previousDigit by remember { mutableStateOf(digit) }
    var currentDigit by remember { mutableStateOf(digit) }
    val flipProgress = remember { Animatable(1f) }

    LaunchedEffect(digit, isUp) {
        if (digit != currentDigit) {
            previousDigit = currentDigit
            currentDigit = digit
            flipProgress.snapTo(0f)
            flipProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 200,
                    easing = FastOutSlowInEasing
                )
            )
        }
    }

    val progress = flipProgress.value.coerceIn(0f, 1f)
    val isFirstHalf = progress < 0.5f
    val displayChar = if (isFirstHalf) previousDigit else currentDigit

    // 3D rotation around X axis (flip per digit): naik = putar ke atas, turun = putar ke bawah
    val rotationX = if (isFirstHalf) {
        val frac = progress * 2f // 0f -> 1f
        if (isUp) -frac * 90f else frac * 90f
    } else {
        val frac = (progress - 0.5f) * 2f // 0f -> 1f
        if (isUp) 90f * (1f - frac) else -90f * (1f - frac)
    }

    val alpha = if (isFirstHalf) {
        1f - (progress * 0.35f)
    } else {
        0.65f + ((progress - 0.5f) * 0.7f)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.graphicsLayer {
            this.rotationX = rotationX
            this.alpha = alpha.coerceIn(0f, 1f)
            this.cameraDistance = 16f * density
        }
    ) {
        Text(
            text = displayChar.toString(),
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = 1
        )
    }
}

@Composable
fun AnimatedPercentageBadge(
    percentage: Double,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    fontWeight: FontWeight = FontWeight.Bold
) {
    val isPositive = percentage >= 0
    val color = if (isPositive) TvGreen else TvRed
    val formatted = PriceFormatter.formatPercentage(percentage)

    Box(modifier = modifier) {
        AnimatedContent(
            targetState = formatted,
            transitionSpec = {
                (slideInVertically(tween(AppAnimations.FAST_MS)) { height -> if (isPositive) -height / 2 else height / 2 } + fadeIn(tween(AppAnimations.FAST_MS)))
                    .togetherWith(slideOutVertically(tween(AppAnimations.FAST_MS)) { height -> if (isPositive) height / 2 else -height / 2 } + fadeOut(tween(AppAnimations.FAST_MS)))
            },
            label = "animated_percentage"
        ) { text ->
            Text(
                text = text,
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                maxLines = 1
            )
        }
    }
}

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
