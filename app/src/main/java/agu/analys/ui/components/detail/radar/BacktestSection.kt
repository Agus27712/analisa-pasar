package agu.analys.ui.components.detail.radar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.scalping.ScalpingBacktestAdapter
import agu.analys.model.Timeframe
import agu.analys.ui.components.detail.AnalysisCard
import agu.analys.ui.theme.*
import agu.analys.util.MtfCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Integrasi [ScalpingBacktestAdapter] ke UI: backtest baseline (SMA20 cross)
 * di atas cache candle M1 pair aktif pada [exchange] yang sedang dibuka.
 *
 * BUKAN sinyal scalping live: tidak memakai setup/MTF/orderbook/VWAP.
 * Untuk validasi engine nyata: replay data bursa (RealDataReplayAnalyzer /
 * tools/scalp_replay.py).
 */
@Composable
fun BacktestSection(
    symbol: String,
    exchange: String,
    fees: TradingFeeConfig = TradingFeeConfig(),
    modifier: Modifier = Modifier
) {
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ScalpingBacktestAdapter.Report?>(null) }
    val scope = rememberCoroutineScope()

    AnalysisCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "📊 BACKTEST BASELINE",
                color = TvBlue,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black
            )
            Button(
                onClick = {
                    if (running) return@Button
                    running = true
                    scope.launch(Dispatchers.Default) {
                        val candles = MtfCacheManager.getCachedCandles(symbol, Timeframe.M1, exchange)
                            ?: emptyList()
                        val r = ScalpingBacktestAdapter.run(candles = candles, fees = fees)
                        withContext(Dispatchers.Main) {
                            report = r
                            running = false
                        }
                    }
                },
                enabled = !running,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TvSurfaceVariant,
                    contentColor = TvTextPrimary
                )
            ) {
                Text(
                    text = if (running) "BERJALAN…" else "JALANKAN",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        report?.let { r ->
            Spacer(Modifier.height(8.dp))
            val res = r.result
            BacktestRow("Trades", "${res.totalTrades} (W${res.winningTrades}/L${res.losingTrades})")
            BacktestRow("Win rate", "${fmt(res.winRatePct)}%")
            BacktestRow("Expectancy", "${fmt(res.expectancyPct)}%/trade")
            BacktestRow("Profit factor", agu.analys.engine.backtest.BacktestEngine.formatProfitFactor(res.profitFactor))
            BacktestRow("Avg R:R", fmt(res.averageRr))
            BacktestRow("Max DD", "${fmt(res.maxDrawdownPct)}%")
            Spacer(Modifier.height(6.dp))
            Text(r.note, color = TvTextSecondary, fontSize = 10.5.sp, lineHeight = 15.sp)
        } ?: run {
            Spacer(Modifier.height(6.dp))
            Text(
                "Baseline SMA20-cross di cache M1 $exchange (${symbol.ifBlank { "-" }}). " +
                    "Bukan sinyal scalping live — hasil hanya metrik sample, bukan edge.",
                color = TvTextSecondary,
                fontSize = 10.5.sp,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun BacktestRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("• $label", color = TvTextSecondary, fontSize = 11.sp)
            Text(value, color = TvTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
