# Laporan Temuan Audit — Jalur Koneksi Tokocrypto & Indodax

**Audit:** 2026-10-17 · **Ditutup seluruhnya:** 2026-10-18 · **Dipindahkan ke sini:** 2026-10-18
**Branch:** `patch`
**Letak berkas:** `docs/laporan_temuan_bugs_koneksi_tokocrypto_indodax.md`. Berkas ini **tidak lagi**
berada di `docs/bugs/`, karena seluruh temuannya sudah tertutup. Aturannya: `docs/bugs/` hanya memuat
temuan yang **masih hidup**; berkas yang sudah beres dipindahkan keluar dengan nama
`docs/laporan_temuan_bugs_<topik>.md`, dan berkas itu **menjadi laporannya** — tidak ada lagi
laporan per batch.
**Isi dokumen:** laporan penutupan. Butir yang sudah ditutup dipangkas dari badan dokumen dan
diringkas di §2; ringkasan per batch ada di §7; bukti pengukuran audit disimpan di §6 (Lampiran).
**Pemicu:** pair **`UVXYBUSDT`** muncul di aplikasi padahal **tidak ada di Tokocrypto**; WebSocket
Indodax sering terputus sehingga semua data jatuh ke REST.
**Catatan owner:** order beli/jual dan saldo riil di **Indodax** dan **Tokocrypto** sudah diuji dan
**berfungsi**. Jadi temuan di dokumen ini bukan soal eksekusi order atau saldo — yang bermasalah
adalah **sumber daftar pair** dan **sumber data pasar**.

| Status | Temuan | Jumlah |
|---|---|---|
| 🔴 Kritis | — | 0 |
| 🟠 Serius | — | 0 |
| 🟡 Sedang | — | 0 |
| 🟢 Rendah | — | 0 |
| ✅ Sudah diperbaiki | K1–K15 | 15 |
| ✅ Sudah benar | order fail-closed, saldo, normalisasi pair, retry REST | — |

> **Tidak ada pekerjaan tersisa di berkas ini.** Kalau butuh pekerjaan baru di area koneksi/sumber
> pair, lakukan **audit baru** dan tulis temuannya ke berkas temuan baru di `docs/bugs/`
> (`temuan_bugs_<topik>.md`), jangan menghidupkan kembali nomor lama. Berkas temuan baru itu nanti
> dipindahkan ke sini dengan nama `docs/laporan_temuan_bugs_<topik>.md` begitu seluruh isinya tutup.

---

## 1. Ringkasan akar masalah

Ada dua akar yang berdiri sendiri:

1. **Daftar pair Tokocrypto diambil dari host yang salah.** Endpoint resmi
   `www.tokocrypto.com/open/v1/common/symbols` mengembalikan **864 simbol** di dalam
   `data.list`, tetapi kode membacanya sebagai `data` berbentuk **array**. Karena bentuknya tidak
   cocok, kode diam-diam pindah ke endpoint cadangan `www.tokocrypto.site/api/v3/exchangeInfo`
   yang mengembalikan **3.729 simbol** — seluruh universe Binance yang dilayani gateway itu,
   lengkap dengan `UVXYBUSDT`. Aplikasi memperlakukannya sebagai pair Tokocrypto yang sah; saat
   order dikirim ke Tokocrypto, bursa menolaknya.
2. **WebSocket Indodax tidak pernah hidup karena tiga cacat berlapis.** Token di kode
   **terpotong satu karakter** (104, seharusnya 105), sehingga server menolak dengan
   `3002 invalid token` — token aslinya masih berlaku sampai 2031. Setelah token dibetulkan,
   kanal `chart:tick-<pair>` ternyata hanya di-ack dan tidak pernah menerbitkan data; kanal yang
   benar `market:summary-24h`. Parser lama juga mengharapkan bentuk baris yang berbeda dari yang
   dikirim server. Seluruh harga Indodax karena itu selalu jatuh ke REST.

Keduanya menjelaskan gejala yang dilaporkan: pair hantu muncul dengan harga yang terlihat wajar,
dan Indodax terasa "selalu putus".

---

## 2. Sudah ditutup (ringkasan + pointer)

