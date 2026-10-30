package agu.analys.engine.intraday.replay

import agu.analys.config.TradingFeeConfig
import agu.analys.engine.intraday.IntradayConfig
import agu.analys.engine.intraday.IntradayExitConfig
import agu.analys.model.CandleBar
import java.io.File
import java.util.Locale

/**
 * Perkakas bersama untuk **alat ukur** intraday di `data/tokocrypto_v8` (paket D & E).
 *
 * Kenapa di Kotlin, bukan Python: geometri TP1, gate net R:R, dan mesin exit hidup di Kotlin.
 * Menulis ulang rumusnya di Python berarti mengukur rumus lain, bukan yang dipakai aplikasi.
 *
 * Semua angka yang dihasilkan alat ini berasal dari [IntradayReplayEngine] — jalur yang sama
 * dengan aplikasi. Berkas ini **bukan** penjaga (tidak punya asersi perilaku); penjaganya ada di
 * `IntradayAccountingAndSessionTest` dan `IntradayExitEngineTest`.
 */
object IntradayResearchHarness {

    data class PairData(
        val pair: String,
        val utama: List<CandleBar>,
        val harian: List<CandleBar>
    )

    data class Dataset(
        val nama: String,
        val tf: String,
        val pairs: List<PairData>
    ) {
        val tanpaD1: List<String> get() = pairs.filter { it.harian.isEmpty() }.map { it.pair }
    }

    data class TradeRingkas(
        val pair: String,
        val entryIndex: Int,
        val entryTime: Long,
        val grossPnlPct: Double,
        val netPnlPct: Double,
        val exitReason: String,
        val outcome: IntradayTradeOutcome
    )

    data class PairHasil(
        val pair: String,
        val bar: Int,
        val buyIndices: List<Int>,
        val trades: List<TradeRingkas>
    ) {
        val buy: Int get() = buyIndices.size
        val menang: Int get() = trades.count { it.netPnlPct >= 0.0 }
        val totalNetPct: Double get() = trades.sumOf { it.netPnlPct }
        val avgNetPct: Double get() = if (trades.isEmpty()) 0.0 else totalNetPct / trades.size
        val winRatePct: Double get() = if (trades.isEmpty()) 0.0 else menang.toDouble() / trades.size * 100.0
        fun perAlasan(): Map<String, Int> = trades.groupingBy { it.exitReason }.eachCount().toSortedMap()
    }

    fun env(nama: String, bawaan: String): String =
        System.getenv(nama)?.takeIf { it.isNotBlank() } ?: bawaan

    fun envInt(nama: String, bawaan: Int): Int =
        System.getenv(nama)?.trim()?.toIntOrNull() ?: bawaan

    fun envDouble(nama: String, bawaan: Double): Double =
        System.getenv(nama)?.trim()?.toDoubleOrNull() ?: bawaan

    fun rootRepo(): File {
        val base = File(System.getProperty("user.dir") ?: ".")
        return listOf(base, File(base, "app"), base.parentFile ?: base, File(base.parentFile ?: base, "app"))
            .firstOrNull { File(it, "data/tokocrypto_v8").isDirectory || File(it, "data/tokocrypto_v7").isDirectory }
            ?: error("Root repo (berisi data/tokocrypto_v8) tidak ditemukan dari $base")
    }

    fun jamPerBar(tf: String): Double = when (tf.lowercase(Locale.ROOT)) {
        "1h" -> 1.0
        "4h" -> 4.0
        "1d" -> 24.0
        else -> error("TF tidak didukung alat ini: $tf (pakai 1h/4h/1d)")
    }

    fun lookaheadBar(tf: String, jam: Double): Int =
        maxOf(1, Math.round(jam / jamPerBar(tf)).toInt())

    fun muatCandle(berkas: File): List<CandleBar> =
        berkas.readLines().drop(1).filter { it.isNotBlank() }.map { baris ->
            val kolom = baris.split(",")
            CandleBar(
                timestamp = kolom[0].toLong(),
                open = kolom[1].toDouble(),
                high = kolom[2].toDouble(),
                low = kolom[3].toDouble(),
                close = kolom[4].toDouble(),
                volume = kolom[5].toDouble()
            )
        }

