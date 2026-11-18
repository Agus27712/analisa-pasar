package agu.analys.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Uji murni Kotlin untuk [AgentLiveSignalPusher]:
 * - Sinyal pertama dan transisi action/stage/skor >=2 langsung EMIT_IMMEDIATE (tanpa tunggu)
 * - Pergeseran halus (harga/skor ±1) ditahan di jendela debounce 1,5s (DEFER_DEBOUNCE) dan
 *   langsung dipancarkan (EMIT_DEBOUNCED) setelah debounce terlewati
 * - Sinyal identik di-SKIP sampai batas heartbeat
 */
class AgentLiveSignalPusherTest {

    private fun signal(
        action: String = "HOLD",
        stage: String = "HOLD",
        confidence: Float = 40f,
        score: Int = 42,
        price: Double = 100.0,
        reason: String = "Pantau",
        setup: String? = null,
        checkpointsOk: Int? = 1
    ) = AgentSignalOutput(
        ts = 1_000L,
        exchange = "tokocrypto",
        symbol = "BTCUSDT",
        quote = "USDT",
        mode = "SCALPING",
        action = action,
        confidence = confidence,
        price = price,
        reason = reason,
        technical = TechnicalBreakdown(
            mtfDirection = "BULLISH",
            mtfCheckpointsOk = checkpointsOk,
            scalpingSetup = setup,
            scalpingRegime = "RANGING",
            scalpingStage = stage,
            scalpingScore = score
        )
    )

    @Test
    fun `sinyal HOLD pertama dan transisi stage atau action langsung EMIT_IMMEDIATE`() {
        val fpHold = AgentLiveSignalPusher.fingerprintOf(signal(action = "HOLD", stage = "HOLD"))
        assertEquals(
            AgentLiveSignalPusher.Decision.EMIT_IMMEDIATE,
            AgentLiveSignalPusher.evaluate(null, fpHold, nowMs = 1_000L, lastEmitMs = 0L)
        )

        val fpWatch = AgentLiveSignalPusher.fingerprintOf(signal(action = "HOLD", stage = "WATCH"))
        assertEquals(
            AgentLiveSignalPusher.Decision.EMIT_IMMEDIATE,
            AgentLiveSignalPusher.evaluate(fpHold, fpWatch, nowMs = 1_200L, lastEmitMs = 1_000L)
        )

        val fpBuy = AgentLiveSignalPusher.fingerprintOf(
            signal(action = "BUY", stage = "CONFIRMED_ENTRY", confidence = 80f, score = 78)
        )
        assertEquals(
            AgentLiveSignalPusher.Decision.EMIT_IMMEDIATE,
            AgentLiveSignalPusher.evaluate(fpWatch, fpBuy, nowMs = 1_250L, lastEmitMs = 1_200L)
        )
    }

    @Test
    fun `lompatan skor atau confidence minimal 2 poin langsung EMIT_IMMEDIATE walau stage sama`() {
        val fp1 = AgentLiveSignalPusher.fingerprintOf(signal(stage = "WATCH", confidence = 50f, score = 55))
        val fp2 = AgentLiveSignalPusher.fingerprintOf(signal(stage = "WATCH", confidence = 50f, score = 57))
        assertEquals(
            AgentLiveSignalPusher.Decision.EMIT_IMMEDIATE,
            AgentLiveSignalPusher.evaluate(fp1, fp2, nowMs = 1_100L, lastEmitMs = 1_000L)
        )
    }

    @Test
    fun `perubahan harga atau skor minor ditunda 1,5 detik lalu dipancarkan setelah debounce`() {
        val fp1 = AgentLiveSignalPusher.fingerprintOf(signal(stage = "WATCH", score = 55, price = 100.0))
        val fpPriceMove = AgentLiveSignalPusher.fingerprintOf(signal(stage = "WATCH", score = 55, price = 100.20))

        // Masih di dalam jendela 1,5 detik -> DEFER_DEBOUNCE
        assertEquals(
            AgentLiveSignalPusher.Decision.DEFER_DEBOUNCE,
            AgentLiveSignalPusher.evaluate(fp1, fpPriceMove, nowMs = 1_800L, lastEmitMs = 1_000L)
        )

        // Setelah 1,5 detik tercapai -> EMIT_DEBOUNCED
        assertEquals(
            AgentLiveSignalPusher.Decision.EMIT_DEBOUNCED,
            AgentLiveSignalPusher.evaluate(
                fp1,
                fpPriceMove,
                nowMs = 1_000L + AgentLiveSignalPusher.DEBOUNCE_MS,
                lastEmitMs = 1_000L
            )
        )
    }

    @Test
    fun `isi identik di-SKIP sebelum heartbeat dan EMIT_DEBOUNCED saat heartbeat`() {
        val fp = AgentLiveSignalPusher.fingerprintOf(signal(stage = "WATCH", score = 55, price = 100.0))
        assertEquals(
            AgentLiveSignalPusher.Decision.SKIP,
            AgentLiveSignalPusher.evaluate(fp, fp, nowMs = 10_000L, lastEmitMs = 1_000L)
        )
        assertEquals(
            AgentLiveSignalPusher.Decision.EMIT_DEBOUNCED,
            AgentLiveSignalPusher.evaluate(
                fp,
                fp,
                nowMs = 1_000L + AgentLiveSignalPusher.HEARTBEAT_MS,
                lastEmitMs = 1_000L
            )
        )
    }
}
