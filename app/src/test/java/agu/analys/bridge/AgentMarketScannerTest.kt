package agu.analys.bridge

import agu.analys.model.MarketTick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji murni Kotlin untuk [AgentMarketScanner] dan [AgentMarketScannerPusher]:
 * - Validasi input pilihan tunggal (menolak kosong atau multi-pair)
 * - Isolasi bursa aktif (Indodax hanya IDR; Tokocrypto IDR+USDT tanpa BIDR/stablecoin)
 * - Penalti overextension (>15%) agar tidak mendorong entry di pucuk ekstrem
 * - Deduplikasi & heartbeat pusher
 */
class AgentMarketScannerTest {

    private fun tick(
        symbol: String,
        price: Double,
        change24h: Double,
        high24h: Double,
        low24h: Double,
        volume24h: Double
    ) = MarketTick(
        symbol = symbol,
        price = price,
        change24h = change24h,
        high24h = high24h,
        low24h = low24h,
        volume24h = volume24h
    )

    @Test
    fun `parseSingleSymbolInput menerima 1 pair valid dan menolak multi-pair`() {
        assertEquals("BTCUSDT", AgentMarketScanner.parseSingleSymbolInput("btc_usdt"))
        assertEquals("ETHIDR", AgentMarketScanner.parseSingleSymbolInput(" eth/idr "))
        assertEquals("SOLUSDT", AgentMarketScanner.parseSingleSymbolInput("SOLUSDT"))

        assertNull(AgentMarketScanner.parseSingleSymbolInput(""))
        assertNull(AgentMarketScanner.parseSingleSymbolInput("   "))
        assertNull(AgentMarketScanner.parseSingleSymbolInput("BTCUSDT,ETHUSDT"))
        assertNull(AgentMarketScanner.parseSingleSymbolInput("BTCUSDT ETHUSDT"))
        assertNull(AgentMarketScanner.parseSingleSymbolInput("BTCUSDT;ETHUSDT"))
        assertNull(AgentMarketScanner.parseSingleSymbolInput("[\"BTCUSDT\"]"))
        assertNull(AgentMarketScanner.parseSingleSymbolInput("BTCUSDT+ETHUSDT"))
    }

    @Test
    fun `scoreAllPairs mengisolasi bursa Indodax hanya IDR dan Tokocrypto tanpa stablecoin atau BIDR`() {
        val mixedTicks = listOf(
            tick("BTCUSDT", 68_000.0, 4.2, 69_000.0, 65_000.0, 20_000_000.0),
            tick("BTCIDR", 1_100_000_000.0, 4.0, 1_120_000_000.0, 1_050_000_000.0, 50_000_000_000.0),
            tick("ETHIDR", 45_000_000.0, 3.1, 46_000_000.0, 43_000_000.0, 30_000_000_000.0),
            tick("BNBBIDR", 9_000_000.0, 2.0, 9_200_000.0, 8_800_000.0, 20_000_000.0),
            tick("USDCUSDT", 1.0, 0.01, 1.001, 0.999, 50_000_000.0)
        )

        val indodaxItems = AgentMarketScanner.scoreAllPairs("INDODAX", mixedTicks, usdtIdrRate = 16_000.0)
        assertTrue(indodaxItems.all { it.quoteAsset == "IDR" })
        assertFalse(indodaxItems.any { it.symbol == "BTCUSDT" })
        assertTrue(indodaxItems.any { it.symbol == "BTCIDR" })
        assertTrue(indodaxItems.any { it.symbol == "ETHIDR" })

        val tokoItems = AgentMarketScanner.scoreAllPairs("TOKOCRYPTO", mixedTicks, usdtIdrRate = 16_000.0)
        assertTrue(tokoItems.any { it.symbol == "BTCUSDT" })
        assertTrue(tokoItems.any { it.symbol == "BTCIDR" })
        assertFalse(tokoItems.any { it.symbol == "USDCUSDT" })
        assertFalse(tokoItems.any { it.symbol == "BNBBIDR" })
    }

    @Test
    fun `scoreAllPairs memberi penalti pair overextended dibanding kenaikan sehat`() {
        val ticks = listOf(
            tick("SOLUSDT", 180.0, 5.5, 185.0, 168.0, 25_000_000.0),
            tick("PUMPUSDT", 12.0, 28.0, 12.1, 9.0, 25_000_000.0)
        )

        val scored = AgentMarketScanner.scoreAllPairs("TOKOCRYPTO", ticks, usdtIdrRate = 16_000.0)
        assertEquals(2, scored.size)
        val healthy = scored.first { it.symbol == "SOLUSDT" }
        val overextended = scored.first { it.symbol == "PUMPUSDT" }

        assertEquals("OVEREXTENDED", overextended.momentumTier)
        assertTrue("Expected healthy ($healthy) > overextended ($overextended)", healthy.serverScore > overextended.serverScore)
        assertEquals("SOLUSDT", scored.first().symbol)
    }

    @Test
    fun `AgentMarketScannerPusher mendeteksi perubahan skor, pergantian bursa, dan heartbeat`() {
        val cand = AgentMarketScanCandidate(
            rank = 1,
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            quoteAsset = "USDT",
            exchange = "TOKOCRYPTO",
            price = 68_000.0,
            change24h = 4.0,
            volume24h = 10_000_000.0,
            volumeIdr = 160_000_000_000.0,
            rangePositionPct = 75.0,
            serverScore = 84.0,
            momentumTier = "STRONG_UP",
            reasonSummary = "test"
        )
        val scanToko = AgentMarketScanOutput(
            ts = 1_000L,
            available = true,
            exchange = "TOKOCRYPTO",
            totalPairsScanned = 50,
            bullishCount = 12,
            topCandidates = listOf(cand)
        )
        val sig1 = AgentMarketScannerPusher.signature(scanToko)
        assertTrue(AgentMarketScannerPusher.shouldEmit(null, sig1, nowMs = 1_000L, lastEmitMs = 0L))
        assertFalse(AgentMarketScannerPusher.shouldEmit(sig1, sig1, nowMs = 61_000L, lastEmitMs = 1_000L))
        assertTrue(
            AgentMarketScannerPusher.shouldEmit(
                sig1,
                sig1,
                nowMs = 1_000L + AgentMarketScannerPusher.HEARTBEAT_MS,
                lastEmitMs = 1_000L
            )
        )

        val scanIndodax = scanToko.copy(
            exchange = "INDODAX",
            topCandidates = listOf(cand.copy(exchange = "INDODAX", symbol = "BTCIDR"))
        )
        val sig2 = AgentMarketScannerPusher.signature(scanIndodax)
        assertTrue(AgentMarketScannerPusher.shouldEmit(sig1, sig2, nowMs = 2_000L, lastEmitMs = 1_000L))
    }
}
