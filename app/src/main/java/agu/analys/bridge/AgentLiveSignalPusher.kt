package agu.analys.bridge

import kotlin.math.abs

/**
 * Penjaga pancaran live sinyal engine ke WebSocket (`/ws/signals`) dan SSE (`/events`).
 *
 * Murni Kotlin tanpa dependensi Android agar mudah diuji unit beserta kontrol negatifnya:
 * - **Pancaran instan (`EMIT_IMMEDIATE`)** bila terjadi transisi bermakna: pergantian pasangan/mode,
 *   perubahan `action` (`HOLD` / `BUY` / `SELL`), perubahan `scalpingStage` atau `lifecycleState`,
 *   perubahan `setup`/`regime`/`mtf`, atau lompatan `confidence`/`scalpingScore` >= 2 poin.
 * - **Debounce event-driven (`DEBOUNCE_MS = 1_500L`)** untuk pembaruan halus dalam stage yang
 *   sama (pergeseran harga, skor/confidence ±1, perubahan alasan) agar klien langsung melihat
 *   kondisi terbaru tanpa membanjiri WebSocket saat burst tick 100ms.
 * - **`DEFER_DEBOUNCE`** menandakan isi berubah tetapi masih di dalam jendela 1,5 detik sehingga
 *   pemanggil dapat menjadwalkan flush trailing satu kali saat jendela berakhir.
 */
object AgentLiveSignalPusher {

    /** Jeda debounce event-driven untuk perubahan non-kritis (1,5 detik). */
    const val DEBOUNCE_MS = 1_500L

    /** Batas diam maksimum sebelum mengirim heartbeat kondisi sinyal saat ini (60 detik). */
    const val HEARTBEAT_MS = 60_000L

    /** Perubahan skor/confidence minimum agar langsung ditembak tanpa menunggu debounce. */
    const val MIN_SCORE_DELTA_IMMEDIATE = 2

    /** Perubahan harga relatif minimum (0,05%) agar dianggap pembaruan harga bermakna. */
    const val MIN_PRICE_CHANGE_RATIO = 0.0005

    data class LiveSignalFingerprint(
        val key: String,
        val action: String,
        val scalpingStage: String,
        val lifecycleState: String,
        val confidence: Int,
        val scalpingScore: Int,
        val scalpingSetup: String,
        val scalpingRegime: String,
        val mtfDirection: String,
        val mtfCheckpointsOk: Int,
        val price: Double,
        val tp1: Double,
        val sl: Double,
        val reason: String
    )

    enum class Decision {
        EMIT_IMMEDIATE,
        EMIT_DEBOUNCED,
        DEFER_DEBOUNCE,
        SKIP
    }

    fun fingerprintOf(
        signal: AgentSignalOutput,
        lifecycleState: String = ""
    ): LiveSignalFingerprint {
        val tech = signal.technical
        val normKey = "${signal.exchange.uppercase()}|${signal.symbol.uppercase()}|${signal.mode.uppercase()}"
        return LiveSignalFingerprint(
            key = normKey,
            action = signal.action.uppercase(),
            scalpingStage = (tech?.scalpingStage ?: "").uppercase(),
            lifecycleState = lifecycleState.uppercase(),
            confidence = signal.confidence.toInt(),
            scalpingScore = tech?.scalpingScore ?: -1,
            scalpingSetup = (tech?.scalpingSetup ?: "").uppercase(),
            scalpingRegime = (tech?.scalpingRegime ?: "").uppercase(),
            mtfDirection = (tech?.mtfDirection ?: "").uppercase(),
            mtfCheckpointsOk = tech?.mtfCheckpointsOk ?: 0,
            price = signal.price,
            tp1 = signal.tp1 ?: 0.0,
            sl = signal.sl ?: 0.0,
            reason = signal.reason
        )
    }

    fun isImmediateTransition(
        previous: LiveSignalFingerprint?,
        next: LiveSignalFingerprint
    ): Boolean {
        if (previous == null) return true
        if (previous.key != next.key) return true
        if (previous.action != next.action) return true
        if (previous.scalpingStage != next.scalpingStage) return true
        if (previous.lifecycleState != next.lifecycleState) return true
        if (previous.scalpingSetup != next.scalpingSetup) return true
        if (previous.scalpingRegime != next.scalpingRegime) return true
        if (previous.mtfDirection != next.mtfDirection) return true
        if (previous.mtfCheckpointsOk != next.mtfCheckpointsOk) return true
        if (abs(next.confidence - previous.confidence) >= MIN_SCORE_DELTA_IMMEDIATE) return true
        if (previous.scalpingScore >= 0 && next.scalpingScore >= 0 &&
            abs(next.scalpingScore - previous.scalpingScore) >= MIN_SCORE_DELTA_IMMEDIATE
        ) return true
        return false
    }

    fun hasContentChange(
        previous: LiveSignalFingerprint,
        next: LiveSignalFingerprint
    ): Boolean {
        if (isImmediateTransition(previous, next)) return true
        if (previous.confidence != next.confidence) return true
        if (previous.scalpingScore != next.scalpingScore) return true
        if (previous.reason != next.reason) return true
        if (movedEnough(previous.price, next.price)) return true
        if (movedEnough(previous.tp1, next.tp1)) return true
        if (movedEnough(previous.sl, next.sl)) return true
        return false
    }

    private fun movedEnough(prev: Double, next: Double): Boolean {
        if (!prev.isFinite() || !next.isFinite()) return false
        if (prev <= 0.0 && next <= 0.0) return false
        if (prev <= 0.0 || next <= 0.0) return true
        return abs(next - prev) / prev >= MIN_PRICE_CHANGE_RATIO
    }

    fun evaluate(
        previous: LiveSignalFingerprint?,
        next: LiveSignalFingerprint,
        nowMs: Long,
        lastEmitMs: Long,
        debounceMs: Long = DEBOUNCE_MS,
        heartbeatMs: Long = HEARTBEAT_MS
    ): Decision {
        if (isImmediateTransition(previous, next)) {
            return Decision.EMIT_IMMEDIATE
        }
        val elapsed = nowMs - lastEmitMs
        val changed = previous != null && hasContentChange(previous, next)
        if (changed) {
            return if (elapsed >= debounceMs) {
                Decision.EMIT_DEBOUNCED
            } else {
                Decision.DEFER_DEBOUNCE
            }
        }
        return if (elapsed >= heartbeatMs) {
            Decision.EMIT_DEBOUNCED
        } else {
            Decision.SKIP
        }
    }
}
