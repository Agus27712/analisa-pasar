package agu.analys.engine.readiness

import agu.analys.config.FeeCalculator
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.indicators.IndicatorMath
import agu.analys.engine.intraday.IntradayEvaluator
import agu.analys.engine.scalping.PairEdgeGate
import agu.analys.engine.scalping.ScalpExitMode
import agu.analys.engine.scalping.ScalpExitParams
import agu.analys.engine.scalping.ScalpingConfig
import agu.analys.engine.scalping.ScalpingRuntimeConfig
import agu.analys.model.CandleBar
import agu.analys.model.Timeframe
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mesin kesiapan multi-horizon (TEAM 1).
 *
 * Menjawab satu pertanyaan untuk setiap pasangan (koin × horizon): **boleh masuk sekarang, atau
 * belum?** — dan bila boleh, dengan rencana apa.
 *
 * ## Prinsip
 *
 * 1. **Murni & tanpa Android.** Masukan hanya candle + tarif fee. Tidak ada I/O dan tidak ada state
 *    global, sehingga bisa diuji langsung dan dipanggil dari mana saja.
 * 2. **Angka tidak dihitung ulang.** Ambang diambil dari [ScalpingConfig] / [ScalpingRuntimeConfig],
 *    dan net R:R dari [FeeCalculator] — sumber kebenaran yang sama dengan engine scalping.
 * 3. **Tidak mengarang kesimpulan.** Candle kurang ⇒ [ReadinessState.NO_DATA].
 * 4. **Gate ekonomi berbasis bukti, bukan karangan.** Syarat terakhir memakai kriteria yang **sama
 *    dengan [PairEdgeGate] yang sudah diuji data**: harga harus mampu bergerak sekurang-kurangnya
 *    [MIN_EDGE_RATIO]× biaya round-trip. Estimasi pergerakan memakai ATR × √(jumlah bar) — lihat
 *    [expectedTravelPct] untuk alasan pemakaian akar.
 *
 * ## Kenapa BUKAN "ATR harus melebihi lantai friksi"
 *
 * Percobaan pertama membandingkan risiko alami (0,8×ATR) terhadap lantai friksi (6× biaya).
 * Hasilnya **mustahil dipenuhi**: untuk USDT Tokocrypto lantai = 5,81%, sehingga butuh ATR ≥ 7,26%
 * — di atas ambang volatilitas ekstrem (3,5%). Artinya papan akan **selalu kosong**, dan saringan
 * yang selalu menolak sama tidak bergunanya dengan saringan yang selalu menerima.
 *
 * Alasannya: lantai friksi adalah **cara beroperasi**, bukan syarat kelayakan. Riset §9 memang
 * menemukan bahwa dengan lantai 6× trade menjadi berdurasi 1–8 jam (bukan scalp cepat) dan
 * **28 dari 32 pair tetap positif**. Jadi yang perlu disaring bukan "apakah SL dipaksa lebar",
 * melainkan "apakah koin ini bergerak cukup jauh untuk menutup biaya" — persis kriteria
 * [PairEdgeGate].
 *
 * ## Yang TIDAK dilakukan engine ini
 *
 * - Tidak melihat orderbook. Keputusan akhir scalping tetap milik `ScalpingMtfEvaluator`.
 * - Tidak mengeksekusi apa pun. Ia menilai dan menyusun rencana.
 */
object HorizonReadinessEngine {

    /** Candle minimum pada timeframe bias agar EMA50 punya dasar. */
    const val MIN_CANDLES_BIAS = 60

    /** Candle minimum pada timeframe lain. */
    const val MIN_CANDLES_CONFIRM = 30

    /**
     * Rasio minimum antara estimasi pergerakan harga dan biaya round-trip.
     *
     * **Sengaja sama dengan [PairEdgeGate]**: gate itu diuji pada data riil 30 hari dan memakai
     * kriteria "MFE ≥ 2× friksi". Menyamakan angkanya membuat dua permukaan tidak bisa berbeda
     * pendapat tentang koin yang sama.
     */
    const val MIN_EDGE_RATIO = 2.0

    /** Jarak SL minimum dalam satuan ATR (selaras `ScalpingRiskEngine.MIN_RISK_ATR`). */
    private const val MIN_RISK_ATR = 0.8

    /** Batas maksimum jarak SL dalam satuan ATR (selaras `ScalpingRiskEngine.MAX_RISK_ATR`). */
    private const val MAX_RISK_ATR = 2.0

