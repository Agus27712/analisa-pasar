package agu.analys.viewmodel

import agu.analys.engine.scalping.AutoBuySizing
import agu.analys.engine.scalping.PaperAutoBuyAllocation
import agu.analys.engine.scalping.PaperExitDecision
import agu.analys.engine.scalping.PaperExitPhase
import agu.analys.engine.scalping.PaperExitReason
import agu.analys.engine.scalping.PaperExitSnapshot
import agu.analys.engine.scalping.PaperExitStateMachine
import agu.analys.engine.scalping.PaperRehydrate
import agu.analys.engine.scalping.PaperRehydrateAction
import agu.analys.engine.scalping.PaperTradeRecord
import agu.analys.engine.scalping.ScalpExitParams
import agu.analys.engine.scalping.SignalTransition
import agu.analys.model.CandleBar
import agu.analys.model.SignalAction
import agu.analys.model.TradingPair
import agu.analys.trading.SimulationOrderResult
import agu.analys.trading.SimulationOrderSide
import agu.analys.trading.SimulationOrderType
import agu.analys.util.AppLogManager
import agu.analys.util.ExchangeRateManager
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
 *
 * **Kepemilikan exit.** Saat engine P2 aktif untuk sebuah pair, jalur auto-sell lama
 * (`checkTrailingForMode` → TP1/TP2/SL dari `positionStore`) sengaja **dimatikan** untuk
 * pair itu, supaya tidak terjadi jual ganda dan supaya satu-satunya sumber kebenaran exit
 * adalah mesin yang sudah diuji paritas terhadap replay.
 */
object PaperAutoTradeRuntime {
    /** State machine exit per `exchange|symbol` untuk posisi paper yang sedang berjalan. */
    private val machines = ConcurrentHashMap<String, PaperExitStateMachine>()

    /** Waktu buka posisi per key — dipakai untuk mengisi `openedAt` di jurnal. */
    private val openedAtMap = ConcurrentHashMap<String, Long>()

    /** Bucket menit terakhir yang sudah dihitung sebagai satu bar M1, per key. */
    private val lastBarBucketMap = ConcurrentHashMap<String, Long>()

    /** Simbol asli (apa adanya) per key — key sendiri menormalkan simbol sehingga lossy. */
    private val rawSymbolMap = ConcurrentHashMap<String, String>()

    /**
     * Key posisi yang sedang dievaluasi exit.
     *
     * [evaluatePaperAutoExit] dipanggil dari dua tempat sekaligus (thread latar price-feed dan
     * thread utama). Tanpa penjaga, dua pemeriksaan bisa sama-sama lolos lalu menutup posisi
     * dua kali. Set ini memastikan hanya satu evaluasi berjalan per posisi.
     */
    private val evaluatingKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun key(exchange: String, symbol: String): String =
        "${exchange.uppercase()}|${symbol.uppercase().replace("_", "")}"

    /**
     * Klaim hak evaluasi untuk satu posisi.
     * @return `true` bila berhasil diklaim — pemanggil **wajib** memanggil [endEvaluation].
     */
    fun beginEvaluation(exchange: String, symbol: String): Boolean =
        evaluatingKeys.add(key(exchange, symbol))

    fun endEvaluation(exchange: String, symbol: String) {
        evaluatingKeys.remove(key(exchange, symbol))
    }

    fun machineFor(exchange: String, symbol: String): PaperExitStateMachine? =
        machines[key(exchange, symbol)]

    fun phaseFor(exchange: String, symbol: String): PaperExitPhase? =
        machines[key(exchange, symbol)]?.phase

    fun hasActivePosition(exchange: String, symbol: String): Boolean =
        machines.containsKey(key(exchange, symbol))

