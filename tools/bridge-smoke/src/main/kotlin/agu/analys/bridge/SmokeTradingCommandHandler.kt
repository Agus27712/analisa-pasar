package agu.analys.bridge

import android.content.Context

/**
 * Stub smoke untuk `AgentTradingCommandHandler` produksi.
 *
 * Handler asli menarik coordinator simulasi/uang riil, mesin eksekusi, dan jurnal —
 * semuanya tidak bisa jalan di JVM smoke. Yang diuji di smoke **bukan** eksekusi order,
 * melainkan kontrak HTTP: rute ada, token 401 bekerja, dan payload ter-serialisasi dengan
 * bentuk yang sama dengan produksi.
 *
 * Berkas DTO produksi (`AgentTradingModels.kt`) disalin apa adanya oleh `copyRealSources`,
 * jadi bentuk JSON yang diuji identik dengan aplikasi.
 *
 * Semua angka di sini fixture, bukan data bursa.
 */
object AgentTradingCommandHandler {

    fun buildPortfolioSnapshot(context: Context): AgentPortfolioOutput? = AgentPortfolioOutput(
        ts = 1L,
        exchange = "TOKOCRYPTO",
        totalIdrValue = 0.0,
        totalUsdtValue = 0.0,
        assets = emptyList()
    )

    fun getTradingStatus(context: Context): AgentTradingStatusResponse = AgentTradingStatusResponse(
        isBridgeRunning = true,
        host = "127.0.0.1",
        port = 8765,
        exchange = "TOKOCRYPTO",
        isRealBuyEnabled = false,
        isPinAuthenticated = false,
        isSimulationAvailable = true
    )

    suspend fun handleBuy(req: AgentTradeRequest, context: Context): AgentTradeResponse =
        AgentTradeResponse(
            success = false,
            message = "SMOKE_FIXTURE: stub handler order.",
            symbol = req.symbol,
            side = req.side,
            status = "REJECTED",
            isSimulation = req.isSimulation
        )

    suspend fun handleSell(req: AgentTradeRequest, context: Context): AgentTradeResponse =
        AgentTradeResponse(
            success = false,
            message = "SMOKE_FIXTURE: stub handler order.",
            symbol = req.symbol,
            side = req.side,
            status = "REJECTED",
            isSimulation = req.isSimulation
        )

    suspend fun handleCancel(req: AgentCancelRequest, context: Context): AgentCancelResponse =
        AgentCancelResponse(
            success = false,
            message = "SMOKE_FIXTURE: stub handler pembatalan.",
            orderId = req.orderId,
            symbol = req.symbol,
            isSimulation = req.isSimulation
        )

    suspend fun handleGetOrder(
        orderId: String,
        symbol: String?,
        isSimulation: Boolean,
        exchange: String,
        context: Context
    ): AgentOrderStatusResponse = AgentOrderStatusResponse(
        success = false,
        message = "SMOKE_FIXTURE: stub handler status order.",
        orderId = orderId,
        symbol = symbol.orEmpty(),
        isSimulation = isSimulation
    )
}
