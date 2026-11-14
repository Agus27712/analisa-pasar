package agu.analys.bridge

import android.content.Context
import agu.analys.config.MarketDataSource
import agu.analys.config.StrategyMode
import agu.analys.engine.ModeSignalEvaluator
import agu.analys.engine.ModeSignalInput
import agu.analys.model.CandleBar
import agu.analys.model.OrderBookItem
import agu.analys.model.Timeframe
import agu.analys.service.IndodaxMarketService
import agu.analys.service.TokocryptoMarketService
import agu.analys.util.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sumber angka analisis engine untuk Agent Bridge.
 *
 * Diteri port dari repo `agentfeed` (commit `51f9c39` — "feat: add engine analysis support"),
 * lalu disesuaikan dengan repo lokal:
 * - bursa **bisa dipilih** (`TOKOCRYPTO` / `INDODAX`), default = bursa aktif pengguna;
 * - tarif diambil dari `AppPreferences.feeConfigFor(symbol, exchange)` sehingga tarif kustom
 *   user dipakai dan pair IDR tidak salah dinilai dengan tarif USDT (aturan FEE_TRUTH);
 * - candle H1/H4 memakai `dropLast(1)` (bar yang masih berjalan dibuang) sesuai kontrak
 *   `ModeSignalInput` lokal; M15/M5/M1 boleh memuat bar berjalan karena evaluator menyintesis
 *   sendiri dari tick;
 * - mode dievaluasi lewat `ModeSignalEvaluator` — **satu-satunya** pemetaan
 *   `StrategyMode → evaluator` (aturan N6/N17), bukan evaluator yang dirakit sendiri.
 *
 * Angka keluar apa adanya dari mesin; tidak ada perhitungan ulang di berkas ini.
 */
object AgentEngineAnalysisProvider {

    /** Batas waktu satu permintaan ke bursa. Habis → mode dianggap "data kurang". */
    private const val FETCH_TIMEOUT_MS = 15_000L

    /** Jumlah candle per timeframe. Minimum engine scalping = 20 bar. */
    private const val CANDLE_LIMIT = 60

    /**
     * `AppPreferences` dibangun **satu kali** per proses.
     *
     * Konstruknya membangun `EncryptedSharedPreferences` + `MasterKey` (lihat aturan
     * "AppPreferences tidak boleh dibangun di jalur per-tick"), jadi tidak boleh dibuat
     * ulang tiap permintaan agent.
     */
    @Volatile
    private var prefsCache: AppPreferences? = null

    /**
     * Analisis lengkap satu pair dari tiga engine (scalping, intraday, swing).
     *
     * @param symbol simbol pair apa adanya (`BTCUSDT`, `BTC/USDT`, `btc_idr`, ...).
     * @param exchange nama bursa (`TOKOCRYPTO` / `INDODAX`); `null` = bursa aktif pengguna.
     */
    suspend fun engineAnalysis(
        context: Context,
        symbol: String,
        exchange: String? = null
    ): AgentEngineAnalysis {
        val norm = AgentEngineAnalysisMapper.normalizeSymbol(symbol)
            ?: return AgentEngineAnalysis(
                symbol = symbol,
                available = false,
                error = "Simbol '$symbol' tidak dikenali. Format yang diterima: BTCUSDT atau BTCIDR."
            )

        val prefs = prefsFor(context)
        val source = if (exchange.isNullOrBlank()) {
            prefs.marketDataSource
        } else {
            val parsed = runCatching { MarketDataSource.valueOf(exchange.trim().uppercase()) }.getOrNull()
            if (parsed == null) {
                return AgentEngineAnalysis(
                    symbol = norm,
                    available = false,
                    exchange = exchange.trim().uppercase(),
                    error = "Bursa '$exchange' tidak dikenal. Pilihan: TOKOCRYPTO atau INDODAX."
                )
            }
            parsed
        }

        val quote = AgentEngineAnalysisMapper.quoteOf(norm)
        // Batas pasar: Indodax hanya IDR. Tolak, jangan diam-diam mengganti ke pair lain.
        if (source == MarketDataSource.INDODAX && quote != "IDR") {
            return AgentEngineAnalysis(
                symbol = norm,
                available = false,
                exchange = source.name,
                error = "Indodax hanya melayani pair IDR; '$norm' tidak bisa dianalisis di bursa ini."
            )
        }

        return evaluateWithKnownTick(context, norm, source, prefetchedTick = null)
    }

