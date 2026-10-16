# Laporan — Batch 3c: Rehidrasi lintas bursa (P1)

**Tanggal:** 2026-10-16
**Branch:** `patch`
**Sumber temuan:** `docs/bugs/temuan_bugs_paper_trade.md` (audit read-only agen lain, `patch` @ `eb01dc3`)
**Cakupan:** P1 (posisi paper di bursa non-aktif kehilangan mesin exit setelah restart).
Batch 3 dikerjakan dalam tiga commit terpisah; ini yang **ketiga dan terakhir**.
**Commit perbaikan: `e33b238`.**

> **Batas yang harus dibaca lebih dulu:** temuan `P*` hanya menyentuh posisi **paper**
> (`isReal = false`) — bukan kerusakan uang riil. Dan perbaikan ini **memulihkan** proteksi, bukan
> menghidupkannya: lihat §4, yang justru bagian terpenting dari laporan ini.

---

## 1. Ringkasan

| # | Temuan | Status | Bukti |
|---|---|---|---|
| **P1** | Posisi paper di bursa non-aktif kehilangan mesin exit **selamanya** setelah restart | ✅ **DIPERBAIKI** | `PaperRehydrateScopeTest` (7) + `SpotPositionStorePaperEngineScopeTest` (6) |

Dengan ini **ketiga temuan kritis** `temuan_bugs_paper_trade.md` (P1, P2, P3) sudah ditutup.
**Masih hidup di area ini:** P5 (kartu FSM dihapus tick pair lain), P6 (dua jalur pemantauan
menghasilkan exit berbeda), P7, P8.

---

## 2. Masalah

```kotlin
fun TradingViewModel.rehydratePaperAutoExit(): Int {
    val cfg = prefs.paperAutoTradeConfig
    val exchange = prefs.marketDataSource.name          // hanya bursa AKTIF
    val symbols = positionStore.getPaperEngineSymbols(isReal = false, exchange = exchange)
    if (symbols.isEmpty()) return 0
```

Rantai yang benar-benar terjadi:

| Langkah | Keadaan |
|---|---|
| 1 | Posisi paper terbuka di Indodax; mesin exit hanya ada di memori, `auto_sell_enabled = false` (diambil alih `armPaperExitEngine`) |
| 2 | Pengguna pindah bursa aktif ke Tokocrypto |
| 3 | App di-kill, lalu dibuka kembali ⇒ bursa aktif = Tokocrypto |
| 4 | Rehidrasi hanya membaca partisi `tokocrypto_sim_*` ⇒ mesin Indodax **tidak pernah dibangun** |
| 5 | `setMarketDataSource` tidak memicu rehidrasi ⇒ **kembali ke Indodax pun tidak memulihkan apa pun** |

Polling 10 detik menambah masalah: `getAllActiveTrailingSymbols` mensyaratkan
`trailing_enabled = true` sedangkan engine paper **mematikannya**, dan
`PaperAutoTradeRuntime.activeSymbols(ex)` juga hanya bursa aktif — jadi simbol itu tidak ditemukan
lewat kedua jalur.

**Dampak.** Posisi tersebut tidak pernah punya SL, TP, BE, trailing, maupun time-stop —
**selamanya**. Kas simulasi sudah terpotong saat order market diisi, posisi tidak pernah menutup,
jurnal paper tidak pernah mencatatnya, dan `countActivePaperPositions()` (global, tanpa bursa) tetap
menghitungnya sehingga ikut memakan satu dari tiga slot auto-buy. Di layar pun tidak terlihat,
karena `paperSnapshotFor` juga hanya membaca bursa aktif.

---

## 3. Perbaikan

1. **Enumerasi lintas bursa** — `trading/PaperEnginePosition.kt` (baru) +
   `SpotPositionStore.getPaperEnginePositions(isReal)`. Menyisir semua key berakhiran
   `_paper_engine`, mem-parse `"${exchange}_${real|sim}_${symbol}"`, menyaring `HOLDING` dan jenis
   akun, lalu mengembalikan pasangan bursa + simbol. Kuncinya sudah memuat bursa, jadi enumerasinya
   tidak perlu tahu bursa mana yang aktif.
