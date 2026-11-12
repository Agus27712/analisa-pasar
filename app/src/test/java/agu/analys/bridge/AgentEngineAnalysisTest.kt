package agu.analys.bridge

import agu.analys.model.AISignalState
import agu.analys.model.ScalpingMtfSnapshot
import agu.analys.model.SignalAction
import agu.analys.model.TechnicalIndicators
import agu.analys.model.TrendSentiment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Penjaga pemetaan analisis engine ke DTO bridge.
 *
 * Yang dijaga di sini murni bentuk data: simbol, bias gabungan, dan pemetaan angka mesin.
 * Tidak ada uji jaringan — angka bursa tidak boleh jadi bagian uji unit.
 */
class AgentEngineAnalysisTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

    @Test
    fun `simbol agent dinormalisasi ke bentuk aplikasi`() {
        assertEquals("BTCUSDT", AgentEngineAnalysisMapper.normalizeSymbol("btcusdt"))
        assertEquals("BTCUSDT", AgentEngineAnalysisMapper.normalizeSymbol("BTC/USDT"))
        assertEquals("BTCUSDT", AgentEngineAnalysisMapper.normalizeSymbol(" BTC_USDT "))
        assertEquals("BTCIDR", AgentEngineAnalysisMapper.normalizeSymbol("BTC-IDR"))
    }

    @Test
    fun `simbol tanpa kuotasi dikenal ditolak`() {
        assertNull(AgentEngineAnalysisMapper.normalizeSymbol(""))
        assertNull(AgentEngineAnalysisMapper.normalizeSymbol("   "))
        assertNull(AgentEngineAnalysisMapper.normalizeSymbol("BTC"))
        assertNull(AgentEngineAnalysisMapper.normalizeSymbol("BTCEUR"))
    }

    @Test
    fun `quote BIDR dilebur ke IDR dan-pair BIDR tetap IDR`() {
        assertEquals("XAUTIDR", AgentEngineAnalysisMapper.normalizeSymbol("XAUTBIDR"))
        assertEquals("BNBIDR", AgentEngineAnalysisMapper.normalizeSymbol("BNBIDR"))
        assertEquals("IDR", AgentEngineAnalysisMapper.quoteOf("BNBIDR"))
        assertEquals("USDT", AgentEngineAnalysisMapper.quoteOf("BTCUSDT"))
    }

    @Test
    fun `bias gabungan mengikuti jumlah arah BUY dan SELL`() {
        assertEquals("BULLISH_CONFLUENCE", AgentEngineAnalysisMapper.overallBias(listOf("BUY", "BUY", "SELL")))
        assertEquals("LEAN_BULLISH", AgentEngineAnalysisMapper.overallBias(listOf("BUY", "HOLD", "HOLD")))
        assertEquals("BEARISH_CONFLUENCE", AgentEngineAnalysisMapper.overallBias(listOf("SELL", "SELL", "BUY")))
        assertEquals("LEAN_BEARISH", AgentEngineAnalysisMapper.overallBias(listOf("HOLD", "HOLD", "SELL")))
        assertEquals("NEUTRAL_MIXED", AgentEngineAnalysisMapper.overallBias(listOf("BUY", "SELL", "HOLD")))
        assertEquals("NEUTRAL_MIXED", AgentEngineAnalysisMapper.overallBias(emptyList()))
    }

    @Test
    fun `indikator NaN menjadi null bukan nol`() {
        val dto = AgentEngineAnalysisMapper.indicatorsOf(
            TechnicalIndicators(
                rsi14 = Double.NaN,
                macd = 0.45,
                ema20 = Double.POSITIVE_INFINITY,
                atr = 1.25
            )
        )
        assertNull(dto.rsi14)
        assertNull(dto.ema20)
        assertEquals(0.45, dto.macd!!, 1e-9)
        assertEquals(1.25, dto.atr!!, 1e-9)
    }

    @Test
    fun `hasil scalping memuat checkpoint mesin apa adanya`() {
        val signal = AISignalState(
            action = SignalAction.BUY,
            confidence = 72,
            entryPrice = 100.0,
            targetPrice1 = 101.0,
            targetPrice2 = 102.0,
            stopLoss = 99.0,
            riskRewardRatio = "1:2",
            scalpingSetup = "Breakout Retest",
            scalpingScore = 61,
            scalpingScoreCategory = "Moderat",
            scalpingRegime = "TRENDING",
            mtf = ScalpingMtfSnapshot(
                completedCount = 3,
                checkpoints = listOf(
                    agu.analys.model.ConfluenceCheckpoint(
                        number = 1,
                        code = "MTF",
                        label = "Struktur MTF",
                        isOk = true,
                        metricValue = "Uptrend Selaras"
                    )
                )
            )
        )
        val dto = AgentEngineAnalysisMapper.scalpingOf(signal, 100.0)
        assertEquals("BUY", dto.action)
        assertEquals("Breakout Retest", dto.setup)
        assertEquals(61, dto.score)
        assertEquals("TRENDING", dto.regime)
        assertEquals(1, dto.checkpoints.size)
        assertEquals("MTF", dto.checkpoints.first().code)
        assertTrue(dto.checkpoints.first().isOk)
    }

    @Test
    fun `entry scalping fallback ke harga pasar bila sinyal tidak punya entry`() {
        val dto = AgentEngineAnalysisMapper.scalpingOf(AISignalState(), 123.45)
        assertEquals(123.45, dto.entryPrice, 1e-9)
        assertEquals("HOLD", dto.action)
    }

    @Test
    fun `hasil swing memakai Bollinger Band sebagai support dan resistance`() {
        val signal = AISignalState(
            action = SignalAction.HOLD,
            confidence = 55,
            sentiment = TrendSentiment.BULLISH_REVERSAL
        )
        val dto = AgentEngineAnalysisMapper.swingOf(
            signal,
            TechnicalIndicators(bbLower = 95.0, bbUpper = 105.0)
        )
        assertEquals(95.0, dto.support, 1e-9)
        assertEquals(105.0, dto.resistance, 1e-9)
        assertEquals("Pembalikan Arah Bullish", dto.trend)
    }

    @Test
    fun `analisis tidak tersedia memuat alasan dan tidak memuat angka mode`() {
        val dto = AgentEngineAnalysis(
            symbol = "BTC",
            available = false,
            error = "Simbol 'BTC' tidak dikenali."
        )
        val encoded = json.encodeToString(dto)
        assertTrue(encoded.contains("\"available\":false"))
        assertTrue(encoded.contains("tidak dikenali"))
        assertFalse(encoded.contains("\"scalping\":{\""))
        assertNull(dto.scalping)
    }

    @Test
    fun `hasil lengkap terserialisasi dengan field kunci untuk agen`() {
        val dto = AgentEngineAnalysis(
            symbol = "BTCUSDT",
            available = true,
            exchange = "TOKOCRYPTO",
            price = 68000.0,
            scalping = AgentScalpingAnalysis(action = "BUY", stage = "ENTRY", confidence = 85),
            intraday = AgentIntradayAnalysis(action = "HOLD", confidence = 40),
            swing = AgentSwingAnalysis(action = "HOLD", confidence = 40),
            indicators = AgentTechnicalIndicators(rsi14 = 52.5),
            overallBias = "LEAN_BULLISH"
        )
        val encoded = json.encodeToString(dto)
        assertTrue(encoded.contains("\"symbol\":\"BTCUSDT\""))
        assertTrue(encoded.contains("\"exchange\":\"TOKOCRYPTO\""))
        assertTrue(encoded.contains("\"overallBias\":\"LEAN_BULLISH\""))
        assertTrue(encoded.contains("\"rsi14\":52.5"))
        assertTrue(encoded.contains("\"stage\":\"ENTRY\""))
    }
}
