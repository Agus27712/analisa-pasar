# Temuan Audit — Jalur Koneksi Tokocrypto & Indodax

**Audit:** 2026-10-17 · **Branch:** `patch` · **Sifat:** baca kode + uji endpoint langsung
**Status:** K5 dan K6 sudah diperbaiki (token utuh, kanal `market:summary-24h`, parser baru);
K1–K4 dan K7–K15 masih terbuka.
**Pemicu:** pair **`UVXYBUSDT`** muncul di aplikasi padahal **tidak ada di Tokocrypto**; WebSocket Indodax
sering terputus sehingga semua data jatuh ke REST.
**Catatan owner:** order beli/jual dan saldo riil di **Indodax** dan **Tokocrypto** sudah diuji dan
**berfungsi**. Jadi temuan di bawah bukan soal eksekusi order atau saldo — yang bermasalah adalah
**sumber daftar pair** dan **sumber data pasar**.

| Status | Temuan | Jumlah |
|---|---|---|
| 🔴 Kritis | K1, K2 | 2 |
| 🟠 Serius | K3, K4, K7 | 3 |
| 🟡 Sedang | K8–K14 | 7 |
| 🟢 Rendah | K15 | 1 |
| ✅ Sudah diperbaiki | K5, K6 | 2 |
| ✅ Sudah benar | order fail-closed, saldo, normalisasi pair, retry REST | — |

---

## 1. Ringkasan akar masalah

Ada dua akar yang berdiri sendiri:

1. **Daftar pair Tokocrypto diambil dari host yang salah.** Endpoint resmi
   `www.tokocrypto.com/open/v1/common/symbols` mengembalikan **864 simbol** di dalam
   `data.list`, tetapi kode membacanya sebagai `data` berbentuk **array**. Karena bentuknya tidak
   cocok, kode diam-diam pindah ke endpoint cadangan `www.tokocrypto.site/api/v3/exchangeInfo`
   yang mengembalikan **3.729 simbol** — seluruh universe Binance yang dilayani gateway itu,
   lengkap dengan `UVXYBUSDT`. Pair itu
   berstatus `TRADING` di sana, punya filter order yang valid, dan punya harga ticker. Aplikasi
   memperlakukannya sebagai pair Tokocrypto yang sah; saat order dikirim ke Tokocrypto, bursa
   menolaknya.
2. **WebSocket Indodax tidak pernah hidup karena tiga cacat berlapis.** Token di kode
   **terpotong satu karakter** (104, seharusnya 105), sehingga server menolak dengan
   `3002 invalid token` — token aslinya masih berlaku sampai 2031. Setelah token dibetulkan,
   kanal `chart:tick-<pair>` ternyata hanya di-ack dan tidak pernah menerbitkan data; kanal yang
   benar `market:summary-24h`. Parser lama juga mengharapkan bentuk baris yang berbeda dari yang
   dikirim server. Seluruh harga Indodax karena itu selalu jatuh ke REST.

Keduanya menjelaskan gejala yang dilaporkan: pair hantu muncul dengan harga yang terlihat wajar,
dan Indodax terasa "selalu putus".

---

## 2. 🔴 Kritis

### K1 — Discovery Tokocrypto tidak mem-parse respons resmi, lalu memakai daftar Binance

**Berkas:** `data/TokocryptoSymbolRepository.kt:46-50`, `:168-204`, `:184`

```kotlin
private val SYMBOLS_ENDPOINTS = listOf(
    "https://www.tokocrypto.com/open/v1/common/symbols",   // resmi
    "https://www.tokocrypto.site/api/v3/exchangeInfo",     // gateway kompatibel Binance
    "https://cloudme-toko.2meta.app/api/v1/exchangeInfo"   // proxy pihak ketiga
)
...
val data = root.optJSONArray("data") ?: root.optJSONArray("symbols")
```

Bentuk respons resmi (diukur 2026-10-17):

```json
{ "code": 0, "msg": "Success", "data": { "list": [ { "type": 1, "symbol": "BTC_USDT", ... } ] } }
```

