package agu.analys.util

import agu.analys.config.MarketDataSource
import agu.analys.model.MarketTick
import agu.analys.service.IndodaxMarketService
import agu.analys.service.TokocryptoMarketService
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sumber tunggal (SSOT) untuk rate konversi USDT <-> IDR.
 *
 * Nilai rate **selalu** diambil dari data market exchange yang sedang aktif:
 * 1. Langsung dari ticker pair `USDTIDR` (Tokocrypto) / `usdt_idr` (Indodax).
 * 2. Turunan dari ticker yang sudah ter-streaming (dashboard ticks / current tick).
 * 3. Turunan silang BTCIDR / BTCUSDT bila ticker USDT/IDR tidak tersedia.
 * 4. Cache terakhir yang tersimpan di SharedPreferences (hasil fetch exchange sebelumnya).
 *
 * Tidak ada satupun nilai rate yang di-hardcode. Jika belum pernah ada data exchange,
 * [usdtIdrRate] bernilai `0.0` yang berarti "rate belum tersedia" dan UI wajib
 * menampilkan indikator "menunggu rate" alih-alih memakai tebakan.
 */
object ExchangeRateManager {

    private const val PREFS_NAME = "exchange_rate_prefs"
    private const val KEY_RATE = "usdt_idr_rate"
    private const val KEY_RATE_AT = "usdt_idr_rate_at"
    private const val REFRESH_INTERVAL_MS = 60_000L
    private const val MIN_RETRY_INTERVAL_MS = 15_000L

    /** Simbol pair USDT/IDR di masing-masing exchange. */
    private const val TOKO_USDT_IDR = "USDTIDR"
    private const val INDO_USDT_IDR = "usdt_idr"

    private val _usdtIdrRate = MutableStateFlow(0.0)
    val usdtIdrRate: StateFlow<Double> = _usdtIdrRate.asStateFlow()

    private val _lastUpdatedAt = MutableStateFlow(0L)
    val lastUpdatedAt: StateFlow<Long> = _lastUpdatedAt.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val fetchMutex = Mutex()
    private var appContext: Context? = null
    private var refreshJob: Job? = null
    private var lastAttemptAt = 0L

    /** Rate snapshot sinkron — dipakai oleh jalur non-suspend (order engine, formatter). */
    fun currentRate(): Double = _usdtIdrRate.value

    fun isRateAvailable(): Boolean = _usdtIdrRate.value > 0.0

    fun lastUpdateMillis(): Long = _lastUpdatedAt.value

    // ═════════════════════════════════════════════════════════════════════
    // Konversi
    // ═════════════════════════════════════════════════════════════════════

    /** Konversi nominal USDT -> IDR. Mengembalikan null bila rate belum tersedia. */
    fun usdtToIdr(amountUsdt: Double): Double? {
        val rate = _usdtIdrRate.value
        if (rate <= 0.0) return null
        return amountUsdt * rate
    }

    /** Konversi nominal IDR -> USDT. Mengembalikan null bila rate belum tersedia. */
    fun idrToUsdt(amountIdr: Double): Double? {
        val rate = _usdtIdrRate.value
        if (rate <= 0.0) return null
        return amountIdr / rate
    }

    /** Konversi harga/value dari mata uang kuotasi pair ke Rupiah (IDR pair = nilai apa adanya). */
    fun toIdr(amount: Double, quoteAsset: String): Double? {
        if (!PriceFormatter.isUsdtQuote(quoteAsset)) return amount
        return usdtToIdr(amount)
    }

    // ═════════════════════════════════════════════════════════════════════
    // Init & lifecycle
    // ═════════════════════════════════════════════════════════════════════

    fun init(context: Context) {
        appContext = context.applicationContext
        val cached = readCachedRate()
        if (cached.first > 0.0) {
            _usdtIdrRate.value = cached.first
            _lastUpdatedAt.value = cached.second
        }
    }

