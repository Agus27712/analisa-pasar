package agu.analys.engine.scalping

import agu.analys.model.CandleBar
import kotlin.math.abs
import kotlin.math.max

/**
 * Alasan exit paper trade — **wajib** sama dengan `exit_reason` di `tools/scalp_replay.py`
 * supaya statistik app dan replay bisa dibandingkan.
 */
enum class PaperExitReason(val key: String, val label: String) {
    SL("SL", "Stop Loss"),
    BE_SL("BE_SL", "Breakeven Stop"),
    TRAIL_SL("TRAIL_SL", "Trailing Stop"),
    TP2("TP2", "Target Profit 2"),
    EARLY_PROFIT("EARLY_PROFIT", "Early Profit"),
    TIMEOUT("TIMEOUT", "Timeout / lookahead habis"),
    BAD_RISK("bad_risk", "Risiko tidak valid");

    companion object {
        /** Hanya alasan ini yang dihitung sebagai trade selesai. */
        val RESOLVED: Set<PaperExitReason> = setOf(SL, BE_SL, TRAIL_SL, TP2, EARLY_PROFIT)

        /** Parser kebal untuk jurnal — nilai tak dikenal jatuh ke [TIMEOUT]. */
        fun fromKey(key: String?): PaperExitReason =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: TIMEOUT
    }
}

enum class PaperExitResult(val key: String) {
    WIN("WIN"),
    LOSS("LOSS"),
    UNRESOLVED("UNRESOLVED");

    companion object {
        /** Parser kebal untuk jurnal — nilai tak dikenal jatuh ke [UNRESOLVED]. */
        fun fromKey(key: String?): PaperExitResult =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: UNRESOLVED
    }
}

/** Fase state machine exit — dipakai untuk indikator FSM di UI paper trade. */
enum class PaperExitPhase(val label: String) {
    HOLDING("Posisi terbuka"),
    TP1_PARTIAL("TP1 partial — SL siap ke BE"),
    BREAKEVEN("SL di breakeven net"),
    TRAILING("Trailing dari peak"),
    CLOSED("Posisi tertutup")
}

data class PaperExitDecision(
    val result: PaperExitResult,
    val r: Double,
    val exitIndex: Int?,
    val ambiguous: Boolean,
    val reason: PaperExitReason,
    /** Harga acuan saat posisi ditutup (level SL aktif / TP2 / close bar). 0.0 bila tak berlaku. */
    val exitPrice: Double = 0.0
) {
    val isResolved: Boolean get() = reason in PaperExitReason.RESOLVED
}

/**
 * Mesin exit paper trade (P2) — **paritas** dengan `resolve_trade()` di `tools/scalp_replay.py`.
 *
 * Aturan satuan R seragam untuk SEMUA mode exit:
 * ```
 * R = net_profit_pct(harga_exit) / risk_pct(entry -> sl)
 * ```
 * `net_profit_pct` sudah memperhitungkan buy fee + sell fee + 2x slippage, jadi SL kena
 * **-1R ditambah biaya**, bukan -1R datar.
 *
 * Objek ini murni (tanpa state global) agar bisa diuji dan dipakai ulang oleh
 * [PaperExitStateMachine] maupun replay.
 */
object PaperExitResolver {

    /** Profit net % setelah buy fee + sell fee + 2x slippage. */
    fun netProfitPct(
        entry: Double,
        price: Double,
        buyFeePct: Double,
        sellFeePct: Double,
        slippagePct: Double
    ): Double {
        if (entry <= 0.0 || price <= 0.0) return 0.0
        val buyCost = 1.0 + (buyFeePct + slippagePct) / 100.0
        val sellNet = max(0.0, 1.0 - (sellFeePct + slippagePct) / 100.0)
        return ((price / entry) * sellNet / buyCost - 1.0) * 100.0
    }

    /** Harga impas net (profit net = 0) — kebalikan dari [netProfitPct]. */
    fun breakevenPrice(
        entry: Double,
        buyFeePct: Double,
        sellFeePct: Double,
        slippagePct: Double
    ): Double {
        val buyCost = 1.0 + (buyFeePct + slippagePct) / 100.0
        val sellNet = max(1e-9, 1.0 - (sellFeePct + slippagePct) / 100.0)
        return entry * buyCost / sellNet
    }