`data` adalah **objek**, bukan array. `optJSONArray("data")` mengembalikan `null`,
`optJSONArray("symbols")` juga `null`, sehingga endpoint resmi **selalu dianggap gagal** dan kode
lanjut ke endpoint kedua.

| Sumber | Jumlah simbol | `UVXYBUSDT`? | Bukti |
|---|---|---|---|
| `tokocrypto.com/open/v1/common/symbols` (resmi) | **864** (487 USDT, 40 IDR, 237 USDC) | **tidak ada** | `data.list` |
| `tokocrypto.site/api/v3/exchangeInfo` (dipakai app) | **3.729** | **ada**, `status: TRADING`, `baseAsset: UVXYB` | universe Binance di gateway (ETHBTC, LTCBTC, …) |
| `cloudme-toko.2meta.app/api/v1/exchangeInfo` | 63 | tidak ada | proxy, endpoint ticker balas 403 |

Akibat langsung:

- `getAllTradingPairs()` dan pencarian pair menampilkan **pair Binance yang tidak ada di
  Tokocrypto**.
- `getSymbolInfo("UVXYBUSDT")` **mengembalikan metadata lengkap**, jadi validasi order lolos
  (`validateOrder` memakai filter dari gateway) dan order baru ditolak di sisi bursa.
- Harga `UVXYBUSDT` terlihat wajar karena host yang sama juga melayani ticker:

```text
GET https://www.tokocrypto.site/api/v3/ticker/24hr?symbol=UVXYBUSDT
→ lastPrice 18.82, high 19.60, low 16.18, quoteVolume …

GET https://www.tokocrypto.com/open/v1/market/ticker?symbol=UVXY_USDT
→ {"code":404,"msg":"Not Found"}
GET https://www.tokocrypto.com/open/v1/market/ticker?symbol=UVXYBUSDT
→ {"code":404,"msg":"Not Found"}
```

**Perbaikan yang diperlukan**

1. Baca `root.optJSONObject("data")?.optJSONArray("list")` **dan** `optJSONArray("symbols")`
   sebagai bentuk yang sah.
2. Jangan langsung mengganti seluruh peta simbol dengan respons endpoint mana pun (termasuk
   `exchangeInfo` gateway yang memuat universe Binance). Tambahkan pemeriksaan kewajaran sebelum
   `symbolsMap.clear()`:
   - jumlah simbol harus di rentang wajar (mis. 100–1.500 untuk Tokocrypto);
   - harus ada pair IDR;
   - tumpang tindih dengan snapshot resmi terakhir ≥ 90%.
3. Kalau endpoint resmi gagal, **pertahankan snapshot terakhir** dan tandai "data lama", jangan
   turun ke daftar yang lebih besar dan berbeda bursa.

---

### K2 — Endpoint cadangan sah untuk data pasar, tetapi tidak sah sebagai sumber daftar pair

**Berkas:** `data/TokocryptoSymbolRepository.kt:46-50`, `service/TokocryptoMarketService.kt:48-49`

Uji berpasangan (dua sampel dalam hitungan detik) menunjukkan depth `BTC_USDT` di
`tokocrypto.com/open/v1/market/depth` dan `tokocrypto.site/api/v3/depth?symbol=BTCUSDT`
**identik**:

```text
resmi   [['81598.00','5.05854'], ['81597.99','0.00368']]
gateway [['81598.00','5.05854'], ['81597.99','0.00368']]
```

Jadi host itu memang **gateway pasar Tokocrypto** (kompatibel Binance), bukan bursa lain — dan
wajar dipakai untuk ticker, kline, dan depth. Kesimpulan "host ini Binance" akan salah.

Masalahnya lebih sempit tetapi tetap kritis: `exchangeInfo` di host yang sama memuat **seluruh
universe Binance** (3.729 simbol: ETHBTC, LTCBTC, …, UVXYBUSDT), sedangkan pair yang benar-benar
diaktifkan Tokocrypto hanya **864**. `exchangeInfo` dibuat untuk kompatibilitas API, bukan untuk
menyatakan "pair ini bisa ditradingkan di Tokocrypto". Memakainya sebagai sumber daftar pair
membuat aplikasi menampilkan pair yang hidup di gateway tetapi tidak ada di bursa.

