package agu.analys.engine.readiness

import agu.analys.config.FeeCalculator
import agu.analys.config.StrategyMode
import agu.analys.config.TradingFeeConfig
import agu.analys.engine.scalping.ScalpExitParams
import agu.analys.engine.scalping.ScalpReadiness
import agu.analys.engine.scalping.ScalpReadinessSnapshot
import agu.analys.engine.scalping.ScalpReadinessState
import agu.analys.engine.scalping.ScalpingConfig
import agu.analys.engine.scalping.ScalpingRuntimeConfig
import agu.analys.model.AISignalState

/**
 * Jembatan **satu sumber kebenaran**: mengubah keputusan engine yang sedang aktif menjadi baris
 * papan kesiapan — tanpa menghitung ulang apa pun.
 *
 * ## Masalah yang diperbaiki
 *
 * Papan kesiapan ([HorizonReadinessEngine]) adalah **pra-saring**: ia membaca candle dari cache dan
 * menyusun daftar syaratnya sendiri. Itu berguna untuk menyaring ratusan koin. Yang keliru adalah
 * membiarkannya menjawab pertanyaan yang **sudah** dijawab engine untuk horizon yang engine-nya
 * sedang aktif.
 *
 * Akibat yang terlihat pengguna (laporan lapangan, 13 Okt):
 *
 * - Halaman detail koin: kartu `KESIAPAN HORIZON` menampilkan **3/5 syarat lolos** untuk SCALPING,
 *   sementara kartu `SCALP DESK` tepat di bawahnya menampilkan **1/5 lolos**. Keduanya "benar"
 *   menurut daftar syarat masing-masing — dan itu justru masalahnya.
 * - Dashboard: preferensi engine disimpan di Pengaturan (`StrategyMode`), tetapi papan tetap
 *   meringkas tiga horizon seolah ketiganya setara, sehingga mode SCALPING terlihat "memantau
 *   engine lain".
 *
 * ## Aturan sekarang
 *
 * ```
 * StrategyMode (pilihan pengguna, tombol simpan di Pengaturan)
 *     └── TradeHorizon.of(mode)  ← horizon aktif
 *             ├── baris horizon aktif  → EngineVerdict  → ReadinessSource.ENGINE
 *             └── baris horizon lain   → pra-saring     → ReadinessSource.PRESCREEN
 * ```
 *
 * Untuk horizon aktif, [of] memakai **objek yang sama** dengan yang dipakai kartu engine:
 *
 * - daftar syarat ← [ScalpReadinessSnapshot.checkpoints] (identik dengan kartu SCALP DESK / radar)
 * - status ← [ScalpReadinessState] hasil [ScalpReadiness.resolve]
 * - skor ← [HorizonReadinessEngine.weightedScore] (satu penghitung skor untuk seluruh aplikasi)
 * - entry / SL / TP ← angka milik sinyal engine (`signal.entryPrice`, `stopLoss`, `targetPrice1/2`)
 *
 * Karena tidak ada satu pun angka yang dihitung ulang di sini, dua permukaan **tidak bisa**
 * berbeda pendapat tentang koin yang sama.
 *
 * ## Kenapa `ScalpReadiness` juga dipakai untuk mode INTRADAY & SWING
 *
 * Ketiga engine (`ScalpingMtfEvaluator`, `IntradayEvaluator`, `SwingEvaluator`) mengisi
 * `ScalpingMtfSnapshot` yang sama — empat kaki: bias, setup, trigger, harga entry. Jadi
 * [ScalpReadiness.resolve] bukan logika khusus scalping; ia hanya membaca kaki-kaki bersama itu.
 * Memakai satu resolver untuk ketiga mode justru yang membuat kesimpulannya konsisten.
 */
object EngineVerdict {

    /**
     * Penunjuk rencana trade pada halaman detail.
     *
     * Dipakai ketika kartu engine (SCALP DESK / radar) sudah tampil di layar yang sama: menyalin
     * ulang entry/SL/TP di kartu kesiapan akan mengembalikan duplikasi yang sedang dihapus.
     */
    const val NOTE_PLAN_ELSEWHERE =
        "Rencana entry/SL/TP ditampilkan pada kartu engine di halaman ini — angka di sini sama."

