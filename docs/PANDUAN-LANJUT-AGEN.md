# PANDUAN LANJUT AGEN — analisa-pasar

> **Baca ini dulu.** Dokumen ini adalah titik masuk untuk agen/engineer berikutnya: status terkini,
> apa yang sudah selesai, apa berikutnya, dan **perangkap yang sudah pernah ditemukan** supaya tidak
> diulang. Terakhir diperbarui: **2026-10-16**.
>
> 🔴 **Untuk "apa lagi yang tersisa" → `docs/bugs/`, bukan dokumen ini.** Status temuan audit
> (notifikasi `N*`, jurnal/P&L `J*`, paper trading `P*`) sudah diverifikasi ke kode dan **direview
> owner** pada `patch` @ `547048d`: **21 tertutup · 3 sebagian · 13 masih hidup**. Ringkasnya:
>
> | Status | Temuan |
> |---|---|
> | 🔴 Kritis — **tidak ada yang tersisa** | (P1–P3 dan J1/J2 sudah tertutup) |
> | 🟠 Serius — masih hidup | J8, J9 · J10 (sebagian) · P6 (sebagian), P7, P8 · N6+N17, N13, N14, N15, N16 |
> | 🟡 Sedang/rendah — masih hidup | N8, N9, N12 · N11 (sebagian) |
>
> Rincian + lokasi + prioritas: `docs/bugs/temuan_bugs_notifikasi.md`, `temuan_bugs_jurnal_pnl.md`,
> `temuan_bugs_paper_trade.md`. Sinkronisasi status terakhir:
> `docs/LAPORAN-sync-dokumen-temuan-2026-10-16.md` (`workplan2.md` §40).

---

## 1. Status terkini

| | |
|---|---|
| Branch kerja | **`patch`** (owner me-review di sini dulu; `main` hanya menerima merge) |
| HEAD `patch` | Ukur: `git rev-parse --short origin/patch` — berubah **tiap commit**, jangan dihardcode |
| HEAD `main` | = `patch` + commit merge di atasnya; ukur `git rev-parse --short origin/main` |
| Titik masuk kode | package `agu.analys`, modul tunggal `:app` |
| Unit test | **416 lulus · 0 gagal · 1 dilewati** (53 kelas) |
| APK debug | 26.821.815 byte (26,8 MB desimal / 25,6 MiB) |
| Kesiapan uang riil | **BELUM** — lihat §5 |

> ⚠️ Angka di tabel ini **pernah salah** di dokumen ini sendiri (tertulis 358/50 kelas). Setiap kali
> angka ini dipakai, **ukur ulang** — jangan kutip dari sini tanpa menghitung.

**Cara mengukur angka di atas sendiri (jangan percaya ingatan, termasuk dokumen ini):**

```bash
export JAVA_HOME=/root/.sdkman/candidates/java/17.0.20-amzn
export GRADLE_USER_HOME=/tmp/gradle-home
./gradlew testDebugUnitTest
python3 -c "
import glob,xml.etree.ElementTree as ET
t=f=s=n=0
for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    r=ET.parse(p).getroot(); n+=1
    t+=int(r.get('tests',0)); f+=int(r.get('failures',0))+int(r.get('errors',0)); s+=int(r.get('skipped',0))
print('kelas',n,'| test',t,'| gagal',f,'| dilewati',s)"
```

**Mengukur baseline dengan jujur** (pola yang sudah dipakai beberapa kali di repo ini):

```bash
git stash push -u -m wip      # simpan pekerjaan
rm -rf app/build/test-results/testDebugUnitTest
./gradlew testDebugUnitTest   # hitung baseline
git stash pop                 # kembalikan pekerjaan
```

---

## 2. Peta pekerjaan — apa yang sudah selesai