    /**
     * Apakah engine paper (P2) yang memegang hak exit untuk pair ini.
     *
     * Jalur auto-sell lama (`checkTrailingForMode`, `deployTrailingOrder`) wajib memakai
     * predikat ini supaya tidak ada dua mesin yang menjual posisi simulasi yang sama.
     * Mematikan `auto_sell_enabled` saja **tidak cukup**: pemeriksaan trailing lama
     * (`updateTrailingPrice`) hanya melihat `isTrailingEnabled`, bukan flag itu.
     *
     * `autoExitEnabled = false` berarti pengguna mematikan auto-exit paper — kepemilikan
     * **dikembalikan** ke jalur lama, karena kalau tidak, posisi akan menggantung tanpa
     * mesin yang menjualnya ([TradingViewModel.evaluatePaperAutoExit] berhenti lebih awal).
     */
    fun ownsExit(exchange: String, symbol: String, autoExitEnabled: Boolean): Boolean =
        autoExitEnabled && hasActivePosition(exchange, symbol)

    /**
     * Simbol asli yang punya posisi paper terbuka di bursa ini.
     *
     * Dipakai `startTrailingPolling` agar posisi paper tetap dievaluasi walau bukan pair
     * yang sedang dibuka di layar.
     */
    fun activeSymbols(exchange: String): List<String> {
        val prefix = "${exchange.uppercase()}|"
        return machines.keys
            .filter { it.startsWith(prefix) }
            .mapNotNull { rawSymbolMap[it] }
    }

    /**
     * @param openedAt waktu buka posisi asli. Dipakai rehidrasi setelah restart supaya
     *   `openedAt` di jurnal tetap waktu masuk yang sebenarnya, bukan waktu app dibuka.
     *   `0` berarti "sekarang".
     */
    fun put(exchange: String, symbol: String, machine: PaperExitStateMachine, openedAt: Long = 0L) {
        val k = key(exchange, symbol)
        machines[k] = machine
        rawSymbolMap[k] = symbol
        val now = System.currentTimeMillis()
        openedAtMap[k] = if (openedAt > 0L) openedAt else now
        lastBarBucketMap[k] = now / 60_000L
    }

    fun openedAt(exchange: String, symbol: String): Long =
        openedAtMap[key(exchange, symbol)] ?: 0L

    /**
     * Tandai bucket menit untuk evaluasi per-tick.
     *
     * @return `true` bila ini tick pertama di menit berjalan, artinya satu bar M1 baru
     *   terbentuk dan `barsHeld` boleh ditambah.
     */
    fun markBarBucket(exchange: String, symbol: String, now: Long): Boolean {
        val k = key(exchange, symbol)
        val bucket = now / 60_000L
        val last = lastBarBucketMap[k]
        lastBarBucketMap[k] = bucket
        return last == null || last != bucket
    }

    fun clear(exchange: String, symbol: String) {
        val k = key(exchange, symbol)
        machines.remove(k)
        openedAtMap.remove(k)
        lastBarBucketMap.remove(k)
        rawSymbolMap.remove(k)
    }

    fun clearAll() {
        machines.clear()
        openedAtMap.clear()
        lastBarBucketMap.clear()
        rawSymbolMap.clear()
        evaluatingKeys.clear()
    }
}

/**
 * Apakah engine paper (P2) yang memegang hak exit untuk pair ini.
 *
 * Dipakai jalur auto-sell lama (`checkTrailingForMode`, `deployTrailingOrder`) untuk
 * menyerahkan kepemilikan exit sepenuhnya ke [PaperExitStateMachine] selama engine aktif.
 */
fun TradingViewModel.paperEngineOwnsExit(exchange: String, symbol: String): Boolean =
    PaperAutoTradeRuntime.ownsExit(exchange, symbol, prefs.paperAutoTradeConfig.autoExitEnabled)

/**
 * ATR% bar M1 terhadap harga acuan — dipakai mesin exit untuk S8 (BE/trailing proporsional ATR).
 *
 * @return 0.0 bila ATR belum tersedia, sehingga mesin exit jatuh ke nilai persen tetap.
 */
private fun TradingViewModel.atrPctAt(referencePrice: Double): Double {
    if (referencePrice <= 0.0) return 0.0
    val atr = engine.indicators.value.atr
    return if (atr > 0.0) atr / referencePrice * 100.0 else 0.0
}

