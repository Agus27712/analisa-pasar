package agu.analys.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import agu.analys.engine.readiness.HorizonReadiness
import agu.analys.engine.readiness.ReadinessSource
import agu.analys.engine.readiness.ReadinessState
import agu.analys.engine.readiness.TradeHorizon
import agu.analys.ui.components.readiness.PlanRow
import agu.analys.ui.components.readiness.ReadinessCheckRow
import agu.analys.ui.components.readiness.ReadinessScoreBar
import agu.analys.ui.components.readiness.ReadinessSourceBadge
import agu.analys.ui.components.readiness.ReadinessStateChip
import agu.analys.ui.components.readiness.UnvalidatedBadge
import agu.analys.ui.components.readiness.horizonIcon
import agu.analys.ui.components.readiness.readinessStateColor
import agu.analys.ui.theme.TvAmber
import agu.analys.ui.theme.TvBackground
import agu.analys.ui.theme.TvBorder
import agu.analys.ui.theme.TvCyan
import agu.analys.ui.theme.TvGreen
import agu.analys.ui.theme.TvRed
import agu.analys.ui.theme.TvSurface
import agu.analys.ui.theme.TvSurfaceVariant
import agu.analys.ui.theme.TvTextMuted
import agu.analys.ui.theme.TvTextPrimary
import agu.analys.ui.theme.TvTextSecondary
import agu.analys.util.PriceFormatter
import agu.analys.viewmodel.TradingViewModel
import agu.analys.viewmodel.refreshReadinessBoard
import agu.analys.viewmodel.requestReadinessData
import agu.analys.viewmodel.setReadinessHorizon

/**
 * **PAPAN KESIAPAN** (TEAM 2).
 *
 * Menjawab pertanyaan yang sebelumnya tidak bisa dijawab aplikasi: *"dari semua koin yang saya
 * pantau, mana yang benar-benar siap masuk — dan untuk horizon apa?"*
 *
 * ## Keputusan tampilan
 *
 * 1. **Tiga horizon sebagai pilihan, bukan tiga daftar bertumpuk.** Satu koin bisa muncul di
 *    ketiganya dengan status berbeda; memisahkannya membuat jawaban langsung terbaca.
 * 2. **Responsif lewat lebar nyata, bukan orientasi.** `BoxWithConstraints` memilih 1 atau 2
 *    kolom. Aplikasi ini sebelumnya hanya punya satu jalan untuk layar lebar (memaksa landscape),
 *    yang tidak berguna di tablet atau jendela desktop.
 * 3. **Alasan selalu terlihat.** Setiap baris menampilkan syarat mana yang gagal; papan yang
 *    hanya berkata "belum siap" tanpa alasan tidak bisa ditindaklanjuti.
 * 4. **`BELUM DIVALIDASI` ditampilkan pada horizon yang belum diuji data.** Pengguna berhak tahu
 *    bahwa INTRADAY/SWING masih heuristik.
 * 5. **`ENGINE AKTIF` dan `PRA-SARING` dipisah tegas.** Baris horizon yang engine-nya sedang
 *    dipakai berasal dari engine itu sendiri; sisanya saringan daftar. Tanpa pemisahan ini papan
 *    tampak membantah kartu engine (laporan lapangan 13 Okt).
 */
