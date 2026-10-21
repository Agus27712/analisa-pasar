# PANDUAN LANJUT AGEN — analisa-pasar

> **Baca ini dulu.** Dokumen ini adalah titik masuk untuk agen/engineer berikutnya: status terkini,
> apa yang sudah selesai, apa berikutnya, dan **perangkap yang sudah pernah ditemukan** supaya tidak
> diulang. Terakhir diperbarui: **2026-10-20** (batch 6p + parity replay + Spec v2 offline).
>
> 🔴 **Untuk "apa lagi yang tersisa" → `docs/bugs/`, bukan dokumen ini.** Status temuan audit
> (notifikasi `N*`, jurnal/P&L `J*`, paper trading `P*`, koneksi `K*`) sudah diverifikasi ke kode dan
> **direview owner** pada `patch` @ `547048d`, lalu ditutup bertahap sampai batch 6o:
> **seluruh berkas temuan tutup — P1–P9, J1–J10, N1–N17, K1–K15**. Ringkasnya:
>
> | Status | Temuan |
> |---|---|
> | 🔴 Kritis — masih hidup | — |
> | 🟠 Serius — masih hidup | — |
> | 🟡 Sedang/rendah — masih hidup | — |
> | ✅ Sudah ditutup | **P1–P9 (seluruhnya — P6 sisa + P9 ditutup batch 6o, 2026-10-18; berkas temuan dipindah ke `docs/laporan_temuan_bugs_paper_trade.md`)** · **J1–J10 (seluruhnya — berkas temuan jurnal dihapus 2026-10-17)** · **N1–N17 (seluruhnya — batch 6i, 2026-10-17)** · **K1–K15 (seluruhnya — batch 6j–6n; berkas temuan koneksi dipindah 2026-10-18)** |
>
> **`docs/bugs/` kini kosong** — tidak ada temuan audit yang masih hidup. Riwayat + aturan turunan:
> `docs/laporan_temuan_bugs_paper_trade.md` (P1–P9),
> `docs/laporan_temuan_bugs_notifikasi.md` (N1–N17),
> `docs/laporan_temuan_bugs_koneksi_tokocrypto_indodax.md` (K1–K15).
>
> 📊 **`docs/exp/` = hasil eksperimen terukur** (satu berkas per eksperimen,
> `exp_<testname>_<pair>.md`). **Baca sebelum percaya klaim "strategi ini bisa profit" atau
> mengubah parameter engine.** Eksperimen terbaru (2026-10-20):
> `exp_scalping_expectancy_ADA-DOGE-XRP.md` — eksperimen lama: win rate **42,7%** vs ambang
> **60,1%** pada geometri SL 1,94% / TP2 2,42%; harness lama belum punya time-stop.
> `exp_scalping_expectancy_parity_ADA-DOGE-XRP.md` — pengulangan dengan exit app (time-stop
> 90 bar): win rate **14,88%**, avg R **−0,4898**, 963/1.149 trade TIMEOUT. Scalping di
> Tokocrypto **tidak layak untuk engine saat ini**.
> `exp_mfe_m15_41pair.md` — MFE 15m 90 hari: **4/41 layak** (MUBARAK, PUMP, ZRO, PLUME);
> 10 pair yang dulu eligible turun status, **0 pair naik**. Tabel `PairEdgeGate` lama kedaluwarsa.
> `exp_spot_backtest_41pair.md` — backtest Spec v2: produksi 15m **0 trade**, produksi 1h
> **7 trade semua rugi** (avg R −0,3835); varian riset terbaik −0,2055R dengan OOS −0,3828.
> **Spec v2 belum punya bukti edge; Fase 1 Kotlin diblokir sampai keputusan owner.**
> Jejak audit jurnal ada di laporan per batch
> (`docs/LAPORAN-batch3-jurnal-*.md` … `docs/LAPORAN-batch6-j*-2026-10-17.md`).
> Aturan baru sejak 2026-10-17: berkas temuan yang **seluruh isinya** sudah tutup dipindahkan ke
> `docs/laporan_temuan_bugs_<topik>.md`, dan **tidak ada lagi laporan per batch**.
> Sinkronisasi status terakhir: `docs/LAPORAN-sync-dokumen-temuan-2026-10-16.md` (`workplan2.md` §40).
>
> 📄 **Dokumen sinkronisasi Spec Scalping Spot v2 (2026-10-20):**
> `docs/LAPORAN-sinkronisasi-spec-scalping-spot-2026-10-20.md` — pemetaan
> **Spec v2** (`docs/exp/scalping_spot_strategy_spec.md`, M15/H1, G0–G8, taker-only,
> lantai friksi 6×) ke codebase, hasil verifikasi, temuan kritis, dan rencana 8 fase.
> **Status: dokumentasi; kode belum disentuh.**
>
> 📊 **Dataset ukur Spec v2 (2026-10-20):** `data/tokocrypto_spot/` — 41 pair (14 eligible
> `PairEdgeGate`, 12 stabil, 15 momentum), 90 hari, interval 15m dan 1h, 441.880 bar, 0 celah.
> Rincian kriteria dan angka di laporan §10. Tool: `tools/fetch_tokocrypto_klines.py`
> (`--select mixed`, `--days`, `--resume` membekukan daftar pair). Backtest **sudah dijalankan**
> (2026-10-20): hasil negatif; lihat `docs/exp/exp_spot_backtest_41pair.md`. Tool pengukuran:
> `tools/spot_mfe.py`, `tools/spot_backtest.py`, `tools/test_spot_backtest.py`.

