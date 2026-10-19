package agu.analys.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **Fee Truth v1.1 (2026-10-19)** — konstanta biaya all-in yang dipakai seluruh aplikasi harus
 * persis sama dengan dokumen. Ini penjaga **drift angka**, bukan uji perilaku.
 *
 * Kalau tarif berubah di bursa, ubah `FEE_TRUTH.md` **dan** `FeeSchedule` **dan** `scalp_config.json`
 * bersama-sama dalam satu commit — uji ini akan menunjukkan konstanta yang tertinggal.
 *
 * Angka round-trip (*F* = fee dua sisi + slippage dua sisi) memakai rumus aditif yang sama dengan
 * `FEE_TRUTH` §8, sama dengan `ScalpingRiskEngine.costPct` dan `PaperStopResolver`.
 *
 * Kontrol negatif: ubah satu konstanta (mis. `TOKOCRYPTO_IDR_SELL_TAKER` → 0.004222) → uji
 * [konstanta fee sama dengan FEE_TRUTH] GAGAL.
 */
class FeeTruthConstantsTest {

    private fun pct(pecahan: Double) = pecahan * 100.0

    @Test
    fun `konstanta Tokocrypto sama dengan FEE_TRUTH`() {
        assertEquals(0.4044, pct(FeeSchedule.TOKOCRYPTO_USDT_BUY_TAKER), 1e-9)
        assertEquals(0.4044, pct(FeeSchedule.TOKOCRYPTO_USDT_SELL_TAKER), 1e-9)
        assertEquals(0.2222, pct(FeeSchedule.TOKOCRYPTO_IDR_BUY_TAKER), 1e-9)
        assertEquals(0.1222, pct(FeeSchedule.TOKOCRYPTO_IDR_BUY_MAKER), 1e-9)
        assertEquals(0.4322, pct(FeeSchedule.TOKOCRYPTO_IDR_SELL_TAKER), 1e-9)
        assertEquals(0.3322, pct(FeeSchedule.TOKOCRYPTO_IDR_SELL_MAKER), 1e-9)
    }

    @Test
    fun `konstanta Indodax sama dengan FEE_TRUTH`() {
        assertEquals(0.21, pct(FeeSchedule.INDODAX_IDR_BUY_TAKER), 1e-9)
        assertEquals(0.11, pct(FeeSchedule.INDODAX_IDR_BUY_MAKER), 1e-9)
        assertEquals(0.42, pct(FeeSchedule.INDODAX_IDR_SELL_TAKER), 1e-9)
        assertEquals(0.32, pct(FeeSchedule.INDODAX_IDR_SELL_MAKER), 1e-9)
    }

    @Test
    fun `slippage base case 0_08 persen per sisi`() {
        assertEquals(0.08, FeeSchedule.DEFAULT_SLIPPAGE_PCT, 1e-9)
    }

    @Test
    fun `total friksi sama dengan FEE_TRUTH tabel 8`() {
        // Bentuk ADITIF = F yang dipakai lantai friksi (ScalpingRiskEngine.costPct dan
        // PaperStopResolver): fee dua sisi + slippage dua sisi.
        val aditifUsdt = 2 * 0.4044 + 2 * 0.08
        assertEquals(0.9688, aditifUsdt, 1e-9)

        val aditifIdrTaker = 0.2222 + 0.4322 + 2 * 0.08
        assertEquals(0.8144, aditifIdrTaker, 1e-9)

        val aditifIdrMaker = 0.1222 + 0.3322 + 2 * 0.08
        assertEquals(0.6144, aditifIdrMaker, 1e-9)

        val aditifIndodax = 0.21 + 0.42 + 2 * 0.08
        assertEquals(0.79, aditifIndodax, 1e-9)
    }

