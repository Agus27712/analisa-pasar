package agu.analys.engine.intraday

import agu.analys.config.FeeCalculator
import agu.analys.config.FeeSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Penjaga **Paket C** mode INTRADAY — geometri SL/TP diturunkan dari SL+ATR+ambang R:R
 * ([IntradayGeometry]) dan histogram MACD yang dinormalisasi ke % harga.
 *
 * Konteks (28 Okt 2026, keputusan owner): band persen lama (SL −1,5%…−2,8%, TP1 +2,8%…+5,0%)
 * membuat net R:R TP1 ≈0,74–0,87 pada biaya all-in — tidak pernah memenuhi ambang 1,5, sehingga
 * sinyal BUY intraday nol di semua dataset uji. Perbaikannya BUKAN menurunkan ambang (itu
 * dilarang owner), melainkan geometri: TP1 diturunkan dari SL yang benar-benar dipakai, dengan
 * batas atas (resistance / `maxTp1Pct`) yang membuat gate tetap bisa gagal jujur.
 *
 * Uji yang tersisa berjalan di atas config + fixture `TestData` (tanpa dataset eksternal).
 *
 * **2026-11-12 — 6 uji yang butuh dataset `data/tokocrypto_v7` dihapus** atas instruksi owner.
 * Dataset itu sudah dihapus dari repo (commit `88103ec`), jadi ujinya **gagal** hanya karena
 * berkas data tidak ada, bukan karena geometri berubah. Konsekuensi yang harus diketahui:
 * penjaga "ambang macdHist ikut mengubah frekuensi", "batas atas 5% membuat gate gagal jujur",
 * dan "setiap trade replay memenuhi ambang R:R" **tidak lagi ada** di repo ini — kalau geometri
 * intraday diubah, ia perlu penjaga baru (idealnya dataset `data/tokocrypto_v8`).
 */
class IntradayGeometryAndMacdHistTest {

    private val feesIdr = FeeSchedule.configFor("INDODAX", "IDR")
    private val feesUsdt = FeeSchedule.configFor("TOKOCRYPTO", "USDT")
    private val feesTokoIdr = FeeSchedule.configFor("TOKOCRYPTO", "IDR")

    // ─────────────────────────────────────────────────────────────────────────
    // IntradayGeometry — fungsi murni
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * TP1-dari-R memenuhi ambang tepat: net R:R dari TP1 final ≈ `minNetRrTp1`, dan TP1 berubah
     * mengikuti ambang di config (bukti ambang dipakai, bukan literal).
     */
    @Test
    fun geometriDiturunkanDariRr_tp1MemenuhiAmbang() {
        val harga = 100.0
        listOf(feesIdr, feesUsdt, feesTokoIdr).forEach { fees ->
            // Batas atas dilonggarkan khusus uji ini supaya mekanisme "TP1 dari R" terlihat;
            // batas bawaan (5,0%) memang bisa mengikat — itu diuji terpisah di bawah.
            val longgar = IntradayGeometry.resolve(
                price = harga, supportLevel = null, resistanceLevel = null,
                atr = harga * 0.02, fees = fees,
                config = IntradayConfig(minNetRrTp1 = 1.5, maxTp1Pct = 20.0)
            )!!
            val ketat = IntradayGeometry.resolve(
                price = harga, supportLevel = null, resistanceLevel = null,
                atr = harga * 0.02, fees = fees,
                config = IntradayConfig(minNetRrTp1 = 0.5, maxTp1Pct = 20.0)
            )!!
            for (g in listOf(longgar, ketat)) {
                val rr = FeeCalculator.roundTrip(
                    entry = harga, stopLoss = g.stopLoss, takeProfit = g.takeProfit1,
                    fees = fees, useMaker = false, slippagePct = FeeSchedule.DEFAULT_SLIPPAGE_PCT
                ).netRr
                assertEquals(
                    "Net R:R dari TP1 final harus = ambang yang diminta config",
                    if (g === longgar) 1.5 else 0.5,
                    rr, 1e-6
                )
            }
            assertTrue(
                "Ambang 1,5 menuntut TP1 lebih jauh daripada ambang 0,5",
                longgar.takeProfit1 > ketat.takeProfit1
            )
            assertFalse("Tanpa resistance dan band lebar, batas atas tidak boleh mengikat", longgar.tp1TerikatBatas)
        }
    }

