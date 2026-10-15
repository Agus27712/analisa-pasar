# Laporan P4.4 — Persistensi Progres State Machine Exit Paper

**Tanggal:** 15 Oktober 2026
**Repo:** branch `patch`
**Basis:** `f9e7fdd` (fix auto-buy nominal Rupiah) + commit P4.4
**Cakupan:** P4.4 saja. P4.2 (fee maker IDR) dan P5 (Indodax) **tidak** disentuh.
**Hasil uji:** **416 test · 0 gagal · 1 dilewati** (baseline sebelum perubahan: 371 · 0 · 1)

---

## 1. Masalah yang ditutup

`PaperAutoTradeRuntime` menyimpan `PaperExitStateMachine` **hanya di memori**, sedangkan posisinya
persisten di `SpotPositionStore`. Saat engine paper mengambil alih exit, jalur auto-sell lama
sengaja dimatikan (`auto_sell_enabled = false`, juga persisten). Jadi setelah app di-kill:

```
machines (memori)     -> kosong        -> engine paper tidak jalan
auto_sell_enabled     -> false         -> jalur lama juga tidak jalan
posisi di prefs       -> masih HOLDING -> posisi menggantung
```

`rehydratePaperAutoExit()` + `PaperRehydrate.decide()` sudah menutup lubang "posisi menggantung"
sejak P2 — mesin dibangun ulang dengan `entry`/`SL` asli. **Tetapi progresnya hilang.** Empat
akibat nyata setiap kali app di-restart:

| Yang hilang | Akibat |
|---|---|
| `activeStopLoss` | SL kembali ke titik awal; trailing yang sudah terkunci **lepas** |
| `isBeArmed` / `isTrailArmed` | mesin harus meng-arm ulang dari nol |
| `isPartialDone` / `partialR` | TP1 partial yang sudah dijual dihitung ulang seolah belum terjadi |
| `barsHeld` | time-stop mulai menghitung dari nol lagi |
| `atrPct` | ambang BE/trailing S8 **diam-diam jatuh ke nilai persen tetap** |

`atrPct` adalah temuan kedua: `PaperRehydrate.kt:98-107` (versi lama) tidak pernah mengisinya,
sehingga `effectiveBreakevenTriggerNetPct` dan `effectiveTrailDistancePct` memakai default
`breakevenTriggerNetPct`/`trailPct` — persis angka yang S8 naikkan karena terbukti terlalu rapat
terhadap noise satu candle.

---

## 2. Keputusan owner yang mengikat

| # | Keputusan | Alasan |
|---|---|---|
| 1 | `barsHeld` dipulihkan dari **jam dinding**: `max(tersimpan, floor((now - openedAt)/60000))` | Replay offline menghitung bar M1 terus-menerus. Konsekuensi yang diterima: app mati 3 jam → posisi langsung kena time-stop saat dibuka. |
| 2 | State tidak konsisten → **buang progres, bangun mesin baru** | `entry`/`SL` asli tetap dipakai (benar), hanya progresnya direset. Bukan `HandBackToLegacy`, karena jalur lama memakai semantik SL/BE berbeda sehingga hasilnya tak sebanding dengan replay. |
| 3 | Cakupan P4.4 saja | Satu perubahan, satu review. |

---

## 3. Perubahan kode

### 3.1 `engine/scalping/PaperExitResolver.kt`

- `data class PaperExitFsmState` — 10 field: `entry`, `stopLoss`, `activeStopLoss`, `peak`,
  `isBeArmed`, `isTrailArmed`, `isPartialDone`, `partialR`, `barsHeld`, `atrPct`.
  Plus `toJson()` / `toJsonString()` / `fromJson(raw)` dengan penanda versi `v:1`.
- `PaperExitStateMachine.snapshot()` — potret progres.
- `PaperExitStateMachine.restoreRejection(state)` — validasi, mengembalikan alasan penolakan.
- `PaperExitStateMachine.restore(state)` — menerapkan **hanya bila** validasi lolos.

