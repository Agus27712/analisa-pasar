package agu.analys.bridge

import agu.analys.model.MarketTick
import kotlin.math.roundToInt

/**
 * Perhitungan pemindaian & pemeringkatan seluruh pair pasar di sisi server (murni Kotlin).
 *
 * Tidak menyentuh jaringan, SharedPreferences, atau UI Android agar mudah diuji unit beserta
 * kontrol negatifnya. Angka `serverScore` (0..100) adalah peringkat kuantitatif kondisi ticker
 * saat ini untuk menyaring kandidat ke Tahap 2 (`ModeSignalEvaluator`), **bukan** jaminan
 * profit atau win-rate historis.
 */
object AgentMarketScanner {

    /** Batas perubahan 24 jam sebelum pair dianggap terlalu melambung (selaras batas scalping 15%). */
    const val OVEREXTENSION_CHANGE_PCT = 15.0

    private val STABLE_BASES = setOf("USDT", "USDC", "DAI", "BUSD", "TUSD", "FDUSD", "IDRT", "EUR", "GBP", "AUD")

    /**
     * Validasi ketat input pilihan 1 pair dari agen.
     *
     * Menolak input kosong, simbol majemuk (`BTCUSDT,ETHUSDT`, spasi, titik koma, baris baru,
     * tanda tambah/pipa, atau format array JSON `[...]`), lalu menormalisasi lewat satu definisi
     * simbol aplikasi ([AgentEngineAnalysisMapper.normalizeSymbol]).
     *
     * @return simbol ternormalisasi jika tepat 1 pair valid, atau `null` jika ditolak.
     */
    fun parseSingleSymbolInput(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it == ',' || it == ';' || it == '|' || it == '+' || it == '[' || it == ']' || it == '{' || it == '}' || it.isWhitespace() }) {
            return null
        }
        return AgentEngineAnalysisMapper.normalizeSymbol(trimmed)
    }

    /**
     * Posisi harga terkini di dalam rentang 24 jam [0..100].
     *
     * 0 = tepat di `low24h`, 100 = tepat di `high24h`, 50 = rentang datar / tidak valid.
     */
    fun computeRangePositionPct(price: Double, high24h: Double, low24h: Double): Double {
        if (!price.isFinite() || price <= 0.0) return 50.0
        if (!high24h.isFinite() || !low24h.isFinite() || high24h <= low24h || low24h <= 0.0) return 50.0
        val raw = ((price - low24h) / (high24h - low24h)) * 100.0
        return raw.coerceIn(0.0, 100.0)
    }

    /** Ekstrak base & quote dari simbol yang sudah ternormalisasi (`BTCUSDT` -> `BTC` + `USDT`). */
    fun splitBaseQuote(normalizedSymbol: String): Pair<String, String>? {
        val upper = normalizedSymbol.trim().uppercase()
        val quote = AgentEngineAnalysisMapper.quoteOf(upper)
        if (quote.isEmpty() || upper.length <= quote.length) return null
        val base = upper.removeSuffix(quote)
        return base to quote
    }

    /** Cek apakah pasangan aset murni stablecoin/fiat yang tidak perlu discan untuk kenaikan harga. */
    fun isStableOrFiatPair(normalizedSymbol: String): Boolean {
        val (base, quote) = splitBaseQuote(normalizedSymbol) ?: return true
        if (quote != "USDT" && quote != "IDR") return true
        return base in STABLE_BASES
    }

    /** Volume 24 jam yang disetarakan ke IDR agar pair IDR dan USDT di Tokocrypto dapat dibandingkan adil. */
    fun volumeIdrOf(symbol: String, volume24h: Double, usdtIdrRate: Double): Double {
        if (!volume24h.isFinite() || volume24h <= 0.0) return 0.0
        val quote = AgentEngineAnalysisMapper.quoteOf(symbol)
        return if (quote == "USDT") {
            val safeRate = if (usdtIdrRate > 1000.0) usdtIdrRate else 0.0
            volume24h * safeRate
        } else {
            volume24h
        }
    }

    /**
     * Hitung skor kuantitatif server dan urutkan **semua** pair layak untuk **satu bursa** saja.
     *
     * - `INDODAX`: hanya kuotasi `IDR`.
     * - `TOKOCRYPTO`: kuotasi `USDT` dan `IDR` (tanpa BIDR).
     */
    fun scoreAllPairs(
        exchange: String,
        ticks: Collection<MarketTick>,
        usdtIdrRate: Double,
        quoteFilter: String? = null
    ): List<AgentMarketPairItem> {
        val ex = exchange.trim().uppercase()
        val qFilter = quoteFilter?.trim()?.uppercase()?.takeIf { it.isNotEmpty() && it != "ALL" }

        val cleaned = ticks
            .asSequence()
            .mapNotNull { tick ->
                val norm = AgentEngineAnalysisMapper.normalizeSymbol(tick.symbol) ?: return@mapNotNull null
                if (!tick.price.isFinite() || tick.price <= 0.0) return@mapNotNull null
                if (isStableOrFiatPair(norm)) return@mapNotNull null
                val quote = AgentEngineAnalysisMapper.quoteOf(norm)
                if (ex == "INDODAX" && quote != "IDR") return@mapNotNull null
                if (qFilter != null && quote != qFilter) return@mapNotNull null
                // Floor harga IDR dan volume minimum agar pair mati tidak masuk peringkat atas
                val vol = if (tick.volume24h.isFinite() && tick.volume24h > 0.0) tick.volume24h else 0.0
                if (quote == "IDR" && (tick.price <= 5.0 || vol < 1_000_000.0)) return@mapNotNull null
                if (quote == "USDT" && vol < 10_000.0) return@mapNotNull null
                norm to tick.copy(symbol = norm)
            }
            .distinctBy { it.first }
            .map { it.second }
            .toList()

        if (cleaned.isEmpty()) return emptyList()

        val volumesIdr = cleaned.map { volumeIdrOf(it.symbol, it.volume24h, usdtIdrRate) }
        val maxVolLog = volumesIdr.maxOfOrNull { kotlin.math.ln(1.0 + it) }?.takeIf { it > 0.0 } ?: 1.0

        return cleaned.mapIndexed { idx, tick ->
            val volIdr = volumesIdr[idx]
            scoreSingleTick(tick, volIdr, maxVolLog)
        }.sortedWith(
            compareByDescending<AgentMarketPairItem> { it.serverScore }
                .thenByDescending { it.volumeIdr }
        )
    }

    private fun scoreSingleTick(
        tick: MarketTick,
        volumeIdr: Double,
        maxVolLog: Double
    ): AgentMarketPairItem {
        val (base, quote) = splitBaseQuote(tick.symbol) ?: (tick.symbol to "")
        val change = if (tick.change24h.isFinite()) tick.change24h else 0.0
        val rangePct = computeRangePositionPct(tick.price, tick.high24h, tick.low24h)

        // 1. Komponen Momentum (0..40 poin): kenaikan sehat 0.5%..12% mendapat bobot tertinggi;
        //    lonjakan ekstrem > 15% mendapat penalti overextension agar tidak mengejar pucuk.
        val momentumPts = when {
            change in 1.5..8.0 -> 34.0 + ((change - 1.5) / 6.5) * 6.0 // 34..40
            change > 8.0 && change <= 12.0 -> 34.0 - ((change - 8.0) / 4.0) * 6.0 // 34..28
            change > 12.0 && change <= OVEREXTENSION_CHANGE_PCT -> 28.0 - ((change - 12.0) / 3.0) * 10.0 // 28..18
            change > OVEREXTENSION_CHANGE_PCT -> (18.0 - (change - OVEREXTENSION_CHANGE_PCT) * 0.8).coerceAtLeast(2.0)
            change in 0.2..1.5 -> 24.0 + ((change - 0.2) / 1.3) * 10.0 // 24..34
            change in -1.5..0.2 -> 14.0 + ((change + 1.5) / 1.7) * 10.0 // 14..24
            else -> (14.0 + (change + 1.5) * 1.2).coerceAtLeast(0.0)
        }

        // 2. Komponen Posisi Rentang 24 Jam (0..30 poin): posisi 55%..88% menandakan kekuatan naik
        //    dari low harian tanpa terlalu mepet pucuk >95%.
        val rangePts = when {
            rangePct in 55.0..88.0 -> 25.0 + ((rangePct - 55.0) / 33.0) * 5.0 // 25..30
            rangePct > 88.0 -> 26.0 - ((rangePct - 88.0) / 12.0) * 8.0 // 26..18
            rangePct in 35.0..55.0 -> 15.0 + ((rangePct - 35.0) / 20.0) * 10.0 // 15..25
            else -> (rangePct / 35.0) * 15.0
        }

        // 3. Komponen Likuiditas Volume IDR (0..30 poin, skala logaritmik)
        val volLog = kotlin.math.ln(1.0 + volumeIdr.coerceAtLeast(0.0))
        val liquidityPts = ((volLog / maxVolLog) * 30.0).coerceIn(0.0, 30.0)

        val totalScore = ((momentumPts + rangePts + liquidityPts).coerceIn(0.0, 100.0) * 10.0).roundToInt() / 10.0

        val tier = when {
            change > OVEREXTENSION_CHANGE_PCT -> "OVEREXTENDED"
            totalScore >= 72.0 && change >= 1.0 -> "STRONG_UP"
            totalScore >= 56.0 && change >= -0.5 -> "BUILDING_UP"
            totalScore >= 40.0 -> "NEUTRAL"
            else -> "WEAK"
        }

        val changeStr = String.format(java.util.Locale.US, "%+.2f%%", change)
        val rangeStr = String.format(java.util.Locale.US, "%.0f%%", rangePct)
        val reason = when (tier) {
            "STRONG_UP" -> "Momentum naik sehat ($changeStr), posisi rentang 24j $rangeStr, likuiditas tinggi"
            "BUILDING_UP" -> "Dorongan naik terbentuk ($changeStr), posisi rentang 24j $rangeStr"
            "OVEREXTENDED" -> "Sudah melonjak tinggi ($changeStr > ${OVEREXTENSION_CHANGE_PCT.toInt()}%), rawan koreksi"
            "NEUTRAL" -> "Konsolidasi ($changeStr), posisi rentang 24j $rangeStr"
            else -> "Momentum lemah ($changeStr), posisi rentang 24j $rangeStr"
        }

        return AgentMarketPairItem(
            symbol = tick.symbol,
            baseAsset = base,
            quoteAsset = quote,
            price = tick.price,
            change24h = (change * 100.0).roundToInt() / 100.0,
            high24h = tick.high24h,
            low24h = tick.low24h,
            volume24h = tick.volume24h,
            volumeIdr = volumeIdr,
            rangePositionPct = (rangePct * 10.0).roundToInt() / 10.0,
            serverScore = totalScore,
            momentumTier = tier,
            reasonSummary = reason
        )
    }
}