---

## 1. Status terkini

| | |
|---|---|
| Branch kerja | **`patch`** (owner me-review di sini dulu; `main` hanya menerima merge) |
| HEAD `patch` | Ukur: `git rev-parse --short origin/patch` — berubah **tiap commit**, jangan dihardcode |
| HEAD `main` | = `patch` + commit merge di atasnya; ukur `git rev-parse --short origin/main` |
| Titik masuk kode | package `agu.analys`, modul tunggal `:app` |
| Unit test | **773 uji · 772 lulus · 0 gagal · 1 dilewati** (101 kelas) — diukur dari XML lokal JDK 17, 2026-10-20 |
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
| Spec v2 | **Pengukuran offline Spec v2** — MFE 15m 90 hari + backtest 41 pair (5 setup, 15m/1h, G0–G8, exit §7) | `tools/spot_lib.py`, `tools/spot_mfe.py`, `tools/spot_backtest.py`, `tools/test_spot_backtest.py` | `docs/exp/exp_mfe_m15_41pair.md`, `docs/exp/exp_spot_backtest_41pair.md`; hasil negatif, Fase 1 Kotlin diblokir |
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

### ⛔ Spec v3 (harness Python) — diukur 2026-10-21, hasil NEGATIF; E1–E4 tidak lolos

Laporan lengkap: `docs/exp/exp_spot_evidence_v3.md` dan
`docs/LAPORAN-harness-spec-v3-2026-10-21.md`.

- Dataset 180 hari, 41 pair, **868.054 bar**, 0 celah/duplikat.
- Gate edge jendela A hanya **1 pair (IOUSDT)**; jendela B **4 pair** (MUBARAK, PUMP, ZRO, PLUME);
  irisan A ∩ B **kosong**.
- **Produksi: 15m 0 trade; 1h 0 trade OOS.** Satu-satunya trade produksi ada di jendela A
  (1 SWEEP, avg R −0,4849). E1/E2 tidak terukur, bukan sekadar kurang sampel.
- Diagnostik tanpa G0: 15m B 7 trade, avg R **−0,2495** (gross −0,1061); 1h B 45 trade,
  **−0,2561** (gross −0,1146); panel penuh 180 hari 15m 12 trade (−0,2888), 1h 67 trade (−0,2391).
- Benchmark acak: SWEEP 1h N=160 gross **−0,1165** vs acak rata-rata **+0,0157** / p99 +0,1122;
  15m N=47 −0,1163 vs +0,0084 / p99 +0,1721. **Entry strategi kalah dari entry acak.**
- 15m tidak bisa dieksekusi: lantai 5,8128% + G2 maksimum 5 ATR menuntut ATR% ≥ 1,16256%;
  IOUSDT 15m median ATR% 0,6413% dan G2 menolak 1.490/1.584 sinyal SWEEP 15m (94,1%).
- Di 1h, time-stop 1,5–3 jam = **3 candle**, jadi 1h bukan horizon lebih panjang.
- E3 **sebagian** (universe belum walk-forward); E4 **tidak cukup sampel**. Satu varian longgar
  (`min_net_rr_final ×0,8`) memberi **1 trade** IOUSDT SWEEP +1,403R; satu trade bukan sampel.

**Jangan mulai Fase 1 Kotlin.** Masalah utama ada di **entry** (R kotor sudah negatif sebelum
biaya), bukan di exit atau friksi. Opsi: hentikan lini Spec v3, rancang ulang entry SWEEP,
uji horizon lebih panjang lewat `timeStopHours`, atau perbaiki gate menjadi walk-forward berkala.
Rekomendasi: rancang ulang entry lebih dulu, tanpa menulis engine.

### Riwayat: Spec v2 (M15/H1) — diukur 2026-10-20, hasil NEGATIF; Fase 1 Kotlin diblokir