Bukti tambahan: ticker `UVXYBUSDT` hidup di gateway (18,82), sementara API resmi Tokocrypto
membalas **404** untuk `UVXY_USDT` maupun `UVXYBUSDT`.

**Perbaikan:** daftar pair hanya dari `common/symbols` (864). Gateway tetap boleh dipakai untuk
data pasar, dengan syarat simbolnya sudah lolos daftar resmi itu.

---

## 3. 🟠 Serius

### K3 — Field `symbolType` salah nama; semua simbol dianggap Type 1

**Berkas:** `data/TokocryptoSymbolRepository.kt:233`

```kotlin
val symbolType = item.optInt("symbolType", 1) // 1 = MBX, 3 = NextMe
```

Respons resmi memakai field **`type`**, bukan `symbolType`:

```text
type: {1: 847, 3: 17}   // 17 simbol Type 3, mis. ALCH_IDR, ATH_USDT, CARV_IDR, CREO_USDT, DRX_IDR
```

Karena `symbolType` tidak ada, semua simbol jatuh ke nilai default **1**. Dampaknya:

- `TokocryptoMarketWebSocket.start()` memilih host Type 1 untuk simbol Type 3 → tidak ada frame.
- `fetchTicker`/`fetchCandles`/`fetchOrderBook` menembak host Type 1 untuk simbol Type 3.
- Diuji langsung: stream Type 3 (`athusdt@miniTicker`) tidak mengirim data di host Type 1 maupun
  host Type 3; ticker Type 3 di host-nya membalas 403.

**Perbaikan:** baca `item.optInt("type", item.optInt("symbolType", 1))`, lalu tambahkan uji fixture
yang memastikan simbol Type 3 benar-benar mendapat host Type 3.

### K4 — Gate "hanya pair TRADING" gagal-terbuka pada data resmi

**Berkas:** `data/TokocryptoSymbolRepository.kt:237-238`, `:365-366`

```kotlin
val status = item.optString("status", "")
val spotTradingEnable = if (status.isNotBlank()) status == "TRADING"
                        else item.optBoolean("spotTradingEnable", true)
```

Respons resmi **tidak punya `status`**, dan `spotTradingEnable` bertipe **angka** (`1`), bukan
boolean. `optBoolean` pada nilai angka mengembalikan nilai default, yaitu **`true`**. Jadi setiap
simbol yang berhasil di-parse dari sumber resmi otomatis dianggap boleh ditradingkan.

Saat ini efeknya tertutup oleh K1 (yang dipakai justru `exchangeInfo` gateway, dan di sana ada `status`), tetapi
begitu K1 diperbaiki, gate ini akan terbuka untuk simbol yang sedang suspend.

**Perbaikan:** terima `1`/`0`, `"1"`/`"0"`, `true`/`false`, dan perlakukan field yang tidak dikenal
sebagai **tidak aktif** (fail-closed), bukan aktif.

### K5 — WebSocket Indodax mati karena token terpotong, kanal salah, dan parser salah bentuk

**Berkas:** `service/IndodaxMarketWebSocket.kt:182-191`, `:100-111`, `:113-137`

Ada tiga cacat yang harus dibetulkan bersamaan; memperbaiki satu saja tidak membuat tick mengalir.

**K5a — token terpotong satu karakter.** Konstanta di kode berakhir `…NVdsske` (104 karakter),
sedangkan token yang dikirim pengguna berakhir `…NVdsskeo` (105 karakter). Tanda tangan HS256
base64url selalu 43 karakter; versi di kode hanya 42. Payload `{"exp":1946618415}` berarti
**berlaku sampai 2031-09-08**, jadi ini bukan token kedaluwarsa.

Diuji 2026-10-17 pada `wss://ws3.indodax.com/ws/`:

```text
token 104 karakter (kode)  → 3002 {"reason":"invalid token","reconnect":false}
token 105 karakter (utuh)  → {"id":1,"result":{"client":"…","version":"2.8.7","expires":true,"ttl":154367516}}
```