@Composable
fun ReadinessBoardScreen(
    viewModel: TradingViewModel,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit
) {
    val board by viewModel.readinessBoard.collectAsStateWithLifecycle()
    val loading by viewModel.readinessLoading.collectAsStateWithLifecycle()
    val horizon by viewModel.readinessHorizon.collectAsStateWithLifecycle()
    val strategyMode by viewModel.strategyMode.collectAsStateWithLifecycle()
    val activeHorizon = remember(strategyMode) { TradeHorizon.of(strategyMode) }

    var filter by remember { mutableStateOf(BoardFilter.SEMUA) }
    var expandedSymbol by remember { mutableStateOf<String?>(null) }

    // Baris engine selalu di atas: bila engine sudah menilai koin ini, itu jawaban yang dicari
    // pengguna — bukan hasil saringan.
    val rows = remember(board, horizon, filter) {
        board.forHorizon(horizon)
            .filter { filter.matches(it.state) }
            .sortedWith(compareByDescending<HorizonReadiness> { it.fromEngine }.thenByDescending { it.score })
    }
    val quoteAsset = remember { PriceFormatter.extractQuote(viewModel.selectedPair.value.symbol) }

    Column(
        Modifier
            .fillMaxSize()
            .background(TvBackground)
    ) {
        BoardHeader(
            loading = loading,
            activeHorizon = activeHorizon,
            onBack = onBack,
            onRefresh = { viewModel.refreshReadinessBoard() },
            onRequestData = { viewModel.requestReadinessData() }
        )

        HorizonSelector(
            selected = horizon,
            activeHorizon = activeHorizon,
            board = board,
            onSelect = { viewModel.setReadinessHorizon(it) }
        )

        FilterRow(selected = filter, onSelect = { filter = it })

        BoardLegend(activeHorizon = activeHorizon)

        if (rows.isEmpty()) {
            EmptyBoardState(
                horizon = horizon,
                filter = filter,
                onRequestData = { viewModel.requestReadinessData() }
            )
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Lebar nyata menentukan jumlah kolom: >= 600dp muat dua kolom yang masih terbaca.
                val columns = if (maxWidth >= 600.dp) 2 else 1
                val grouped = remember(rows, columns) { rows.chunked(columns) }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(grouped, key = { it.joinToString("|") { r -> "${r.symbol}-${r.horizon}" } }) { rowGroup ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowGroup.forEach { row ->
                                Box(Modifier.weight(1f)) {
                                    BoardItem(
                                        row = row,
                                        quoteAsset = quoteAsset,
                                        expanded = expandedSymbol == row.symbol,
                                        onToggle = {
                                            expandedSymbol =
                                                if (expandedSymbol == row.symbol) null else row.symbol
                                        },
                                        onOpenDetail = { onOpenDetail(row.symbol) }
                                    )
                                }
                            }
                            // Isi ruang kosong bila kolom terakhir tidak penuh.
                            repeat(columns - rowGroup.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Bagian layar ────────────────────────────────────────────────────────────

@Composable
private fun BoardHeader(
    loading: Boolean,
    activeHorizon: TradeHorizon,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRequestData: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(TvSurface)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Kembali",
            tint = TvTextPrimary,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onBack() }
                .padding(8.dp)
        )
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Default.Radar, null, tint = TvGreen, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "PAPAN KESIAPAN",
                color = TvTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Text(
                "Engine aktif: ${activeHorizon.label.uppercase()} · sisanya pra-saring",
                color = TvTextMuted,
                fontSize = 10.sp
            )
        }

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = TvGreen
            )
            Spacer(Modifier.width(10.dp))
        }

        Icon(
            Icons.Default.Sync,
            contentDescription = "Minta data candle",
            tint = TvTextSecondary,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onRequestData() }
                .padding(8.dp)
        )
        Icon(
            Icons.Default.Radar,
            contentDescription = "Hitung ulang",
            tint = TvTextSecondary,
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onRefresh() }
                .padding(8.dp)
        )
    }
}