2. **Resolver murni** `engine/scalping/PaperRehydrateScope.kt`:

```kotlin
fun decide(storedExchanges: Collection<String>, activeExchange: String): List<String> {
    val distinct = storedExchanges.map { it.trim().uppercase() }
        .filter { it.isNotEmpty() }.distinct().sorted()
    if (distinct.isEmpty()) return emptyList()

    val active = activeExchange.trim().uppercase()
    if (active.isEmpty() || active !in distinct) return distinct
    return listOf(active) + distinct.filter { it != active }
}
```

   Bursa aktif **didahulukan** supaya posisi yang sedang dilihat pengguna pulih lebih dulu dan
   mesinnya siap saat tick pertama masuk; sisanya alfabetis supaya hasilnya deterministik.
3. **Rehidrasi mengiterasi seluruh cakupan** — target `(exchange, symbol)` dibangun dari
   `decide(...)`, bukan dari satu bursa.
4. **Ganti bursa memicu rehidrasi** — `initSubscriptionsAndPolling` mengumpulkan aliran
   `marketDataSource` dan memicu rehidrasi saat nilainya berubah. Emisi pertama **dilewati** karena
   sudah ditangani rehidrasi awal di fungsi yang sama; setelah itu
   `checkAndStopTrailingServiceIfEmpty()` dipanggil supaya service latar tidak menggantung.
5. **Polling menemukan simbol dari disk** — `startTrailingPolling` menambahkan
   `getPaperEngineSymbols(isReal = false, exchange = currentEx)` ke `activeSymbols`.
   `PaperAutoTradeRuntime` kosong setelah restart sampai rehidrasi selesai, jadi tanpa ini posisi
   paper bisa terlewat satu putaran polling.
6. **Peringatan bursa non-aktif** — setelah rehidrasi, bursa yang tidak aktif dicatat eksplisit di
   log beserta alasannya, supaya posisi yang "diam" tidak terlihat seperti bug tanpa sebab.

---

## 4. Batas yang jujur — memulihkan proteksi, bukan menghidupkannya

Mesin exit untuk bursa non-aktif **dibangun**, tetapi **belum bisa dievaluasi**. Kedua pemanggil
`evaluatePaperAutoExit` memakai bursa aktif:

- `TradingViewModel.kt:157` — `val currentEx = prefs.marketDataSource.name` di dalam
  `onPriceUpdate` `MarketDataCoordinator`;
- `TradingViewModelSync.kt:669` — `evaluatePaperAutoExit(tick.symbol, currentEx, tick.price)` di
  loop polling.

Aplikasi hanya punya umpan harga untuk bursa yang sedang dipakai. Jadi:

| | Sebelum | Sesudah |
|---|---|---|
| Posisi di bursa non-aktif **saat ini** | Tidak terlindungi | Tidak terlindungi (sama) |
| Kembali ke bursa itu | Mesin **tetap kosong** — menggantung permanen | Mesin sudah dipulihkan ⇒ proteksi langsung hidup |
| Progres FSM (BE/trailing/time-stop) | Hilang | Dipulihkan |
| Slot `maxConcurrentPositions` | Terpakai tanpa terlihat | Terpakai, tetapi ada peringatan di log |

Klaim audit bahwa posisi itu "menggantung **permanen**" benar dan itulah yang ditutup. Tetapi jangan
membacanya sebagai "posisi di bursa non-aktif kini terlindungi otomatis" — untuk itu dibutuhkan
umpan harga lintas bursa, yang belum ada dan **tidak** dikerjakan di batch ini.

---

## 5. Angka terukur (dari XML, bukan klaim)