    /** Pangsa TP2 untuk TP1 (selaras `ScalpingRiskEngine.TP1_SHARE`). */
    private const val TP1_SHARE = 0.55

    /** TP2 minimum dalam satuan R (selaras `ScalpingRiskEngine.MIN_TP2_R`). */
    private const val MIN_TP2_R = 1.5

    private const val EMA_FAST = 20
    private const val EMA_SLOW = 50
    private const val RSI_PERIOD = 14
    private const val ATR_PERIOD = 14
    private const val RVOL_PERIOD = 20

    private const val SCALP_RSI_LOW = 50.0
    private const val SCALP_RSI_HIGH = 75.0
    private const val SWING_RSI_LOW = 45.0
    private const val SWING_RSI_HIGH = 70.0

    private const val SWING_MAX_EXTENSION_PCT = 8.0
    private const val SWING_BLOCK_EXTENSION_PCT = 15.0

    private const val FLASH_DUMP_PCT = -12.0
    private const val FLASH_DUMP_WINDOW_H4 = 18

    private const val SCALP_RVOL_MIN = 1.2

    /** Berat gate ekonomi pada skor (syarat lain berbobot 1). */
    private const val ECONOMY_WEIGHT = 2

    const val CHECK_ECONOMY = "Ekonomi trade (biaya vs pergerakan)"

    data class Input(
        val symbol: String,
        val horizon: TradeHorizon,
        val candles: Map<Timeframe, List<CandleBar>>,
        /** Harga acuan; bila <= 0 dipakai close candle bias. */
        val price: Double = 0.0,
        val fees: TradingFeeConfig = TradingFeeConfig(),
        val useMaker: Boolean = false,
        val slippagePct: Double = ScalpingConfig.DEFAULT_SLIPPAGE_PCT,
        /** Untuk uji deterministik sesi intraday. */
        val now: Long = System.currentTimeMillis()
    )

    fun evaluate(input: Input): HorizonReadiness {
        val horizon = input.horizon
        val symbol = input.symbol

        val bias = input.candles[horizon.biasTimeframe].orEmpty()
        val confirm = input.candles[horizon.confirmTimeframe].orEmpty()
        val trigger = input.candles[horizon.triggerTimeframe].orEmpty()

        val missing = buildList {
            if (bias.size < MIN_CANDLES_BIAS) {
                add("${horizon.biasTimeframe.label} ${bias.size}/$MIN_CANDLES_BIAS")
            }
            if (confirm.size < MIN_CANDLES_CONFIRM) {
                add("${horizon.confirmTimeframe.label} ${confirm.size}/$MIN_CANDLES_CONFIRM")
            }
            if (trigger.size < MIN_CANDLES_CONFIRM) {
                add("${horizon.triggerTimeframe.label} ${trigger.size}/$MIN_CANDLES_CONFIRM")
            }
        }

        val refPrice = when {
            input.price > 0.0 -> input.price
            bias.isNotEmpty() -> bias.last().close
            else -> 0.0
        }

        if (missing.isNotEmpty() || refPrice <= 0.0) {
            val detail = if (refPrice <= 0.0) "harga belum tersedia" else missing.joinToString(", ")
            return HorizonReadiness(
                symbol = symbol,
                horizon = horizon,
                state = ReadinessState.NO_DATA,
                score = 0,
                headline = "Data belum cukup untuk menilai $symbol pada horizon ${horizon.label}.",
                blocker = "Candle/harga belum siap: $detail",
                checks = emptyList(),
                plan = null,
                price = refPrice
            )
        }

        val atrTimeframe = horizon.confirmTimeframe
        val atrCandles = input.candles[atrTimeframe].orEmpty()
        val atr = IndicatorMath.atr(atrCandles, ATR_PERIOD)
        val atrPct = if (atr > 0.0) atr / refPrice * 100.0 else 0.0

        val costPct = roundTripCostPct(input.fees, input.useMaker, input.slippagePct)
        val frictionFloorPct = horizon.minFrictionMultiple * costPct

        // Risiko alami dari volatilitas koin (tanpa lantai), lalu lantai friksi dipaksakan
        // supaya SL tidak tenggelam di biaya — sama seperti `ScalpingRiskEngine`.
        val naturalRiskPct = max(MIN_RISK_ATR * atrPct, ScalpingConfig.MIN_RISK_PCT)
        val maxRiskPct = max(MAX_RISK_ATR * atrPct, frictionFloorPct)
        val riskPct = min(max(naturalRiskPct, frictionFloorPct), maxRiskPct)

        val plan = buildPlan(
            horizon = horizon,
            entry = refPrice,
            riskPct = riskPct,
            frictionFloorPct = frictionFloorPct,
            fees = input.fees,
            useMaker = input.useMaker,
            slippagePct = input.slippagePct,
            exitParams = ScalpingRuntimeConfig.current.exit
        )

        val travelPct = expectedTravelPct(atrPct, windowBars(horizon))

        val checks = when (horizon) {
            TradeHorizon.SCALP -> scalpChecks(
                bias = bias,
                confirm = confirm,
                trigger = trigger,
                atrPct = atrPct,
                travelPct = travelPct,
                costPct = costPct,
                frictionFloorPct = frictionFloorPct,
                plan = plan
            )

            TradeHorizon.INTRADAY -> intradayChecks(
                bias = bias,
                confirm = confirm,
                trigger = trigger,
                travelPct = travelPct,
                costPct = costPct,
                frictionFloorPct = frictionFloorPct,
                plan = plan,
                now = input.now
            )

            TradeHorizon.SWING -> swingChecks(
                bias = bias,
                confirm = confirm,
                travelPct = travelPct,
                costPct = costPct,
                frictionFloorPct = frictionFloorPct,
                plan = plan,
                price = refPrice
            )
        }

        val blocker = hardBlocker(horizon, atrPct, bias, refPrice)
        val passed = checks.count { it.ok }
        val total = checks.size
        val economyOk = checks.firstOrNull { it.label == CHECK_ECONOMY }?.ok == true

        val state = when {
            blocker != null -> ReadinessState.BLOCKED
            passed == total -> ReadinessState.READY
            passed == total - 1 -> ReadinessState.WARMING
            else -> ReadinessState.WATCH
        }

        return HorizonReadiness(
            symbol = symbol,
            horizon = horizon,
            state = state,
            score = weightedScore(checks),
            headline = headline(horizon, state, passed, total, economyOk, blocker),
            blocker = blocker ?: checks.firstOrNull { !it.ok }?.let { "${it.label}: ${it.detail}" },
            checks = checks,
            plan = plan,
            price = refPrice
        )
    }

