# Laporan Batch 6d — P8: `barsHeld` tidak lagi dihitung dua kali sesudah rehidrasi

**Tanggal:** 2026-10-17
**Branch:** `patch`
**Temuan:** P8 — `docs/bugs/temuan_bugs_paper_trade.md`

## Ringkasan

Dua jalur menghitung menit yang sama:

1. `PaperRehydrate.mergedBarsHeld(stored, openedAt, now)` menghitung `barsHeld` dari jam dinding:
   `max(stored, (now − openedAt) / 60_000)` — menit yang berjalan saat app tertutup sudah masuk.
2. `PaperAutoTradeRuntime.put(..., markFirstTickAsNewBar = true)` menandai bucket menit dengan
   sentinel `FIRST_TICK_BUCKET = -1L`, sehingga tick pertama sesudah rehidrasi dianggap **bar baru**
   dan `PaperExitStateMachine.onBar` menaikkan `barsHeld` **sekali lagi**.

Akibatnya time-stop keluar **1 bar (1 menit) lebih awal** daripada seharusnya — app dan
`tools/scalp_replay.py` tidak lagi sebanding, padahal paritas itu alasan utama jurnal paper ada.

Yang tidak boleh disederhanakan: tick pertama itu **tetap harus dievaluasi sebagai bar baru**.
Kalau tidak, posisi yang seharusnya sudah kehabisan waktu baru ditutup pada batas menit berikutnya
(regresi ke perilaku sebelum `markFirstTickAsNewBar` ada).

## Perbaikan

**Memisahkan "dievaluasi" dari "dihitung".**

`engine/scalping/PaperExitResolver.kt`:

```kotlin
fun onBar(
    bar: CandleBar,
    window14: List<CandleBar>,
    isNewBar: Boolean = true,
    countAsNewBar: Boolean = isNewBar   // default: perilaku lama tidak berubah
): PaperExitDecision? {
    ...
    if (countAsNewBar) barsHeld++
```

Gate time-stop (baris ~656) tetap membaca `isNewBar` — itu yang membuat time-stop langsung dicek
pada tick pertama sesudah rehidrasi.

`trading/PaperFirstBar.kt` (baru) — keputusan murni:

```kotlin
object PaperFirstBarResolver {
    fun countsAsNewBar(isNewBar: Boolean, firstTickAfterRehydrate: Boolean): Boolean =
        isNewBar && !firstTickAfterRehydrate
}
```

`viewmodel/PaperAutoTradeCoordinator.kt` — `markBarBucket` kini melaporkan **fakta**, bukan
keputusan:

```kotlin
fun markBarBucket(exchange: String, symbol: String, now: Long): BarBucketMark {
    val k = key(exchange, symbol); val bucket = now / 60_000L
    val last = lastBarBucketMap[k]; lastBarBucketMap[k] = bucket
    return BarBucketMark(
        isNewBar = last == null || last != bucket,
        firstTickAfterRehydrate = last == FIRST_TICK_BUCKET
    )
}
```

Situs evaluasi memetakannya lewat resolver:

```kotlin
val barMark = PaperAutoTradeRuntime.markBarBucket(exchange, symbol, now)
val countAsNewBar = PaperFirstBarResolver.countsAsNewBar(
    isNewBar = barMark.isNewBar,
    firstTickAfterRehydrate = barMark.firstTickAfterRehydrate
)
val decision = machine.onBar(bar, window, isNewBar = barMark.isNewBar, countAsNewBar = countAsNewBar)
```

**Kenapa tidak menambah field runtime baru.** Penanda "baru saja rehidrasi" sengaja tidak disimpan
sebagai field terpisah: sentinel `-1L` sudah ada di `lastBarBucketMap` dan **terhapus pada
pemanggilan `markBarBucket` pertama**, sehingga pengecualian hanya berlaku untuk satu tick.
Penanda yang bertahan akan menahan time-stop lebih lama — arah kesalahan yang berlawanan dan lebih
sulit terlihat. Konsekuensi lain: `clear`/`clearAll` tidak perlu diubah.

## Uji & kontrol negatif

