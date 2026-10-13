package agu.analys.viewmodel

import agu.analys.engine.readiness.CandleSource
import agu.analys.engine.readiness.ReadinessBoard
import agu.analys.engine.readiness.ReadinessScanner
import agu.analys.engine.readiness.ReadinessState
import agu.analys.engine.readiness.TradeHorizon
import agu.analys.model.Timeframe
import agu.analys.util.MtfCacheManager
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Papan kesiapan multi-horizon — jembatan antara engine (TEAM 1) dan UI (TEAM 2).
 *
 * ## Kenapa membaca cache, bukan mengambil data
 *
 * Pemindaian menyentuh **puluhan simbol × 5 timeframe**. Bila setiap pemindaian memanggil bursa,
 * aplikasi akan langsung kena rate-limit. Karena itu sumber candle-nya adalah
 * [MtfCacheManager.getCachedCandles] — pembacaan **sinkron dari memori**, tanpa I/O. Pengisian
 * cache tetap tugas alur yang sudah ada (`WatchlistViewModel` → `MtfCacheManager.updateQueues`).
 *
 * Konsekuensinya jujur: simbol yang candle-nya belum pernah diambil akan tampil sebagai
 * `DATA KURANG`, bukan dikira-kira. [requestReadinessData] memberi pengguna cara meminta
 * pengambilan data untuk timeframe yang belum ada.
 */
private const val READINESS_MAX_SYMBOLS = 40
private const val READINESS_REFRESH_MS = 60_000L

/** Batas permintaan pengambilan data manual, supaya tidak membanjiri bursa. */
private const val READINESS_MAX_FETCH_SYMBOLS = 8

/**
 * Simbol yang ikut dinilai: pair yang sedang dibuka, seluruh daftar pantau, lalu koin yang
 * sudah punya tick. Urutan ini membuat pair aktif selalu ikut dinilai walau belum masuk pantau.
 */
fun TradingViewModel.readinessSymbols(): List<String> {
    val out = LinkedHashSet<String>()
    selectedPair.value.symbol.trim().takeIf { it.isNotEmpty() }?.let { out += it }
    watchlist.value.forEach { if (it.isNotBlank()) out += it.trim() }
    dashboardTicks.value.keys.forEach { if (it.isNotBlank()) out += it.trim() }
    return out.take(READINESS_MAX_SYMBOLS)
}

/**
 * Hitung ulang papan kesiapan dari cache.
 *
 * Pemindaian lama dibatalkan lebih dulu supaya hasil yang dipublikasikan tidak pernah berasal dari
 * campuran dua waktu pengambilan data.
 */
fun TradingViewModel.refreshReadinessBoard() {
    val symbols = readinessSymbols()
    if (symbols.isEmpty()) {
        _readinessBoard.value = ReadinessBoard.EMPTY
        return
    }

    readinessRefreshJob?.cancel()
    readinessRefreshJob = viewModelScope.launch {
        _readinessLoading.value = true
        try {
            val fees = tradingFees.value
            val exchange = marketDataSource.value.name
            val ticks = dashboardTicks.value

            val board = withContext(Dispatchers.Default) {
                ReadinessScanner.scan(
                    symbols = symbols,
                    source = CandleSource { symbol, tf ->
                        MtfCacheManager.getCachedCandles(symbol, tf, exchange)
                    },
                    fees = fees,
                    priceOf = { s -> ticks[s]?.price?.takeIf { p -> p > 0.0 } }
                )
            }
            _readinessBoard.value = board
        } finally {
            _readinessLoading.value = false
        }
    }
}

/** Ganti horizon yang sedang dilihat di papan. */
fun TradingViewModel.setReadinessHorizon(horizon: TradeHorizon) {
    _readinessHorizon.value = horizon
}

/**
 * Minta pengambilan candle untuk timeframe yang **belum ada** pada simbol yang sedang terlihat.
 *
 * Dijalankan atas permintaan pengguna (tombol), bukan otomatis: `MtfCacheManager` hanya
 * mem-prefetch M1/M15/H1/H4, sehingga SWING (butuh D1) hampir selalu `DATA KURANG` sampai
 * diminta secara sadar. Dibatasi [READINESS_MAX_FETCH_SYMBOLS] simbol sekali tekan.
 */
fun TradingViewModel.requestReadinessData(): Int {
    val exchange = marketDataSource.value.name
    val horizon = _readinessHorizon.value
    val symbols = _readinessBoard.value
        .forHorizon(horizon)
        .filter { it.state == ReadinessState.NO_DATA }
        .map { it.symbol }
        .take(READINESS_MAX_FETCH_SYMBOLS)
        .ifEmpty { readinessSymbols().take(READINESS_MAX_FETCH_SYMBOLS) }

    if (symbols.isEmpty()) return 0

    var requested = 0
    for (symbol in symbols) {
        for (tf in horizon.timeframes) {
            if (MtfCacheManager.getCachedCandles(symbol, tf, exchange).isNullOrEmpty()) {
                MtfCacheManager.retryTimeframe(symbol, tf, exchange)
                requested++
            }
        }
    }
    return requested
}

/**
 * Pindai berkala.
 *
 * Dipanggil sekali dari [initSubscriptionsAndPolling]. Interval 60 detik dipilih karena
 * `MtfCacheManager` sendiri menyegarkan candle M15 setiap 30 menit dan M1 setiap 3 menit —
 * memindai lebih sering hanya akan menghitung ulang data yang sama.
 */
fun TradingViewModel.startReadinessPolling() {
    viewModelScope.launch {
        while (true) {
            refreshReadinessBoard()
            delay(READINESS_REFRESH_MS)
        }
    }
}

/** Timeframe yang dibutuhkan papan untuk horizon tertentu — dipakai UI untuk menjelaskan. */
fun TradeHorizon.requiredTimeframes(): List<Timeframe> = timeframes