    // ── Syarat per horizon ──────────────────────────────────────────────────

    private fun scalpChecks(
        bias: List<CandleBar>,
        confirm: List<CandleBar>,
        trigger: List<CandleBar>,
        atrPct: Double,
        travelPct: Double,
        costPct: Double,
        frictionFloorPct: Double,
        plan: TradePlan
    ): List<ReadinessCheck> {
        val biasOk = uptrend(bias)
        val confirmEma = IndicatorMath.ema(confirm, EMA_FAST)
        val confirmRsi = IndicatorMath.rsi(confirm, RSI_PERIOD)
        val momentumOk = confirm.last().close > confirmEma && confirmRsi in SCALP_RSI_LOW..SCALP_RSI_HIGH
        val rvol = IndicatorMath.relativeVolume(trigger, RVOL_PERIOD)
        val pushOk = rvol >= SCALP_RVOL_MIN && trigger.last().close > trigger.last().open
        val volOk = atrPct in ScalpingConfig.ATR_PCT_SCALP_SWEET_LOW..ScalpingConfig.ATR_PCT_SCALP_SWEET_HIGH

        return listOf(
            ReadinessCheck(
                label = "Tren ${TradeHorizon.SCALP.biasTimeframe.label} searah",
                ok = biasOk,
                detail = "Close ${fmt(bias.last().close)} vs EMA$EMA_FAST ${fmt(IndicatorMath.ema(bias, EMA_FAST))} / " +
                    "EMA$EMA_SLOW ${fmt(IndicatorMath.ema(bias, EMA_SLOW))}."
            ),
            ReadinessCheck(
                label = "Momentum ${TradeHorizon.SCALP.confirmTimeframe.label}",
                ok = momentumOk,
                detail = "RSI $RSI_PERIOD ${fmt(confirmRsi)} (butuh ${fmt(SCALP_RSI_LOW)}–${fmt(SCALP_RSI_HIGH)}), " +
                    "close ${if (confirm.last().close > confirmEma) "di atas" else "di bawah"} EMA$EMA_FAST."
            ),
            ReadinessCheck(
                label = "Dorongan ${TradeHorizon.SCALP.triggerTimeframe.label}",
                ok = pushOk,
                detail = "RVOL ${fmt(rvol)}× (min ${fmt(SCALP_RVOL_MIN)}×), candle terakhir " +
                    "${if (trigger.last().close > trigger.last().open) "naik" else "turun"}."
            ),
            ReadinessCheck(
                label = "Volatilitas sehat (ATR ${TradeHorizon.SCALP.confirmTimeframe.label})",
                ok = volOk,
                detail = "ATR ${fmt(atrPct)}% (pita ${fmt(ScalpingConfig.ATR_PCT_SCALP_SWEET_LOW)}–" +
                    "${fmt(ScalpingConfig.ATR_PCT_SCALP_SWEET_HIGH)}%)."
            ),
            economyCheck(travelPct, costPct, frictionFloorPct, plan)
        )
    }