    /**
     * Batas atas memotong TP1-dari-R → gate GAGAL jujur. Inilah kontrol atas sifat tautologis
     * geometri R-derived: tanpa batas, gate selalu lolos.
     */
    @Test
    fun geometriTerikatBatas_gateGagalJujur() {
        val harga = 100.0
        val g = IntradayGeometry.resolve(
            price = harga, supportLevel = null, resistanceLevel = null,
            atr = harga * 0.02, fees = feesIdr, config = IntradayConfig(maxTp1Pct = 2.0)
        )!!
        assertTrue("Batas atas harus melaporkan dirinya mengikat", g.tp1TerikatBatas)
        assertEquals(
            "TP1 harus tepat di batas atas",
            harga * 1.02, g.takeProfit1, 1e-9
        )
        assertTrue(
            "Net R:R setelah terpotong harus di bawah ambang 1,5 (dapat ${g.netRrTp1})",
            g.netRrTp1 < 1.5
        )
        // Resistance yang lebih dekat daripada band juga memotong.
        val gRes = IntradayGeometry.resolve(
            price = harga, supportLevel = null, resistanceLevel = harga * 1.03,
            atr = harga * 0.02, fees = feesIdr, config = IntradayConfig()
        )!!
        assertEquals(harga * 1.03, gRes.takeProfit1, 1e-9)
        assertTrue("Net R:R harus di bawah ambang (dapat ${gRes.netRrTp1})", gRes.netRrTp1 < 1.5)
    }

    /**
     * SL tidak boleh lebih rapat dari `minSlFrictionMultiple × impas net` — dengan lantai 2,0×,
     * SL terapat di Indodax IDR ≈1,59% (2 × 0,794%), bukan 0,5%. Mematikan lantai (0,0) harus
     * membiarkan SL rapat sesuai `minSlPct` — bukti lantai dipakai dari config.
     */
    @Test
    fun slTidakLebihRapatDariLantaiFriksi() {
        val harga = 100.0
        val denganLantai = IntradayGeometry.resolve(
            price = harga, supportLevel = harga * 0.999, resistanceLevel = null,
            atr = harga * 0.02, fees = feesIdr,
            config = IntradayConfig(minSlPct = 0.5, minSlFrictionMultiple = 2.0)
        )!!
        val jarakDenganLantai = (harga - denganLantai.stopLoss) / harga * 100.0
        val impas = denganLantai.impasNetPct
        assertTrue(
            "Jarak SL ($jarakDenganLantai%) harus ≥ 2,0 × impas net ($impas%)",
            jarakDenganLantai >= 2.0 * impas - 1e-9
        )

        val tanpaLantai = IntradayGeometry.resolve(
            price = harga, supportLevel = harga * 0.999, resistanceLevel = null,
            atr = harga * 0.02, fees = feesIdr,
            config = IntradayConfig(minSlPct = 0.5, minSlFrictionMultiple = 0.0)
        )!!
        val jarakTanpaLantai = (harga - tanpaLantai.stopLoss) / harga * 100.0
        // Tanpa lantai, SL = support − 0,3 × ATR = 99,9 − 0,6 = 99,3 → jarak 0,7%
        // (band SL atas tidak ikut campur karena lebih lebar).
        assertEquals(0.7, jarakTanpaLantai, 1e-9)
        assertTrue("Lantai friksi harus melebarkan SL", denganLantai.stopLoss < tanpaLantai.stopLoss)
    }