    @Test
    fun `impas net multiplikatif bukan sama dengan F aditif`() {
        // Dua angka ini berbeda fungsi dan KEDUANYA benar — jangan saling menggantikan:
        //   - 0,9688% = F aditif → dipakai menghitung lantai SL (6 x F = 5,81% untuk USDT)
        //   - 0,9735% = kenaikan harga yang benar-benar menutup semua biaya (multiplikatif),
        //     dipakai kartu "impas" dan ambang P&L net
        // Selisihnya kecil tapi bukan nol; dokumen yang menyebut salah satu tanpa
        // menjelaskan mana akan menggeser ambang P&L.
        val impasUsdt = FeeSchedule.roundTripPctMultiplicative("TOKOCRYPTO", "USDT")
        assertEquals(0.9735, impasUsdt, 5e-4)

        val impasIdrTaker = FeeSchedule.roundTripPctMultiplicative("TOKOCRYPTO", "IDR")
        assertEquals(0.8186, impasIdrTaker, 5e-4)
    }

    @Test
    fun `configFor memilih tarif sesuai kuotasi pair`() {
        val idr = FeeSchedule.configFor("TOKOCRYPTO", "IDR")
        assertEquals(0.2222, idr.buyTakerPct, 1e-9)
        assertEquals(0.4322, idr.sellTakerPct, 1e-9)

        val usdt = FeeSchedule.configFor("TOKOCRYPTO", "USDT")
        assertEquals(0.4044, usdt.buyTakerPct, 1e-9)
        assertEquals(0.4044, usdt.sellTakerPct, 1e-9)
    }

    @Test
    fun `mirror replay sama dengan FeeSchedule`() {
        // `data/config/scalp_config.json` adalah SATU-SATUNYA mirror offline yang dibaca
        // `tools/scalp_replay.py` (bawaan `--config`). Kalau fee di JSON menyimpang dari
        // `FeeSchedule`, hasil replay tidak lagi merepresentasikan aplikasi.
        val json = bacaMirror()
        val feeBlok = json.substringAfter("\"fee\"").substringBefore("\"thresholds\"")
        val buyTaker = feeBlok.substringAfter("\"buy_taker_pct\"").substringBefore(",").trim().toDouble()
        val sellTaker = feeBlok.substringAfter("\"sell_taker_pct\"").substringBefore(",").trim().toDouble()
        val buyMaker = feeBlok.substringAfter("\"buy_maker_pct\"").substringBefore(",").trim().toDouble()
        val sellMaker = feeBlok.substringAfter("\"sell_maker_pct\"").substringBefore(",").trim().toDouble()

        assertEquals(pct(FeeSchedule.TOKOCRYPTO_USDT_BUY_TAKER), buyTaker, 1e-9)
        assertEquals(pct(FeeSchedule.TOKOCRYPTO_USDT_SELL_TAKER), sellTaker, 1e-9)
        assertEquals(buyTaker, buyMaker, 1e-9)
        assertEquals(sellTaker, sellMaker, 1e-9)

        // Parameter lantai friksi juga harus sama dengan ScalpingConfig.
        val minFriction = json.substringAfter("\"min_friction_multiple\"").substringBefore(",").trim().toDouble()
        val targetRr = json.substringAfter("\"target_net_rr\"").substringBefore(",").trim().toDouble()
        val slippage = json.substringAfter("\"slippage_pct\"").substringBefore(",").trim().toDouble()
        assertEquals(
            agu.analys.engine.scalping.ScalpingConfig.MIN_FRICTION_MULTIPLE, minFriction, 1e-9
        )
        assertEquals(agu.analys.engine.scalping.ScalpingConfig.TARGET_NET_RR, targetRr, 1e-9)
        assertEquals(FeeSchedule.DEFAULT_SLIPPAGE_PCT, slippage, 1e-9)
    }

    private fun bacaMirror(): String {
        val base = java.io.File(System.getProperty("user.dir"))
        val kandidat = listOf(
            java.io.File(base, "data/config/scalp_config.json"),
            java.io.File(base, "../data/config/scalp_config.json")
        )
        val file = kandidat.firstOrNull { it.exists() }
            ?: error("data/config/scalp_config.json tidak ditemukan dari ${base.absolutePath}")
        return file.readText()
    }
}