    private fun intradayChecks(
        bias: List<CandleBar>,
        confirm: List<CandleBar>,
        trigger: List<CandleBar>,
        travelPct: Double,
        costPct: Double,
        frictionFloorPct: Double,
        plan: TradePlan,
        now: Long
    ): List<ReadinessCheck> {
        val phase = IntradayEvaluator.getCurrentIntradayPhase(now)
        val biasOk = uptrend(bias)
        val adx = IndicatorMath.adx(confirm, 14)
        val trendOk = adx >= ScalpingConfig.ADX_WEAK
        val dump = flashDumpPct(trigger)
        val dumpOk = dump == null

        return listOf(
            ReadinessCheck(
                label = "Sesi boleh buka (WIB)",
                ok = phase.isOpenWindow,
                detail = "${phase.label} — ${if (phase.isOpenWindow) "boleh buka posisi" else "dilarang buka posisi baru"}."
            ),
            ReadinessCheck(
                label = "Tren ${TradeHorizon.INTRADAY.biasTimeframe.label} searah",
                ok = biasOk,
                detail = "Close ${fmt(bias.last().close)} vs EMA$EMA_FAST ${fmt(IndicatorMath.ema(bias, EMA_FAST))} / " +
                    "EMA$EMA_SLOW ${fmt(IndicatorMath.ema(bias, EMA_SLOW))}."
            ),
            ReadinessCheck(
                label = "Kekuatan tren ${TradeHorizon.INTRADAY.confirmTimeframe.label} (ADX)",
                ok = trendOk,
                detail = "ADX ${fmt(adx)} (min ${fmt(ScalpingConfig.ADX_WEAK)})."
            ),
            ReadinessCheck(
                label = "Bukan trauma dump 72 jam",
                ok = dumpOk,
                detail = if (dumpOk) {
                    "Tidak ada candle ${TradeHorizon.INTRADAY.triggerTimeframe.label} turun ≤ " +
                        "${fmt(FLASH_DUMP_PCT)}% dalam $FLASH_DUMP_WINDOW_H4 candle terakhir."
                } else {
                    "Ada candle ${TradeHorizon.INTRADAY.triggerTimeframe.label} turun ${fmt(dump!!)}% — " +
                        "tunggu basis akumulasi terbentuk."
                }
            ),
            economyCheck(travelPct, costPct, frictionFloorPct, plan)
        )
    }

