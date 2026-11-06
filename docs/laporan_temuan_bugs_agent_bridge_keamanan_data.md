# Laporan Temuan — Audit Agent Bridge: keamanan & akurasi data ke agen (OMP)

**Ditemukan:** 2026-11-06 · **Ditutup:** 2026-11-06 · **Branch:** `main`
**Letak berkas:** `docs/laporan_temuan_bugs_agent_bridge_keamanan_data.md` (audit baru,
berbeda dari `docs/laporan_temuan_bugs_agent_bridge.md` yang hanya memuat paket force
close 2026-11-04).
**Cakupan:** `bridge/AgentHttpServer.kt` · `bridge/AgentTradingCommandHandler.kt` ·
`bridge/AgentTradingModels.kt` · `bridge/AgentOutputModels.kt` · `bridge/OmpBridgeInstaller.kt` ·
`util/AppPreferences.kt` · `viewmodel/RealTradeExecutor.kt` · `viewmodel/RealTradeCoordinator.kt` ·
`viewmodel/TradingViewModelSync.kt` · `tools/oh-my-pi-extension/index.ts`
**Batas penting:** yang diperbaiki adalah **keamanan endpoint tulis, kelengkapan data
sinyal, dan kejujuran respons order**. Tidak ada perubahan pada engine scalping, fee,
logika saldo (`finalExecutedQty`), maupun jalur order bursa.

| Status | Temuan | Jumlah |
|---|---|---|
| 🔴 Serius | A1 (endpoint tulis terbuka tanpa token; `HOST` faktual `0.0.0.0`, bukan `127.0.0.1` seperti klaim §91.8) | 1 |
| 🟡 Sedang | A2 (`TechnicalBreakdown` rsi/macd/emaTrend selalu null — sinyal membawa data MTF tetapi tidak diteruskan) · A3 (respons order memakai harga acuan + qty hasil pembagian nominal, bukan fill bursa — kasus SOXSB 2026-11-05) | 2 |
| 🔵 Rendah | A4 (ekstensi tidak melaporkan usia sinyal `/signals/latest`) | 1 |
| ✅ Sudah diperbaiki | A1–A4 | 4 butir |

> **Sisa yang belum terverifikasi:** pemasangan ulang ekstensi di HP owner (perintah
> pemasangan baru menulis `agent-bridge-token.txt`) dan panggilan order dari OMP nyata
> belum pernah dieksekusi di perangkat; uji lokal + smoke CI hanya membuktikan kode.

---

## A1 — endpoint tulis terbuka di LAN, tanpa token

**Tekanan nyata dari temuan:** perangkat owner memakai Wi-Fi publik/berbagi; `POST
/api/v1/trading/buy` dari perangkat lain di jaringan yang sama bisa memicu order uang
riil, tanpa PIN, tanpa token, cukup tahu IP HP.

1. `AgentHttpServer.HOST` faktual literal `"0.0.0.0"` — padahal `workplan2.md` §91.8
   sudah mengklaim mengikat `127.0.0.1`. `AgentTradingStatusResponse.host` juga
   memancarkan `0.0.0.0`.
2. Gerbang PIN/mode real hanya lapisan logika handler; tidak ada produksi **jaringan**.

**Perbaikan:** `HOST = "127.0.0.1"`; token `AppPreferences.agentBridgeToken` (SecureRandom
32-hex, persistent); helper `requireAgentToken` di lima endpoint tulis; fail-closed bila
token kosong; rute baca publik tetap terbuka agar smoke CI tidak terganggu. Token
diteruskan ke Termux sebagai `agent-bridge-token.txt` (`OmpBridgeInstaller`, dua
direktori) dan dibaca ekstensi (`env ANALISA_PASAR_TOKEN` atau berkas).

## A2 — `TechnicalBreakdown` tidak memuat data yang sebenarnya ada

Agen melihat `rsi:null, macd:null, emaTrend:null, volumeSpike:false` di setiap sinyal,
lalu menyimpulkan "feed indikator rusak", padahal `AISignalState.mtf`
(`ScalpingMtfSnapshot`) membawa arah tren, status konfluensi, dan setup — hanya
`orderbookPressure` yang pernah diisi. **Perbaikan:** builder murni
`AgentSignalBreakdown.build(...)` + kedua titik emit mem-pass `mtf`. `rsi`/`macd` tetap
null dengan sengaja (data memang tidak ada; null = "tidak tersedia", bukan feed rusak).
Field baru: `mtfDirection`, `mtfStatusTitle`, `mtfCheckpointsOk`, `scalpingSetup`,
`scalpingRegime`.

## A3 — respons order bukan order bursa

