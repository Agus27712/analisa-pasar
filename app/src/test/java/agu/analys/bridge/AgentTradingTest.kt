package agu.analys.bridge

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
class AgentTradingTest {

    private lateinit var context: Context
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        AgentTradingCommandHandler.unregister()
    }

    @Test
    fun `parseAndValidateSymbol memvalidasi kuotasi USDT dan IDR dengan benar`() {
        val btcUsdt = AgentTradingCommandHandler.parseAndValidateSymbol("BTCUSDT")
        assertNotNull(btcUsdt)
        assertEquals("BTC", btcUsdt?.first)
        assertEquals("USDT", btcUsdt?.second)
        assertEquals("BTC_USDT", btcUsdt?.third)

        val ethIdr = AgentTradingCommandHandler.parseAndValidateSymbol("eth_idr")
        assertNotNull(ethIdr)
        assertEquals("ETH", ethIdr?.first)
        assertEquals("IDR", ethIdr?.second)

        // Kuotasi tidak valid atau BIDR umum ditolak
        assertNull(AgentTradingCommandHandler.parseAndValidateSymbol("DOGEBIDR"))
        assertNull(AgentTradingCommandHandler.parseAndValidateSymbol("BTCBUSD"))
        assertNull(AgentTradingCommandHandler.parseAndValidateSymbol(""))
    }

    @Test
    fun `handleBuy menolak order riil saat coordinator belum siap atau mode real mati`() {
        runBlocking {
            val req = AgentTradeRequest(
                symbol = "BTCUSDT",
                side = "BUY",
                quoteAmount = 15.0,
                price = 65000.0,
                isSimulation = false
            )
            val resp = AgentTradingCommandHandler.handleBuy(req, context)
            assertFalse(resp.success)
            assertTrue("pesan penolakan: ${resp.message}", resp.message.contains("belum siap") || resp.message.contains("dimatikan"))
        }
    }

    @Test
    fun `handleBuy menolak simbol yang kuotasinya tidak sah`() {
        runBlocking {
            val req = AgentTradeRequest(
                symbol = "INVALIDPAIR",
                side = "BUY",
                quoteAmount = 10.0,
                price = 100.0,
                isSimulation = true
            )
            val resp = AgentTradingCommandHandler.handleBuy(req, context)
            assertFalse(resp.success)
            assertTrue("pesan simbol tidak valid: ${resp.message}", resp.message.contains("Format simbol"))
        }
    }

    @Test
    fun `model serialisasi JSON dapat di-encode dan di-decode secara konsisten`() {
        val req = AgentTradeRequest(
            symbol = "BTCUSDT",
            side = "BUY",
            quoteAmount = 25.0,
            price = 65000.0,
            isSimulation = true
        )
        val str = json.encodeToString(req)
        assertTrue(str.contains("\"symbol\":\"BTCUSDT\""))
        assertTrue(str.contains("\"quoteAmount\":25.0"))

        val decoded = json.decodeFromString<AgentTradeRequest>(str)
        assertEquals("BTCUSDT", decoded.symbol)
        assertEquals(25.0, decoded.quoteAmount)
        assertTrue(decoded.isSimulation)

        val resp = AgentTradeResponse(
            success = true,
            message = "Order diterima",
            orderId = "12345",
            symbol = "BTC_USDT",
            side = "BUY",
            price = 65000.0,
            quantity = 0.00038,
            status = "FILLED"
        )
        val respStr = json.encodeToString(resp)
        val respDecoded = json.decodeFromString<AgentTradeResponse>(respStr)
        assertEquals("12345", respDecoded.orderId)
        assertEquals("FILLED", respDecoded.status)
    }

    @Test
    fun `endpoint status trading mengembalikan status JSON yang valid`() {
        val status = AgentTradingCommandHandler.getTradingStatus(context)
        assertFalse(status.isRealBuyEnabled)
        assertEquals(8765, status.port)
        assertEquals(AgentHttpServer.HOST, status.host)
        val jsonStr = json.encodeToString(status)
        assertTrue("isi status host: $jsonStr", jsonStr.contains("\"host\":\"${AgentHttpServer.HOST}\""))
        assertTrue("isi status port: $jsonStr", jsonStr.contains("\"port\":8765"))
    }
}
