# Laporan Temuan — Kode Aplikasi yang Memperbanyak 429 Tokocrypto

**Tanggal:** 2026-11-19
**Pemicu:** host data `www.tokocrypto.site` membalas 429 sampai aplikasi buta
(`reason: "Menunggu data MTF scalping (H1 0/20 · M15 0/20 · M5 0/20 · M1 n/20)"`,
`market_scan.json` beku). Pertanyaan owner: **kode app mana** yang ikut menyebabkan 429?
**Jawaban singkat:** bukan satu baris, tetapi **enam pengganda** di jalur baca pasar. Tidak ada
satu pun yang memakai Binance.

## 1. Yang benar (tidak perlu diubah)

Pencarian menyeluruh atas `app/src/main` untuk `binance`, `binance.com`, `binance.vision`,
`api.binance` **tidak menemukan satu pun rujukan runtime**. Seluruh sumber data pasar adalah:

| Sumber | Host | Pemakaian |
|---|---|---|
| Data pasar Tokocrypto Type 1 | `https://www.tokocrypto.site/api/v3` | ticker, kline, depth, trades |
| Data pasar Tokocrypto Type 3 | `https://cloudme-toko.2meta.app/api/v1` | ticker, kline (simbol NextMe) |
| API resmi Tokocrypto | `https://www.tokocrypto.com/open/v1/...` | daftar simbol, time, kline/trades cadangan |
| Indodax | `https://indodax.com` | ticker, depth, candles, trading |
| WebSocket | `wss://stream-cloud.tokocrypto.site/stream`, `wss://stream-toko.2meta.app` | ticker/depth/trade live |

`BINANCE` hanya muncul di `PaperRehydrateScope` sebagai **string bursa** dari penyimpanan lama
(partisi posisi), bukan sebagai alamat jaringan. Aturan "jangan hidupkan Binance di path produksi"
sudah dipatuhi.

## 2. Enam pengganda 429

Semua ada di jalur baca gateway yang sama. Tidak satu pun menghormati `429` sebagai
"sudah cukup, berhenti".

### K1 — `429` diulang, bukan dihormati

`TokocryptoMarketService.get()` memperlakukan `429` sama seperti `5xx`: tidur 200–400 ms lalu
**mencoba lagi**. Kuota yang sudah habis tidak pulih dalam 400 ms, jadi percobaan kedua hampir
pasti gagal juga — dan menambah satu permintaan ke hitungan host.

**Perbaikan:** `429` tidak diulang. Balasan itu membuka jeda global lewat `TokocryptoRateGate`
(durasi dari header `Retry-After`, bawaan 60 s, plafon 5 menit). Selama jeda, `get()` mengembalikan
`null` **tanpa membuka koneksi**.

### K2 — Setiap kegagalan menjadi 3–4 permintaan

`getWithFallback()` menembak URL utama, lalu 2–3 URL cadangan, semuanya ke gateway yang sama.
Satu kegagalan = 4 permintaan beruntun.

**Perbaikan:** URL yang terkena gerbang dilewati selama jeda. URL host resmi
(`www.tokocrypto.com/open/v1/...`) tetap dicoba karena tidak terkena kuota yang sama.

### K3 — Ticker seluruh pasar diambil berkali-kali per menit

`GET /ticker/24hr` **tanpa** parameter `symbol` mengembalikan seluruh pasar (ratusan KB). Empat
pemanggil menariknya sendiri-sendiri, masing-masing **dua kali** (Type 1 + Type 3):

| Pemanggil | Interval |
|---|---|
| `MarketViewModel.refreshWorthCoinsFromMarket` | 30 s |
| `TradingForegroundService.startHoldingsMonitor` | **4 s** |
| `TradingViewModel.startTrailingPolling` | 10 s |
| `AgentOutputBridge.startMarketScanPush` (jalur REST) | ≤45 s |

Perkiraan beban tetap: **belasan unduhan pasar penuh per menit**, siang-malam, hanya untuk
memperbarui beberapa posisi. Inilah pengganda terbesar.