    /**
     * Momentum lemah: EMA9 < EMA21 × 0,998, atau RSI di luar 40–78 disertai candle merah,
     * atau candle merah dengan EMA9 < EMA21.
     */
    fun isMomentumWeak(window: List<CandleBar>): Boolean {
        if (window.size < 10) return true
        val closes = window.map { it.close }
        val e9 = emaLast(closes, 9)
        val e21 = emaLast(closes, 21)
        val r = rsiWilder(closes, minOf(14, closes.size - 1))
        val last = window.last()
        val bearCandle = last.close < last.open
        val emaWeak = e9 < e21 * 0.998
        val rsiWeak = r < 40.0 || r > 78.0
        return emaWeak || (rsiWeak && bearCandle) || (bearCandle && e9 < e21)
    }

    /** EMA rekursif dari nilai pertama (identik dengan `ema_last` di Python). */
    fun emaLast(values: List<Double>, period: Int): Double {
        if (period <= 0 || values.isEmpty()) return 0.0
        val k = 2.0 / (period + 1.0)
        var e = values[0]
        for (i in 1 until values.size) {
            e = (values[i] - e) * k + e
        }
        return e
    }

    /** Wilder RSI (identik dengan `rsi_wilder` di Python). */
    fun rsiWilder(closes: List<Double>, period: Int): Double {
        val n = closes.size
        if (period <= 0 || n <= period) return 50.0
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val ch = closes[i] - closes[i - 1]
            if (ch >= 0.0) gain += ch else loss -= ch
        }
        var ag = gain / period
        var al = loss / period
        for (i in period + 1 until n) {
            val ch = closes[i] - closes[i - 1]
            ag = (ag * (period - 1) + (if (ch > 0.0) ch else 0.0)) / period
            al = (al * (period - 1) + (if (ch < 0.0) -ch else 0.0)) / period
        }
        if (al == 0.0) return if (ag == 0.0) 50.0 else 100.0
        return 100.0 - (100.0 / (1.0 + ag / al))
    }

    /**
     * Resolve trade dari bar `entryIndex + 1` ke depan.
     *
     * @param bars deret M1 berurutan waktu.
     * @param entryIndex indeks bar tempat entry terjadi.
     * @param lookaheadBars jumlah bar maksimum yang diperiksa (default 120, selaras replay).
     */
    fun resolve(
        bars: List<CandleBar>,
        entryIndex: Int,
        entry: Double,
        stopLoss: Double,
        tp1: Double,
        tp2: Double,
        buyFeePct: Double,
        sellFeePct: Double,
        slippagePct: Double,
        params: ScalpExitParams,
        lookaheadBars: Int = DEFAULT_LOOKAHEAD_BARS,
        // S8 — ATR% bar M1 saat entry. `<= 0` = tidak diketahui → jatuh ke nilai persen tetap.
        atrPct: Double = 0.0
    ): PaperExitDecision {
        val risk = entry - stopLoss
        if (risk <= 0.0 || entry <= 0.0) {
            return PaperExitDecision(
                PaperExitResult.UNRESOLVED, 0.0, null, false, PaperExitReason.BAD_RISK
            )
        }
        val riskPct = risk / entry * 100.0
        fun rAt(price: Double): Double =
            netProfitPct(entry, price, buyFeePct, sellFeePct, slippagePct) / riskPct
        // S2/S8 — ambang efektif. Lihat [ScalpExitParams] untuk alasan tiap nilai.
        fun grossAt(price: Double): Double =
            if (entry > 0.0 && price > 0.0) (price / entry - 1.0) * 100.0 else 0.0
        val floorGrossPct = params.minProfitFloorGrossPct(buyFeePct, sellFeePct, slippagePct)
        val beTriggerNetPct = params.effectiveBreakevenTriggerNetPct(atrPct)
        val trailDistancePct = params.effectiveTrailDistancePct(atrPct)

        val bePx = breakevenPrice(entry, buyFeePct, sellFeePct, slippagePct)

        var end = minOf(bars.size, entryIndex + 1 + lookaheadBars)
        if (params.maxHoldBarsM1 > 0) {
            end = minOf(end, entryIndex + 1 + params.maxHoldBarsM1)
        }

        var activeSl = stopLoss
        var beArmed = false
        var trailArmed = false
        var peak = entry
        var partialR = 0.0
        var partialDone = false
        var ambiguous = false

        fun done(
            totalR: Double,
            idx: Int,
            reason: PaperExitReason,
            amb: Boolean = false,
            price: Double = 0.0
        ) =
            PaperExitDecision(
                result = if (totalR > 0.0) PaperExitResult.WIN else PaperExitResult.LOSS,
                r = round4(totalR),
                exitIndex = idx,
                ambiguous = amb,
                reason = reason,
                exitPrice = price
            )

        for (j in (entryIndex + 1) until end) {
            val bar = bars[j]
            val hi = bar.high
            val lo = bar.low
            val cl = bar.close
            peak = max(peak, hi)

            // --- Hard SL selalu dicek lebih dulu ---
            if (lo <= activeSl) {
                if (hi >= tp2 && activeSl <= stopLoss + EPS) ambiguous = true
                val rem = rAt(activeSl)
                val totalR = if (partialDone) partialR + 0.5 * rem else rem
                val reason = when {
                    activeSl <= stopLoss + EPS -> PaperExitReason.SL
                    beArmed && !trailArmed -> PaperExitReason.BE_SL
                    else -> PaperExitReason.TRAIL_SL
                }
                return done(totalR, j, reason, ambiguous, activeSl)
            }

            // --- TP2 full ---
            if (hi >= tp2) {
                val totalR = if (partialDone) partialR + 0.5 * rAt(tp2) else rAt(tp2)
                return done(totalR, j, PaperExitReason.TP2, price = tp2)
            }

            // --- Logika spesifik mode ---
            if (params.mode != ScalpExitMode.CLASSIC) {
                val netHi = netProfitPct(entry, hi, buyFeePct, sellFeePct, slippagePct)
                val netCl = netProfitPct(entry, cl, buyFeePct, sellFeePct, slippagePct)

                // TP1 partial 50% + geser SL ke BE
                if (params.mode.usesTp1Partial && !partialDone && hi >= tp1) {
                    partialDone = true
                    partialR = 0.5 * rAt(tp1)
                    activeSl = max(activeSl, bePx)
                    beArmed = true
                }

                // BE tanpa partial (mode trail / early / full)
                // S2/S8 — selain trigger net, wajib sudah melewati lantai profit kotor (S2);
                // trigger net itu sendiri sudah dinaikkan bila ATR tersedia (S8).
                if (params.mode.usesBreakeven && !beArmed && netHi >= beTriggerNetPct &&
                    grossAt(hi) >= floorGrossPct
                ) {
                    activeSl = max(activeSl, bePx)
                    beArmed = true
                }

                // Trailing dari peak
                if (params.mode.usesTrailing && netHi >= beTriggerNetPct &&
                    grossAt(hi) >= floorGrossPct
                ) {
                    trailArmed = true
                    val trailSl = peak * (1.0 - trailDistancePct / 100.0)
                    activeSl = max(activeSl, trailSl)
                }

                // Early profit: net di close >= min_early DAN momentum lemah DAN lantai profit (S2)
                if (params.mode.usesEarlyProfit && netCl >= params.minEarlyProfitNetPct &&
                    grossAt(cl) >= floorGrossPct
                ) {
                    val window = bars.subList(max(0, j - 14), j + 1)
                    if (isMomentumWeak(window)) {
                        val rem = rAt(cl)
                        val totalR = if (partialDone) partialR + 0.5 * rem else rem
                        return done(totalR, j, PaperExitReason.EARLY_PROFIT, price = cl)
                    }
                }
            }
        }

        return PaperExitDecision(
            PaperExitResult.UNRESOLVED, 0.0, null, false, PaperExitReason.TIMEOUT
        )
    }

    const val DEFAULT_LOOKAHEAD_BARS = 120
    private const val EPS = 1e-12

    private fun round4(v: Double): Double = Math.round(v * 10_000.0) / 10_000.0
}

