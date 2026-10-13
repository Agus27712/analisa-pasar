# Penyelesaian Milestone P2 — analisa-pasar

**Repo:** `Agus27712/analisa-pasar` · **Branch dasar:** `main` @ `f1c67d2`
**Tanggal:** 2026-10-12 · **Status:** P2 tuntas 9/9, 1 cacat ditemukan & diperbaiki

---

## 1. Ringkasan

Workplan `workplan2.md` §P2 dan §8 menandai seluruh item P2 sebagai selesai `[x]`. Saya
verifikasi klaim itu terhadap kode, bukan terhadap centang di dokumen. Hasilnya:

- **8 dari 9 item benar-benar ada** di kode dan terhubung sampai UI.
- **1 item cacat**: *kepemilikan exit tunggal* — klaimnya tidak benar. Sudah diperbaiki.

Cacat ini bukan kosmetik. Dampaknya: posisi paper bisa **dijual dua kali**, dan trade-nya
**hilang dari jurnal** sehingga kartu statistik melaporkan angka yang salah.

---

## 2. Hasil verifikasi per item

| # | Item P2 (workplan2.md) | Bukti di kode | Status |
|---|---|---|---|
| 1 | Auto-buy paper + `PaperAutoTradeConfig` (nominal tetap / berbasis risiko) | `engine/scalping/PaperAutoTradeConfig.kt`, `tryPaperAutoBuy()` | OK |
| 2 | Exit 5 mode (`classic`/`tp1_be`/`early`/`trail`/`full`) | `ScalpExitConfig.kt`, `PaperExitResolver.resolve()`, `PaperExitStateMachine.onBar()` | OK |
| 3 | Parameter exit di `ScalpingRuntimeConfig.exit` + ekspor JSON | `ScalpExitParams.toJson()`, `data/config/scalp_config_exit_*.json` (5 file, `max_hold_bars_m1: 90`) | OK |
| 4 | Paritas dengan `tools/scalp_replay.py` | `PaperExitResolverParityTest` (39 test) | OK — diverifikasi ulang, lihat §5 |
| 5 | `PaperTradeStats`: TIMEOUT tidak dibuang, BE tidak dihitung kalah | `PaperTradeStats.kt` (`timeouts`, `PaperTradeOutcome.BREAKEVEN`) | OK |
| 6 | Jurnal persisten, maks 500 baris | `util/PaperTradeJournalStore.kt`, `PaperTradeRecord.MAX_RECORDS = 500` | OK |
| 7 | FSM visual + kartu statistik di UI | `PaperExitFsmCard.kt`, `PaperTradeStatsCard.kt` → `TradeSimulationScreen.kt:310,336` | OK |
| 8 | Toggle di Pengaturan | `PaperAutoTradeSettings.kt` → `SettingsScreen.kt:743` | OK |
| 9 | **Kepemilikan exit tunggal** | `PaperAutoTradeRuntime.hasActivePosition()` **tidak pernah dipanggil di kode produksi** | **CACAT → diperbaiki** |

---

## 3. Cacat: kepemilikan exit tunggal

### Klaim di dokumen
> "saat engine P2 aktif untuk sebuah pair, jalur auto-sell lama (`checkTrailingForMode` →
> TP1/TP2/SL dari `positionStore`) dimatikan untuk pair itu, sehingga tidak ada dua mesin
> yang menjual posisi yang sama."

### Kenyataan di kode
`tryPaperAutoBuy()` memang mematikan auto-sell lama:

```kotlin
positionStore.setAutoSellParams(enabled = !p2OwnsExit, ...)
```

Tapi flag `auto_sell_enabled` **hanya** menggerbangi blok TP1/TP2/SL
(`TradingViewModelAlerts.kt:237`). Jalur **trailing** tidak melihat flag itu sama sekali —
`SpotPositionStore.updateTrailingPrice()` hanya memeriksa `isHolding && isTrailingEnabled`:

```kotlin
// SpotPositionStore.kt:509
if (!current.isHolding || !current.isTrailingEnabled || currentPrice <= 0.0) {
    return Pair(current, false)
}
```

`isTrailingEnabled` bisa menyala untuk pair yang exit-nya dipegang engine paper, lewat tombol
**"Deploy Trailing"** di `DetailChartScreen` (`deployTrailingOrder()` menulis
`trailing_enabled = true` tanpa syarat). Selain itu `setHolding()` **mempertahankan**
`isTrailingEnabled` dari posisi sebelumnya (baris 343), jadi bendera trailing lama bisa
terbawa ke posisi paper baru.

Fungsi `PaperAutoTradeRuntime.hasActivePosition()` jelas ditulis sebagai penjaga untuk kasus
ini — tapi hanya dipakai di satu unit test, tidak pernah di kode produksi.

### Akibat
1. Dua mesin bisa menjual posisi simulasi yang sama.
2. Bila jalur lama menang duluan, `finalizePaperExit()` menemukan qty = 0 →
   `dispatched = false` → **tidak ada baris jurnal** → kartu statistik kehilangan trade itu.

### Perbaikan
Empat berkas, 119 baris (termasuk test). Tidak menyentuh jalur uang riil sama sekali.