`entry`/`stopLoss` disimpan bukan sebagai progres melainkan **acuan identitas**: simbol bisa dipakai
ulang, jadi state milik posisi lama bisa terbaca posisi baru. `restore` menolaknya.

`atrPct` **tidak** diterapkan oleh `restore()` — ia parameter konstruktor dan nilai turunannya
(`effectiveBreakevenTriggerNetPct`, `effectiveTrailDistancePct`) adalah `val` yang dihitung sekali
saat mesin lahir. Karena itu `atrPct` dioper saat **membangun** mesin di `PaperRehydrate.decide()`.

`PaperExitResolver.resolve()` (stateless) **tidak disentuh** → paritas `tools/scalp_replay.py` utuh.

### 3.2 `trading/SpotPositionStore.kt`

Tiga fungsi baru dengan key `<buildKey>_paper_fsm`, isinya **satu string JSON**:
`setPaperFsmState` / `getPaperFsmState` / `clearPaperFsmState`.

Store sengaja **tidak** mem-parsing isinya — skema JSON dimiliki paket `engine`, sehingga `trading`
tidak perlu bergantung pada `engine`. Satu key (bukan satu key per field) dipilih supaya `markSold`
hanya perlu satu `.remove()` per blok, tidak ada kemungkinan state separuh tertulis, dan JSON bisa
membawa `entry`/`stopLoss` untuk verifikasi identitas.

`markSold` dibersihkan di **dua** blok: blok utama dan blok legacy.

### 3.3 `engine/scalping/PaperRehydrate.kt`

- `PaperRehydrateAction.Restore` diperluas dengan `progressRestored` dan `warning` (ber-default,
  jadi test lama tetap kompilasi).
- `decide()` menerima `storedFsm`, `openedAt`, `now` (semua ber-default).
- `PaperRehydrate.mergedBarsHeld(stored, openedAt, now)` — aturan jam dinding, dipisah agar bisa
  diuji sendiri. `openedAt <= 0` atau di masa depan → nilai tersimpan dipakai apa adanya, supaya
  posisi lama tanpa `openedAt` tidak menghasilkan jumlah bar raksasa.
- Dokumentasi batasan lama (`barsHeld`/`isBeArmed`/`isPartialDone`/`peak` reset) **dihapus** karena
  tidak lagi benar.

### 3.4 `viewmodel/PaperAutoTradeCoordinator.kt`

- `PaperAutoTradeRuntime`: `lastPersistedFsmMap` + `lastFsmWriteAtMap`, ikut dibersihkan di
  `clear()` dan `clearAll()`.
- `put()` dapat parameter `markFirstTickAsNewBar`. Tanpa ini tick pertama setelah restart dianggap
  bukan bar M1 baru, sehingga time-stop baru dievaluasi sampai batas menit berikutnya — meniadakan
  keputusan owner #1.
- `shouldWriteFsm(...)`: tulis **segera** bila ada perubahan yang mengubah perilaku exit (flag
  proteksi, SL naik, jumlah bar); tahan 2 detik bila hanya `peak` yang bergeser.
  Ini perlu karena `evaluatePaperAutoExit` berjalan **per tick harga**.
- `persistPaperFsm(...)`: helper yang memakai keputusan di atas lalu menulis ke store.
- Hook tulis: setelah `armPaperExitEngine` memasang mesin, dan setelah `onBar` yang tidak menutup posisi.
- Hook hapus: `finalizePaperExit`, `clearPaperExitState`, dan cabang "posisi sudah tidak ada" di
  `evaluatePaperAutoExitLocked` (cabang ini melepas mesin **tanpa** lewat `markSold`), plus cabang
  `HandBackToLegacy`/`Skip` di rehidrasi.
- `rehydratePaperAutoExit()` membaca state, meneruskan `openedAt`/`now`, memanggil `put` dengan
  `markFirstTickAsNewBar = true`, dan mencatat progres yang dipulihkan/dibuang ke log.

---

