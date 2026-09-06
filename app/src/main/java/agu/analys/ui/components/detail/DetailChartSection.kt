package agu.analys.ui.components.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.model.*
import agu.analys.ui.components.SimpleComposeChart
import agu.analys.ui.theme.*

@Composable
fun DetailChartSection(
    candles: List<CandleBar>,
    tick: MarketTick?,
    signal: AISignalState,
    pair: TradingPair,
    selectedTimeframe: Timeframe,
    onOpenLandscapeChart: () -> Unit,
    modifier: Modifier = Modifier
) {
    var chartVisible by remember { mutableStateOf(false) }
    var showVolume by remember { mutableStateOf(true) }
    var showEma by remember { mutableStateOf(false) }
    var showBb by remember { mutableStateOf(false) }
    var showStochRsi by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { chartVisible = !chartVisible },
                modifier = Modifier.weight(1f).height(38.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TvGreen),
                border = BorderStroke(1.dp, TvBorder),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.ShowChart, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (chartVisible) "Tutup Grafik" else "Grafik", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onOpenLandscapeChart,
                modifier = Modifier.weight(1f).height(38.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TvBlue),
                border = BorderStroke(1.dp, TvBorder),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.CropRotate, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Layar Penuh", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        AnimatedVisibility(
            visible = chartVisible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TvCardBackground),
                border = BorderStroke(1.dp, TvBorder)
            ) {
                Column(Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "GRAFIK ${selectedTimeframe.label.uppercase()}",
                            color = TvBlue,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val chipColors = FilterChipDefaults.filterChipColors(
                                containerColor = Color.Transparent,
                                labelColor = TvTextSecondary,
                                selectedContainerColor = TvBlue.copy(alpha = 0.2f),
                                selectedLabelColor = TvBlue
                            )
                            val chipBorder = FilterChipDefaults.filterChipBorder(
                                borderColor = TvBorder,
                                enabled = true,
                                selected = false
                            )
                            FilterChip(
                                selected = showBb,
                                onClick = { showBb = !showBb },
                                label = { Text("BB", fontSize = 9.sp, fontWeight = FontWeight.Bold) },
                                colors = chipColors,
                                border = chipBorder,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.height(24.dp)
                            )
                            FilterChip(
                                selected = showStochRsi,
                                onClick = { showStochRsi = !showStochRsi },
                                label = { Text("StochRSI", fontSize = 9.sp, fontWeight = FontWeight.Bold) },
                                colors = chipColors,
                                border = chipBorder,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.height(24.dp)
                            )
                            FilterChip(
                                selected = showEma,
                                onClick = { showEma = !showEma },
                                label = { Text("EMA", fontSize = 9.sp, fontWeight = FontWeight.Bold) },
                                colors = chipColors,
                                border = chipBorder,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.height(24.dp)
                            )
                            FilterChip(
                                selected = showVolume,
                                onClick = { showVolume = !showVolume },
                                label = { Text("Vol", fontSize = 9.sp, fontWeight = FontWeight.Bold) },
                                colors = chipColors,
                                border = chipBorder,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SimpleComposeChart(
                        prices = emptyList(),
                        candles = candles,
                        currentPrice = tick?.price ?: 0.0,
                        isPositiveTrend = (tick?.change24h ?: 0.0) >= 0,
                        showVolume = showVolume,
                        showEma = showEma,
                        showBb = showBb,
                        showStochRsi = showStochRsi,
                        entryPrice = signal.entryPrice,
                        targetPrice1 = signal.targetPrice1,
                        targetPrice2 = signal.targetPrice2,
                        stopLoss = signal.stopLoss,
                        quoteAsset = pair.quoteAsset,
                        modifier = Modifier.fillMaxWidth().height(ChartLayoutDefaults.PortraitHeight)
                    )
                }
            }
        }
    }
}
