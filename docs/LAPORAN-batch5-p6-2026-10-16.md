# Laporan Batch 5c — P6: dua jalur pemantauan menghasilkan exit berbeda

**Tanggal:** 2026-10-16 · **Branch:** `patch` · **Tier:** 🟠 serius
**Temuan:** P6 (`docs/bugs/temuan_bugs_paper_trade.md` §3)

---

## 1. Ringkasan

| | |
|---|---|
| Masalah | Trade yang sama bisa keluar `EARLY_PROFIT` atau `TP2` hanya karena pair-nya kebetulan dibuka |
| Akar | Pemanggil foreground mengirim jendela momentum; pemanggil latar mengirim kosong |
| Perbaikan | Jendela **tidak lagi dikirim pemanggil** — dibaca dari satu sumber: cache M1, digerbang `PaperExitMomentumWindow` |
| Uji baru | `PaperExitMomentumWindowTest` — 12 kasus |
| Kontrol negatif | Aturan kesegaran dihapus ⇒ **5 dari 12 uji gagal** |
| Angka terukur | 70 kelas / 549 uji → **71 kelas / 561 uji**, 0 gagal, 1 dilewati |

---

## 2. Mengapa ini lebih dari sekadar "jarang terjadi"

`EARLY_PROFIT` hanya mungkin bila jendela tidak kosong:

```kotlin
// PaperExitResolver.onBar
if (params.mode.usesEarlyProfit && window14.isNotEmpty() && …) {
    if (PaperExitResolver.isMomentumWeak(window14)) { … }
}
```

Jadi keputusan exit bergantung pada **apa yang sedang dilihat pengguna**, bukan pada keadaan
posisi. Dua akibatnya:

1. Jurnal berisi dua alasan berbeda untuk trade yang seharusnya identik — dan alasan itu yang
   dipakai untuk menilai "apakah scalp punya edge".
2. App tidak sebanding dengan `tools/scalp_replay.py`. Paritas app↔replay adalah alasan jurnal ini
   ada; kalau hasilnya bergantung pada layar yang terbuka, perbandingannya kehilangan arti.

---

## 3. Yang diubah

### 3.1 `trading/PaperExitMomentumWindow.kt` (baru)

| Bagian | Keputusan |
|---|---|
| Sumber | cache M1 `MtfCacheManager` — sama untuk semua pemanggil |
| Syarat segar | **mengikuti `MtfCacheManager.isCacheValid(M1)`**: ≥20 bar, umur ≤180 detik |
| Ukuran | `takeLast(15)` — sama dengan jendela replay `bars.subList(max(0, j-14), j+1)` |
| Bila tidak segar | kosong → engine menahan diri dari early-profit |

Ambangnya **bukan angka baru**. `MtfCacheManager.isCacheValid` sudah memakai 20 bar / 180 detik
untuk M1; memakai ambang lain berarti menciptakan dua definisi "M1 masih segar" dalam satu aplikasi.

### 3.2 `viewmodel/PaperAutoTradeCoordinator.kt`

Parameter `window` **dihapus** dari `evaluatePaperAutoExit`. Jendela diresolusi di dalam:

```kotlin
val window = PaperExitMomentumWindow.resolve(
    candles = MtfCacheManager.getCachedCandles(symbol, Timeframe.M1, exchange),
    nowMs = now
)
```

### 3.3 Kedua pemanggil kini identik

```kotlin
// TradingViewModel.kt — foreground
runCatching { evaluatePaperAutoExit(symbol, currentEx, price) }
// TradingViewModelSync.kt — latar
runCatching { evaluatePaperAutoExit(tick.symbol, currentEx, tick.price) }
```

---

## 4. Efek yang disengaja

`EARLY_PROFIT` kini **lebih jarang** di jalur foreground — persis itulah inti temuan. Yang
dijamin bukan "jendela selalu tersedia", melainkan **"kedua jalur mendapat jendela yang sama"**.

Bila sebuah simbol tidak ada di cache M1 (mis. tidak masuk watchlist/riwayat yang diantrekan
`MtfCacheManager`), kedua jalur sama-sama mendapat jendela kosong. Itu konsisten, walaupun
berarti early-profit tidak dievaluasi untuk posisi itu — sebelum perbaikan pun jalur latar
memang begitu, hanya jalur foreground yang "istimewa".

---

## 5. Uji & kontrol negatif

**12 kasus** di `PaperExitMomentumWindowTest`: konversi stempel detik/milidetik, `isFresh`
(segar, <20 bar, kosong/null, >180 detik, batas tepat 180.000 ms, stempel detik), dan `resolve`
(15 terakhir, cache basi, kurang bar, null, tidak pernah lebih dari 15).

**Kontrol negatif.** Aturan kesegaran dihapus: `isFresh` selalu `true`, `resolve` meneruskan apa
adanya. Hasil: **5 dari 12 uji GAGAL**. Tujuh yang tetap lulus dicatat apa adanya karena memang
tidak menguji aturan kesegaran: dua konversi stempel, "cache segar layak dipakai", "jendela
diambil dari cache segar", "null diperlakukan seperti kosong", dan "tidak pernah lebih dari 15".

---

## 6. Yang belum ikut berubah

Bagian kedua P6 — **`peak` di jalur latar tertinggal dari high M1** (bar sintetis dibuat dengan
OHLC identik dan `volume = 0.0`, sampling 10 detik) — **tidak** diperbaiki, dan ini dicatat bukan
diklaim. Alasannya:

- Ini bukan cabang kode yang berbeda antar pemanggil — kedua jalur membuat bar sintetis yang sama.
  Jadi memperbaikinya **tidak** menutup "dua jalur berbeda"; itu perubahan terpisah.
- Akar masalahnya adalah **tidak ada umpan intrabar**: `peak` hanya bisa setinggi harga tick yang
  kebetulan tersampel.
- Memaksa `bar.high` dari candle M1 terakhir berarti menghitung ulang bar yang sudah dihitung
  mesin, dan berisiko menggandakan peak/time-stop/ATR. Perubahan seperti itu perlu temuan dan uji
  sendiri, bukan disisipkan di sini.
