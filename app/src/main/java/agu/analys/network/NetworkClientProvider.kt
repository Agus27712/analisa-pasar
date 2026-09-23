package agu.analys.network

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Provider sentral OkHttpClient untuk mengoptimalkan alokasi resource jaringan:
 * - 1 ConnectionPool bersama (mencegah socket leak & menghemat TLS handshake).
 * - 1 Thread pool Dispatcher bersama (mengurangi overhead pembuatan thread per service).
 * - DNS IPv4-first resolver untuk mencegah hanging IPv6 pada ISP seluler.
 * - Variasi konfigurasi timeout & ping interval diturunkan via `.newBuilder()`.
 */
object NetworkClientProvider {

    private val sharedConnectionPool = ConnectionPool(
        maxIdleConnections = 16,
        keepAliveDuration = 5,
        timeUnit = TimeUnit.MINUTES
    )

    private val sharedDispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 16
    }

    private val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            val ipv4 = addresses.filterIsInstance<Inet4Address>()
            return if (ipv4.isNotEmpty()) ipv4 else addresses
        }
    }

    /**
     * Root client induk yang membawa ConnectionPool, Dispatcher, dan DNS bersama.
     */
    val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(sharedConnectionPool)
        .dispatcher(sharedDispatcher)
        .dns(ipv4FirstDns)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Client untuk REST Publik Indodax (Ticker, Depth, Trades, Summary, Candles).
     * Cepat dan responsif dengan timeout 8 detik.
     */
    val marketClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Client untuk Trading API V2 Indodax (Account, Orders, Mutasi, History).
     * Timeout membaca lebih longgar (20s) untuk memastikan order tercatat dengan aman.
     */
    val tradeClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Client untuk WebSocket Realtime Indodax.
     * readTimeout = 0 (infinite stream) dengan ping interval 15s.
     */
    val webSocketClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Client untuk Service AI (Gemini, Groq, AI Screener).
     * Timeout baca hingga 60s untuk mengakomodasi inferensi model LLM.
     */
    val aiClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Client untuk Headline Crypto & RSS Feed Agregator.
     */
    val rssClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