**K5b — kanal tidak pernah menerbitkan data.** Dengan token utuh, `method:1` untuk
`chart:tick-btcusdt` dijawab `{"recoverable":true,"epoch":"1733887641","offset":104011}`, lalu
**diam**. Kanal yang benar adalah `market:summary-24h` (yang dipakai situs Indodax sendiri);
kanal ini mengalirkan semua pair, terukur ±1 pesan/detik (±3 KB per 20 detik) — ringan untuk
baterai. Varian per-pair (`market:summary-24h-btcusdt`, `market:ticker-btcusdt`,
`market:price-btcusdt`) semuanya diam. Yang tersedia per-pair hanya
`market:order-book-<pair>` (bisa dipakai nanti untuk depth, di luar perbaikan ini).

**K5c — bentuk baris berbeda.** Server mengirim:

```json
{"result":{"channel":"market:summary-24h","data":{"data":[
  ["btcidr",1792251000,1459280000,1454489000,1470242000,1462856000,"10482997980","7.17230292"]
],"offset":147527011}}}
```

Urutannya `[pair, epochDetik, last, low, high, open, vol_idr, vol_koin]` — bukan
`[epoch, seq, harga, volume]` seperti yang dibaca parser lama. `vol_idr` dipakai untuk
`volume24h` (sama dengan REST), `vol_koin` kumulatif dipakai untuk delta volume candle.

**Perbaikan (sudah diterapkan):**

- `service/IndodaxSummaryFeed.kt` — parser murni + validasi bentuk token + konstanta kanal.
- `IndodaxMarketWebSocket` memakai token utuh, kanal `market:summary-24h`, menyaring satu pair,
  menghitung delta volume kumulatif, dan menolak menyambung bila token tidak utuh.
- `lastSequence` dihapus (tidak ada nomor urut pada kanal ringkasan), sehingga **K6 ikut tutup**.
- Uji: `IndodaxSummaryFeedTest` (9 kasus, termasuk kontrol negatif token terpotong, kanal lama,
  dan baris 4 kolom).

### K6 — `lastSequence` tidak di-reset saat menyambung ulang (Indodax) — ✅ tutup bersama K5

**Berkas:** `service/IndodaxMarketWebSocket.kt` (kode `lastSequence` sudah tidak ada)

`lastSequence` sudah **dihapus** bersama perbaikan K5: kanal `market:summary-24h` tidak membawa
nomor urut per baris, jadi tidak ada lagi state yang bisa basi setelah reconnect. Deduplikasi kini
memakai pasangan `(timestamp, harga)` yang terakhir diterima.

### K7 — `isStale` memutus socket, bukan menyambung ulang dengan tenang

**Berkas:** `viewmodel/MarketDataCoordinator.kt:350-360`, `service/IndodaxMarketWebSocket.kt:44-51`

```kotlin
if (indodaxWebSocket.isStale(30_000L)) {
    indodaxWebSocket.start(pair.symbol)   // start() → stop(false) → connect()
}
```

`start()` menutup socket lama. Untuk pair yang sepi (tidak ada trade 30 detik), ini berarti
memutus koneksi yang sebenarnya sehat, lalu menyambung lagi — berulang tiap 30 detik. Setiap
restart juga mengosongkan `candle`, sehingga histori menit berjalan hilang.

**Perbaikan:** pisahkan `reconnectNow()` dari `start(symbol)`, dan pakai ambang stale yang
memperhitungkan ping/pong (bukan hanya frame data). Pair sepi bukan alasan memutus koneksi.

---

## 4. 🟡 Sedang

### K8 — Backoff di-reset setiap `onOpen`, tanpa jitter dan tanpa batas percobaan

**Berkas:** `service/IndodaxMarketWebSocket.kt:90-98`, `service/TokocryptoMarketWebSocket.kt:132-140`

