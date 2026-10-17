# Laporan Batch 6a — J10 (sisa): mesin exit paper tidak lagi memakai SL/TP pair lain

**Tanggal:** 2026-10-17
**Branch:** `patch`
**Temuan:** J10 (sisa) — `docs/bugs/temuan_bugs_jurnal_pnl.md` §2.1

## Ringkasan

Batch 5a (`9767c88`) menutup J10 di **jurnal**: `TradeSignalSnapshot.capture` menggerbang sinyal
dengan `SignalSymbolAttribution.matches`, dan kedua jalur beli memakai `attributedFields`. Tetapi
jalur **beli paper manual** masih meng-*arm* mesin exit dengan SL/TP **mentah** dari
`engine.signalState.value` (`TradingViewModelSync.kt:343-350`).

Jendelanya sama dengan J4: `selectPair()` mengganti pair lebih dulu, sedangkan `signalState` masih
berisi hasil evaluasi pair lama sampai tick berikutnya. Bedanya dengan jurnal — di sini nilainya
dipakai untuk **keputusan**:

- `stopLoss` masih disaring `PaperStopResolver.resolve` (`0 < SL < entry`), jadi SL pair lain yang
  jauh di atas entry ditolak → jatuh ke lantai friksi. Tetapi SL yang kebetulan di bawah entry
  **dan** di bawah lantai friksi tetap dipakai — SL terlalu lebar untuk koin itu.
- `tp1`/`tp2` **tidak** disaring. `bar.high >= tp1` memicu partial TP + geser SL ke BE; TP1 pair
  lain yang berada **di bawah** entry posisi ini bisa memicu **jual sebagian pada tick pertama**.

## Perbaikan

Resolver murni baru `app/src/main/java/agu/analys/trading/PaperArmSignal.kt`:

```kotlin
fun resolve(requestedSymbol, signalSymbol, stopLoss, tp1, tp2): PaperArmParams =
    if (SignalSymbolAttribution.matches(requestedSymbol, signalSymbol))
        PaperArmParams(stopLoss, tp1, tp2)
    else
        PaperArmParams(0.0, 0.0, 0.0)
```

Nol berarti "tidak ada TP/SL dari sinyal": SL jatuh ke lantai friksi, TP tidak ada. Validasi sisi
harga tetap milik `PaperStopResolver` — resolver ini hanya soal atribusi.

`TradingViewModelSync` kini membaca `engine.signalState.value` **sekali** ke `engineSignal`, lalu
memakai `PaperArmSignal.resolve(...)` untuk `stopLoss`/`tp1`/`tp2`.

## Uji & kontrol negatif

- **Uji baru:** `app/src/test/java/agu/analys/trading/PaperArmSignalTest.kt` — **8 kasus**: simbol
  sama meneruskan nilai, simbol beda mengosongkan ketiganya (inti), simbol sinyal kosong, simbol
  diminta kosong, format simbol berbeda (`btc_idr` vs `BTC/IDR`), nilai nol tetap nol, SL negatif
  tidak divalidasi di sini, dan atribusi tidak bergantung bursa.
- **Kontrol negatif:** resolver dikembalikan ke perilaku lama (nilai mentah tanpa cek kepemilikan)
  ⇒ **tepat 3 dari 8 uji GAGAL** — ketiganya penjaga kepemilikan. Lima yang tetap lulus memang
  tidak membedakan versi. Dicatat apa adanya.
- **Angka terukur (dari XML):** 74 kelas / 593 uji → **75 kelas / 601 uji**, 0 gagal, 1 dilewati.

## Batas

- Jalur **auto-buy** tidak diubah: sinyalnya berasal dari transisi untuk `pair.symbol` itu sendiri,
  jadi tidak ada jendela pair lain.
- Wiring `armPaperExitEngine` di `TradingViewModelSync` tidak diuji otomatis; yang diuji
  resolver-nya. Bila wiring dikembalikan ke nilai mentah, uji ini **tidak** akan gagal — batas yang
  sama dengan perbaikan J4 sebelumnya.
