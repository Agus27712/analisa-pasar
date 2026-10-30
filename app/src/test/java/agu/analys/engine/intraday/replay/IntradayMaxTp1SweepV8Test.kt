package agu.analys.engine.intraday.replay

import agu.analys.config.FeeSchedule
import agu.analys.engine.intraday.IntradayConfig
import agu.analys.model.CandleBar
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Alat ukur **sweep `maxTp1Pct` pada dataset H4 `data/tokocrypto_v8`** (26 pair USDT), memakai
 * jalur replay dan geometri yang **sama** dengan aplikasi ([IntradayReplayEngine] +
 * [agu.analys.engine.intraday.IntradayGeometry]).
 *
 * Kenapa berkas ini ada (2026-10-29): alat sebelumnya, [IntradayMaxTp1SweepTest], mengukur di
 * `data/tokocrypto_v7` — 11 pair × 999 bar H1 (±41 hari) dan **tanpa candle D1**. Itu bukan jalur
 * app: `CandidateScanWorker` mengoper `h4Candles` + `d1Candles` ke evaluator intraday, dan sejak
 * `9972ab6` tersedia `data/tokocrypto_v8` (H4 4.999 bar ≈ 833 hari, D1 2.205–3.360 bar, 26 pair).
 * Berkas lama **tidak diubah** supaya angka §83 tetap bisa direproduksi; berkas ini yang jadi alat
 * kanonik untuk pengukuran berikutnya.
 *
 * Perbedaan yang diperbaiki di sini:
 * 1. **Timeframe = H4** (jalur app), bukan H1.
 * 2. **Candle D1 dioper** ke `replay(dailyCandles = …)` sehingga veto anomali makro ikut aktif.
 * 3. **Lookahead berbasis jam, bukan jumlah bar**: bawaan 24 jam → 6 bar H4 (dulu 24 bar = 96 jam).
 * 4. **Pair diambil dari daftar berkas**, bukan dipilih dari hasil sinyal (menghindari seleksi
 *    bercampur validasi). Semua pair ikut; pair tanpa sinyal dilaporkan sebagai nol.
 * 5. **Pemanasan 220 bar** (bawaan) supaya EMA200/EMA100 sudah terisi saat bar pertama dievaluasi,
 *    setara feed 200 candle H4 di app. Tanpa itu bar awal dinilai dengan EMA yang belum ada.
 * 6. **Potongan window & walk-forward dihitung dari daftar trade yang sama** (partisi `entryIndex`),
 *    bukan replay ulang pada data terpotong. Replay ulang memotong lookahead di batas potongan
 *    sehingga jumlah potongan tidak lagi menjumlah ke window penuh.
 * 7. Fee dari **`FeeSchedule`** (bukan preset lokal) dan `roundTripPct` dilaporkan di keluaran.
 *
 * Hanya jalan bila `RUN_INTRADAY_SWEEP_V8=true`; tanpa env itu test **dilewati** agar suite harian
 * tetap cepat (pola sama dengan [IntradayMaxTp1SweepTest]).
 *
 * Perintah (yang menghasilkan angka laporan):
 *
 * ```
 * RUN_INTRADAY_SWEEP_V8=true ./gradlew :app:testDebugUnitTest \
 *   --tests "agu.analys.engine.intraday.replay.IntradayMaxTp1SweepV8Test"
 * ```
 *
 * Variabel lingkungan (semua opsional, bawaan = pengukuran penuh):
 * - `INTRADAY_SWEEP_DATASET` (bawaan `data/tokocrypto_v8`), `INTRADAY_SWEEP_TF` (bawaan `4h`).
 * - `INTRADAY_SWEEP_CAPS` (bawaan `5,6,7,8,9,10`), `INTRADAY_SWEEP_LOOKAHEAD_HOURS` (bawaan `24`).
 * - `INTRADAY_SWEEP_WARMUP` (bawaan `220`), `INTRADAY_SWEEP_FOLDS` (bawaan `4`).
 * - `INTRADAY_SWEEP_PAIRS` (bawaan kosong = semua berkas), `INTRADAY_SWEEP_LIMIT` (bawaan `0` = semua).
 * - `INTRADAY_SWEEP_THREADS` (bawaan jumlah core).
 *
 * Konfigurasi yang dipegang tetap selama sweep: ambang `minNetRrTp1 = 1,5` (keputusan owner,
 * tidak diturunkan), `macdHistMinPct = 0,0`, fee Tokocrypto USDT all-in, slippage bawaan.
 * Hanya `maxTp1Pct` yang berubah — satu variabel, supaya perbandingan sah.
 * `IntradayConfig` **tidak** diubah oleh alat ini.
 *
 * Keluaran: `app/build/intraday-maxtp1-sweep-v8.md` (tabel laporan),
 * `app/build/intraday-maxtp1-sweep-v8.json` (angka mentah per pair),
 * `app/build/intraday-maxtp1-sweep-v8-progress.txt` (baris progres selama berjalan).
 *
 * Angka yang boleh dikutip hanya yang ada di berkas keluaran itu — jangan dari ingatan.
 */
