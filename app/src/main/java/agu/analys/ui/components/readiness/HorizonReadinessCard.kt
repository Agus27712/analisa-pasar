package agu.analys.ui.components.readiness

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.readiness.HorizonReadiness
import agu.analys.engine.readiness.ReadinessState
import agu.analys.engine.readiness.TradeHorizon
import agu.analys.ui.components.detail.AnalysisCard
import agu.analys.ui.components.detail.AnalysisDivider
import agu.analys.ui.components.detail.SectionTitle
import agu.analys.ui.theme.TvBorder
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvSurfaceVariant
import agu.analys.ui.theme.TvTextMuted
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter

/**
 * **KESIAPAN HORIZON** — kartu untuk halaman detail koin.
 *
 * Menjawab langsung pertanyaan "koin ini siap untuk apa?" dengan menampilkan tiga horizon
 * berdampingan. Ditempatkan berdampingan (bukan bertumpuk) supaya pengguna bisa membandingkan:
 * koin yang sama bisa `SIAP` untuk swing tetapi `PANTAU` untuk scalp, dan itu memang informasi
 * yang berguna, bukan kontradiksi.
 *
 * @param rows baris kesiapan untuk koin ini (biasanya 3: scalp/intraday/swing)
 * @param loading sedang menghitung
 * @param onRequestData minta pengambilan candle untuk timeframe yang belum ada
 */
@Composable
fun SymbolReadinessCard(
    symbol: String,
    rows: List<HorizonReadiness>,
    quoteAsset: String = "IDR",
    loading: Boolean = false,
    onRequestData: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val ordered = remember(rows) {
        TradeHorizon.entries.mapNotNull { h -> rows.firstOrNull { it.horizon == h } }
    }
    val missing = ordered.filter { it.state == ReadinessState.NO_DATA }

    AnalysisCard(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle("KESIAPAN HORIZON", Icons.Default.Info)
            if (loading) {
                Text("menghitung…", color = TvTextMuted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Satu koin bisa siap untuk satu horizon dan belum siap untuk horizon lain — " +
                "biaya transaksi menentukan, bukan hanya arah tren.",
            color = TvTextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
        Spacer(Modifier.height(10.dp))

        if (ordered.isEmpty()) {
            Text(
                text = "Data kesiapan belum tersedia untuk $symbol.",
                color = TvTextSecondary,
                fontSize = 12.sp
            )
            return@AnalysisCard
        }

        ordered.forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            HorizonReadinessRow(row = row, quoteAsset = quoteAsset)
        }

        if (missing.isNotEmpty() && onRequestData != null) {
            Spacer(Modifier.height(10.dp))
            AnalysisDivider()
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TvSurfaceVariant.copy(alpha = 0.6f))
                    .clickable { onRequestData() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Sync, null, tint = TvTextSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Ambil data candle untuk ${missing.size} horizon yang belum siap",
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/** Satu horizon: status, skor, kesimpulan, dan (bila dibuka) syarat + rencana trade. */
@Composable
fun HorizonReadinessRow(
    row: HorizonReadiness,
    quoteAsset: String = "IDR",
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    var expanded by remember(row.symbol, row.horizon, row.state) { mutableStateOf(initiallyExpanded) }
    val color = readinessStateColor(row.state)

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.06f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(horizonIcon(row.horizon), null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = row.horizon.label.uppercase(),
                color = TvTextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp
            )
            Spacer(Modifier.width(6.dp))
            Text(row.horizon.holdingWindow, color = TvTextMuted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            ReadinessStateChip(row.state)
        }

        Spacer(Modifier.height(6.dp))
        ReadinessScoreBar(score = row.score, color = color)

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.headline,
                color = TvTextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${row.passedChecks}/${row.totalChecks} · ${row.score}",
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (!row.horizon.validatedOutOfSample) {
            Spacer(Modifier.height(5.dp))
            UnvalidatedBadge()
        }

        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                AnalysisDivider()
                Spacer(Modifier.height(6.dp))
                row.checks.forEach { ReadinessCheckRow(it) }

                row.plan?.let { plan ->
                    Spacer(Modifier.height(8.dp))
                    AnalysisDivider()
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "RENCANA TRADE (LONG)",
                        color = TvTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    PlanRow("Entry", PriceFormatter.formatPriceFull(plan.entry, quoteAsset))
                    PlanRow(
                        "Stop loss",
                        "${PriceFormatter.formatPriceFull(plan.stopLoss, quoteAsset)}  (−${fmtPct(plan.riskPct)}%)",
                        TvRed
                    )
                    PlanRow("TP1", PriceFormatter.formatPriceFull(plan.takeProfit1, quoteAsset))
                    PlanRow(
                        "TP2",
                        "${PriceFormatter.formatPriceFull(plan.takeProfit2, quoteAsset)}  (+${fmtPct(plan.rewardPct)}%)",
                        TvGreen
                    )
                    PlanRow("Net R:R (setelah fee)", fmt(plan.netRr) + "×", TvGreen)
                    PlanRow("Lantai friksi", fmtPct(plan.frictionFloorPct) + "%")
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(TvBorder.copy(alpha = 0.35f))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = plan.exitRule,
                            color = TvTextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }

                if (row.state == ReadinessState.NO_DATA && row.blocker != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(row.blocker, color = TvTextMuted, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
    }
}

private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

private fun fmtPct(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
