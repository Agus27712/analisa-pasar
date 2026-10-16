# Laporan Batch 5f — J5: fallback lintas bursa di jurnal trade dihapus

**Tanggal:** 2026-10-16
**Branch:** `patch`
**Temuan:** J5 (`docs/bugs/temuan_bugs_jurnal_pnl.md` §5)

## Ringkasan

`TradeHistoryRecorder` punya dua fallback lintas bursa:

```kotlin
// recordSell — jual BTCIDR di Indodax bisa menutup record HOLDING BTCIDR milik Tokocrypto
dao.getActiveHoldingForSymbolAndExchange(symbol, isReal, exchange)
    ?: dao.getActiveHoldingForSymbol(symbol, isReal)      // ← SEMUA bursa

// processPriceTick — harga bursa A memperbarui peak/trough/drawdown record bursa B
dao.getHoldingRecordsByExchange(exchange)
    .filter { it.symbol == normSymbol }
    .ifEmpty { dao.getHoldingRecords().filter { it.symbol == normSymbol } }  // ← SEMUA bursa
```

Ini nyata di domain ini karena Tokocrypto melayani **USDT + IDR** sedangkan Indodax **hanya
IDR** (AGENTS.md §"Batasan pasar") — `BTCIDR`/`ETHIDR`/`XAUTIDR` ada di kedua bursa sekaligus.
Yang berbeda pasarnya, bukan simbolnya.

## Perbaikan

Resolver murni baru `app/src/main/java/agu/analys/database/HoldingResolution.kt`
(fungsi `suspend` karena DAO `suspend`):

| Fungsi | Menegakkan |
|---|---|
| `resolveForSell` | uuid bila ada (uuid **kosong** = tidak ada, bukan short-circuit miss); selain itu hanya query berversi exchange; kosong berarti kosong |
| `holdingsForPriceTick` | hanya record HOLDING milik bursa yang sama; filter simbol abaikan kapitalisasi; tidak ada percabangan "semua bursa" |

Pola meniru `SignalLogRepository.processPriceTick` yang sudah benar sejak awal — query berversi
exchange, tanpa fallback, dengan komentar eksplisit. `processBatchPriceTicks` sudah berversi
exchange sejak awal — tidak diubah.

## Uji & kontrol negatif

- **Uji baru:** `app/src/test/java/agu/analys/database/HoldingResolutionTest.kt` — **11 kasus**:
  pencarian uuid, uuid kosong diperlakukan seperti tidak ada, uuid miss tidak jatuh ke bursa
  lain, **bursa ini kosong → null** (inti J5 bagian 1), `isReal` dibedakan, dan enam kasus tick
  (termasuk **bursa ini kosong → kosong**, inti J5 bagian 2).
- **Kontrol negatif:** fallback dikembalikan (`?: bySymbolAndExchange(…, "")` dan
  `.ifEmpty { byExchange("") … }`) ⇒ **tepat 2 dari 11 uji GAGAL** — keduanya justru penjaga inti
  J5. Sembilan yang tetap lulus memang tidak membedakan versi (jalur uuid, pencocokan simbol,
  abaikan kapitalisasi, record bursa sendiri). Dicatat apa adanya.
- **Angka terukur (dari XML):** 73 kelas / 582 uji → **74 kelas / 593 uji**, 0 gagal, 1 dilewati.

## Perubahan perilaku

- Jual tanpa uuid yang dulu **kebetulan** menutup record bursa lain kini mencatat penjualan
  tanpa menutup record apa pun. Posisi bursa lain tetap terbuka sampai benar-benar dijual di
  bursanya sendiri.
- `peakPriceDuringHold` per posisi hanya bergerak pada harga bursanya sendiri; statistik
  MFE/drawdown tidak lagi tercemar harga bursa lain.

## Batas perbaikan

- Jalur **uuid** tidak divalidasi silang dengan exchange: uuid unik global, jadi tidak mungkin
  campur bursa — sesuai audit.
- Uji hanya menjangkau resolver murni; `TradeHistoryRecorder` sendiri (Room, coroutine)
  tidak diuji otomatis di batch ini.