| Berkas | Perubahan |
|---|---|
| `viewmodel/PaperAutoTradeCoordinator.kt` | Tambah `PaperAutoTradeRuntime.ownsExit()` + helper `TradingViewModel.paperEngineOwnsExit()`. Saat P2 mengambil alih exit, bersihkan sisa `trailing_enabled`. Lepas state machine bila posisi sim sudah tidak ada (cegah FSM hantu yang mengunci kepemilikan selamanya). |
| `viewmodel/TradingViewModelAlerts.kt` | `checkTrailingForMode()` mundur saat engine paper memegang exit (menutup 4 jalur `executeAutoSellOrder` lama sekaligus). `deployTrailingOrder()` menolak dengan jujur dan mengembalikan `Boolean`. |
| `ui/screens/DetailChartScreen.kt` | Toast tidak lagi berbohong "Trailing Aktif!" saat permintaan ditolak. |
| `test/.../PaperAutoTradeRuntimeTest.kt` | +2 test untuk predikat kepemilikan. |

Dua keputusan desain yang saya ambil sengaja:

- **Penjaga hanya untuk `isReal = false`.** Jalur trading riil tidak berubah perilakunya.
- **`autoExitEnabled = false` mengembalikan kepemilikan ke jalur lama.** Kalau tidak, mematikan
  auto-exit paper akan membuat posisi menggantung tanpa mesin penjual sama sekali.

---

## 4. Berkas hasil

- `p2-exit-ownership.patch` — patch git, sudah diverifikasi `git apply --check` bersih.

Terapkan dari root repo:

```bash
git checkout -b fix/p2-exit-ownership
git apply p2-exit-ownership.patch
./gradlew :app:testDebugUnitTest --tests "agu.analys.engine.scalping.*" \
  --tests "agu.analys.viewmodel.PaperAutoTradeRuntimeTest"
```

---

## 5. Verifikasi yang dijalankan

### Unit test
Toolchain: JDK 17 (Corretto 17.0.20) + Android SDK 36 + build-tools 36.0.0, Gradle 9.7.0.

```
BUILD SUCCESSFUL in 5m 55s
TOTAL: 150 test | gagal 0 | dilewati 1
```

Termasuk `PaperExitResolverParityTest` (39 test), `PaperTradeJournalTest` (15),
`PaperTradeStatsTest` (5), `PaperAutoTradeRuntimeTest` (5 — 3 lama + 2 baru),
`FeeCalculatorTest` (3), dan seluruh `engine.scalping.*`.

### Paritas Kotlin ↔ Python (verifikasi independen)
Saya tidak hanya mempercayai angka di test — saya hitung ulang memakai fungsi
`resolve_trade()` milik repo sendiri (`tools/scalp_replay.py`):

| Skenario | Mode | Hasil Python | Nilai harapan di test Kotlin |
|---|---|---|---|
| `tp2_hit` | classic / tp1_be / early / trail / full | WIN, r = **1.5118**, TP2, idx 2 | 1.5118, TP2, idx 2 — **cocok** |
| `sl_hit` | classic / tp1_be / early / trail / full | LOSS, r = **−1.9545**, SL, idx 1 | −1.9545, SL, idx 1 — **cocok** |

Jadi klaim paritas item #4 memang benar.

### Tool replay offline
`python3 tools/scalp_replay.py --pair XRPUSDT --data data/tokocrypto_v2 --config
data/config/scalp_config_exit_full.json` → jalan bersih, 11 trade, ringkasan per
`exit_reason` lengkap.

---

## 6. Celah tersisa yang BELUM saya perbaiki

**Posisi paper kehilangan mesin exit setelah app di-restart.**

`PaperAutoTradeRuntime.machines` adalah `ConcurrentHashMap` di dalam `object` — hanya di
memori. Sementara `auto_sell_enabled = false` ditulis ke `SharedPreferences` dan **bertahan**.

Setelah restart, untuk posisi paper yang sedang terbuka:

| Mesin | Kondisi | Hasil |
|---|---|---|
| Engine P2 | `machines` kosong | tidak jalan |
| Trailing lama | `isTrailingEnabled` sudah dibersihkan | tidak jalan |
| TP1/TP2/SL lama | `isAutoSellEnabled = false` (persisten) | tidak jalan |

→ **Tidak ada mesin yang menjual posisi itu.** Posisi menggantung sampai dijual manual, dan
kartu FSM tidak menampilkan apa pun. Ini tidak diklaim di dokumen mana pun, jadi di luar
definisi "selesai" P2 — tapi jelas bug nyata pada fitur auto-exit paper.

Dua arah perbaikan, keduanya perlu keputusan Anda:

1. **Rehidrasi saat startup** (lebih sederhana). Bangun ulang `PaperExitStateMachine` dari
   posisi sim terbuka. Datanya tersedia: `positionStore` menyimpan `entryPrice`,
   `stopLossPrice`, `tp1Price`, `tp2Price` (ditulis walau auto-sell dimatikan), `peakPrice`.
   Parameter exit dari `prefs.scalpingRuntimeConfig.exit`, fee dari `prefs.feeConfigFor()`.
   Kompromi: progres `barsHeld` / `beArmed` / `partialDone` ter-reset, jadi trade yang sedang
   berjalan bisa dievaluasi dengan state yang tidak persis.
2. **Persist state machine** (lebih akurat). Serialisasi seluruh state mesin ke DataStore tiap
   perubahan fase. Kompromi: lebih banyak kode dan titik gagal.

Saya rekomendasikan **opsi 1** — dampaknya kecil, dan trade paper yang sedang berjalan saat
app ditutup adalah kasus langka.

---

## 7. Catatan lain

- `release.yml` terpicu pada **setiap** push ke `main` dan langsung menerbitkan APK. Patch ini
  sebaiknya digabung lewat branch + PR, bukan push langsung, agar tidak memicu rilis.
- Patch tidak menyentuh `workplan2.md`. Klaim P2 di dokumen itu sekarang sudah benar apa
  adanya, jadi tidak ada yang perlu dikoreksi.
