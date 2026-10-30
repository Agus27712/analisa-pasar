package agu.analys.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.StrategyMode
import agu.analys.engine.scalping.ScalpingRuntimeConfig
import agu.analys.ui.theme.*

@Composable
fun TradingModeSettings(
    strategyMode: StrategyMode,
    scalpingConfig: ScalpingRuntimeConfig,
    intradayConfig: agu.analys.engine.intraday.IntradayConfig = agu.analys.engine.intraday.IntradayRuntimeConfig.current,
    onStrategyChange: (StrategyMode) -> Unit,
    onOpenIntradaySettings: (() -> Unit)? = null
) {
    Column {
        SectionHeader("MODE ANALISIS TRADING")
        Text(
            "Sesuaikan strategi perhitungan engine sinyal and timeframe aktif.",
            color = TvTextSecondary,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))

        ModeOptionCard(
            title = "SCALPING",
            tag = "AGGRESSIVE SCALPING",
            tagBg = TvGreen.copy(alpha = 0.15f),
            tagFg = TvGreen,
            isSelected = strategyMode == StrategyMode.SCALPING,
            desc = "Mencari peluang BUY jangka pendek (1M – 15M) secara agresif dengan eksekusi cepat dan filter MTF.",
            bullets = listOf(
                "Bias: 1H · Setup: 15M · Trigger: 1M",
                "Skor LONG: ${scalpingConfig.minScoreLong} · STRONG: ${scalpingConfig.minScoreStrong}",
                "Skor Retest: ${scalpingConfig.minScoreBreakoutRetest} · Net R:R: ${String.format(java.util.Locale.US, "%.2f", scalpingConfig.minNetRr)}",
                "RVOL breakout: ${String.format(java.util.Locale.US, "%.1f", scalpingConfig.rvolBreakoutMin)}x · CHOP: ${String.format(java.util.Locale.US, "%.0f", scalpingConfig.chopHardRanging)}"
            ),
            onClick = { onStrategyChange(StrategyMode.SCALPING) }
        )

        Spacer(Modifier.height(10.dp))

        ModeOptionCard(
            title = "SWING",
            tag = "ANALISIS TREND",
            tagBg = TvBlue.copy(alpha = 0.15f),
            tagFg = TvBlue,
            isSelected = strategyMode == StrategyMode.SWING,
            desc = "Menganalisis trend jangka menengah (1H – 1D) untuk posisi swing yang lebih tenang.",
            bullets = listOf("Timeframe: 1H & 1D", "Analisis struktur trend (HH/HL/LH/LL)", "Fokus: Support / Resistance & Demand Zone"),
            onClick = { onStrategyChange(StrategyMode.SWING) }
        )

        Spacer(Modifier.height(10.dp))

        ModeOptionCard(
            title = "INTRADAY",
            tag = "OPEN PAGI · CLOSE MALAM",
            tagBg = Color(0xFFA5B4FC).copy(alpha = 0.18f),
            tagFg = Color(0xFFA5B4FC),
            isSelected = strategyMode == StrategyMode.OFFICE_DAILY,
            desc = "Trading harian disiplin sesi (Open Pagi 06:00-11:29 WIB, Close Malam 19:30-23:29 WIB). Batas akhir fase milik fase berikutnya: 11:30 Siang, 15:30 Sore, 19:30 Close Malam, 23:30 Istirahat. Menggunakan histori harga panjang & filter anti-flash dump.",
            bullets = listOf(
                "Siklus: Open Pagi (06:00–11:29) & Close Malam (19:30–23:29 WIB)",
                "Anti Flash Dump: Analisis histori H4 (200 candle) & D1 (100 candle)",
                "Deteksi Trap: Memblokir jebakan fake pump wick & drop mendadak",
                "Kunci Profit: Exit sebelum tengah malam, lindungi kas dari overnight dump"
            ),
            onClick = { onStrategyChange(StrategyMode.OFFICE_DAILY) }
        )

        if (strategyMode == StrategyMode.SCALPING) {
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
                border = androidx.compose.foundation.BorderStroke(1.dp, TvGreen.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("PARAMETER ENGINE SCALPING AKTIF", color = TvGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Skor: LONG ${scalpingConfig.minScoreLong} · STRONG ${scalpingConfig.minScoreStrong} · RETEST ${scalpingConfig.minScoreBreakoutRetest}",
                        color = TvTextPrimary, fontSize = 11.sp
                    )
                    Text(
                        "Risiko: Min Net R:R ${String.format(java.util.Locale.US, "%.2f", scalpingConfig.minNetRr)} · Target R:R ${String.format(java.util.Locale.US, "%.2f", scalpingConfig.targetNetRr)} · Min Risk ${String.format(java.util.Locale.US, "%.2f", scalpingConfig.minRiskPct)}% · Max TP2 ${String.format(java.util.Locale.US, "%.1f", scalpingConfig.maxTp2R)}R",
                        color = TvTextPrimary, fontSize = 11.sp
                    )
                    Text(
                        "Volume: RVOL breakout ${String.format(java.util.Locale.US, "%.1f", scalpingConfig.rvolBreakoutMin)}x · setup ${String.format(java.util.Locale.US, "%.1f", scalpingConfig.rvolSetupMin)}x · retest ${String.format(java.util.Locale.US, "%.1f", scalpingConfig.rvolRetestMin)}x",
                        color = TvTextPrimary, fontSize = 11.sp
                    )
                    Text(
                        "Orderbook: breakout ${String.format(java.util.Locale.US, "%.2f", scalpingConfig.buyPressureBreakout)}x · retest ${String.format(java.util.Locale.US, "%.2f", scalpingConfig.buyPressureRetest)}x · Slippage ${String.format(java.util.Locale.US, "%.2f", scalpingConfig.slippagePct)}%",
                        color = TvTextPrimary, fontSize = 11.sp
                    )
                    Text(
                        "Struktur: CHOP ${String.format(java.util.Locale.US, "%.0f", scalpingConfig.chopHardRanging)} · Retest jarak ${String.format(java.util.Locale.US, "%.1f", scalpingConfig.retestMaxDistPct)}% · Breakout factor ${String.format(java.util.Locale.US, "%.3f", scalpingConfig.breakoutAboveFactor)} · Retest factor ${String.format(java.util.Locale.US, "%.3f", scalpingConfig.retestAboveFactor)}",
                        color = TvTextPrimary, fontSize = 11.sp
                    )
                    Text(
                        "Strength retest ${scalpingConfig.retestMinStructureStrength} · Pullback support ${String.format(java.util.Locale.US, "%.1f", scalpingConfig.pullbackSupportMaxDistPct)}%",
                        color = TvTextPrimary, fontSize = 11.sp
                    )
                }
            }
        }

        // Mode INTRADAY punya config sendiri (bukan menumpang scalping — aturan §79.1).
        // Tombol di bawah membuka layar slider intraday; nilai yang tampil di sini selalu
        // berasal dari config yang sedang aktif, bukan angka literal di UI.
        if (strategyMode == StrategyMode.OFFICE_DAILY) {
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TvSurfaceVariant),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFA5B4FC).copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        "PARAMETER ENGINE INTRADAY AKTIF",
                        color = Color(0xFFA5B4FC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Stop Loss: ${fmtNum(intradayConfig.minSlPct, 1)}–${fmtNum(intradayConfig.maxSlPct, 1)}% · " +
                            "lantai biaya ${fmtNum(intradayConfig.minSlFrictionMultiple, 1)}x",
                        color = TvTextPrimary, fontSize = 11.sp
                    )
                    Text(
                        "Target 1: ${fmtNum(intradayConfig.tp1AtrMult, 2)}x ATR (cap ${fmtNum(intradayConfig.maxTp1Pct, 1)}%) · " +
                            "Target 2: ${fmtNum(intradayConfig.minTp2Pct, 1)}–${fmtNum(intradayConfig.maxTp2Pct, 1)}%",
                        color = TvTextPrimary, fontSize = 11.sp
                    )
                    Text(
                        "Saringan: Net R:R TP1 min ${fmtNum(intradayConfig.minNetRrTp1, 2)}x · " +
                            "MACD hist min ${fmtNum(intradayConfig.macdHistMinPct, 2)}%",
                        color = TvTextPrimary, fontSize = 11.sp
                    )
                    Text(
                        "Trauma flash dump: ${intradayConfig.flashDumpTraumaCandles} candle · " +
                            "pindai ${intradayConfig.flashDumpScanCandles} candle",
                        color = TvTextPrimary, fontSize = 11.sp
                    )

                    if (onOpenIntradaySettings != null) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = onOpenIntradaySettings,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFA5B4FC).copy(alpha = 0.5f))
                        ) {
                            Text(
                                "Atur Sensitifitas Intraday (slider)",
                                color = Color(0xFFA5B4FC),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun fmtNum(value: Double, decimals: Int): String =
    String.format(java.util.Locale.US, "%.${decimals}f", value)

@Composable
fun ModeOptionCard(
    title: String,
    tag: String,
    tagBg: Color,
    tagFg: Color,
    isSelected: Boolean,
    desc: String,
    bullets: List<String>,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) TvSurfaceVariant else TvCardBackground),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) tagFg else TvBorder
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (isSelected) TvTextPrimary else TvTextSecondary)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(tagBg, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(tag, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = tagFg)
                    }
                }
                if (isSelected) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Terpilih", tint = tagFg, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(desc, fontSize = 11.sp, color = TvTextSecondary, lineHeight = 15.sp)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                bullets.forEach { bullet ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(4.dp)
                                .background(if (isSelected) tagFg else TvTextSecondary, RoundedCornerShape(50))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(bullet, fontSize = 10.sp, color = if (isSelected) TvTextPrimary else TvTextSecondary)
                    }
                }
            }
        }
    }
}
