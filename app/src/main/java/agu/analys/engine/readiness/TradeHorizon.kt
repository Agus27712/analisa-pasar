package agu.analys.engine.readiness

import agu.analys.config.StrategyMode
import agu.analys.engine.scalping.ScalpingConfig
import agu.analys.model.Timeframe

/**
 * Tiga horizon trading yang dipantau aplikasi.
 *
 * ## Kenapa dipisah per horizon, bukan satu "skor koin"
 *
 * Pertanyaan "koin ini bagus?" tidak punya jawaban tunggal. Koin dengan ATR 0,4% **tidak bisa**
 * di-scalp di bursa berbiaya 0,97% round-trip (satu SL ≈ 8R), tetapi justru **layak** untuk swing
 * karena stop loss swing yang lebar membuat friksi jadi tidak berarti. Sebaliknya koin liar dengan
 * ATR 6% bagus untuk scalp cepat tetapi berbahaya untuk swing tanpa konfirmasi struktur.
 *
 * Karena itu kesiapan dinilai **terpisah** per horizon, masing-masing dengan timeframes,
 * ambang friksi, dan target net R:R sendiri.
 *
 * ## Kejujuran status (penting)
 *
 * [validatedOutOfSample] menandai apakah ambang horizon ini pernah diuji keluar-sampel dengan data
 * riil. **Hanya SCALP yang `true`** (uji 30 hari × 40 pair Tokocrypto, lihat `workplan2.md` §9).
 * INTRADAY dan SWING masih **heuristik** — angkanya masuk akal tetapi belum dibuktikan punya edge.
 * UI wajib menampilkan penanda ini; jangan sampai pengguna mengira ketiganya setara.
 *
 * @param label nama panjang untuk judul
 * @param shortLabel nama pendek untuk chip/tab
 * @param holdingWindow ekspektasi lama menahan posisi
 * @param timeframes semua timeframe yang dipakai, dari tertinggi ke terendah
 * @param biasTimeframe timeframe penentu arah utama
 * @param triggerTimeframe timeframe penentu timing masuk
 * @param targetNetRr target net R:R setelah fee & slippage
 * @param minFrictionMultiple kelipatan minimum jarak SL terhadap friksi round-trip
 * @param validatedOutOfSample sudah diuji keluar-sampel dengan data riil?
 * @param strategyMode mode engine yang memakai horizon ini — **penaut ke SSOT**
 */
enum class TradeHorizon(
    val label: String,
    val shortLabel: String,
    val holdingWindow: String,
    val timeframes: List<Timeframe>,
    val biasTimeframe: Timeframe,
    val triggerTimeframe: Timeframe,
    val targetNetRr: Double,
    val minFrictionMultiple: Double,
    val validatedOutOfSample: Boolean,
    val strategyMode: StrategyMode
) {
    /** Scalping: menit–jam. Ambang & lantai friksi **sama** dengan engine scalping (SSOT §9). */
    SCALP(
        label = "Scalping",
        shortLabel = "Scalp",
        holdingWindow = "menit – jam",
        timeframes = listOf(Timeframe.H1, Timeframe.M15, Timeframe.M1),
        biasTimeframe = Timeframe.H1,
        triggerTimeframe = Timeframe.M1,
        targetNetRr = ScalpingConfig.TARGET_NET_RR,
        minFrictionMultiple = ScalpingConfig.MIN_FRICTION_MULTIPLE,
        validatedOutOfSample = true,
        strategyMode = StrategyMode.SCALPING
    ),

    /**
     * Intraday: jam–1 hari, tunduk pada sesi WIB.
     *
     * Lantai friksi **4×** (bukan 6×) karena SL intraday berbasis H1/H4 yang secara alami lebih
     * lebar dari SL scalp M1/M15 — lantai 6× praktis tidak akan pernah mengikat di sini, jadi
     * menuliskannya akan menciptakan penjaga semu. Nilai ini **belum divalidasi**.
     */
    INTRADAY(
        label = "Intraday",
        shortLabel = "Intraday",
        holdingWindow = "jam – 1 hari",
        timeframes = listOf(Timeframe.H4, Timeframe.H1, Timeframe.M15),
        biasTimeframe = Timeframe.H4,
        triggerTimeframe = Timeframe.M15,
        targetNetRr = 1.5,
        minFrictionMultiple = 4.0,
        validatedOutOfSample = false,
        strategyMode = StrategyMode.OFFICE_DAILY
    ),

    /**
     * Swing: hari–minggu.
     *
     * Lantai friksi **3×** dengan alasan sama seperti INTRADAY. Nilai ini **belum divalidasi**.
     */
    SWING(
        label = "Swing",
        shortLabel = "Swing",
        holdingWindow = "hari – minggu",
        timeframes = listOf(Timeframe.D1, Timeframe.H4, Timeframe.H1),
        biasTimeframe = Timeframe.D1,
        triggerTimeframe = Timeframe.H4,
        targetNetRr = 2.0,
        minFrictionMultiple = 3.0,
        validatedOutOfSample = false,
        strategyMode = StrategyMode.SWING
    );

    /** Timeframe perantara (selain bias & trigger) — dipakai untuk konfirmasi momentum. */
    val confirmTimeframe: Timeframe
        get() = timeframes.first { it != biasTimeframe && it != triggerTimeframe }

    companion object {
        fun fromKey(key: String?): TradeHorizon? =
            entries.firstOrNull { it.name.equals(key?.trim(), ignoreCase = true) }

        /**
         * Horizon yang engine-nya sedang aktif.
         *
         * **Ini penaut SSOT.** Preferensi engine disimpan pengguna lewat tombol simpan di
         * Pengaturan ([StrategyMode]); fungsi ini memastikan papan kesiapan tidak pernah
         * menampilkan kesimpulan untuk horizon yang engine-nya tidak dipilih — kecuali
         * ditandai tegas sebagai pra-saring.
         */
        fun of(mode: StrategyMode): TradeHorizon =
            entries.first { it.strategyMode == mode }
    }
}
