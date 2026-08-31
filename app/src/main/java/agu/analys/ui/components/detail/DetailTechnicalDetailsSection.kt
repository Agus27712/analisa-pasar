package agu.analys.ui.components.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.engine.MarketStructureSnapshot
import agu.analys.model.AISignalState
import agu.analys.model.TechnicalIndicators
import agu.analys.ui.theme.*

@Composable
fun DetailTechnicalDetailsSection(
    indicators: TechnicalIndicators,
    structure: MarketStructureSnapshot,
    volume24h: Double,
    scalping: Boolean,
    signal: AISignalState,
    price: Double,
    quoteAsset: String,
    modifier: Modifier = Modifier
) {
    var showTechnicalDetails by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground),
        border = BorderStroke(1.dp, TvBorder)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showTechnicalDetails = !showTechnicalDetails },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, null, tint = TvBlue, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("INDIKATOR & OBSERVASI", color = TvBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = if (showTechnicalDetails) "Tutup ▲" else "Lihat ▼",
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )
            }
            AnimatedVisibility(visible = showTechnicalDetails) {
                Column(Modifier.padding(top = 8.dp)) {
                    TechnicalDetailsCard(
                        indicators = indicators,
                        structure = structure,
                        volume24h = volume24h,
                        scalping = scalping
                    )
                    Spacer(Modifier.height(8.dp))
                    MonitorCard(
                        signal = signal,
                        structure = structure,
                        price = price,
                        cached = false,
                        quoteAsset = quoteAsset
                    )
                }
            }
        }
    }
}
