# LAPORAN — Mode detail koin otoritatif & kartu backtest dibuang

**Tanggal:** 2026-10-15
**Branch:** `patch`
**Berkas terkait:** `workplan2.md` §20, `AGENTS.md` (UI & Compose)

---

## 1. Masalah

Halaman detail koin menampilkan **kartu beli dan jual bersamaan**. Struktur lamanya:

- `WaitingEntryRadarCard` punya cabang BUY dan cabang SELL, lalu **selalu** merender
  `RadarTransactionFeeSection` di bawahnya;
- `RadarTransactionFeeSection` memuat **toggle Beli/Jual manual** (baris 139–201) yang mengubah
  cabang `RadarBuySection` / `RadarSellSection`.

Akibatnya user tidak bisa membedakan apakah ia sedang menyiapkan pembelian atau penjualan, dan
satu koin bisa menampilkan dua alur sekaligus.

Catatan penting: mode **sudah** otomatis pindah ke SELL saat posisi muncul
(`DetailChartScreen.kt:132-135`), tetapi user masih bisa menekan toggle kembali ke Beli — itulah
yang membuat UI terasa gabungan.

Owner juga meminta baseline backtest dibuang dari halaman detail.

## 2. Keputusan owner

| Aspek | Keputusan |
|---|---|
| Saat hold | halaman detail = **mode SELL** |
| Saat tidak hold | halaman detail = **mode BUY** |
| Toggle Beli/Jual | **dihapus** |
| DCA saat hold | **tetap bisa** (lewat jalur terpisah) |
| Backtest | **hanya kartu UI** yang dibuang; mesin dipertahankan |

## 3. Perubahan

| Titik | Berkas | Perubahan |
|---|---|---|
| Resolver murni | `model/PositionContext.kt` | `enum DetailTradeMode { BUY, SELL }` + `resolveDetailTradeMode(isHolding)` |
| Layar | `ui/screens/DetailChartScreen.kt` | state lokal `isBuyMode` + `LaunchedEffect(isHolding)` **dihapus**; arg `isBuyMode`/`onBuyModeChanged` tidak lagi dioper |
| Kartu | `ui/components/detail/WaitingEntryRadarCard.kt` | param `isBuyMode`/`onBuyModeChanged` dihapus; mode = `resolveDetailTradeMode(isHolding)`; kode mati dibersihkan; tombol + dialog DCA ditambahkan |
| Form | `ui/components/detail/RadarTransactionFeeSection.kt` | param `onBuyModeChanged` + **seluruh toggle Beli/Jual dihapus** |
| Backtest | `ui/components/detail/radar/BacktestSection.kt` | **berkas dihapus** |
| Backtest | `ui/components/detail/scalp/ScalpDeskCard.kt` | blok + import `BacktestSection` dihapus |

### 3.1 Resolver sebagai satu-satunya tempat aturan

`resolveDetailTradeMode(isHolding)` di `model/PositionContext.kt` adalah satu-satunya tempat
aturan mode ditegakkan — sejalan dengan pola repo yang menguji keputusan lewat fungsi murni
(`resolveWorkflow`, `SellCheckpointEvaluator`). Dengan begitu aturannya bisa dikunci unit test
tanpa Android.

### 3.2 Kode mati yang ikut dibersihkan

Karena `!currentBuyMode` kini **identik** dengan `isHolding`, dua blok berikut tidak akan pernah
tercapai lagi dan sudah dibuang:

1. cabang `else` "Belum ada koin … di dompet" di dalam cabang SELL;
2. notis "Posisi … aktif di dompet. Mode Buy aktif untuk re-entry / cicil beli (DCA)." di dalam
   cabang BUY.

Membiarkannya akan mengulang preseden buruk `SellManualBuyDialog.kt` yang sampai sekarang jadi
dead code.

### 3.3 Jalur DCA

DCA **tidak** punya jalur lama: `SellManualBuyDialog.kt` ternyata **dead code** (nol pemanggil)
dan fungsinya merekam harga beli manual, bukan menambah posisi. Karena itu jalur baru dibuat:

- tombol **"Tambah posisi (DCA)"** di cabang SELL, tepat di bawah `SellTargetLevelsSection`;
- membuka dialog yang **memakai ulang `RadarBuySection`** dengan parameter yang sama seperti mode
  BUY (harga, nominal, fee aktif, mode real/paper, cooldown, orderbook).

Memakai ulang `RadarBuySection` berarti jalur eksekusi (`onExecuteBuy` → `executeRealTrade` /
`submitSimulationOrder`) **identik** dengan mode BUY — tidak ada form kedua yang bisa menyimpang.

### 3.4 Cakupan penghapusan backtest

Dihapus: `radar/BacktestSection.kt` + dua pemanggilnya (`WaitingEntryRadarCard`, `ScalpDeskCard`).
Diverifikasi `grep -rn "BacktestSection" app/src` → **kosong** setelah perubahan.

**Tidak** dihapus (sesuai keputusan owner): `engine/backtest/BacktestEngine.kt`,
`WalkForwardEvaluator.kt`, `engine/scalping/ScalpingBacktestAdapter.kt`, dan test ketiganya —
masih dipakai tooling/test. Konsekuensi yang diterima: `ScalpingBacktestAdapter` kini tidak lagi
punya pemanggil produksi.

Field model `backtestWinRatePct` / `backtestScore` (`model/TradingViewModels.kt`) **tidak**
disentuh: tidak ada pembaca lain selain definisi + penulisnya, dan menghapusnya berarti menyentuh
`ScalpingMtfEvaluator` yang berisiko tanpa manfaat untuk membuang kartu UI.

## 4. Test

| Kelas | Jumlah | Yang dikunci |
|---|---:|---|
| `engine/DetailTradeModeTest` | 3 | tidak hold → BUY, hold → SELL, konsisten dengan `resolveWorkflow` |

### Kontrol negatif (wajib)

`resolveDetailTradeMode` dibalik (hold → BUY):

```
test 3 gagal 3
  GAGAL  sedang hold menghasilkan mode jual
  GAGAL  tidak hold menghasilkan mode beli
  GAGAL  mode selaras dengan workflow
```

**3 dari 3 gagal.** Dipulihkan → hijau.

## 5. Hasil terukur

| | Sebelum | Sesudah |
|---|---|---|
| Unit test (dari XML) | 416 lulus · 0 gagal · 1 dilewati (53 kelas) | **429 lulus · 0 gagal · 1 dilewati (56 kelas)** |
| `assembleDebug` | sukses | **sukses** |
| APK debug | 26.821.815 byte | **27.875.220 byte** |

## 6. Risiko & mitigasi

| Risiko | Mitigasi |
|---|---|
| `selectedSellQty` kosong setelah toggle hilang | inisialisasi `availableCoin` di kartu + fallback `activeSellQty` di `RadarTransactionFeeSection` (sudah memakai `availableCoin` bila qty ≤ 0) |
| Mode SELL terkunci saat `isHolding` transien | `isHolding` dan `currentBuyMode` sama-sama turunan `PositionContext` |
| DCA menyimpang dari jalur beli normal | dialog memakai `RadarBuySection` yang sama, bukan form baru |
| Kartu backtest masih terpanggil | `grep BacktestSection` = 0 setelah perubahan |

## 7. Yang belum diverifikasi (butuh perangkat)

Perilaku UI sesungguhnya **belum** diuji di perangkat — repo ini hanya punya unit test, tidak ada
test Compose instrumentasi. Yang perlu dicek manual di HP:

1. buka detail koin **tanpa** posisi → hanya mode BUY yang tampil (tanpa toggle);
2. beli → halaman berpindah ke mode SELL secara otomatis;
3. tekan "Tambah posisi (DCA)" → dialog terbuka, eksekusi beli berjalan seperti mode BUY;
4. jual seluruh posisi → halaman kembali ke mode BUY;
5. pastikan tidak ada lagi kartu "BACKTEST BASELINE" di halaman detail.
