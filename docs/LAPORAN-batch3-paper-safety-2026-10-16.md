# Laporan — Batch 3b: Keamanan siklus hidup posisi paper (P2, P3, P4)

**Tanggal:** 2026-10-16
**Branch:** `patch`
**Sumber temuan:** `docs/bugs/temuan_bugs_paper_trade.md` (audit read-only agen lain, `patch` @ `eb01dc3`)
**Cakupan:** P2 (gerbang mode + lantai friksi SL), P3 (DCA & serah-terima SL tidak valid),
P4 (`atrPct` jalur beli manual). Batch 3 dikerjakan dalam tiga commit terpisah; ini yang kedua.

> **Batas yang harus dibaca lebih dulu:** seluruh temuan `P*` hanya menyentuh posisi **paper**
> (`isReal = false`). Ini **bukan** kerusakan uang riil. Tetapi posisi paper adalah dasar seluruh
> keputusan "apakah scalp punya edge", jadi angkanya harus benar sebelum uang riil dipromosikan.
> Yang menyentuh uang riil ada di area lain (mis. J7 di `temuan_bugs_jurnal_pnl.md`).

---

## 1. Ringkasan

| # | Temuan | Status | Bukti |
|---|---|---|---|
| **P2** | Auto-buy menerima sinyal SWING/INTRADAY dengan SL 1,5–2,8% (jauh di bawah lantai friksi 5,8128%) | ✅ **DIPERBAIKI** | `PaperStopResolverTest` (16 uji) |
| **P3** | DCA tidak me-re-arm mesin exit; `HandBackToLegacy` menyalakan auto-sell dengan SL ≥ entry ⇒ jual paksa berlabel `STOP_LOSS` | ✅ **DIPERBAIKI** | uji skenario P3 + sweep |
| **P4** | `atrPct` tidak diteruskan di jalur beli manual ⇒ S8 (BE/trailing berbasis ATR) mati untuk separuh posisi | ✅ **DIPERBAIKI** | uji `PaperAtr.of` |

**Temuan yang tetap hidup di area ini:** P1 (rehidrasi hanya bursa aktif) — dikerjakan di commit
ketiga batch ini; P5 (kartu FSM dihapus tick pair lain), P6 (dua jalur pemantauan menghasilkan exit
berbeda), P7, P8.

---

## 2. P2 — gerbang mode + lantai friksi untuk SL sinyal

### 2.1 Masalah

`tryPaperAutoBuy` memeriksa dua hal saja:

```kotlin
if (signal.action != SignalAction.BUY) return false
if (!cfg.stageQualifies(signal.scalpingStage)) return false
// transition.mode TIDAK pernah diperiksa
```

`minStage` default `ScalpingStage.ENTRY` dan `stageQualifies` = `ordinal >= minStage.ordinal`.
Evaluator SWING dan INTRADAY **memasang `ScalpingStage.ENTRY` untuk sinyal BUY**, jadi sinyal mereka
lolos ambang yang sama dengan sinyal scalping.

SL yang mereka bawa jauh lebih rapat:

```kotlin
// IntradayEvaluator
val calculatedSl = maxOf(supportLevel - (intradayAtr * 0.3), price * 0.972)
    .coerceAtMost(price * 0.985)      // jarak 1,5% – 2,8%
```

Lantai friksi Tokocrypto USDT = `6,0 × (0,4044 + 0,4044 + 2 × 0,08)` = **5,8128%**.
`ScalpingRiskEngine` menegakkan lantai itu untuk sinyal scalping — tetapi jalur posisi paper
melewatinya begitu saja: `armPaperExitEngine` memakai SL sinyal apa adanya selama "valid"
(`0 < SL < entry`), tanpa memeriksa lantai.

### 2.2 Dampak

- Satu SL = **−1,3R…−2R** padahal friksi round-trip ≈ 0,97% (SL tidak pernah bisa "aman").
- Jurnal paper — dasar seluruh keputusan edge — tercampur strategi ber-horizon harian.
- Mesin exit **scalping** dengan time-stop `maxHoldBarsM1` (~2 jam) memotong posisi harian sebagai
  `TIMEOUT` palsu.
- `transition.mode` hanya dipakai sebagai label di log.

### 2.3 Perbaikan

**a. Gerbang mode** di `tryPaperAutoBuy`:

