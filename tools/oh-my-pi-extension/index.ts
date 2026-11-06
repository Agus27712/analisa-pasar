/**
 * analisa-pasar Agent Bridge — extension untuk omp
 *
 * Path (OMP):
 *   ~/.omp/agent/extensions/analisa-pasar/index.ts
 *
 * Syarat app:
 *   Agent Bridge ON → http://127.0.0.1:8765  +  ws://127.0.0.1:8765/ws/signals
 *
 * Format: export default function (pi) — wajib di omp (bukan export const tools).
 */
import type { ExtensionAPI } from "@oh-my-pi/pi-coding-agent";

type Signal = {
  ts: number;
  exchange: string;
  symbol: string;
  quote: string;
  mode: string;
  action: string;
  confidence: number;
  price: number;
  tp1?: number;
  tp2?: number;
  sl?: number;
  reason: string;
  type?: string;
};

const BRIDGE_HTTP = "http://127.0.0.1:8765";
const BRIDGE_WS = "ws://127.0.0.1:8765/ws/signals";

let wsClient: WebSocket | null = null;
const lastSignals: Signal[] = [];

function pushSignal(sig: Signal) {
  lastSignals.push(sig);
  if (lastSignals.length > 100) lastSignals.shift();
}

function connectWs(logger?: { info?: (m: string, ...a: unknown[]) => void; debug?: (m: string, ...a: unknown[]) => void }) {
  if (wsClient && wsClient.readyState === WebSocket.OPEN) return wsClient;
  try {
    wsClient = new WebSocket(BRIDGE_WS);
    wsClient.onmessage = (ev) => {
      try {
        const sig = JSON.parse(String(ev.data)) as Signal;
        pushSignal(sig);
        logger?.debug?.(
          `[analisa-pasar] ${sig.symbol} ${sig.action} ${sig.confidence}% @ ${sig.price}`
        );
      } catch {
        /* frame non-JSON (ping app-level) diabaikan */
      }
    };
    wsClient.onerror = () => {
      wsClient = null;
    };
    wsClient.onclose = () => {
      wsClient = null;
      setTimeout(() => connectWs(logger), 3000);
    };
  } catch {
    wsClient = null;
  }
  return wsClient;
}

async function fetchLatest(): Promise<
  | { source: string; signal: Signal; summary: string; actionable: boolean }
  | { error: string }
