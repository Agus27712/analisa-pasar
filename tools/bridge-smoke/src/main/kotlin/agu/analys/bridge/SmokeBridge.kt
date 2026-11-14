package agu.analys.bridge
import android.content.Context
import java.nio.file.Files

/**
 * Stub smoke untuk `AgentOutputBridge` produksi.
 *
 * `AgentHttpServer` memakai `getInstance`/`getDir`/`getLatest`/`startPortfolioPush`;
 * stub ini harus punya keempatnya atau smoke gagal compile.
 */
class AgentOutputBridge {
    private val dir = Files.createTempDirectory("bridge-smoke").toFile()
    val snapshot = AgentSignalOutput(1L, "test", "SNAPSHOT", "USDT", "test", "BUY", 90f, 1.0, reason = "smoke")
    fun getDir() = dir
    fun getLatest() = snapshot
    fun startPortfolioPush() = Unit
    fun startMarketScanPush() = Unit
    companion object {
        private val instance = AgentOutputBridge()
        fun getInstance(context: Context) = instance
    }
}
