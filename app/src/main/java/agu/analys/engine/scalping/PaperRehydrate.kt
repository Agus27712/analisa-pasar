package agu.analys.engine.scalping

/**
 * Keputusan rehidrasi untuk **satu** posisi paper setelah app restart.
 *
 * Lihat [PaperRehydrate.decide] untuk aturan yang dipakai.
 */
sealed interface PaperRehydrateAction {

    /** Bangun ulang state machine exit dari parameter yang tersimpan. */
    data class Restore(val machine: PaperExitStateMachine) : PaperRehydrateAction

    /**
     * Serahkan kembali kepemilikan exit ke jalur auto-sell lama
     * (`checkTrailingForMode` → TP1/TP2/SL dari `positionStore`).
     */
    data class HandBackToLegacy(val reason: String) : PaperRehydrateAction

    /** Lewati posisi ini — tidak ada yang bisa dipulihkan dengan aman. */
    data class Skip(val reason: String) : PaperRehydrateAction
}

/**
 * Aturan rehidrasi state machine exit paper (P2).
 *
 * ## Kenapa ini ada
 *
 * [PaperAutoTradeRuntime] menyimpan state machine **hanya di memori**
 * (`ConcurrentHashMap`), sedangkan posisinya persisten di `SharedPreferences`
 * (`SpotPositionStore`). Saat engine P2 mengambil alih exit, jalur auto-sell lama
 * sengaja dimatikan (`auto_sell_enabled = false`, juga persisten). Jadi setelah app
 * di-kill:
 *
 * ```
 * machines (memori)     -> kosong        -> engine paper tidak jalan
 * auto_sell_enabled     -> false         -> jalur lama juga tidak jalan
 * posisi di prefs       -> masih HOLDING -> posisi MENGGANTUNG
 * ```
 *
 * ## Kenapa tidak cukup membaca `positionStore` saja
 *
 * `SpotPositionStore.setHolding` menulis SL sintetis `entry * 0.99` (≈1%) karena
 * `markBought` tidak menerima SL. `setAutoSellParams` versi lama juga tidak menulis SL.
 * Kalau rehidrasi memakai nilai itu apa adanya, mesin akan dibangun dengan SL 1%
 * padahal SL asli engine minimal sebesar lantai friksi (≥ 5,8% untuk USDT Tokocrypto)
 * — artinya mesin akan **memalsukan stop-out** dan mengotori jurnal.
 *
 * Karena itu [decide] menolak SL yang tidak masuk akal ([HandBackToLegacy]) daripada
 * memegang posisi dengan risiko yang salah.
 *
 * ## Batas yang diterima
 *
 * `barsHeld`, `isBeArmed`, `isPartialDone`, dan `peak` **tidak** dipulihkan (tidak
 * dipersistenkan). Posisi yang sudah bergerak ke BE/trailing akan dievaluasi ulang dari
 * awal. Untuk paper trade ini diterima dan dicatat sebagai batasan.
 */
object PaperRehydrate {

    const val REASON_NO_ENTRY = "Harga entry tidak valid"
    const val REASON_AUTO_EXIT_OFF = "Auto-exit paper dimatikan"
    const val REASON_INVALID_SL = "SL tersimpan tidak valid (harus 0 < SL < entry)"

    /**
     * Tentukan tindakan untuk satu posisi paper yang tersimpan.
     *
     * @param entry harga masuk posisi (dari `positionStore`)
     * @param stopLoss SL tersimpan; ditulis oleh `setAutoSellParams(stopLossPrice = ...)`
     * @param tp1 target profit 1 (0 = belum ada)
     * @param tp2 target profit 2 (0 = belum ada)
     * @param autoExitEnabled apakah auto-exit paper masih aktif di Pengaturan
     * @param buyFeePct biaya beli taker (%) untuk pair ini
     * @param sellFeePct biaya jual taker (%) untuk pair ini
     * @param slippagePct asumsi slippage (%) dari runtime config
     * @param params parameter exit aktif ([ScalpingRuntimeConfig.exit])
     */
    fun decide(
        entry: Double,
        stopLoss: Double,
        tp1: Double,
        tp2: Double,
        autoExitEnabled: Boolean,
        buyFeePct: Double,
        sellFeePct: Double,
        slippagePct: Double,
        params: ScalpExitParams
    ): PaperRehydrateAction {
        if (entry <= 0.0 || !entry.isFinite()) {
            return PaperRehydrateAction.Skip(REASON_NO_ENTRY)
        }
        if (!autoExitEnabled) {
            return PaperRehydrateAction.HandBackToLegacy(REASON_AUTO_EXIT_OFF)
        }
        if (stopLoss <= 0.0 || !stopLoss.isFinite() || stopLoss >= entry) {
            return PaperRehydrateAction.HandBackToLegacy(REASON_INVALID_SL)
        }

        return PaperRehydrateAction.Restore(
            PaperExitStateMachine(
                entry = entry,
                stopLoss = stopLoss,
                tp1 = if (tp1 > 0.0) tp1 else entry,
                tp2 = if (tp2 > 0.0) tp2 else entry * 1.02,
                buyFeePct = buyFeePct,
                sellFeePct = sellFeePct,
                slippagePct = slippagePct,
                params = params
            )
        )
    }
}