**Uji baru:** `app/src/test/java/agu/analys/trading/PaperFirstBarTest.kt` — **7 kasus**:

| Kelompok | Kasus |
|---|---|
| Resolver murni (4) | bar baru biasa → `true`; menit sama → `false`; tick rehidrasi → `false`; penanda rehidrasi tanpa bar baru → `false` |
| Integrasi mesin (3) | `barsHeld` 4/5 tidak naik pada tick rehidrasi; time-stop tetap terpicu saat `barsHeld` 5/5; bar M1 berikutnya tetap menambah (4 → 5 → TIMEOUT) |

Dua uji integrasi pertama adalah pasangan yang saling mengunci: yang satu menolak hitungan maju,
yang lain menolak time-stop tertunda.

`app/src/test/java/agu/analys/viewmodel/PaperAutoTradeRuntimeTest.kt` disesuaikan: enam pemanggil
`markBarBucket` kini membaca `.isNewBar`, ditambah asersi `.firstTickAfterRehydrate` ( `true` untuk
`markFirstTickAsNewBar = true`, `false` untuk arm biasa).

**Kontrol negatif (dua arah):**

| Kontrol | Perubahan | Hasil |
|---|---|---|
| A — resolver dimatikan | `countsAsNewBar` → `isNewBar` (abaikan penanda) | **3 dari 7 GAGAL**: tick rehidrasi menambah hitungan, `barsHeld` naik 4→5, dan `barsHeld` naik 5→6 saat exit |
| B — pemisahan di `onBar` dimatikan | `if (countAsNewBar)` → `if (isNewBar)` | **2 dari 7 GAGAL**: `barsHeld` naik 4→5 dan 5→6 |

Kontrol B penting: ia membuktikan uji tidak hanya menguji resolver, tetapi juga **pemakaiannya** —
kalau resolver benar tetapi `onBar` mengabaikannya, uji tetap gagal.

**Angka terukur (dari `app/build/test-results/testDebugUnitTest/*.xml`, setelah direktori hasil
dihapus):** 77 kelas / 619 uji → **78 kelas / 626 uji**, **0 gagal, 0 error, 1 dilewati**.

## Batas

- **Bukan** perbaikan P6 (sisa `peak`): `peak` masih hanya bergerak dari bar sintetis
  `high = low = close = currentPrice`, bukan high M1 dari cache. Itu tetap temuan hidup.
- Yang diperbaiki hanya **hitungan** `barsHeld` pada tick pertama sesudah rehidrasi. Perilaku
  `mergedBarsHeld` sendiri tidak diubah — ia sudah benar.
- Wiring di `PaperAutoTradeCoordinator` (fungsi besar ber-`ViewModel`) tidak diuji otomatis;
  yang diuji resolver murni + `PaperExitStateMachine` + `PaperAutoTradeRuntime`. Pemetaan
  `barMark → countAsNewBar` adalah tiga baris tanpa cabang.
- Jalur replay Python (`tools/scalp_replay.py`) tidak menyentuh rehidrasi sama sekali, jadi tidak
  perlu perubahan di sana; paritas kini kembali karena app tidak lagi menambah bar ekstra.

## Berkas

| Berkas | Perubahan |
|---|---|
| `app/src/main/java/agu/analys/trading/PaperFirstBar.kt` | **baru** — resolver `PaperFirstBarResolver` |
| `app/src/main/java/agu/analys/engine/scalping/PaperExitResolver.kt` | `onBar` menerima `countAsNewBar` (default `= isNewBar`); `barsHeld++` memakai parameter itu |
| `app/src/main/java/agu/analys/viewmodel/PaperAutoTradeCoordinator.kt` | `markBarBucket` mengembalikan `BarBucketMark`; situs evaluasi memakai resolver |
| `app/src/test/java/agu/analys/trading/PaperFirstBarTest.kt` | **baru** — 7 uji |
| `app/src/test/java/agu/analys/viewmodel/PaperAutoTradeRuntimeTest.kt` | 6 pemanggil disesuaikan + asersi `firstTickAfterRehydrate` |