`reconnectAttempt = 0` di `onOpen`. Server yang menerima koneksi lalu langsung menutupnya
(kasus K5) membuat siklus: buka → tutup → tunggu 800 ms → buka lagi. Tidak ada jitter, tidak ada
batas percobaan, tidak ada status "menyerah sementara". Dampaknya: traffic sia-sia, baterai, dan
log penuh.

**Perbaikan:** reset hitungan hanya setelah koneksi **stabil** (mis. 60 detik atau setelah frame
pertama), tambahkan jitter ±20%, dan batas percobaan dengan jeda panjang (mis. 5 menit) sebelum
mencoba lagi.

### K9 — Listener Indodax tidak punya penjaga generasi

**Berkas:** `service/IndodaxMarketWebSocket.kt:158-180` (bandingkan `TokocryptoMarketWebSocket.kt:142-155`)

Saat pengguna berganti pair, socket lama masih bisa memanggil `onDisconnected()` dan
`reconnect()` karena tidak ada pemeriksaan "apakah saya masih socket yang berlaku". Tokocrypto
sudah memakai `generation`; Indodax belum. Akibatnya status bursa bisa berubah menjadi "terputus"
padahal socket aktif sehat.

**Perbaikan:** tiru pola `generation` dari Tokocrypto.

### K10 — Satu flag `wsLive` dipakai dua bursa

**Berkas:** `viewmodel/MarketDataCoordinator.kt:95-155`

`wsLive` di-set `false` oleh callback bursa mana pun. Kalau socket bursa yang tidak aktif
mengirim `onDisconnected`, bursa yang sedang dipakai ikut dianggap tidak live, sehingga REST
fallback aktif padahal tidak perlu. Ini memperbesar pemakaian REST.

**Perbaikan:** simpan status per bursa (`wsLiveToko`, `wsLiveIndodax`) atau periksa bursa pemilik
callback sebelum mengubah status.

### K11 — Worker Indodax mengunduh seluruh pasar, bukan watchlist

**Berkas:** `service/CandidateScanWorker.kt:252-270`

```kotlin
/** Hanya pair di watchlist yang diambil tick-nya; tidak pernah seluruh pasar. */
private suspend fun fetchTicks(...) {
    if (isIndodax) {
        val allTicks = IndodaxMarketService.fetchAllMarketTicks()   // /api/summaries: SEMUA pair
```

Komentarnya menyatakan watchlist, kodenya mengambil `/api/summaries` (seluruh pasar) setiap
15 menit. Ini melanggar aturan "pemindaian latar hanya watchlist" yang sudah ditetapkan untuk
Tokocrypto, dan memboroskan data/baterai.

**Perbaikan:** pakai `IndodaxMarketService.fetchTickers(eligibleSymbols)` yang sudah ada.

### K12 — `fetchTickers` Indodax selalu mengunduh seluruh ringkasan

**Berkas:** `service/IndodaxMarketService.kt:158-199`

Meski dipanggil untuk satu pair, fungsi ini mengambil `https://indodax.com/api/summaries`
(seluruh pasar) lalu memfilternya di memori. Dipanggil setiap 10 detik oleh polling trailing
(`TradingViewModelSync.kt:751`), jadi beban REST jauh lebih besar dari yang terlihat.

**Perbaikan:** untuk satu/beberapa pair, pakai `/api/ticker/<pair>`; simpan `/api/summaries`
hanya untuk kebutuhan dashboard penuh.

### K13 — Simbol bisa masuk watchlist tanpa dicek keberadaannya

**Berkas:** `ui/components/settings/WatchlistManagerSettings.kt:205-218`, `util/AppPreferences.kt:513-526`, `model/TradingViewModels.kt:337-395`

- Kolom "Ketik simbol" menerima teks bebas; hasilnya langsung `onAddPair(...)`.
- `setWatchlist`/`toggleWatchlist` hanya mengubah huruf besar dan memangkas spasi.
- `fromCustomSymbol` **mengarang** `TradingPair` untuk simbol yang tidak dikenal, tanpa bertanya ke
  repository bursa.

Jadi `UVXYBUSDT` (atau string apa pun) bisa masuk watchlist, tampil sebagai kartu dengan harga
`-`, dan ikut dievaluasi engine. Ini pintu masuk kedua bagi pair hantu, terlepas dari K1.