```kotlin
if (transition.mode != StrategyMode.SCALPING) {
    if (cfg.stageQualifies(signal.scalpingStage)) { /* log diagnostik */ }
    return false
}
if (!cfg.stageQualifies(signal.scalpingStage)) return false
```

Ditempatkan **sebelum** `stageQualifies`, bukan sesudah: kalau sesudah, cabang log diagnostiknya
tidak akan pernah tercapai (sinyal non-scalping sudah keluar lebih dulu lewat ambang stage).

**b. Resolver murni** `engine/scalping/PaperStopResolver.kt` — `frictionFloorPct(...)` dan
`resolve(signalSl, entry, floorPct)`. Aturannya **hanya melebar, tidak pernah menyempitkan** SL:

```kotlin
if (!entry.isFinite() || entry <= 0.0) return ResolvedPaperStop(0.0, NONE)

val floorActive = floorPct.isFinite() && floorPct > 0.0 && floorPct < 100.0
val signalValid = signalSl.isFinite() && signalSl > 0.0 && signalSl < entry

if (!floorActive) {                       // lantai mati: pakai SL sinyal bila valid, atau NONE
    return if (signalValid) ResolvedPaperStop(signalSl, SIGNAL)
           else ResolvedPaperStop(0.0, NONE)
}

val floorPrice = entry * (1.0 - floorPct / 100.0)
return if (signalValid && signalSl <= floorPrice) ResolvedPaperStop(signalSl, SIGNAL)
       else ResolvedPaperStop(floorPrice, FRICTION_FLOOR)
```

"Lebih lebar" berarti harga SL lebih **rendah** (risiko per unit lebih besar); mengambil yang lebih
rendah membuat posisi tidak pernah memakai stop yang lebih rapat daripada yang diizinkan lantai.

`frictionFloorPct` mengembalikan `0.0` (lantai mati) bila kelipatan ≤ 0, biaya ≤ 0, atau lantainya
≥ 100% — supaya pengaturan ekstrem menghasilkan "lantai nonaktif", bukan harga SL negatif.

Resolver ini kini satu-satunya penentu harga SL posisi paper, dipakai **empat** jalur: auto-buy
(`armPaperExitEngine`), beli manual (`TradingViewModelSync`), re-arm DCA, dan serah-terima ke jalur
auto-sell lama saat rehidrasi.

---

## 3. P3 — DCA & SL yang berada di atas entry

### 3.1 Masalah

DCA mengubah `entry` posisi menjadi harga rata-rata:

```kotlin
val totalCost = (currentPos.entryPrice * currentPos.quantity) + (fillPrice * order.quantity)
positionStore.setHolding(..., entry = weightedAvg, quantity = totalQty, ...)
```

Tetapi mesin exit dipanggil dengan `force = false` (default), sehingga mesin lama yang memakai entry
fill **pertama** tidak pernah diganti. `SpotPositionStore.setHolding` juga mempertahankan SL lama
apa adanya.

### 3.2 Rantai jual paksa

| Langkah | Keadaan |
|---|---|
| 1 | Posisi dibuka: `entry 100`, `SL 95` |
| 2 | DCA di harga rendah ⇒ rata-rata turun ke `94`, SL di store tetap `95` |
| 3 | Restart ⇒ `PaperRehydrate`: `stopLoss (95) >= entry (94)` ⇒ `HandBackToLegacy` |
| 4 | Cabang itu menulis SL `95` apa adanya **sambil menyalakan auto-sell** |
| 5 | Tick berikutnya: `currentPrice <= 95` ⇒ jalur legacy menjual **seluruh** posisi di harga pasar, dilabeli `STOP_LOSS` |

Tidak ada stop yang tersentuh, tetapi jurnal mencatat stop-out. Sementara itu di kasus DCA di harga
**lebih tinggi**, rata-rata ≠ `machine.entry` sehingga `restoreRejection` menolak dan progres FSM
dibuang tiap restart — posisi berjalan dengan SL/TP fill pertama.

### 3.3 Perbaikan

- `TradingViewModelSync` — `isDca` dihitung sekali, `effectiveEntry` menggantikan `fillPrice`, dan
  `armPaperExitEngine(..., force = isDca)`. Ini satu-satunya jalur yang boleh menimpa mesin hidup.
- `HandBackToLegacy` di `PaperAutoTradeCoordinator` — SL diambil dari `PaperStopResolver` yang sama,
  dan `enabled = resolvedHandBack.usable`:

