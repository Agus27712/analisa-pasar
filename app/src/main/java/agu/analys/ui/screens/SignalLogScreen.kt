package agu.analys.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import agu.analys.database.SignalLogEntity
import agu.analys.database.TradeHistoryRecordEntity
import agu.analys.model.ConfidenceTierStats
import agu.analys.model.SignalLogFilter
import agu.analys.model.SignalReliabilitySummary
import agu.analys.model.TradingPair
import agu.analys.trading.TradeLogExporter
import agu.analys.ui.components.trade.TradeHistoryJourneyCard
import agu.analys.ui.theme.*
import agu.analys.util.PriceFormatter
import agu.analys.viewmodel.TradingViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SignalLogScreen(
    viewModel: TradingViewModel,
    onBack: () -> Unit,
    onNavigateToDetail: (TradingPair) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val allLogs by viewModel.allSignalLogs.collectAsStateWithLifecycle()
    val reliabilitySummary by viewModel.signalReliabilitySummary.collectAsStateWithLifecycle()
    val tradeHistoryRecords by viewModel.tradeHistoryRecords.collectAsStateWithLifecycle()

    var selectedScreenTab by remember { mutableIntStateOf(0) } // 0 = Siklus Histori Trade, 1 = Log & Evaluasi Sinyal AI

    // Trade History Filters
    var tradeFilterRealSim by remember { mutableStateOf("ALL") } // ALL, REAL, SIM
    var tradeFilterStatus by remember { mutableStateOf("ALL") } // ALL, WIN, LOSS, HOLDING
    var selectedTradeSymbol by remember { mutableStateOf<String?>(null) }

    // Signal Log Filters
    var selectedFilter by remember { mutableStateOf(SignalLogFilter.ALL) }
    var selectedSymbolFilter by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var logToResolveManually by remember { mutableStateOf<SignalLogEntity?>(null) }
    var expandedLogId by remember { mutableStateOf<Long?>(null) }

    val distinctTradeSymbols = remember(tradeHistoryRecords) {
        tradeHistoryRecords.map { it.symbol }.distinct().sorted()
    }

    val filteredTradeRecords = remember(tradeHistoryRecords, tradeFilterRealSim, tradeFilterStatus, selectedTradeSymbol) {
        tradeHistoryRecords.filter { item ->
            val matchSymbol = selectedTradeSymbol == null || item.symbol.equals(selectedTradeSymbol, ignoreCase = true)
            val matchMode = when (tradeFilterRealSim) {
                "REAL" -> item.isReal
                "SIM" -> !item.isReal
                else -> true
            }
            val matchStatus = when (tradeFilterStatus) {
                "WIN" -> item.isWin == true || (item.pnlIdr ?: 0.0) > 0.0
                "LOSS" -> item.isWin == false && (item.pnlIdr ?: 0.0) < 0.0
                "HOLDING" -> item.status == "HOLDING"
                else -> true
            }
            matchSymbol && matchMode && matchStatus
        }
    }

    val distinctSymbols = remember(allLogs) {
        allLogs.map { it.symbol }.distinct().sorted()
    }

    val filteredLogs = remember(allLogs, selectedFilter, selectedSymbolFilter) {
        allLogs.filter { log ->
            val matchSymbol = selectedSymbolFilter == null || log.symbol.equals(selectedSymbolFilter, ignoreCase = true)
            val matchFilter = when (selectedFilter) {
                SignalLogFilter.ALL -> true
                SignalLogFilter.WIN_HIT_TP -> log.outcomeStatus in listOf("HIT_TP1", "HIT_TP2", "MANUAL_WIN") || (log.realizedPnlPct ?: 0.0) > 0
                SignalLogFilter.LOSS_HIT_SL -> log.outcomeStatus in listOf("HIT_SL", "MANUAL_LOSS") || (log.realizedPnlPct ?: 0.0) < 0
                SignalLogFilter.TRACKING -> log.outcomeStatus == "TRACKING"
                SignalLogFilter.BUY_ONLY -> log.action.equals("BUY", ignoreCase = true)
                SignalLogFilter.SELL_ONLY -> log.action.equals("SELL", ignoreCase = true)
            }
            matchSymbol && matchFilter
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(TvBackground)
    ) {
        // TOP APP BAR
        Surface(
            color = TvSurface,
            shadowElevation = 2.dp,
            border = BorderStroke(1.dp, TvBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("signal_logs_back_btn")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Kembali",
                            tint = TvTextPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (selectedScreenTab == 0) "HISTORI SIKLUS TRADE" else "LOG & RELIABILITAS SINYAL",
                            color = TvTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            maxLines = 1
                        )
                        Text(
                            text = if (selectedScreenTab == 0) "Database Lokal Room · Siklus Sinyal ➔ Buy ➔ Hold ➔ Sell" else "Database Lokal Room · Evaluasi Skor & Akurasi",
                            color = TvTextSecondary,
                            fontSize = 9.5.sp,
                            maxLines = 1
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Tombol Refresh: sync log valid dari detail/engine ke halaman Log & Akurasi
                    IconButton(
                        onClick = {
                            viewModel.refreshSignalLogs()
                            Toast.makeText(context, "Log sinyal di-refresh & dikonsolidasi", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("signal_logs_refresh_btn")
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh Log Sinyal",
                            tint = TvCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            if (selectedScreenTab == 0) {
                                viewModel.seedSampleTradeJourneys()
                                Toast.makeText(context, "Sampel histori siklus trade ditambahkan ke database", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.seedSampleSignalLogs()
                                Toast.makeText(context, "Data simulasi sinyal ditambahkan untuk pengujian", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.testTag("signal_logs_seed_btn")
                    ) {
                        Icon(
                            Icons.Default.AddChart,
                            contentDescription = "Muat Data Uji",
                            tint = TvCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    val hasItemsToClear = if (selectedScreenTab == 0) tradeHistoryRecords.isNotEmpty() else allLogs.isNotEmpty()
                    if (hasItemsToClear) {
                        IconButton(
                            onClick = { showClearDialog = true },
                            modifier = Modifier.testTag("signal_logs_clear_btn")
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "Hapus Semua Log",
                                tint = TvRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // DUAL TABS: HISTORI SIKLUS TRADE vs EVALUASI SINYAL
            TabRow(
                selectedTabIndex = selectedScreenTab,
                containerColor = TvSurfaceVariant,
                contentColor = TvTextPrimary,
                indicator = {},
                divider = { HorizontalDivider(color = TvBorder.copy(alpha = 0.6f)) }
            ) {
                Tab(
                    selected = selectedScreenTab == 0,
                    onClick = { selectedScreenTab = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Histori Siklus Trade",
                                fontSize = 12.sp,
                                fontWeight = if (selectedScreenTab == 0) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedScreenTab == 0) TvCyan else TvTextSecondary
                            )
                            if (tradeHistoryRecords.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    color = if (selectedScreenTab == 0) TvCyan.copy(alpha = 0.2f) else TvBorder,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(
                                        text = "${tradeHistoryRecords.size}",
                                        color = if (selectedScreenTab == 0) TvCyan else TvTextSecondary,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                )

                Tab(
                    selected = selectedScreenTab == 1,
                    onClick = { selectedScreenTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Log & Akurasi Sinyal",
                                fontSize = 12.sp,
                                fontWeight = if (selectedScreenTab == 1) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedScreenTab == 1) TvCyan else TvTextSecondary
                            )
                            if (allLogs.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    color = if (selectedScreenTab == 1) TvCyan.copy(alpha = 0.2f) else TvBorder,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(
                                        text = "${allLogs.size}",
                                        color = if (selectedScreenTab == 1) TvCyan else TvTextSecondary,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }

        if (selectedScreenTab == 0) {
            // TAB 0: HISTORI SIKLUS TRADE (Sinyal Buy -> User Buy -> Durasi Hold -> Sell & Profit/Loss)
            TradeHistoryTabContent(
                records = tradeHistoryRecords,
                filteredRecords = filteredTradeRecords,
                tradeFilterRealSim = tradeFilterRealSim,
                onFilterRealSimChange = { tradeFilterRealSim = it },
                tradeFilterStatus = tradeFilterStatus,
                onFilterStatusChange = { tradeFilterStatus = it },
                distinctSymbols = distinctTradeSymbols,
                selectedSymbol = selectedTradeSymbol,
                onSelectSymbol = { selectedTradeSymbol = it },
                onDeleteRecord = { viewModel.deleteTradeHistoryRecord(it) },
                onSeedSamples = { viewModel.seedSampleTradeJourneys() },
                modifier = Modifier.weight(1f)
            )
        } else {
            // TAB 1: LOG & EVALUASI SINYAL
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
            // 1. STATISTICAL RELIABILITY DASHBOARD (Main evaluation summary)
            item(key = "reliability_dashboard") {
                ReliabilityEvaluationCard(summary = reliabilitySummary)
            }

            // 2. CONFIDENCE TIER PERFORMANCE COMPARISON
            item(key = "confidence_tier_section") {
                ConfidenceReliabilityBreakdownCard(summary = reliabilitySummary)
            }

            // 3. FILTER CHIPS & SYMBOL FILTER ROW
            item(key = "filter_section") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Status filters
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SignalLogFilter.values().forEach { filter ->
                            val isSelected = selectedFilter == filter
                            val chipBg = if (isSelected) TvBlue.copy(alpha = 0.2f) else TvSurface
                            val chipBorder = if (isSelected) TvBlue else TvBorder
                            val chipColor = if (isSelected) TvBlue else TvTextSecondary

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = chipBg,
                                border = BorderStroke(1.dp, chipBorder),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedFilter = filter }
                            ) {
                                Text(
                                    text = filter.label,
                                    color = chipColor,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    // Symbol selector filter if multiple symbols exist
                    if (distinctSymbols.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Koin:",
                                color = TvTextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(end = 2.dp)
                            )

                            val isAllSymbol = selectedSymbolFilter == null
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isAllSymbol) TvCyan.copy(alpha = 0.15f) else TvSurfaceVariant,
                                border = BorderStroke(1.dp, if (isAllSymbol) TvCyan else TvBorder),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { selectedSymbolFilter = null }
                            ) {
                                Text(
                                    "Semua",
                                    color = if (isAllSymbol) TvCyan else TvTextSecondary,
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isAllSymbol) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }

                            distinctSymbols.forEach { sym ->
                                val isSelected = selectedSymbolFilter == sym
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSelected) TvCyan.copy(alpha = 0.15f) else TvSurfaceVariant,
                                    border = BorderStroke(1.dp, if (isSelected) TvCyan else TvBorder),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { selectedSymbolFilter = sym }
                                ) {
                                    Text(
                                        sym,
                                        color = if (isSelected) TvCyan else TvTextSecondary,
                                        fontSize = 10.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Section header count
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DAFTAR RIWAYAT SINYAL (${filteredLogs.size})",
                            color = TvCyan,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "Ketuk item untuk detail lengkap",
                            color = TvTextSecondary,
                            fontSize = 9.5.sp
                        )
                    }
                }
            }

            // 4. LOG LIST ITEMS
            if (filteredLogs.isEmpty()) {
                item(key = "empty_logs") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = TvCardBackground,
                        border = BorderStroke(1.dp, TvBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Timeline,
                                contentDescription = null,
                                tint = TvTextSecondary,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = "Belum Ada Log Sinyal",
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Sinyal baru dari Learning Engine akan otomatis disimpan dan dievaluasi akurasinya secara real-time.",
                                color = TvTextSecondary,
                                fontSize = 11.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = { viewModel.seedSampleSignalLogs() },
                                colors = ButtonDefaults.buttonColors(containerColor = TvSurfaceVariant),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Muat Contoh Data Riwayat Sinyal", color = TvCyan, fontSize = 11.5.sp)
                            }
                        }
                    }
                }
            } else {
                items(filteredLogs, key = { it.id }) { log ->
                    val isExpanded = expandedLogId == log.id
                    SignalLogItemCard(
                        log = log,
                        isExpanded = isExpanded,
                        onToggleExpand = {
                            expandedLogId = if (isExpanded) null else log.id
                        },
                        onNavigateToCoin = {
                            onNavigateToDetail(TradingPair.fromCustomSymbol(log.symbol))
                        },
                        onResolveManually = { logToResolveManually = log },
                        onDeleteLog = { viewModel.deleteSignalLog(log.id) }
                    )
                }
            }

            item(key = "footer_spacer") {
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

    // DIALOGS
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text(
                    text = if (selectedScreenTab == 0) "Hapus Semua Histori Siklus Trade?" else "Hapus Semua Log Sinyal?",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (selectedScreenTab == 0) {
                        "Tindakan ini akan menghapus seluruh rekaman siklus trade (sinyal buy, eksekusi order user, durasi hold, dan hasil sell profit/loss) dari database lokal Room."
                    } else {
                        "Tindakan ini akan menghapus seluruh rekaman sinyal dan riwayat evaluasi performa dari database lokal Room."
                    },
                    color = TvTextSecondary,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedScreenTab == 0) {
                            viewModel.clearAllTradeHistoryRecords()
                            showClearDialog = false
                            Toast.makeText(context, "Seluruh histori siklus trade berhasil dibersihkan", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.clearAllSignalLogs()
                            showClearDialog = false
                            Toast.makeText(context, "Seluruh log sinyal berhasil dibersihkan", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TvRed)
                ) {
                    Text("Hapus Semua", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Batal", color = TvTextSecondary)
                }
            },
            containerColor = TvSurface,
            shape = RoundedCornerShape(14.dp)
        )
    }

    if (logToResolveManually != null) {
        val target = logToResolveManually!!
        ManualResolveDialog(
            log = target,
            onDismiss = { logToResolveManually = null },
            onConfirmResolve = { isWin, note ->
                viewModel.resolveSignalLogManually(target.id, isWin, note = note)
                logToResolveManually = null
                Toast.makeText(context, "Status sinyal berhasil diperbarui", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun TradeHistoryTabContent(
    records: List<TradeHistoryRecordEntity>,
    filteredRecords: List<TradeHistoryRecordEntity>,
    tradeFilterRealSim: String,
    onFilterRealSimChange: (String) -> Unit,
    tradeFilterStatus: String,
    onFilterStatusChange: (String) -> Unit,
    distinctSymbols: List<String>,
    selectedSymbol: String?,
    onSelectSymbol: (String?) -> Unit,
    onDeleteRecord: (Long) -> Unit,
    onSeedSamples: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. STATISTICAL SUMMARY OF COMPLETED & ACTIVE TRADES
        item(key = "trade_history_summary") {
            TradeHistorySummaryCard(records = records)
        }

        // 2. FILTERS (REAL/SIMULASI, STATUS HASIL, PAIR)
        item(key = "trade_history_filters") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Filter Mode (Semua, Real, Simulasi)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "ALL" to "Semua Mode",
                        "REAL" to "1:1 Real Trade",
                        "SIM" to "Simulasi Trade"
                    ).forEach { (key, label) ->
                        val isSelected = tradeFilterRealSim == key
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) TvBlue.copy(alpha = 0.2f) else TvSurface,
                            border = BorderStroke(1.dp, if (isSelected) TvBlue else TvBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onFilterRealSimChange(key) }
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) TvBlue else TvTextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier
                                    .padding(vertical = 7.dp)
                                    .wrapContentWidth(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }

                // Filter Status (Semua, Profit, Loss, Holding)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "ALL" to "Semua",
                        "WIN" to "🟢 Profit (Win)",
                        "LOSS" to "🔴 Cut Loss (Loss)",
                        "HOLDING" to "🔵 Sedang Menahan"
                    ).forEach { (key, label) ->
                        val isSelected = tradeFilterStatus == key
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) TvCyan.copy(alpha = 0.2f) else TvSurface,
                            border = BorderStroke(1.dp, if (isSelected) TvCyan else TvBorder),
                            modifier = Modifier.clickable { onFilterStatusChange(key) }
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) TvCyan else TvTextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // Filter Symbol
                if (distinctSymbols.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (selectedSymbol == null) TvAmber.copy(alpha = 0.2f) else TvSurfaceVariant,
                            border = BorderStroke(0.5.dp, if (selectedSymbol == null) TvAmber else TvBorder),
                            modifier = Modifier.clickable { onSelectSymbol(null) }
                        ) {
                            Text(
                                text = "Semua Koin (${records.size})",
                                color = if (selectedSymbol == null) TvAmber else TvTextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }

                        distinctSymbols.forEach { sym ->
                            val isSelected = selectedSymbol.equals(sym, ignoreCase = true)
                            val count = records.count { it.symbol.equals(sym, ignoreCase = true) }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) TvAmber.copy(alpha = 0.2f) else TvSurfaceVariant,
                                border = BorderStroke(0.5.dp, if (isSelected) TvAmber else TvBorder),
                                modifier = Modifier.clickable { onSelectSymbol(if (isSelected) null else sym) }
                            ) {
                                Text(
                                    text = "$sym ($count)",
                                    color = if (isSelected) TvAmber else TvTextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    softWrap = false,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. TRADE HISTORY JOURNEY ITEMS OR EMPTY STATE
        if (filteredRecords.isEmpty()) {
            item(key = "trade_history_empty") {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = TvSurfaceVariant,
                    border = BorderStroke(1.dp, TvBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.ReceiptLong,
                            contentDescription = null,
                            tint = TvTextSecondary,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "Belum Ada Histori Siklus Trade",
                            color = TvTextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Setiap kali sinyal buy dieksekusi (baik Real maupun Simulasi), sistem secara otomatis mencatat pengeluaran sinyal buy di database (mode strategi & perhitungannya) -> user buy -> durasi hold & tracking -> sell (profit/loss).",
                            color = TvTextSecondary,
                            fontSize = 10.5.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 15.sp
                        )
                        Button(
                            onClick = onSeedSamples,
                            colors = ButtonDefaults.buttonColors(containerColor = TvCyan.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, TvCyan),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.AddChart, contentDescription = null, tint = TvCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Muat Contoh Siklus Trade Lengkap", color = TvCyan, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            items(filteredRecords, key = { it.id }) { record ->
                TradeHistoryJourneyCard(
                    record = record,
                    onDelete = { onDeleteRecord(record.id) }
                )
            }
        }

        item(key = "trade_history_footer_spacer") {
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TradeHistorySummaryCard(records: List<TradeHistoryRecordEntity>) {
    val totalTrades = records.size
    val closedTrades = records.filter { it.status == "CLOSED" }
    val holdingTrades = records.filter { it.status == "HOLDING" }
    val winTrades = closedTrades.filter { it.isWin == true || (it.pnlIdr ?: 0.0) > 0.0 }
    val lossTrades = closedTrades.filter { it.isWin == false && (it.pnlIdr ?: 0.0) < 0.0 }
    val winRatePct = if (closedTrades.isNotEmpty()) (winTrades.size.toDouble() / closedTrades.size) * 100.0 else 0.0
    val totalPnlIdr = closedTrades.mapNotNull { it.pnlIdr }.sum()
    val isOverallProfit = totalPnlIdr >= 0.0
    val pnlColor = if (isOverallProfit) TvGreen else TvRed
    val pnlPrefix = if (isOverallProfit) "+" else ""
    val avgHoldMs = if (closedTrades.isNotEmpty()) closedTrades.map { it.holdingDurationMs }.average().toLong() else 0L

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = TvSurfaceVariant,
        border = BorderStroke(1.dp, TvBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(TvCyan.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.QueryStats,
                            contentDescription = null,
                            tint = TvCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "RINGKASAN SIKLUS PERJALANAN TRADE",
                            color = TvTextPrimary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.4.sp
                        )
                        Text(
                            text = "Perekaman Sinyal Buy ➔ Beli ➔ Hold ➔ Jual & PnL",
                            color = TvTextSecondary,
                            fontSize = 9.sp
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = TvSurface,
                    border = BorderStroke(0.5.dp, TvBorder)
                ) {
                    Text(
                        text = "$totalTrades Trade",
                        color = TvTextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            HorizontalDivider(color = TvBorder.copy(alpha = 0.5f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Metric 1: Win Rate
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = TvBackground,
                    border = BorderStroke(1.dp, if (winRatePct >= 50.0) TvGreen.copy(alpha = 0.4f) else TvAmber.copy(alpha = 0.4f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "WIN RATE SELESAI",
                            color = TvTextSecondary,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (closedTrades.isNotEmpty()) "${String.format(Locale.US, "%.1f", winRatePct)}%" else "N/A",
                            color = if (winRatePct >= 50.0) TvGreen else TvRed,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "${winTrades.size} Win · ${lossTrades.size} Loss",
                            color = TvTextSecondary,
                            fontSize = 8.5.sp
                        )
                    }
                }

                // Metric 2: Total Realized PnL
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = TvBackground,
                    border = BorderStroke(1.dp, pnlColor.copy(alpha = 0.4f)),
                    modifier = Modifier.weight(1.2f)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "TOTAL REALIZED PNL",
                            color = TvTextSecondary,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "$pnlPrefix${PriceFormatter.formatPrice(totalPnlIdr, quoteAsset = "IDR")}",
                            color = pnlColor,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1
                        )
                        Text(
                            text = "${closedTrades.size} Selesai · ${holdingTrades.size} Hold",
                            color = TvTextSecondary,
                            fontSize = 8.5.sp
                        )
                    }
                }

                // Metric 3: Avg Hold Duration
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = TvBackground,
                    border = BorderStroke(1.dp, TvBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "RATA-RATA HOLD",
                            color = TvTextSecondary,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (avgHoldMs > 0) TradeLogExporter.formatDuration(avgHoldMs) else "-",
                            color = TvBlue,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = "Durasi Siklus",
                            color = TvTextSecondary,
                            fontSize = 8.5.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReliabilityEvaluationCard(summary: SignalReliabilitySummary) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = TvSurfaceVariant,
        border = BorderStroke(1.dp, TvBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(TvGreen.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Verified,
                            contentDescription = null,
                            tint = TvGreen,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "EVALUASI RELIABILITAS SINYAL",
                            color = TvTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp,
                            maxLines = 1
                        )
                        Text(
                            text = "Akurasi Hasil Eksekusi Nyata",
                            color = TvTextSecondary,
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = TvBackground
                ) {
                    Text(
                        text = "Total ${summary.totalLogs} Sinyal",
                        color = TvCyan,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Main Metrics Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Win Rate Large Box
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = TvBackground,
                    border = BorderStroke(1.dp, if (summary.overallWinRatePct >= 60.0) TvGreen.copy(alpha = 0.4f) else TvAmber.copy(alpha = 0.4f)),
                    modifier = Modifier.weight(1.3f)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "TINGKAT AKURASI (WIN RATE)",
                            color = TvTextSecondary,
                            fontSize = 7.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (summary.completedLogs > 0) "${String.format(Locale.US, "%.1f", summary.overallWinRatePct)}%" else "N/A",
                            color = if (summary.overallWinRatePct >= 60.0) TvGreen else if (summary.overallWinRatePct >= 45.0) TvAmber else TvRed,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${summary.winCount} Menang · ${summary.lossCount} Kalah",
                            color = TvTextSecondary,
                            fontSize = 8.5.sp
                        )
                    }
                }

                // Profit Factor & Avg Gain/Loss
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = TvBackground,
                    border = BorderStroke(1.dp, TvBorder),
                    modifier = Modifier.weight(1.3f)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Profit Factor:", color = TvTextSecondary, fontSize = 8.5.sp)
                            Text(
                                if (summary.profitFactor > 0) String.format(Locale.US, "%.2fx", summary.profitFactor) else "-",
                                color = if (summary.profitFactor >= 1.5) TvGreen else TvTextPrimary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Rata-rata Menang:", color = TvTextSecondary, fontSize = 8.5.sp)
                            Text(
                                if (summary.avgProfitPct > 0) "+${String.format(Locale.US, "%.2f", summary.avgProfitPct)}%" else "-",
                                color = TvGreen,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Rata-rata Kalah:", color = TvTextSecondary, fontSize = 8.5.sp)
                            Text(
                                if (summary.avgLossPct != 0.0) "${String.format(Locale.US, "%.2f", summary.avgLossPct)}%" else "-",
                                color = TvRed,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfidenceReliabilityBreakdownCard(summary: SignalReliabilitySummary) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = TvCardBackground,
        border = BorderStroke(1.dp, TvBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "RELIABILITAS SKOR KEYAKINAN",
                        color = TvCyan,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        maxLines = 1
                    )
                    Text(
                        text = "Akurasi per Level Confidence (Tinggi, Sedang, Awal)",
                        color = TvTextSecondary,
                        fontSize = 8.5.sp,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = TvBackground.copy(alpha = 0.8f),
                    border = BorderStroke(1.dp, TvBorder)
                ) {
                    Text(
                        text = "Skor vs Hasil",
                        color = TvTextSecondary,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            ConfidenceTierRow(stats = summary.highConfidenceStats, accentColor = TvGreen)
            Spacer(Modifier.height(8.dp))
            ConfidenceTierRow(stats = summary.mediumConfidenceStats, accentColor = TvBlue)
            Spacer(Modifier.height(8.dp))
            ConfidenceTierRow(stats = summary.lowConfidenceStats, accentColor = TvAmber)

            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = TvBackground.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = TvCyan,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Skor keyakinan tinggi (≥80%) menunjukkan rasio kemenangan tertinggi dan drawdown terendah pada eksekusi real-time.",
                        color = TvTextSecondary,
                        fontSize = 8.5.sp,
                        lineHeight = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfidenceTierRow(stats: ConfidenceTierStats, accentColor: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(accentColor, CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stats.tierLabel,
                    color = TvTextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${stats.winCount}W / ${stats.lossCount}L",
                    color = TvTextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (stats.winCount + stats.lossCount > 0) "${String.format(Locale.US, "%.1f", stats.winRatePct)}% Win" else "Belum ada data",
                    color = if (stats.winRatePct >= 65.0) TvGreen else if (stats.winRatePct >= 50.0) TvCyan else TvAmber,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Progress bar for visual tier comparison
        val progress = (stats.winRatePct / 100.0).toFloat().coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(TvBackground)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (progress > 0f) progress else 0.02f)
                    .fillMaxHeight()
                    .background(accentColor)
            )
        }
    }
}

@Composable
private fun SignalLogItemCard(
    log: SignalLogEntity,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onNavigateToCoin: () -> Unit,
    onResolveManually: () -> Unit,
    onDeleteLog: () -> Unit
) {
    val isBuy = log.action.equals("BUY", ignoreCase = true)
    val tvGreen = TvGreen
    val tvRed = TvRed
    val tvTextSecondary = TvTextSecondary
    val tvCyan = TvCyan
    val tvAmber = TvAmber
    val tvBorder = TvBorder
    val actionColor = if (isBuy) tvGreen else tvRed
    val timeFormat = remember { SimpleDateFormat("dd MMM HH:mm:ss", Locale.US) }

    val statusDetails = remember(log.outcomeStatus, log.realizedPnlPct, tvGreen, tvRed, tvTextSecondary, tvCyan) {
        when (log.outcomeStatus) {
            "HIT_TP2" -> Triple("🎯 TARGET TP2 TERCAPAI", tvGreen, "+${String.format(Locale.US, "%.2f", log.realizedPnlPct ?: log.maxProfitPct)}%")
            "HIT_TP1" -> Triple("🎯 TARGET TP1 TERCAPAI", tvGreen, "+${String.format(Locale.US, "%.2f", log.realizedPnlPct ?: log.maxProfitPct)}%")
            "HIT_SL" -> Triple("🛑 STOP LOSS TERSENTUH", tvRed, "${String.format(Locale.US, "%.2f", log.realizedPnlPct ?: log.maxDrawdownPct)}%")
            "MANUAL_WIN" -> Triple("✅ SELESAI (PROFIT MANUAL)", tvGreen, "+${String.format(Locale.US, "%.2f", log.realizedPnlPct ?: log.maxProfitPct)}%")
            "MANUAL_LOSS" -> Triple("❌ SELESAI (CUT LOSS MANUAL)", tvRed, "${String.format(Locale.US, "%.2f", log.realizedPnlPct ?: log.maxDrawdownPct)}%")
            "EXPIRED" -> Triple("⏳ KEDALUWARSA", tvTextSecondary, "${String.format(Locale.US, "%.2f", log.realizedPnlPct ?: 0.0)}%")
            else -> Triple("⏳ SEDANG DIPANTAU", tvCyan, "Puncak: +${String.format(Locale.US, "%.2f", log.maxProfitPct)}%")
        }
    }

    val confidenceColor = when {
        log.confidence >= 80 -> tvGreen
        log.confidence >= 65 -> tvCyan
        else -> tvAmber
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = TvSurface,
        border = BorderStroke(1.dp, if (log.outcomeStatus == "TRACKING") TvCyan.copy(alpha = 0.4f) else TvBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggleExpand() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // HEADER ROW: Symbol, Action, Strategy, Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = actionColor.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, actionColor.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = log.action,
                            color = actionColor,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(Modifier.width(6.dp))

                    Text(
                        text = log.symbol,
                        color = TvTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black
                    )

                    Spacer(Modifier.width(6.dp))

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = TvSurfaceVariant
                    ) {
                        Text(
                            text = log.strategyMode,
                            color = TvTextSecondary,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = timeFormat.format(Date(log.firedAt)),
                    color = TvTextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.height(8.dp))

            // MIDDLE ROW: Entry Price, Confidence Score at Firing, Performance Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Harga Saat Sinyal:", color = TvTextSecondary, fontSize = 8.5.sp)
                    Text(
                        text = PriceFormatter.formatPrice(log.entryPrice),
                        color = TvTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Confidence Score Pill
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = confidenceColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, confidenceColor.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Skor AI: ", color = TvTextSecondary, fontSize = 8.5.sp)
                        Text(
                            text = "${log.confidence}%",
                            color = confidenceColor,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Outcome status tag
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusDetails.second.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, statusDetails.second.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusDetails.third,
                            color = statusDetails.second,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // TARGET PRICE LEVELS ROW
            if (log.targetPrice1 > 0 || log.stopLoss > 0) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TargetLevelPill("TP1", log.targetPrice1, TvGreen, Modifier.weight(1f))
                    TargetLevelPill("TP2", log.targetPrice2, TvGreen, Modifier.weight(1f))
                    TargetLevelPill("SL", log.stopLoss, TvRed, Modifier.weight(1f))
                }
            }

            // RESOLUTION NOTE IF AVAILABLE
            if (!log.resolutionNote.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Hasil: ${log.resolutionNote}",
                    color = statusDetails.second,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // EXPANDED DETAILS ACCORDION
            if (isExpanded) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = TvBorder, thickness = 0.5.dp)
                Spacer(Modifier.height(8.dp))

                if (log.reasoning.isNotBlank()) {
                    Text(
                        text = "Analisis Saat Sinyal Dipicu:",
                        color = TvTextSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = log.reasoning,
                        color = TvTextPrimary,
                        fontSize = 9.5.sp,
                        lineHeight = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Harga Puncak Tertinggi:", color = TvTextSecondary, fontSize = 8.5.sp)
                        Text(
                            text = if (log.peakPrice > 0) "${PriceFormatter.formatPrice(log.peakPrice)} (+${String.format(Locale.US, "%.2f", log.maxProfitPct)}%)" else "-",
                            color = TvGreen,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text("Drawdown Terendah:", color = TvTextSecondary, fontSize = 8.5.sp)
                        Text(
                            text = if (log.troughPrice > 0) "${PriceFormatter.formatPrice(log.troughPrice)} (${String.format(Locale.US, "%.2f", log.maxDrawdownPct)}%)" else "-",
                            color = TvRed,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ACTION BUTTONS FOR LOG
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = onNavigateToCoin,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f).height(32.dp),
                        contentPadding = PaddingValues(0.dp),
                        border = BorderStroke(1.dp, TvCyan.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.ShowChart, null, tint = TvCyan, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Buka Chart", color = TvCyan, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                    }

                    if (log.outcomeStatus == "TRACKING") {
                        Button(
                            onClick = onResolveManually,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1.2f).height(32.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TvGreen)
                        ) {
                            Text("Selesaikan Manual", color = Color.Black, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    IconButton(
                        onClick = onDeleteLog,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Hapus Log", tint = TvRed, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetLevelPill(label: String, price: Double, color: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.3f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, color = color, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
            Text(
                text = if (price > 0) PriceFormatter.formatPrice(price) else "-",
                color = TvTextPrimary,
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ManualResolveDialog(
    log: SignalLogEntity,
    onDismiss: () -> Unit,
    onConfirmResolve: (isWin: Boolean, note: String) -> Unit
) {
    var isWin by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Selesaikan Sinyal ${log.symbol} Secara Manual",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Pilih hasil evaluasi akhir jika Anda telah menutup order atau ingin mencatat hasil trading ini ke histori:",
                    color = TvTextSecondary,
                    fontSize = 11.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = isWin,
                        onClick = { isWin = true },
                        label = { Text("Menang (Profit)", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TvGreen.copy(alpha = 0.2f),
                            selectedLabelColor = TvGreen
                        )
                    )

                    FilterChip(
                        selected = !isWin,
                        onClick = { isWin = false },
                        label = { Text("Kalah (Cut Loss)", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TvRed.copy(alpha = 0.2f),
                            selectedLabelColor = TvRed
                        )
                    )
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Catatan / Alasan Keluar (Opsional)", fontSize = 10.sp) },
                    placeholder = { Text("misal: Take profit manual di +3.2%", fontSize = 10.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = Color.White),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalNote = if (note.isNotBlank()) note else (if (isWin) "Take profit manual" else "Cut loss manual")
                    onConfirmResolve(isWin, finalNote)
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (isWin) TvGreen else TvRed)
            ) {
                Text(if (isWin) "Simpan Menang" else "Simpan Kalah", color = if (isWin) Color.Black else Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Batal", color = TvTextSecondary)
            }
        },
        containerColor = TvSurface,
        shape = RoundedCornerShape(14.dp)
    )
}