    /** Invarian replay: SL < harga < TP1 < TP2 pada band config, di tiga preset tarif. */
    @Test
    fun invarianUrutanHarga_slTp1Tp2() {
        listOf(feesIdr, feesUsdt, feesTokoIdr).forEach { fees ->
            val g = IntradayGeometry.resolve(
                price = 100.0, supportLevel = 98.0, resistanceLevel = 110.0,
                atr = 2.0, fees = fees, config = IntradayConfig()
            )!!
            assertTrue("SL harus di bawah harga", g.stopLoss < 100.0)
            assertTrue("TP1 harus di atas harga", g.takeProfit1 > 100.0)
            assertTrue("TP2 harus di atas TP1 (replay memilih exit TP2 bila high ≥ TP2)", g.takeProfit2 > g.takeProfit1)
            assertTrue("TP2 tidak boleh melewati batas atas", g.takeProfit2 <= 100.0 * (1.0 + IntradayConfig.DEFAULT.maxTp2Pct / 100.0) + 1e-9)
        }
    }

    /**
     * Harga tidak valid tidak boleh membuat proses crash. `resolve` fail-closed (`null`) untuk
     * `0`, negatif, `NaN`, dan `∞` — jalur evaluasi berjalan per tick dari feed bursa, jadi
     * `require` di sana berarti satu harga rusak menjatuhkan aplikasi.
     */
    @Test
    fun hargaTidakValid_failClosedBukanCrash() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { harga ->
            val hasil = IntradayGeometry.resolve(
                price = harga, supportLevel = null, resistanceLevel = null,
                atr = 2.0, fees = feesUsdt, config = IntradayConfig()
            )
            assertTrue("Harga $harga harus ditolak fail-closed (null)", hasil == null)
        }
    }

    /**
     * Cabang tanpa support memakai `noSupportAtrMult` dari config dan ATR yang **sudah di-clamp**
     * (bukan literal 1,2 + ATR mentah). Mengubah knob harus mengubah SL; ATR di luar band harus
     * menghasilkan SL yang sama dengan ATR di tepi band.
     */
    @Test
    fun tanpaSupport_memakaiKnobDanAtrTerClamp() {
        val harga = 100.0
        // ATR 10% harga → di-clamp ke atrCapPct 4,0%, jadi SL = 100 − 4×(1,2+0,3) = 94.
        val atrBesar = IntradayGeometry.resolve(
            price = harga, supportLevel = null, resistanceLevel = null,
            atr = harga * 0.10, fees = feesUsdt,
            config = IntradayConfig(maxTp1Pct = 20.0)
        )!!
        // Band SL maksimum 2,8% lebih rapat daripada 6% → SL = 97,2.
        assertEquals(harga * (1.0 - IntradayConfig.DEFAULT.maxSlPct / 100.0), atrBesar.stopLoss, 1e-9)

        // Dengan maxSlPct dilonggarkan, SL mentah dari ATR ter-clamp terlihat: 100 − 4×1,5 = 94.
        val atrBesarLonggar = IntradayGeometry.resolve(
            price = harga, supportLevel = null, resistanceLevel = null,
            atr = harga * 0.10, fees = feesUsdt,
            config = IntradayConfig(maxSlPct = 10.0, maxTp1Pct = 20.0)
        )!!
        assertEquals(94.0, atrBesarLonggar.stopLoss, 1e-9)

        // Knob berbeda → SL berbeda (bukan literal yang tidak bisa diatur).
        val knobKecil = IntradayGeometry.resolve(
            price = harga, supportLevel = null, resistanceLevel = null,
            atr = harga * 0.10, fees = feesUsdt,
            config = IntradayConfig(noSupportAtrMult = 0.5, maxSlPct = 10.0, maxTp1Pct = 20.0)
        )!!
        assertEquals(100.0 - 4.0 * (0.5 + 0.3), knobKecil.stopLoss, 1e-9)
    }
}