| Sumber SL | Yang terjadi |
|---|---|
| `SIGNAL` | SL tersimpan valid ⇒ dipertahankan, auto-sell dinyalakan |
| `FRICTION_FLOOR` | SL tersimpan tidak valid ⇒ dipakai SL lantai friksi, auto-sell dinyalakan |
| `NONE` | SL tidak valid **dan** lantai friksi nonaktif ⇒ auto-sell **sengaja tidak** dinyalakan |

Pilihan pada baris terakhir disengaja: **posisi tanpa stop lebih baik daripada penjualan pasar
berlabel `STOP_LOSS` yang salah.** Posisi seperti itu tetap terlihat di UI dan bisa dijual manual.

- Catatan log per sumber SL supaya keputusan itu terbaca pengguna.

### 3.4 Penyimpangan dari rekomendasi audit

Audit menyarankan resolver `PaperRearmDecision.decide(prevMachine, newEntry, newQty, storedSl)`
dengan tiga cabang (`Rearm`/`Reset`/`Keep`). Yang dipakai di sini: `force = isDca` di pemanggil DCA,
dan invarian "SL < entry" ditegakkan di `PaperStopResolver` yang dipakai semua jalur.

Alasannya dicatat di sini supaya tidak dianggap terlewat: cabang `Reset` dan `Keep` belum punya
konsumen nyata di kode saat ini, sehingga menambahkan objek keputusan itu hanya akan menghasilkan
kode mati dengan test sendiri. Invariannya sendiri — yang justru menjadi inti bug ini — sudah
diuji lewat sweep `resolve`.

---

## 4. P4 — `atrPct` di jalur beli manual

`atrPctAt` adalah fungsi `private` di `PaperAutoTradeCoordinator`, jadi `TradingViewModelSync` tidak
bisa memakainya dan mengirim `atrPct = 0.0` untuk setiap posisi hasil beli manual. Karena `0.0`
berarti "ATR tidak diketahui", mesin jatuh ke ambang BE/trailing **persen tetap** — dan nilai nol
itu ikut tersimpan ke disk lewat `persistPaperFsm`, jadi kerusakannya **persisten**, bukan hanya satu
sesi.

Perbaikan: rumusnya diekstrak ke `engine/scalping/PaperAtr.kt`:

```kotlin
fun of(atr: Double, referencePrice: Double): Double {
    if (!referencePrice.isFinite() || referencePrice <= 0.0) return 0.0
    if (!atr.isFinite() || atr <= 0.0) return 0.0
    val pct = atr / referencePrice * 100.0
    return if (pct.isFinite() && pct > 0.0) pct else 0.0
}
```

Dipakai `atrPctAt` **dan** jalur beli manual. Ini sekaligus efek samping perbaikan P3: nilai
`atrPct` dikirim bersamaan dengan `force = isDca`.

---

## 5. Angka terukur (dari XML, bukan klaim)

Angka di bawah diukur pada **pohon commit ini apa adanya**: berkas P1 (`PaperRehydrateScope.kt`,
`PaperEnginePosition.kt`, dua berkas ujinya) dan perubahan `SpotPositionStore` sementara
dikeluarkan dari pohon supaya yang diukur benar-benar isi commit ini, bukan isi commit berikutnya.

| Pohon | Kelas | Total | Lulus | Gagal | Dilewati |
|---|---|---|---|---|---|
| Baseline setelah batch 2 (§29) | 63 | 462 | 461 | 0 | 1 |
| Commit 1 batch 3 (§30, batch 3a) — *diturunkan* | 64 | 476 | 475 | 0 | 1 |
| **Commit ini (§31, batch 3b)** | **65** | **492** | **491** | **0** | **1** |
| Setelah commit 3 (§32, P1) — untuk pembanding | 67 | 505 | 504 | 0 | 1 |

Baris commit 1 dan commit 3 **diturunkan**, bukan diukur ulang: commit 1 hanya menambah
`SignalOutcomeClassificationTest` (14 uji, satu-satunya berkas uji di commit itu), dan commit 3
hanya menambah `PaperRehydrateScopeTest` (7) + `SpotPositionStorePaperEngineScopeTest` (6). Baris
baseline, baris commit ini, dan baris akhir diukur langsung dari XML.