    private fun swingChecks(
        bias: List<CandleBar>,
        confirm: List<CandleBar>,
        travelPct: Double,
        costPct: Double,
        frictionFloorPct: Double,
        plan: TradePlan,
        price: Double
    ): List<ReadinessCheck> {
        val biasOk = uptrend(bias)
        val emaFast = IndicatorMath.ema(bias, EMA_FAST)
        val extensionPct = if (emaFast > 0.0) (price - emaFast) / emaFast * 100.0 else 0.0
        val notExtended = extensionPct <= SWING_MAX_EXTENSION_PCT
        val rsi = IndicatorMath.rsi(confirm, RSI_PERIOD)
        val momentumOk = rsi in SWING_RSI_LOW..SWING_RSI_HIGH
        val higherLowOk = higherLow(confirm)

        return listOf(
            ReadinessCheck(
                label = "Tren ${TradeHorizon.SWING.biasTimeframe.label} searah",
                ok = biasOk,
                detail = "Close ${fmt(bias.last().close)} vs EMA$EMA_FAST ${fmt(emaFast)} / " +
                    "EMA$EMA_SLOW ${fmt(IndicatorMath.ema(bias, EMA_SLOW))}."
            ),
            ReadinessCheck(
                label = "Harga tidak extended",
                ok = notExtended,
                detail = "Harga ${fmt(extensionPct)}% di atas EMA$EMA_FAST ${TradeHorizon.SWING.biasTimeframe.label} " +
                    "(maks ${fmt(SWING_MAX_EXTENSION_PCT)}%)."
            ),
            ReadinessCheck(
                label = "Momentum ${TradeHorizon.SWING.confirmTimeframe.label} sehat",
                ok = momentumOk,
                detail = "RSI $RSI_PERIOD ${fmt(rsi)} (butuh ${fmt(SWING_RSI_LOW)}–${fmt(SWING_RSI_HIGH)})."
            ),
            ReadinessCheck(
                label = "Struktur higher low (${TradeHorizon.SWING.confirmTimeframe.label})",
                ok = higherLowOk,
                detail = if (higherLowOk) {
                    "Low terbaru lebih tinggi dari low sebelumnya — struktur naik utuh."
                } else {
                    "Low terbaru tidak lebih tinggi — struktur naik belum terkonfirmasi."
                }
            ),
            economyCheck(travelPct, costPct, frictionFloorPct, plan)
        )
    }

    /**
     * Gate ekonomi — syarat terakhir setiap horizon.
     *
     * Memakai kriteria [PairEdgeGate]: koin harus mampu bergerak minimal [MIN_EDGE_RATIO]× biaya
     * round-trip dalam jendela horizon ini. Bila tidak, biaya sudah mengalahkan peluang sebelum
     * masuk — berapa pun bagusnya tren.
     */
    private fun economyCheck(
        travelPct: Double,
        costPct: Double,
        frictionFloorPct: Double,
        plan: TradePlan
    ): ReadinessCheck {
        val edgeRatio = if (costPct > 0.0) travelPct / costPct else 0.0
        val ok = edgeRatio >= MIN_EDGE_RATIO
        val detail = buildString {
            append("Perkiraan gerak ${fmt(travelPct)}% ÷ biaya ${fmt(costPct)}% = ${fmt(edgeRatio)}× ")
            append("(min ${fmt(MIN_EDGE_RATIO)}×) · net R:R ${fmt(plan.netRr)} · ")
            append("risiko ${fmt(plan.riskPct)}% · lantai friksi ${fmt(frictionFloorPct)}%")
            if (!ok) append(" — koin ini terlalu tenang untuk menutup biaya di horizon ini.")
        }
        return ReadinessCheck(label = CHECK_ECONOMY, ok = ok, detail = detail)
    }

    // ── Penghalang keras ────────────────────────────────────────────────────

    private fun hardBlocker(
        horizon: TradeHorizon,
        atrPct: Double,
        bias: List<CandleBar>,
        price: Double
    ): String? = when (horizon) {
        TradeHorizon.SCALP -> if (atrPct > ScalpingConfig.ATR_PCT_HIGH) {
            "Volatilitas ekstrem: ATR ${fmt(atrPct)}% di atas ${fmt(ScalpingConfig.ATR_PCT_HIGH)}% — noise mengalahkan sinyal."
        } else null

        // Trauma dump sudah menjadi salah satu syarat, bukan penghalang terpisah.
        TradeHorizon.INTRADAY -> null

        TradeHorizon.SWING -> {
            val emaFast = IndicatorMath.ema(bias, EMA_FAST)
            val extensionPct = if (emaFast > 0.0) (price - emaFast) / emaFast * 100.0 else 0.0
            if (extensionPct > SWING_BLOCK_EXTENSION_PCT) {
                "Harga ${fmt(extensionPct)}% di atas EMA$EMA_FAST D1 — risiko beli di puncak blow-off."
            } else null
        }
    }

    // ── Rencana trade (beli → tahan → keluar) ───────────────────────────────

