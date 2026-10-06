package agu.analys.util

import agu.analys.config.MarketDataSource
import agu.analys.model.MarketTick
import agu.analys.service.IndodaxMarketService
import agu.analys.service.TokocryptoMarketService

/**
 * Peringkat dashboard yang adil untuk pair IDR dan USDT.
 * Volume USDT dikonversi ke IDR, tidak ada threshold harga absolut untuk USDT.
 */
object DashboardRanking {
    /** Jumlah pair per halaman dashboard (load bertahap saat scroll). */
    const val PAGE_SIZE = 15

    private const val FALLBACK_USDT_IDR = 16000.0

    fun safeRate(rate: Double): Double = if (rate > 1000.0) rate else FALLBACK_USDT_IDR

    fun isUsdtQuote(symbol: String): Boolean =
        symbol.uppercase().replace("_", "").replace("/", "").endsWith("USDT")

    /** Volume 24 jam dalam IDR. Pair USDT dikali kurs, pair IDR apa adanya. */
    fun volumeInIdr(symbol: String, volume24h: Double, usdtIdrRate: Double): Double =
        if (isUsdtQuote(symbol)) volume24h * safeRate(usdtIdrRate) else volume24h

    /** Floor harga hanya untuk quote IDR (harga sangat kecil dalam rupiah = koin mati). */
    fun passesPriceFloor(tick: MarketTick): Boolean =
        tick.price > 0.0 && (isUsdtQuote(tick.symbol) || tick.price > 5.0)

    /** Urut volume 24 jam tertinggi (dinormalisasi ke IDR), satu entri per simbol. */
    fun rankByVolume(
        ticks: Collection<MarketTick>,
        usdtIdrRate: Double,
        accept: (MarketTick) -> Boolean = { true }
    ): List<MarketTick> = ticks
        .asSequence()
        .filter(accept)
        .distinctBy { it.symbol.uppercase().replace("_", "") }
        .sortedByDescending { volumeInIdr(it.symbol, it.volume24h, usdtIdrRate) }
        .toList()

    /** Pair layak tampil di dashboard untuk bursa aktif. */
    fun isRankable(source: MarketDataSource, tick: MarketTick): Boolean {
        val sym = tick.symbol.uppercase().replace("_", "")
        if (tick.price <= 0.0) return false
        return if (source == MarketDataSource.TOKOCRYPTO) {
            TokocryptoMarketService.isIdrOrUsdtPair(sym) &&
                TokocryptoMarketService.isSafeTradableAsset(
                    price = tick.price,
                    volume24h = tick.volume24h,
                    high24h = tick.high24h,
                    low24h = tick.low24h,
                    isIdrPair = sym.endsWith("IDR")
                )
        } else {
            !sym.contains("USDC") && !sym.contains("DAI") &&
                IndodaxMarketService.isSafeTradableAsset(
                    price = tick.price,
                    volume24h = tick.volume24h,
                    high24h = tick.high24h,
                    low24h = tick.low24h,
                    isIdrPair = sym.endsWith("IDR")
                )
        }
    }
}
