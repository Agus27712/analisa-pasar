# LAPORAN — SSOT: horizon kesiapan vs engine yang aktif

> ## ⛔ DIHAPUS — 2026-10-13. JANGAN DIBANGUN ULANG.
>
> **Perbaikan SSOT di laporan ini sudah tidak ada di kode**, karena seluruh fitur horizon yang
> diperbaikinya ikut dihapus.
>
> **Alasan:** laporan ini memperbaiki kontradiksi "3/5 vs 1/5" dengan menyatukan sumber. Tetapi
> akar persoalannya lebih dalam: **permukaannya sendiri memang berlebih.** Selama masih ada dua
> kartu yang menampilkan status kesiapan, akan selalu ada cara bagi keduanya untuk berbeda. Owner
> memutuskan menghapus permukaan keduanya, bukan menyinkronkannya.
>
> **Pelajaran yang tetap berlaku dan sudah dipindahkan ke `AGENTS.md`:** jangan pernah menampilkan
> `N/5` dari daftar syarat berbeda untuk koin yang sama di dua kartu berbeda; status engine boleh
> dipetakan, angkanya tidak boleh dihitung ulang.
>
> Laporan ini disimpan hanya sebagai riwayat keputusan.

---



**Tanggal:** 2026-10-13
**Branch:** `patch`
**Pemicu:** review owner langsung di HP (3 tangkapan layar)

---

## 1. Laporan pengguna (apa adanya)

> "setelah saya review di hp langsung menambahkan horizon scalp, intraday, swing ke dashboard dan
> detail koin aplikasi menjadi rancu, terutama di halaman detail koin, kesiapan horizon 3/5 tapi
> penilaian engine scalping masih 1 gate yang lolos
>
> dan juga di dashboard, posisi dashboard sekarang sedang di mode scalping, tapi dashboard
> memantau engine lainnya. dan ingat preferensi pemilihan engine itu kan ada di tombol simpan di
> setting, berarti kesimpulan saya engine horizon dan engine scalping, intraday, swing itu berbeda,
> (tidak SSOT)"

Kesimpulan pengguna **benar**, dan penyebabnya bukan bug tampilan.

---

## 2. Akar masalah

Aplikasi punya **dua evaluator yang menjawab pertanyaan yang sama** untuk horizon yang sama:

| Permukaan | Sumber | Daftar syarat |
|---|---|---|
| Kartu `SCALP DESK` (halaman detail) | `ScalpingMtfEvaluator` → `ScalpReadiness.checkpoints()` | 5: Bias & Ruang Naik · Setup Scalping · Tekanan Orderbook · Trigger VWAP/VSA · Net R:R & Skor |
| Kartu `KESIAPAN HORIZON` (halaman detail) | `HorizonReadinessEngine.scalpChecks()` | 5: Tren H1 · Momentum M15 · Dorongan M1 · Volatilitas sehat · Ekonomi trade |

Dua daftar yang **berbeda**, keduanya menampilkan format `N/5`. Koin yang sama bisa lolos 1 syarat
menurut engine dan 3 syarat menurut pra-saring — dan keduanya "benar" menurut daftar masing-masing.

Yang salah bukan angkanya, melainkan **arsitekturnya**: pra-saring tidak pernah diberi mandat untuk
menilai horizon yang engine-nya sedang aktif. Ia ada untuk menyaring ratusan koin sebelum engine
mengulasnya, bukan untuk menjadi pendapat kedua.

Di dashboard masalahnya sama tetapi terlihat berbeda: kartu `SIAP TRADING` meringkas **tiga**
horizon seolah setara, sementara preferensi engine disimpan pengguna lewat tombol simpan di
Pengaturan (`StrategyMode`). Saat mode SCALPING aktif, kartu itu terlihat "memantau engine lain".

### 2.1 Kenapa ini tidak terdeteksi saat pengerjaan sebelumnya

Saat fitur kesiapan dibuat, engine hanya dianggap "sumber ambang" (friksi 6×, target net R:R) —
dan itu memang sudah dipakai bersama. Yang tidak diperiksa adalah bahwa **daftar syaratnya sendiri**
juga menjadi kesimpulan yang dibaca pengguna. Dua permukaan boleh berbagi ambang tetapi tidak boleh
berbagi mandat menyimpulkan.