    /**
     * Baris kesiapan untuk horizon yang engine-nya sedang aktif.
     *
     * @param mode preferensi engine pengguna (SSOT)
     * @param symbol simbol pair
     * @param signal sinyal engine hidup untuk simbol ini
     * @param readiness potret `ScalpReadiness.resolve` untuk sinyal yang sama; dihitung pemanggil
     *   supaya kartu engine dan baris ini benar-benar membaca satu objek
     * @param price harga acuan; `<= 0` memakai harga sinyal
     * @param fees tarif bursa aktif
     * @param exitRuleText aturan exit yang sedang dijalankan engine; `null` ⇒ disusun dari
     *   [ScalpingRuntimeConfig] aktif
     * @param planInCardElsewhere sembunyikan angka rencana bila kartu engine tampil di layar ini
     */
    fun of(
        mode: StrategyMode,
        symbol: String,
        signal: AISignalState,
        readiness: ScalpReadinessSnapshot,
        price: Double = 0.0,
        fees: TradingFeeConfig = TradingFeeConfig(),
        useMaker: Boolean = false,
        slippagePct: Double = ScalpingConfig.DEFAULT_SLIPPAGE_PCT,
        exitParams: ScalpExitParams = ScalpingRuntimeConfig.current.exit,
        planInCardElsewhere: Boolean = false
    ): HorizonReadiness {
        val horizon = TradeHorizon.of(mode)

        // Satu-satunya konversi di seluruh berkas: status engine → status papan.
        // HOLDING sengaja punya status sendiri supaya "posisi aktif" tidak pernah terbaca "siap".
        val state = when (readiness.state) {
            ScalpReadinessState.HOLDING -> ReadinessState.HOLDING
            ScalpReadinessState.READY -> ReadinessState.READY
            ScalpReadinessState.CONFIRMING -> ReadinessState.WARMING
            ScalpReadinessState.WATCHING -> ReadinessState.WATCH
            ScalpReadinessState.BLOCKED -> ReadinessState.BLOCKED
        }

        // Objek yang sama dengan kartu engine — bukan salinan bernilai sama, melainkan label,
        // urutan, dan detail yang dipakai bersama.
        val checks = readiness.checkpoints.map {
            ReadinessCheck(label = it.label, ok = it.ok, detail = it.detail)
        }

        val refPrice = when {
            price > 0.0 -> price
            signal.entryPrice > 0.0 -> signal.entryPrice
            else -> 0.0
        }

        return HorizonReadiness(
            symbol = symbol,
            horizon = horizon,
            state = state,
            score = HorizonReadinessEngine.weightedScore(checks),
            headline = readiness.headline,
            blocker = readiness.blocker,
            checks = checks,
            plan = if (planInCardElsewhere) null else plan(
                signal = signal,
                horizon = horizon,
                entry = refPrice,
                fees = fees,
                useMaker = useMaker,
                slippagePct = slippagePct,
                exitParams = exitParams
            ),
            price = refPrice,
            source = ReadinessSource.ENGINE,
            sourceNote = if (planInCardElsewhere) NOTE_PLAN_ELSEWHERE else ENGINE_NOTE
        )
    }

    private const val ENGINE_NOTE =
        "Dinilai engine yang sedang aktif — syarat, angka, dan rencana di bawah berasal langsung " +
            "dari sinyal engine, bukan dari pra-saring."

    /**
     * Rencana trade dari **angka milik sinyal engine**.
     *
     * `null` bila engine belum menetapkan entry/SL/TP yang masuk akal — lebih baik tidak
     * menampilkan rencana daripada menampilkan level karangan.
     */
    private fun plan(
        signal: AISignalState,
        horizon: TradeHorizon,
        entry: Double,
        fees: TradingFeeConfig,
        useMaker: Boolean,
        slippagePct: Double,
        exitParams: ScalpExitParams
    ): TradePlan? {
        val sl = signal.stopLoss
        val tp1 = signal.targetPrice1
        val tp2 = signal.targetPrice2

        if (entry <= 0.0 || sl <= 0.0 || sl >= entry || tp2 <= entry) return null

        val risk = entry - sl
        val riskPct = risk / entry * 100.0
        val costPct = HorizonReadinessEngine.roundTripCostPct(fees, useMaker, slippagePct)

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
            // TP1 diambil dari engine; hanya bila engine belum menetapkannya, bagi TP2
            // memakai pangsa TP1 yang sama dengan `ScalpingRiskEngine`.
            takeProfit1 = if (tp1 > entry) tp1 else entry + (tp2 - entry) * 0.55,
            takeProfit2 = tp2,
            riskPct = riskPct,
            rewardPct = (tp2 - entry) / entry * 100.0,
            netRr = fee.netRr,
            frictionFloorPct = horizon.minFrictionMultiple * costPct,
            tp2R = (tp2 - entry) / risk,
            exitRule = HorizonReadinessEngine.exitRule(horizon, exitParams)
        )
    }
}
