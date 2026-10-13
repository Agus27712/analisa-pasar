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
import agu.analys.engine.readiness.ReadinessSource
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
 * Menjawab "koin ini siap untuk apa?" untuk tiga horizon sekaligus, dengan satu aturan yang tidak
 * bisa dilanggar:
 *
 * > **Horizon yang engine-nya sedang aktif dinilai engine itu sendiri** — daftar syarat, angka,
 * > dan statusnya identik dengan kartu engine di halaman yang sama. Horizon lain tampil sebagai
 * > `PRA-SARING` dan menyebut dirinya begitu.
 *
 * Aturan ini menggantikan perilaku sebelumnya, di mana kartu ini menghitung sendiri horizon yang
 * sama dengan engine memakai daftar syarat berbeda — sehingga SCALPING bisa terbaca "3/5" di sini
 * sementara SCALP DESK tepat di bawahnya menampilkan "1/5". Dua angka, satu pertanyaan, tidak ada
 * cara bagi pembaca untuk tahu mana yang harus dipercaya.
 *
 * @param rows baris kesiapan untuk koin ini (biasanya 3: scalp/intraday/swing)
 * @param activeHorizon horizon yang engine-nya aktif — barisnya berasal dari engine
 * @param loading sedang menghitung
 * @param onRequestData minta pengambilan candle untuk timeframe yang belum ada
 */
@Composable
fun SymbolReadinessCard(
    symbol: String,
    rows: List<HorizonReadiness>,
    quoteAsset: String = "IDR",
    activeHorizon: TradeHorizon? = null,
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
        if (activeHorizon != null) {
            Spacer(Modifier.height(6.dp))
            ReadinessSourceLegend(activeHorizon = activeHorizon)
        }
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
            HorizonReadinessRow(
                row = row,
                quoteAsset = quoteAsset,
                isActiveHorizon = row.horizon == activeHorizon
            )
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
                    text = "Ambil data candle untuk ${missing.size} horizon yang datanya belum ada",
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * Legenda dua jenis baris.
 *
 * Ditulis eksplisit karena inilah inti perbaikan: pengguna harus tahu bahwa `PRA-SARING` bukan
 * keputusan engine, dan bahwa `ENGINE AKTIF` adalah satu-satunya baris yang angkanya sama dengan
 * kartu engine.
 */
@Composable
fun ReadinessSourceLegend(
    activeHorizon: TradeHorizon,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TvSurfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReadinessSourceBadge(ReadinessSource.ENGINE)
            Spacer(Modifier.width(6.dp))
            Text(
                text = "dinilai engine ${activeHorizon.label.uppercase()} yang aktif — sama dengan kartu engine",
                color = TvTextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReadinessSourceBadge(ReadinessSource.PRESCREEN)
            Spacer(Modifier.width(6.dp))
            Text(
                text = "saringan biaya & tren — bukan keputusan engine, hanya penyaring daftar",
                color = TvTextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}

/**
 * Satu horizon: sumber, status, skor, kesimpulan, dan (bila dibuka) syarat + rencana trade.
 *
 * @param isActiveHorizon horizon ini milik engine yang sedang aktif? Hanya dipakai untuk menandai
 *   barisnya secara visual — kebenaran "dari engine" tetap dibaca dari [HorizonReadiness.source],
 *   bukan dari parameter ini.
 */
@Composable
fun HorizonReadinessRow(
    row: HorizonReadiness,
    quoteAsset: String = "IDR",
    isActiveHorizon: Boolean = false,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    // Baris engine dibuka otomatis: pengguna datang ke halaman ini untuk melihat keputusan engine,
    // bukan untuk membuka lipatan satu per satu.
    var expanded by remember(row.symbol, row.horizon, row.state, row.source) {
        mutableStateOf(initiallyExpanded || row.fromEngine)
    }
    val color = readinessStateColor(row.state)
    val borderAlpha = if (isActiveHorizon) 0.55f else 0.25f

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = if (isActiveHorizon) 0.09f else 0.05f))
            .border(1.dp, color.copy(alpha = borderAlpha), RoundedCornerShape(10.dp))
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

        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReadinessSourceBadge(row.source)
            if (isActiveHorizon && row.fromEngine) {
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "horizon engine aktif",
                    color = TvTextMuted,
                    fontSize = 9.sp
                )
            }
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

                row.sourceNote?.let { note ->
                    Spacer(Modifier.height(6.dp))
                    Text(note, color = TvTextMuted, fontSize = 10.sp, lineHeight = 14.sp)
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