---

## 3. Perbaikan — satu aturan, tidak bisa dilanggar

```
StrategyMode (pilihan pengguna, tombol simpan di Pengaturan)   ← SSOT
    └── TradeHorizon.of(mode)                                  ← horizon aktif
            ├── baris horizon aktif → EngineVerdict → ReadinessSource.ENGINE
            └── baris horizon lain  → pra-saring     → ReadinessSource.PRESCREEN
```

**Aturan:** horizon yang engine-nya aktif **selalu** dinilai engine itu sendiri. Horizon lain hanya
boleh tampil sebagai `PRA-SARING` dan wajib menyebut dirinya begitu.

### 3.1 Berkas baru

| Berkas | Isi |
|---|---|
| `engine/readiness/EngineVerdict.kt` | Jembatan murni: `AISignalState` + `ScalpReadinessSnapshot` → `HorizonReadiness`. Memakai objek checkpoint yang **sama** dengan kartu engine; tidak ada angka yang dihitung ulang. |
| `engine/readiness/ReadinessSource` (di `HorizonReadiness.kt`) | `ENGINE` / `PRESCREEN` — penanda dari mana sebuah baris berasal. |

### 3.2 Berkas yang diubah

| Berkas | Perubahan |
|---|---|
| `engine/readiness/TradeHorizon.kt` | +`strategyMode` per horizon, +`TradeHorizon.of(mode)` — penaut ke SSOT |
| `engine/readiness/HorizonReadiness.kt` | +`ReadinessState.HOLDING`, +`source`/`sourceNote`, +`ReadinessBoard.withEngineRows()`, +`engineRows()`/`prescreenRows()` |
| `engine/readiness/HorizonReadinessEngine.kt` | `weightedScore`/`exitRule`/`roundTripCostPct` jadi publik (dipakai bersama, bukan disalin); `HOLDING` ditangani di `headline()` |
| `viewmodel/TradingViewModel.kt` | +`readinessScanBoard` (hasil pindai mentah), +`readinessEngineJob`, `_readinessHorizon` default = horizon aktif, `setStrategyMode` memindahkan horizon + publikasi ulang |
| `viewmodel/TradingViewModelReadiness.kt` | +`readinessActiveHorizon()`, +`publishReadinessBoard()`, +`engineVerdictRows()`, lapisan reaktif `startReadinessEngineLayer()` |
| `ui/components/readiness/ReadinessUi.kt` | +`ReadinessSourceBadge`, +warna `HOLDING` |
| `ui/components/readiness/HorizonReadinessCard.kt` | +`activeHorizon`, +badge sumber per baris, +`ReadinessSourceLegend`, baris engine dibuka otomatis |
| `ui/components/readiness/ReadinessBoardCard.kt` | +`activeHorizon`; kartu dashboard menyatakan engine aktif dan menyebut angkanya pra-saring |
| `ui/screens/ReadinessBoardScreen.kt` | +legenda, penanda `ENGINE AKTIF` pada pemilih horizon, baris engine selalu di atas |
| `ui/screens/DashboardScreen.kt`, `ui/screens/DetailChartScreen.kt` | wiring `activeHorizon`; detail menghitung baris engine secara lokal |

### 3.3 Keputusan yang diambil tanpa bertanya

| Keputusan | Alasan |
|---|---|
| **Pra-saring tetap ada**, tidak dihapus | Ia menyaring ratusan koin dengan murah dari cache. Yang salah adalah mandatnya, bukan keberadaannya. |
| **`HOLDING` jadi status tersendiri** | Menerjemahkan "posisi terbuka" menjadi `SIAP` akan mengembalikan kontradiksi di layar yang sama (kartu engine menulis `POSISI AKTIF`). |
| **`ScalpReadiness.resolve` dipakai untuk ketiga mode** | Ketiga engine mengisi `ScalpingMtfSnapshot` yang sama (bias/setup/trigger/entry). Satu resolver = satu aturan kesimpulan. |
| **Baris engine disembunyikan rencananya di mode scalping** (`planInCardElsewhere`) | Kartu `SCALP DESK` tepat di bawah sudah memuat entry/SL/TP. Menyalinnya lagi = duplikasi yang baru saja dihapus. |
| **Detail menghitung baris engine sendiri, tidak menunggu papan** | Pair yang baru dibuka harus langsung benar, walau pemindaian 60 detik belum berjalan. |