| # | Pekerjaan | Berkas kunci | Bukti |
|---|---|---|---|
| §9 | **Lantai risiko berbasis friksi** (`min_friction_multiple = 6,0`, `target_net_rr = 2,0`) | `ScalpingConfig.kt`, `ScalpingRiskEngine.kt` | `workplan2.md` §9 |
| §9 | Uji universe 30 hari × 40 pair, gate out-of-sample | `tools/split_validate.py` | `data/hasil/u3_*.json`, `u4_*.md` |
| §9 | **Gerbang edge pair** (MFE ≥ 2× friksi) | `PairEdgeGate.kt`, `tools/pair_gate.py` | `data/config/pair_gate.json` |
| §11 | **UI scalping baru** (radar vs setup tidak sinkron) | `ScalpReadiness.kt`, `ScalpDeskCard.kt`, `PaperTradeDeskCard.kt` | `ScalpReadinessTest.kt` |
| §12 | **Rehidrasi state machine exit paper** setelah restart | `PaperRehydrate.kt`, `SpotPositionStore.kt` | `PaperRehydrateTest.kt` |
| §13 | **Akar `EARLY_ENTRY`** — stage tinggi wajib punya pola | `ScalpingMtfEvaluator.kt`, `SignalLifecycleManager.kt` | `workplan2.md` §13 |
| §16 | **Integritas log audit P0–P2** — basis volume RVOL, basis bar indikator, `signalTime`, `sellReason`, trailing nyata, Peak/Drawdown dari candle, label & format, flag `isSample` | `IndicatorMath.kt`, `ScalpingMtfEvaluator.kt`, `TradeHistoryRecorder.kt`, `TradeSignalSnapshot.kt`, `TradeLogExporter.kt`, `AppDatabase.kt` (v9) | `docs/LAPORAN-verifikasi-ui-vs-candle-2026-10-14.md`, `docs/LAPORAN-eksekusi-p0p2-2026-10-14.md`, `IndicatorMathScalpingExtTest.kt` |
| §17 | **Perbaikan strategi S1–S9** — RVOL wajib breakout, lantai profit, SL keras configurable, konstanta ATR dipakai, basis PnL seragam, label volume, exit engine untuk beli manual, BE/trail proporsional ATR, gerbang extended-move (default nonaktif) | `ScalpSetupDetector.kt`, `ScalpExitConfig.kt`, `PaperExitResolver.kt`, `ScalpingRiskEngine.kt`, `ScalpingRuntimeConfig.kt`, `MarketRegimeEngine.kt`, `SimulationTradeStore.kt`, `TradeLogExporter.kt`, `PaperAutoTradeCoordinator.kt`, `tools/scalp_replay.py` | `docs/LAPORAN-eksekusi-s1s9-2026-10-14.md`, `ScalpStrategyFixTest.kt`, `SimulationSlippageParityTest.kt` |
| P4.1 | **Mode walk-forward** di `tools/split_validate.py` — jendela bergulir + **3 bug diperbaiki** (kunci agregasi salah → 9 jendela palsu; penulis `.md` crash `StopIteration`; pair lebih pendek dari satu jendela → `IndexError`) | `tools/split_validate.py`, `tools/fetch_tokocrypto_klines.py` | `docs/LAPORAN-p41-walk-forward-2026-10-14.md`, `data/hasil/u7_walk_forward_v4.{json,md}`, `data/hasil/u8_walk_forward_40pair.{json,md}` |
| P4.4 | **Persistensi progres FSM paper** — `barsHeld`, `peak`, `activeStopLoss`, `isBeArmed`/`isTrailArmed`/`isPartialDone`, `partialR`, `atrPct` disimpan sebagai satu JSON di key `_paper_fsm` | `PaperExitResolver.kt`, `PaperRehydrate.kt`, `SpotPositionStore.kt`, `PaperAutoTradeCoordinator.kt` | `docs/LAPORAN-p44-persistensi-fsm-2026-10-15.md`, `PaperExitFsmStateTest.kt`, `SpotPositionStorePaperFsmTest.kt` |
| — | **Perbaikan bug auto-buy**: nominal Rupiah dibaca sebagai USDT + anggaran tidak menyisihkan fee/slippage → auto-buy **tidak pernah** tereksekusi | `PaperAutoTradeConfig.kt`, `PaperAutoTradeCoordinator.kt`, `AppPreferences.kt`, `PaperAutoTradeSettings.kt` | `docs/LAPORAN-bug-auto-buy-nominal-idr-2026-10-15.md`, `PaperAutoBuySizingTest.kt` |
| ~~§14~~ | ~~Kesiapan multi-horizon (scalp/intraday/swing) + papan UI~~ | — | ⛔ **DIBATALKAN** — lihat §3b |
| ~~§15~~ | ~~SSOT: horizon vs engine aktif~~ | — | ⛔ **DIBATALKAN** — lihat §3b |