Diukur dari `app/build/test-results/testDebugUnitTest/*.xml`; direktori hasil dihapus lebih dulu
supaya Gradle benar-benar menjalankan ulang, bukan memakai hasil basi.
`PaperStopResolverTest` = 16 uji, semuanya lulus.

**Koreksi cara membaca angka §30.** Laporan batch 3a menulis `kelas=67 tests=505` — itu angka
**pohon kerja saat itu**, yang kebetulan sudah memuat berkas P1 yang belum di-commit. Angka pohon
commit 1 yang sebenarnya adalah `64 / 476 / 475`. Selisihnya bukan test yang hilang, hanya berkas
yang belum ikut commit. Angka 67/505 adalah keadaan akhir batch 3 dan tetap benar sebagai angka
akhir.

Kelas produksi baru di commit ini: `PaperStopResolver.kt`, `PaperAtr.kt` (uji keduanya masuk ke
satu kelas uji, `PaperStopResolverTest`, supaya resolver dan pemakainya diuji berdampingan).

---

## 6. Kontrol negatif

Wajib, dan sudah dijalankan. Dua perbaikan dimatikan sekaligus:

| Perbaikan | Cara mematikan |
|---|---|
| P2 (pelebaran ke lantai) | syarat `signalSl < entry` dan `signalSl <= floorPrice` dihapus dari `resolve` |
| P4 (penjaga ATR) | `PaperAtr.of` dibuat tanpa penjaga harga acuan/ATR |

**Hasil: 4 dari 16 uji GAGAL** — tepat pada penjaga masing-masing perbaikan:

| Uji yang gagal | Kenapa |
|---|---|
| `SL sinyal 2 persen dilebarkan ke lantai friksi` | tanpa pelebaran, SL 98 tetap dipakai |
| `SL tersimpan di atas rata-rata DCA tidak dipakai apa adanya` | tanpa syarat `signalSl < entry`, SL 95 (≥ entry 94) lolos apa adanya |
| `resolve tidak pernah menghasilkan SL di atas atau sama dengan entry` | sweep menemukan SL ≥ entry |
| `ATR yang belum tersedia menghasilkan nol, bukan NaN` | tanpa penjaga, ATR kosong menghasilkan `NaN`/`∞` |

---

## 7. Batas yang perlu diketahui

1. **Gerbang mode P2 tidak punya uji otomatis.** `tryPaperAutoBuy` adalah ekstensi `TradingViewModel`
   yang butuh `AppPreferences`, store, dan engine hidup; repo ini belum punya harness untuk itu
   (alasan yang sama seperti pada laporan batch 3a). Yang teruji adalah **invariannya**
   (`PaperStopResolver`, `PaperAtr`), bukan gerbangnya. Gerbang mode baru terbukti lewat pembacaan
   kode + log diagnostik yang sengaja dipasang agar perilakunya terlihat di lapangan.
2. **Perubahan perilaku yang disengaja pada `HandBackToLegacy`.** Posisi dengan SL tidak valid dan
   lantai friksi nonaktif sekarang berjalan **tanpa** auto-sell. Siapa pun yang menganggap
   "auto-sell selalu menyala" sebagai kontrak akan melihat ini sebagai regresi — itu bukan regresi,
   melainkan penggantian penjualan pasar berlabel salah dengan posisi tanpa stop yang jujur.
   Konsekuensinya: dengan lantai friksi dimatikan, posisi seperti itu perlu SL manual.
3. **Belum diuji di perangkat fisik.** Sandbox tidak punya emulator; jalur DCA → restart → rehidrasi
   baru terbukti lewat pembacaan kode dan uji resolver, bukan lewat siklus nyata.
4. **Rekomendasi `PaperRearmDecision` tidak diadopsi** (lihat §3.4). Kalau nanti muncul kebutuhan
   membedakan `Reset` (simbol dipakai ulang) dari `Rearm`, resolver itu bisa ditambahkan — saat ini
   belum ada konsumennya.

---

## 8. Riwayat

| Tanggal | Aksi |
|---|---|
| 2026-10-16 | Audit read-only siklus hidup posisi paper diekspor ke `docs/bugs/temuan_bugs_paper_trade.md` |
| 2026-10-16 | **Batch 3b:** P2 + P3 + P4 diperbaiki + diuji (16 uji, kontrol negatif 4/16 gagal) |