**Perbaikan:** `getFullMarketCached()` menyimpan payload per URL selama 10 s, sehingga seluruh
pemanggil berbagi satu hasil. Isi lama hanya dipakai bila umurnya < 5 menit, supaya dashboard tidak
menampilkan harga basi sebagai live. Daftar ≤5 simbol (pemantauan posisi) tidak lewat cache itu:
`fetchTickers()` mengambil ticker per-simbol, karena satu permintaan per-simbol lebih murah dan
lebih segar daripada satu payload pasar penuh.

### K4 — Evaluasi Tahap 2 di pemindaian pasar menghabiskan ~54 permintaan per siklus

`AgentMarketScanProvider` mengevaluasi 6 kandidat teratas dengan `AgentEngineAnalysisProvider`,
yaitu 1 ticker + 7 kline + 1 depth ≈ 9 permintaan per kandidat. Cache-nya hanya **20 detik**,
sementara siklus pemindaian sendiri 20 detik — jadi cache itu praktis tidak pernah terpakai dan
~54 permintaan berjalan terus-menerus.

**Perbaikan:** TTL cache evaluasi dinaikkan ke 90 s (kandidat teratas jarang berganti), dan saat
gerbang menutup, evaluasi baru dilewati sama sekali — kandidat memakai cache atau tampil dengan
`engineAnalysis = null`.

### K5 — `MtfCacheManager` mengulang dan berputar tanpa henti

`safeFetch()` mencoba simbol alternatif lalu mengulang sekali lagi setelah 200 ms, dan
`startBackgroundWorkerIfNeeded()` berputar tiap 1,5 detik untuk simbol berikutnya. Saat host
menolak, loop itu mengubah satu kegagalan menjadi kegagalan berantai tanpa jeda.

**Perbaikan:** saat gerbang menutup, prefetch berhenti per simbol, retry dilewati, dan worker
tidur 5 detik sebelum memeriksa lagi.

### K6 — Depth orderbook diminta per tick

`OrderBookDepthCache.fetchIfNeeded()` dipanggil dari jalur tick. Saat host menolak, setiap
pemanggilan tetap membuka koneksi baru.

**Perbaikan:** saat gerbang menutup, permintaan depth dilewati dan nilai cache lama dipertahankan
(pressure tidak dikosongkan hanya karena data belum bisa diperbarui).

## 3. Perubahan perilaku yang terlihat pengguna

| Keadaan | Sebelum | Sesudah |
|---|---|---|
| Host membalas 429 | diulang 2–8×, 429 makin lama | jeda 60 s, nol permintaan |
| Dashboard saat jeda | terus mencoba, harga lama/0 campur | harga cache ≤5 menit, lalu "tidak tersedia" |
| `GET /api/v1/engine/analysis` saat jeda | `available:false, price:0` (ambigu) | `available:false` dengan alasan eksplisit "jeda 429" |
| Pemindaian pasar saat jeda | 54 permintaan per siklus | 0 permintaan, kandidat dari cache |

## 4. Bukti

- `app/src/test/java/agu/analys/service/TokocryptoRateGateTest.kt` — 8 uji tanpa jaringan:
  pemetaan host, `Retry-After` detik/tanggal/plafon, `trip` tidak memperpendek jeda berjalan,
  `reset`.
- Kontrol negatif yang membuat uji gagal: mengembalikan cabang retry `429` di
  `TokocryptoMarketService.get()`, atau menghapus koersi plafon di `parseRetryAfterMs`.
- `python3 tools/check_md_text.py` → bersih.

## 5. Yang tidak dikerjakan

- Tidak ada perubahan pada jalur order (beli/jual). Gerbang hanya menyentuh jalur baca.
- Tidak ada data Binance yang ditambahkan ke runtime app. Arsip `data.binance.vision` tetap
  hanya untuk alat riset offline, sesuai `AGENTS.md`.