> {
  if (lastSignals.length > 0) {
    const s = lastSignals[lastSignals.length - 1];
    return {
      source: "websocket_push",
      signal: s,
      summary: `${s.symbol} ${s.action} ${s.confidence}% @ ${s.price} — ${s.reason}`,
      actionable: s.confidence >= 70 && s.action !== "HOLD",
    };
  }
  try {
    const res = await fetch(`${BRIDGE_HTTP}/signals/latest`, {
      signal: AbortSignal.timeout(3000),
    });
    if (!res.ok) return { error: "No signal yet (bridge ON tapi belum ada EMIT non-HOLD)" };
    const data = (await res.json()) as Signal;
    pushSignal(data);
    return {
      source: "http_polling",
      signal: data,
      summary: `${data.symbol} ${data.action} ${data.confidence}% @ ${data.price} — ${data.reason}`,
      actionable: data.confidence >= 70 && data.action !== "HOLD",
    };
  } catch (e) {
    return {
      error: `Bridge mati / tidak reachable di ${BRIDGE_HTTP}. App analisa-pasar jalan + sakelar Agent Bridge ON? ${e}`,
    };
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Harga + biaya + pemantauan posisi
//
// Semua tarif di bawah adalah cermin `agu.analys.config.FeeSchedule` (sumber
// kebenaran app), jadi target harga di sini identik dengan mesin simulasi.
// ─────────────────────────────────────────────────────────────────────────────

/** Host harga Tokocrypto: type 1 (mayoritas) → tokocrypto.site, type lain → cloudme. */
const PRICE_HOSTS = [
  "https://www.tokocrypto.site/api/v3/ticker/price",
  "https://cloudme-toko.2meta.app/api/v1/ticker/price",
];
/** Host type-3 mengembalikan `time`; buang kalau sudah basi (pernah jumpa 4 bulan). */
const PRICE_STALE_MS = 10 * 60 * 1000;

/** Quote yang dikenali, terpanjang dulu supaya `BTCUSDT` tidak terbaca quote `U`. */
const QUOTE_ASSETS = ["USDT", "USDC", "USD1", "BUSD", "IDR", "USD", "BNB", "BTC", "ETH", "SOL", "U"];
/** Quote yang biayanya mengikuti tarif USDT (maker = taker). */
const USDT_LIKE: Record<string, true> = { USDT: true, USDC: true, BUSD: true, USD: true, USD1: true, U: true };

type SymbolParts = { base: string; quote: string; pair: string; display: string };
type FeeRates = { buy: number; sell: number; quote: string; kind: "USDT" | "IDR" };

function splitSymbol(symbol: string): SymbolParts {
  const clean = symbol.trim().toUpperCase().replace(/[^A-Z0-9]/g, "");
  for (const quote of QUOTE_ASSETS) {
    if (clean.length > quote.length && clean.endsWith(quote)) {
      const base = clean.slice(0, -quote.length);
      return { base, quote, pair: clean, display: `${base}_${quote}` };
    }
  }
  throw new Error(`Simbol tidak dikenali: ${symbol} (butuh format BASEQUOTE, mis. BTCUSDT)`);
}

/** Tarif all-in per sisi (pecahan), cermin `FeeSchedule`. */
function feesFor(exchange: string, quote: string): FeeRates {
  const ex = exchange.trim().toUpperCase();
  const q = quote.toUpperCase();
  if (ex === "INDODAX") return { buy: 0.0021, sell: 0.0042, quote: q, kind: "IDR" };
  if (USDT_LIKE[q]) return { buy: 0.004044, sell: 0.004044, quote: q, kind: "USDT" };
  // Tokocrypto IDR (taker): beli bebas PPh, jual kena PPh 0,21% + ICEx.
  return { buy: 0.002222, sell: 0.004322, quote: q, kind: "IDR" };
}

const priceHostCache = new Map<string, string>();

/**
 * Harga pasar mentah terakhir untuk pair apa pun.
 *
 * Router tipe market tidak tersedia lewat API publik, jadi host type-1 dicoba
 * dulu lalu host type-3; host yang berhasil di-cache per pair.
 */
async function fetchPrice(symbol: string, timeoutMs = 8000): Promise<{ price: number; source: string } | null> {
  const { pair } = splitSymbol(symbol);
  const cached = priceHostCache.get(pair);
  const hosts = cached ? [cached, ...PRICE_HOSTS.filter((h) => h !== cached)] : PRICE_HOSTS;
  for (const host of hosts) {
    try {
      const res = await fetch(`${host}?symbol=${pair}`, { signal: AbortSignal.timeout(timeoutMs) });
      const body = (await res.json()) as { price?: string; code?: number; time?: number };
      if (body.code) continue; // -1121: pair tidak ada di host ini, coba host berikutnya
      const price = Number(body.price);
      if (!Number.isFinite(price) || price <= 0) continue;
      if (body.time && Date.now() - body.time > PRICE_STALE_MS) continue;
      priceHostCache.set(pair, host);
      return { price, source: host };
    } catch {
      /* host mati / timeout: lanjut */
    }
  }
  return null;
}

type OrderResult = {
  success: boolean;
  message?: string;
  orderId?: string;
  price?: number;
  quantity?: number;
  status?: string;
  executedQty?: number;
  isSimulation?: boolean;
  symbol?: string;
};

type OrderArgs = {
  side: "BUY" | "SELL";
  symbol: string;
  quantity?: number;
  quoteAmount?: number;
  price?: number;
  autoLimitTp1?: number;
  autoLimitTp2?: number;
  isSimulation: boolean;
  exchange: string;
};

/**
 * Kirim order ke bridge.
 *
 * `side` wajib ada — bridge men-deserialize `AgentTradeRequest` dan menolak
 * payload tanpa field itu. `price` sengaja dikirim eksplisit: kalau 0, bridge
 * memakai cache harga app yang bisa tertinggal jauh dari ticker, sehingga fill
 * terjadi di harga yang tidak pernah kita lihat.
 */
async function placeOrder(args: OrderArgs, timeoutMs = 25000): Promise<OrderResult> {
  const path = args.side === "BUY" ? "buy" : "sell";
  const body = {
    side: args.side,
    symbol: args.symbol,
    ...(args.quantity != null ? { quantity: args.quantity } : {}),
    ...(args.quoteAmount != null ? { quoteAmount: args.quoteAmount } : {}),
    price: args.price ?? 0,
    ...(args.autoLimitTp1 != null ? { autoLimitTp1: args.autoLimitTp1 } : {}),
    ...(args.autoLimitTp2 != null ? { autoLimitTp2: args.autoLimitTp2 } : {}),
    isSimulation: args.isSimulation,
    exchange: args.exchange,
  };
  try {
    const res = await fetch(`${BRIDGE_HTTP}/api/v1/trading/${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    });
    return (await res.json()) as OrderResult;
  } catch (e) {
    return { success: false, message: `Gagal kirim order ${args.side}: ${e}` };
  }
}

type WatchOpts = {
  symbol: string;
  quantity: number;
  /** Harga pasar mentah saat entry (belum termasuk slippage). */
  entryPrice: number;
  /** Target profit net setelah semua biaya, dalam persen dari biaya pokok. */
  targetNetPct: number;
  /** Cut loss otomatis; kosong/0 = mati. */
  stopLossPct?: number;
  /** Asumsi slippage per sisi (%), selaras `DEFAULT_SLIPPAGE_PCT` app. */
  slippagePct?: number;
  pollMs?: number;
  maxWaitSec?: number;
  isSimulation: boolean;
  exchange: string;
  onTick?: (tick: { elapsedSec: number; price: number; pnlPct: number; targetPrice: number; stopPrice: number | null }) => void;
  /** Batalkan pemantauan (posisi dibiarkan terbuka, hasil `status: "open"`). */
  signal?: AbortSignal;
};

type WatchResult = {
  status: "closed" | "open" | "error";
  symbol: string;
  quantity: number;
  entryPrice: number;
  costBasis: number;
  fees: FeeRates;
  targetPrice: number;
  stopPrice: number | null;
  ticks: number;
  elapsedSec: number;
  lastPrice: number | null;
  lastPnlPct: number | null;
  priceSource: string | null;
  sell?: OrderResult;
  realized?: { netQuote: number; pnl: number; pnlPct: number; exitPrice: number };
  message: string;
};

/** Ambil qty yang benar-benar tersedia dari pesan penolakan dust. */
function availableFromMessage(message: string | undefined): number | null {
  if (!message) return null;
  const m = /Tersedia:\s*([0-9.eE+-]+)/.exec(message);
  if (!m) return null;
  const v = Number(m[1]);
  return Number.isFinite(v) && v > 0 ? v : null;
}

/**
 * Pantau posisi sampai target net (atau stop loss) tercapai, lalu SELL market.
 *
 * `maxWaitSec` membatasi satu panggilan; kalau waktu habis posisi dibiarkan
 * terbuka dan hasilnya `status: "open"` lengkap dengan qty + harga entry supaya
 * panggilan berikutnya bisa melanjutkan tanpa menghitung ulang biaya.
 */
async function watchPosition(opts: WatchOpts): Promise<WatchResult> {
  const parts = splitSymbol(opts.symbol);
  const fees = feesFor(opts.exchange, parts.quote);
  const slip = Math.max(0, (opts.slippagePct ?? 0.08) / 100);
  const targetPct = opts.targetNetPct;
  const stopPct = opts.stopLossPct ?? 0;
  const pollMs = Math.max(2000, opts.pollMs ?? 15000);
  const maxWaitMs = Math.max(5000, (opts.maxWaitSec ?? 180) * 1000);

  let qty = opts.quantity;
  const entryPrice = opts.entryPrice;
  const costBasis = qty * entryPrice * (1 + slip) * (1 + fees.buy);
  const netPerUnitAt = (px: number) => px * (1 - slip) * (1 - fees.sell);
  const priceForNet = (net: number) => net / (qty * (1 - slip) * (1 - fees.sell));
  const targetPrice = priceForNet(costBasis * (1 + targetPct / 100));
  // Stop diukur dari HARGA, bukan dari basis net: posisi yang baru dibuka sudah
  // −(fee beli + fee jual + 2×slippage) di basis net, jadi stop berbasis basis
  // akan langsung terpicu di detik nol (stop > harga entry).
  const stopPrice = stopPct > 0 ? entryPrice * (1 - stopPct / 100) : null;

  const t0 = Date.now();
  let ticks = 0;
  let lastPrice: number | null = null;
  let lastPnlPct: number | null = null;
  let lastSource: string | null = null;
  let missStreak = 0;

  const { promise: aborted, resolve: onAbort } = Promise.withResolvers<void>();
  if (opts.signal?.aborted) onAbort();
  else opts.signal?.addEventListener("abort", onAbort, { once: true });
  // Bun.sleep tidak bisa dibatalkan; race dengan abort supaya tool berhenti saat user minta.
  const pause = () => Promise.race([Bun.sleep(pollMs).then(() => undefined), aborted]);
  const elapsedSec = () => Math.round((Date.now() - t0) / 1000);
  const snapshot = () => ({
    symbol: parts.display, quantity: qty, entryPrice, costBasis, fees,
    targetPrice, stopPrice, ticks, elapsedSec: elapsedSec(),
    lastPrice, lastPnlPct, priceSource: lastSource,
  });

  while (Date.now() - t0 < maxWaitMs && !opts.signal?.aborted) {
    const q = await fetchPrice(opts.symbol);
    if (!q) {
      if (++missStreak >= 4) {
        return {
          ...snapshot(),
          status: "error",
          message: "Harga tidak tersedia dari kedua host Tokocrypto; posisi TIDAK ditutup.",
        };
      }
      await pause();
      continue;
    }
    missStreak = 0;
    ticks += 1;
    lastPrice = q.price;
    lastSource = q.source;
    const pnlPct = ((qty * netPerUnitAt(q.price) - costBasis) / costBasis) * 100;
    lastPnlPct = pnlPct;
    opts.onTick?.({ elapsedSec: elapsedSec(), price: q.price, pnlPct, targetPrice, stopPrice });

    const takeProfit = pnlPct >= targetPct;
    const cutLoss = stopPrice != null && q.price <= stopPrice;
    if (takeProfit || cutLoss) {
      const reason = takeProfit ? "TARGET" : "STOPLOSS";
      let sell: OrderResult | null = null;
      for (let attempt = 0; attempt < 3; attempt++) {
        // Kirim harga ticker yang baru dibaca: `price: 0` akan membuat bridge
        // memakai cache harga app yang bisa tertinggal ratusan poin.
        const fresh = attempt === 0 ? q.price : ((await fetchPrice(opts.symbol))?.price ?? q.price);
        sell = await placeOrder({
          side: "SELL", symbol: parts.display, quantity: qty, price: fresh,
          isSimulation: opts.isSimulation, exchange: opts.exchange,
        });
        if (sell.success) break;
        const available = availableFromMessage(sell.message);
        if (available == null) break;
        qty = Number((available * 0.999999).toPrecision(12));
        if (!(qty > 0)) break;
      }
      if (!sell || !sell.success) {
        return {
          ...snapshot(),
          status: "error",
          sell: sell ?? undefined,
          message: `${reason} kena tapi SELL gagal: ${sell?.message ?? "tanpa respons"}`,
        };
      }
      const exitRaw = Number(sell.price ?? q.price);
      const netQuote = qty * netPerUnitAt(exitRaw);
      const pnl = netQuote - costBasis;
      const pnlPctRealized = costBasis > 0 ? (pnl / costBasis) * 100 : 0;
      return {
        ...snapshot(),
        status: "closed",
        sell,
        realized: { netQuote, pnl, pnlPct: pnlPctRealized, exitPrice: exitRaw },
        message: `${reason} tercapai (${pnlPct.toFixed(3)}% net @ ${q.price}) → SELL ${sell.status ?? ""} @ ${exitRaw} → PnL ${pnl.toFixed(5)} ${fees.quote} (${pnlPctRealized.toFixed(3)}%)`,
      };
    }

    await pause();
  }

  return {
    ...snapshot(),
    status: "open",
    message: opts.signal?.aborted
      ? `Pemantauan dibatalkan; posisi masih terbuka. Butuh harga ${targetPrice} untuk profit ${targetPct}% net.`
      : `Waktu tunggu habis; posisi masih terbuka. Butuh harga ${targetPrice} untuk profit ${targetPct}% net.`,
  };
}

function textResult(payload: unknown) {
  return {
    content: [{ type: "text" as const, text: JSON.stringify(payload, null, 2) }],
    details: payload,
  };
}

export default function analisaPasarBridge(pi: ExtensionAPI) {
  const z = pi.zod;

  connectWs(pi.logger);

  pi.on("session_start", async (_event, ctx) => {
    connectWs(pi.logger);
    ctx.ui.notify("analisa-pasar bridge loaded (WS :8765)", "info");
  });

  pi.registerTool({
    name: "get_latest_signal",
    label: "Latest signal",
    description:
      "Ambil sinyal terakhir dari analisa-pasar: cache WebSocket dulu, fallback HTTP /signals/latest",
    parameters: z.object({}),
    async execute(_toolCallId, _params) {
      return textResult(await fetchLatest());
    },
  });

  pi.registerTool({
    name: "subscribe_signals",
    label: "Subscribe signals",
    description:
      "Tunggu push WebSocket beberapa detik, kembalikan sinyal yang masuk di jendela itu (plus cache)",
    parameters: z.object({
      seconds: z
        .number()
        .min(3)
        .max(60)
        .default(15)
        .describe("Durasi tunggu detik (3–60)"),
    }),
    async execute(_toolCallId, params) {
      const seconds = params.seconds ?? 15;
      connectWs(pi.logger);
      const before = lastSignals.length;
      await new Promise((r) => setTimeout(r, seconds * 1000));
      const fresh = lastSignals.slice(before);
      return textResult({
        waited_seconds: seconds,
        ws_open: wsClient?.readyState === WebSocket.OPEN,
        new_count: fresh.length,
        cache_total: lastSignals.length,
        signals: fresh.length > 0 ? fresh : lastSignals.slice(-5),
        note:
          fresh.length === 0
            ? "Tidak ada push baru. Engine app harus EMIT (non-HOLD) saat jendela ini."
            : undefined,
      });
    },
  });

  pi.registerTool({
    name: "get_signal_history",
    label: "Signal history",
    description: "Riwayat sinyal dari HTTP /signals/history",
    parameters: z.object({
      limit: z.number().min(1).max(100).default(20).describe("Jumlah maksimal"),
    }),
    async execute(_toolCallId, params) {
      const limit = params.limit ?? 20;
      try {
        const res = await fetch(`${BRIDGE_HTTP}/signals/history?limit=${limit}`, {
          signal: AbortSignal.timeout(5000),
        });
        const data = await res.json();
        return textResult({
          count: Array.isArray(data) ? data.length : 0,
          signals: data,
        });
      } catch (e) {
        return textResult({ error: `Gagal history: ${e}` });
      }
    },
  });

  pi.registerTool({
    name: "get_portfolio",
    label: "Portfolio",
    description: "Portofolio dari HTTP /portfolio bridge analisa-pasar",
    parameters: z.object({}),
    async execute() {
      try {
        const res = await fetch(`${BRIDGE_HTTP}/portfolio`, {
          signal: AbortSignal.timeout(5000),
        });
        if (!res.ok) return textResult({ error: "Belum ada data portofolio" });
        return textResult(await res.json());
      } catch (e) {
        return textResult({ error: `Gagal portfolio: ${e}` });
      }
    },
  });

  pi.registerTool({
    name: "bridge_health",
    label: "Bridge health",
    description: "Cek GET /health Agent Bridge V2 di localhost:8765",
    parameters: z.object({}),
    async execute() {
      try {
        const res = await fetch(`${BRIDGE_HTTP}/health`, {
          signal: AbortSignal.timeout(3000),
        });
        const body = await res.text();
        return textResult({
          ok: res.ok,
          status: res.status,
          body,
          ws_open: wsClient?.readyState === WebSocket.OPEN,
          cache_signals: lastSignals.length,
        });
      } catch (e) {
        return textResult({ ok: false, error: String(e) });
      }
    },
  });

  pi.registerTool({
    name: "write_research_log",
    label: "Write research log",
    description: "Tulis log riset Markdown (bukan eksekusi order)",
    parameters: z.object({
      symbol: z.string(),
      analysis: z.string(),
      decision: z.enum(["BUY", "SELL", "HOLD", "WAIT"]),
    }),
    async execute(_toolCallId, params) {
      const { symbol, analysis, decision } = params;
      try {
        const fs = await import("node:fs/promises");
        const home = process.env.HOME || "/data/data/com.termux/files/home";
        const dir = `${home}/storage/shared/analisa-pasar/research`;
        await fs.mkdir(dir, { recursive: true });
        const path = `${dir}/${symbol}_${Date.now()}.md`;
        await fs.writeFile(
          path,
          `# ${symbol} — ${new Date().toISOString()}\n\nDecision: **${decision}**\n\n${analysis}\n`
        );
        return textResult({ saved: path });
      } catch (e) {
        return textResult({
          error: `Gagal tulis log (izin storage Termux?). ${e}`,
        });
      }
    },
  });

  // --- TRADING TOOLS (Agent-Assisted Trading) ---

  pi.registerTool({
    name: "get_bridge_trading_status",
    label: "Bridge trading status",
    description: "Cek kesiapan trading bridge (status Mode Real, sesi PIN, saldo, dan koneksi bursa)",
    parameters: z.object({}),
    async execute() {
      try {
        const res = await fetch(`${BRIDGE_HTTP}/api/v1/trading/status`, {
          signal: AbortSignal.timeout(4000),
        });
        const data = await res.json();
        return textResult(data);
      } catch (e) {
        return textResult({ ok: false, error: `Gagal membaca status trading bridge: ${e}` });
      }
    },
  });

  pi.registerTool({
    name: "place_buy_order",
    label: "Place buy order",
    description:
      "Kirim order BELI ke Analisa Pasar (Tokocrypto/Paper) atas instruksi pengguna terkonfirmasi. Format: quoteAmount (nominal USDT/IDR) atau quantity.",
    parameters: z.object({
      symbol: z.string().describe("Simbol pair, contoh: BTCUSDT, ETHUSDT, BTCIDR"),
      quoteAmount: z.number().optional().describe("Nominal beli dalam quote currency (contoh: 15 USDT atau 100000 IDR)"),
      quantity: z.number().optional().describe("Kuantitas koin jika tidak menentukan quoteAmount"),
      price: z.number().optional().describe("Harga acuan; kosong = ticker Tokocrypto terbaru (bukan cache app)"),
      autoLimitTp1: z.number().optional().describe("Harga Take Profit 1 opsional"),
      autoLimitTp2: z.number().optional().describe("Harga Take Profit 2 opsional"),
      isSimulation: z.boolean().optional().default(false).describe("True untuk simulasi paper trading, False untuk akun riil"),
      exchange: z.string().optional().default("TOKOCRYPTO").describe("Bursa target (default: TOKOCRYPTO)"),
    }),
    async execute(_toolCallId, params) {
      const price = params.price && params.price > 0 ? params.price : (await fetchPrice(params.symbol))?.price;
      const data = await placeOrder({
        side: "BUY",
        symbol: params.symbol,
        quoteAmount: params.quoteAmount,
        quantity: params.quantity,
        price,
        autoLimitTp1: params.autoLimitTp1,
        autoLimitTp2: params.autoLimitTp2,
        isSimulation: params.isSimulation ?? false,
        exchange: params.exchange ?? "TOKOCRYPTO",
      });
      return textResult(data);
    },
  });

  pi.registerTool({
    name: "place_sell_order",
    label: "Place sell order",
    description:
      "Kirim order JUAL ke Analisa Pasar (Tokocrypto/Paper) atas instruksi pengguna terkonfirmasi. Tokocrypto SELL menggunakan MARKET.",
    parameters: z.object({
      symbol: z.string().describe("Simbol pair, contoh: BTCUSDT, ETHUSDT, BTCIDR"),
      quantity: z.number().describe("Jumlah aset koin yang ingin dijual"),
      price: z.number().optional().describe("Harga acuan; kosong = ticker Tokocrypto terbaru (bukan cache app)"),
      isSimulation: z.boolean().optional().default(false).describe("True untuk simulasi paper trading, False untuk akun riil"),
      exchange: z.string().optional().default("TOKOCRYPTO").describe("Bursa target (default: TOKOCRYPTO)"),
    }),
    async execute(_toolCallId, params) {
      const price = params.price && params.price > 0 ? params.price : (await fetchPrice(params.symbol))?.price;
      const data = await placeOrder({
        side: "SELL",
        symbol: params.symbol,
        quantity: params.quantity,
        price,
        isSimulation: params.isSimulation ?? false,
        exchange: params.exchange ?? "TOKOCRYPTO",
      });
      return textResult(data);
    },
  });

  pi.registerTool({
    name: "cancel_order",
    label: "Cancel order",
    description: "Batalkan open order di Analisa Pasar (Tokocrypto/Paper)",
    parameters: z.object({
      symbol: z.string().describe("Simbol pair, contoh: BTCUSDT"),
      orderId: z.string().describe("Order ID yang ingin dibatalkan"),
      isSimulation: z.boolean().optional().default(false).describe("True jika order paper trading"),
      exchange: z.string().optional().default("TOKOCRYPTO").describe("Bursa target (default: TOKOCRYPTO)"),
    }),
    async execute(_toolCallId, params) {
      try {
        const res = await fetch(`${BRIDGE_HTTP}/api/v1/trading/cancel`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(params),
          signal: AbortSignal.timeout(15000),
        });
        const data = await res.json();
        return textResult(data);
      } catch (e) {
        return textResult({
          success: false,
          message: `Gagal membatalkan order di bridge: ${e}`,
        });
      }
    },
  });

  pi.registerTool({
    name: "get_order_status",
    label: "Get order status",
    description: "Cek detail dan status order berdasarkan order ID dari Tokocrypto/Paper",
    parameters: z.object({
      orderId: z.string().describe("Order ID yang ingin dicek"),
      symbol: z.string().optional().describe("Simbol pair (disarankan untuk Tokocrypto)"),
      isSimulation: z.boolean().optional().default(false).describe("True jika order paper trading"),
      exchange: z.string().optional().default("TOKOCRYPTO").describe("Bursa target (default: TOKOCRYPTO)"),
    }),
    async execute(_toolCallId, params) {
      try {
        const q = new URLSearchParams({
          symbol: params.symbol || "",
          isSimulation: String(params.isSimulation ?? false),
          exchange: params.exchange || "TOKOCRYPTO",
        });
        const res = await fetch(`${BRIDGE_HTTP}/api/v1/trading/orders/${encodeURIComponent(params.orderId)}?${q}`, {
          signal: AbortSignal.timeout(10000),
        });
        const data = await res.json();
        return textResult(data);
      } catch (e) {
        return textResult({
          success: false,
          message: `Gagal membaca status order dari bridge: ${e}`,
        });
      }
    },
  });

  const tickUpdate = (symbol: string, targetNetPct: number) => (t: { price: number; pnlPct: number; targetPrice: number }) => ({
    content: [
      {
        type: "text" as const,
        text: `${splitSymbol(symbol).display} ${t.price} → net ${t.pnlPct.toFixed(3)}% (target ${targetNetPct}% @ ${t.targetPrice.toFixed(8)})`,
      },
    ],
  });

  pi.registerTool({
    name: "watch_paper_position",
    label: "Watch paper position",
    description:
      "Pantau posisi paper sampai profit net target (atau stop loss) tercapai, lalu tutup dengan SELL market. " +
      "Biaya pakai FeeSchedule app + slippage 0.08%/sisi. Terbatas waktu: kalau `status:\"open\"` kembalikan " +
      "quantity/entryPrice yang sama ke panggilan berikutnya untuk melanjutkan.",
    parameters: z.object({
      symbol: z.string().describe("Simbol pair, contoh: BTCUSDT, ETHUSDT, BTCIDR"),
      quantity: z.number().positive().describe("Kuantitas koin yang dipegang (dari hasil place_buy_order)"),
      entryPrice: z
        .number()
        .positive()
        .optional()
        .describe("Harga pasar mentah saat entry; kalau kosong dipakai harga pasar sekarang"),
      targetNetPct: z.number().default(0.33).describe("Target profit net (%) setelah fee + slippage"),
      stopLossPct: z.number().optional().describe("Cut loss otomatis dalam % net; kosong = tanpa stop"),
      pollMs: z.number().min(2000).default(15000).describe("Jarak antar cek harga (ms)"),
      maxWaitSec: z.number().min(5).max(600).default(170).describe("Batas waktu satu panggilan (detik)"),
      isSimulation: z.boolean().default(true).describe("True untuk paper trading"),
      exchange: z.string().default("TOKOCRYPTO").describe("Bursa target (default: TOKOCRYPTO)"),
    }),
    async execute(_toolCallId, params, signal, onUpdate) {
      const entryPrice = params.entryPrice ?? (await fetchPrice(params.symbol))?.price;
      if (!entryPrice) {
        return textResult({
          status: "error",
          message: `Harga ${params.symbol} tidak tersedia dan entryPrice tidak diisi; posisi TIDAK dipantau.`,
        });
      }
      const result = await watchPosition({
        symbol: params.symbol,
        quantity: params.quantity,
        entryPrice,
        targetNetPct: params.targetNetPct,
        stopLossPct: params.stopLossPct,
        pollMs: params.pollMs,
        maxWaitSec: params.maxWaitSec,
        isSimulation: params.isSimulation,
        exchange: params.exchange,
        signal,
        onTick: (t) => onUpdate?.(tickUpdate(params.symbol, params.targetNetPct)(t)),
      });
      return textResult(result);
    },
  });

  pi.registerTool({
    name: "buy_and_watch_paper",
    label: "Buy and watch (paper)",
    description:
      "Beli paper lalu langsung pantau sampai target net tercapai / stop loss / waktu habis. " +
      "Satu panggilan = satu siklus; kalau hasilnya `status:\"open\"` lanjutkan dengan watch_paper_position.",
    parameters: z.object({
      symbol: z.string().describe("Simbol pair, contoh: BTCUSDT, ETHUSDT, BTCIDR"),
      quoteAmount: z.number().positive().describe("Nominal beli dalam quote currency (mis. 6 USDT / 100000 IDR)"),
      targetNetPct: z.number().default(0.33).describe("Target profit net (%) setelah fee + slippage"),
      stopLossPct: z.number().optional().describe("Cut loss otomatis dalam % net; kosong = tanpa stop"),
      pollMs: z.number().min(2000).default(15000).describe("Jarak antar cek harga (ms)"),
      maxWaitSec: z.number().min(5).max(600).default(170).describe("Batas waktu satu panggilan (detik)"),
      isSimulation: z.boolean().default(true).describe("True untuk paper trading"),
      exchange: z.string().default("TOKOCRYPTO").describe("Bursa target (default: TOKOCRYPTO)"),
    }),
    async execute(_toolCallId, params, signal, onUpdate) {
      const buy = await placeOrder({
        side: "BUY",
        symbol: params.symbol,
        quoteAmount: params.quoteAmount,
        price: (await fetchPrice(params.symbol))?.price,
        isSimulation: params.isSimulation,
        exchange: params.exchange,
      });
      if (!buy.success) return textResult({ stage: "buy", buy });
      const quantity = Number(buy.quantity ?? buy.executedQty ?? 0);
      const entryPrice = Number(buy.price ?? 0) || (await fetchPrice(params.symbol))?.price;
      if (!(quantity > 0) || !entryPrice) {
        return textResult({
          stage: "buy",
          buy,
          message: "Order BUY sukses tapi quantity/harga entry tidak lengkap; posisi terbuka tanpa pemantauan.",
        });
      }
      const watch = await watchPosition({
        symbol: params.symbol,
        quantity,
        entryPrice,
        targetNetPct: params.targetNetPct,
        stopLossPct: params.stopLossPct,
        pollMs: params.pollMs,
        maxWaitSec: params.maxWaitSec,
        isSimulation: params.isSimulation,
        exchange: params.exchange,
        signal,
        onTick: (t) => onUpdate?.(tickUpdate(params.symbol, params.targetNetPct)(t)),
      });
      return textResult({ stage: "watch", buy, watch });
    },
  });
}


