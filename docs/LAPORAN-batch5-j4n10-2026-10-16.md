# Laporan Batch 5a — J4 & N10: jurnal memakai simbol yang dievaluasi engine

**Tanggal:** 2026-10-16 · **Branch:** `patch` · **Tier:** 🟠 serius
**Temuan:** J4 (`docs/bugs/temuan_bugs_jurnal_pnl.md` §3) + N10 (`docs/laporan_temuan_bugs_notifikasi.md`)

---

## 1. Ringkasan

| | |
|---|---|
| Masalah | Field turunan sinyal (entry, TP1, TP2, SL, confidence, harga & waktu sinyal) menempel pada baris jurnal/trade milik **simbol lain** |
| Akar | `listenToEngineSignals` **menimpa** `marketSymbol` yang sudah benar; jalur beli memakai `engine.signalState.value` tanpa verifikasi kepemilikan |
| Perbaikan | Satu resolver murni `SignalSymbolAttribution` + gerbang di `TradeSignalSnapshot.capture` |
| Uji baru | `SignalSymbolAttributionTest` — 20 kasus |
| Kontrol negatif | Perilaku lama dipulihkan ⇒ **10 dari 20 uji gagal** |
| Angka terukur | 68 kelas / 522 uji → **69 kelas / 542 uji**, 0 gagal, 1 dilewati |

---

## 2. Jendela terjadinya

```
selectPair("ETHIDR")                       ← pair berganti SEKARANG
  ↓
engine.signalState.value                   ← masih berisi hasil evaluasi BTCIDR
  ↓ (sampai tick berikutnya)
listenToEngineSignals { … }
  symbol = selectedPair.value.symbol       ← "ETHIDR"
  entryPrice/TP1/TP2/SL/confidence         ← milik BTCIDR
```

`SignalHistoryPanel.kt:74` memfilter `marketSymbol == currentSymbol`, jadi seharusnya panel bisa
menyelamatkan keadaan — tetapi `marketSymbol` **sudah ditimpa** menjadi `ETHIDR` di baris 736
sebelumnya. Filter tidak punya lagi informasi yang bisa dipakai.

---

## 3. Yang diubah

### 3.1 `trading/SignalSymbolAttribution.kt` (baru)

| Fungsi | Keputusan yang ditegakkan |
|---|---|
| `normalize(symbol)` | `btc_idr` / `BTC/IDR` / `BTC-IDR` / `BTC IDR` → `BTCIDR` |
| `matches(requested, signalSymbol)` | simbol kosong di salah satu sisi = **tidak cocok** |
| `journalSymbol(signalSymbol, selected)` | simbol engine; fallback ke pair yang dibuka **hanya** bila engine tidak mengisi |
| `isRecordable(symbol)` | baris tanpa simbol tidak boleh ditulis ke jurnal |
| `entryPrice(signalEntryPrice, tickPrice)` | fallback memakai tick **simbol jurnal**, bukan tick pair yang dibuka |
| `attributedFields(...)` | bila tidak cocok → confidence 0, TP/SL 0.0, harga & waktu jatuh ke transaksi nyata |

### 3.2 `trading/TradeSignalSnapshot.kt`

Satu gerbang di pintu masuk `capture`:

```kotlin
val attributedSignal = signal?.takeIf {
    SignalSymbolAttribution.matches(symbol, it.marketSymbol)
}
```

Karena gerbang ada di `capture`, **keempat** pemanggilnya terlindungi sekaligus —
`TradingViewModelSync` (beli real, beli paper), `TradingViewModelOrders` (dua jalur simulasi).
Field yang ikut terbuang bila sinyal milik pair lain: `confidenceScore`, `marketRegime`,
`patternDetected`, `reasons`, `scalpingSetup/Score/Category/Detail/Regime`, `entryZoneLow/High`.

### 3.3 `viewmodel/TradingViewModelSync.kt`

- **Beli real & beli paper** — `signalPrice`, `signalConfidence`, `targetPrice1/2`,
  `stopLossPrice`, `signalTime` lewat `attributedFields(fallbackPrice = harga fill,
  fallbackTime = waktu order)`.
- `tp1`/`tp2` di jalur real **tidak** disentuh: nilainya dari parameter order, bukan dari engine.
- **`listenToEngineSignals`** — memakai `journalSymbol`, `return@collect` bila simbol kosong,
  harga fallback dari `marketDataCoordinator.dashboardTicks.value[journalSymbol]`.

---

## 4. Mengapa nilai dikosongkan, bukan dipakai

Bila sinyal bukan milik simbol ini, confidence menjadi `0` dan TP/SL menjadi `0.0`.

Ini pilihan yang disengaja. Mengisi `72` (skor milik ETH) pada baris BTC lebih berbahaya daripada
mengisi `0`: `72` terbaca sebagai data yang valid dan ikut masuk ke `SignalReliabilitySummary`,
sedangkan `0` terbaca sebagai "tidak ada data" dan tidak ikut mengubah rata-rata tier.
Prinsipnya sama dengan A6 (`bbMiddle` harus `null`, bukan EMA20 berlabel Bollinger).

---

## 5. Uji & kontrol negatif

**20 kasus** di `SignalSymbolAttributionTest`:

| Kelompok | Kasus |
|---|---|
| `normalize` | underscore, slash/strip, spasi |
| `matches` | format beda simbol sama, simbol beda, simbol sinyal kosong, simbol diminta kosong |
| `journalSymbol` | pair sudah berganti (J4), engine tidak mengisi |
| `isRecordable` | kosong vs ada |
| `entryPrice` | harga sinyal ada, jatuh ke tick, tidak ada harga sama sekali |
| `attributedFields` | cocok (semua dipertahankan), tidak cocok → confidence, tidak cocok → TP/SL, tidak cocok → harga & waktu, harga sinyal nol, waktu sinyal nol, `marketSymbol` kosong |

**Kontrol negatif.** `SignalSymbolAttribution.kt` dikembalikan ke perilaku lama:
`matches` selalu `true`, `journalSymbol` mengembalikan pair yang dibuka, `entryPrice` tanpa
fallback tick. Hasil: **10 dari 20 uji GAGAL**. Sepuluh yang tetap lulus memang tidak menguji
atribusi — normalisasi format, `isRecordable`, `entryPrice` saat harga sinyal ada, dan
kasus "simbol sama" yang perilakunya tidak berubah.

Berkas dipulihkan dari cadangan, diverifikasi dengan `grep`, lalu uji diulang ⇒ hijau.

---

## 6. Batas yang jangan dibaca lebih dari kenyataannya

1. **Uji menguji resolvernya, bukan komposisi Compose.** Perilaku `SignalHistoryPanel` setelah
   perbaikan (kartu ETH kini menampilkan hanya sinyal ETH) tidak diuji otomatis — hanya logika
   pemilihan simbolnya.
2. **Jurnal lama tidak dibersihkan.** Baris yang tersimpan sebelum perbaikan tetap memakai
   simbol pair yang dibuka. Tidak ada migrasi yang menandai baris itu.
3. **Efek samping yang diharapkan:** setelah ganti pair, kartu riwayat bisa tampak kosong sampai
   sinyal baru terbit untuk pair tersebut. Itu benar — dulu kartunya terisi, tetapi isinya milik
   pair sebelumnya.
4. **Kaki jual tidak tersentuh** temuan ini; J7 yang menaunginya, dan kaki beli di
   `RadarBuySection.kt:104` / `CustomBuyOrderDialog.kt:92` masih punya masalah basis sendiri
   (fee limit order dinolkan, slippage diabaikan) yang **tidak** punya temuan audit.
