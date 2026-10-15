# LAPORAN — Batas jumlah koin untuk auto-buy paper

**Tanggal:** 2026-10-15
**Branch:** `patch`
**Berkas terkait:** `workplan2.md` §19, `AGENTS.md` (Engine scalping — aturan agen)

---

## 1. Masalah

Auto-buy bisa terpicu sinyal entry padahal portofolio paper **tidak punya / kurang saldo**.
Penyebabnya bukan sinyal yang buruk, melainkan **tidak ada batas jumlah posisi bersamaan**:
kas yang sudah terpotong posisi auto-buy sebelumnya yang masih **hold** tetap "terpakai",
sehingga order berikutnya ditolak karena saldo kurang.

Sebelum perubahan ini satu-satunya penjaga adalah **dedup per-pair** di `tryPaperAutoBuy`
(`if (existing.isHolding) return false`). Tidak ada aturan apa pun tentang berapa **banyak koin**
yang boleh dipegang bersamaan. Akibatnya kegagalan muncul sebagai pesan saldo dari order engine,
bukan sebagai masalah setelan — sulit didiagnosis pengguna.

## 2. Keputusan owner

| Aspek | Keputusan |
|---|---|
| Cakupan | **Global** — satu batas untuk semua bursa & mata uang kuotasi |
| Yang dihitung | **Semua** posisi paper (termasuk yang dibeli manual) |
| Default | **3** koin; `0` = tanpa batas |
| Yang diblokir | **Hanya auto-buy** — beli manual tetap bebas (aksi sadar pengguna) |

## 3. Perubahan

| Titik | Berkas | Perubahan |
|---|---|---|
| Field + helper murni | `engine/scalping/PaperAutoTradeConfig.kt` | `maxConcurrentPositions: Int = 3`; `positionLimitReached(currentCount)` |
| Hitungan global | `trading/SpotPositionStore.kt` | `countActivePaperPositions()` |
| Gerbang | `viewmodel/PaperAutoTradeCoordinator.kt` | gate di `tryPaperAutoBuy`, setelah dedup per-pair, sebelum baca saldo |
| Persistensi | `util/AppPreferences.kt` | `KEY_PAPER_AUTO_BUY_MAX_POSITIONS` (getter + setter) |
| UI | `ui/components/settings/PaperAutoTradeSettings.kt` | `StepperRow` "Maks. posisi bersamaan (0 = tanpa batas)", rentang 0–20 |

### 3.1 Kenapa `getAllActivePositions(isReal = false)`

`countActivePaperPositions()` memakai `getAllActivePositions(exchange = null, isReal = false)`.
Sudah diverifikasi dengan membaca badan fungsinya: ia mengiterasi **seluruh** key `*_state`,
menyaring `HOLDING`, menyaring `isReal == false`, lalu hanya menerima posisi dengan
`quantity > 0`. Karena `exchange = null`, filter bursa dilewati → **benar-benar global** lintas
Tokocrypto & Indodax. Posisi **beli manual** ikut terhitung karena tercatat di store yang sama.

Penanda `_paper_engine` **tidak** dipakai di sini: penanda itu menandai **kepemilikan exit**,
bukan asal order — auto-buy dan beli manual memakai jalur yang sama, jadi ia bukan pembeda
auto vs manual.

### 3.2 Kenapa gerbangnya di `tryPaperAutoBuy`

`tryPaperAutoBuy` adalah **satu-satunya choke point auto-buy** (diverifikasi: hanya dipanggil dari
`TradingViewModelSync.kt:535`). Beli manual **tidak** lewat fungsi itu (halaman detail memanggil
`submitSimulationOrder` + `setOwnership`), sehingga menaruh gerbang di sini otomatis hanya
memblokir auto-buy — sesuai keputusan owner, tanpa menyentuh jalur manual.

Pesan `Skip`/penolakan memakai gaya yang sama dengan penolakan yang sudah ada, dan menyebut
hitungan aktual, mis.:

```
⏭️ [BTCUSDT] Auto-buy dilewati: batas posisi bersamaan tercapai (3/3).
   Tutup posisi lain atau naikkan batas di Pengaturan.
```

## 4. Test

Tiga berkas test baru ditambahkan untuk tugas ini dan tugas mode detail (lihat laporan kedua);
yang relevan di sini:

| Kelas | Jumlah | Yang dikunci |
|---|---:|---|
| `engine/scalping/PaperAutoTradeConfigPositionLimitTest` | 6 | aritmetika batas, makna `0` = tanpa batas, default 3 |
| `trading/SpotPositionStoreActiveCountTest` | 4 | hitungan global lintas bursa, posisi real dikecualikan, posisi terjual keluar, beli manual terhitung |

### Kontrol negatif (wajib)

`positionLimitReached` dijadikan `false` sementara:

```
test 6 gagal 3
  lulus  batas belum terlampaui bila jumlah di bawah batas
  lulus  nol berarti tanpa batas
  GAGAL  batas terlampaui bila jumlah melebihi batas
  GAGAL  batas tercapai bila jumlah sama dengan batas
  lulus  default maks posisi adalah tiga
  GAGAL  batas satu langsung tercapai pada posisi pertama
```

**3 dari 6 gagal**, dan hanya test yang benar-benar bergantung pada gerbang. Dipulihkan → hijau.
Artinya test memang mengunci gerbangnya, bukan sekadar lulus karena kebetulan.

## 5. Hasil terukur

| | Sebelum | Sesudah |
|---|---|---|
| Unit test (dari XML) | 416 lulus · 0 gagal · 1 dilewati (53 kelas) | **429 lulus · 0 gagal · 1 dilewati (56 kelas)** |
| `assembleDebug` | sukses | **sukses** |
| APK debug | 26.821.815 byte | **27.875.220 byte** |

## 6. Batasan & catatan

- Gerbang ini **tidak** membatasi beli manual. Bila owner ingin beli manual ikut diblokir,
  gerbangnya harus ditambahkan di jalur `onExecuteBuy` halaman detail — belum dilakukan.
- Batas dihitung dari **posisi**, bukan dari saldo. Saldo tetap diperiksa terpisah oleh
  `PaperAutoTradeConfig.plan()` seperti sebelumnya; keduanya saling melengkapi.
- Nilai preferensi yang tidak terbaca jatuh ke default aman **3**, bukan ke "tanpa batas".