/**
 * Bangun & pasang state machine exit paper untuk satu posisi simulasi.
 *
 * Dipakai bersama oleh **auto-buy** ([tryPaperAutoBuy]) dan **beli manual**
 * ([syncSimulationTradeToPositionStore]) supaya semua posisi paper memakai mesin exit yang sama.
 *
 * Sebelumnya mesin ini hanya dibuat untuk auto-buy, sehingga posisi yang dibeli manual berjalan
 * **tanpa SL/BE/trailing terkelola** — persis kasus AMP/USDT: menahan −2,76% selama 9 menit
 * tanpa stop apa pun.
 *
 * **S3 — SL keras wajib.** Bila tidak ada SL valid dari sinyal (`0 < SL < entry`), SL dihitung
 * dari **lantai friksi** (`minFrictionMultiple × friksi round-trip`) supaya posisi tidak pernah
 * menggantung tanpa lantai risiko. Bila `minFrictionMultiple = 0` dan sinyal tidak punya SL,
 * posisi sengaja dibiarkan tanpa mesin (perilaku lama) dan dicatat di log.
 *
 * @param force `true` menimpa mesin yang sudah ada (dipakai auto-buy yang nilainya otoritatif
 *   dari sinyal transisi). `false` tidak menimpa — pemanggil manual aman dipanggil berulang.
 * @return `true` bila mesin terpasang (atau sudah ada).
 */
