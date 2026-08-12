package agu.analys.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.TechnicalIndicators
import agu.analys.ui.theme.TvAmber
import agu.analys.ui.theme.TvCardBackground
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter

@Composable
fun IndicatorDashboard(indicators: TechnicalIndicators, modifier: Modifier = Modifier) {
    val available = indicators.rsi14.isFinite() && indicators.ema20.isFinite() && indicators.ema50.isFinite() && indicators.atr.isFinite()
    Card(modifier = modifier.fillMaxWidth().border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(20.dp)), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = TvCardBackground)) {
        Column(modifier = Modifier.padding(18.dp).fillMaxWidth()) {
            Text("INDIKATOR TEKNIKAL REAL-TIME", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvTextSecondary, letterSpacing = 1.2.sp)
            Spacer(modifier = Modifier.height(10.dp))
            if (!available) {
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x1AFF3B30)).padding(14.dp)) {
                    Column {
                        Text("OFFLINE / DATA BELUM TERSEDIA", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TvRed)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("RSI, MACD, EMA, dan ATR tidak dihitung dari data lama. Sambungkan internet untuk menerima candle market baru.", fontSize = 11.sp, color = TvTextSecondary, lineHeight = 16.sp)
                    }
                }
                return@Column
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("RSI (14)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TvTextSecondary)
                val rsiColor = when { indicators.rsi14 < 30 -> TvGreen; indicators.rsi14 > 70 -> TvRed; else -> TvAmber }
                val rsiStatus = when { indicators.rsi14 < 30 -> "JENUH JUAL"; indicators.rsi14 > 70 -> "JENUH BELI"; else -> "NETRAL" }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rsiStatus, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = rsiColor)
                    Spacer(Modifier.width(6.dp))
                    Text(PriceFormatter.formatRsi(indicators.rsi14), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { (indicators.rsi14 / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = when { indicators.rsi14 < 30 -> TvGreen; indicators.rsi14 > 70 -> TvRed; else -> TvAmber }, trackColor = Color(0x1AFFFFFF))
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("MACD Momentum", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TvTextSecondary)
                val macdColor = if (indicators.macdHist >= 0) TvGreen else TvRed
                Text("Hist: ${PriceFormatter.formatIndicatorVal(indicators.macdHist, 4)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = macdColor)
            }
            Spacer(Modifier.height(14.dp))
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                IndicatorRow("EMA 20", PriceFormatter.formatPrice(indicators.ema20))
                IndicatorRow("EMA 50", PriceFormatter.formatPrice(indicators.ema50))
                IndicatorRow("ATR VOL", PriceFormatter.formatPrice(indicators.atr))
            }
        }
    }
}

@Composable
private fun IndicatorRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF121212))
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TvTextSecondary)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
    }
}
