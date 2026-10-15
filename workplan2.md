# workplan2.md — Workplan aktif (Oktober 2026)

> **Sumber kebenaran workplan.**  
> `workplan.md`, `checkpoint.md`, dan `checkpoint_progres.md` **DEPRECATED** — jangan diikuti agen. Baca ini + `AGENTS.md` + `APP_CONTEXT.md` (§ arah produk).

**Update:** 2026-10-15 (WIB)

> ⚠️ **Seksi §9.5b sudah DIBATALKAN** (angka edge lama tidak sah). Angka yang berlaku ada di
> **§9.5c**. Seksi §14/§15 **dibatalkan** — fiturnya sudah dihapus dari kode, jangan dibangun ulang.
> Seksi §16–§18 baru ditambahkan 2026-10-15 (P0–P2, S1–S9, P4.4 + auto-buy).
> Seksi §19–§21 ditambahkan 2026-10-15 (batas posisi auto-buy, mode detail otoritatif, hapus kartu backtest).

---

## 1. Arah produk (kunci)

**analisa-pasar** = app Android screener + **scalp mode** untuk Tokocrypto (USDT/IDR) & Indodax (IDR only).

| Pilar | Keputusan |
|-------|-----------|
| Mode fokus | **Scalp mode** (setup MTF, skor, risk, exit paper) |
| Uang riil | **Tidak** untuk validasi strategi sampai paper + fee truth stabil |
| Validasi | **Paper trade** di app + **`tools/scalp_replay.py`** dengan config yang sama |
| Sensitifitas | Slider UI ↔ `ScalpingRuntimeConfig` ↔ JSON mirror `data/config/scalp_config.json` |
| Edge | Belum terbukti di data historis (v2/v3/Binance45d). Slider mengubah **frekuensi** sinyal, **bukan** menciptakan expectancy |
| Engine asal | Port logika dari [dikigambol/kriptoyoi](https://github.com/dikigambol/kriptoyoi) `analyzer/p0_engine.py` + spec; **bukan** copy seluruh web app |

**Satu kalimat:** bantu melihat setup & risiko (termasuk biaya all-in) dan menguji scalp di paper; jangan klaim profit otomatis.

---

## 2. Alur kerja (wajib diikuti agen)

```text
┌─────────────────────┐
│ ScalpingConfig.kt   │  konstanta default (seed)
│ (defaults only)     │
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ ScalpingRuntimeConfig│  runtime: slider / import JSON / preset
│ (DataStore + memory) │
└──────────┬──────────┘
           ├──────────────────────┐
           ▼                      ▼
┌─────────────────────┐  ┌────────────────────────┐
│ Engine live/paper   │  │ Export JSON            │
│ detector/score/risk │  │ = format scalp_config  │
└──────────┬──────────┘  └───────────┬────────────┘
           ▼                         ▼
┌─────────────────────┐  ┌────────────────────────┐
│ Paper trade journal │  │ tools/scalp_replay.py  │
│ log + thresholds    │  │ --config <exported>    │
└─────────────────────┘  └────────────────────────┘
```

1. Ubah threshold hanya lewat **RuntimeConfig** / JSON — jangan hardcode tersebar di detector.
2. Setelah ubah slider: uji **paper**; untuk historis, **export JSON** lalu replay.
3. Fee **bukan** slider “biar hijau”: ikut Fee Truth (all-in Tokocrypto/Indodax).
4. Commit docs/engine non-rilis: sertakan **`[skip ci]`** di pesan commit bila diminta owner.
5. Hipotesis entry baru = folder/tool terpisah, **jangan** merge ke config live sebelum gate edge kotor > fee (train+test).

---

## 3. Fase kerja aktif

### P0 — Fee Truth (prioritas) — [SELESAI ✅]
- [x] Dokumen singkat fee resmi (`FEE_TRUTH.md`: Tokocrypto USDT all-in 0.4044%, IDR taker 0.2222/0.4322; Indodax terpisah)
- [x] `TradingFeeConfig` + `FeeCalculator` + default exchange = Fee Truth (all-in Tokocrypto USDT/IDR)
- [x] `data/config/scalp_config*.json` fee block selaras (USDT 0.4044% per sisi)
- [x] UI Friction / VIABLE tidak pakai model upstream ~0,72% tanpa label
- [x] Unit test / assert RT fee snapshot (`FeeCalculatorTest.kt` passing)

### P1 — Runtime sensitivity + paritas alat — [SELESAI ✅]
- [x] `ScalpingRuntimeConfig` (seed dari `ScalpingConfig`, mutable, preset DEFAULT/STRICT/LOOSE/CUSTOM)
- [x] Wiring: `ScalpSetupDetector`, `ScalpingMtfEvaluator`, `ScalpingRiskEngine` baca runtime
- [x] UI Settings: preset Ketat/Default/Longgar + 11 slider (di `ScalpingSensitivitySettings.kt`)
- [x] Export / import JSON mirror `scalp_config.json` (tombol Export/Import di UI pengaturan scalping)
- [x] Paper trade log menyimpan snapshot thresholds (`TradeSignalSnapshot.scalpingThresholds`)
- [x] `scalp_replay` TIMEOUT fix di main (semua trade resolve + R net)
- [x] Audit BTC gate / TTL vs upstream p0 (terdokumentasi: tidak ada gate diskriminatif, TTL 10m standar di `SignalLifecycleManager`)

### P2 — Paper scalp auto & exit (decision support) — [SELESAI ✅]
> **Catatan Status:** Sinyal scalping + slider threshold aktif penuh. Auto-buy paper, engine exit 5 mode,
> paritas replay, visual FSM, dan kartu statistik sudah masuk. Seluruhnya **paper trade** — tidak ada
> jalur ke eksekusi uang riil dari sinyal scalp.
- [x] **Paper Auto-Buy (Simulasi)**: Toggle di Pengaturan → Engine Scalping. Membuka posisi simulasi saat sinyal
      `BUY` dengan `scalpingStage` ≥ ambang terpilih (`ENTRY` / `STRONG_ENTRY`). Alokasi **nominal tetap**
      atau **berbasis risiko** (`PaperAutoTradeConfig`). Tidak pernah memicu order uang riil.
      Nominal tetap diisi dalam **Rupiah** dan dikonversi ke kuotasi pair saat sinyal
      (`fixedIdrAmount / kurs USDT-IDR`), dengan anggaran yang sudah menyisihkan fee + slippage;
      nominal yang tidak muat **dilewati**, bukan dipotong (2026-10-15).
- [x] **Paper Auto-Exit Modes**: `PaperExitResolver` + `PaperExitStateMachine` meniru `resolve_trade()` di
      `tools/scalp_replay.py` untuk 5 mode (`classic`, `tp1_be`, `early`, `trail`, `full`), termasuk R seragam
      net setelah fee all-in + slippage. Parameter exit masuk `ScalpingRuntimeConfig.exit` dan ikut ter-ekspor
      sebagai blok `exit` di JSON (dibaca replay).
- [x] Paritas diuji: `PaperExitResolverParityTest` — angka harapan dihasilkan langsung dari Python
      `tools/scalp_replay.py`, bukan dihitung manual.
- [x] `PaperTradeStats` menutup 2 keterbatasan yang dicatat di `SCALP_EXIT.md`: TIMEOUT **tidak** dibuang
      diam-diam, dan exit breakeven (r = 0) berlabel BREAKEVEN (bukan LOSS).
- [x] **FSM visual di UI paper trade** — `PaperExitFsmCard`: stepper fase (`HOLDING` → `TP1 50%` → `BE` → `TRAIL`),
      tangga level (entry / SL awal / SL aktif / BE net / peak / TP1 / TP2), bar posisi SL↔TP2, dan metrik
      berjalan (net %, R, jumlah bar M1, jarak ke SL). Angka diambil dari `PaperExitSnapshot` — mesin yang sama
      dengan yang memutuskan exit, jadi tidak ada dua sumber kebenaran.
- [x] **Kartu statistik R net terakumulasi** — `PaperTradeStatsCard`: total R, rata-rata R, win rate, profit
      factor, hitungan menang/kalah/breakeven/timeout, rincian per alasan exit, dan 4 baris jurnal terakhir.
      Hasil tiap trade dipersistensikan lewat `PaperTradeJournalStore` (maks 500 baris), jadi akurat lintas restart.
- [x] **Kepemilikan exit tunggal** — saat engine P2 aktif untuk sebuah pair, jalur auto-sell lama
      (`checkTrailingForMode` → TP1/TP2/SL dari `positionStore`) dimatikan untuk pair itu, sehingga tidak ada
      dua mesin yang menjual posisi yang sama.
- [x] Evaluasi per-tick aman: pemeriksaan level (SL/TP/BE/trailing) jalan tiap tick, sedangkan hitungan bar M1
      dan time-stop hanya maju sekali per menit. Early-profit sengaja dilewati bila jendela momentum M1 belum
      tersedia — lebih baik menahan posisi daripada keluar dari data yang tidak terukur.
- [x] Larangan keras: **tidak ada** jalur real auto-execute dari sinyal scalp di kode yang ditambahkan.

### P3 — Riset entry (di luar path live) — [SELESAI ✅ — gate TIDAK lolos]
> **Hasil:** 8 hipotesis entry diuji pada 60 hari data Tokocrypto (12 Agu – 11 Okt 2026, 8 pair,
> 87.000 bar M1/pair, tanpa celah). **0 dari 8 lolos gate.** Gross mean terbaik di TEST hanya
> +0,03% terhadap ambang 1%; semua NET berada di −0,94%..−1,05%, yaitu praktis sama dengan
> friksi round-trip 0,97%. **Tidak ada yang dipromosikan ke config live** — dan memang tidak boleh.
- [x] Tool hipotesis terpisah + label HIPOTESIS: `tools/entry_hypothesis_lab.py`
- [x] Gate dijalankan: mean GROSS ≥ 1% di **TRAIN dan TEST**, n ≥ 30 per split,
      mengalahkan baseline acak (500 permutasi, ambang Bonferroni 0,05/8)
- [x] Harness divalidasi peka: `tools/selftest_entry_lab.py` — pada data sintetis ber-edge
      tanam +2% gate **lolos** (p = 0,0033), pada hipotesis kontrol gate **gagal**.
      Jadi hasil 0/8 adalah temuan, bukan alat yang buta.
- [x] Hasil lengkap tersimpan: `data/hasil/p3_entry_hypothesis_lab.json` + `.md`
- [ ] Promote ke RuntimeConfig default — **tidak dilakukan**, tidak ada kandidat yang lolos gate

---

## 4. Slider sensitifitas (map JSON)

Default = nilai di `ScalpingConfig.kt` / `data/config/scalp_config.json`.

| UI label | Key JSON | Default | Range usulan |
|----------|----------|---------|--------------|
| Skor min LONG | `min_score_long` | 60 | 45–80 |
| Skor STRONG | `min_score_strong` | 75 | 60–90 |
| Skor retest | `min_score_breakout_retest` | 75 | 60–90 |
| Min Net R:R | `min_net_rr` | 1.15 | 0.8–2.0 |
| Target Net R:R | `target_net_rr` | **2.0** | 1.0–2.5 |
| **Lantai risiko vs friksi** | `min_friction_multiple` | **6.0** | 0–10 (0 = nonaktif) |
| RVOL breakout | `rvol_breakout_min` | 1.5 | 1.0–2.5 |
| RVOL setup | `rvol_setup_min` | 1.2 | 0.8–2.0 |
| RVOL retest | `rvol_retest_min` | 1.5 | 1.0–2.5 |
| CHOP hard ranging | `chop_hard_ranging` | 65 | 55–75 |
| Jarak max retest % | `retest_max_dist_pct` | 1.2 | 0.5–2.5 |
| Strength struktur retest | `retest_min_structure_strength` | 60 | 40–80 |

> **Perubahan default 2026-10-12** (berdasar uji 30 hari × 40 pair Tokocrypto, lihat §9):
> `target_net_rr` 1.25 → **2.0** dan tambah slider `min_friction_multiple` = **6.0** (plateau 5–10×).
> Ini bukan kosmetik: kombinasi keduanya mengubah avg R dari **−1,8 menjadi ≈ +0,07** pada
> pair yang sama. Lihat §9 untuk bukti dan batasannya.

Advanced (opsional): `buy_pressure_*`, `min_risk_pct`, `max_tp2_r`, `slippage_pct`, exit paper params.

**Bukan slider:** fee all-in, bobot skor internal (structure 25, dll.).

---

## 5. Temuan empiris (jangan diabaikan)

- Tokocrypto v2 (up) / v3 (down) + fee all-in: exit full mengatur loss, **bukan** +EV stabil.
- Matrix `min_score` / `min_net_rr`: bottleneck utama di **entry** (s1/s3), bukan parameter risk saja.
- Binance vision ~45 hari (XRP/SOL/BTC): full exit tetap −EV orde besar; BTC sering 0 sinyal.
- 5 hipotesis entry baru di 45d: tidak lolos gate gross ≥1% train+test.
- Upstream p0 friction ~0,72% RT vs all-in riset ~0,97% → UI “VIABLE” bisa terlalu optimis.
- **P3 — 60 hari Tokocrypto (12 Agu – 11 Okt 2026), 8 pair, 8 hipotesis entry: 0/8 lolos gate.**
  Gross mean TEST terbaik hanya +0,03% (ambang 1%); semua NET −0,94%..−1,05%, yaitu ≈ friksi
  round-trip 0,97%. Artinya pada periode ini **timing entry tidak menyumbang apa pun** — rugi
  bersih praktis seluruhnya berasal dari biaya. MFE rata-rata hanya +0,33%..+0,58% dalam 1 jam,
  jadi bahkan gerakan 1% pun jarang tersedia untuk diambil.
  Satu hipotesis (`sweep reclaim`) **signifikan di train (p=0,002) lalu hilang di test (p=0,35)** —
  contoh langsung kenapa gate wajib diuji di dua periode, bukan satu.

Detail: `SCALP_EXIT.md`, `data/hasil/p3_entry_hypothesis_lab.md`, artefak lokal uji (bila ada).

---

## 6. Batasan exchange (dari AGENTS — wajib)

- Tokocrypto: **USDT + IDR** saja. Indodax: **IDR** saja.
- Dilarang request ke `binance.com` / `stream.binance.com` (diblok di ID).
- Cache/Room/prefs: partisi per `exchange`.
- Quote BIDR dieliminasi (kecuali nama koin seperti BNBIDR).

---

## 7. Dokumen terkait

| File | Peran |
|------|--------|
| **workplan2.md** (ini) | Workplan aktif |
| AGENTS.md | Aturan agen, build, batasan |
| APP_CONTEXT.md | Arsitektur + § arah produk terkini |
| SCALP_EXIT.md | Exit paper / R net |
| data/config/scalp_config.json | Mirror threshold + fee untuk replay |
| tools/scalp_replay.py | Offline paritas engine |
| tools/entry_hypothesis_lab.py | Lab hipotesis entry (P3) — offline, berlabel HIPOTESIS, tidak menyentuh config live |
| tools/selftest_entry_lab.py | Uji daya-deteksi lab (data sintetis ber-edge tanam) |
| data/hasil/p3_entry_hypothesis_lab.md | Hasil P3: 0/8 hipotesis lolos gate |

**Jangan baca sebagai instruksi aktif:** `workplan.md`, `checkpoint.md`, `checkpoint_progres.md` (legacy checklist tahap lama).

---

## 8. Definition of Done (per milestone)

> **Aturan DoD:** satu milestone baru boleh ditandai **Selesai** hanya bila **semua** item
> *fitur* **dan** semua item *keselamatan* tercentang. Item keselamatan tidak boleh dihapus
> dari DoD meskipun tidak nyaman — kelalaian inilah yang membuat cacat kepemilikan exit
> tunggal sempat lolos ke P2.

### Milestone P0 & P1 — [SELESAI ✅]
- [x] RuntimeConfig + minimal 1 layar slider + export JSON
- [x] Paper trade memakai RuntimeConfig + log threshold
- [x] Fee Truth tersambung di app + JSON replay
- [x] AGENTS/APP_CONTEXT menunjuk workplan2 saja untuk arah

### Milestone P2 — [SELESAI ✅]
**Fitur:**
- [x] Toggle Auto-Buy Paper Trade (eksekusi order beli simulasi otomatis saat transisi sinyal scalping terkonfirmasi READY/STRONG_ENTRY)
- [x] Engine Auto-Exit Paper Trade 5 mode (`classic`, `tp1_be`, `early`, `trail`, `full`) selaras `SCALP_EXIT.md`
- [x] Parameter exit tersambung ke `ScalpingRuntimeConfig` + ekspor JSON (blok `exit`) untuk paritas replay
- [x] Paritas diuji: `PaperExitResolverParityTest` — angka harapan dihasilkan langsung dari Python
      `tools/scalp_replay.py`, bukan dihitung manual (diverifikasi ulang independen: `tp2_hit` r = 1.5118, `sl_hit` r = −1.9545)
- [x] Statistik performa paper trade (R net terakumulasi setelah fee all-in) — mesin `PaperTradeStats` + unit test
- [x] FSM visual + kartu statistik di UI layar paper trade (`PaperExitFsmCard` + `PaperTradeStatsCard`),
      hasil per trade dipersistensikan di `PaperTradeJournalStore`

**Keselamatan (wajib — ditambahkan 2026-10-12 setelah audit kode):**
- [x] **Kepemilikan exit tunggal** — `PaperAutoTradeRuntime.ownsExit()` + helper `paperEngineOwnsExit()`
      menutup jalur auto-sell lama (TP1/TP2/SL **dan** trailing manual dari `DetailChartScreen`)
      untuk pair yang dipegang engine paper. Jalur uang riil (`isReal = true`) **tidak** tersentuh.
- [x] **Evaluasi per-tick aman** — level SL/TP/BE/trailing dicek tiap tick; bar M1 & time-stop maju
      sekali per menit; early-profit dilewati bila jendela momentum M1 belum tersedia.
- [x] **Larangan real auto-execute** — tidak ada jalur order uang riil dari sinyal scalp; penjaga
      kepemilikan hanya berlaku saat `isReal = false`.

> **Celah diketahui (di luar scope P2, belum diperbaiki):** setelah app di-restart, posisi paper
> kehilangan mesin exit — `PaperAutoTradeRuntime.machines` hanya di memori, sementara
> `auto_sell_enabled = false` persisten. Kandidat rehidrasi state machine saat startup; lihat P4.

### Milestone P3 — [SELESAI ✅ — gate TIDAK lolos, sengaja tidak dipromosikan]
- [x] Tool hipotesis terpisah + label HIPOTESIS: `tools/entry_hypothesis_lab.py`
- [x] Gate dijalankan: mean GROSS ≥ 1% di **TRAIN dan TEST**, n ≥ 30 per split,
      mengalahkan baseline acak (500 permutasi, ambang Bonferroni 0,05/8)
- [x] Harness divalidasi peka: `tools/selftest_entry_lab.py` — data sintetis ber-edge tanam +2%
      gate **lolos** (p = 0,0033); hipotesis kontrol **gagal**. Hasil 0/8 adalah temuan, bukan alat buta.
- [x] Hasil lengkap tersimpan: `data/hasil/p3_entry_hypothesis_lab.json` + `.md`
- [ ] Promote ke RuntimeConfig default — **SENGAJA TIDAK DILAKUKAN.** Checkbox dibiarkan kosong
      bukan karena pekerjaan tertunda, tetapi karena **aturan §2 poin 5 melarang** merge hipotesis
      ke config live sebelum lolos gate, dan **0/8 hipotesis lolos**. Tidak ada kandidat untuk dipromosikan.

---

## 9. P4 — Lantai risiko berbasis friksi & uji universe (2026-10-12)

> **Konteks arah produk:** owner memutuskan engine scalping **diarahkan ke trading uang riil**,
> dengan syarat: (a) diuji dulu di data riil memakai tool yang tersedia, (b) target semua pair
> Tokocrypto & Indodax, (c) jalur Indodax memakai **TAPI v2**, (d) **Tokocrypto dimaksimalkan
> lebih dulu** sebelum menyentuh Indodax.

### 9.1 Masalah yang ditemukan

Engine diuji ulang di **40 pair Tokocrypto USDT** (30 hari data M1 riil, `data/tokocrypto_live_usdt/`)
memakai config live. Hasil: **tidak ada satu pair pun yang avg R-nya positif.**

| Pair | Sinyal | Trade | WR | avg R | PF | MFE 7j median |
|---|---:|---:|---:|---:|---:|---:|
| ADAUSDT | 52 | 50 | 18,0% | −1,86 | 0,04 | 1,59% |
| APTUSDT | 179 | 177 | 29,4% | −1,45 | 0,11 | 2,14% |
| AVAXUSDT | 124 | 124 | 35,5% | −1,24 | 0,14 | 1,56% |
| ENAUSDT | 355 | 352 | 29,6% | −1,37 | 0,11 | 2,25% |
| BTCUSDT | 0 | 0 | — | — | — | 0,50% |
| BNBUSDT | 0 | 0 | — | — | — | 0,63% |

### 9.2 Diagnosis (bukan tebakan)

Dengan `min_risk_pct` = 0,5% dan friksi Tokocrypto USDT 0,9688%, **satu kali kena SL kehilangan
0,5% + 0,97% = 1,47% ≈ 2,9R**. Unit risiko jauh lebih kecil dari biaya, jadi matematikanya
mustahil: untuk impas pada target net R:R 1,25 dibutuhkan win-rate `w* = 1/(1+1,25) ≈ 44%`,
sementara engine hanya mencapai 18–35%. Ini persis peringatan yang sudah tertulis di
`FEE_TRUTH.md` §3 ("Distorsi Stop Loss Sempit") tetapi belum pernah diimplementasikan.

Distribusi exit mengonfirmasi: mayoritas trade keluar lewat `SL` dengan nilai ≈ −2 sampai −3R.

### 9.3 Perubahan yang diterapkan

| Perubahan | File | Alasan (terukur) |
|---|---|---|
| **Lantai risiko berbasis friksi** `min_friction_multiple` (default **6.0**, lihat §9.4b) | `ScalpingConfig.kt`, `ScalpingRiskEngine.kt`, `ScalpingRuntimeConfig.kt`, `tools/scalp_replay.py` | Memaksa `risk ≥ 6 × friksi round-trip`, jadi SL selalu menutup biaya beberapa kali |
| **`target_net_rr` default 1.25 → 2.0** | `ScalpingConfig.kt`, `data/config/*.json` | Menurunkan win-rate minimum agar impas |
| **Slider baru "Lantai Risiko vs Friksi"** | `ScalpingSensitivitySettings.kt` | Agar bisa diubah tanpa hardcode; ikut export/import JSON |
| **Config varian IDR** (`scalp_config_exit_full_idr.json`, `..._idr_maker.json`) | `data/config/` | Friksi IDR 0,8144% (taker) / 0,6144% (maker) < USDT 0,9688% |

### 9.4 Bukti: grid 81 kombinasi (6 pair, 20.000 bar M1)

| risk% | target RR | hold | fee | trade | WR | **avg R** | pair positif |
|---:|---:|---:|---|---:|---:|---:|---:|
| 3.0 | 2.0 | 90 | idr_maker | 321 | 90,7% | **+0,0235** | 4/6 |
| 3.0 | 3.0 | 90 | idr_maker | 321 | 90,7% | +0,0235 | 4/6 |
| 3.0 | 1.25 | 90 | idr_maker | 323 | 90,7% | +0,0232 | 4/6 |
| 3.0 | 2.0 | 90 | idr_taker | 256 | 88,7% | −0,0005 | 4/6 |
| 3.0 | 2.0 | 90 | usdt | 219 | 88,1% | −0,0108 | 3/6 |
| 1.5 | 2.0 | 90 | idr_maker | 557 | 61,2% | −0,3572 | 0/6 |
| 0.5 | 1.25 | 90 | usdt (lama) | 1.258 | 22,1% | −1,8192 | 0/6 |

**Hanya 3 dari 81 kombinasi positif, dan semuanya adalah `risk 3.0 + hold 90 + IDR maker`.**
Hubungannya monoton: risk 0,5% → avg R ≈ −1,8; 1,5% → −0,36; 3,0% → ≈ 0 / sedikit positif.

### 9.4b Sweep kelipatan friksi (menentukan default final)

Karena formulasi **relatif** (`min_friction_multiple`) terbukti lebih baik daripada lantai
absolut (`min_risk_pct` 3,0%), sweep dilanjutkan pada kelipatan friksi (6 pair, 20.000 bar):

| mult | fee | trade | WR | avg R | pair positif |
|---:|---|---:|---:|---:|---:|
| 3,0 | usdt | 224 | 86,6% | −0,0300 | 1/6 |
| 3,0 | idr_maker | 463 | 69,3% | −0,2367 | 0/6 |
| 4,0 | usdt | 200 | 93,5% | +0,0342 | 4/6 |
| 4,0 | idr_maker | 362 | 82,6% | −0,0659 | 1/6 |
| 5,0 | usdt | 188 | 97,3% | +0,0655 | 5/6 |
| 5,0 | idr_maker | 318 | 90,9% | +0,0245 | 4/6 |
| **6,0** | **usdt** | **186** | **98,9%** | **+0,0695** | **6/6** |
| **6,0** | **idr_maker** | **297** | **93,9%** | **+0,0430** | **4/6** |
| 8,0 | usdt | 181 | 99,4% | +0,0557 | 6/6 |
| 8,0 | idr_maker | 276 | 98,6% | +0,0735 | 6/6 |
| 10,0 | usdt | 179 | 99,4% | +0,0436 | 6/6 |
| 10,0 | idr_maker | 273 | 99,3% | +0,0639 | 6/6 |

Ada **plateau lebar 5–10×**; **6,0 dipilih** sebagai default (terbaik untuk pair USDT, dan masih
baik untuk IDR maker). Di bawah 5× belum konsisten positif.

### 9.5 Batasan yang wajib dibaca (jangan dibuang)

1. **Angkanya tipis.** +0,0235R per trade tidak cukup untuk diklaim edge stabil. Ini baru
   "tidak rugi lagi", bukan "terbukti untung".
2. **Belum uji out-of-sample.** Grid dijalankan pada 6 pair × 20.000 bar yang sama; belum ada
   split train/test. Aturan §2 poin 5 tetap berlaku: **jangan** promosikan ke default live
   sebelum lolos gate train+test.
3. **`hold 90` terbaik, bukan 240/480.** Hipotesis "perpanjang horizon" **tidak** terbukti di
   data ini — time-stop 90 bar M1 justru lebih baik (avg R +0,0235 vs −0,0279 pada 240 bar).
   Jangan naikkan tanpa bukti.
4. **Exit yang diskalakan ke R juga TIDAK menolong.** Diuji: `breakeven_trigger`, `trail_pct`,
   dan `min_early_profit` dikalikan kelipatan R (0,5R/1R/1,5R) dengan horizon 480 bar.
   Semua kombinasi **lebih buruk** (−0,04 s/d −0,92) daripada parameter exit default
   (0,40% / 0,35% / 0,45%) dengan hold 90. Jadi **jangan** ubah exit params berdasarkan teori
   "harus proporsional R" — datanya menolak.
5. **IDR maker menuntut order limit.** Angka `idr_maker` hanya berlaku bila entry benar-benar
   maker (limit pasif). Bila tereksekusi taker, friksinya kembali ke 0,8144%.
6. **Pair diuji ≠ semua pair.** Baru 40 pair USDT + 14 pair IDR Tokocrypto. Indodax **belum ada
   data sama sekali** di `data/`.

### 9.5b Hasil validasi out-of-sample & profil exit (2026-10-12) — 🔴 DIBATALKAN

> 🔴 **Angka di seksi ini TIDAK SAH. Jangan dipakai untuk keputusan apa pun.**
> Seksi ini dibuat sebelum `703c3b6`, ketika `summarize()` di `scalp_replay.py` **membuang** trade
> `UNRESOLVED` (yang tidak menyentuh SL/TP/early/trail dalam jendela *lookahead*) dari seluruh
> statistik. Pada 40 pair itu berarti **±81% sinyal dibuang**, dan verdictnya berbalik dari
> TIDAK LOLOS menjadi LOLOS. Angka yang berlaku ada di **§9.5c**.
> Bukti: `docs/LAPORAN-temuan-akuntansi-replay-2026-10-14.md`.
> Seksi ini disimpan **hanya sebagai riwayat keputusan** — jangan mengutipnya sebagai hasil.

**Split train/test (P4.1) — dulu dilaporkan "LOLOS, tetapi tipis"; setelah dihitung jujur: TIDAK LOLOS.**

| Segmen | Trade | avg R terbobot | Pair positif |
|---|---:|---:|---:|
| Train 70% | 1.835 | +0,0350 ❌ | 28/32 ❌ |
| Test 30% | 669 | **+0,0216** ❌ tidak sah | 23/30 ❌ tidak sah |

- ~~13 pair layak (test n ≥ 20); rasio positif **84,6%**~~ → dengan akuntansi jujur: **0/40 pair
  positif** (§9.5c).
- Gate ketat (avgR>0 ∧ PF≥1,2 ∧ n≥30): **21/40 in-sample → hanya 5/40 di test**
  (APT, NEAR, SU, W, ZRO). Angka jujur: edge sebagian besar pair **tidak stabil out-of-sample**.

**Profil exit = skew negatif (menang kecil, kalah besar).** Distribusi 2.550 trade:

| Exit | Trade | Porsi | WR | avg R |
|---|---:|---:|---:|---:|
| `TRAIL_SL` (SL sudah di BE/peak) | 2.263 | 88,7% | 100% | **+0,090** |
| `EARLY_PROFIT` | 163 | 6,4% | 100% | +0,144 |
| `SL` (stop keras) | 124 | 4,9% | 0% | **−1,156** |

Titik impas butuh WR ≈ 92,8%; engine mencapai 95,1% → **margin hanya ~2,3 pp**.
Drawdown per pair: **11,9R** (QNT), **10,5R** (MUBARAK), 4,3R (STRK ≈ 2.000 trade untuk pulih).

**Penyebab struktural.** Friksi 0,97% memaksa SL ≥ 5,8%, tetapi median MFE 90 menit hanya
1,5–3%. Penyebut R selalu besar sementara pergerakan yang bisa ditangkap kecil → avg R
plafon di ≈ 0,03. Ini konsisten dengan §9.5 poin 3 (horizon lebih panjang lebih buruk,
karena peluang menyentuh SL 5,8% naik).

**IDR Tokocrypto: belum konklusif.** 14 pair IDR hanya menghasilkan 88 trade di train dan
**6 di test** — n test terlalu kecil untuk menilai apa pun (split menulis "TIDAK LOLOS",
tapi itu artefak ukuran sampel, bukan bukti edge negatif). Pair IDR Tokocrypto terlalu
tidak likuid untuk filter engine saat ini.

**Arah perbaikan yang dibenarkan data — gerbang pair berbasis edge.** Kolom "capture wajib"
(= friksi ÷ median MFE 7j) menunjukkan BTC **193%**, XAUT 460%, PAXG 470%: pair seperti ini
**tidak mungkin** profit pada horizon 90 menit, apa pun strateginya, karena pergerakan
mediannya lebih kecil daripada biaya. Sebaliknya ZRO 30%, NEAR 34%, PUMP 36%, MUBARAK 36%
punya ruang.

Dua garis berbeda, jangan dicampur:

| Rasio (MFE ÷ friksi) | Arti | Contoh |
|---|---|---|
| **< 1,0** | Pergerakan median lebih kecil daripada biaya → **mustahil** profit | BTC 0,52×, XAUT 0,20×, USDC 0,01× |
| **1,0 – 2,0** | Secara teori mungkin, margin terlalu tipis untuk diandalkan → ditolak sebagai *buffer* | ADA 1,64×, AVAX 1,61× |
| **≥ 2,0** | Layak (masih perlu lolos uji out-of-sample) | ZRO 3,33×, NEAR 2,99×, APT 2,21× |

Dengan ambang 2,0×: **14/40 pair USDT Tokocrypto layak** (ZRO, NEAR, MUBARAK, PUMP, W, STRK,
ENA, QNT, PLUME, APT, SU, FET, WLD, FIL). Diimplementasikan sebagai `PairEdgeGate.kt` +
kartu UI `PairEdgeGateCard` + `tools/pair_gate.py` (regenerasi tabel → `data/config/pair_gate.json`).

Karena itu target "semua pair profit" harus dibaca sebagai **"semua pair yang lolos gerbang
edge"**, bukan seluruh daftar pair bursa. Menjanjikan lebih dari itu berarti mengabaikan
aritmetika biaya.

### 9.5c Temuan akuntansi replay — angka edge lama tidak sah (2026-10-14)

> 🔴 **Baca ini sebelum memakai angka edge apa pun.** Ini menggantikan §9.5b.

`summarize()` di `scalp_replay.py` hanya menghitung trade `WIN`/`LOSS`; trade yang tidak menyentuh
SL/TP/early/trail di dalam jendela *lookahead* ditandai `UNRESOLVED` lalu **dikeluarkan** dari
`avg_r` dan `win_rate`. Commit `703c3b6` menutup trade di akhir jendela. Sejak itu angkanya
berubah drastis.

**Kontrol — data, config, dan kode identik (40 pair USDT Tokocrypto, split 70/30):**

| Akuntansi | Test n | Test avg R | Pair positif | Verdict |
|---|---:|---:|---:|---|
| Lama — `UNRESOLVED` dikecualikan | 817 | **+0,0577** | 26/29 | LOLOS ❌ |
| Baru — semua trade ditutup di akhir jendela | 4.403 | **−0,1733** | **0/40** | **TIDAK LOLOS** |

Mematikan time-stop **dan** lantai profit S2 tidak mengembalikan hasil positif (3 varian, semuanya
≈ −0,17). Jadi −0,17 bukan efek samping setelan exit.

**Kenapa persis ≈ −0,17R:** lantai friksi memaksa SL ≥ `6 × 0,9688%` = 5,81%. Trade tanpa edge
hanya menanggung biaya lalu ditutup: `−0,9688% / 5,81% = −0,167 R`. Cocok dengan yang terukur.

**Walk-forward (P4.1) setelah akuntansi diperbaiki:**

| Dataset | Jendela | Lolos | Test avg R | Pair positif |
|---|---:|---:|---:|---:|
| 40 pair `tokocrypto_live_usdt` (4 jendela) | 4 | **0/4** | −0,1614 | 0/156 |
| 8 pair v4 `tokocrypto_v4` (3 jendela, gate-rejected) | 3 | **0/3** | −0,1790 | — |

> ⚠️ Kedelapan pair v4 itu **INELIGIBLE** menurut `pair_gate.json` (0,242×–1,638×). Verdict
> walk-forward **hanya berlaku untuk dataset yang diuji** — jangan pakai hasil itu untuk
> menyimpulkan strategi gagal.

**Kesimpulan.** Bukan lagi "tandanya benar, besarnya belum cukup" — melainkan **tidak ada edge
kotor yang tersisa setelah friksi**. Pertanyaan baru yang lebih mendesak: **adakah varian entry
yang menghasilkan avg R kotor > +0,167R pada horizon 120 bar M1?** Tanpa itu, menyetel exit
(P4.2/P4.4) tidak akan mengubah apa pun.

**Aturan yang lahir dari temuan ini:** setiap angka avg R **wajib** menyebut berapa sinyal yang
tidak tuntas. Angka avg R tanpa keterangan itu tidak bisa ditafsirkan.

### 9.6 Yang belum selesai (roadmap, urut prioritas)

| # | Pekerjaan | Status |
|---|---|---|
| P4.1 | Validasi train/test split untuk lantai friksi + target 2.0 | ❌ **TIDAK LOLOS** setelah akuntansi diperbaiki (avg R test **−0,1733**, **0/40 pair positif**; walk-forward **0/4** jendela). Angka lama "+0,0216 / LOLOS" **tidak sah** — lihat §9.5b (dibatalkan) dan §9.5c |
| P4.2 | Preferensi maker otomatis untuk pair IDR (order limit pasif) | ⏳ belum — **prioritas berikutnya** (lihat §9.5c: menyetel exit tidak berguna sebelum ada edge entry) |
| P4.3 | Gerbang pair berbasis edge (MFE horizon vs friksi) di UI | ✅ **Selesai** — `PairEdgeGate.kt` + `PairEdgeGateCard.kt` + `tools/pair_gate.py` → `data/config/pair_gate.json` (14/40 pair USDT layak) |
| P4.4 | Rehidrasi state machine paper saat app restart (celah P2) | ✅ **Selesai 2026-10-15** (`56eb5cd`). Progres FSM dipersistenkan sebagai satu JSON di key `_paper_fsm` (`barsHeld`, `peak`, `activeStopLoss`, `isBeArmed`/`isTrailArmed`/`isPartialDone`, `partialR`, `atrPct`). Bukti: `docs/LAPORAN-p44-persistensi-fsm-2026-10-15.md`, §18 |
| P5.1 | Data historis Indodax (belum ada di `data/`) | ⏳ belum |
| P5.2 | Jalur TAPI v2 Indodax — **klien sudah ada** (`IndodaxTradeApiV2.kt`, 729 baris, HMAC-SHA256, limit/market/cancel/balance) | ⚠️ terpasang, belum diuji end-to-end |
| P5.3 | Harness replay untuk pair Indodax (IDR, fee 0,79% RT) | ⏳ belum |
| P6.1 | **Auto-execute uang riil dari sinyal scalp** — tetap DILARANG sampai gate `SCALP_EXIT.md` lolos | ⛔ ditahan |

---

## 10. Alat & data baru (P4)

| File | Peran |
|------|--------|
| `tools/fetch_universe.py` | Ambil data M1/M15/H1 riil Tokocrypto untuk **semua** pair (USDT & IDR), bukan hanya 8 major |
| `tools/universe_replay.py` | Jalankan engine replay asli di seluruh universe, hasilkan tabel per pair (sinyal, WR, avg R, PF, MFE) |
| `tools/param_grid.py` | Grid parameter paralel (risk × target RR × horizon × model fee) untuk mencari konfigurasi terbaik |
| `tools/split_validate.py` | **Gate out-of-sample**: bagi data per waktu (train 70% / test 30%), jalankan engine di kedua potongan, nilai apakah edge bertahan (P4.1) |
| `tools/pair_gate.py` | **Gerbang edge pair (P4.3)**: hitung `median MFE 7j ÷ friksi` per pair, tandai layak/tidak, tulis `data/config/pair_gate.json` + potongan Kotlin untuk `PairEdgeGate.kt` |
| `data/tokocrypto_live_usdt/` | 40 pair USDT Tokocrypto, 30 hari M1+M15+H1 (data riil) |
| `data/tokocrypto_live_idr/` | 14 pair IDR Tokocrypto, 30 hari M1+M15+H1 |
| `data/hasil/u1_toko_usdt.{json,md}` | Baseline universe sebelum perbaikan |
| `data/hasil/u2_param_grid.json` | Bukti grid 81 kombinasi |
| `data/hasil/u3_toko_usdt_after.{json,md}` | Universe **sesudah** perbaikan lantai friksi: 28/32 pair avg R > 0 |
| `data/hasil/u4_split_toko_usdt.{json,md}` | Gate **out-of-sample** USDT (P4.1) — 🔴 angka "+0,0216 / LOLOS" **tidak sah** (akuntansi lama membuang ±81% sinyal). Hasil jujur: **−0,1733, 0/40 pair positif** — lihat §9.5c |
| `data/hasil/u7_walk_forward_v4.{json,md}` | Walk-forward 8 pair v4 (3 jendela) — **0/3 lolos**, test avg R −0,1790. ⚠️ Kedelapan pair INELIGIBLE menurut `pair_gate.json` |
| `data/hasil/u8_walk_forward_40pair.{json,md}` | Walk-forward 40 pair `tokocrypto_live_usdt` (4 jendela) — **0/4 lolos**, test avg R −0,1614, pair positif 0/156 |
| `data/hasil/u5_toko_idr.{json,md}` | Universe IDR Tokocrypto (96 trade — terlalu tipis untuk disimpulkan) |
| `data/hasil/u6_split_toko_idr.{json,md}` | Gate out-of-sample IDR — n test = 6, belum konklusif |
| `data/config/pair_gate.json` | Tabel kelayakan pair (P4.3): 14/40 pair USDT layak |

Reproduksi:
```bash
python3 tools/fetch_universe.py --quote USDT --top 40 --days 30 --out data/tokocrypto_live_usdt
python3 tools/universe_replay.py --data data/tokocrypto_live_usdt \
  --config data/config/scalp_config_exit_full.json --out-prefix data/hasil/u1_toko_usdt
python3 tools/param_grid.py --data data/tokocrypto_live_usdt \
  --pairs ADAUSDT,APTUSDT,AVAXUSDT,ENAUSDT,NEARUSDT,WLDUSDT --max-bars 20000 --jobs 24 \
  --out data/hasil/u2_param_grid.json
# sesudah perbaikan lantai friksi
python3 tools/universe_replay.py --data data/tokocrypto_live_usdt \
  --config data/config/scalp_config_exit_full.json --out-prefix data/hasil/u3_toko_usdt_after
# gate out-of-sample (P4.1)
python3 tools/split_validate.py --data data/tokocrypto_live_usdt --jobs 24 \
  --config data/config/scalp_config_exit_full.json --out data/hasil/u4_split_toko_usdt
# universe + split untuk IDR Tokocrypto
python3 tools/universe_replay.py --data data/tokocrypto_live_idr \
  --config data/config/scalp_config_exit_full_idr.json --out-prefix data/hasil/u5_toko_idr
python3 tools/split_validate.py --data data/tokocrypto_live_idr --jobs 14 \
  --config data/config/scalp_config_exit_full_idr.json --out data/hasil/u6_split_toko_idr
# gerbang edge pair (P4.3)
python3 tools/pair_gate.py --usdt data/hasil/u3_toko_usdt_after.json \
  --idr data/hasil/u5_toko_idr.json --out data/config/pair_gate.json
```

## 11. P4.5 — UI scalping baru di halaman detail (2026-10-12)

**Laporan pengguna:** *"radar menyatakan terdeteksi tapi setup menyatakan none, jadi tidak
sinkron"*. Ini bug nyata, bukan sekadar tampilan.

### 11.1 Akar masalah (terverifikasi)

```
ScalpingMtfEvaluator.kt:246   early = !ready && !isDangerous && step2Ok
                              ^^^ step2Ok = step1Ok && isOrderBookValid → TIDAK memeriksa setup
ScalpingMtfEvaluator.kt:294   early -> ScalpingStage.EARLY_ENTRY
SignalLifecycleManager.kt:112 EARLY_ENTRY, WAIT_PULLBACK -> LifecycleState.DETECTED
WaitingEntryRadarCard.kt:317  DETECTED -> "TERDETEKSI"
```

`ScalpingMtfEvaluator.kt:403` mengisi `scalpingSetup = setup.name`, dan `setup` tetap
`ScalpSetupType.NONE` bila tidak ada pola valid. Jadi radar berkata "terdeteksi" sementara
setup berkata "NONE".

Satu kelas bug ini muncul di **tiga** permukaan:

| # | Tempat | Klaim salah saat `setup == NONE` |
|---|---|---|
| 1 | `WaitingEntryRadarCard.kt:313-321` | `DETECTED` → "TERDETEKSI" (biru) |
| 2 | `MarketConditionCard.kt:42,49,73` | `EARLY_ENTRY` → "EARLY BUY" hijau + "Breakout 1M terdeteksi lebih awal." |
| 3 | Log sinyal / `MonitorCard` | lewat `lifecycleState` yang sama |

Catatan semantik yang penting: `ScalpingMtfSnapshot.setupOk` **bukan** "setup terdeteksi" —
ia diisi `step2Ok` (tekanan orderbook). Yang benar-benar menuntut pola adalah `triggerOk`
(= `step3Ok`). Di mode scalping, `WaitingEntryRadarCard.kt:328,331` melewati checklist dan
stepper 4 checkpoint, sehingga **tidak ada satu pun** tempat yang menampilkan `statusTitle`,
`waitingFor`, atau `step1..step4`.

### 11.2 Perbaikan

Lapisan UI saja (engine tidak disentuh, jadi baseline replay & statistik log sinyal tidak
berubah), memakai satu model bersama:

- **`engine/scalping/ScalpReadiness.kt`** — model murni + invarian:
  `scalpingSetup` kosong/`"NONE"` ⟹ status **tidak boleh** READY/CONFIRMING, meski
  `lifecycleState == DETECTED`. Juga menyediakan 5 checkpoint dengan label jujur (baris
  "Setup Scalping" dipisah dari "Tekanan Orderbook").
- **`ui/components/detail/scalp/ScalpDeskCard.kt`** — meja analisa scalping: status terpadu,
  5 checkpoint, penghalang, rencana risiko (entry zone/SL/TP1/TP2/netRR/lantai friksi),
  chip kelayakan `PairEdgeGate`, baseline backtest, skor & regime.
- **`ui/components/detail/scalp/PaperTradeDeskCard.kt`** — panel auto buy/sell simulasi:
  status posisi hidup, kontrol (auto-buy/auto-exit/stage minimum), peringatan gerbang uang riil.
  Rincian FSM direuse dari `PaperExitFsmCard` — tidak ada presentasi yang terduplikasi.
- **`MarketConditionCard.kt`** (cabang scalping) — status diambil dari `ScalpReadiness`.
- **`WaitingEntryRadarCard.kt`** — parameter baru `hideAnalysis`; mode scalping melewati blok
  analisa lama (header radar + target level + backtest) agar entry/SL/TP tidak tampil dua kali.
  Zona eksekusi manual tetap tampil.
- **`viewmodel/PaperAutoTradeCoordinator.kt`** — `paperSnapshotFor(exchange, symbol, price)`.
  Wajib: `TradingViewModel.paperExitSnapshot` adalah StateFlow tunggal dari tick pair mana pun
  (`TradingViewModel.kt:157`), jadi tidak boleh dipakai langsung di halaman detail.

### 11.3 Uji

`app/src/test/java/agu/analys/engine/scalping/ScalpReadinessTest.kt` — 14 test, termasuk
matriks 6 `lifecycleState` × 6 `scalpingStage` dengan `setup = NONE` yang menegaskan hasil
bukan READY/CONFIRMING, dan kasus persis yang dilaporkan (`DETECTED` + `NONE` → MEMANTAU).

### 11.4 Perbaikan akar di engine — **SELESAI** (2026-10-13)

Semula ditunda karena mengubah statistik log sinyal dan pemicu alert (`isNewDetected`).
Perbaikan akar sudah dikerjakan sebagai perubahan tersendiri dengan regresi penuh —
lihat **§13**.

---

## 12. P2-fix — Rehidrasi state machine exit paper setelah restart (2026-10-13)

**Temuan audit:** `PaperAutoTradeRuntime.machines` adalah `ConcurrentHashMap` **in-memory**,
sedangkan `auto_sell_enabled = false` **persisten** di `SharedPreferences`. Setelah app
kill/restart, posisi paper **menggantung**: engine paper kosong, jalur auto-sell lama mati.

### 12.1 Akar masalah (terverifikasi)

| Komponen | Sifat | Akibat setelah restart |
|---|---|---|
| `PaperAutoTradeRuntime.machines` | memori | kosong → engine paper tidak jalan |
| `auto_sell_enabled` | persisten, `false` | jalur auto-sell lama juga tidak jalan |
| posisi di `SpotPositionStore` | persisten, `HOLDING` | posisi menggantung |

### 12.2 Cacat tambahan yang ditemukan saat verifikasi

Rekomendasi awal ("rehidrasi dari `positionStore`") **tidak cukup dan berbahaya**:

`SpotPositionStore.setHolding` (`SpotPositionStore.kt:331,362`) menulis SL **sintetis**
`entry * 0.99` (≈1%), karena `markBought` tidak menerima SL dan `setAutoSellParams` versi lama
tidak menulis `_stop_loss`. Padahal SL asli engine minimal sebesar **lantai friksi** (≥ 5,8%
untuk USDT Tokocrypto, `min_friction_multiple = 6.0`).

Rehidrasi naif akan membangun mesin dengan SL 1% alih-alih 5,8% → **memalsukan stop-out** dan
mengotori jurnal paper. Lebih buruk daripada tidak melakukan apa-apa.

### 12.3 Perbaikan

1. **`SpotPositionStore.setAutoSellParams(..., stopLossPrice: Double = 0.0)`** — menulis SL asli
   sinyal, menimpa nilai sintetis. Default `0.0` mempertahankan perilaku lama (backward compat).
   Sekaligus memperbaiki jalur auto-sell lama saat kepemilikan dikembalikan.
2. **Penanda persisten `_paper_engine`** — `setPaperEngineOwned` / `isPaperEngineOwned` /
   `getPaperEngineSymbols`. Satu-satunya cara mengenali posisi milik engine paper setelah restart.
   Dibersihkan di `markSold`, `finalizePaperExit`, `clearPaperExitState`, dan saat posisi hilang.
   `getAllActiveTrailingSymbols` **tidak bisa** dipakai untuk ini: ia mensyaratkan
   `trailing_enabled = true`, sedangkan posisi paper justru mematikannya.
3. **`engine/scalping/PaperRehydrate.kt`** — model murni berisi aturan keputusan:
   `Restore` / `HandBackToLegacy` / `Skip`, dengan prioritas **auto-exit mati → hand back**
   lebih dulu, lalu validasi SL (`0 < SL < entry`).
4. **`TradingViewModel.rehydratePaperAutoExit()`** — dijalankan sekali dari
   `initSubscriptionsAndPolling()`. `Restore` → `PaperAutoTradeRuntime.put(..., openedAt = pos.openedAt)`
   agar waktu masuk di jurnal tetap benar. `HandBackToLegacy` → `auto_sell_enabled` dinyalakan
   kembali + penanda dilepas, supaya posisi tidak menggantung.
5. **`PaperAutoTradeRuntime.put(..., openedAt: Long = 0L)`** — `openedAt` asli bisa dikembalikan.

### 12.4 Batasan yang diterima

> ⚠️ **Sebagian batasan di bawah sudah DIPERBAIKI oleh P4.4 (2026-10-15) — lihat §18.**
> Butir pertama (progres FSM tidak dipulihkan) **tidak berlaku lagi** sejak `56eb5cd`.

- ~~`barsHeld`, `isBeArmed`, `isPartialDone`, `peak` **tidak** dipulihkan (tidak dipersistenkan).~~
  **Sudah dipulihkan (P4.4):** keempatnya kini disimpan sebagai satu JSON di key `_paper_fsm`
  bersama `isTrailArmed`, `partialR`, `activeStopLoss`, dan `atrPct`. `barsHeld` dihitung dari
  jam dinding (`mergedBarsHeld`), jadi posisi yang jendela tahannya lewat selama app mati
  **langsung** kena time-stop saat dibuka.
- Posisi yang dibeli **sebelum** perbaikan ini tidak punya penanda, jadi sengaja **tidak**
  disentuh: SL-nya tersimpan salah (`entry * 0.99`), jadi memulihkannya akan memalsukan stop-out.
  Posisi warisan seperti itu perlu ditangani manual.
- Rehidrasi memakai `scalpingRuntimeConfig` **saat ini**, bukan saat posisi dibuka. Bila pengguna
  mengubah parameter exit di antara dua sesi, mesin yang dipulihkan memakai parameter baru.
  Ini disengaja (perilaku sama dengan restart tanpa rehidrasi) tetapi perlu dicatat.
- State tersimpan yang tidak lolos invarian (`PaperExitStateMachine.restoreRejection`) **dibuang**,
  bukan dipakai setengah: mesin tetap dibangun dengan `entry`/`SL` asli, hanya progresnya direset,
  dan alasannya dicatat di log. (P4.4)

### 12.5 Uji

- `app/src/test/java/agu/analys/engine/scalping/PaperRehydrateTest.kt` — 16 test: jalur pulih,
  dan yang terpenting **jalur tolak** (SL nol / SL ≥ entry / SL NaN / entry nol / NaN) plus
  urutan prioritas auto-exit-mati.
- `app/src/test/java/agu/analys/trading/SpotPositionStorePaperMarkerTest.kt` — 8 test
  (Robolectric): SL asli menimpa SL sintetis, `stopLossPrice = 0` mempertahankan perilaku lama,
  penanda bertahan, enumerasi hanya posisi bertanda + `HOLDING`, isolasi antar-bursa.


---

## 13. P1-fix — Akar `EARLY_ENTRY` di engine (2026-10-13)

**Temuan audit:** perbaikan §11 hanya melindungi lapisan UI. Engine masih bisa memancarkan
rantai `step2Ok (orderbook) → EARLY_ENTRY → DETECTED` tanpa pola valid, sehingga statistik log
sinyal dan pemicu alert tetap "kotor". Sekarang akarnya ditutup di dua lapis.

### 13.1 Akar masalah (terverifikasi)

```
ScalpingMtfEvaluator.kt:246   early = !ready && !isDangerous && step2Ok
                              ^^^ step2Ok = step1Ok && isOrderBookValid → TIDAK memeriksa setup
ScalpingMtfEvaluator.kt:294   early -> ScalpingStage.EARLY_ENTRY
SignalLifecycleManager.kt:112 EARLY_ENTRY, WAIT_PULLBACK -> LifecycleState.DETECTED
```

`step2Ok` hanya menuntut **tekanan orderbook** (`buyPressure > 1.0`). Jadi saat orderbook sudah
mendukung tetapi `ScalpSetupDetector` belum menemukan pola apa pun (`scalpingSetup == "NONE"`),
engine tetap melabeli sinyal sebagai `EARLY_ENTRY`, dan state machine menaikkannya ke `DETECTED`.

Dampak yang benar-benar terjadi di produksi (bukan sekadar kosmetik):

| Jalur | Dampak |
|---|---|
| `AppLogManager.aiEngine("SignalEngine", "… IDLE ➔ DETECTED \| Sinyal: ⚪ HOLD …")` | log transisi mencatat "DETECTED" untuk sinyal tanpa pola → statistik log sinyal menyesatkan |
| `TradingViewModelSync.kt:468` | `isNewDetected` dianggap transisi layak → memanggil `tryPaperAutoBuy` |
| `WaitingEntryRadarCard.kt:328` | `DETECTED` → "👁 TERDETEKSI" |

Catatan penting yang membatasi blast radius: `tryPaperAutoBuy` **tetap aman** karena mensyaratkan
`signal.action == BUY` (`PaperAutoTradeCoordinator.kt:186`), sedangkan `EARLY_ENTRY` selalu
dikirim dengan `action = HOLD` (`ready == false → direction = WAIT → HOLD`). Begitu juga
`AlertNotificationHelper` — hanya dipanggil saat `hasTriggeringTransition`, yang juga
mensyaratkan `action == BUY`. Jadi yang benar-benar rusak adalah **kejujuran status & log**,
bukan eksekusi. Ini yang membuat perbaikan ini aman dilakukan.

### 13.2 Perbaikan — dua lapis

**Lapis 1 (akar) — `ScalpingMtfEvaluator.kt`.** `EARLY_ENTRY` sekarang mensyaratkan pola:

```kotlin
val early = !ready && !isDangerous && step2Ok && setup != ScalpSetupType.NONE
```

Efeknya stage menjadi jujur: tanpa pola, stage turun ke `WATCH`, bukan `EARLY_ENTRY`. Label ikut
disinkronkan agar tidak ada klaim palsu tersisa:

| Tempat | Sebelum | Sesudah |
|---|---|---|
| `reasons[0]` saat early | "EARLY: kondisi awal terbentuk, tunggu setup & konfirmasi penuh." | "EARLY: setup X terbentuk, menunggu trigger & konfirmasi penuh." |
| `reasons[0]` saat `step2Ok` tanpa pola | "WAIT: belum ada setup valid. Menunggu momentum & orderbook." | "WAIT: tekanan orderbook sudah mendukung, tetapi belum ada setup scalping valid." |
| `statusTitle` | `step2Ok` → jatuh ke "BIAS OK (1/4)" (menghitung salah) | cabang baru "ORDERBOOK OK (2/4) · SETUP BELUM ADA" |
| `waitingFor` | `step2Ok` → "Setup scalping valid & VWAP" (berlaku untuk dua keadaan berbeda) | dipisah: `early` → "Trigger VWAP/VSA & gate Net R:R"; `step2Ok` → "Setup scalping valid (sweep/retest/breakout/pullback)" |

**Lapis 2 (jaring pengaman) — `SignalLifecycleManager.kt`.** Di cabang `SCALPING`, stage yang
tidak didukung pola dinormalisasi sebelum dipakai mesin transisi:

```kotlin
val stage = when {
    ScalpReadiness.hasSetup(rawSignal) -> rawSignal.scalpingStage
    rawSignal.scalpingStage == ScalpingStage.HOLD -> ScalpingStage.HOLD
    else -> ScalpingStage.WATCH
}
```

Alasan memilih normalisasi (satu titik) daripada menambal tiap cabang: invarian jadi berlaku untuk
**semua** jalur promosi, termasuk cabang yang ditambahkan orang lain di masa depan. Penyesuaian
`HOLD` dipertahankan eksplisit supaya **jalur invalidasi tidak ikut berubah** — kondisi berbahaya
tetap membatalkan sinyal yang sudah `DETECTED`/`READY`, walaupun polanya tidak terbaca.

Perilaku untuk sinyal yang sah **tidak berubah**: `WATCH` dan `HOLD` sudah punya transisi yang
benar di mesin ini (`IDLE` tetap `IDLE`, `DETECTED` turun ke `IDLE`, `READY` dibatalkan).

Predikat "punya pola" memakai `ScalpReadiness.hasSetup()` — satu sumber kebenaran yang sama dengan
lapisan UI, jadi kedua lapis tidak bisa berbeda pendapat.

### 13.3 Uji

- `ScalpingMtfEvaluatorTest.testInvariant_EarlyEntryRequiresSetup` — pasar datar (tanpa pola) dengan
  orderbook dianggap memadai: menegaskan `setup == "NONE"`, **`setupOk == true`** (prasyarat agar
  test tidak hampa — inilah kondisi yang dulu memicu bug), lalu `scalpingStage == WATCH` dan
  `statusTitle == "ORDERBOOK OK (2/4) · SETUP BELUM ADA"`. Data dibuat deterministik (bukan
  `Math.random()`), jadi hasilnya stabil.
- `SignalLifecycleManagerTest.testScalping_NoSetupNeverPromoted` — `EARLY_ENTRY`, `WAIT_PULLBACK`,
  `ENTRY`, dan `STRONG_ENTRY` tanpa setup semuanya berhenti di `IDLE`; begitu pola valid muncul,
  `DETECTED` kembali sah.
- `SignalLifecycleManagerTest.testScalping_DangerousHoldStillInvalidatesEvenWithoutSetup` —
  memastikan jalur invalidasi tidak ikut ternormalisasi.
- `SignalLifecycleManagerTest.testScalping_BackwardsCompatibility` — diperbarui: sinyal scalping
  kini wajib menyertakan `scalpingSetup` valid.

### 13.4 Yang tetap belum dikerjakan

- **Kesiapan uang riil tetap BELUM.** Ini memperbaiki kejujuran status, bukan menambah edge.
  Profil exit masih skew negatif (§9.5b), gate ketat out-of-sample hanya 5 pair.
- Angka `confidence` untuk keadaan pra-pola tetap 49 (setara "2/4 checkpoint"). Nilainya konsisten
  dengan hitungan checkpoint, tetapi tidak berarti "hampir siap entry" — UI scalping tidak
  memakainya untuk menyimpulkan status.
- `P4.1` walk-forward antar-bulan, `P4.2` preferensi maker IDR, `P5` data Indodax + replay
  TAPI v2 — belum tersentuh.

---

## 14. TEAM 1 + TEAM 2 — Kesiapan multi-horizon (scalp/intraday/swing), 2026-10-13

> ## ⛔ DIHAPUS — 2026-10-13. JANGAN DIBANGUN ULANG.
>
> **Status kode:** fitur ini **sudah tidak ada di aplikasi**. `engine/readiness/*`,
> `ui/components/readiness/*`, `ReadinessBoardScreen.kt`, `TradingViewModelReadiness.kt`,
> `AppScreen.READINESS`, kartu `SIAP TRADING` (dashboard), kartu `KESIAPAN HORIZON` (detail koin),
> dan 4 berkas tesnya semuanya dihapus.
>
> **Alasan pembatalan:** fitur ini menghasilkan **informasi yang sama dengan kartu engine** di
> permukaan berbeda. Halaman detail koin menampilkan `KESIAPAN HORIZON 3/5 syarat lolos` sementara
> kartu `SCALP DESK` tepat di bawahnya menampilkan `1/5 lolos` — dua daftar syarat berbeda untuk
> satu pertanyaan, keduanya menulis `N/5`, tanpa cara bagi pembaca untuk tahu mana yang harus
> dipercaya. Owner menilai datanya dobel dan memutuskan membatalkan.
>
> **Yang tidak boleh diulang:** membuat evaluator kedua di luar engine yang sedang aktif, atau
> menampilkan `N/5` dari daftar syarat berbeda untuk koin yang sama di dua kartu berbeda.
> Lihat `AGENTS.md` → kotak "JANGAN BANGUN ULANG".
>
> **Yang tetap berlaku dari seksi ini:** hanya pelajaran bahwa keputusan engine **tidak boleh**
> dihitung ulang di tempat lain. Kartu `SCALP DESK` + radar `WaitingEntryRadarCard` tetap menjadi
> satu-satunya permukaan status.
>
> Seksi di bawah ini disimpan sebagai **riwayat keputusan**, bukan sebagai rencana kerja.

**Permintaan:** "buat aplikasi ini peka terhadap koin yang scalping ready, intraday ready, dan
swing ready; khusus scalping ready engine mengimplementasikan exit long (buy, hold, exit = profit)",
lalu diwujudkan sebagai UI/UX yang mudah dibaca dan responsif.

### 14.1 Kenapa satu skor koin tidak cukup

Pertanyaan "koin ini bagus?" tidak punya jawaban tunggal, dan jawabannya **ditentukan biaya**:

| Koin | ATR M15 | Untuk SCALP | Untuk SWING |
|---|---|---|---|
| Tenang | 0,3% | friksi 0,97% menelan seluruh gerakan | stop lebar membuat friksi tidak berarti → layak |
| Liar | 4,0% | noise mengalahkan sinyal (diblokir) | perlu konfirmasi struktur D1 |

Karena itu kesiapan dinilai **terpisah per horizon**, masing-masing dengan timeframe, lantai
friksi, dan target net R:R sendiri. Satu koin boleh muncul tiga kali dengan status berbeda — itu
informasi, bukan kontradiksi.

### 14.2 Berkas baru (TEAM 1 — engine)

| Berkas | Isi |
|---|---|
| `engine/readiness/TradeHorizon.kt` | enum SCALP/INTRADAY/SWING: timeframe bias & trigger, jendela tahan, target net R:R, lantai friksi, **penanda `validatedOutOfSample`** |
| `engine/readiness/HorizonReadiness.kt` | model murni: `ReadinessState`, `ReadinessCheck`, `TradePlan`, `HorizonReadiness`, `ReadinessBoard` |
| `engine/readiness/HorizonReadinessEngine.kt` | penilaian satu (koin × horizon) dari candle; 5 syarat per horizon + penghalang keras |
| `engine/readiness/ReadinessScanner.kt` | `CandleSource` + pemindaian N koin × 3 horizon, dedup timeframe |

Ambang horizon:

| Horizon | Timeframes | Lantai friksi | Target net R:R | Jendela | Divalidasi? |
|---|---|---|---|---|---|
| SCALP | H1 / M15 / M1 | 6× (sama dengan `ScalpingConfig`) | 2,0 (sama) | 28 bar M15 | **ya** |
| INTRADAY | H4 / H1 / M15 | 4× | 1,5 | 12 bar H1 | belum |
| SWING | D1 / H4 / H1 | 3× | 2,0 | 14 bar H4 | belum |

Lantai friksi INTRADAY/SWING lebih longgar **dengan alasan**: stop mereka berbasis H1/H4 yang
secara alami jauh lebih lebar dari stop scalp M1/M15, sehingga lantai 6× tidak akan pernah
mengikat. Menuliskan 6× di sana hanya akan menciptakan penjaga semu.

### 14.3 Gate ekonomi — kesalahan desain yang ditemukan dan dibuang

Versi pertama membandingkan risiko alami (`0,8 × ATR`) terhadap lantai friksi. Untuk USDT
Tokocrypto lantai = 5,81%, jadi syaratnya menjadi **ATR ≥ 7,26%** — di atas ambang volatilitas
ekstrem (3,5%). Papan akan **selalu kosong**, dan saringan yang selalu menolak sama tidak
bergunanya dengan saringan yang selalu menerima.

Akar kesalahannya: lantai friksi adalah **cara beroperasi**, bukan syarat kelayakan. §9 justru
menemukan bahwa dengan lantai 6× trade menjadi berdurasi 1–8 jam dan **28 dari 32 pair tetap
positif**.

Versi final memakai kriteria yang **sama dengan `PairEdgeGate`** (yang sudah diuji data):
pergerakan harus ≥ **2×** biaya round-trip, dengan estimasi `ATR × √bar`. Skala akar dipakai
karena `ATR × bar` mengasumsikan tren sempurna — memakai itu akan melebih-lebihkan kemampuan koin
dan membuat gate lolos terlalu mudah.

Contoh: ATR M15 1,0% → 1,0 × √28 = 5,29% ÷ 0,9688% = **5,46×** → lolos. ATR M15 0,3% →
1,59% ÷ 0,9688% = **1,64×** → gagal, dan memang harus gagal.

### 14.4 Exit long untuk scalping (buy → hold → exit = profit)

`TradePlan` membawa entry, SL, TP1, TP2, `riskPct`, `rewardPct`, `netRr` (dari `FeeCalculator`),
`frictionFloorPct`, `tp2R`, dan `exitRule`.

`exitRule` untuk SCALP **dibangun dari `ScalpExitParams` yang sedang aktif**
(`ScalpingRuntimeConfig.current.exit`), sehingga teks di UI tidak pernah berbeda dari aturan yang
benar-benar dijalankan `PaperExitResolver`. Contoh keluaran untuk mode FULL:

> Beli di area entry, tahan, keluar saat profit net mencapai target (TP2) atau kena SL. Mode Full:
> TP1 50% lalu SL ke breakeven net (0,40%), trailing 0,35% dari puncak, early-profit ≥ 0,45% net.
> Time-stop 90 bar M1.

INTRADAY: keluar sebelum sesi close malam (19:30 WIB), tidak menahan melewati tengah malam.
SWING: keluar di TP2/resistance atau saat close di bawah EMA50 D1.

### 14.5 Berkas baru (TEAM 2 — UI)

| Berkas | Isi |
|---|---|
| `viewmodel/TradingViewModelReadiness.kt` | papan dari cache (tanpa jaringan), `refreshReadinessBoard()`, `requestReadinessData()`, polling 60 detik |
| `ui/components/readiness/ReadinessUi.kt` | pemetaan status→warna, chip, bilah skor, baris syarat, baris rencana |
| `ui/components/readiness/ReadinessBoardCard.kt` | ringkasan Dashboard: hitungan siap/memanas per horizon + kandidat teratas |
| `ui/components/readiness/HorizonReadinessCard.kt` | `SymbolReadinessCard` (3 horizon) + `HorizonReadinessRow` yang bisa dibuka |
| `ui/screens/ReadinessBoardScreen.kt` | papan penuh: pemilih horizon, saringan, daftar, responsif 1–2 kolom |

Berkas yang diubah: `AppScreen` (+`READINESS`), `MainActivity` (rute), `TradingViewModelNavigation`
(`openReadiness`, `openReadinessSymbol`), `TradingViewModel` (4 StateFlow), `TradingViewModelSync`
(hook polling), `DashboardScreen` (kartu), `DetailChartScreen` (kartu + refresh saat pair berubah).

Keputusan UI:

1. **Responsif lewat lebar nyata** (`BoxWithConstraints` → 1 atau 2 kolom), bukan orientasi.
   Sebelumnya aplikasi hanya punya satu jalan untuk layar lebar: memaksa landscape.
2. **Alasan selalu terlihat.** Tiap baris menampilkan syarat mana yang gagal.
3. **`BELUM DIVALIDASI`** tampil pada INTRADAY/SWING, supaya pengguna tidak menyimpulkan ketiganya
   setara.
4. **Status SIAP disertai peringatan** bahwa itu hasil saringan, bukan ajakan beli, dan eksekusi
   uang riil masih diblokir.

### 14.6 Uji

`HorizonReadinessEngineTest` (16) + `ReadinessScannerTest` (10) = **+26 test**. Sorotan:

- fixture deterministik (tanpa `Math.random()`), bar terakhir dipaksa naik supaya uji momentum
  tidak gagal karena kebetulan gelombang sinus;
- gate ekonomi ditolak untuk koin tenang **dan** diterima untuk koin wajar;
- skor berbobot ganda pada gate ekonomi (3/6 = 50, bukan 3/5 = 60);
- `expectedTravelPct` memakai skala akar (dibuktikan `< ATR × n`);
- pemindai membaca tiap timeframe **sekali** per simbol (10 pembacaan untuk 2 simbol, bukan 18);
- hanya SCALP yang bertanda `validatedOutOfSample`.

### 14.7 Batasan yang harus diingat

- **INTRADAY dan SWING belum divalidasi keluar-sampel.** Ambangnya masuk akal tetapi belum
  dibuktikan punya edge. UI menandainya; jangan dipromosikan ke uang riil tanpa uji data dulu.
- **Papan membaca cache, bukan mengambil data.** `MtfCacheManager` hanya mem-prefetch
  M1/M15/H1/H4 — **D1 tidak pernah**, sehingga SWING hampir selalu `DATA KURANG` sampai pengguna
  menekan "Ambil data candle" (dibatasi 8 simbol sekali tekan).
- **Tanpa orderbook.** Keputusan entry scalping yang sebenarnya tetap milik `ScalpingMtfEvaluator`;
  mesin ini menyaring koin, bukan menggantikan evaluator.
- **Belum ada eksekusi otomatis dari papan.** Papan hanya menilai; auto-buy paper tetap lewat
  `PaperAutoTradeCoordinator` dan tetap mensyaratkan `action == BUY`.
- Kesiapan uang riil tetap **BELUM** (§9.5b, §13.4).

---

## 15. SSOT — horizon kesiapan vs engine yang aktif (2026-10-13)

> ## ⛔ DIHAPUS — 2026-10-13. JANGAN DIBANGUN ULANG.
>
> **Status kode:** tidak ada di aplikasi. Perbaikan SSOT di seksi ini beserta seluruh fitur horizon
> yang diperbaikinya sudah dihapus (revert 4 commit kode horizon).
>
> **Alasan:** seksi ini memperbaiki kontradiksi "3/5 vs 1/5" dengan menyatukan sumber — tetapi
> akar persoalannya lebih dalam: **permukaannya sendiri memang berlebih.** Selama masih ada dua
> kartu yang menampilkan status kesiapan, akan selalu ada cara bagi keduanya untuk berbeda. Owner
> memutuskan menghapus permukaan keduanya, bukan menyinkronkannya.
>
> **Pelajaran yang tetap berlaku:** lihat butir 3 di bawah — jangan pernah menampilkan `N/5` dari
> daftar syarat berbeda untuk koin yang sama di dua kartu berbeda. Pelajaran itu masih tertulis di
> `AGENTS.md`.
>
> Seksi di bawah ini disimpan sebagai **riwayat keputusan**, bukan sebagai rencana kerja.

**Laporan owner setelah review di HP:**

> "menambahkan horizon scalp, intraday, swing ke dashboard dan detail koin aplikasi menjadi rancu,
> terutama di halaman detail koin, kesiapan horizon 3/5 tapi penilaian engine scalping masih 1 gate
> yang lolos … preferensi pemilihan engine itu kan ada di tombol simpan di setting, berarti
> kesimpulan saya engine horizon dan engine scalping/intraday/swing itu berbeda (tidak SSOT)"

Kesimpulan owner **benar**, dan §14 di atas adalah penyebabnya.

### 15.1 Akar masalah (terverifikasi di kode)

§14 menambahkan `HorizonReadinessEngine` yang menilai **semua** horizon, termasuk horizon yang
engine-nya sedang aktif. Dua daftar syarat berbeda untuk satu pertanyaan yang sama:

| Permukaan | Sumber | 5 syaratnya |
|---|---|---|
| `SCALP DESK` | `ScalpReadiness.checkpoints()` | Bias & Ruang Naik · Setup Scalping · Tekanan Orderbook · Trigger VWAP/VSA · Net R:R & Skor |
| `KESIAPAN HORIZON` | `HorizonReadinessEngine.scalpChecks()` | Tren H1 · Momentum M15 · Dorongan M1 · Volatilitas sehat · Ekonomi trade |

Keduanya menulis `N/5`. Koin yang sama lolos 1 syarat menurut engine dan 3 syarat menurut
pra-saring — dan keduanya "benar" menurut daftarnya masing-masing. Yang salah bukan angkanya,
melainkan **mandat** pra-saring: ia dibuat untuk menyaring ratusan koin, bukan menjadi pendapat
kedua atas horizon yang sudah dijawab engine.

Di dashboard bentuknya berbeda tetapi akarnya sama: kartu `SIAP TRADING` meringkas **tiga** horizon
seolah setara, sementara preferensi engine disimpan pengguna lewat tombol simpan di Pengaturan.

### 15.2 Perbaikan — satu aturan

```
StrategyMode (preferensi pengguna, tombol simpan di Pengaturan)   ← SSOT
    └── TradeHorizon.of(mode)                                     ← horizon aktif
            ├── horizon aktif → EngineVerdict → ReadinessSource.ENGINE
            └── horizon lain  → pra-saring    → ReadinessSource.PRESCREEN
```

| Berkas | Perubahan |
|---|---|
| `engine/readiness/EngineVerdict.kt` **(baru)** | `AISignalState` + `ScalpReadinessSnapshot` → `HorizonReadiness`. Memakai objek checkpoint yang **sama** dengan kartu engine; tidak ada angka dihitung ulang. Rencana trade diambil dari level milik sinyal (`entryPrice`/`stopLoss`/`targetPrice1/2`). |
| `HorizonReadiness.kt` | +`ReadinessState.HOLDING`, +`ReadinessSource`, +`HorizonReadiness.source`/`sourceNote`, +`ReadinessBoard.withEngineRows()`/`engineRows()`/`prescreenRows()` |
| `TradeHorizon.kt` | +`strategyMode`, +`TradeHorizon.of(mode)` |
| `HorizonReadinessEngine.kt` | `weightedScore`/`exitRule`/`roundTripCostPct` dipublikasikan supaya dipakai bersama, bukan disalin |
| `TradingViewModel.kt` | +`readinessScanBoard`, +`readinessEngineJob`; `_readinessHorizon` default = horizon aktif; `setStrategyMode` memindahkan horizon + publikasi ulang |
| `TradingViewModelReadiness.kt` | +`readinessActiveHorizon()`, `publishReadinessBoard()`, `engineVerdictRows()`, `startReadinessEngineLayer()` (reaktif, tanpa memindai ulang) |
| `ui/components/readiness/*`, `ui/screens/*` | badge `ENGINE AKTIF`/`PRA-SARING`, legenda, tile engine ditandai; kartu dashboard menyatakan engine aktif |

### 15.3 Keputusan desain yang diambil

| Keputusan | Alasan |
|---|---|
| Pra-saring **tetap ada** | Ia menyaring ratusan koin dari cache dengan murah. Yang salah mandatnya, bukan keberadaannya. |
| `HOLDING` jadi status tersendiri | Menerjemahkan "posisi terbuka" ke `SIAP` akan mengulang kontradiksi di layar yang sama (kartu engine menulis `POSISI AKTIF`). |
| Satu resolver (`ScalpReadiness.resolve`) untuk ketiga mode | Ketiga engine mengisi `ScalpingMtfSnapshot` yang sama; satu resolver = satu aturan kesimpulan. |
| Rencana disembunyikan di kartu kesiapan saat mode scalping | `SCALP DESK` tepat di bawah sudah memuat entry/SL/TP. |
| Detail menghitung baris engine sendiri | Pair yang baru dibuka langsung benar, tanpa menunggu pemindaian 60 detik. |
| Baris engine selalu di atas di papan | Bila engine sudah menilai, itu jawaban yang dicari — bukan hasil saringan. |

### 15.4 Batasan yang diakui di UI

Engine menilai **satu simbol** (yang sedang diulas). Koin lain di daftar pantau belum punya sinyal
engine hidup, jadi barisnya tetap `PRA-SARING` — dan itu **ditulis di layar**, bukan disembunyikan.

### 15.5 Uji

Baseline 364 → **385** (+21). `385 lulus · 0 gagal · 1 dilewati`.

- `EngineVerdictTest` (12): identitas checkpoint dengan `ScalpReadiness` (label, urutan, nilai `ok`,
  detail), **1/5 tetap 1/5**, skor memakai penghitung yang sama, pemetaan status termasuk `HOLDING`,
  `READY` tanpa level engine tidak actionable, horizon mengikuti mode.
- `ReadinessBoardOverlayTest` (8): baris engine **mengganti** (bukan menambah) baris pra-saring,
  simbol/horizon lain tidak tersentuh, pencocokan simbol tanpa peduli huruf besar-kecil, pemetaan
  `StrategyMode` ↔ `TradeHorizon` satu-ke-satu.
- `HorizonReadinessEngineTest` (+1): pra-saring **tidak pernah** menandai barisnya `ENGINE`.

**Kontrol negatif** (wajib, lihat `AGENTS.md`):

| NC | Perubahan sementara | Hasil |
|---|---|---|
| NC-3 | `EngineVerdict` kembali menilai ulang dengan daftar syarat sendiri | 2 tes GAGAL |
| NC-1 | `withEngineRows` dimatikan | 3 tes GAGAL |
| NC-2 | `HOLDING` dipetakan ke `READY` | 1 tes GAGAL |

Ketiganya dipulihkan dan suite kembali hijau (`grep -c "NEGATIVE CONTROL"` = 0).

### 15.6 Yang belum dikerjakan

- **INTRADAY/SWING belum divalidasi keluar-sampel** — §15 menyamakan *siapa yang menjawab*, bukan
  menambah bukti edge.
- **Sinyal engine per-simbol belum tersedia untuk seluruh daftar pantau.** Kalau nanti
  `LearningTradingEngine` menilai banyak simbol sekaligus, `engineVerdictRows()` bisa diperluas
  menjadi per-simbol dan seluruh papan bisa berhenti memakai pra-saring untuk horizon aktif.
- Kesiapan uang riil tetap **BELUM** (§9.5c, §13.4).

---

## 16. Integritas log audit P0–P2 (2026-10-14)

**Masalah:** jurnal audit tidak bisa direproduksi pihak ketiga — beberapa kolom diisi konstanta
atau dihitung dari basis yang salah, sehingga angka di laporan tidak berasal dari state nyata.

**Laporan:** `docs/LAPORAN-eksekusi-p0p2-2026-10-14.md`
**Hasil:** `assembleDebug` sukses · **344 test lulus / 0 gagal / 1 skip** (48 kelas)

| # | Perbaikan | Akar masalah |
|---|---|---|
| P0-1 | **RVOL basis volume diselaraskan** | pembilang memakai volume bar M1 yang **masih berjalan**, penyebut rata-rata bar penuh → bias rendah sebesar persentase waktu berjalan |
| P0-2 | **Basis bar indikator tersimpan** di log (`barTimestamp`, `barClosed`, `rvol`, `vwap`, `macdSignal`) | angka RSI/MACD/RVOL tidak bisa direproduksi dari candle bursa |
| P0-3 | `signalTime` dari engine, bukan `now` | lag sinyal→eksekusi tidak terukur (dua kolom selalu identik) |
| P0-4 | `sellReason` nyata (`TRAILING_STOP`/`STOP_LOSS`/`HIT_TP1`/`HIT_TP2`/`MANUAL_SELL`) | konstanta `SIMULATION_SELL` — exit manual vs TP vs SL tidak bisa dibedakan |
| P0-5 | `isTrailingUsed` + `trailingPercent` dari state nyata | getter hardcode `1.5` (bisa salah 10× dari nilai nyata 0,15) |
| P0-6 | Peak/Drawdown dari candle bursa + label `Peak (candle)` vs `Peak (dari tick)` | peak dihitung dari tick sehingga menyiratkan akurasi yang tidak dimiliki |
| P1-7 | `Golden/Death Cross` → `MACD Histogram > 0 / < 0` | label tidak sesuai yang dihitung |
| P1-8 | Format angka 4–5 digit signifikan | `%E` mentah 16 digit |
| P1-9 | `VWAP 1M` kosong → `-` | `0.00` dibaca sebagai nilai nyata |
| P1-10 | Penamaan **stage** vs **score band** dipisah | `STRONG ENTRY: Skor 68` vs `Skor 68/100 (WATCH)` terbaca kontradiktif |
| P2-11 | Flag `isSample` + badge + keluar dari statistik | 7 record seed mencemari win rate/PnL |

**Migrasi Room 8 → 9** non-destruktif (`MIGRATION_8_9`) — jurnal pengguna tidak terhapus.

**Bukti terkuat (P0-1, kasus GNS/USDT 13 Okt 23:58 WIB):** bar berjalan 52% waktu, volume 30.215,
rata-rata 20 bar tutup 39.818 → **RVOL lama 0,76×** (gagal gate 1,2×) vs **RVOL baru 1,46×** (lolos).
Konsekuensinya temuan "GNS entry tipis" **batal** — entry-nya sehat, yang rusak pengukurannya.
6 unit test baru mengunci perilaku ini, termasuk satu test regresi yang mereproduksi 0,76× → 1,46×.

---

## 17. Perbaikan strategi scalping S1–S9 (2026-10-14)

**Sumber kebutuhan:** `docs/BELUM-DIKERJAKAN-2026-10-14.md` Bagian 1 (audit GNS/USDT & AMP/USDT).
**Laporan:** `docs/LAPORAN-eksekusi-s1s9-2026-10-14.md`
**Hasil:** **344 → 358 test** lulus · 0 gagal · 1 dilewati (50 kelas) · `assembleDebug` sukses

| # | Perubahan | ⚠️ Catatan |
|---|---|---|
| S1 | **RVOL wajib ≥ 1,2× untuk setup BREAKOUT**; loophole "2 dari 3 konfirmasi" dihapus | — |
| S2 | **Lantai profit tutup** `minProfitFrictionMultiple` default **2,0** (= 1,9376% kotor) | ⚠️ **mengubah perilaku default** — set slider 0 untuk perilaku lama |
| S3 | **SL keras configurable** `maxRiskAtr` default 2,0 (ketat 1,5 / longgar 2,5); tak pernah menyempit di bawah lantai friksi | — |
| S4 | Konstanta `ATR_PCT_*` benar-benar dipakai + penanda ATR di luar band scalp ideal | — |
| S5 | **Basis PnL seragam**: order MARKET simulasi kena slippage 0,08%/sisi; LIMIT tidak | ⚠️ harga isi & PnL berubah dari versi sebelumnya |
| S6 | Label volume 24 jam di ekspor audit (`$baseAsset` → benar sebagai quote) | — |
| S7 | **Beli manual kini punya mesin exit** (`armPaperExitEngine()`); auto-sell/trailing lama dimatikan | ⚠️ beli manual sekarang **bisa menjual sendiri** |
| S8 | **BE & trailing proporsional ATR** (`be_trigger_atr_fraction` 0,5 · `trail_atr_fraction` 0,6) | GNS: BE 0,35% → 1,655%; trail 0,15% → 1,986% |
| S9 | **Gerbang extended-move** (`max_change_24h_pct`) | ⚠️ dikirim **NONAKTIF** (default 0) — lihat di bawah |

**Bukti replay (fee 0,4044%/sisi, slippage 0,08%, lookahead 120 bar M1):**

| Pair | Sebelum | Sesudah (S1+S2+S8) |
|---|---|---|
| GNS/USDT | 17 trade, WR 76,5%, avg R **+0,0021**, PF 1,01 | 4 trade, WR **100%**, avg R +0,1782, **tanpa drawdown** |
| AMP/USDT | 10 trade, WR 80,0%, avg R −0,0630, PF 0,75 | 7 trade, WR 71,4%, avg R −0,0680, PF **0,81** |

AMP tetap negatif di semua konfigurasi — perbaikan exit hanya **mengurangi kerugian** (PF 0,75 → 0,81).
Itu memang batas yang bisa diharapkan; pair ini bergerak berlawanan dengan asumsi entry.

**Kenapa S9 nonaktif secara default.** Rekomendasi awal menahan sinyal bila koin naik ≥25%/24 jam
(dari kasus AMP +28,09%). Diuji pada GNS, ambang itu **menghapus seluruh trade** — padahal tanpa
gerbang keempat trade itu **4/4 menang**. Satu pair bukan dasar untuk mengubah perilaku entry di
semua pair. Maka implementasi tetap dikirim (kode + alasan penolakan `STEP0_EXTENDED_MOVE` + slider
0–60%), default **0**, dan kalibrasi ambang masuk agenda P4.1. Ini asal perangkap #18.

**Parameter baru:** `max_risk_atr` (2,0) · `max_change_24h_pct` (0) ·
`exit.min_profit_friction_multiple` (2,0) · `exit.be_trigger_atr_fraction` (0,5) ·
`exit.trail_atr_fraction` (0,6).

**Uji:** `ScalpStrategyFixTest.kt` (9 uji) + `SimulationSlippageParityTest.kt` (3 uji Robolectric).
Dua berkas uji lama disesuaikan **bukan karena bug**, melainkan karena default S2 berubah — lantai
dimatikan eksplisit di skenario lama (lihat perangkap #19).

---

## 18. P4.4 — Persistensi progres FSM paper & perbaikan auto-buy nominal IDR (2026-10-15)

### 18.1 P4.4 — Rehidrasi progres FSM paper (`56eb5cd`)

**Masalah:** `PaperAutoTradeRuntime.machines` hanya hidup di memori. Setelah restart, `barsHeld`,
`isBeArmed`, `isPartialDone`, dan `peak` **reset** — mesin yang dipulihkan ≠ mesin yang hilang.
Ini menutup batasan yang sebelumnya diterima di §12.4.

**Perbaikan:** progres FSM disimpan sebagai **satu JSON** di `SpotPositionStore` dengan key
`<key>_paper_fsm`, mencakup `barsHeld`, `peak`, `activeStopLoss`, `isBeArmed`, `isTrailArmed`,
`isPartialDone`, `partialR`, dan `atrPct`.

Empat hal yang harus dijaga setiap kali menyentuh siklus hidup posisi paper:

1. tulis lewat `TradingViewModel.persistPaperFsm(...)` (ber-throttle) — **jangan** tulis langsung,
   karena `evaluatePaperAutoExit` berjalan **per tick harga**;
2. hapus key-nya di **setiap** jalur pelepasan kepemilikan: `markSold` (dua blok),
   `finalizePaperExit`, `clearPaperExitState`, dan cabang "posisi sudah tidak ada" di
   `evaluatePaperAutoExitLocked` — key yang tertinggal akan terbaca posisi berikutnya;
3. `atrPct` **tidak** dipulihkan lewat `restore()` — ia parameter konstruktor mesin, jadi harus
   dioper saat mesin dibangun di `PaperRehydrate.decide()`. Kalau terlewat, ambang BE/trailing S8
   diam-diam jatuh ke nilai persen tetap;
4. `barsHeld` dihitung dari **jam dinding** (`mergedBarsHeld`), bukan dari nilai tersimpan saja —
   replay offline menghitung bar M1 terus-menerus, jadi app harus setara.

State tersimpan yang tidak lolos invarian (`PaperExitStateMachine.restoreRejection`) **dibuang**,
bukan dipakai setengah.

**Laporan:** `docs/LAPORAN-p44-persistensi-fsm-2026-10-15.md`
**Uji:** `PaperExitFsmStateTest` (18), `SpotPositionStorePaperFsmTest` (8, Robolectric),
`PaperRehydrateTest` (+10), `PaperAutoTradeRuntimeTest` (+9) → **371 → 416 test**.

### 18.2 Bug auto-buy paper: nominal Rupiah dibaca sebagai USDT (`f9e7fdd`)

**Gejala di log runtime:** `❌ [AVAUSDT] Auto-buy simulasi gagal: Saldo USDT tidak cukup.
Dibutuhkan $560.41, saldo $557.71.` — auto-buy **tidak pernah** tereksekusi.

**Dua cacat bertumpuk** (yang kedua tidak dilaporkan, ditemukan saat menelusuri):

1. `PaperAutoTradeConfig.fixedQuoteAmount` disajikan di UI sebagai **Rupiah** (default `100_000`,
   langkah `10_000`, tampil `"100 rb"` tanpa penanda mata uang) tetapi dipakai apa adanya sebagai
   **kas kuotasi pair** → pada pair USDT, Rp 100.000 menjadi **$100.000**.
2. Batas anggaran memakai `coerceAtMost(availableQuote)` (**saldo mentah**), padahal order MARKET
   menuntut `notional × (1+slippage) × (1+fee)`. Jadi setiap nominal ≥ saldo **selalu** ditolak —
   kegagalan sistematis, bukan kebetulan.

Aritmetika yang mereproduksi log persis (fee Tokocrypto USDT 0,4044%, slippage 0,08%):
`min(100000, 557,71) × 1,0008 × 1,004044 = **$560,41**` → ditolak.

**Perbaikan:** `fixedQuoteAmount` → `fixedIdrAmount` (kunci preferensi lama dipertahankan);
`quantityFor(): Double` → `plan(): AutoBuySizing` (`Ok`/`Skip`, alasan bisa dibaca); anggaran
memakai rumus **identik** dengan order engine; nominal yang tidak muat **dilewati** (tidak dipotong
diam-diam); kurs USDT/IDR belum tersedia → sinyal dilewati. UI diberi label `(Rp)`, nilai
`Rp 100.000`, plus ekuivalen USDT dengan kurs live.

**Kontrol negatif:** logika dibalik ke perilaku lama → **6 dari 13 test gagal**, termasuk
`expected:<6.0976> but was:<557.71>`.

**Laporan:** `docs/LAPORAN-bug-auto-buy-nominal-idr-2026-10-15.md`

### 18.3 Status setelah §18

| | |
|---|---|
| Unit test (diukur dari XML) | **416 lulus · 0 gagal · 1 dilewati** (53 kelas) |
| Baseline sebelum P4.4 (`f9e7fdd`) | **371 lulus · 0 gagal · 1 dilewati** (51 kelas) |
| APK debug | 26.821.815 byte (26,8 MB desimal / 25,6 MiB) |
| Yang dilewati | `RealTokocryptoReplayTest` — hanya dijalankan oleh workflow manual |
| `patch` vs `main` | Snapshot 2026-10-15 tepat setelah PR #60: **identik secara isi** (`git diff HEAD origin/main` kosong); `main` hanya +2 commit merge (PR #59, PR #60). Sejak itu `patch` maju tiap commit baru — **normal** |
| Kesiapan uang riil | **BELUM** — lihat §9.5c |

### 18.4 Sinkronisasi klaim branch (2026-10-15)

**Masalah:** `AGENTS.md`, `docs/PANDUAN-LANJUT-AGEN.md`, dan `docs/BELUM-DIKERJAKAN-2026-10-14.md`
masih menyatakan "`patch` di depan `main`". Per 2026-10-15 klaim itu **terbalik**.

**Terukur (bukan diingat):**

```sh
git ls-remote --heads origin              # hanya refs/heads/main & refs/heads/patch
#   main = 7f890eb (Merge PR #60) · patch = 5dc7182
git rev-list --count origin/main..HEAD    # 0 → patch tidak di depan
git rev-list --count HEAD..origin/main    # 2 → main unggul 2 commit (keduanya MERGE)
git diff --name-status HEAD origin/main   # kosong → isi patch == isi main
```

Sejak **PR #60** (`7f890eb`), `patch` dan `main` **identik secara isi**; `main` hanya unggul
**2 commit merge** (PR #59 `bc48ac4`, PR #60 `7f890eb`). Seluruh pekerjaan `f9e7fdd` (auto-buy) dan
`56eb5cd` (P4.4) **sudah ada di `main`**.

**Catatan penting:** setelah snapshot itu, **setiap commit baru mendarat di `patch` dulu**, jadi
`origin/main..HEAD` normalnya **`> 0`** — itu wajar, bukan tanda `main` ketinggalan. Commit dokumen
ini sendiri membuat `patch` maju **+1** dari `main`.

**Perbaikan:** klaim branch di keempat berkas (`AGENTS.md` §Langkah 0, `PANDUAN` §1–§2,
`BELUM-DIKERJAKAN` header, tabel ini) diperbarui menjadi "identik isi + `main` unggul commit merge",
dengan snapshot bertanggal supaya tidak cepat usang. Aturan kerja **tidak berubah**: kerja di
`patch` dulu, `main` menerima merge setelah review.

**Laporan:** `docs/LAPORAN-sinkron-klaim-branch-2026-10-15.md`. Ini perubahan **docs-only** — komit
dengan `[skip ci]`.

**Tindak lanjut:** setelah sinkronisasi, `patch` di-merge ke `main` (commit merge `70aea38`, ditandai
`[skip ci]` agar workflow **Release APK** tidak terpicu) — `main` dan `patch` kembali identik isi.

---

## 19. Batas jumlah koin untuk auto-buy paper (2026-10-15)

### 19.1 Masalah

Auto-buy bisa terpicu sinyal entry padahal portofolio paper **tidak punya / kurang saldo** —
saldo sudah terpotong posisi auto-buy sebelumnya yang masih **hold**. Sebelum ini **tidak ada**
batas jumlah posisi bersamaan sama sekali; satu-satunya penjaga adalah dedup per-pair di
`tryPaperAutoBuy`. Akibatnya kegagalan terlihat sebagai "saldo kurang" dari order engine, bukan
sebagai masalah setelan.

### 19.2 Keputusan owner

| | |
|---|---|
| Cakupan | **global** — satu batas untuk semua bursa & kuotasi |
| Yang dihitung | **semua** posisi paper (termasuk beli manual) |
| Default | **3** koin; `0` = tanpa batas; bisa diubah user di Pengaturan |
| Yang diblokir | **hanya auto-buy**; beli manual tetap bebas |

### 19.3 Implementasi

| Titik | Berkas | Perubahan |
|---|---|---|
| Field + helper murni | `engine/scalping/PaperAutoTradeConfig.kt` | `maxConcurrentPositions = 3`, `positionLimitReached(currentCount)` |
| Hitungan global | `trading/SpotPositionStore.kt` | `countActivePaperPositions()` = `getAllActivePositions(isReal = false).size` |
| Gerbang | `viewmodel/PaperAutoTradeCoordinator.kt` | di `tryPaperAutoBuy`, setelah dedup per-pair, sebelum baca saldo |
| Persistensi | `util/AppPreferences.kt` | `KEY_PAPER_AUTO_BUY_MAX_POSITIONS` |
| UI | `ui/components/settings/PaperAutoTradeSettings.kt` | `StepperRow` "Maks. posisi bersamaan", rentang 0–20 |

`getAllActivePositions(exchange = null, isReal = false)` mengiterasi seluruh key, menyaring
`HOLDING` + `isReal=false` + `quantity > 0` → benar-benar global, dan **mencakup** posisi beli
manual. Penanda `_paper_engine` **tidak** dipakai karena ia menandai kepemilikan exit, bukan asal
order.

### 19.4 Kontrol negatif

`positionLimitReached` dijadikan `false` → **3 dari 6** test gagal (`batas tercapai`,
`batas terlampaui`, `batas satu`); test yang tidak bergantung gerbang tetap lulus. Dipulihkan →
hijau.

### 19.5 Laporan

`docs/LAPORAN-batas-posisi-paper-2026-10-15.md`

---

## 20. Mode detail koin otoritatif & kartu backtest dibuang (2026-10-15)

### 20.1 Masalah

Halaman detail koin menampilkan kartu beli dan jual **bersamaan**: satu kartu gabungan
`WaitingEntryRadarCard` + tab Beli/Jual manual di `RadarTransactionFeeSection`. User tidak bisa
membedakan sedang membeli atau menjual. Owner juga meminta baseline backtest dibuang dari UI.

### 20.2 Keputusan owner

| | |
|---|---|
| Saat hold | halaman detail = **mode SELL** |
| Saat tidak hold | halaman detail = **mode BUY** |
| Toggle Beli/Jual | **dihapus** |
| DCA saat hold | **tetap bisa** (jalur terpisah) |
| Backtest | **hanya kartu UI** yang dibuang; mesin dipertahankan |

### 20.3 Implementasi

| Titik | Berkas | Perubahan |
|---|---|---|
| Resolver murni | `model/PositionContext.kt` | `enum DetailTradeMode` + `resolveDetailTradeMode(isHolding)` |
| Layar | `ui/screens/DetailChartScreen.kt` | state `isBuyMode` lokal dihapus (mode jadi turunan) |
| Kartu | `ui/components/detail/WaitingEntryRadarCard.kt` | param mode dihapus; kode mati dibersihkan; tombol + dialog DCA |
| Form | `ui/components/detail/RadarTransactionFeeSection.kt` | toggle Beli/Jual (baris 139–201) dihapus |
| Backtest | `ui/components/detail/radar/BacktestSection.kt` | **berkas dihapus** + 2 pemanggilnya |

Kode mati yang ikut dibersihkan: cabang "Belum ada koin di dompet" dan notis "mode Buy untuk
re-entry/DCA" — keduanya tidak pernah tercapai lagi karena `!currentBuyMode` kini identik dengan
`isHolding`.

Jalur DCA dibuat baru (yang lama tidak ada: `SellManualBuyDialog.kt` adalah dead code dan hanya
merekam harga beli manual). Dialog DCA memakai ulang `RadarBuySection`, jadi jalur eksekusi
real/paper identik dengan mode BUY.

### 20.4 Kontrol negatif

`resolveDetailTradeMode` dibalik (hold → BUY) → **3 dari 3** test gagal. Dipulihkan → hijau.

### 20.5 Laporan

`docs/LAPORAN-mode-detail-dan-hapus-backtest-2026-10-15.md`

---

## 21. Status setelah §19–§20

| | |
|---|---|
| Unit test (diukur dari XML) | **429 lulus · 0 gagal · 1 dilewati** (56 kelas) |
| Baseline sebelum §19–§20 | **416 lulus · 0 gagal · 1 dilewati** (53 kelas) |
| Tambahan | **+13 test, +3 kelas** |
| APK debug | 27.875.220 byte (27,9 MB desimal) |
| Kesiapan uang riil | **BELUM** — lihat §9.5c |