    /**
     * Menjalankan loop refresh rate periodik di scope yang diberikan.
     * Aman dipanggil berulang kali — job lama akan dibatalkan.
     */
    fun startAutoRefresh(scope: CoroutineScope, sourceProvider: () -> MarketDataSource) {
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { refresh(sourceProvider()) }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    // ═════════════════════════════════════════════════════════════════════
    // Fetch
    // ═════════════════════════════════════════════════════════════════════

    /** Ambil rate terbaru dari exchange aktif. Mengembalikan rate (>0) atau 0.0 bila gagal. */
    suspend fun refresh(source: MarketDataSource): Double = fetchMutex.withLock {
        val now = System.currentTimeMillis()
        // Hindari spam saat banyak pemanggil meminta refresh bersamaan.
        if (now - lastAttemptAt < MIN_RETRY_INTERVAL_MS && _usdtIdrRate.value > 0.0) {
            return@withLock _usdtIdrRate.value
        }
        lastAttemptAt = now
        _isRefreshing.value = true
        return@withLock try {
            val fetched = fetchRateFromExchange(source)
            if (fetched > 0.0) {
                applyRate(fetched, source.label)
            } else {
                AppLogManager.warn(
                    "ExchangeRate",
                    "⚠️ Rate USDT/IDR gagal diambil dari ${source.label}. Cache dipertahankan: ${_usdtIdrRate.value}"
                )
            }
            _usdtIdrRate.value
        } finally {
            _isRefreshing.value = false
        }
    }

    private suspend fun fetchRateFromExchange(source: MarketDataSource): Double {
        val tick = if (source == MarketDataSource.INDODAX) {
            IndodaxMarketService.fetchTicker(INDO_USDT_IDR)
        } else {
            TokocryptoMarketService.fetchTicker(TOKO_USDT_IDR)
        }
        val direct = tick?.price?.takeIf { it > 0.0 }
        if (direct != null) return direct

        // Fallback: derivasi BTC/IDR dibagi BTC/USDT dari exchange aktif saja dibagi BTC/USDT dari exchange aktif.
        return deriveFromBtc(source)
    }

    private suspend fun deriveFromBtc(source: MarketDataSource): Double {
        return try {
            val (idrSymbol, usdtSymbol) = if (source == MarketDataSource.INDODAX) {
                "btc_idr" to null
            } else {
                "BTCIDR" to "BTCUSDT"
            }
            val btcIdr = if (source == MarketDataSource.INDODAX) {
                IndodaxMarketService.fetchTicker(idrSymbol)?.price ?: 0.0
            } else {
                TokocryptoMarketService.fetchTicker(idrSymbol)?.price ?: 0.0
            }
            if (btcIdr <= 0.0) return 0.0
            val btcUsdt = if (usdtSymbol == null) {
                IndodaxMarketService.fetchTicker("btc_usdt")?.price ?: 0.0
            } else {
                TokocryptoMarketService.fetchTicker(usdtSymbol)?.price ?: 0.0
            }
            if (btcUsdt <= 0.0) 0.0 else btcIdr / btcUsdt
        } catch (_: Exception) {
            0.0
        }
    }

    private fun applyRate(rate: Double, sourceLabel: String) {
        val previous = _usdtIdrRate.value
        _usdtIdrRate.value = rate
        _lastUpdatedAt.value = System.currentTimeMillis()
        writeCachedRate(rate, _lastUpdatedAt.value)
        if (previous <= 0.0 || kotlin.math.abs(previous - rate) / previous > 0.0005) {
            AppLogManager.market(
                "ExchangeRate",
                "💱 Rate USDT/IDR dari $sourceLabel: Rp ${PriceFormatter.formatIdrNumber(rate)} / USDT"
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Sinkronisasi pasif dari stream market yang sudah berjalan
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Update rate dari peta tick yang sudah di-fetch oleh dashboard/detail stream.
     * Ini murni data exchange (harga terakhir pair USDT/IDR), hanya menghemat 1 request.
     */
    fun updateFromTicks(ticks: Map<String, MarketTick>?, currentTick: MarketTick? = null) {
        if (ticks.isNullOrEmpty() && currentTick == null) return
        val candidates = listOf("USDTIDR", "usdt_idr", "USDT_IDR", "USDT_IDR".uppercase())
        val direct = candidates.firstNotNullOfOrNull { key ->
            ticks?.get(key)?.price?.takeIf { it > 0.0 }
        } ?: currentTick?.price?.takeIf {
            it > 0.0 && (currentTick.symbol.equals("USDTIDR", true) || currentTick.symbol.equals("usdt_idr", true))
        }
        if (direct != null && direct > 0.0) {
            applyRate(direct, "stream market")
            return
        }
        val btcIdr = ticks?.get("BTCIDR")?.price ?: ticks?.get("btc_idr")?.price ?: 0.0
        val btcUsdt = ticks?.get("BTCUSDT")?.price ?: 0.0
        if (btcIdr > 0.0 && btcUsdt > 0.0) {
            applyRate(btcIdr / btcUsdt, "stream market (silang BTC)")
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Cache persistensi (hasil fetch exchange, bukan konstanta)
    // ═════════════════════════════════════════════════════════════════════

    private fun readCachedRate(): Pair<Double, Long> {
        val ctx = appContext ?: return 0.0 to 0L
        return try {
            val p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            p.getFloat(KEY_RATE, 0f).toDouble() to p.getLong(KEY_RATE_AT, 0L)
        } catch (_: Exception) {
            0.0 to 0L
        }
    }

    private fun writeCachedRate(rate: Double, at: Long) {
        val ctx = appContext ?: return
        try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_RATE, rate.toFloat())
                .putLong(KEY_RATE_AT, at)
                .apply()
        } catch (_: Exception) { }
    }
}