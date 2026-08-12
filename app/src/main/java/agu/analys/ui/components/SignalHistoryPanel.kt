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
import agu.analys.model.AISignalState
import agu.analys.model.SignalAction
import agu.analys.ui.theme.TvAmber
import agu.analys.ui.theme.TvCardBackground
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SignalHistoryPanel(
    history: List<AISignalState>,
    currentSymbol: String,
    modifier: Modifier = Modifier
) {
    // History is kept across pair changes, then filtered strictly to the active pair.
    // This is important for custom/watchlist pairs that are not part of POPULAR_PAIRS.
    val visibleHistory = history.filter { it.marketSymbol.equals(currentSymbol, ignoreCase = true) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = TvCardBackground)
    ) {
        Column(modifier = Modifier.padding(18.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RIWAYAT & LOG SINYAL",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TvTextSecondary,
                    letterSpacing = 1.2.sp
                )
                Text(
                    text = "${visibleHistory.size} untuk koin ini",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TvGreen
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (visibleHistory.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Belum ada sinyal untuk koin ini.",
                        fontSize = 12.sp,
                        color = TvTextSecondary
                    )
                }
            } else {
                visibleHistory.take(6).forEach { signal ->
                    val color = when (signal.action) {
                        SignalAction.BUY -> TvGreen
                        SignalAction.SELL -> TvRed
                        SignalAction.HOLD -> TvAmber
                    }
                    val actionLabel = when (signal.action) {
                        SignalAction.BUY -> "BELI"
                        SignalAction.SELL -> "JUAL"
                        SignalAction.HOLD -> "TAHAN"
                    }
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
                    val timeStr = timeFormat.format(Date(signal.timestamp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF121212))
                            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(color.copy(alpha = 0.2f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(actionLabel, fontSize = 11.sp, fontWeight = FontWeight.Black, color = color)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(currentSymbol, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color)
                                    Text(
                                        PriceFormatter.formatPrice(signal.entryPrice),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = TvTextPrimary
                                    )
                                    Text(signal.sentiment.displayName, fontSize = 10.sp, color = TvTextSecondary)
                                }
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Score ${signal.confidence}/100",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = color
                                )
                                Text(timeStr, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TvTextSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}