Kasus SOXSB (2026-11-05, paparan owner): cost basis owner **cocok matematis** dengan
model harga acuan — `6,0 × 1,0008 × 1,004044 = 6,02908` USDT — sedangkan PnL −2,954%
dihitung dari ticker 80,94 (bukan fill 80,88). Artinya `price` yang diterima agen =
harga acuan model, `executedQty` = hasil pembagian nominal, `status` = `FILLED`
karangan meskipun order belum tentu terisi. **Perbaikan:** `RealFillRecord` + `lastFill`
di semua cabang akhir `executeTrade` (ditulis **sebelum** `onResult`),
`RealTradeCoordinator.lastRealFillFor(pair, side)` berumur ≤ 120 detik, dan
`AgentTradeResponse` memuat `orderId`/`clientOrderId`/`requestedPrice` nyata dengan
status dari `realFillStatus(...)` (FILLED/PARTIALLY_FILLED/NEW/REJECTED). Catatan batas:
`OrderResult` Tokocrypto/Indodax tidak punya harga rata-rata fill — harga yang
dilaporkan tetap harga acuan (`execPrice` model app, sama yang dipakai update saldo),
tetapi kini dikirim sebagai `requestedPrice` agar agen tahu itu bukan harga fill
terverifikasi. Path simulasi memakai `order.filledAvgPrice`/`filledQuantity`.

## A4 — usia sinyal tidak dilaporkan

`fetchLatest` ekstensi tidak memberi tahu seberapa tua sinyal yang ditarik. **Perbaikan
kecil:** `age_sec` dihitung dari `ts`, ditaruh di objek hasil dan ringkasan teks
(usia negatif dicetak `?` bila jam perangkat melonjak). Tanpa policy TTL — keputusan
usia sinyal tetap di agen.

## Perbaikan

- `AgentHttpServer.kt`: `HOST` loopback; `HEADER_TOKEN`; `requireAgentToken`; gerbang di
  5 endpoint; `trading_auth` di dokumen `/`.
- `AppPreferences.kt`: `agentBridgeToken` + key `agent_bridge_token_v1`.
- `OmpBridgeInstaller.kt`: menulis `agent-bridge-token.txt` ke dua direktori.
- `AgentOutputModels.kt`: field baru + `AgentSignalBreakdown`.
- `TradingViewModelSync.kt`: `emitAgentSignalIfEnabled` menerima `mtf`, `scalpingSetup`,
  `scalpingRegime`; dua titik emit mem-pass snapshot.
- `RealTradeExecutor.kt`: `RealFillRecord` + `recordFill` di cabang NEW / FILLED /
  PARTIALLY / sukses generik / REJECTED.
- `RealTradeCoordinator.kt`: `lastRealFillFor` + batas umum 120 detik.
- `AgentTradingCommandHandler.kt`: `realFillStatus`; `handleBuy`/`handleSell` dari
  record fill; simulasi memakai harga/qty fill mesin simulasi.
- `AgentTradingModels.kt`: field `requestedPrice: Double?`.
- `tools/oh-my-pi-extension/index.ts`: pembaca token (berkas/env), header
  `X-Agent-Token` di 4 panggilan `api/v1/trading/*`, pesan 401 yang menjelaskan solusi,
  `age_sec` di `fetchLatest`.

## Bukti

- Baseline sebelum perubahan (diukur dari `app/build/test-results/testDebugUnitTest/*.xml`,
  2026-11-06): **13 test bridge, 0 gagal** (AgentBridgeServerTest 3, AgentBridgeTest 3,
  AgentTradingTest 5, OmpBridgeInstallerTest 2).
- Setelah perubahan: **24 test bridge, 0 gagal** — AgentSignalBreakdownTest 4 (baru),
  AgentTradingTest 10 (dari 5), AgentBridgeTest 3, OmpBridgeInstallerTest 2 (tetap),
  AgentBridgeServerTest 5 (dari 3).
- Keputusan penyimpanan token: token bridge **tidak** memakai `EncryptedSharedPreferences`
  — stabilitas lintas restart lebih penting untuk token ini (kegagalan AndroidKeyStore
  menghapus seluruh prefs utama, token akan "hilang"/daur ulang); token bukan kredensial
  bursa. Penyimpanan khusus `agent_bridge_prefs_v1`.

## Riwayat

- **2026-11-06** — audit atas pertanyaan owner "apa yang dikirim app ke agen"; owner
  memilih nomor 1–3 untuk diperbaiki; A1–A4 dikerjakan di sesi yang sama.
- **Kontrol negatif (terukur, run `gradle-test4`):** tiga perbaikan dicabut secara
  berurutan → tiga uji merah persis: (1) gerbang token `/buy` dilepas →
  `AgentBridgeServerTest.endpoint tulis menuntut X-Agent-Token...` merah; (2) `mtf`
  tidak di-pass di titik emit 1 → `AgentTradingTest.sumber emit sinyal...` merah;
  (3) `recordFill` cabang REJECTED dilepas → `AgentTradingTest.sumber executor...`
  merah. Catatan: penjaga executor dulu kurang sensitif (deklarasi `private fun
  recordFill(` ikut terhitung) sampai kontrol gagal memeriksa; diperketat ke
  `recordFill(pair` ≥ 4 panggilan, kontrol diulang dan merah, lalu dikembalikan dan
  suite hijau penuh (24/24). Ini pola yang benar: kontrol yang *lolos malah fail* itu
  protokol kontrol, bukan kegagalan perbaikan.
- **Uji jalan lokal PC** (`./gradlew :app:testDebugUnitTest --tests
  "agu.analys.bridge.*"`, JDK 17, gradle 9.3.1) — angka dilaporkan dari XML, bukan dari
  pesan. Verifikasi sisa (ekstensi di HP owner, order nyata dari OMP) masih terbuka.