**Status di `main`:** `main` sudah menerima merge **PR #59** (`bc48ac4`, membawa sampai `9f65109`)
**dan PR #60** (`7f890eb`, membawa `f9e7fdd` auto-buy + `56eb5cd` P4.4 + `5dc7182`). Snapshot
**2026-10-15 tepat setelah PR #60**, `patch` dan `main` **identik secara isi** (`git diff HEAD
origin/main` kosong); `main` hanya unggul **2 commit merge**. Sejak itu setiap commit mendarat di
`patch` dulu, jadi `patch` normalnya **> 0** di depan `main` — bukan tanda `main` ketinggalan.
Pada 2026-10-15 `patch` di-merge ulang ke `main` (`70aea38`, ditandai `[skip ci]`), sehingga
keduanya kembali **identik secara isi**. §14/§15 tetap **dihapus dari kode** (2026-10-13).

---

## 3. Arsitektur ringkas (yang perlu diketahui sebelum mengubah apa pun)

```
MarketTick/Candle  ──►  LearningTradingEngine  ──►  ScalpingMtfEvaluator ─┐
   (per exchange)          (routing per              SwingEvaluator       ├─► AISignalState
                            StrategyMode)             IntradayEvaluator   ─┘        │
                                                                                    ▼
                                                                    SignalLifecycleManager
                                                                     (IDLE→DETECTED→…)
                                                                                    │
   MtfCacheManager (cache memori)                                                  │
             │                                                                      │
             └────────────► PaperAutoTradeCoordinator                              │
                              (auto buy/sell paper)                                 │
                                                                                     ▼
                                                              TradingViewModel (Facade, banyak berkas)
                                                                                     │
                                                                            Compose screens
