package agu.analys.engine.intraday

import agu.analys.config.FeeCalculator
import agu.analys.config.FeeSchedule
import agu.analys.engine.TestData
import agu.analys.engine.intraday.replay.IntradayReplayEngine
import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

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
 * Seluruh uji di sini berjalan di atas config + dataset riset ter-commit
 * (`data/tokocrypto_v7/DOGEUSDT_1h.csv`) — uji perilaku, bukan pindai sumber.
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
            val longgar = IntradayGeometry.resolve(
                price = harga, supportLevel = null, resistanceLevel = null,
                atr = harga * 0.02, fees = fees, config = IntradayConfig(minNetRrTp1 = 1.5)
            )
            val ketat = IntradayGeometry.resolve(
                price = harga, supportLevel = null, resistanceLevel = null,
                atr = harga * 0.02, fees = fees, config = IntradayConfig(minNetRrTp1 = 0.5)
            )
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
            assertFalse("Tanpa resistance dan band 8%, batas atas tidak boleh mengikat", longgar.tp1TerikatBatas)
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
        )
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
        )
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
        )
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
        )
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
            )
            assertTrue("SL harus di bawah harga", g.stopLoss < 100.0)
            assertTrue("TP1 harus di atas harga", g.takeProfit1 > 100.0)
            assertTrue("TP2 harus di atas TP1 (replay memilih exit TP2 bila high ≥ TP2)", g.takeProfit2 > g.takeProfit1)
            assertTrue("TP2 tidak boleh melewati batas atas", g.takeProfit2 <= 100.0 * (1.0 + IntradayConfig.DEFAULT.maxTp2Pct / 100.0) + 1e-9)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Perilaku lewat evaluator & replay (dataset riset ter-commit)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Setiap trade yang terekam replay (sinyal BUY dengan geometri baru) wajib memenuhi ambang
     * net R:R dari config — diukur ulang dari level yang terekam, bukan dipercaya dari gate.
     *
     * Pair pengukuran: SUIUSDT — terukur (28 Okt 2026) satu-satunya dari 7 pair dataset
     * `tokocrypto_v7` yang menghasilkan sinyal BUY pada ambang bawaan 1,5 (3 trade); pair lain
     * 0 BUY karena resistance memotong TP1 dan gate gagal jujur.
     */
    @Test
    fun setiapTradeReplayMemenuhiAmbangRrDariConfig() {
        val config = IntradayConfig(minNetRrTp1 = 1.5)
        val report = IntradayReplayEngine.replay(
            symbol = "SUIUSDT",
            candles = muatCandleH1Riset("SUIUSDT"),
            feeConfig = feesUsdt,
            config = config
        )
        assertTrue(
            "SUIUSDT harus menghasilkan trade dengan geometri baru (terukur 3 trade)",
            report.trades.isNotEmpty()
        )
        report.trades.forEach { trade ->
            val rr = FeeCalculator.roundTrip(
                entry = trade.entryPrice,
                stopLoss = trade.stopLoss,
                takeProfit = trade.targetPrice1,
                fees = feesUsdt,
                useMaker = config.useMakerFee,
                slippagePct = config.slippagePct
            ).netRr
            assertTrue(
                "Trade ${trade.entryTime} RR $rr di bawah ambang ${config.minNetRrTp1}",
                rr >= config.minNetRrTp1 - 1e-9
            )
        }
    }

    /**
     * Checkpoint 6 konfluensi wajib memakai **geometri dan ambang intraday yang sama** dengan
     * sinyal (paket C). Sebelumnya checkpoint ini menghitung SL/TP sendiri dengan gate
     * `netRr >= 2.0` hardcoded, sehingga satu sinyal punya dua definisi R:R.
     *
     * Uji perilaku: pada bar dengan geometri bebas, checkpoint `RR` harus lolos dan angkanya
     * persis ambang config (1:1.50), bukan angka geometri lain. Bila override dimatikan,
     * checkpoint kembali memakai SL/TP hasil hitungannya sendiri dan angka/gagalnya berbeda.
     */
    @Test
    fun checkpointKonfluensiMemakaiGeometriDanAmbangIntraday() {
        val candles = muatCandleH1Riset("DOGEUSDT")
        val durasiBarH1 = 3_600_000L
        var ditemukan = false
        for (i in 20 until candles.size) {
            val window = candles.subList(maxOf(0, i - 300), i + 1)
            val r = IntradayEvaluator.evaluate(
                price = candles[i].close,
                history = window,
                fees = feesUsdt,
                evaluationTimestamp = candles[i].timestamp + durasiBarH1,
                config = IntradayConfig()
            )
            if (!r.signal.mtf.triggerOk) continue
            val rrSignal = r.signal.riskRewardRatio.removePrefix("1:").toDoubleOrNull() ?: 0.0
            if (rrSignal < 1.5 - 1e-9) continue
            val rrCheckpoint = r.signal.mtf.checkpoints.first { it.code == "RR" }
            assertTrue(
                "Checkpoint 6 harus lolos karena geometri sinyal memenuhi ambang 1,5 " +
                    "(bar $i, isOk=${rrCheckpoint.isOk}, metric=${rrCheckpoint.metricValue})",
                rrCheckpoint.isOk
            )
            // Format ConfluenceEvaluator: "Net 1:%.2f" — harus ambang config, bukan 2,00.
            assertEquals(
                "Angka R:R checkpoint harus sama dengan ambang intraday dari config (bar $i)",
                "Net 1:1.50",
                rrCheckpoint.metricValue
            )
            assertTrue(
                "Detail checkpoint harus menyebut ambang 1,50, bukan 2,00 (bar $i): " +
                    rrCheckpoint.detail,
                rrCheckpoint.detail.contains("1:1.5") && !rrCheckpoint.detail.contains("1:2.0")
            )
            ditemukan = true
            break
        }
        assertTrue("Dataset harus punya bar geometri bebas untuk menguji checkpoint 6", ditemukan)
    }

    /**
     * Histogram MACD dinormalisasi ke % harga → keputusan Step3 **konsisten antar skala harga**.
     * Riwayat yang sama diskalakan ×1000 harus memberi keputusan sama pada setiap prefix.
     */
    @Test
    fun macdHistDinormalisasi_konsistenAntarSkalaHarga() {
        val dasar = muatCandleH1Riset("DOGEUSDT")
        val besar = dasar.map { c ->
            c.copy(
                open = c.open * 1000.0,
                high = c.high * 1000.0,
                low = c.low * 1000.0,
                close = c.close * 1000.0
            )
        }
        // Dua ambang pada dataset riil DOGE (harga ~0,08 → histogram absolut < 0,001; ×1000 →
        // sampai ~0,4): ambang 0,1% membedakan unit dengan pasti (unit harga: koin kecil selalu
        // gagal, koin besar campuran), sementara −0,05% longgar. Dengan normalisasi, keduanya
        // wajib memberi keputusan identik antar skala — dan tidak trivial (ada yang lolos).
        val durasiBarH1 = 3_600_000L
        for (ambang in listOf(0.1, -0.05)) {
            val config = IntradayConfig(macdHistMinPct = ambang)
            var prefixDiperiksa = 0
            for (i in 20 until dasar.size) {
                val windowKecil = dasar.subList(maxOf(0, i - 300), i + 1)
                val windowBesar = besar.subList(maxOf(0, i - 300), i + 1)
                val kecil = IntradayEvaluator.evaluate(
                    price = windowKecil.last().close,
                    history = windowKecil,
                    fees = feesUsdt,
                    evaluationTimestamp = dasar[i].timestamp + durasiBarH1,
                    config = config
                )
                val gede = IntradayEvaluator.evaluate(
                    price = windowBesar.last().close,
                    history = windowBesar,
                    fees = feesUsdt,
                    evaluationTimestamp = dasar[i].timestamp + durasiBarH1,
                    config = config
                )
                assertEquals(
                    "Keputusan Step3 (triggerOk) harus sama untuk skala harga asli dan ×1000 " +
                        "(ambang $ambang, bar $i)",
                    kecil.signal.mtf.triggerOk,
                    gede.signal.mtf.triggerOk
                )
                prefixDiperiksa++
            }
            assertTrue("Uji harus benar-benar memeriksa beberapa bar", prefixDiperiksa >= 900)
        }
    }

    /**
     * Ambang histogram dari config benar-benar dipakai: ambang sangat ketat (+99,0% harga —
     * histogram MACD dataset ini maksimal < 1%, terukur) memangkas frekuensi bar Step3-lolos
     * di dataset yang sama menjadi nol, dibanding bawaan 0,0.
     *
     * Dampak frekuensi pada sinyal BUY lengkapnya diukur terpisah (dokumen paket C), karena
     * sinyal BUY memakai seluruh gate + fase sesi, bukan Step3 saja.
     */
    @Test
    fun ambangMacdHistDariConfig_ikutMengubahFrekuensi() {
        val candles = muatCandleH1Riset("DOGEUSDT")
        val durasiBarH1 = 3_600_000L

        fun hitungStep3Lolos(ambang: Double): Int {
            var lolos = 0
            for (i in 20 until candles.size) {
                val window = candles.subList(maxOf(0, i - 300), i + 1)
                val r = IntradayEvaluator.evaluate(
                    price = candles[i].close,
                    history = window,
                    fees = feesUsdt,
                    evaluationTimestamp = candles[i].timestamp + durasiBarH1,
                    config = IntradayConfig(macdHistMinPct = ambang)
                )
                if (r.signal.mtf.triggerOk) lolos++
            }
            return lolos
        }

        val longgar = hitungStep3Lolos(0.0)
        val ketat = hitungStep3Lolos(99.0)
        assertTrue(
            "Ambang 0,0 harus menyisakan bar Step3-lolos di dataset ini (didapat $longgar)",
            longgar > 0
        )
        assertTrue(
            "Ambang +99,0% (di atas maksimum histogram dataset) harus memangkas semuanya " +
                "(longgar $longgar vs ketat $ketat)",
            ketat < longgar
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Perkakas
    // ─────────────────────────────────────────────────────────────────────────

    private fun wib(hour: Int, minute: Int): Long =
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jakarta")).apply {
            set(2026, java.util.Calendar.OCTOBER, 27, hour, minute, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Muat candle H1 dari dataset riset ter-commit `data/tokocrypto_v7`. */
    private fun muatCandleH1Riset(symbol: String): List<CandleBar> {
        val relatif = "data/tokocrypto_v7/${symbol}_1h.csv"
        val base = File(System.getProperty("user.dir") ?: ".")
        val berkas = listOf(
            base,
            File(base, "app"),
            base.parentFile ?: base,
            File(base.parentFile ?: base, "app")
        ).map { File(it, relatif) }.firstOrNull { it.exists() }
            ?: error("Berkas riset tidak ditemukan: $relatif (dicari dari $base)")
        return berkas.readLines().drop(1).filter { it.isNotBlank() }.map { baris ->
            val kolom = baris.split(",")
            CandleBar(
                timestamp = kolom[0].toLong(),
                open = kolom[1].toDouble(),
                high = kolom[2].toDouble(),
                low = kolom[3].toDouble(),
                close = kolom[4].toDouble(),
                volume = kolom[5].toDouble()
            )
        }
    }
}
