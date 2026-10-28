package agu.analys.engine.intraday

import agu.analys.config.FeeSchedule

/**
 * Konfigurasi khusus mode **INTRADAY** (`StrategyMode.OFFICE_DAILY`).
 *
 * Kenapa terpisah dari [agu.analys.engine.scalping.ScalpingRuntimeConfig]:
 * - Config scalping diatur pengguna lewat **slider** dan JSON ekspor (`data/config/scalp_config.json`).
 *   Mode intraday belum punya slider, jadi tidak boleh ikut menumpang config scalping — kalau
 *   menumpang, geseran satu slider scalping akan diam-diam mengubah sinyal intraday.
 * - Berkas ini **tidak** punya state global yang bisa diubah saat runtime (tidak ada `current`
 *   yang mutable). Semua nilai dioper eksplisit oleh pemanggil, sehingga replay dan app memakai
 *   angka yang sama tanpa jalur tersembunyi.
 *
 * Yang **tidak** ada di sini dengan sengaja: jarak SL/TP. Geometri SL/TP masih memakai band persen
 * di `IntradayEvaluator` (poin C rencana perbaikan) dan belum diubah karena mengubah output sinyal.
 *
 * @property slippagePct asumsi slippage **per sisi** (%), default dari [FeeSchedule].
 * @property useMakerFee `false` = taker (bawaan, sesuai basis FSM: auto-buy/auto-exit adalah order
 *   market). `true` hanya untuk eksperimen eksplisit.
 * @property minNetRrTp1 ambang net R:R minimum yang **dihitung dari TP1** setelah fee + slippage.
 * @property flashDumpScanCandles jumlah candle terakhir yang dipindai untuk deteksi flash dump.
 * @property flashDumpTraumaCandles batas umur candle dump yang masih memblokir entry.
 */
data class IntradayConfig(
    val slippagePct: Double = FeeSchedule.DEFAULT_SLIPPAGE_PCT,
    val useMakerFee: Boolean = false,
    val minNetRrTp1: Double = 1.5,
    val flashDumpScanCandles: Int = 18,
    val flashDumpTraumaCandles: Int = 12
) {
    companion object {
        val DEFAULT = IntradayConfig()
    }
}
