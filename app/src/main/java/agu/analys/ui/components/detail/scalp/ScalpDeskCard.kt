package agu.analys.ui.components.detail.scalp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.scalping.PairEdgeGate
import agu.analys.engine.scalping.ScalpReadiness
import agu.analys.engine.scalping.ScalpReadinessState
import agu.analys.engine.scalping.ScalpingRuntimeConfig
import agu.analys.model.AISignalState
import agu.analys.ui.components.detail.AnalysisCard
import agu.analys.ui.components.detail.AnalysisDivider
import agu.analys.ui.components.detail.SectionTitle
import agu.analys.ui.components.detail.radar.RadarChecklistItem
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter

/**
 * **SCALP DESK** — meja kerja scalping untuk halaman detail.
 *
 * Menggantikan blok analisa radar lama **hanya saat mode scalping**, karena blok lama
 * menampilkan status dari `lifecycleState` yang bisa berkata "TERDETEKSI" padahal
 * `scalpingSetup == "NONE"` (lihat [ScalpReadiness] untuk rantai penyebabnya).
 *
 * Semua angka di kartu ini berasal dari engine ([AISignalState]) — kartu tidak menghitung
 * ulang threshold apa pun.
 */
@Composable
fun ScalpDeskCard(
    symbol: String,
    signal: AISignalState,
    currentPrice: Double,
    quoteAsset: String,
    exchange: String,
    fees: TradingFeeConfig = TradingFeeConfig(),
    runtimeConfig: ScalpingRuntimeConfig = ScalpingRuntimeConfig.DEFAULT,
    hasPaperPosition: Boolean = false,
    modifier: Modifier = Modifier
) {
    val snapshot = remember(signal, hasPaperPosition) {
        ScalpReadiness.resolve(signal, hasPaperPosition = hasPaperPosition)
    }
    val mtf = signal.mtf

    val symbolKey = symbol
    val frictionPct = remember(fees, runtimeConfig.slippagePct) {
        PairEdgeGate.frictionRoundTripPct(fees.buyTakerPct, fees.sellTakerPct, runtimeConfig.slippagePct)
    }
    val frictionFloorPct = runtimeConfig.minFrictionMultiple * frictionPct
    val edge = remember(symbolKey, quoteAsset, frictionPct) {
        if (symbolKey.isBlank()) null else PairEdgeGate.evaluate(symbolKey, quoteAsset, frictionPct)
    }

    val stateColor = when (snapshot.state) {
        ScalpReadinessState.READY, ScalpReadinessState.HOLDING -> TvGreen
        ScalpReadinessState.CONFIRMING -> TvAmber
        ScalpReadinessState.WATCHING -> TvBlue
        ScalpReadinessState.BLOCKED -> TvRed
    }

    val passed = snapshot.checkpoints.count { it.ok }

    Column(modifier = modifier.fillMaxWidth()) {
        AnalysisCard {
            SectionTitle("SCALP DESK", Icons.Default.Radar)

            Spacer(Modifier.height(9.dp))

            // ── Status terpadu ──────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(stateColor, CircleShape)
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    snapshot.state.label,
                    color = stateColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.weight(1f))
                if (hasPaperPosition) {
                    MiniChip("SIMULASI", TvCyan)
                }
            }

            Spacer(Modifier.height(7.dp))
            Text(
                snapshot.headline,
                color = TvTextPrimary,
                fontSize = 13.5.sp,
                lineHeight = 19.sp
            )

            snapshot.blocker?.let { blocker ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = TvAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Penghalang: $blocker",
                        color = TvAmber,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            AnalysisDivider()
            Spacer(Modifier.height(10.dp))

            // ── Checklist 5 checkpoint ──────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "CHECKPOINT",
                    color = TvTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.3.sp
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "$passed/${snapshot.checkpoints.size} lolos",
                    color = if (passed == snapshot.checkpoints.size) TvGreen else TvTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(7.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TvSurfaceVariant, RoundedCornerShape(10.dp))
                    .border(1.dp, TvBorder, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                snapshot.checkpoints.forEach { cp ->
                    RadarChecklistItem(
                        stepNumber = cp.index,
                        label = "${cp.index}. ${cp.label}",
                        isOk = cp.ok,
                        detail = cp.detail
                    )
                }
            }

            if (mtf.statusTitle.isNotBlank() || mtf.waitingFor.isNotBlank()) {
                Spacer(Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Menunggu: ${mtf.waitingFor.ifBlank { "—" }}",
                        color = TvTextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (mtf.statusTitle.isNotBlank()) {
                        MiniChip(mtf.statusTitle, TvTextSecondary)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            AnalysisDivider()
            Spacer(Modifier.height(10.dp))

            // ── Rencana risiko ──────────────────────────────────────────────
            Text(
                "RENCANA RISIKO",
                color = TvTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp
            )
            Spacer(Modifier.height(7.dp))

            RiskRow(
                label = "Zona entry",
                value = if (signal.entryZoneLow > 0.0 && signal.entryZoneHigh > 0.0) {
                    "${PriceFormatter.formatPrice(signal.entryZoneLow, showSymbol = false, quoteAsset = quoteAsset)} – " +
                        PriceFormatter.formatPrice(signal.entryZoneHigh, quoteAsset = quoteAsset)
                } else {
                    "Menunggu setup"
                }
            )
            RiskRow("Stop loss", priceOrDash(signal.stopLoss, quoteAsset))
            if (signal.stopLoss > 0.0 && currentPrice > 0.0) {
                val distPct = (currentPrice - signal.stopLoss) / currentPrice * 100.0
                RiskRow(
                    label = "Jarak harga ke SL",
                    value = "${fmt(distPct)}%",
                    valueColor = if (distPct > 0.0) TvTextPrimary else TvRed
                )
            }
            RiskRow("TP1", priceOrDash(signal.targetPrice1, quoteAsset))
            RiskRow("TP2", priceOrDash(signal.targetPrice2, quoteAsset))
            RiskRow("Net R:R", signal.riskRewardRatio.ifBlank { "—" })
            RiskRow(
                label = "Lantai friksi",
                value = if (runtimeConfig.minFrictionMultiple <= 0.0) {
                    "Nonaktif"
                } else {
                    "${fmt(runtimeConfig.minFrictionMultiple)}× friksi = ${fmt(frictionFloorPct)}%"
                },
                valueColor = if (runtimeConfig.minFrictionMultiple <= 0.0) TvAmber else TvTextPrimary
            )
            RiskRow(
                label = "Friksi round-trip",
                value = "${fmt(frictionPct)}% (fee + slippage)"
            )

            // ── Kelayakan pair ─────────────────────────────────────────────
            edge?.let { e ->
                Spacer(Modifier.height(10.dp))
                AnalysisDivider()
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (e.eligible == true) TvGreen else TvRed,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        if (e.eligible == true) "PAIR LAYAK DI-SCALP" else "PAIR TIDAK LAYAK",
                        color = if (e.eligible == true) TvGreen else TvRed,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${e.ratioText} friksi",
                        color = TvTextSecondary,
                        fontSize = 10.5.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    e.reason,
                    color = TvTextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }

            // ── Skor & regime ──────────────────────────────────────────────
            if (signal.scalpingScore > 0) {
                Spacer(Modifier.height(10.dp))
                AnalysisDivider()
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Skor ${signal.scalpingScore}/100",
                        color = scoreColor(signal.scalpingScoreCategory),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                    MiniChip(
                        signal.scalpingScoreCategory.ifBlank { "—" },
                        scoreColor(signal.scalpingScoreCategory)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        listOf(signal.scalpingRegime, signal.scalpingDirection)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        color = TvTextSecondary,
                        fontSize = 10.5.sp
                    )
                }
                if (signal.scalpingScoreDetail.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        signal.scalpingScoreDetail,
                        color = TvTextMuted,
                        fontSize = 9.5.sp,
                        lineHeight = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun RiskRow(label: String, value: String, valueColor: Color = TvTextPrimary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = TvTextSecondary,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            color = valueColor,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MiniChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun scoreColor(category: String): Color = when (category.uppercase()) {
    "VERY_STRONG", "STRONG" -> TvGreen
    "WATCH" -> TvAmber
    "WEAK" -> TvOrange
    else -> TvTextMuted
}

private fun priceOrDash(price: Double, quoteAsset: String): String =
    if (price > 0.0 && price.isFinite()) {
        PriceFormatter.formatPrice(price, quoteAsset = quoteAsset)
    } else {
        "—"
    }

private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