/**
 * State machine exit paper trade untuk pemakaian **live** (tick/bar demi bar).
 *
 * Menyimpan state yang sama dengan loop di [PaperExitResolver.resolve] sehingga UI bisa
 * menampilkan fase FSM ([PaperExitPhase]) dan engine bisa memutuskan exit pada tick berjalan.
 */
class PaperExitStateMachine(
    val entry: Double,
    val stopLoss: Double,
    val tp1: Double,
    val tp2: Double,
    val buyFeePct: Double,
    val sellFeePct: Double,
    val slippagePct: Double,
    val params: ScalpExitParams,
    /**
     * S8 — ATR% bar M1 saat entry (mis. 3,31 untuk GNS). `<= 0` = tidak diketahui, dan
     * trigger BE / jarak trailing jatuh ke nilai persen tetap di [ScalpExitParams].
     */
    val atrPct: Double = 0.0
) {
    private val riskPct: Double = if (entry > 0.0) (entry - stopLoss) / entry * 100.0 else 0.0
    private val bePx: Double = PaperExitResolver.breakevenPrice(entry, buyFeePct, sellFeePct, slippagePct)

    /** S2 — lantai profit kotor (%). Exit profit sukarela tidak boleh di bawah ini. */
    private val floorGrossPct: Double =
        params.minProfitFloorGrossPct(buyFeePct, sellFeePct, slippagePct)

    /** S8 — trigger BE efektif (net %) setelah memperhitungkan ATR. */
    val effectiveBreakevenTriggerNetPct: Double = params.effectiveBreakevenTriggerNetPct(atrPct)

    /** S8 — jarak trailing efektif (% kotor dari peak) setelah memperhitungkan ATR. */
    val effectiveTrailDistancePct: Double = params.effectiveTrailDistancePct(atrPct)

    /** Profit kotor (%) pada harga tertentu — dasar perbandingan lantai profit (S2). */
    fun grossProfitPct(price: Double): Double =
        if (entry > 0.0 && price > 0.0) (price / entry - 1.0) * 100.0 else 0.0

    var activeStopLoss: Double = stopLoss
        private set
    var peak: Double = entry
        private set
    var isBeArmed: Boolean = false
        private set
    var isTrailArmed: Boolean = false
        private set
    var isPartialDone: Boolean = false
        private set
    var partialR: Double = 0.0
        private set
    var barsHeld: Int = 0
        private set

    val isRiskValid: Boolean get() = riskPct > 0.0 && entry > 0.0

    /** Harga impas net (profit net = 0) — dipakai UI untuk menggambar garis BE. */
    val netBreakevenPrice: Double get() = bePx

    /** Risiko awal (entry → SL) dalam persen. */
    val riskPctValue: Double get() = riskPct

    val phase: PaperExitPhase
        get() = when {
            isTrailArmed -> PaperExitPhase.TRAILING
            isPartialDone -> PaperExitPhase.TP1_PARTIAL
            isBeArmed -> PaperExitPhase.BREAKEVEN
            else -> PaperExitPhase.HOLDING
        }

    fun netProfitPct(price: Double): Double =
        PaperExitResolver.netProfitPct(entry, price, buyFeePct, sellFeePct, slippagePct)

    fun rAt(price: Double): Double =
        if (riskPct > 0.0) netProfitPct(price) / riskPct else 0.0

    /**
     * Proses satu bar tertutup. Kembalikan [PaperExitDecision] bila posisi harus ditutup,
     * atau `null` bila masih ditahan.
     *
     * @param window14 bar terakhir (maks 15) untuk uji momentum early profit.
     * @param isNewBar `false` saat evaluasi dilakukan di tengah bar berjalan (per-tick).
     *   Dalam kondisi ini pemeriksaan level (SL/TP/BE/trailing/early) tetap jalan — supaya
     *   proteksi tidak menunggu akhir bar — tetapi `barsHeld` tidak ditambah dan time-stop
     *   tidak dievaluasi, karena hitungan bar M1 hanya sah di batas bar.
     */
    fun onBar(bar: CandleBar, window14: List<CandleBar>, isNewBar: Boolean = true): PaperExitDecision? {
        if (!isRiskValid) {
            return PaperExitDecision(
                PaperExitResult.UNRESOLVED, 0.0, null, false, PaperExitReason.BAD_RISK
            )
        }
        if (isNewBar) barsHeld++
        peak = max(peak, bar.high)

        if (bar.low <= activeStopLoss) {
            val ambiguous = bar.high >= tp2 && activeStopLoss <= stopLoss + 1e-12
            val rem = rAt(activeStopLoss)
            val totalR = if (isPartialDone) partialR + 0.5 * rem else rem
            val reason = when {
                activeStopLoss <= stopLoss + 1e-12 -> PaperExitReason.SL
                isBeArmed && !isTrailArmed -> PaperExitReason.BE_SL
                else -> PaperExitReason.TRAIL_SL
            }
            return close(totalR, reason, ambiguous, activeStopLoss)
        }

        if (bar.high >= tp2) {
            val totalR = if (isPartialDone) partialR + 0.5 * rAt(tp2) else rAt(tp2)
            return close(totalR, PaperExitReason.TP2, price = tp2)
        }

        if (params.mode != ScalpExitMode.CLASSIC) {
            val netHi = netProfitPct(bar.high)
            val netCl = netProfitPct(bar.close)

            if (params.mode.usesTp1Partial && !isPartialDone && bar.high >= tp1) {
                isPartialDone = true
                partialR = 0.5 * rAt(tp1)
                activeStopLoss = max(activeStopLoss, bePx)
                isBeArmed = true
            }

            if (params.mode.usesBreakeven && !isBeArmed && netHi >= effectiveBreakevenTriggerNetPct &&
                grossProfitPct(bar.high) >= floorGrossPct
            ) {
                activeStopLoss = max(activeStopLoss, bePx)
                isBeArmed = true
            }

            if (params.mode.usesTrailing && netHi >= effectiveBreakevenTriggerNetPct &&
                grossProfitPct(bar.high) >= floorGrossPct
            ) {
                isTrailArmed = true
                val trailSl = peak * (1.0 - effectiveTrailDistancePct / 100.0)
                activeStopLoss = max(activeStopLoss, trailSl)
            }

            // Early profit hanya dievaluasi bila data momentum benar-benar tersedia. Pada
            // jalur live di mana jendela M1 belum terbentuk, lebih baik menahan posisi
            // daripada keluar berdasarkan momentum yang tidak terukur.
            if (params.mode.usesEarlyProfit && window14.isNotEmpty() &&
                netCl >= params.minEarlyProfitNetPct && grossProfitPct(bar.close) >= floorGrossPct
            ) {
                if (PaperExitResolver.isMomentumWeak(window14)) {
                    val rem = rAt(bar.close)
                    val totalR = if (isPartialDone) partialR + 0.5 * rem else rem
                    return close(totalR, PaperExitReason.EARLY_PROFIT, price = bar.close)
                }
            }
        }

        if (isNewBar && params.maxHoldBarsM1 > 0 && barsHeld >= params.maxHoldBarsM1) {
            val totalR = if (isPartialDone) partialR + 0.5 * rAt(bar.close) else rAt(bar.close)
            return close(totalR, PaperExitReason.TIMEOUT, price = bar.close)
        }

        return null
    }

    private fun close(
        totalR: Double,
        reason: PaperExitReason,
        ambiguous: Boolean = false,
        price: Double = 0.0
    ) =
        PaperExitDecision(
            result = if (totalR > 0.0) PaperExitResult.WIN else PaperExitResult.LOSS,
            r = Math.round(totalR * 10_000.0) / 10_000.0,
            exitIndex = null,
            ambiguous = ambiguous,
            reason = reason,
            exitPrice = price
        )
}
