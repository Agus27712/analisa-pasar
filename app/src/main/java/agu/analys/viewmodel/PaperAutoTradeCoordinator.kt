package agu.analys.viewmodel

import agu.analys.engine.scalping.PaperExitDecision
import agu.analys.engine.scalping.PaperExitPhase
import agu.analys.engine.scalping.PaperExitReason
import agu.analys.engine.scalping.PaperExitStateMachine
import agu.analys.engine.scalping.ScalpExitParams
import agu.analys.engine.scalping.SignalTransition
import agu.analys.model.CandleBar
import agu.analys.model.SignalAction
import agu.analys.model.TradingPair
import agu.analys.trading.SimulationOrderResult
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import agu.analys.util.AppLogManager
import agu.analys.util.PriceFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Auto-buy & auto-exit **paper trade** (P2) — decision support, bukan bot profit.
 *
 * Batas keras yang dipegang file ini:
 * - Hanya menyentuh posisi **simulasi** (`isReal = false`). Tidak ada jalur ke eksekusi
 *   uang riil dari sinyal scalp (larangan `workplan2.md` §P2 dan `AGENTS.md`).
 * - Ukuran posisi dihitung lewat [agu.analys.engine.scalping.PaperAutoTradeConfig],
 *   tidak pernah melebihi daya beli saldo kas simulasi.
 * - Exit memakai parameter `exit` dari [agu.analys.engine.scalping.ScalpingRuntimeConfig],
 *   yang sama dengan yang diekspor ke `tools/scalp_replay.py`.
 */
object PaperAutoTradeRuntime {
    /** State machine exit per `exchange|symbol` untuk posisi paper yang sedang berjalan. */
    private val machines = ConcurrentHashMap<String, PaperExitStateMachine>()

    fun key(exchange: String, symbol: String): String =
        "${exchange.uppercase()}|${symbol.uppercase().replace("_", "")}"

    fun machineFor(exchange: String, symbol: String): PaperExitStateMachine? =
        machines[key(exchange, symbol)]

    fun phaseFor(exchange: String, symbol: String): PaperExitPhase? =
        machines[key(exchange, symbol)]?.phase

    fun put(exchange: String, symbol: String, machine: PaperExitStateMachine) {
        machines[key(exchange, symbol)] = machine
    }

    fun clear(exchange: String, symbol: String) {
        machines.remove(key(exchange, symbol))
    }

    fun clearAll() = machines.clear()
}

/**
 * Buka posisi **simulasi** otomatis saat sinyal scalp terkonfirmasi.
 *
 * Syarat (semua harus terpenuhi):
 * 1. toggle auto-buy paper aktif di Pengaturan,
 * 2. aksi sinyal `BUY`,
 * 3. `scalpingStage` memenuhi ambang minimum yang dipilih,
 * 4. belum ada posisi simulasi terbuka untuk pair tersebut,
 * 5. kuantitas hasil perhitungan > 0.
 *
 * @return `true` bila order simulasi berhasil dikirim.
 */