    private fun buildPlan(
        horizon: TradeHorizon,
        entry: Double,
        riskPct: Double,
        frictionFloorPct: Double,
        fees: TradingFeeConfig,
        useMaker: Boolean,
        slippagePct: Double,
        exitParams: ScalpExitParams
    ): TradePlan {
        val costPct = roundTripCostPct(fees, useMaker, slippagePct)
        val risk = entry * riskPct / 100.0
        val sl = entry - risk

        // TP2 = titik yang membuat net R:R tepat menyentuh target horizon ini.
        val requiredGrossPct = horizon.targetNetRr * (riskPct + costPct) + costPct
        val tp2Pct = max(requiredGrossPct, MIN_TP2_R * riskPct)
        val tp1 = entry * (1.0 + tp2Pct * TP1_SHARE / 100.0)
        val tp2 = entry * (1.0 + tp2Pct / 100.0)

        val fee = FeeCalculator.roundTrip(
            entry = entry,
            stopLoss = sl,
            takeProfit = tp2,
            fees = fees,
            useMaker = useMaker,
            slippagePct = slippagePct
        )

        return TradePlan(
            entry = entry,
            stopLoss = sl,
            takeProfit1 = tp1,
            takeProfit2 = tp2,
            riskPct = riskPct,
            rewardPct = tp2Pct,
            netRr = fee.netRr,
            frictionFloorPct = frictionFloorPct,
            tp2R = if (risk > 0.0) (tp2 - entry) / risk else 0.0,
            exitRule = exitRule(horizon, exitParams)
        )
    }

    /**
     * Aturan exit long — "beli → tahan → keluar saat profit".
     *
     * Untuk SCALP teksnya dibangun dari [ScalpExitParams] yang **sedang aktif**, sehingga UI tidak
     * pernah menampilkan aturan exit yang berbeda dari yang benar-benar dijalankan engine paper.
     */
    fun exitRule(horizon: TradeHorizon, exitParams: ScalpExitParams): String =
        when (horizon) {
            TradeHorizon.SCALP -> buildString {
                append("Beli di area entry, tahan, keluar saat profit net mencapai target (TP2) atau kena SL.")
                when (exitParams.mode) {
                    ScalpExitMode.CLASSIC ->
                        append(" Mode Classic: TP2 penuh, tanpa TP1/trailing.")

                    ScalpExitMode.TP1_BE ->
                        append(" Mode TP1+BE: jual 50% di TP1, sisanya SL digeser ke breakeven net.")

                    ScalpExitMode.EARLY ->
                        append(" Mode Early: keluar lebih awal bila profit net ≥ ${fmt(exitParams.minEarlyProfitNetPct)}% dan momentum melemah.")

                    ScalpExitMode.TRAIL ->
                        append(" Mode Trailing: setelah profit net ${fmt(exitParams.breakevenTriggerNetPct)}%, SL ikut puncak dengan jarak ${fmt(exitParams.trailPct)}%.")

                    ScalpExitMode.FULL ->
                        append(
                            " Mode Full: TP1 50% lalu SL ke breakeven net (${fmt(exitParams.breakevenTriggerNetPct)}%), " +
                                "trailing ${fmt(exitParams.trailPct)}% dari puncak, early-profit ≥ ${fmt(exitParams.minEarlyProfitNetPct)}% net."
                        )
                }
                if (exitParams.maxHoldBarsM1 > 0) {
                    append(" Time-stop ${exitParams.maxHoldBarsM1} bar M1.")
                }
            }

            TradeHorizon.INTRADAY ->
                "Beli di sesi buka pagi, tahan dengan trailing di sesi sore, keluar sebelum sesi close malam (19:30 WIB) " +
                    "atau saat SL tersentuh. Tidak menahan posisi melewati tengah malam."

            TradeHorizon.SWING ->
                "Beli saat pullback tertahan di area nilai, tahan beberapa hari–minggu, keluar di TP2/resistance " +
                    "atau ketika tren D1 rusak (close di bawah EMA50 D1)."
        }

    // ── Utilitas ────────────────────────────────────────────────────────────

    /**
     * Jumlah bar dalam jendela pengamatan tiap horizon.
     *
     * SCALP memakai 28 bar M15 (±7 jam) — sama dengan jendela MFE 7 jam di `tools/pair_gate.py`.
     * INTRADAY 12 bar H1 (±12 jam kerja), SWING 14 bar H4 (±2,3 hari).
     */
    fun windowBars(horizon: TradeHorizon): Int = when (horizon) {
        TradeHorizon.SCALP -> 28
        TradeHorizon.INTRADAY -> 12
        TradeHorizon.SWING -> 14
    }