    fun muat(dataset: String, tf: String, filterPair: List<String>, limit: Int): Dataset {
        val dir = File(rootRepo(), dataset)
        require(dir.isDirectory) { "Dataset tidak ditemukan: ${dir.absolutePath}" }
        val berkas = dir.listFiles { f: File -> f.name.endsWith("_$tf.csv") }
            ?.sortedBy { it.name }
            ?: emptyList()
        require(berkas.isNotEmpty()) { "Tidak ada berkas *_$tf.csv di ${dir.absolutePath}" }

        var pair = berkas.map { it.name.removeSuffix("_$tf.csv") }
        if (filterPair.isNotEmpty()) pair = pair.filter { it in filterPair }
        if (limit > 0) pair = pair.take(limit)
        require(pair.isNotEmpty()) { "Tidak ada pair yang lolos filter" }

        val pairs = pair.map { nama ->
            PairData(
                pair = nama,
                utama = muatCandle(File(dir, "${nama}_$tf.csv")),
                harian = File(dir, "${nama}_1d.csv").takeIf { it.isFile }?.let { muatCandle(it) } ?: emptyList()
            )
        }
        return Dataset(nama = dataset, tf = tf, pairs = pairs)
    }

    /**
     * Satu replay untuk satu pair. Trade sebelum [warmup] dibuang karena EMA100/EMA200 belum
     * terisi — di app feed-nya 200 candle, jadi bar awal tidak setara.
     */
    fun replayPair(
        data: PairData,
        fee: TradingFeeConfig,
        config: IntradayConfig,
        exitConfig: IntradayExitConfig,
        lookaheadBar: Int,
        warmup: Int
    ): PairHasil {
        if (data.utama.size <= warmup + 5) {
            return PairHasil(data.pair, data.utama.size, emptyList(), emptyList())
        }
        val rep = IntradayReplayEngine.replay(
            symbol = data.pair,
            candles = data.utama,
            dailyCandles = data.harian,
            forwardLookaheadBars = lookaheadBar,
            enforceSessionClose = true,
            feeConfig = fee,
            config = config,
            exitConfig = exitConfig
        )
        val trades = rep.trades
            .filter { it.entryIndex >= warmup }
            .map {
                TradeRingkas(
                    pair = data.pair,
                    entryIndex = it.entryIndex,
                    entryTime = it.entryTime,
                    grossPnlPct = it.grossPnlPct,
                    netPnlPct = it.netPnlPct,
                    exitReason = it.exitReason,
                    outcome = it.outcome
                )
            }
        return PairHasil(
            pair = data.pair,
            bar = data.utama.size,
            buyIndices = rep.frames
                .filter { it.action == agu.analys.model.SignalAction.BUY && it.index >= warmup }
                .map { it.index },
            trades = trades
        )
    }

    fun fmt(x: Double): String = String.format(Locale.US, "%.3f", x)

    /** Ringkasan agregat untuk satu set hasil pair. */
    data class Agregat(
        val pair: Int,
        val buy: Int,
        val trade: Int,
        val menang: Int,
        val totalNetPct: Double,
        val unresolved: Int,
        val endOfData: Int,
        val grossPlusNetMinus: Int,
        val pairBerBuy: String
    ) {
        val avgNetPct: Double get() = if (trade == 0) 0.0 else totalNetPct / trade
        val winRatePct: Double get() = if (trade == 0) 0.0 else menang.toDouble() / trade * 100.0
    }

    fun agregat(hasil: List<PairHasil>): Agregat {
        val trades = hasil.flatMap { it.trades }
        val perPair = trades.groupingBy { it.pair }.eachCount().toSortedMap()
        return Agregat(
            pair = hasil.size,
            buy = hasil.sumOf { it.buy },
            trade = trades.size,
            menang = trades.count { it.netPnlPct >= 0.0 },
            totalNetPct = trades.sumOf { it.netPnlPct },
            unresolved = trades.count { it.exitReason == "LOOKAHEAD_EXPIRY" },
            endOfData = trades.count { it.exitReason == "END_OF_DATA" },
            grossPlusNetMinus = trades.count { it.grossPnlPct > 0.0 && it.netPnlPct < 0.0 },
            pairBerBuy = perPair.entries.joinToString(", ") { "${it.key} ${it.value}" }
        )
    }
}
