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
      price: z.number().optional().default(0).describe("Harga limit (0 = otomatis pakai harga pasar terbaru)"),
      autoLimitTp1: z.number().optional().describe("Harga Take Profit 1 opsional"),
      autoLimitTp2: z.number().optional().describe("Harga Take Profit 2 opsional"),
      isSimulation: z.boolean().optional().default(false).describe("True untuk simulasi paper trading, False untuk akun riil"),
      exchange: z.string().optional().default("TOKOCRYPTO").describe("Bursa target (default: TOKOCRYPTO)"),
    }),
    async execute(_toolCallId, params) {
      try {
        const res = await fetch(`${BRIDGE_HTTP}/api/v1/trading/buy`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(params),
          signal: AbortSignal.timeout(25000),
        });
        const data = await res.json();
        return textResult(data);
      } catch (e) {
        return textResult({
          success: false,
          message: `Gagal mengirim order BUY ke bridge: ${e}`,
        });
      }
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
      price: z.number().optional().default(0).describe("Harga limit (0 = market sell)"),
      isSimulation: z.boolean().optional().default(false).describe("True untuk simulasi paper trading, False untuk akun riil"),
      exchange: z.string().optional().default("TOKOCRYPTO").describe("Bursa target (default: TOKOCRYPTO)"),
    }),
    async execute(_toolCallId, params) {
      try {
        const res = await fetch(`${BRIDGE_HTTP}/api/v1/trading/sell`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(params),
          signal: AbortSignal.timeout(25000),
        });
        const data = await res.json();
        return textResult(data);
      } catch (e) {
        return textResult({
          success: false,
          message: `Gagal mengirim order SELL ke bridge: ${e}`,
        });
      }
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
}