```

**Satu engine, satu permukaan status.** `StrategyMode` memilih engine mana yang menghitung sinyal;
`ScalpReadiness` + `ScalpDeskCard`/`WaitingEntryRadarCard` adalah satu-satunya tempat status
ditampilkan. Tidak ada evaluator kedua dan tidak ada kartu ringkasan lintas-horizon — lihat §3b.

- **`TradingViewModel` adalah facade** yang dipecah ke banyak berkas (`TradingViewModelSync.kt`,
  `TradingViewModelAlerts.kt`, `TradingViewModelReadiness.kt`, …). Menambah state berarti menambah
  properti di `TradingViewModel.kt` **dan** logika di berkas ekstensinya — extension tidak bisa
  menyimpan state.
- **Tanpa Hilt.** Semua ViewModel dibuat manual di `MainActivity.kt`.
- **Tanpa Navigation-Compose.** Navigasi = enum `AppScreen` + `AnimatedContent` di `MainActivity`,
  dengan helper `openXxx()` di `TradingViewModelNavigation.kt`.
- **Teks UI ditulis langsung dalam Bahasa Indonesia** di Compose. `strings.xml` hanya berisi
  `app_name`.

**Menambah layar baru (3 langkah):**

1. tambah konstanta di `enum AppScreen` (`model/TradingViewModels.kt`);
2. tambah cabang `when (screen)` di `MainActivity.kt` + import;
3. tambah `fun TradingViewModel.openXxx()` di `viewmodel/TradingViewModelNavigation.kt`.

---

## 3b. DIBATALKAN — fitur kesiapan multi-horizon (§14/§15)

**Jangan bangun ulang.** Fitur ini pernah ada di kode, lalu **dihapus seluruhnya pada 2026-10-13**.

| | |
|---|---|
| Yang dihapus | `engine/readiness/*` · `ui/components/readiness/*` · `ReadinessBoardScreen.kt` · `TradingViewModelReadiness.kt` · `AppScreen.READINESS` · kartu `SIAP TRADING` (dashboard) · kartu `KESIAPAN HORIZON` (detail koin) · 4 berkas tes |
| Cara dihapus | `git revert` 4 commit kode horizon (commit `revert(readiness): hapus fitur horizon dan seluruh turunannya`) |
| Efek | `app/src` kembali **identik** dengan `9e892fc`; tes 385 → **338** |
| Bukti | `git diff 9e892fc -- app/src` **kosong** |

**Alasan pembatalan.** Fitur ini menilai kesiapan koin **sendiri**, dengan daftar syarat yang
berbeda dari engine yang sedang aktif. Halaman detail koin lalu menampilkan `KESIAPAN HORIZON
3/5 syarat lolos` sementara kartu `SCALP DESK` tepat di bawahnya menampilkan `1/5 lolos`. Keduanya
"benar" menurut daftarnya masing-masing — dan justru itu masalahnya: pembaca tidak punya cara
untuk tahu mana yang harus dipercaya. Owner menilai datanya **dobel**, dan memutuskan menghapus
permukaan keduanya alih-alih menyinkronkannya.

**Pelajaran yang tetap berlaku:**

1. **Satu engine, satu permukaan status.** `ScalpReadiness` + `ScalpDeskCard`/`WaitingEntryRadarCard`
   sudah cukup. Jangan menambah kartu ringkasan kedua untuk data yang sama.
2. **Status engine boleh dipetakan, angkanya tidak boleh dihitung ulang.** Kalau sebuah permukaan
   butuh angka dari engine, ambil dari engine — jangan menyusun daftar syarat sendiri.
3. **Jangan pernah menampilkan `N/5` dari dua daftar syarat berbeda untuk koin yang sama.**
   Format yang sama akan dibaca sebagai satu kebenaran.

Riwayat lengkap ada di `workplan2.md` §14/§15 (keduanya bertanda ⛔ DIHAPUS) dan dua laporan di
`docs/LAPORAN-readiness-multihorizon-*.md` serta `docs/LAPORAN-ssot-horizon-vs-engine-*.md`.

---

## 4. Berikutnya — prioritas dan alasannya

Diurutkan berdasarkan **nilai dibagi risiko**. Jangan mengerjakan yang bawah sebelum yang atas
selesai, kecuali ada instruksi eksplisit.

### ⭐ Prioritas #1 — cari varian **entry** yang punya edge kotor (belum ada jawabannya)

Temuan akuntansi (§5) mengubah pertanyaannya. Bukan lagi "apakah exit-nya bisa diperbaiki",
melainkan: **adakah varian entry yang menghasilkan avg R kotor > +0,167R pada horizon 120 bar M1?**

Alasannya aritmetis: lantai friksi memaksa SL ≥ 5,81%, dan trade tanpa edge hanya menanggung biaya
lalu ditutup → `−0,9688% / 5,81% = −0,167 R`. Itu persis yang terukur (avg R ≈ −0,17, 0/40 pair
positif). **Selama tidak ada entry yang melewati +0,167R kotor, menyetel exit (P4.2/P4.4) tidak
akan mengubah apa pun.** Kerjakan ini sebelum yang lain.

### P4.1 — Walk-forward antar-bulan ✅ tooling selesai, hasil NEGATIF (2026-10-14)

**Masalah:** validasi keluar-sampel saat ini hanya **satu potongan** (train 70% / test 30% menurut
waktu). Satu potongan bisa kebetulan menguntungkan.

**Kerjakan:** perluas `tools/split_validate.py` menjadi beberapa jendela bergulir (mis. 4 jendela
per bulan) dan laporkan sebaran hasil, bukan satu angka. Pertanyaan yang harus dijawab: **apakah
tanda positif bertahan di semua jendela?**

**Status:** mode `--walk-forward` (+ `--window-bars`, `--step-bars`) **sudah ada dan sudah
diperbaiki** — lihat `docs/LAPORAN-p41-walk-forward-2026-10-14.md` dan perangkap #21.

**Dua hasil yang sudah keluar, keduanya negatif — dan keduanya belum sah sebagai kesimpulan:**

1. `data/hasil/u7_walk_forward_v4.{json,md}` — 8 pair `data/tokocrypto_v4`, 3 jendela,
   **0/3 lolos**, test avg R **−0,1790**. **Tapi kedelapan pair itu INELIGIBLE** menurut
   `pair_gate.json` (rasio 0,242×–1,638×, semua di bawah 2,0). Jangan pakai untuk menyimpulkan.
2. Hasil 40 pair `data/tokocrypto_live_usdt` — lihat §4 di laporan P4.1.

**Selesai bila:** ada tabel per-jendela dari **dataset 40 pair yang gate-eligible**, dan
kesimpulannya eksplisit (bertahan / tidak).

### P4.4 — Rehidrasi FSM paper: pulihkan progres ✅ **SELESAI 2026-10-15** (`56eb5cd`)

**Masalah:** `barsHeld`, `isBeArmed`, `isPartialDone`, `peak` **reset** saat restart
(`workplan2.md` §12). Mesin yang dipulihkan tidak identik dengan yang hilang.

**Selesai:** seluruh progres FSM (termasuk `isTrailArmed`, `partialR`, `activeStopLoss`, `atrPct`)
disimpan sebagai satu JSON di key `_paper_fsm`; `barsHeld` dihitung dari jam dinding.
Bukti: `docs/LAPORAN-p44-persistensi-fsm-2026-10-15.md`, `workplan2.md` §18.1.

**Jangan ulangi kesalahannya:** `atrPct` harus dioper saat mesin dibangun di `PaperRehydrate.decide()`
(bukan lewat `restore()`), dan key `_paper_fsm` harus dihapus di **setiap** jalur pelepasan
kepemilikan posisi.

### P4.2 — Preferensi maker untuk pair IDR

**Masalah:** tarif taker IDR 0,8144% vs maker 0,6144%. Semua perhitungan saat ini memakai taker.

**Kerjakan:** dukung `useMaker = true` di jalur yang relevan; pastikan `ScalpingRiskEngine` dan
`FeeCalculator` menerima flag yang sama supaya angkanya tidak berbeda.

**Perhatikan:** ini mengubah angka net R:R di seluruh UI. Ukur ulang, jangan hanya ganti konstanta.

### P5 — Data Indodax + replay TAPI v2

**Masalah:** folder `data/` belum punya data Indodax; `IndodaxTradeApiV2.kt` ada tetapi belum
diuji ujung-ke-ujung.

**Kerjakan:** harness pengambilan data + replay, lalu jalankan pipeline §9 pada data Indodax.
Perhatikan: Indodax **hanya IDR**; fee 0,79% (lihat `FEE_TRUTH.md`).

### P6 — Auto-execute uang riil

**DIBLOKIR** sampai P4.1 dan P5 selesai. Jangan disentuh tanpa instruksi eksplisit owner.

---

## 5. Kenapa kesiapan uang riil masih BELUM (jangan lupa ini)

> 🔴 **Angka lama "+0,0216 avg R keluar-sampel" SUDAH TIDAK SAH.** Angka itu berasal dari
> `u4_split_toko_usdt` yang dibuat sebelum `703c3b6`, ketika trade `UNRESOLVED` **dikeluarkan**
> dari statistik (±83% sinyal dibuang). Setelah trade yang sama dihitung jujur, hasil 40 pair
> berbalik menjadi **avg R ≈ −0,17, 0/40 pair positif**. Lihat
> `docs/LAPORAN-temuan-akuntansi-replay-2026-10-14.md`.

| Fakta | Angka |
|---|---|
| avg R terbobot, keluar-sampel (akuntansi baru, 40 pair) | **≈ −0,17** |
| Pair dengan avg R > 0 (keluar-sampel) | **0 dari 40** |
| Sinyal yang dulu dibuang akuntansi lama | **±83%** |
| Walk-forward 40 pair (4 jendela) | **0/4 lolos**, test avg R **−0,1614** |
| Walk-forward 8 pair gate-rejected (v4, 3 jendela) | **0/3 lolos**, test avg R **−0,1790** |

Artinya bukan lagi "tandanya benar, besarnya belum cukup" — melainkan **tidak ada edge kotor
yang tersisa setelah friksi**. Rata-rata trade bergerak persis sebesar biaya round-trip
(−0,9688% / 5,81% ≈ −0,167 R).

Aturan yang sudah berlaku dan **jangan dilonggarkan**:

- `MIN_FRICTION_MULTIPLE = 6.0` — jangan turunkan di bawah 5 tanpa bukti replay baru.
- `PairEdgeGate` rasio ≥ 2,0 — jangan dilemahkan supaya sinyal bertambah.
- **Trade `UNRESOLVED` wajib dihitung** (atau jumlahnya disebut) di setiap angka avg R.
- Setiap permukaan yang menampilkan status `READY` wajib menyertakan konteks bahwa eksekusi uang
  riil masih diblokir.

---

## 6. Perangkap yang sudah pernah ditemukan (hemat waktu Anda)

1. **`setupOk` di `ScalpingMtfSnapshot` bukan hasil deteksi pola.** Field itu diisi `step2Ok`
   (tekanan orderbook). Yang benar-benar mensyaratkan pola adalah `triggerOk` (= `step3Ok`).
   Memakai `setupOk` untuk menyimpulkan "ada setup" adalah sumber bug asli §11/§13.
2. **`ScalpExitParams` dan `ScalpingRuntimeConfig` bisa berubah saat runtime.** Jangan menyalin
   nilainya ke variabel saat inisialisasi; baca dari `ScalpingRuntimeConfig.current` saat dipakai.
3. **`PaperAutoTradeRuntime.machines` hanya hidup di memori.** Posisi dan `auto_sell_enabled`
   persisten — tiga hal ini harus dijaga sinkron (§12).
4. **SL dari `setHolding` itu sintetis** (`entry * 0.99` ≈ 1%), bukan SL engine. Selalu tulis SL
   asli lewat `setAutoSellParams(stopLossPrice = ...)`.
5. **`paperExitSnapshot` adalah StateFlow tunggal** yang diisi tick pair mana pun. Di halaman detail
   pakai `paperSnapshotFor(exchange, symbol, price)`.
6. **Candle bisa ber-timestamp detik atau milidetik.** `MtfCacheManager.isCacheValid` menangani
   keduanya — tiru pola itu bila membandingkan waktu.
7. **`MtfCacheManager` hanya mem-prefetch M1/M15/H1/H4 — D1 tidak pernah.** Bila suatu saat ada
   fitur yang butuh D1, ia harus meminta lewat `retryTimeframe`. Jangan menyimpulkan "engine rusak".
8. **Clone di sandbox kadang dibatasi** `remote.origin.fetch=+refs/heads/main:...`, sehingga
   `git branch -r` terlihat hanya berisi `main` padahal remote punya 13 branch. Perbaiki refspec
   dulu sebelum menyimpulkan.
9. **`debug.keystore` di-gitignore.** Build pertama di mesin baru bisa gagal di
   `validateSigningDebug` — buat keystore debug lokal.
10. **Test dengan `Math.random()` tidak membuktikan apa pun.** Semua fixture di repo ini
    deterministik; pertahankan itu.
11. **Gelombang sinus membuat bar terakhir bisa kebetulan turun.** Saat membuat fixture "tren naik",
    paksa bar terakhir naik, kalau tidak uji momentum akan gagal karena alasan yang salah.
12. **Uji yang selalu hijau tidak membuktikan apa pun.** Untuk setiap perbaikan bug, matikan
    perbaikannya, buktikan test GAGAL, lalu kembalikan. Pola ini dipakai di §12, §13, dan §15.
13. **Dua permukaan yang menampilkan format sama (`N/5`) akan dibaca sebagai satu kebenaran.**
    Ini akar pembatalan §14/§15: kartu kesiapan dan kartu engine sama-sama menulis `N/5` dari
    daftar syarat yang berbeda. Kalau menambah permukaan baru, pastikan pembaca tahu **siapa**
    yang menjawab — atau jangan tampilkan angkanya sama sekali.
14. **Menambah permukaan kedua untuk data yang sudah ditampilkan bukan "melengkapi", melainkan
    menciptakan cara untuk berbeda.** Itu yang terjadi di §14/§15, dan akhirnya seluruh fitur
    dibatalkan. Sebelum membuat kartu/layar baru, tanyakan: apakah data ini sudah ada di kartu
    lain? Kalau ya, tambahkan di situ — jangan buat permukaan baru.
15. **Preferensi pengguna (`StrategyMode`) mengikat seluruh aplikasi.** Hanya ada **satu** engine
    aktif pada satu waktu, dan hanya engine itu yang berhak menyimpulkan. Jangan membuat fitur
    yang menilai koin dengan aturannya sendiri di luar `StrategyMode`.
16. **Bar terakhir dari bursa SELALU sebagian.** Volume bar M1 yang masih berjalan tidak boleh
    dibandingkan dengan rata-rata bar penuh — hasilnya bias rendah sebesar persentase waktu yang
    sudah berjalan (kasus nyata: 0,76× padahal 1,46×). Gunakan `IndicatorMath.elapsedFraction` +
    `projectVolumeToFullBar`, dan pastikan `CandleBar.isClosed` benar-benar terisi (REST & WS).
17. **Jangan menulis field jurnal sebagai konstanta.** `sellReason = "SIMULATION_SELL"`,
    `isTrailingUsed = false`, `signalTime = now`, dan `trailingPercent = 1.5` semuanya membuat
    jurnal tidak dapat diaudit — dan `trailingPercent = 1.5` bahkan bisa salah 10× dari nilai
    konfigurasi nyata (0,15). Setiap kolom harus berasal dari state nyata atau `null`.

18. **Ambang yang berasal dari SATU kasus nyata jangan dijadikan default.** Gerbang
    extended-move (S9) direkomendasikan dari AMP (+28,09%/24 jam) dengan ambang 25%.
    Diuji pada GNS: ambang itu menghapus **seluruh** trade — padahal tanpa gerbang trade
    tersebut 4/4 menang, avg R +0,178, tanpa drawdown. Ambang tetap dikirim, tetapi
    default 0 (nonaktif) sampai ada walk-forward multi-pair.
19. **Mengubah default parameter exit mengubah hasil replay lama, bukan hanya kode.**
    `min_profit_friction_multiple` default 2,0 memblokir lantai profit di bawah 1,94% kotor,
    sehingga uji paritas lama gagal meski tidak ada yang rusak. Bila skenario uji bergerak
    kecil, **matikan lantai secara eksplisit** dan tulis alasannya; jangan sekadar
    memperbarui angka harapan.
20. **Satu exit = satu pemilik.** `PaperExitStateMachine` dulu hanya dibuat untuk auto-buy,
    jadi beli manual berjalan tanpa SL/BE/trailing (AMP menahan −2,76% selama 9 menit).
    Saat menambah jalur entry baru, pastikan ia ikut memanggil `armPaperExitEngine()`
    **dan** mematikan jalur auto-sell/trailing lama untuk pair yang sama.
21. **Jangan mengelompokkan jendela walk-forward dengan timestamp bar.** Tiap CSV pair mulai
    pada detik yang berbeda (pengambilan data berurutan), jadi `win[0]["t"]` **berbeda beberapa
    detik antar-pair**. Mengelompokkan dengan kunci itu memecah satu jendela menjadi beberapa
    "jendela" palsu yang masing-masing hanya berisi 1–2 pair — dan verdict-nya jadi dihitung dari
    satu pair. Pakai **indeks bar** (`window_start_bar`) sebagai kunci; timestamp hanya untuk
    tampilan. Bug ini sudah terjadi di `u7_walk_forward_v4` (9 jendela palsu dari 8 pair,
    seharusnya 3 jendela berisi 8 pair) — lihat `LAPORAN-p41-walk-forward-2026-10-14.md` §2.
22. **Verdict walk-forward hanya berlaku untuk dataset yang diuji.** Hasil negatif pada 8 pair
    yang **ditolak `PairEdgeGate`** (BTC 0,518×, TRX 0,242×, …) tidak boleh dipakai menyimpulkan
    strategi gagal. Periksa dulu apakah pair di direktori data lolos gate, baru tafsirkan verdict.
23. **Jangan melaporkan avg R tanpa menyebut berapa sinyal yang tidak tuntas.** `summarize()` hanya
    menghitung `WIN`/`LOSS`; trade `UNRESOLVED` dulu dibuang diam-diam. Pada data 40 pair itu
    membuang **±81% sinyal** dan membalik verdict dari TIDAK LOLOS (avg R −0,17) menjadi
    LOLOS (avg R +0,058). Kalau angka avg R tidak disertai jumlah sinyal yang dibuang, angka itu
    tidak bisa ditafsirkan. Bukti: `docs/LAPORAN-temuan-akuntansi-replay-2026-10-14.md`.
24. **Setelan nominal auto-buy itu Rupiah — dan anggaran wajib menyisihkan fee + slippage.**
    `PaperAutoTradeConfig.fixedIdrAmount` (dulu `fixedQuoteAmount`) disajikan di UI sebagai Rupiah
    (default 100.000, langkah 10.000). Kalau nilainya dipakai apa adanya sebagai **kas kuotasi pair**,
    Rp 100.000 menjadi $100.000 di pair USDT. Wajib dikonversi lewat kurs `ExchangeRateManager` di
    `PaperAutoTradeConfig.plan()`.
    Sisi keduanya: batas anggaran dulu memakai **saldo mentah** (`coerceAtMost(availableQuote)`),
    padahal order MARKET memotong `slippage` **dan** `fee` di atas nilai posisi
    (`notional × (1+slippage) × (1+fee)`). Akibatnya setiap nominal yang ≥ saldo **selalu** ditolak —
    auto-buy gagal 100%, bukan sesekali. Contoh nyata AVAUSDT: saldo $557,71 → butuh $560,41 → ditolak.
    Aturan: hitung anggaran dengan rumus yang **identik** dengan order engine; bila nominal tidak
    muat, **lewati** dengan alasan yang bisa dibaca — jangan dipotong diam-diam. Bila kurs USDT/IDR
    belum tersedia, lewati sinyalnya — jangan menebak ukuran. Bukti:
    `docs/LAPORAN-bug-auto-buy-nominal-idr-2026-10-15.md`.

---

## 7. Checklist sebelum menyelesaikan pekerjaan

- [ ] `./gradlew testDebugUnitTest assembleDebug` hijau; angka test **diukur**, bukan diingat.
- [ ] Baseline dibandingkan lewat `git stash` (bukan dari pesan commit lama).
- [ ] Kontrol negatif dijalankan untuk setiap perbaikan bug.
- [ ] `workplan2.md` diperbarui (seksi baru bila perlu).
- [ ] `AGENTS.md` diperbarui bila ada aturan baru.
- [ ] `docs/LAPORAN-<topik>-<tanggal>.md` ditulis (dalam Bahasa Indonesia).
- [ ] Tidak ada token/keystore/`.env` yang ikut ter-commit.
- [ ] Push ke **`patch`**, bukan `main`.