    /**
     * Evaluasi tiga mode untuk simbol & bursa yang sudah tervalidasi.
     * Dipakai bersama oleh [engineAnalysis] dan pemindai pasar (`AgentMarketScanProvider`)
     * agar kandidat pemindaian memakai pipeline `ModeSignalEvaluator` + `FEE_TRUTH` yang sama persis.
     */
    internal suspend fun evaluateWithKnownTick(
        context: Context,
        norm: String,
        source: MarketDataSource,
        prefetchedTick: agu.analys.model.MarketTick? = null
    ): AgentEngineAnalysis {
        val prefs = prefsFor(context)
        return try {
            coroutineScope {
                val tickerJob = if (prefetchedTick != null) null else async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetchTicker(source, norm) }
                }
                val h1Job = async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetchCandles(source, norm, Timeframe.H1) }.orEmpty()
                }
                val h4Job = async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetchCandles(source, norm, Timeframe.H4) }.orEmpty()
                }
                val d1Job = async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetchCandles(source, norm, Timeframe.D1) }.orEmpty()
                }
                val m15Job = async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetchCandles(source, norm, Timeframe.M15) }.orEmpty()
                }
                val m5Job = async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetchCandles(source, norm, Timeframe.M5) }.orEmpty()
                }
                val m1Job = async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetchCandles(source, norm, Timeframe.M1) }.orEmpty()
                }
                val bookJob = async {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { fetchOrderBook(source, norm) }
                }

                val tick = prefetchedTick ?: tickerJob?.await()
                val price = tick?.price ?: 0.0
                if (price <= 0.0) {
                    return@coroutineScope AgentEngineAnalysis(
                        symbol = norm,
                        available = false,
                        exchange = source.name,
                        error = "Ticker atau harga $norm belum tersedia dari bursa."
                    )
                }

                val h1 = h1Job.await()
                val h4 = h4Job.await()
                val d1 = d1Job.await()
                val m15 = m15Job.await()
                val m5 = m5Job.await()
                val m1 = m1Job.await()
                val (bids, asks) = bookJob.await() ?: (emptyList<OrderBookItem>() to emptyList())

                val nowMs = System.currentTimeMillis()
                val fees = prefs.feeConfigFor(norm, source.name)

                val baseInput = ModeSignalInput(
                    mode = StrategyMode.SCALPING,
                    symbol = norm,
                    price = price,
                    nowMs = nowMs,
                    change24h = tick?.change24h ?: Double.NaN,
                    // H1/H4: bar yang masih berjalan dibuang (kontrak ModeSignalInput).
                    h1Candles = h1.dropLast(1),
                    h4Candles = h4.dropLast(1),
                    d1Candles = d1,
                    m15Candles = m15,
                    m5Candles = m5,
                    m1Candles = m1,
                    bids = bids,
                    asks = asks,
                    fees = fees
                )

                val scalp = aman { ModeSignalEvaluator.evaluate(baseInput.copy(mode = StrategyMode.SCALPING)) }
                val intraday = aman { ModeSignalEvaluator.evaluate(baseInput.copy(mode = StrategyMode.OFFICE_DAILY)) }
                val swing = aman { ModeSignalEvaluator.evaluate(baseInput.copy(mode = StrategyMode.SWING)) }

                val scalpDto = scalp?.let { AgentEngineAnalysisMapper.scalpingOf(it.signal, price) }
                val intradayDto = intraday?.let { AgentEngineAnalysisMapper.intradayOf(it.signal) }
                val swingDto = swing?.let { AgentEngineAnalysisMapper.swingOf(it.signal, it.indicators) }
                val indicators = (scalp?.indicators ?: intraday?.indicators ?: swing?.indicators)
                    ?.let { AgentEngineAnalysisMapper.indicatorsOf(it) }

                val bias = AgentEngineAnalysisMapper.overallBias(
                    listOfNotNull(scalpDto?.action, intradayDto?.action, swingDto?.action)
                )

                AgentEngineAnalysis(
                    symbol = norm,
                    available = true,
                    source = "ENGINE_EVALUATOR",
                    exchange = source.name,
                    price = price,
                    scalping = scalpDto,
                    intraday = intradayDto,
                    swing = swingDto,
                    indicators = indicators,
                    overallBias = bias,
                    ts = nowMs
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            AgentEngineAnalysis(
                symbol = norm,
                available = false,
                exchange = source.name,
                error = "Analisis engine gagal: ${t.javaClass.simpleName}: ${t.message ?: "tanpa pesan"}"
            )
        }
    }

    /** Satu instance preferensi per proses; lihat catatan di atas. */
    private fun prefsFor(context: Context): AppPreferences {
        prefsCache?.let { return it }
        return synchronized(this) {
            prefsCache ?: AppPreferences(context.applicationContext).also { prefsCache = it }
        }
    }

    private suspend fun fetchTicker(source: MarketDataSource, symbol: String) = when (source) {
        MarketDataSource.TOKOCRYPTO -> TokocryptoMarketService.fetchTicker(symbol)
        MarketDataSource.INDODAX -> IndodaxMarketService.fetchTicker(symbol)
    }

    private suspend fun fetchCandles(
        source: MarketDataSource,
        symbol: String,
        timeframe: Timeframe
    ): List<CandleBar> = when (source) {
        MarketDataSource.TOKOCRYPTO -> TokocryptoMarketService.fetchCandles(symbol, timeframe, CANDLE_LIMIT)
        MarketDataSource.INDODAX -> IndodaxMarketService.fetchCandles(symbol, timeframe, CANDLE_LIMIT)
    }

    private suspend fun fetchOrderBook(
        source: MarketDataSource,
        symbol: String
    ): Pair<List<OrderBookItem>, List<OrderBookItem>> = when (source) {
        MarketDataSource.TOKOCRYPTO -> TokocryptoMarketService.fetchOrderBook(symbol, 20)
        MarketDataSource.INDODAX -> IndodaxMarketService.fetchOrderBook(symbol, 20)
    }

    /**
     * Jalankan evaluasi tanpa menelan pembatalan koroutine.
     *
     * Kegagalan satu mode **tidak** boleh menggagalkan mode lain: mode yang gagal berarti
     * "data belum cukup", bukan "permintaan gagal".
     */
    private inline fun <T> aman(block: () -> T): T? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        null
    }
}