fun TradingViewModel.armPaperExitEngine(
    symbol: String,
    exchange: String,
    entryPrice: Double,
    stopLoss: Double,
    tp1: Double,
    tp2: Double,
    atrPct: Double = 0.0,
    force: Boolean = false
): Boolean {
    val exchangeKey = exchange.uppercase()
    if (!prefs.paperAutoTradeConfig.autoExitEnabled) return false
    if (entryPrice <= 0.0 || !entryPrice.isFinite()) return false

    val pair = TradingPair.fromCustomSymbol(raw = symbol, exchange = exchange)
    if (!force && PaperAutoTradeRuntime.machineFor(exchangeKey, pair.symbol) != null) return true

    val runtime = prefs.scalpingRuntimeConfig
    val fees = prefs.feeConfigFor(pair.symbol)

    // S3 — SL keras: pakai SL sinyal bila valid, jika tidak turunkan dari lantai friksi.
    val signalSlValid = stopLoss > 0.0 && stopLoss.isFinite() && stopLoss < entryPrice
    val effectiveSl = if (signalSlValid) {
        stopLoss
    } else {
        val costPct = fees.buyTakerPct + fees.sellTakerPct + 2.0 * runtime.slippagePct
        val floorPct = runtime.minFrictionMultiple * costPct
        if (runtime.minFrictionMultiple > 0.0 && floorPct > 0.0) {
            AppLogManager.service(
                "PaperAutoExit",
                "🛡️ [${pair.symbol}] Tidak ada SL dari sinyal — SL keras dipasang dari lantai friksi " +
                    "${String.format(java.util.Locale.US, "%.2f", floorPct)}% " +
                    "(harga ${PriceFormatter.formatPrice(entryPrice * (1.0 - floorPct / 100.0), quoteAsset = pair.quoteAsset)})."
            )
            entryPrice * (1.0 - floorPct / 100.0)
        } else {
            AppLogManager.service(
                "PaperAutoExit",
                "⏭️ [${pair.symbol}] Beli manual tanpa SL & lantai friksi nonaktif — posisi dibiarkan tanpa mesin exit."
            )
            0.0
        }
    }
    if (effectiveSl <= 0.0 || effectiveSl >= entryPrice) return false

    val tp1v = if (tp1 > 0.0) tp1 else entryPrice
    val tp2v = if (tp2 > 0.0) tp2 else entryPrice * 1.02

    val machine = PaperExitStateMachine(
        entry = entryPrice,
        stopLoss = effectiveSl,
        tp1 = tp1v,
        tp2 = tp2v,
        buyFeePct = fees.buyTakerPct,
        sellFeePct = fees.sellTakerPct,
        slippagePct = runtime.slippagePct,
        params = runtime.exit,
        atrPct = atrPct
    )
    PaperAutoTradeRuntime.put(exchangeKey, pair.symbol, machine)
    // Penanda persisten: satu-satunya jejak bahwa exit pair ini dipegang engine paper.
    // Tanpa ini posisi menggantung setelah restart (lihat [PaperRehydrate]).
    positionStore.setPaperEngineOwned(
        symbol = pair.symbol, isReal = false, exchange = exchangeKey, owned = true
    )

    // Kepemilikan exit tunggal: matikan jalur auto-sell lama untuk pair ini supaya tidak ada
    // dua mesin yang menjual posisi yang sama.
    positionStore.setAutoSellParams(
        symbol = pair.symbol,
        enabled = false,
        tp1Price = tp1v,
        tp1Percent = 50.0,
        tp2Price = tp2v,
        tp2Percent = 50.0,
        isReal = false,
        exchange = exchangeKey,
        stopLossPrice = effectiveSl
    )
    // `setHolding` mempertahankan `isTrailingEnabled` dari posisi sebelumnya, jadi bendera
    // trailing lama bisa terbawa ke posisi paper baru. Bersihkan sejak tick pertama.
    positionStore.setTrailingStop(
        symbol = pair.symbol,
        enabled = false,
        trailingPercent = 0.0,
        isReal = false,
        exchange = exchangeKey
    )

    startTrailingPolling()
    return true
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

    // Biaya & slippage diambil dari sumber yang **sama** dengan yang dipakai order engine
    // (`SimulationCoordinator.setSlippagePct` <- `prefs.scalpingRuntimeConfig.slippagePct`).
    // Kalau keduanya berbeda, ukuran yang dicek di sini tidak sama dengan yang benar-benar
    // dipotong — persis kelas bug yang membuat auto-buy selalu ditolak.
    val quoteIsUsdt = PriceFormatter.isUsdtQuote(pair.quoteAsset)
    val usdtIdrRate = ExchangeRateManager.currentRate()

    val sizing = cfg.plan(
        availableQuote = available,
        price = price,
        stopLoss = signal.stopLoss,
        quoteIsUsdt = quoteIsUsdt,
        usdtIdrRate = usdtIdrRate,
        feeRate = prefs.feeConfigFor(pair.symbol).buyTakerPct / 100.0,
        slippagePct = prefs.scalpingRuntimeConfig.slippagePct
    )

    val (qty, notionalQuote) = when (sizing) {
        is AutoBuySizing.Skip -> {
            AppLogManager.service(
                "PaperAutoBuy",
                "⏭️ [${pair.symbol}] Auto-buy dilewati: ${sizing.reason} " +
                    "(kas ${pair.quoteAsset} tersedia ${PriceFormatter.formatPrice(available, quoteAsset = pair.quoteAsset)})."
            )
            return false
        }

        is AutoBuySizing.Ok -> sizing.quantity to sizing.notionalQuote
    }

    // Catat nominal yang benar-benar dipakai, supaya "Rp 100.000" tidak lagi bisa disalahartikan
    // sebagai $100.000 di log.
    val nominalNote = when (cfg.allocation) {
        PaperAutoBuyAllocation.FIXED_QUOTE -> {
            val idr = PriceFormatter.formatMoney(cfg.fixedIdrAmount, "IDR")
            if (quoteIsUsdt) {
                "$idr → ${PriceFormatter.formatPrice(notionalQuote, quoteAsset = pair.quoteAsset)}" +
                    " (kurs ${PriceFormatter.formatPrice(usdtIdrRate, quoteAsset = "IDR")})"
            } else {
                "nominal $idr"
            }
        }

        PaperAutoBuyAllocation.RISK_BASED ->
            "risiko ${String.format(java.util.Locale.US, "%.1f", cfg.riskPctPerTrade)}% → " +
                PriceFormatter.formatPrice(notionalQuote, quoteAsset = pair.quoteAsset)
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

    // Engine P2 hanya boleh mengambil alih exit bila risikonya valid (SL di bawah entry).
    val p2OwnsExit = cfg.autoExitEnabled && signal.stopLoss > 0.0 && signal.stopLoss < price

    val tp1Percent = 50.0
    positionStore.setAutoSellParams(
        symbol = pair.symbol,
        // Bila engine P2 yang memegang exit, jalur auto-sell lama dimatikan untuk pair ini
        // agar tidak ada dua mesin yang menjual posisi yang sama.
        enabled = !p2OwnsExit,
        tp1Price = signal.targetPrice1,
        tp1Percent = tp1Percent,
        tp2Price = signal.targetPrice2,
        tp2Percent = 100.0 - tp1Percent,
        isReal = false,
        exchange = exchangeKey,
        // SL asli sinyal. Tanpa ini `setHolding` meninggalkan SL sintetis `entry * 0.99`,
        // sehingga jalur auto-sell lama dan rehidrasi FSM memakai SL yang jauh lebih rapat
        // daripada lantai friksi engine.
        stopLossPrice = signal.stopLoss
    )

    if (p2OwnsExit) {
        // Mesin exit dibangun lewat helper bersama (S7) supaya beli manual & auto-buy memakai
        // jalur yang sama. `force = true` karena SL/TP sinyal transisi di sini otoritatif.
        armPaperExitEngine(
            symbol = pair.symbol,
            exchange = exchangeKey,
            entryPrice = price,
            stopLoss = signal.stopLoss,
            tp1 = signal.targetPrice1,
            tp2 = signal.targetPrice2,
            atrPct = atrPctAt(price),
            force = true
        )
    }

    positionCoordinator.refreshPosition(pair.symbol)

    val modeLabel = prefs.scalpingRuntimeConfig.exit.mode.label
    AppLogManager.trade(
        "PaperAutoBuy",
        "🤖 [${pair.symbol}] Auto-buy SIMULASI ${String.format(java.util.Locale.US, "%.8f", qty)} ${pair.baseAsset} @ " +
            "${PriceFormatter.formatPrice(price, quoteAsset = pair.quoteAsset)} | $nominalNote | " +
            "Stage ${signal.scalpingStage.name} | Exit: " +
            if (p2OwnsExit) modeLabel else "auto-sell bawaan"
    )

    return true
}

/**
 * Evaluasi exit paper untuk satu tick harga pada posisi simulasi yang sedang berjalan.
 *
 * Pemeriksaan level (SL / TP2 / TP1 partial / BE / trailing / early) dijalankan tiap tick
 * supaya proteksi tidak menunggu akhir bar. Hitungan bar M1 dan time-stop hanya maju sekali
 * per menit, lihat [PaperExitStateMachine.onBar] parameter `isNewBar`.
 *
 * @param window bar M1 terakhir (maks 15) untuk uji momentum early profit; boleh kosong.
 * @return keputusan exit bila posisi baru saja ditutup, atau `null`.
 */
fun TradingViewModel.evaluatePaperAutoExit(
    symbol: String,
    exchange: String,
    currentPrice: Double,
    window: List<CandleBar> = emptyList()
): PaperExitDecision? {
    // Kunci per posisi: cegah dua pemanggil (thread utama & latar) mengevaluasi bersamaan
    // sehingga posisi tertutup dua kali atau jurnalnya hilang.
    if (!PaperAutoTradeRuntime.beginEvaluation(exchange, symbol)) return null
    try {
        return evaluatePaperAutoExitLocked(symbol, exchange, currentPrice, window)
    } finally {
        PaperAutoTradeRuntime.endEvaluation(exchange, symbol)
    }
}

private fun TradingViewModel.evaluatePaperAutoExitLocked(
    symbol: String,
    exchange: String,
    currentPrice: Double,
    window: List<CandleBar>
): PaperExitDecision? {
    val machine = PaperAutoTradeRuntime.machineFor(exchange, symbol)
    if (machine == null) {
        // Tidak ada posisi paper berjalan -> pastikan kartu FSM kosong.
        publishPaperExitSnapshot(null)
        return null
    }

    // Posisi simulasi sudah tidak ada (mis. dijual manual dari layar lain) -> lepas state
    // machine supaya kartu FSM tidak menampilkan posisi hantu, dan supaya kepemilikan exit
    // tidak terkunci selamanya oleh mesin yang sudah tidak punya posisi.
    val pair = TradingPair.fromCustomSymbol(raw = symbol, exchange = exchange)
    if (!positionStore.get(pair.symbol, isReal = false, exchange = exchange).isHolding) {
        PaperAutoTradeRuntime.clear(exchange, symbol)
        positionStore.setPaperEngineOwned(symbol = pair.symbol, isReal = false, exchange = exchange, owned = false)
        publishPaperExitSnapshot(null)
        return null
    }

    if (currentPrice <= 0.0) {
        publishPaperExitSnapshot(PaperExitSnapshot.of(machine, symbol, exchange, currentPrice))
        return null
    }

    if (!prefs.paperAutoTradeConfig.autoExitEnabled) {
        publishPaperExitSnapshot(PaperExitSnapshot.of(machine, symbol, exchange, currentPrice))
        return null
    }

    val now = System.currentTimeMillis()
    val isNewBar = PaperAutoTradeRuntime.markBarBucket(exchange, symbol, now)
    val bar = CandleBar(
        timestamp = now,
        open = currentPrice,
        high = currentPrice,
        low = currentPrice,
        close = currentPrice,
        volume = 0.0
    )
    // Jendela momentum diteruskan apa adanya: bila kosong, engine menahan diri dari
    // early-profit (lihat PaperExitStateMachine.onBar).
    val decision = machine.onBar(bar, window, isNewBar = isNewBar)
    if (decision == null) {
        publishPaperExitSnapshot(PaperExitSnapshot.of(machine, symbol, exchange, currentPrice))
        return null
    }

    finalizePaperExit(symbol, exchange, machine, decision)
    publishPaperExitSnapshot(null)
    return decision
}

/**
 * Tutup posisi paper: kirim SELL simulasi, catat jurnal, lalu lepas state machine.
 *
 * Hanya menyentuh akun simulasi (`isReal = false`).
 */
fun TradingViewModel.finalizePaperExit(
    symbol: String,
    exchange: String,
    machine: PaperExitStateMachine,
    decision: PaperExitDecision
): Boolean {
    val pair = TradingPair.fromCustomSymbol(raw = symbol, exchange = exchange)
    val posQty = positionStore.get(pair.symbol, isReal = false, exchange = exchange).quantity
    val heldQty = if (posQty > 0.0) posQty else simCoordinator.wallet.value.getAvailableCoin(pair.baseAsset)

    val triggerType = when (decision.reason) {
        PaperExitReason.SL -> "STOP_LOSS"
        PaperExitReason.BE_SL -> "BE_STOP"
        PaperExitReason.TRAIL_SL -> "TRAILING"
        PaperExitReason.TP2 -> "TP2"
        PaperExitReason.EARLY_PROFIT -> "EARLY_PROFIT"
        PaperExitReason.TIMEOUT -> "TIME_STOP"
        PaperExitReason.BAD_RISK -> "BAD_RISK"
    }

    val exitPrice = if (decision.exitPrice > 0.0) decision.exitPrice else machine.entry

    val dispatched = heldQty > 0.0 && executeAutoSellOrder(
        symbol = pair.symbol,
        price = exitPrice,
        quantity = heldQty,
        triggerType = triggerType,
        isReal = false,
        isPartial = false,
        exchange = exchange
    )

    // Catat ke jurnal hanya bila exit benar-benar tereksekusi di akun simulasi.
    if (dispatched) {
        val now = System.currentTimeMillis()
        appendPaperTradeRecord(
            PaperTradeRecord(
                id = now,
                symbol = pair.symbol,
                exchange = exchange.uppercase(),
                mode = machine.params.mode,
                reason = decision.reason,
                result = decision.result,
                entry = machine.entry,
                exitPrice = exitPrice,
                stopLoss = machine.stopLoss,
                tp1 = machine.tp1,
                tp2 = machine.tp2,
                r = decision.r,
                ambiguous = decision.ambiguous,
                barsHeld = machine.barsHeld,
                openedAt = PaperAutoTradeRuntime.openedAt(exchange, symbol),
                closedAt = now
            )
        )

        AppLogManager.trade(
            "PaperAutoExit",
            "🏁 [${pair.symbol}] Paper exit ${decision.reason.displayLabel()} " +
                "(${machine.params.mode.label}) | R net ${String.format(java.util.Locale.US, "%.2f", decision.r)} " +
                "@ ${PriceFormatter.formatPrice(exitPrice, quoteAsset = pair.quoteAsset)}" +
                if (decision.ambiguous) " | ⚠️ bar ambigu (SL & TP2 satu bar)" else ""
        )
    } else {
        AppLogManager.service(
            "PaperAutoExit",
            "⚠️ [${pair.symbol}] Sinyal exit ${decision.reason.displayLabel()} tidak tereksekusi (qty simulasi 0)."
        )
    }

    PaperAutoTradeRuntime.clear(exchange, symbol)
    positionStore.setPaperEngineOwned(symbol = pair.symbol, isReal = false, exchange = exchange, owned = false)
    positionCoordinator.refreshPosition(pair.symbol)
    return dispatched
}

/** Hapus state machine exit setelah posisi paper ditutup. */
fun TradingViewModel.clearPaperExitState(symbol: String, exchange: String) {
    PaperAutoTradeRuntime.clear(exchange, symbol)
    positionStore.setPaperEngineOwned(symbol = symbol, isReal = false, exchange = exchange, owned = false)
    publishPaperExitSnapshot(null)
}

/** Ringkas parameter exit yang sedang aktif, untuk ditampilkan di UI. */
fun TradingViewModel.currentExitParams(): ScalpExitParams = prefs.scalpingRuntimeConfig.exit

/**
 * Potret posisi paper untuk **pair tertentu**.
 *
 * [TradingViewModel.paperExitSnapshot] sengaja tidak dipakai di halaman detail: StateFlow itu
 * di-`publish` dari tick pair mana pun (`MarketDataCoordinator.onPriceUpdate` memanggil
 * [evaluatePaperAutoExit] untuk semua simbol yang diperbarui), sehingga isinya bisa milik pair
 * lain. Kartu detail harus selalu membaca posisi pair yang sedang dibuka.
 *
 * @return `null` bila pair ini tidak punya posisi simulasi terbuka.
 */
fun TradingViewModel.paperSnapshotFor(
    exchange: String,
    symbol: String,
    currentPrice: Double
): PaperExitSnapshot? {
    val machine = PaperAutoTradeRuntime.machineFor(exchange, symbol) ?: return null
    return PaperExitSnapshot.of(machine, symbol, exchange, currentPrice)
}

/**
 * Bangun ulang state machine exit paper setelah app restart.
 *
 * ## Masalah yang ditutup
 *
 * [PaperAutoTradeRuntime] menyimpan state machine **hanya di memori**, sedangkan posisinya
 * persisten di `SpotPositionStore`, dan `auto_sell_enabled` sudah dimatikan saat engine
 * mengambil alih exit. Setelah app di-kill, tidak ada mesin yang menjual posisi itu:
 * engine paper kosong, jalur auto-sell lama mati, posisi menggantung.
 *
 * ## Cara kerja
 *
 * Penanda persisten `_paper_engine` (ditulis [tryPaperAutoBuy]) dipakai untuk mengenali
 * posisi yang exit-nya memang dipegang engine paper. Untuk setiap posisi:
 * - [PaperRehydrateAction.Restore] → mesin dibangun ulang, `openedAt` asli dikembalikan;
 * - [PaperRehydrateAction.HandBackToLegacy] → `auto_sell_enabled` dinyalakan kembali dan
 *   penanda dilepas, supaya jalur auto-sell lama yang menjualnya;
 * - [PaperRehydrateAction.Skip] → dilewati.
 *
 * Posisi lama yang **belum** punya penanda (dibeli sebelum perbaikan ini) sengaja tidak
 * disentuh: SL-nya tersimpan sebagai `entry * 0.99` yang salah, jadi memulihkannya akan
 * memalsukan stop-out.
 *
 * @return jumlah mesin yang berhasil dipulihkan.
 */
fun TradingViewModel.rehydratePaperAutoExit(): Int {
    val cfg = prefs.paperAutoTradeConfig
    val exchange = prefs.marketDataSource.name
    val symbols = positionStore.getPaperEngineSymbols(isReal = false, exchange = exchange)
    if (symbols.isEmpty()) return 0

    val runtime = prefs.scalpingRuntimeConfig
    var restored = 0
    var handedBack = 0

    for (raw in symbols) {
        // Jangan timpa mesin yang sudah hidup (mis. auto-buy terjadi sebelum rehidrasi jalan).
        if (PaperAutoTradeRuntime.machineFor(exchange, raw) != null) continue

        val pair = TradingPair.fromCustomSymbol(raw = raw, exchange = exchange)
        val pos = positionStore.get(pair.symbol, isReal = false, exchange = exchange)
        val fees = prefs.feeConfigFor(pair.symbol)

        val action = PaperRehydrate.decide(
            entry = pos.entryPrice,
            stopLoss = pos.stopLossPrice,
            tp1 = pos.tp1Price,
            tp2 = pos.tp2Price,
            autoExitEnabled = cfg.autoExitEnabled,
            buyFeePct = fees.buyTakerPct,
            sellFeePct = fees.sellTakerPct,
            slippagePct = runtime.slippagePct,
            params = runtime.exit
        )

        when (action) {
            is PaperRehydrateAction.Restore -> {
                PaperAutoTradeRuntime.put(exchange, pair.symbol, action.machine, openedAt = pos.openedAt)
                restored++
            }

            is PaperRehydrateAction.HandBackToLegacy -> {
                // Kepemilikan dikembalikan ke jalur lama; tanpa ini posisi menggantung karena
                // `auto_sell_enabled` sudah dimatikan saat engine mengambil alih.
                positionStore.setAutoSellParams(
                    symbol = pair.symbol,
                    enabled = true,
                    tp1Price = pos.tp1Price,
                    tp1Percent = pos.tp1Percent,
                    tp2Price = pos.tp2Price,
                    tp2Percent = pos.tp2Percent,
                    isReal = false,
                    exchange = exchange,
                    stopLossPrice = pos.stopLossPrice
                )
                positionStore.setPaperEngineOwned(
                    symbol = pair.symbol, isReal = false, exchange = exchange, owned = false
                )
                handedBack++
                AppLogManager.service(
                    "PaperAutoExit",
                    "↩️ [${pair.symbol}] Rehidrasi dilewati (${action.reason}) — exit diserahkan ke jalur auto-sell lama."
                )
            }

            is PaperRehydrateAction.Skip -> {
                AppLogManager.service(
                    "PaperAutoExit",
                    "⏭️ [${pair.symbol}] Rehidrasi dilewati: ${action.reason}."
                )
            }
        }
    }

    if (restored > 0) {
        startTrailingPolling()
        AppLogManager.trade(
            "PaperAutoExit",
            "♻️ Rehidrasi setelah restart: $restored posisi paper dipulihkan" +
                if (handedBack > 0) ", $handedBack diserahkan ke jalur lama." else "."
        )
    }
    return restored
}

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
