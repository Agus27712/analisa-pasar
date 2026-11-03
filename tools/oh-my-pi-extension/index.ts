/**
 * oh-my-pi extension V2 - support Polling + WebSocket Push
 * Path pemasangan di Termux: ~/.config/oh-my-pi/extensions/analisa-pasar/index.ts
 */

type Signal = {
  ts: number
  exchange: string
  symbol: string
  quote: string
  mode: string
  action: string
  confidence: number
  price: number
  tp1?: number
  tp2?: number
  sl?: number
  reason: string
}

export const manifest = {
  name: "analisa-pasar-bridge-v2",
  version: "2.0.0",
  description: "Bridge to Agus27712/analisa-pasar with WebSocket push + polling fallback"
}

let wsClient: WebSocket | null = null
let lastSignals: Signal[] = []

function connectWs() {
  if (wsClient && wsClient.readyState === WebSocket.OPEN) return wsClient
  try {
    wsClient = new WebSocket("ws://127.0.0.1:8765/ws/signals")
    wsClient.onmessage = (ev) => {
      try {
        const sig = JSON.parse(ev.data as string) as Signal
        lastSignals.push(sig)
        if (lastSignals.length > 100) lastSignals.shift()
        console.log(`[analisa-pasar WS] ${sig.symbol} ${sig.confidence}% @ ${sig.price}`)
      } catch {}
    }
    wsClient.onerror = () => { wsClient = null }
    wsClient.onclose = () => { wsClient = null; setTimeout(connectWs, 3000) }
  } catch {
    wsClient = null
  }
  return wsClient
}

// Auto connect saat extension load
connectWs()

export const tools = [
  {
    name: "get_latest_signal",
    description: "Ambil sinyal terakhir - coba dari WS cache dulu, fallback ke HTTP polling",
    parameters: { type: "object", properties: {} },
    execute: async () => {
      // 1. Coba cache WebSocket (realtime)
      if (lastSignals.length > 0) {
        const s = lastSignals[lastSignals.length - 1]
        return {
          source: "websocket_push",
          signal: s,
          summary: `${s.symbol} ${s.confidence}% @ ${s.price} - ${s.reason}`,
          actionable: s.confidence >= 70
        }
      }
      // 2. Fallback polling HTTP
      try {
        const res = await fetch("http://127.0.0.1:8765/signals/latest", { signal: AbortSignal.timeout(3000) })
        if (!res.ok) return { error: "No signal yet" }
        const data = await res.json() as Signal
        return { source: "http_polling", signal: data, actionable: data.confidence >= 70 }
      } catch (e) {
        return { error: `Bridge mati atau belum aktif. Pastikan app analisa-pasar jalan & toggle ON. ${e}` }
      }
    }
  },
  {
    name: "subscribe_signals",
    description: "Subscribe live signals via WebSocket. Return sinyal baru yang masuk dalam jeda waktu waitMs (ms). Cocok buat scalping.",
    parameters: {
      type: "object",
      properties: {
        waitMs: { type: "number", default: 10000, description: "Jeda waktu tunggu sinyal masuk (ms)" }
      }
    },
    execute: async ({ waitMs }: { waitMs?: number }) => {
      connectWs()
      const startLen = lastSignals.length
      const timeoutMs = waitMs || 10000
      await new Promise(r => setTimeout(r, timeoutMs))
      const newOnes = lastSignals.slice(startLen)
      return {
        received: newOnes.length,
        signals: newOnes,
        note: newOnes.length === 0 ? "Tidak ada sinyal baru dalam window ini, coba polling history" : "Realtime push diterima"
      }
    }
  },
  {
    name: "get_signal_history",
    description: "Ambil riwayat sinyal (polling) buat analisa trend",
    parameters: {
      type: "object",
      properties: {
        limit: { type: "number", default: 20, description: "Jumlah sinyal maksimal" }
      }
    },
    execute: async ({ limit }: { limit?: number }) => {
      try {
        const res = await fetch(`http://127.0.0.1:8765/signals/history?limit=${limit || 20}`)
        const data = await res.json()
        return { count: data.length, signals: data }
      } catch (e) {
        return { error: `Gagal ambil history sinyal: ${e}` }
      }
    }
  },
  {
    name: "get_portfolio",
    description: "Ambil data portofolio dari app",
    parameters: { type: "object", properties: {} },
    execute: async () => {
      try {
        const res = await fetch("http://127.0.0.1:8765/portfolio")
        if (!res.ok) return { error: "Belum ada data portofolio" }
        return await res.json()
      } catch (e) {
        return { error: `Gagal fetch portfolio: ${e}` }
      }
    }
  },
  {
    name: "write_research_log",
    description: "Tulis hasil riset agen ke dokumen Markdown",
    parameters: {
      type: "object",
      properties: {
        symbol: { type: "string" },
        analysis: { type: "string" },
        decision: { type: "string", enum: ["BUY", "SELL", "HOLD", "WAIT"] }
      },
      required: ["symbol", "analysis", "decision"]
    },
    execute: async ({ symbol, analysis, decision }: { symbol: string, analysis: string, decision: string }) => {
      const fs = await import("fs")
      const dir = `${process.env.HOME}/storage/documents/analisa-pasar/research`
      await fs.promises.mkdir(dir, { recursive: true })
      const path = `${dir}/${symbol}_${Date.now()}.md`
      await fs.promises.writeFile(path, `# ${symbol} - ${new Date().toISOString()}\nDecision: ${decision}\n\n${analysis}\n`)
      return { saved: path }
    }
  }
]

export const systemPrompt = `
Kamu mengonsumsi sinyal dari aplikasi Android analisa-pasar.

Kamu punya 2 mode koneksi:
- PUSH (WebSocket): realtime, pakai subscribe_signals untuk menerima sinyal detik itu juga. Cocok untuk scalping.
- POLLING (HTTP): fallback, pakai get_latest_signal.

Aturan operasional:
1. Untuk scalping, prioritaskan subscribe_signals (tunggu 10 detik) untuk menangkap push realtime.
2. Skor keyakinan (confidence):
   - < 60: HOLD
   - 60 - 69: WAIT & pantau struktur
   - >= 70: Analisis mendalam dan catat ke log riset
3. Jangan pernah eksekusi transaksi uang riil tanpa konfirmasi eksplisit dari pengguna.
`