class IntradayMaxTp1SweepV8Test {

    /** Ringkasan satu irisan (window penuh / potongan / fold) untuk satu nilai `maxTp1Pct`. */
    private data class Hasil(
        val cap: Double,
        val irisan: String,
        val buy: Int,
        val trade: Int,
        val menang: Int,
        val totalNetPct: Double,
        val pairBerBuy: String
    ) {
        val avgNetPct: Double get() = if (trade > 0) totalNetPct / trade else 0.0
        val winRatePct: Double get() = if (trade > 0) menang.toDouble() / trade * 100.0 else 0.0
    }

    /** Satu trade yang sudah diketahui irisan mana ia masuk. */
    private data class TradeRingkas(
        val pair: String,
        val entryIndex: Int,
        val entryTime: Long,
        val netPnlPct: Double,
        val exitReason: String
    )

    @Test
    fun `sweep maxTp1Pct pada dataset H4 v8`() {
        assumeTrue(
            "Sweep v8 hanya dijalankan bila diminta (RUN_INTRADAY_SWEEP_V8=true)",
            System.getenv("RUN_INTRADAY_SWEEP_V8") == "true"
        )

        val root = cariRootRepo()
        val namaDataset = ambil("INTRADAY_SWEEP_DATASET", "data/tokocrypto_v8")
        val tf = ambil("INTRADAY_SWEEP_TF", "4h").lowercase(Locale.ROOT)
        val dirData = File(root, namaDataset)
        assertTrue("Dataset tidak ditemukan: ${dirData.absolutePath}", dirData.isDirectory)

        val capList = ambil("INTRADAY_SWEEP_CAPS", "5,6,7,8,9,10")
            .split(",").mapNotNull { it.trim().toDoubleOrNull() }.sorted()
        assertTrue("Daftar cap kosong", capList.isNotEmpty())

        val jamLookahead = ambil("INTRADAY_SWEEP_LOOKAHEAD_HOURS", "24").toDouble()
        val lookaheadBar = maxOf(1, Math.round(jamLookahead / jamPerBar(tf)).toInt())
        val warmup = ambil("INTRADAY_SWEEP_WARMUP", "220").toInt()
        val jumlahFold = ambil("INTRADAY_SWEEP_FOLDS", "4").toInt()
        val threads = ambil("INTRADAY_SWEEP_THREADS", Runtime.getRuntime().availableProcessors().toString()).toInt()
        val limit = ambil("INTRADAY_SWEEP_LIMIT", "0").toInt()
        val filterPair = ambil("INTRADAY_SWEEP_PAIRS", "")
            .split(",").map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotBlank() }

        // ── Muat dataset ────────────────────────────────────────────────────
        val berkasTf = dirData.listFiles { f: File -> f.name.endsWith("_$tf.csv") }
            ?.sortedBy { it.name }
            ?: emptyList()
        assertTrue(
            "Tidak ada berkas *_$tf.csv di ${dirData.absolutePath}",
            berkasTf.isNotEmpty()
        )

        var pair = berkasTf.map { it.name.removeSuffix("_$tf.csv") }
        if (filterPair.isNotEmpty()) pair = pair.filter { it in filterPair }
        if (limit > 0) pair = pair.take(limit)
        assertTrue("Tidak ada pair yang lolos filter", pair.isNotEmpty())

        val data = pair.associateWith { nama ->
            val utama = muatCandle(File(dirData, "${nama}_$tf.csv"))
            val harian = File(dirData, "${nama}_1d.csv").takeIf { it.isFile }
                ?.let { muatCandle(it) } ?: emptyList()
            utama to harian
        }
        val pairTanpaD1 = data.filterValues { it.second.isEmpty() }.keys