## 4. Aturan validasi

Pelanggaran apa pun → `restore()` mengembalikan `false` dan **tidak ada** field yang diterapkan,
sehingga mesin tetap pada kondisi awal (bukan kondisi setengah jadi).

| Invarian | Kenapa |
|---|---|
| `entry` & `stopLoss` cocok dengan posisi hidup | simbol bisa dipakai ulang |
| `peak >= entry` | `peak` dimulai dari `entry` dan hanya naik |
| `activeStopLoss >= stopLoss` | SL hanya pernah naik |
| `activeStopLoss`, `peak`, `partialR` finite | NaN/Infinity menghasilkan keputusan sampah |
| `barsHeld >= 0` | hitungan bar tak pernah negatif |
| `!isPartialDone ⟹ partialR == 0.0` | `partialR` hanya diisi saat partial terpicu |
| `isTrailArmed ⟹ isBeArmed` | `usesTrailing` (TRAIL, FULL) ⊆ `usesBreakeven` |
| `isPartialDone ⟹ isBeArmed` | blok TP1 partial selalu men-set `isBeArmed = true` |

**Dua invarian yang sengaja TIDAK dipakai:**

- `activeStopLoss < peak` — terbukti bisa sah dilanggar: `tp1` default = `entry` membuat partial
  ter-arm saat `high ≈ entry`, sementara `activeStopLoss` naik ke harga BE yang ada **di atas** entry.
- `isBeArmed ⟹ activeStopLoss >= bePx` — `bePx` dihitung dari fee/params **saat ini**. Kalau fee
  diubah antar sesi, state lama akan tampak melanggar padahal sah.

`atrPct` negatif/NaN disanitasi ke `0.0` ("tidak diketahui") dan **tidak** membuang seluruh progres.

---

## 5. Test

### Baru

| Berkas | Isi |
|---|---|
| `PaperExitFsmStateTest.kt` (18 test) | snapshot; restore identik; **uji paritas mesin A vs B**; 8 kasus negatif invarian; round-trip JSON; JSON rusak/versi asing; sanitasi `atrPct` |
| `SpotPositionStorePaperFsmTest.kt` (8 test, Robolectric) | set/get/timpa/clear; `markSold` menghapus key di **kedua** blok; isolasi antar-bursa; isi rusak dikembalikan apa adanya |

Uji terkuat ada di `PaperExitFsmStateTest`:

> mesin A menjalani deret bar sampai trailing ter-arm → `snapshot()` → mesin B `restore()` →
> keduanya diberi bar yang low-nya jatuh **di bawah trailing SL tetapi di atas SL awal**.
> Mesin A dan B **sama-sama keluar** dengan `TRAIL_SL`; mesin yang kehilangan progres **tidak keluar**.
> Itulah bug yang ditutup P4.4, dinyatakan sebagai perilaku.

### Diperluas

- `PaperRehydrateTest.kt` (+10 test): state valid dipulihkan penuh; state rusak direset tapi mesin
  tetap dibangun dengan SL asli; state milik posisi lain ditolak; `barsHeld` jam dinding (3 jam → 180);
  nilai tersimpan yang lebih besar tetap dipakai; `openedAt` tak diketahui; `atrPct` dipakai untuk
  ambang S8; `mergedBarsHeld` menjaga masukan tidak masuk akal.
- `PaperAutoTradeRuntimeTest.kt` (+9 test): `markFirstTickAsNewBar`; throttle vs perubahan semantik;
  `force`; `seedPersistedFsm`; `clear`/`clearAll` melepas jejak penulisan.

### Kontrol negatif

Wajib per `AGENTS.md`. Dijalankan dua kali dengan perbaikan sengaja dimatikan:

| Kontrol | Hasil |
|---|---|
| `snapshot()` mengembalikan state nol (mensimulasikan progres tidak tersimpan) | **3 test GAGAL** — `snapshot menangkap progres`, `restore pada mesin baru menghasilkan state identik`, `mesin yang direstore berlanjut sama dengan mesin yang tidak terputus` |
| `restore()` selalu `false` (mensimulasikan state tidak pernah diterapkan) | **6 test GAGAL** — termasuk 4 test jalur rehidrasi di `PaperRehydrateTest` |

