package agu.analys.engine.swing

import agu.analys.model.CandleBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * Golden baseline `SwingEvaluator` pada `SwingConfig.DEFAULT`.
 *
 * Fingerprint direkam DENGAN KODE ASLI (sebelum parameter ditambahkan, 2026-11-03) lewat
 * `TempBaselineDumpTest` (sementara, sudah dihapus). Angka di bawah = hasil kode asli; refactor
 * config dihitung LULUS hanya bila `SwingConfig.DEFAULT` memproduksi fingerprint yang sama
 * persis — keputusan, skor, harga, dan teks reasoning byte-per-byte.
 *
 * Fungsi kedua menguji perilaku slider memakai knob yang terbukti mengubah output fixture ini
 * (hasil probe 2026-11-03): `overboughtRsi` (confidence), `nearLevelPct` (keputusan), dan
 * `slippagePct` (R:R). PERHATIAN: `TestData.generateCandles` TIDAK deterministik antar-run, jadi
 * fixture bakeaan itu tidak dipakai sebagai golden. Jangan menambah case baru tanpa merekam ulang
 * baseline dari kode pohon kerja.
 */
class SwingEvaluatorGoldenTest {

    /** Fingerprint numerik baris A_qualified — direkam dari kode asli. */
    private data class GoldenFp(
        val action: String,
        val conf: Int,
        val entry: Double,
        val tp1: Double,
        val tp2: Double,
        val sl: Double,
        val rr: String,
        val stage: String,
        val reasoning: String
    )

    private val golden = mapOf(
        "A_qualified" to GoldenFp(
            action = "HOLD", conf = 36,
            entry = 1018.0000000000023, tp1 = 1089.2600000000025,
            tp2 = 1180.8800000000026, sl = 1002.7300000000022,
            rr = "1:6.63", stage = "ENTRY",
            reasoning = "Kondisi Pasar: TRANSITION / MIXED. ## Level penting → Support Rp 952 | Resistance Rp 1.041. ## RSI 48.24 momentum bullish terjaga. ## EMA20 > EMA50, harga di atas EMA (uptrend selaras). ## MACD histogram negatif (tekanan jual). ## Volume beli 5.42× di atas rata-rata. ## Struktur market range / transisi. ## Pola Candlestick: Bullish Engulfing."
        ),
        // B_bearish (TestData.generateCandles) dilepas dari golden: fixture itu nondeterministik
        // antar-run (recent high bergeser 957 → 993), jadi tidak sah sebagai baseline.
        "C_doge_1200" to GoldenFp(
            action = "HOLD", conf = 36,
            entry = 0.10316, tp1 = 0.11038120000000001,
            tp2 = 0.1196656, sl = 0.1016126,
            rr = "1:6.63", stage = "ENTRY",
            reasoning = "Kondisi Pasar: TRANSITION / MIXED. ## Level penting → Support Rp 0,1027 | Resistance Rp 0,1051. ## RSI 55.63 momentum bullish terjaga. ## Harga reclaim EMA20 & EMA50 dengan momentum positif. ## MACD histogram positif (momentum beli). ## Struktur market range / transisi."
        ),
        "C_doge_2500" to GoldenFp(
            action = "HOLD", conf = 0,
            entry = 0.07229, tp1 = 0.0773503,
            tp2 = 0.08385639999999998, sl = 0.07120565,
            rr = "1:6.63", stage = "EARLY_ENTRY",
            reasoning = "⚠️ Tertahan: Harga koin sedang turun menembus support (Breakdown). ## Kondisi Pasar: TRENDING DOWN. ## Level penting → Support Rp 0,0718 | Resistance Rp 0,0729. ## RSI 38.23 di zona akumulasi sehat. ## EMA20 < EMA50, harga di bawah EMA20 (downtrend makro). ## MACD histogram negatif (tekanan jual). ## Struktur market range / transisi. ## Pola Candlestick: Bullish Pin Bar."
        ),
        "C_doge_3800" to GoldenFp(
            action = "BUY", conf = 92,
            entry = 0.09143, tp1 = 0.0978301,
            tp2 = 0.1060588, sl = 0.09033284,
            rr = "1:7.63", stage = "ENTRY",
            reasoning = "✅ Setup BREAKOUT valid — entry swing siap. ## Kondisi Pasar: TRENDING UP. ## Level penting → Support Rp 0,0897 | Resistance Rp 0,0917. ## BREAKOUT: harga tembus Resistance Rp 0,0917 dengan candle solid + volume 1.39× (lebih aman tunggu retest). ## RSI 61.61 momentum bullish terjaga. ## EMA20 > EMA50, harga di atas EMA (uptrend selaras). ## MACD histogram positif (momentum beli). ## Volume beli 1.39× di atas rata-rata."
        ),
        "C_doge_4998" to GoldenFp(
            action = "HOLD", conf = 65,
            entry = 0.07803, tp1 = 0.08349210000000001,
            tp2 = 0.09051479999999999, sl = 0.07685955,
            rr = "1:6.63", stage = "EARLY_ENTRY",
            reasoning = "Kondisi Pasar: TRANSITION / MIXED. ## Level penting → Support Rp 0,078 | Resistance Rp 0,078. ## RETEST Resistance gagal: level Rp 0,078 ditolak lagi setelah pernah tembus. ## RSI 38.88 di zona akumulasi sehat. ## EMA20 > EMA50, harga di atas EMA (uptrend selaras). ## MACD histogram negatif (tekanan jual). ## Struktur market range / transisi. ## Pola Candlestick: Bearish Engulfing."
        )
    )