fun TradingViewModel.tryPaperAutoBuy(transition: SignalTransition, exchange: String): Boolean {
    val cfg = prefs.paperAutoTradeConfig
    if (!cfg.autoBuyEnabled) return false

    val signal = transition.signal
    if (signal.action != SignalAction.BUY) return false
    if (!cfg.stageQualifies(signal.scalpingStage)) return false

    val price = signal.entryPrice
    if (price <= 0.0) return false

    val pair = TradingPair.fromCustomSymbol(raw = transition.symbol, exchange = exchange)
    val existing = positionStore.get(pair.symbol, isReal = false, exchange = exchange)
    if (existing.isHolding) return false

    val available = simCoordinator.wallet.value.getAvailableQuote(pair.quoteAsset)
    val qty = cfg.quantityFor(available, price, signal.stopLoss)
    if (qty <= 0.0) {
        AppLogManager.service(
            "PaperAutoBuy",
            "⏭️ [${pair.symbol}] Auto-buy dilewati: kuantitas 0 (kas ${pair.quoteAsset} tersedia ${PriceFormatter.formatPrice(available, quoteAsset = pair.quoteAsset)})."
        )
        return false
    }

    val res = simCoordinator.submitOrder(
        pair = pair,
        currentPrice = price,
        side = SimulationOrderSide.BUY,
        type = SimulationOrderType.MARKET,
        price = price,
        stopPrice = 0.0,
        quantity = qty,
        strategyMode = transition.mode.name,
        entryPrice = price,
        entryTimestamp = System.currentTimeMillis()
    )

    if (res !is SimulationOrderResult.Success) {
        val msg = (res as? SimulationOrderResult.Error)?.message ?: "tidak diketahui"
        AppLogManager.service("PaperAutoBuy", "❌ [${pair.symbol}] Auto-buy simulasi gagal: $msg")
        return false
    }

    val exchangeKey = exchange.uppercase()
    // Daftarkan posisi simulasi + parameter auto-sell (SL/TP dari sinyal).
    positionStore.markBought(
        symbol = pair.symbol,
        entryPrice = price,
        invested = qty * price,
        quantity = qty,
        isReal = false,
        exchange = exchangeKey
    )

    val tp1Percent = 50.0
    positionStore.setAutoSellParams(
        symbol = pair.symbol,
        enabled = true,
        tp1Price = signal.targetPrice1,
        tp1Percent = tp1Percent,
        tp2Price = signal.targetPrice2,
        tp2Percent = 100.0 - tp1Percent,
        isReal = false,
        exchange = exchangeKey
    )

    // Siapkan state machine exit sesuai mode yang dipilih di runtime config.
    if (cfg.autoExitEnabled && signal.stopLoss > 0.0 && signal.stopLoss < price) {
        val runtime = prefs.scalpingRuntimeConfig
        val fees = prefs.tradingFees
        val machine = PaperExitStateMachine(
            entry = price,
            stopLoss = signal.stopLoss,
            tp1 = if (signal.targetPrice1 > 0.0) signal.targetPrice1 else price,
            tp2 = if (signal.targetPrice2 > 0.0) signal.targetPrice2 else price * 1.02,
            buyFeePct = fees.buyTakerPct,
            sellFeePct = fees.sellTakerPct,
            slippagePct = runtime.slippagePct,
            params = runtime.exit
        )
        PaperAutoTradeRuntime.put(exchangeKey, pair.symbol, machine)
    }

    positionCoordinator.refreshPosition(pair.symbol)

    val modeLabel = prefs.scalpingRuntimeConfig.exit.mode.label
    AppLogManager.trade(
        "PaperAutoBuy",
        "🤖 [${pair.symbol}] Auto-buy SIMULASI ${String.format(java.util.Locale.US, "%.8f", qty)} ${pair.baseAsset} @ " +
            "${PriceFormatter.formatPrice(price, quoteAsset = pair.quoteAsset)} | Stage ${signal.scalpingStage.name} | Exit: $modeLabel"
    )

    return true
}

/**
 * Evaluasi exit paper untuk satu bar/tick pada posisi simulasi yang sedang berjalan.
 *
 * @param currentPrice harga terakhir.
 * @param window bar terakhir (maks 15) untuk uji momentum early profit; boleh kosong.
 * @return keputusan exit bila posisi harus ditutup, atau `null`.
 */
fun TradingViewModel.evaluatePaperAutoExit(
    symbol: String,
    exchange: String,
    currentPrice: Double,
    window: List<CandleBar> = emptyList()
): PaperExitDecision? {
    val cfg = prefs.paperAutoTradeConfig
    if (!cfg.autoExitEnabled) return null
    if (currentPrice <= 0.0) return null

    val machine = PaperAutoTradeRuntime.machineFor(exchange, symbol) ?: return null

    val now = System.currentTimeMillis()
    val bar = CandleBar(
        timestamp = now,
        open = currentPrice,
        high = currentPrice,
        low = currentPrice,
        close = currentPrice,
        volume = 0.0
    )
    val effectiveWindow = if (window.isNotEmpty()) window else listOf(bar)
    return machine.onBar(bar, effectiveWindow)
}

/** Hapus state machine exit setelah posisi paper ditutup. */
fun TradingViewModel.clearPaperExitState(symbol: String, exchange: String) {
    PaperAutoTradeRuntime.clear(exchange, symbol)
}

/** Ringkas parameter exit yang sedang aktif, untuk ditampilkan di UI. */
fun TradingViewModel.currentExitParams(): ScalpExitParams = prefs.scalpingRuntimeConfig.exit

/** Label alasan exit untuk notifikasi/jurnal. */
fun PaperExitReason.displayLabel(): String = when (this) {
    PaperExitReason.SL -> "Stop Loss"
    PaperExitReason.BE_SL -> "Breakeven Stop"
    PaperExitReason.TRAIL_SL -> "Trailing Stop"
    PaperExitReason.TP2 -> "Target Profit 2"
    PaperExitReason.EARLY_PROFIT -> "Early Profit"
    PaperExitReason.TIMEOUT -> "Timeout"
    PaperExitReason.BAD_RISK -> "Risiko tidak valid"
}