| Temuan | Ringkas | Batch | Commit |
|---|---|---|---|
| K1 — discovery Tokocrypto tidak mem-parse respons resmi, lalu memakai daftar Binance | Parse `data.list`; tolak daftar di luar rentang wajar / tanpa IDR / overlap rendah | 6k | `0903d86` |
| K2 — endpoint cadangan sah untuk data pasar, tidak sah sebagai sumber daftar pair | hanya `common/symbols` untuk daftar pair; gateway tetap untuk ticker/kline/depth | 6k | `0903d86` |
| K3 — field `symbolType` salah nama; semua simbol dianggap Type 1 | baca `type` lebih dulu; Type 3 dapat host Type 3 | 6k | `0903d86` |
| K4 — gate "hanya pair TRADING" gagal-terbuka pada data resmi | terima angka/boolean/string; tak dikenal, fail-closed | 6k | `0903d86` |
| K5 — WS Indodax mati: token terpotong, kanal salah, parser salah bentuk | token utuh, kanal `market:summary-24h`, parser 8 kolom | 6j | `4684e8a` |
| K6 — `lastSequence` tidak di-reset saat reconnect (Indodax) | `lastSequence` dihapus (kanal ringkasan tidak punya nomor urut) | 6j | `4684e8a` |
| K7 — `isStale` memutus socket, bukan menyambung ulang dengan tenang | ambang hitung siklus ping + `reconnectNow()` tanpa buang candle | 6l | `6ae7b5a` |
| K8 — backoff di-reset setiap `onOpen`, tanpa jitter dan tanpa batas percobaan | reset hanya setelah sesi stabil 60 detik; jitter ±20%; jeda menyerah 5 menit | 6n | `a6c5e00` |
| K9 — listener Indodax tidak punya penjaga generasi | penjaga generasi di kedua socket; frame socket usang diabaikan | 6n | `a6c5e00` |
| K10 — satu flag `wsLive` dipakai dua bursa | status live dipisah per bursa (`wsLiveToko`, `wsLiveIndodax`) | 6n | `a6c5e00` |
| K11 — worker Indodax mengunduh seluruh pasar, bukan watchlist | worker memakai `fetchTickers(eligibleSymbols)` | 6n | `a6c5e00` |
| K12 — `fetchTickers` Indodax selalu mengunduh seluruh ringkasan | per-pair lewat `/api/ticker/<pair>`; `/api/summaries` khusus dashboard | 6n | `a6c5e00` |
| K13 — simbol bisa masuk watchlist tanpa dicek keberadaannya | `WatchlistEntryGate` + `hasSymbol`; hapus fallback fail-open | 6m | `af957b7` |
| K14 — cabang dashboard strategi melewati filter listing | `DashboardRanking.symbolListed`; `uniqueTicks` disaring sekali di awal | 6m | `af957b7` |
| K15 — `getSymbolType` mengembalikan 1 untuk simbol tak dikenal | konstanta `SYMBOL_TYPE_UNKNOWN = 0` + log sekali per simbol | 6n | `a6c5e00` |

Batch 6n di-commit sebagai `a6c5e00` di branch `patch` (2026-10-18).

---

## 3. ✅ Yang sudah benar (jangan diubah)

- **Order gagal-tertutup saat metadata tidak ada.** `TokocryptoTradeApi.kt:440-446` mengembalikan
  error "Symbol … tidak ditemukan di metadata Tokocrypto" sebelum mengirim order.
- **Order & saldo riil berfungsi** di Indodax dan Tokocrypto (diverifikasi owner).
- **Normalisasi pair Indodax** (`IndodaxMarketService.toPairId`) menangani `BTCIDR`, `btc_idr`,
  `BTC/IDR`, dan hanya mengizinkan pasar IDR.
- **Retry REST** punya throttle 200 ms dan backoff pada 429/5xx (`IndodaxMarketService.kt:63-90`).
- **WebSocket Tokocrypto** memakai penjaga `generation`, `pingInterval` 15 detik, dan menyambung
  ulang saat server menutup koneksi 24 jam.
- **`DashboardRanking.isRankable`** sudah mensyaratkan status listing untuk Tokocrypto.
- **Peta simbol diganti, bukan ditambah**, saat sync berhasil, sehingga simbol delisting tidak
  menumpuk.

---

## 4. Dampak ke pengguna (sudah ditangani)

| Gejala | Penyebab | Temuan |
|---|---|---|
| Pair `UVXYBUSDT` muncul, ada harga, tapi order ditolak | Daftar simbol dari `exchangeInfo` gateway (universe Binance) | K1, K2 |
| Pair aneh bisa ditambahkan manual dan tampil selamanya | Watchlist tanpa validasi | K13 |
| Indodax terasa "selalu putus", harga loncat tiap 5 detik | WS mati karena token ditolak; backoff tanpa batas | K5, K8 |
| Pair Type 3 (mis. `ATH_USDT`) tidak punya harga/stream | `type` tidak terbaca | K3 |
| Baterai/data boros di latar | Worker & polling mengunduh seluruh pasar | K11, K12 |
| Pair lolos gate listing padahal tidak aktif di bursa | Gate memakai daftar gateway, bukan `common/symbols` | K1, K2, K4 |

---

## 5. Cara memverifikasi ulang

```sh
# 1. Bentuk respons resmi (harus 864 simbol, data.list)
curl -s https://www.tokocrypto.com/open/v1/common/symbols \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d['data']['list']))"

# 2. Pair hantu tidak ada di bursa
curl -s "https://www.tokocrypto.com/open/v1/market/ticker?symbol=UVXY_USDT"
# -> {"code":404,...}

# 3. Tapi ada di host yang dulu dipakai aplikasi
curl -s "https://www.tokocrypto.site/api/v3/ticker/24hr?symbol=UVXYBUSDT"
# -> lastPrice 18.82 ...

# 4. Token WS Indodax (handshake)
python3 - <<'PY'
import asyncio, json, websockets
T="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE5NDY2MTg0MTV9.UR1lBM6Eqh0yWz-PVirw1uPCxe60FdchR8eNVdsskeo"
async def main():
    async with websockets.connect("wss://ws3.indodax.com/ws/") as ws:
        await ws.send(json.dumps({"params": {"token": T}, "id": 1}))
        print(await ws.recv())
asyncio.run(main())
PY
# -> {"id":1,"result":{"client":"...","version":"2.8.7","expires":true,...}}
```