| Pohon | Kelas | Total | Lulus | Gagal | Dilewati |
|---|---|---|---|---|---|
| Baseline setelah batch 2 (§29) | 63 | 462 | 461 | 0 | 1 |
| Setelah commit 1 (§30) — *diturunkan* | 64 | 476 | 475 | 0 | 1 |
| Setelah commit 2 (§31) — terukur | 65 | 492 | 491 | 0 | 1 |
| **Setelah commit ini (§32)** | **67** | **505** | **504** | **0** | **1** |

Diukur dari `app/build/test-results/testDebugUnitTest/*.xml`; direktori hasil dihapus lebih dulu
supaya Gradle benar-benar menjalankan ulang. Baris commit 1 **diturunkan** (commit itu hanya
menambah `SignalOutcomeClassificationTest`, 14 uji) dan ditandai begitu supaya tidak dibaca sebagai
hasil pengukuran.

Uji baru commit ini: `PaperRehydrateScopeTest` (7) dan `SpotPositionStorePaperEngineScopeTest` (6) —
total **+13 uji**, sesuai selisih 492 → 505.

**APK debug:** 27.880.361 byte. Diukur dari `app/build/outputs/apk/debug/app-debug.apk` hasil build
pukul 14:21, yaitu build dari pohon kerja yang isinya **identik** dengan commit ini (berkas P1 sudah
ada di pohon saat itu). Baseline setelah batch 2 = 27.877.730 byte ⇒ +2.631 byte. Tidak ada APK baru
yang dibangun khusus untuk commit ini, jadi angka ini **bukan** hasil build ulang terpisah.

---

## 6. Kontrol negatif

Wajib, dan sudah dijalankan. Dua perbaikan dimatikan sekaligus:

| Perbaikan | Cara mematikan |
|---|---|
| Cakupan bursa | `PaperRehydrateScope.decide` dikembalikan ke "hanya bursa aktif" |
| Enumerasi lintas bursa | filter bursa di `getPaperEnginePositions` di-hardcode ke `tokocrypto` |

**Hasil: 8 dari 13 uji GAGAL.**

---

## 7. Batas lain yang perlu diketahui

1. **Siklus penuh (restart → rehidrasi lintas bursa → ganti bursa) belum diuji otomatis.** Yang
   teruji adalah resolver murni dan enumerasi store (Robolectric). Pemanggilnya —
   `rehydratePaperAutoExit`, pemicu di `initSubscriptionsAndPolling` — adalah ekstensi
   `TradingViewModel` yang butuh `AppPreferences`, store, dan engine hidup; repo ini belum punya
   harness untuk itu.
2. **Belum diuji di perangkat fisik.** Sandbox tidak punya emulator, jadi urutan
   kill-app → buka → ganti bursa tidak pernah dijalankan sungguhan.
3. **`getPaperEnginePositions` bergantung pada format kunci.** Ia mem-parse
   `"${exchange}_${real|sim}_${symbol}"`. Kalau `buildKey` berubah, fungsi ini harus ikut berubah —
   ada uji yang memastikan simbol hasil enumerasi bisa langsung dipakai untuk membaca posisinya,
   tetapi tidak ada yang menjaga format kuncinya sendiri.
4. **Peringatan bursa non-aktif hanya di log**, belum di UI. Pengguna yang tidak membuka log tidak
   akan tahu posisinya sedang tidak terlindungi — ini kandidat perbaikan berikutnya (area UI).

---

## 8. Riwayat

| Tanggal | Aksi |
|---|---|
| 2026-10-16 | Audit read-only siklus hidup posisi paper diekspor ke `docs/bugs/temuan_bugs_paper_trade.md` |
| 2026-10-16 | **Batch 3a:** J2 + J1 diperbaiki + diuji (14 uji baru, kontrol negatif 9/22 gagal) |
| 2026-10-16 | **Batch 3b:** P2 + P3 + P4 diperbaiki + diuji (16 uji, kontrol negatif 4/16 gagal) |
| 2026-10-16 | **Batch 3c:** P1 diperbaiki + diuji (13 uji baru, kontrol negatif 8/13 gagal) |