        println(
            "Sweep v8: dataset=$namaDataset tf=$tf pair=${pair.size} cap=$capList " +
                "lookahead=$lookaheadBar bar (${jamLookahead} jam) warmup=$warmup fold=$jumlahFold " +
                "thread=$threads"
        )

        val fee = FeeSchedule.configFor("TOKOCRYPTO", "USDT")
        val feeRoundTripPct = FeeSchedule.roundTripPctMultiplicative("TOKOCRYPTO", "USDT")

        // ── Berkas keluaran & progres ───────────────────────────────────────
        val berkasProgres = File(root, "app/build/intraday-maxtp1-sweep-v8-progress.txt")
        berkasProgres.parentFile?.mkdirs()
        berkasProgres.writeText("")

        val berkasJson = File(root, "app/build/intraday-maxtp1-sweep-v8.json")

        // ── Replay: satu kali per (pair, cap) ───────────────────────────────
        // Potongan window dan fold TIDAK me-replay ulang: keduanya partisi `entryIndex` dari daftar
        // trade yang sama. Dengan begitu satu trade dihitung tepat sekali dan jumlah irisan tetap
        // konsisten dengan window penuh.
        val pool = Executors.newFixedThreadPool(maxOf(1, threads)) { r ->
            Thread(r, "intraday-sweep-v8").apply { isDaemon = true }
        }
        val jsonAkar = JSONObject()
            .put("dataset", namaDataset)
            .put("timeframe", tf)
            .put("lookaheadBars", lookaheadBar)
            .put("lookaheadHours", jamLookahead)
            .put("warmupBars", warmup)
            .put("folds", jumlahFold)
            .put("threads", threads)
            .put("pairsTotal", pair.size)
            .put("generatedAt", Instant.now().toString())
            .put("fee", JSONObject()
                .put("exchange", "TOKOCRYPTO")
                .put("quote", "USDT")
                .put("buyTakerPct", fee.buyTakerPct)
                .put("sellTakerPct", fee.sellTakerPct)
                .put("slippagePct", FeeSchedule.DEFAULT_SLIPPAGE_PCT)
                .put("roundTripPct", feeRoundTripPct)
            )
            .put("defaults", JSONObject()
                .put("maxTp1Pct", IntradayConfig.DEFAULT.maxTp1Pct)
                .put("minNetRrTp1", IntradayConfig.DEFAULT.minNetRrTp1)
                .put("macdHistMinPct", IntradayConfig.DEFAULT.macdHistMinPct)
                .put("enforceSessionClose", true)
            )
            .put("pairTanpaD1", JSONArray(pairTanpaD1.toList()))