### 3.4 Yang belum bisa dilakukan (dan diakui di UI)

Engine hanya menilai **satu simbol** — simbol yang sedang diulas. Koin lain di daftar pantau belum
punya sinyal engine hidup, jadi barisnya tetap `PRA-SARING`. Ini ditulis di layar, bukan
disembunyikan: lebih baik mengaku "belum dinilai engine" daripada menampilkan angka pra-saring
seolah keputusan engine.

---

## 4. Uji

| | Jumlah |
|---|---|
| Baseline sebelum perubahan | 364 |
| Sesudah perubahan | **385** |
| Selisih | **+21** |

`385 tests · 0 failed · 1 skipped` (skipped = tes lama yang memang dilewati, bukan baru).

Tes baru:

- `EngineVerdictTest` (12) — identitas checkpoint dengan `ScalpReadiness`, 1/5 tetap 1/5, skor
  memakai penghitung yang sama, pemetaan status termasuk `HOLDING`, sumber `ENGINE`, rencana
  berasal dari level engine, horizon mengikuti mode.
- `ReadinessBoardOverlayTest` (8) — baris engine **mengganti** baris pra-saring (tidak menduplikasi),
  simbol/horizon lain tidak tersentuh, pencocokan simbol tanpa peduli huruf besar-kecil, pemetaan
  `StrategyMode` ↔ `TradeHorizon` satu-ke-satu.
- `HorizonReadinessEngineTest` (+1) — pra-saring **tidak pernah** menandai barisnya `ENGINE`.

### 4.1 Kontrol negatif (wajib — membuktikan tes benar-benar menjaga)

| Kontrol | Perubahan sementara | Hasil |
|---|---|---|
| NC-3 | `EngineVerdict` kembali menilai ulang dengan daftar syarat sendiri (perilaku lama) | `oneOfFiveStaysOneOfFive` + `checkpointsAreIdenticalToEngineSnapshot` **GAGAL** (2) |
| NC-1 | `withEngineRows` dimatikan (`return this`) | `engineRowReplacesPrescreenRowForSameSymbolAndHorizon` + 2 lainnya **GAGAL** (3) |
| NC-2 | `HOLDING` dipetakan ke `READY` | `holdingHasItsOwnStateAndNeverLooksReady` **GAGAL** (1) |

Ketiganya dipulihkan; `grep -c "NEGATIVE CONTROL"` = 0 pada kedua berkas, dan suite kembali hijau.

---

## 5. Efek yang diharapkan terlihat di HP

| Sebelum | Sesudah |
|---|---|
| Detail koin: `SCALPING 3/5 syarat lolos` + `SCALP DESK 1/5 lolos` | `SCALPING 1/5` dengan badge `ENGINE AKTIF`, syaratnya **baris yang sama** dengan SCALP DESK |
| Detail koin: 3 horizon tampak setara | Horizon aktif berbadge `ENGINE AKTIF`, dua lainnya `PRA-SARING` + legenda |
| Dashboard: kartu `SIAP TRADING` meringkas 3 horizon | Kartu menyatakan `Engine aktif: SCALPING`, tile SCALP ditandai, dan angkanya disebut pra-saring |
| Papan: tidak jelas mana yang keputusan engine | Legenda + badge per baris, baris engine selalu di atas |
| Pindah mode di Pengaturan | Papan langsung pindah horizon; baris mode sebelumnya berhenti mengaku keputusan engine |

---

## 6. Batasan yang tetap berlaku

- **INTRADAY & SWING belum divalidasi keluar-sampel.** Perbaikan ini menyamakan *siapa yang
  menjawab*, bukan menambah bukti edge.
- **SWING sering `DATA KURANG`** karena `MtfCacheManager` tidak pernah mem-prefetch D1.
- **Pra-saring tetap tidak melihat orderbook.** Keputusan entry scalping tetap milik engine.
- **Eksekusi uang riil tetap diblokir** (`SCALP_EXIT.md`, `workplan2.md` §9.5b).
