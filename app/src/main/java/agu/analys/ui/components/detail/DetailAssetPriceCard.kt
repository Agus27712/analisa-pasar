package agu.analys.ui.components.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.MarketTick
import agu.analys.ui.animation.FlipCardPriceText
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Card Harga Aset di Detail Screen dengan FlipCard Price Effect.
 * - Angka aset bertransisi dengan efek flip 3D halus saat update harga.
 * - Tidak looping (single animation cycle).
 * - Tidak loncat/jitter kecuali ada perubahan harga ekstrem (>25%).
 */
@Composable
fun DetailAssetPriceCard(
    tick: MarketTick?,
    modifier: Modifier = Modifier
) {
    if (tick == null) return

    val price = tick.price
    val change = tick.change24h
    val changeColor = if (change >= 0) TvGreen else TvRed

    var previousPrice by remember { mutableStateOf(price) }
    var flashState by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(price) {
        if (!price.isFinite() || price <= 0.0) return@LaunchedEffect
        if (price != previousPrice) {
            val delta = price - previousPrice
            val rel = if (previousPrice > 0.0) abs(delta) / previousPrice else 0.0
            if (rel <= 0.25) {
                // Subtle flash border on regular tick
                flashState = if (delta > 0) TvGreen.copy(alpha = 0.4f) else TvRed.copy(alpha = 0.4f)
                delay(220)
                flashState = null
            }
            previousPrice = price
        }
    }

    val animatedBorderColor by animateColorAsState(
        targetValue = flashState ?: Color(0xFF1E2D3D),
        animationSpec = tween(durationMillis = 200),
        label = "price_card_border_glow"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101720)),
        border = BorderStroke(1.dp, animatedBorderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HARGA ASET",
                    color = TvTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp
                )
                if (tick.volume24h > 0) {
                    Text(
                        text = "Vol 24H: ${PriceFormatter.formatPrice(tick.volume24h)}",
                        color = TvTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // FlipCard display plate
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF141E2A), Color(0xFF0C131B))
                        )
                    )
                    .border(0.5.dp, Color(0x3344607A), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column {
                    FlipCardPriceText(
                        price = price,
                        color = TvTextPrimary,
                        fontSize = 29.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.testTag("live_price_header")
                    )

                    // Subtle horizontal split indicator for realistic flipboard feel
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(Color(0x1AFFFFFF))
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(changeColor.copy(alpha = 0.15f))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = PriceFormatter.formatPercentage(change),
                            color = changeColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "(24 jam)",
                        color = TvTextSecondary,
                        fontSize = 12.sp
                    )
                }

                if (tick.low24h > 0 && tick.high24h > 0) {
                    Text(
                        text = "L: ${PriceFormatter.formatPrice(tick.low24h)} · H: ${PriceFormatter.formatPrice(tick.high24h)}",
                        color = TvTextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