Setelah perbaikan dihidupkan kembali, suite penuh dijalankan dengan `--rerun`:
**416 · 0 gagal · 1 dilewati** (yang dilewati adalah `RealTokocryptoReplayTest`, sudah ada sebelumnya
dan hanya berjalan di workflow manual).

---

## 6. Risiko dan batasan yang tersisa

| Risiko | Mitigasi / status |
|---|---|
| Cabang "posisi sudah tidak ada" melepas mesin tanpa `markSold` → key tertinggal | `clearPaperFsmState(...)` eksplisit di cabang itu |
| Key tertinggal mencemari posisi berikutnya | Dua lapis: `markSold` menghapus **dan** invarian identitas menolak |
| `clearAll()` (saat auto-buy dimatikan) membersihkan memori, bukan disk | **Di luar cakupan P4.4.** Tidak lebih buruk dari `_paper_engine` yang sudah ada; rehidrasi tetap memvalidasi terhadap posisi hidup |
| `clearPaperExitState` bisa dipanggil di luar lock evaluasi | Dampak terbatas: key basi atau hilang, keduanya pulih lewat invarian identitas |
| Off-by-one `barsHeld` (floor menit + 1 bar di tick pertama) | Selisih ≤ 1 bar, konservatif untuk time-stop, didokumentasikan |
| **Gap risk tidak tertutup oleh P4.4** | Kalau app mati, SL **tidak** dipantau. Memulihkan `barsHeld` dari jam dinding membuat time-stop jujur, tetapi stop-out yang seharusnya terjadi selama app mati tetap tidak tercatat. Ini keterbatasan paper trading, bukan bug |
| `decide()` kini 12 parameter | `decide()` sudah 9 parameter sebelum P4.4, jadi bukan regresi baru |

---

## 7. Berkas yang disentuh

**Produksi**
- `app/src/main/java/agu/analys/engine/scalping/PaperExitResolver.kt`
- `app/src/main/java/agu/analys/engine/scalping/PaperRehydrate.kt`
- `app/src/main/java/agu/analys/trading/SpotPositionStore.kt`
- `app/src/main/java/agu/analys/viewmodel/PaperAutoTradeCoordinator.kt`

**Test**
- `app/src/test/java/agu/analys/engine/scalping/PaperExitFsmStateTest.kt` (baru)
- `app/src/test/java/agu/analys/trading/SpotPositionStorePaperFsmTest.kt` (baru)
- `app/src/test/java/agu/analys/engine/scalping/PaperRehydrateTest.kt`
- `app/src/test/java/agu/analys/viewmodel/PaperAutoTradeRuntimeTest.kt`

**Dokumentasi**
- `AGENTS.md` (aturan baru: progres FSM wajib dijaga sinkron)
- `docs/BELUM-DIKERJAKAN-2026-10-14.md` (P4.4 → selesai)
- `docs/LAPORAN-p44-persistensi-fsm-2026-10-15.md` (berkas ini)

---

## 8. Yang belum berubah

- **Kesiapan uang riil tetap BELUM.** P4.4 memperbaiki kejujuran data paper, bukan edge strategi.
  Angka `avg R ≈ −0,17` (40 pair, 0/40 positif) dan walk-forward 0/4 jendela **tidak berubah** oleh
  laporan ini. Lihat `docs/LAPORAN-temuan-akuntansi-replay-2026-10-14.md`.
- **P4.2** (preferensi maker untuk pair IDR) — belum.
- **P5** (data Indodax + replay TAPI v2) — belum.
- **P6** (auto-execute uang riil) — tetap **DIBLOKIR**.
- Tiga jurnal paralel (`trade_history_records` · `signal_logs` · `SimulationTradeHistoryItem`)
  belum disatukan.