Setelah perbaikan, langkah 1 menjadi sumber daftar pair, dan langkah 3 tidak lagi menentukan isi
aplikasi.

---

## 6. Lampiran bukti audit (2026-10-17)

Disimpan agar pengukuran yang menjadi dasar temuan tidak hilang. Angka-angka ini hasil ukur
**saat audit**; perilaku endpoint pihak ketiga bisa berubah.

Bentuk respons resmi:

```json
{ "code": 0, "msg": "Success", "data": { "list": [ { "type": 1, "symbol": "BTC_USDT", ... } ] } }
```

Tabel sumber daftar pair:

| Sumber | Jumlah simbol | `UVXYBUSDT`? | Bukti |
|---|---|---|---|
| `tokocrypto.com/open/v1/common/symbols` (resmi) | **864** (487 USDT, 40 IDR, 237 USDC) | **tidak ada** | `data.list` |
| `tokocrypto.site/api/v3/exchangeInfo` (dulu dipakai app) | **3.729** | **ada**, `status: TRADING`, `baseAsset: UVXYB` | universe Binance di gateway |
| `cloudme-toko.2meta.app/api/v1/exchangeInfo` | 63 | tidak ada | proxy, endpoint ticker balas 403 |

Distribusi tipe resmi: `{1: 847, 3: 17}` — 17 simbol Type 3, mis. `ALCH_IDR`, `ATH_USDT`,
`CARV_IDR`, `CREO_USDT`, `DRX_IDR`.

Depth `BTC_USDT` resmi vs gateway (identik, jadi host itu gateway pasar Tokocrypto, bukan bursa lain):

```text
resmi   [['81598.00','5.05854'], ['81597.99','0.00368']]
gateway [['81598.00','5.05854'], ['81597.99','0.00368']]
```

Handshake WebSocket Indodax:

```text
token 104 karakter (kode lama) -> 3002 {"reason":"invalid token","reconnect":false}
token 105 karakter (utuh)      -> {"id":1,"result":{"client":"...","version":"2.8.7","expires":true,"ttl":154367516}}
```

Bentuk baris kanal `market:summary-24h`:

```json
{"result":{"channel":"market:summary-24h","data":{"data":[
  ["btcidr",1792251000,1459280000,1454489000,1470242000,1462856000,"10482997980","7.17230292"]
],"offset":147527011}}}
```

Urutannya `[pair, epochDetik, last, low, high, open, vol_idr, vol_koin]`.

---

## 7. Riwayat

| Batch | Isi | Sumber |
|---|---|---|
| 6j | K5 (K5a/K5b/K5c) + K6 — token utuh, kanal `market:summary-24h`, parser 8 kolom | commit `4684e8a` |
| 6k | K1–K4 — daftar pair hanya dari endpoint resmi, gate fail-closed | commit `0903d86` |
| 6l | K7 — ambang usang hitung siklus ping, `reconnectNow()` tanpa buang candle | commit `6ae7b5a` |
| 6m | K13 + K14 — gate entry watchlist & satu gate listing dashboard | commit `af957b7` |
| 6n | K8–K12 + K15 — backoff berjitter & berbatas, penjaga generasi, status live per bursa, worker & ticker Indodax sesuai lingkup, tipe simbol tak dikenal jujur | commit `a6c5e00` |
| — | Berkas dipindahkan dari `docs/bugs/` ke sini (seluruh temuan tertutup) | 2026-10-18 |

---

## 8. Batas audit

- Perilaku endpoint pihak ketiga bisa berubah kapan saja. Yang dicatat adalah hasil pengukuran
  pada 2026-10-17, bukan janji mereka.
- Type 3 diuji dengan dua format stream dan tiga jalur REST; hasilnya negatif. Bisa jadi ada
  endpoint resmi lain yang belum terdokumentasi — perlu konfirmasi ke dokumentasi Tokocrypto
  sebelum menyimpulkan "Type 3 tidak mungkin didukung".
- Order riil tidak diuji ulang di audit ini (sudah diverifikasi owner).
- Angka jumlah uji batch 6n **sudah diukur** lewat CI (`unit-tests-manual.yml`, commit `a6c5e00`):
  run `39083182133` (7 kelas / 38 uji, 0 gagal) dan run `39083423103` (`all-unit-tests`:
  **94 kelas / 726 uji, 0 gagal, 0 error, 1 dilewati**). Rincian ada di `workplan2.md` §57.4.
  Sandbox agen sendiri tidak bisa menjalankan Gradle (tidak ada Android SDK; `aapt2` aarch64).