**Perbaikan:** tolak penambahan yang tidak ada di `TokocryptoSymbolRepository`/
`IndodaxSymbolRepository`, dengan pesan yang jelas. `fromCustomSymbol` tetap boleh untuk
menampilkan data historis, tetapi **bukan** untuk membuat pair baru yang bisa ditradingkan.

### K14 — Cabang dashboard strategi melewati filter listing

**Berkas:** `ui/screens/DashboardScreen.kt:142-161`

`pairListed(...)` dipakai untuk `watchAndFav`, `explicit`, dan `popularHead`, tetapi **tidak** untuk
`highVol` (SCALPING), `swingCandidates`, dan `intradayCandidates`. Ketiganya dibangun langsung dari
`uniqueTicks`, sehingga simbol yang muncul di ticker tetapi tidak lolos gate listing tetap bisa
tampil.

**Perbaikan:** terapkan `pairListed` pada ketiga daftar itu, atau saring `uniqueTicks` sekali di
awal.

---

## 5. 🟢 Rendah

### K15 — `getSymbolType` mengembalikan 1 untuk simbol tak dikenal

**Berkas:** `data/TokocryptoSymbolRepository.kt:355-357`

Simbol yang tidak ada di peta diperlakukan sebagai Type 1, bukan "tidak diketahui". Kesalahan
konfigurasi jadi tersembunyi: permintaan diarahkan ke host yang salah tanpa satu pun log peringatan.
Sebaiknya kembalikan `null`/`0` dan tangani di pemanggil, atau minimal catat log sekali per simbol.

---

## 6. ✅ Yang sudah benar (jangan diubah)

- **Order gagal-tertutup saat metadata tidak ada.** `TokocryptoTradeApi.kt:440-446` mengembalikan
  error "Symbol … tidak ditemukan di metadata Tokocrypto" sebelum mengirim order. Jadi jalur order
  sendiri tidak pernah mengarang quantity.
- **Order & saldo riil berfungsi** di Indodax dan Tokocrypto (diverifikasi owner). Temuan di
  dokumen ini tidak menyentuh eksekusi order.
- **Normalisasi pair Indodax** (`IndodaxMarketService.toPairId`) menangani `BTCIDR`, `btc_idr`,
  `BTC/IDR`, dan hanya mengizinkan pasar IDR.
- **Retry REST** punya throttle 200 ms dan backoff pada 429/5xx (`IndodaxMarketService.kt:63-90`).
- **WebSocket Tokocrypto** memakai penjaga `generation`, `pingInterval` 15 detik, dan menyambung
  ulang saat server menutup koneksi 24 jam.
- **`DashboardRanking.isRankable`** sudah mensyaratkan status listing untuk Tokocrypto — hanya saja
  syarat itu tidak berarti selama K1/K4 belum diperbaiki.
- **Peta simbol diganti, bukan ditambah**, saat sync berhasil, sehingga simbol delisting tidak
  menumpuk (niatnya benar; yang salah adalah sumber datanya).

---

## 7. Dampak ke pengguna

| Gejala | Penyebab | Temuan |
|---|---|---|
| Pair `UVXYBUSDT` muncul, ada harga, tapi order ditolak | Daftar simbol dari `exchangeInfo` gateway (universe Binance) | K1, K2 |
| Pair aneh bisa ditambahkan manual dan tampil selamanya | Watchlist tanpa validasi | K13 |
| Indodax terasa "selalu putus", harga loncat tiap 5 detik | WS mati karena token ditolak | K5, K8 |
| Pair Type 3 (mis. `ATH_USDT`) tidak punya harga/stream | `type` tidak terbaca | K3 |
| Baterai/data boros di latar | Worker & polling mengunduh seluruh pasar | K11, K12 |
| Pair lolos gate listing padahal tidak aktif di bursa | Gate memakai daftar gateway, bukan `common/symbols` | K1, K2, K4 |

---

## 8. Rencana perbaikan (urutan yang disarankan)

