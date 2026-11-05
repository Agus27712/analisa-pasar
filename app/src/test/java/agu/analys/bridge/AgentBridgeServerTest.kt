package agu.analys.bridge

import android.content.Context
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Uji **perilaku nyata** server bridge: bukan serialisasi model, tetapi "apakah sakelar di
 * Pengaturan benar-benar menyalakan server dan tidak menutup aplikasi".
 *
 * Latar belakang (2026-11-04): `start()` dulu memanggil `embeddedServer(...)` langsung di main
 * thread, dan kegagalannya berupa `NoSuchMethodError` (konflik kotlinx-coroutines dengan Ktor
 * 2.3.9). Karena `NoSuchMethodError` turunan `Error`, `catch (e: Exception)` tidak menangkapnya
 * sehingga aplikasi force close begitu sakelar dinyalakan. Uji di berkas ini menjaga tiga sifat:
 *
 * 1. `start()` **tidak memblokir** pemanggil (pekerjaan server berjalan di thread terpisah);
 * 2. keadaan akhir terlihat jelas lewat [AgentHttpServer.state] — `Running` hanya setelah port
 *    benar-benar melayani, bukan setelah objek server dibuat;
 * 3. `stop()` benar-benar menutup port, dan kegagalan menyala dilaporkan sebagai `Failed`
 *    (bukan dilempar ke pemanggil).
 */
@RunWith(RobolectricTestRunner::class)
class AgentBridgeServerTest {

    private lateinit var context: Context
    private lateinit var server: AgentHttpServer

    @Before
    fun siapkan() {
        runBlocking {
            context = RuntimeEnvironment.getApplication()
            server = AgentHttpServer.getInstance(context)
            server.stop()
            server.awaitSettled(10_000)
        }
    }

    private fun httpGet(path: String): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:${AgentHttpServer.PORT}$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 1_500
        conn.readTimeout = 1_500
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `sakelar menyala membuka port dan stop menutupnya`() {
        runBlocking {
            server.start()
            val state = server.awaitSettled(20_000)
            assertTrue("keadaan akhir: $state", state is AgentBridgeServerState.Running)
            assertTrue(server.isRunning)

            val (healthCode, healthBody) = httpGet("/health")
            assertEquals(200, healthCode)
            assertTrue("badan /health: $healthBody", healthBody.contains("\"ok\":true"))

            val (rootCode, rootBody) = httpGet("/")
            assertEquals(200, rootCode)
            assertTrue("badan /: $rootBody", rootBody.contains("analisa-pasar-agent-bridge"))

            server.stop()
            assertEquals(AgentBridgeServerState.Stopped, server.awaitSettled(10_000))
            assertTrue(!server.isRunning)
            val afterStop = runCatching { httpGet("/health") }
            assertTrue("port masih melayani setelah stop", afterStop.isFailure)
        }
    }

    @Test
    fun `start tidak memblokir thread pemanggil`() {
        val startedAt = System.nanoTime()
        server.start()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("start() memblokir pemanggil selama $elapsedMs ms", elapsedMs < 500)
        runBlocking { server.awaitSettled(20_000) }
    }

    @Test
    fun `port terpakai dilaporkan Failed bukan melempar keluar`() {
        runBlocking {
            ServerSocket(AgentHttpServer.PORT).use {
                server.start()
                val state = server.awaitSettled(20_000)
                assertTrue("keadaan akhir: $state", state is AgentBridgeServerState.Failed)
                assertTrue("server tidak boleh diklaim berjalan", !server.isRunning)
            }

            // Setelah port bebas, percobaan berikutnya harus bisa berhasil (bukan terkunci di Failed).
            server.start()
            val retry = server.awaitSettled(20_000)
            assertTrue("keadaan akhir percobaan ulang: $retry", retry is AgentBridgeServerState.Running)
            server.stop()
            server.awaitSettled(10_000)
        }
    }
}