    private fun cekFingerprint(tag: String, result: SwingEvalResult) {
        val g = golden.getValue(tag)
        val s = result.signal
        assertEquals("[$tag] action", g.action, s.action.name)
        assertEquals("[$tag] confidence", g.conf, s.confidence)
        assertEquals("[$tag] entry", g.entry, s.entryPrice, g.entry * 1e-12 + 1e-12)
        assertEquals("[$tag] tp1", g.tp1, s.targetPrice1, g.tp1 * 1e-12 + 1e-12)
        assertEquals("[$tag] tp2", g.tp2, s.targetPrice2, g.tp2 * 1e-12 + 1e-12)
        assertEquals("[$tag] sl", g.sl, s.stopLoss, g.sl * 1e-12 + 1e-12)
        assertEquals("[$tag] rr", g.rr, s.riskRewardRatio)
        assertEquals("[$tag] stage", g.stage, s.scalpingStage.name)
        assertEquals(
            "[$tag] reasoning",
            g.reasoning,
            s.reasoning.joinToString(" ## ")
        )
    }

    /**
     * Fixture A: 150 bar uptrend + 4 koreksi + 1 candle pantulan ber-volume kuat
     * (reproduce dari fixture TempBaselineDumpTest sebelum refactor).
     */
    private fun qualifiedSwingHistory(): List<CandleBar> {
        val baseTime = 1_700_000_000_000L
        val history = mutableListOf<CandleBar>()
        var p = 800.0
        for (i in 0 until 150) {
            val open = p
            val close = p + 1.6
            history.add(CandleBar(baseTime + i * 3_600_000L, open, close + 0.5, open - 0.5, close, 2500.0))
            p = close
        }
        val peak = p
        val corrections = listOf(peak - 8.0, peak - 16.0, peak - 23.0, peak - 28.0)
        for ((idx, target) in corrections.withIndex()) {
            val open = p
            history.add(CandleBar(baseTime + (150 + idx) * 3_600_000L, open, open + 1.0, target - 1.0, target, 2000.0))
            p = target
        }
        val lastOpen = p
        val lastClose = p + 6.0
        history.add(CandleBar(baseTime + 154 * 3_600_000L, lastOpen, lastClose + 0.5, lastOpen - 2.0, lastClose, 12_000.0))
        return history
    }

    private fun muatDoge1h(): List<CandleBar> {
        val kandidat = listOf(
            File("data/tokocrypto_v8/DOGEUSDT_1h.csv"),
            File("../data/tokocrypto_v8/DOGEUSDT_1h.csv")
        )
        val berkas = kandidat.firstOrNull { it.isFile }
            ?: error("Dataset DOGEUSDT_1h.csv tidak ditemukan")
        return berkas.readLines().drop(1).filter { it.isNotBlank() }.map {
            val k = it.split(",")
            CandleBar(k[0].toLong(), k[1].toDouble(), k[2].toDouble(), k[3].toDouble(), k[4].toDouble(), k[5].toDouble())
        }
    }

    @Test
    fun `golden lima fixture DEFAULT harus identik baseline pra-refactor`() {
        cekFingerprint("A_qualified", SwingEvaluator.evaluate(qualifiedSwingHistory().last().close, qualifiedSwingHistory()))

        val doge = muatDoge1h()
        for (idx in listOf(1200, 2500, 3800, 4998)) {
            val slice = doge.take(idx + 1)
            cekFingerprint("C_doge_$idx", SwingEvaluator.evaluate(slice.last().close, slice))
        }
    }

    @Test
    fun `menggeser knob terasa pada keputusan dan param tanpa config identik DEFAULT`() {
        val hist = qualifiedSwingHistory()
        val price = hist.last().close

        // Kontrol: evaluasi tanpa config = DEFAULT = fingerprint yang sama.
        val bawaan = SwingEvaluator.evaluate(price, hist)
        val denganDefault = SwingEvaluator.evaluate(price, hist, config = SwingConfig.DEFAULT)
        assertEquals(bawaan.signal.confidence, denganDefault.signal.confidence)
        assertEquals(bawaan.signal.action, denganDefault.signal.action)
        assertEquals(bawaan.signal.entryPrice, denganDefault.signal.entryPrice, 0.0)
        assertEquals(bawaan.signal.riskRewardRatio, denganDefault.signal.riskRewardRatio)

        // Slider menyentuh keputusan (hasil probe 2026-11-03 pada fixture ini — knob lain
        // terhalang lantai internal/kondisi fixture, lihat komentar KDoc atas):
        // 1) overboughtRsi 40 → RSI 48,24 dianggap jenuh beli → confidence 36 menjadi 0.
        val jenuhBeli = SwingEvaluator.evaluate(
            price, hist,
            config = SwingConfig.DEFAULT.copy(overboughtRsi = 40.0)
        ).signal
        assertEquals(0, jenuhBeli.confidence)
        assertEquals(36, denganDefault.signal.confidence)

        // 2) nearLevelPct 7% → setup REJECTION/BREAKOUT di level terbaca → HOLD menjadi BUY.
        val levelLebar = SwingEvaluator.evaluate(
            price, hist,
            config = SwingConfig.DEFAULT.copy(nearLevelPct = 7.0)
        ).signal
        assertEquals("BUY", levelLebar.action.name)
        assertEquals("HOLD", denganDefault.signal.action.name)

        // 3) slippage 0,5% → net R:R tampilan berubah (1:6,63 → rendah).
        val slipBesar = SwingEvaluator.evaluate(
            price, hist,
            config = SwingConfig.DEFAULT.copy(slippagePct = 0.5)
        ).signal
        assertNotEquals(denganDefault.signal.riskRewardRatio, slipBesar.riskRewardRatio)
    }
}