| Prioritas | Pekerjaan | Kriteria selesai |
|---|---|---|
| **P0-1** | Parse `data.list` + `symbols`; tolak respons dengan jumlah simbol di luar 100–1.500 atau tanpa pair IDR | `getAllTradingPairs()` berisi 864 simbol resmi; `UVXYBUSDT` tidak ada |
| **P0-2** | Bandingkan respons dengan snapshot resmi terakhir (≥90% tumpang tindih) sebelum mengganti peta | `exchangeInfo` gateway tidak bisa menggantikan daftar resmi |
| **P0-3** | Baca `type`, `spotTradingEnable` (angka/boolean), `status` dengan default fail-closed | 17 simbol Type 3 dikenali; simbol suspend tidak lolos |
| **P1-1** | Token WS Indodax: perbarui dinamis, atau lepas WS dan akui REST-only | Tidak ada lagi `3002` berulang; status "live" hanya setelah frame pertama |
| **P1-2** | Reset `lastSequence` di `onOpen`; tambah penjaga generasi; backoff berjitter + batas percobaan | Uji unit kebijakan reconnect lulus |
| **P2-1** | Validasi penambahan watchlist ke repository bursa | Menolak `UVXYBUSDT` dengan pesan jelas |
| **P2-2** | Terapkan filter listing di semua cabang dashboard; worker Indodax hanya watchlist | Tidak ada pair di luar daftar resmi yang tampil/dipindai |
| **P2-3** | Uji baru: `SymbolDiscoveryParseTest`, `SymbolSourceSanityTest`, `IndodaxReconnectPolicyTest`, `WatchlistValidationTest`, `DashboardListingFilterTest` | Masing-masing punya kontrol negatif yang benar-benar gagal saat kode dirusak |

Catatan: P0-1 sampai P0-3 mengubah daftar pair yang terlihat pengguna (pair yang tidak terdaftar di
Tokocrypto hilang dari pencarian). Itu memang tujuannya, tetapi sebaiknya diumumkan di catatan rilis.

---

## 9. Cara memverifikasi ulang

```sh
# 1. Bentuk respons resmi (harus 864 simbol, data.list)
curl -s https://www.tokocrypto.com/open/v1/common/symbols \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d['data']['list']))"

# 2. Pair hantu tidak ada di bursa
curl -s "https://www.tokocrypto.com/open/v1/market/ticker?symbol=UVXY_USDT"
# → {"code":404,...}

# 3. Tapi ada di host yang dipakai aplikasi
curl -s "https://www.tokocrypto.site/api/v3/ticker/24hr?symbol=UVXYBUSDT"
# → lastPrice 18.82 …

# 4. Token WS Indodax
python3 - <<'PY'
import asyncio, json, websockets
T="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE5NDY2MTg0MTV9.UR1lBM6Eqh0yWz-PVirw1uPCxe60FdchR8eNVdsske"
async def main():
    try:
        async with websockets.connect("wss://ws3.indodax.com/ws/") as ws:
            await ws.send(json.dumps({"params": {"token": T}, "id": 1}))
            print(await ws.recv())
    except Exception as e:
        print(type(e).__name__, e)
asyncio.run(main())
PY
# → ConnectionClosedError 3002 ... "invalid token","reconnect":false
```

Setelah perbaikan, langkah 1 harus menjadi sumber daftar pair, dan langkah 3 tidak boleh lagi
menentukan isi aplikasi.

---

## 10. Batas audit

- Audit ini **tidak** mengubah kode; semua temuan masih hidup.
- Perilaku endpoint pihak ketiga bisa berubah kapan saja. Yang dicatat adalah hasil pengukuran
  pada 2026-10-17, bukan janji mereka.
- Type 3 diuji dengan dua format stream dan tiga jalur REST; hasilnya negatif. Bisa jadi ada
  endpoint resmi lain yang belum terdokumentasi — perlu konfirmasi ke dokumentasi Tokocrypto
  sebelum menyimpulkan "Type 3 tidak mungkin didukung".
- Order riil tidak diuji ulang di audit ini (sudah diverifikasi owner).