        try {
            val tradePerCap = LinkedHashMap<Double, MutableList<TradeRingkas>>()
            val buyPerCap = LinkedHashMap<Double, MutableList<Pair<String, Int>>>()
            val jsonCap = JSONArray()

            for (cap in capList) {
                val tugas = pair.map { nama ->
                    val (candle, harian) = data.getValue(nama)
                    pool.submit<PairHasil> {
                        hitungPair(
                            pair = nama,
                            candle = candle,
                            harian = harian,
                            cap = cap,
                            fee = fee,
                            lookaheadBar = lookaheadBar,
                            warmup = warmup
                        )
                    }
                }
                val hasilPair = tugas.map { it.get() }
                tradePerCap[cap] = hasilPair.flatMap { it.trades }.toMutableList()
                buyPerCap[cap] = hasilPair.flatMap { h -> h.buyIndices.map { h.pair to it } }.toMutableList()

                val objCap = JSONObject().put("cap", cap)
                val arrPair = JSONArray()
                hasilPair.sortedBy { it.pair }.forEach { h ->
                    arrPair.put(
                        JSONObject()
                            .put("pair", h.pair)
                            .put("bar", h.bar)
                            .put("buy", h.buy)
                            .put("trade", h.trade)
                            .put("menang", h.menang)
                            .put("totalNetPct", h.totalNetPct)
                            .put("avgNetPct", h.avgNetPct)
                            .put("unresolved", h.unresolved)
                    )
                }
                objCap.put("pairs", arrPair)
                objCap.put("buy", hasilPair.sumOf { it.buy })
                objCap.put("trade", hasilPair.sumOf { it.trade })
                jsonCap.put(objCap)

                berkasProgres.appendText(
                    "cap=${fmt(cap)}% selesai: ${hasilPair.size} pair, " +
                        "sinyal=${hasilPair.sumOf { it.buy }} trade=${hasilPair.sumOf { it.trade }}\n"
                )
                println("cap=${fmt(cap)}% selesai: ${hasilPair.size} pair")
            }

            jsonAkar.put("configs", jsonCap)

            // ── Ringkasan irisan dari daftar trade & sinyal yang sama ────────
            // BUY (sinyal) dan trade dihitung pada irisan yang sama; satu sinyal BUY yang ditahan
            // TTL/posisi terbuka bisa tidak menjadi trade, jadi kedua kolom memang bisa berbeda.
            val barMinimum = data.values.minOf { it.first.size }
            val irisan = mutableListOf<Hasil>()
            val jsonIrisan = JSONObject()

            /** Batas indeks [dari, sampai) per pair untuk satu irisan. */
            fun batas(irisan: String, pair: String): Pair<Int, Int> {
                val n = data.getValue(pair).first.size
                return when {
                    irisan == "window penuh" -> 0 to n
                    irisan == "awal 60%" -> 0 to (n * 0.6).toInt()
                    irisan == "akhir 40%" -> (n * 0.6).toInt() to n
                    irisan.startsWith("fold ") -> {
                        val f = irisan.removePrefix("fold ").substringBefore('/').toInt() - 1
                        val lebar = (n - warmup).toDouble() / jumlahFold
                        val a = warmup + (lebar * f).toInt()
                        val b = if (f == jumlahFold - 1) n else warmup + (lebar * (f + 1)).toInt()
                        a to b
                    }
                    else -> error("irisan tidak dikenal: $irisan")
                }
            }

            val daftarIrisan = buildList {
                add("window penuh")
                add("awal 60%")
                add("akhir 40%")
                for (f in 1..jumlahFold) add("fold $f/$jumlahFold")
            }

            for (cap in capList) {
                val trades = tradePerCap.getValue(cap)
                val buys = buyPerCap.getValue(cap)
                for (namaIrisan in daftarIrisan) {
                    val tradeIrisan = trades.filter { t ->
                        val (a, b) = batas(namaIrisan, t.pair)
                        t.entryIndex >= a && t.entryIndex < b
                    }
                    val buyIrisan = buys.filter { (p, idx) ->
                        val (a, b) = batas(namaIrisan, p)
                        idx >= a && idx < b
                    }
                    irisan += ringkas(cap, namaIrisan, tradeIrisan, buyIrisan)
                }
            }

            // Distribusi alasan keluar pada window penuh (diagnostik, bukan klaim profit).
            for (cap in capList) {
                val perAlasan = tradePerCap.getValue(cap).groupingBy { it.exitReason }.eachCount()
                jsonIrisan.put(
                    fmt(cap),
                    JSONObject(perAlasan.mapValues { it.value } as Map<*, *>)
                )
            }
            jsonAkar.put("exitReasons", jsonIrisan)

            berkasJson.writeText(jsonAkar.toString(2), Charsets.UTF_8)

            // ── Laporan markdown ────────────────────────────────────────────
            val laporan = buildString {
                appendLine("# Sweep maxTp1Pct — mode intraday di `$namaDataset` ($tf, ${pair.size} pair)")
                appendLine()
                appendLine("Dihasilkan `IntradayMaxTp1SweepV8Test` pada ${pair.size} pair `$tf` dataset")
                appendLine("`$namaDataset`, fee Tokocrypto USDT all-in (round-trip **${fmt(feeRoundTripPct)}%**,")
                appendLine("slippage ${fmt(FeeSchedule.DEFAULT_SLIPPAGE_PCT)}%/sisi), ambang `minNetRrTp1 = 1,5`,")
                appendLine("`macdHistMinPct = 0,0`, lookahead **$lookaheadBar bar** (${jamLookahead.toInt()} jam),")
                appendLine("pemanasan $warmup bar, exit sesi Close Malam aktif. Hanya `maxTp1Pct` yang berubah.")
                appendLine()
                appendLine("Panjang data: terpendek **$barMinimum bar**, terpanjang ${data.values.maxOf { it.first.size }} bar.")
                if (pairTanpaD1.isNotEmpty()) {
                    appendLine()
                    appendLine("Tanpa candle D1 (veto anomali makro tidak aktif): ${pairTanpaD1.joinToString(", ")}")
                }
                appendLine()
                appendLine("## Window penuh")
                appendLine()
                appendLine("| maxTp1Pct | BUY | Trade | Menang | Win rate | Avg net/trade | Total net | Pair yang BUY |")
                appendLine("|---|---|---|---|---|---|---|---|")
                irisan.filter { it.irisan == "window penuh" }.forEach { appendLine(barisTabel(it)) }
                appendLine()
                appendLine("## Potongan window (60% awal / 40% akhir)")
                appendLine()
                appendLine("Potongan dihitung dari **indeks bar** tiap pair, bukan replay ulang, jadi satu trade")
                appendLine("masuk tepat satu potongan dan jumlahnya konsisten dengan window penuh.")
                appendLine()
                appendLine("| maxTp1Pct | Potong | BUY | Trade | Avg net/trade | Win rate | Pair yang BUY |")
                appendLine("|---|---|---|---|---|---|---|")
                irisan.filter { it.irisan.startsWith("awal") || it.irisan.startsWith("akhir") }
                    .forEach { h ->
                        appendLine(
                            "| ${fmt(h.cap)}% | ${h.irisan} | ${h.buy} | ${h.trade} | " +
                                "${if (h.trade > 0) fmt(h.avgNetPct) + "%" else "—"} | " +
                                "${if (h.trade > 0) fmt(h.winRatePct) + "%" else "—"} | " +
                                "${h.pairBerBuy.ifBlank { "—" }} |"
                        )
                    }
                if (jumlahFold > 0) {
                    appendLine()
                    appendLine("## Walk-forward ($jumlahFold fold berurutan)")
                    appendLine()
                    appendLine("| maxTp1Pct | Fold | BUY | Trade | Avg net/trade | Total net | Pair yang BUY |")
                    appendLine("|---|---|---|---|---|---|---|")
                    irisan.filter { it.irisan.startsWith("fold ") }.forEach { h ->
                        appendLine(
                            "| ${fmt(h.cap)}% | ${h.irisan.removePrefix("fold ")} | ${h.buy} | ${h.trade} | " +
                                "${if (h.trade > 0) fmt(h.avgNetPct) + "%" else "—"} | " +
                                "${if (h.trade > 0) fmt(h.totalNetPct) + "%" else "—"} | " +
                                "${h.pairBerBuy.ifBlank { "—" }} |"
                        )
                    }
                }
                appendLine()
                appendLine("## Catatan batas pengukuran")
                appendLine()
                appendLine("1. Dataset hanya USDT. Pair IDR (biaya all-in lebih murah) **belum** terukur di sini.")
                appendLine("2. PEPEUSDT 2.999 bar dan CHIPUSDT/BANKUSDT lebih pendek karena listing baru / HTTP 429")
                appendLine("   saat pengambilan (lihat `$namaDataset/README.md`). Fold memakai batas per pair.")
                appendLine("3. Candle D1 dipotong `timestamp <= waktu evaluasi` (perilaku engine & app). Bar D1 hari")
                appendLine("   berjalan ikut terpakai, jadi veto anomali makro memakai high hari itu — sama dengan app,")
                appendLine("   dicatat sebagai batas, bukan diperbaiki diam-diam di alat ukur.")
                appendLine("4. Replay memakai jendela histori sampai 301 bar, app mengoper 200 candle H4.")
                appendLine("   Selisih ini berasal dari engine, bukan alat ukur.")
                appendLine("5. Trade yang tidak menyentuh SL/TP/sesi ditutup di bar terakhir lookahead")
                appendLine("   (`LOOKAHEAD_EXPIRY`) atau di bar terakhir dataset (`END_OF_DATA`), dan dihitung")
                appendLine("   **kalah** seperti trade lain — tidak dibuang. Paket D memisahkan keduanya.")
            }
            val keluar = File(root, "app/build/intraday-maxtp1-sweep-v8.md")
            keluar.parentFile?.mkdirs()
            keluar.writeText(laporan, Charsets.UTF_8)
            println(laporan)
            assertTrue("Laporan sweep v8 tidak ditulis", keluar.exists())
            assertTrue("Laporan sweep v8 kosong", keluar.length() > 0)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Perkakas
    // ─────────────────────────────────────────────────────────────────────────

    private data class PairHasil(
        val pair: String,
        val bar: Int,
        /** Indeks bar yang menghasilkan sinyal BUY (setelah pemanasan). */
        val buyIndices: List<Int>,
        val trade: Int,
        val menang: Int,
        val unresolved: Int,
        val totalNetPct: Double,
        val trades: List<TradeRingkas>
    ) {
        val buy: Int get() = buyIndices.size
        val avgNetPct: Double get() = if (trade > 0) totalNetPct / trade else 0.0
    }

    private fun hitungPair(
        pair: String,
        candle: List<CandleBar>,
        harian: List<CandleBar>,
        cap: Double,
        fee: agu.analys.config.TradingFeeConfig,
        lookaheadBar: Int,
        warmup: Int
    ): PairHasil {
        if (candle.size <= warmup + 5) {
            return PairHasil(pair, candle.size, emptyList(), 0, 0, 0, 0.0, emptyList())
        }
        val rep = IntradayReplayEngine.replay(
            symbol = pair,
            candles = candle,
            dailyCandles = harian,
            forwardLookaheadBars = lookaheadBar,
            enforceSessionClose = true,
            feeConfig = fee,
            config = IntradayConfig(maxTp1Pct = cap)
        )
        // Buang trade yang masuk sebelum pemanasan selesai: di sana EMA100/EMA200 belum terisi,
        // jadi sinyalnya tidak setara dengan feed 200 candle H4 di app.
        val trades = rep.trades
            .filter { it.entryIndex >= warmup }
            .map {
                TradeRingkas(
                    pair = pair,
                    entryIndex = it.entryIndex,
                    entryTime = it.entryTime,
                    netPnlPct = it.netPnlPct,
                    exitReason = it.exitReason
                )
            }
        val menang = trades.count { it.netPnlPct >= 0.0 }
        val unresolved = trades.count {
            it.exitReason == "LOOKAHEAD_EXPIRY" || it.exitReason == "END_OF_DATA" || it.exitReason == "UNRESOLVED"
        }
        return PairHasil(
            pair = pair,
            bar = candle.size,
            buyIndices = rep.frames
                .filter { it.action == agu.analys.model.SignalAction.BUY && it.index >= warmup }
                .map { it.index },
            trade = trades.size,
            menang = menang,
            unresolved = unresolved,
            totalNetPct = trades.sumOf { it.netPnlPct },
            trades = trades
        )
    }

    private fun ringkas(
        cap: Double,
        irisan: String,
        trades: List<TradeRingkas>,
        buys: List<Pair<String, Int>>
    ): Hasil {
        val menang = trades.count { it.netPnlPct >= 0.0 }
        val perPair = trades.groupingBy { it.pair }.eachCount().toSortedMap()
        return Hasil(
            cap = cap,
            irisan = irisan,
            buy = buys.size,
            trade = trades.size,
            menang = menang,
            totalNetPct = trades.sumOf { it.netPnlPct },
            pairBerBuy = perPair.entries.joinToString(", ") { "${it.key} ${it.value}" }
        )
    }

    private fun barisTabel(h: Hasil): String =
        "| ${fmt(h.cap)}% | ${h.buy} | ${h.trade} | ${h.menang} | " +
            "${if (h.trade > 0) fmt(h.winRatePct) + "%" else "—"} | " +
            "${if (h.trade > 0) fmt(h.avgNetPct) + "%" else "—"} | " +
            "${if (h.trade > 0) fmt(h.totalNetPct) + "%" else "—"} | " +
            "${h.pairBerBuy.ifBlank { "—" }} |"

    /** Jam per bar untuk TF yang didukung alat ini (dipakai mengubah lookahead jam → jumlah bar). */
    private fun jamPerBar(tf: String): Double = when (tf.lowercase(Locale.ROOT)) {
        "1h" -> 1.0
        "4h" -> 4.0
        "1d" -> 24.0
        else -> error("TF tidak didukung alat ini: $tf (pakai 1h/4h/1d)")
    }

    private fun fmt(x: Double): String = String.format(Locale.US, "%.3f", x)

    private fun ambil(nama: String, bawaan: String): String =
        System.getenv(nama)?.takeIf { it.isNotBlank() } ?: bawaan

    private fun muatCandle(berkas: File): List<CandleBar> =
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

    private fun cariRootRepo(): File {
        val base = File(System.getProperty("user.dir") ?: ".")
        return listOf(base, File(base, "app"), base.parentFile ?: base, File(base.parentFile ?: base, "app"))
            .firstOrNull { File(it, "data/tokocrypto_v8").isDirectory || File(it, "data/tokocrypto_v7").isDirectory }
            ?: error("Root repo (berisi data/tokocrypto_v8) tidak ditemukan dari $base")
    }
}
