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

import agu.analys.ui.theme.*

@Composable
fun MarketStructureLearningCard(
    snapshot: MarketStructureSnapshot,
    quoteAsset: String = "IDR",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().background(TvCardBackground, RoundedCornerShape(14.dp)).padding(14.dp)
    ) {
        Text("MARKET STRUCTURE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvGreen)
        Spacer(Modifier.height(3.dp))
        Text("Belajar membaca arah pasar", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
        Spacer(Modifier.height(10.dp))

        if (!snapshot.dataEnough) {
            Text(snapshot.trendExplanation, fontSize = 11.sp, color = TvTextSecondary)
            return@Column
        }

        Text(snapshot.trend, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TvAmber)
        Spacer(Modifier.height(4.dp))
        Text(snapshot.trendExplanation, fontSize = 11.sp, color = TvTextSecondary, lineHeight = 16.sp)
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LevelBox("Support", snapshot.support, snapshot.supportDistancePct, quoteAsset, Modifier.weight(1f))
            LevelBox("Resistance", snapshot.resistance, snapshot.resistanceDistancePct, quoteAsset, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Text("Swing High terakhir", fontSize = 9.sp, color = TvTextSecondary)
        Text(snapshot.lastSwingHigh?.let { PriceFormatter.formatPrice(it, quoteAsset = quoteAsset) } ?: "Belum terbentuk", fontSize = 11.sp, color = TvTextPrimary)
        Spacer(Modifier.height(5.dp))
        Text("Swing Low terakhir", fontSize = 9.sp, color = TvTextSecondary)
        Text(snapshot.lastSwingLow?.let { PriceFormatter.formatPrice(it, quoteAsset = quoteAsset) } ?: "Belum terbentuk", fontSize = 11.sp, color = TvTextPrimary)
        Spacer(Modifier.height(9.dp))
        Text(snapshot.structureExplanation, fontSize = 9.sp, color = TvTextSecondary, lineHeight = 14.sp)
        Spacer(Modifier.height(5.dp))
        Text("Latihan: jangan langsung BUY/SELL. Tanyakan dulu: apakah HH/HL atau LH/LL masih bertahan?", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = TvTextPrimary, lineHeight = 15.sp)
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
    Column(modifier.background(TvSurfaceVariant, RoundedCornerShape(10.dp)).padding(9.dp)) {
        Text(label, fontSize = 9.sp, color = TvTextSecondary)
        Text(value?.let { PriceFormatter.formatPrice(it, quoteAsset = quoteAsset) } ?: "Belum ada", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TvTextPrimary)
        Text(distancePct?.let { "${String.format(java.util.Locale.US, "%.2f", it)}% dari harga" } ?: "Tidak tersedia", fontSize = 8.sp, color = TvTextSecondary)
    }
}