@Composable
private fun HorizonSelector(
    selected: TradeHorizon,
    activeHorizon: TradeHorizon,
    board: agu.analys.engine.readiness.ReadinessBoard,
    onSelect: (TradeHorizon) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(TvSurface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TradeHorizon.entries.forEach { horizon ->
            val active = horizon == selected
            val isEngineHorizon = horizon == activeHorizon
            val ready = board.countOf(horizon, ReadinessState.READY)
            val accent = if (active) TvGreen else TvTextSecondary
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) TvGreen.copy(alpha = 0.10f) else TvSurfaceVariant.copy(alpha = 0.5f))
                    .border(
                        if (isEngineHorizon) 1.5.dp else 1.dp,
                        when {
                            isEngineHorizon -> TvCyan.copy(alpha = 0.6f)
                            active -> TvGreen.copy(alpha = 0.5f)
                            else -> TvBorder
                        },
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelect(horizon) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        horizonIcon(horizon),
                        null,
                        tint = if (isEngineHorizon) TvCyan else accent,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        horizon.shortLabel,
                        color = accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text("$ready siap", color = if (active) TvGreen else TvTextMuted, fontSize = 11.sp)
                Text(
                    text = if (isEngineHorizon) "ENGINE AKTIF" else horizon.holdingWindow,
                    color = if (isEngineHorizon) TvCyan else TvTextMuted,
                    fontSize = 9.sp,
                    fontWeight = if (isEngineHorizon) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

private enum class BoardFilter(val label: String) {
    SEMUA("Semua"),
    SIAP("Siap"),
    MEMANAS("Memanas"),
    PANTAU("Pantau");

    fun matches(state: ReadinessState): Boolean = when (this) {
        SEMUA -> true
        SIAP -> state == ReadinessState.READY
        MEMANAS -> state == ReadinessState.WARMING
        PANTAU -> state == ReadinessState.WATCH || state == ReadinessState.BLOCKED ||
            state == ReadinessState.NO_DATA || state == ReadinessState.HOLDING
    }
}

@Composable
private fun FilterRow(
    selected: BoardFilter,
    onSelect: (BoardFilter) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        BoardFilter.entries.forEach { f ->
            val active = f == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (active) TvGreen.copy(alpha = 0.16f) else TvSurfaceVariant.copy(alpha = 0.5f))
                    .border(
                        1.dp,
                        if (active) TvGreen.copy(alpha = 0.5f) else TvBorder,
                        RoundedCornerShape(20.dp)
                    )
                    .clickable { onSelect(f) }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    f.label,
                    color = if (active) TvGreen else TvTextSecondary,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * Legenda dua jenis baris.
 *
 * Selalu terlihat (bukan disembunyikan di dialog bantuan) karena inilah satu-satunya cara pembaca
 * tahu mengapa sebuah koin bisa berstatus `PANTAU` di papan sementara kartu engine-nya berkata
 * lain: keduanya menjawab pertanyaan yang berbeda, dan sekarang hal itu tertulis di layar.
 */
@Composable
private fun BoardLegend(activeHorizon: TradeHorizon) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReadinessSourceBadge(ReadinessSource.ENGINE)
            Spacer(Modifier.width(6.dp))
            Text(
                text = "penilaian engine ${activeHorizon.label.uppercase()} (pilihan Anda di Pengaturan)",
                color = TvTextSecondary,
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReadinessSourceBadge(ReadinessSource.PRESCREEN)
            Spacer(Modifier.width(6.dp))
            Text(
                text = "saringan biaya & tren — bukan keputusan engine",
                color = TvTextSecondary,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun EmptyBoardState(
    horizon: TradeHorizon,
    filter: BoardFilter,
    onRequestData: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(horizonIcon(horizon), null, tint = TvTextMuted, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (filter == BoardFilter.SEMUA) {
                "Belum ada koin yang bisa dinilai untuk horizon ${horizon.label}."
            } else {
                "Tidak ada koin dengan status ${filter.label} di horizon ${horizon.label}."
            },
            color = TvTextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Papan menilai dari candle yang sudah ada di cache. " +
                "Timeframe ${horizon.timeframes.joinToString(", ") { it.label }} " +
                "belum tentu sudah diambil.",
            color = TvTextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(TvGreen.copy(alpha = 0.14f))
                .border(1.dp, TvGreen.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .clickable { onRequestData() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text("Ambil data candle", color = TvGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BoardItem(
    row: HorizonReadiness,
    quoteAsset: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenDetail: () -> Unit
) {
    val color = readinessStateColor(row.state)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.06f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .clickable { onToggle() }
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.symbol,
                color = TvTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            ReadinessStateChip(row.state)
        }

        Spacer(Modifier.height(5.dp))
        ReadinessSourceBadge(row.source)

        Spacer(Modifier.height(6.dp))
        ReadinessScoreBar(score = row.score, color = color)
        Spacer(Modifier.height(6.dp))

        Text(
            text = row.headline,
            color = TvTextSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )

        row.blocker?.let { blocker ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = blocker,
                color = TvTextMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                maxLines = if (expanded) 6 else 2
            )
        }

        if (!row.horizon.validatedOutOfSample) {
            Spacer(Modifier.height(5.dp))
            UnvalidatedBadge()
        }

        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                row.checks.forEach { ReadinessCheckRow(it) }

                row.plan?.let { plan ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "RENCANA LONG",
                        color = TvTextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    PlanRow("Entry", PriceFormatter.formatPriceFull(plan.entry, quoteAsset))
                    PlanRow(
                        "SL",
                        "${PriceFormatter.formatPriceFull(plan.stopLoss, quoteAsset)} (−${pct(plan.riskPct)}%)",
                        TvRed
                    )
                    PlanRow(
                        "TP2",
                        "${PriceFormatter.formatPriceFull(plan.takeProfit2, quoteAsset)} (+${pct(plan.rewardPct)}%)",
                        TvGreen
                    )
                    PlanRow("Net R:R", "${pct(plan.netRr)}×", TvGreen)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = plan.exitRule,
                        color = TvTextMuted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }

                row.sourceNote?.let { note ->
                    Spacer(Modifier.height(6.dp))
                    Text(note, color = TvTextMuted, fontSize = 10.sp, lineHeight = 14.sp)
                }

                if (row.state == ReadinessState.READY) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(TvAmber.copy(alpha = 0.12f))
                            .border(1.dp, TvAmber.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (row.fromEngine) {
                                "Status ini keputusan engine yang sedang aktif, bukan ajakan beli. " +
                                    "Eksekusi uang riil masih diblokir (lihat SCALP_EXIT.md)."
                            } else {
                                "Status SIAP adalah hasil saringan, bukan ajakan beli. " +
                                    "Eksekusi uang riil masih diblokir (lihat SCALP_EXIT.md)."
                            },
                            color = TvAmber,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(TvSurfaceVariant.copy(alpha = 0.6f))
                        .clickable { onOpenDetail() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("Lihat chart", color = TvTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun pct(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
