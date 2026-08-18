package agu.analys.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.MarketStructureSnapshot
import agu.analys.util.PriceFormatter

private val CardBg = Color(0xFF15171D)
private val Primary = Color(0xFFF2F4F8)
private val Secondary = Color(0xFF9AA0AA)
private val Accent = Color(0xFF52D273)
private val Warning = Color(0xFFFFC857)

@Composable
fun MarketStructureLearningCard(
    snapshot: MarketStructureSnapshot,
    quoteAsset: String = "IDR",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(14.dp)).padding(14.dp)
    ) {
        Text("MARKET STRUCTURE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Accent)
        Spacer(Modifier.height(3.dp))
        Text("Belajar membaca arah pasar", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primary)
        Spacer(Modifier.height(10.dp))

        if (!snapshot.dataEnough) {
            Text(snapshot.trendExplanation, fontSize = 11.sp, color = Secondary)
            return@Column
        }

        Text(snapshot.trend, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Warning)
        Spacer(Modifier.height(4.dp))
        Text(snapshot.trendExplanation, fontSize = 11.sp, color = Secondary, lineHeight = 16.sp)
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LevelBox("Support", snapshot.support, snapshot.supportDistancePct, quoteAsset, Modifier.weight(1f))
            LevelBox("Resistance", snapshot.resistance, snapshot.resistanceDistancePct, quoteAsset, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Text("Swing High terakhir", fontSize = 9.sp, color = Secondary)
        Text(snapshot.lastSwingHigh?.let { PriceFormatter.formatPrice(it, quoteAsset = quoteAsset) } ?: "Belum terbentuk", fontSize = 11.sp, color = Primary)
        Spacer(Modifier.height(5.dp))
        Text("Swing Low terakhir", fontSize = 9.sp, color = Secondary)
        Text(snapshot.lastSwingLow?.let { PriceFormatter.formatPrice(it, quoteAsset = quoteAsset) } ?: "Belum terbentuk", fontSize = 11.sp, color = Primary)
        Spacer(Modifier.height(9.dp))
        Text(snapshot.structureExplanation, fontSize = 9.sp, color = Secondary, lineHeight = 14.sp)
        Spacer(Modifier.height(5.dp))
        Text("Latihan: jangan langsung BUY/SELL. Tanyakan dulu: apakah HH/HL atau LH/LL masih bertahan?", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Primary, lineHeight = 15.sp)
    }
}

@Composable
private fun LevelBox(
    label: String,
    value: Double?,
    distancePct: Double?,
    quoteAsset: String,
    modifier: Modifier = Modifier
) {
    Column(modifier.background(Color(0x0DFFFFFF), RoundedCornerShape(10.dp)).padding(9.dp)) {
        Text(label, fontSize = 9.sp, color = Secondary)
        Text(value?.let { PriceFormatter.formatPrice(it, quoteAsset = quoteAsset) } ?: "Belum ada", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Primary)
        Text(distancePct?.let { "${String.format(java.util.Locale.US, "%.2f", it)}% dari harga" } ?: "Tidak tersedia", fontSize = 8.sp, color = Secondary)
    }
}