    /**
     * Estimasi jarak yang **wajar** ditempuh harga dalam jendela.
     *
     * Memakai `ATR × √n`, bukan `ATR × n`. Alasannya: `ATR × n` mengasumsikan harga bergerak
     * searah setiap bar — itu skenario tren sempurna, bukan perkiraan. Untuk lintasan acak,
     * simpangan baku perpindahan tumbuh sebanding akar jumlah langkah. Memakai `ATR × n` akan
     * melebih-lebihkan kemampuan koin dan membuat gate lolos terlalu mudah — persis jenis
     * optimisme yang membuat saringan tidak berguna.
     */
    fun expectedTravelPct(atrPct: Double, bars: Int): Double {
        if (atrPct <= 0.0 || bars <= 0) return 0.0
        return atrPct * sqrt(bars.toDouble())
    }

    /** Tren naik sederhana: harga di atas EMA50 **dan** EMA20 di atas EMA50. */
    private fun uptrend(candles: List<CandleBar>): Boolean {
        if (candles.size < EMA_SLOW + 1) return false
        val close = candles.last().close
        val fast = IndicatorMath.ema(candles, EMA_FAST)
        val slow = IndicatorMath.ema(candles, EMA_SLOW)
        return close > slow && fast > slow
    }

    /** Higher low: low terendah 5 candle terakhir di atas low terendah 5 candle sebelumnya. */
    private fun higherLow(candles: List<CandleBar>): Boolean {
        if (candles.size < 11) return false
        val recent = candles.takeLast(5).minOf { it.low }
        val previous = candles.dropLast(5).takeLast(5).minOf { it.low }
        return recent > previous
    }

    /** Penurunan terburuk (persen) pada jendela terakhir; `null` bila tidak ada yang ekstrem. */
    private fun flashDumpPct(candles: List<CandleBar>): Double? {
        if (candles.size < 2) return null
        val window = candles.takeLast(FLASH_DUMP_WINDOW_H4)
        val worst = window.minOfOrNull { c ->
            if (c.open <= 0.0) 0.0 else (c.close - c.open) / c.open * 100.0
        } ?: return null
        return if (worst <= FLASH_DUMP_PCT) worst else null
    }

    /** Biaya round-trip all-in (fee beli + fee jual + 2× slippage), persen harga. */
    fun roundTripCostPct(
        fees: TradingFeeConfig,
        useMaker: Boolean,
        slippagePct: Double
    ): Double {
        val buyFee = if (useMaker) fees.buyMakerPct else fees.buyTakerPct
        val sellFee = if (useMaker) fees.sellMakerPct else fees.sellTakerPct
        return buyFee + sellFee + 2.0 * slippagePct
    }

    /**
     * Skor berbobot: gate ekonomi dihitung [ECONOMY_WEIGHT] kali.
     *
     * Publik supaya [EngineVerdict] memakai **penghitung skor yang sama** saat mengubah keputusan
     * engine menjadi baris papan. Dua penghitung skor yang berbeda akan membuat angka papan dan
     * angka kartu engine berbeda meski syaratnya identik.
     */
    fun weightedScore(checks: List<ReadinessCheck>): Int {
        if (checks.isEmpty()) return 0
        var earned = 0
        var total = 0
        checks.forEach { c ->
            val w = if (c.label == CHECK_ECONOMY) ECONOMY_WEIGHT else 1
            total += w
            if (c.ok) earned += w
        }
        return if (total == 0) 0 else (earned * 100.0 / total).toInt()
    }

    private fun headline(
        horizon: TradeHorizon,
        state: ReadinessState,
        passed: Int,
        total: Int,
        economyOk: Boolean,
        blocker: String?
    ): String = when (state) {
        // Tidak pernah dihasilkan pra-saring (lihat `EngineVerdict`); dicantumkan supaya
        // penambahan status baru tidak bisa diam-diam menghasilkan teks kosong.
        ReadinessState.HOLDING -> "Posisi simulasi terbuka — exit dipegang engine paper."
        ReadinessState.NO_DATA -> "Menunggu data candle ${horizon.label.lowercase()}."
        ReadinessState.BLOCKED -> blocker ?: "Tertahan kondisi pasar."
        ReadinessState.READY -> "Siap ${horizon.label.lowercase()} — $passed/$total syarat lolos."
        ReadinessState.WARMING -> if (!economyOk) {
            "Hampir siap — biaya round-trip belum tertutup pergerakan koin."
        } else {
            "Hampir siap — $passed/$total syarat lolos."
        }

        ReadinessState.WATCH -> "$passed/$total syarat lolos — belum layak masuk."
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
}