Laporan lengkap: `docs/exp/exp_spot_backtest_41pair.md` dan `docs/exp/exp_mfe_m15_41pair.md`.

- **Produksi 15m: 0 trade** dari 41 pair dalam 90 hari. Lantai 6x (5,8128%) + G2 maksimum 5 ATR
  + G3 minimum 1,5F (1,4532%) saling mengunci; ATR% 15m sebagian besar 0,2–1,1%.
- **Produksi 1h: 7 trade, semuanya rugi**, WR 0%, avg R −0,3835.
- **Riset longgar: 37–74 trade, semuanya negatif.** Yang terbaik −0,2055R (lantai 4x + G4–G6
  longgar), in-sample −0,0912, out-of-sample −0,3828.
- **SWEEP_RECLAIM konsisten rugi** (6–67 trade, −0,17 sampai −0,40R); BREAKOUT negatif pada
  14–22 trade di 15m dan positif hanya n=6 di 1h; PULLBACK hanya 2 trade (rugi); RANGE 0.
- **MFE 90 hari: 4/41 layak**, bukan 14; tidak ada pair baru. Tabel `PairEdgeGate` lama
  kedaluwarsa untuk jendela ini.

**Jangan mulai Fase 1 Kotlin** sebelum owner memutuskan: perbaiki kontradiksi gate/TP1,
rancang ulang pemicu/exit SWEEP, pindah ke horizon lebih panjang, atau hentikan program.
Kalau memilih lanjut, mulai dari memperbaiki spec — bukan dari menulis engine.

**Pembaruan (2026-10-21): Spec v3 sudah diukur — hasilnya NEGATIF.** Harness Python setara v3
sudah dibuat dan dijalankan pada dataset 180 hari; E1–E4 tidak lolos. Blok di atas adalah
ringkasannya. Spec yang diperbaiki **tidak** membuka blokir: Fase 1 Kotlin tetap menunggu
E1–E3 lolos, dan saat ini belum ada satu setup pun dengan R kotor positif.

### ⭐ Prioritas #1 — cari varian **entry** yang punya edge kotor (belum ada jawabannya)

Temuan akuntansi (§5) mengubah pertanyaannya. Bukan lagi "apakah exit-nya bisa diperbaiki",
melainkan: **adakah varian entry yang menghasilkan avg R kotor > +0,167R pada horizon 120 bar M1?**

Alasannya aritmetis: lantai friksi memaksa SL ≥ 5,81%, dan trade tanpa edge hanya menanggung biaya
lalu ditutup → `−0,9688% / 5,81% = −0,167 R`. Itu persis yang terukur (avg R ≈ −0,17, 0/40 pair
positif). **Selama tidak ada entry yang melewati +0,167R kotor, menyetel exit tidak akan
mengubah apa pun.** Kerjakan ini sebelum yang lain.

Catatan dari harness Spec v3 (2026-10-21): dengan risiko per unit net `FeeSchedule`, ambang impas
setara adalah **R kotor ≈ +0,14R** (friksi ±0,142R per trade). R kotor terukur justru negatif
(−0,097 sampai −0,150R) di 15m dan 1h. Jadi pertanyaannya tetap sama dan jawabannya belum ada:
**belum ada entry yang melewati ambang kotor, apalagi mengalahkan entry acak.**

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

### P4.2 — Preferensi maker untuk pair IDR — DIBATALKAN 2026-10-20

**Keputusan:** jalur app dan replay **taker-only**. Knob `order_type`/maker dihapus dari replay,
config kanonik, dan ekspor JSON. P4.2 tidak dikerjakan karena akan menghidupkan kembali jalur
yang sudah dihapus dan membuat angka app/replay bercabang. Tarif maker tetap disimpan sebagai
data FEE_TRUTH di blok `fee`, bukan sebagai pilihan eksekusi.

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

> **Checklist ini dipakai ulang setiap batch — kotaknya sengaja dibiarkan kosong** (mencentangnya
> permanen akan membuat checklist berikutnya terasa "sudah terpenuhi" tanpa bukti). Bukti per
> batch ditulis sebagai catatan, bukan centang. Contoh batch 6o (2026-10-18):
> CI hijau **98 kelas / 757 uji** (run `39145158155`); baseline dibandingkan dari angka terukur
> batch 6n (94/726); kontrol negatif 6 mutasi menggigit (`workplan2.md` §59.3); `workplan2.md` §59,
> `AGENTS.md`, dan laporan temuan diperbarui; tidak ada laporan per batch baru (berkas temuan
> dipindah ke `docs/laporan_temuan_bugs_paper_trade.md`); tidak ada token ter-commit; push ke
> `patch` (`220e7a0`, `8fb8d12`).