/**
 * Penjaga jadwal & deduplikasi emit hasil pemindaian pasar ke agenfeed (`AgentEventBus`).
 *
 * Pola identik dengan [AgentPortfolioPusher]: snapshot yang peringkat teratasnya belum berubah
 * tidak dipancarkan ulang tiap siklus sampai batas heartbeat tercapai, tetapi pergantian bursa
 * aktif atau perubahan kandidat utama langsung memicu emit baru.
 */
object AgentMarketScannerPusher {

    /** Jeda debounce event-driven saat menerima pembaruan tick pasar live (1,5 detik). */
    const val LIVE_DEBOUNCE_MS = 1_500L

    /** Interval cadangan saat tidak ada aliran tick live masuk (20 detik, bukan 60 detik). */
    const val SCAN_INTERVAL_MS = 20_000L

    /** Interval penyegaran penuh REST sebagai jaring pengaman jika WS ticker bursa senyap (45 detik). */
    const val REST_REFRESH_GUARD_MS = 45_000L

    /** Batas maksimum diam sebelum mengirim heartbeat snapshot terbaru (5 menit). */
    const val HEARTBEAT_MS = 5 * 60_000L

    fun signature(scan: AgentMarketScanOutput): String = buildString {
        append(scan.exchange).append('|')
        append(scan.available).append('|')
        append(scan.totalPairsScanned).append('|')
        append(scan.bullishCount).append('|')
        scan.topCandidates.take(8).forEach { c ->
            val scoreBucket = (c.serverScore * 2.0).roundToInt() // resolusi 0.5 poin
            val priceBucket = if (c.price > 0.0 && c.price.isFinite()) {
                kotlin.math.round(kotlin.math.ln(c.price) * 1000.0).toLong() // ~0.1% sensitivitas
            } else 0L
            val bias = c.engineAnalysis?.overallBias ?: "-"
            val scalpAction = c.engineAnalysis?.scalping?.action ?: "-"
            val scalpStage = c.engineAnalysis?.scalping?.stage ?: "-"
            append(c.symbol).append(':')
            append(scoreBucket).append(':')
            append(priceBucket).append(':')
            append(c.momentumTier).append(':')
            append(bias).append(':')
            append(scalpAction).append(':')
            append(scalpStage).append(';')
        }
    }

    fun shouldEmit(
        previousSignature: String?,
        nextSignature: String,
        nowMs: Long,
        lastEmitMs: Long,
        heartbeatMs: Long = HEARTBEAT_MS
    ): Boolean {
        if (previousSignature == null) return true
        if (nextSignature != previousSignature) return true
        return nowMs - lastEmitMs >= heartbeatMs
    }
}
