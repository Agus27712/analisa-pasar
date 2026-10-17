# workplan2.md — Workplan aktif (Oktober 2026)

> **Sumber kebenaran workplan.**  
> `workplan.md`, `checkpoint.md`, dan `checkpoint_progres.md` **sudah dihapus (2026-10-16)** — jangan cari, jangan dibuat ulang. Baca ini + `AGENTS.md` + `APP_CONTEXT.md` (§ arah produk).

**Update:** 2026-10-16 (WIB) — §40 sinkronisasi dokumen temuan

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

**Jangan baca sebagai instruksi aktif:** `workplan.md`, `checkpoint.md`, `checkpoint_progres.md` — ketiganya **sudah dihapus (2026-10-16)**; isinya cuma stub DEPRECATED yang menunjuk ke `workplan2.md`.

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

---

## 22. Mode SELL yang benar & frame FSM jadi utama (2026-10-15)

### 22.1 Masalah

Owner melaporkan **"masih sama, UI buy dan sell"**. Setelah §20 mode halaman detail sudah otoritatif
(hold → SELL), tetapi secara visual kedua mode tetap terasa identik.

Akar: `ui/screens/DetailChartScreen.kt` baris 316 merender `ScalpDeskCard` + `PaperTradeDeskCard`
dengan syarat `isScalping` **saja**, **tanpa melihat status kepemilikan koin**. Kedua frame terbesar
di layar jadi statis — itulah yang membuat mode beli dan jual terlihat sama.

### 22.2 Keputusan owner

| Aspek | Keputusan |
|---|---|
| Mode SELL | Sembunyikan `ScalpDeskCard`, `PaperTradeDeskCard`, **dan** `MarketConditionCard` ("KONDISI SCALPING") |
| Tetap tampil di mode SELL | FSM paper trade, indikator observasi (`DetailTechnicalDetailsSection`), log sinyal (`SignalHistoryPanel`) |
| Posisi FSM | **Paling atas** — setelah kartu grafik, sebelum radar jual |
| Kosmetik FSM | **Lengkap** |

### 22.3 Implementasi

| Titik | Berkas | Perubahan |
|---|---|---|
| Resolver murni | `model/PositionContext.kt` | `enum DetailScalpPanel {NONE, SCALPING_DESK, EXIT_FSM}` + `resolveDetailScalpPanel(isScalping, isHolding)` |
| Layar | `ui/screens/DetailChartScreen.kt` | `if (isScalping)` → `when (resolveDetailScalpPanel(...))`; FSM dipanggil langsung dari layar; `MarketConditionCard` dibungkus `if (!(isScalping && isHolding))` |
| Kosmetik | `ui/components/simulation/PaperExitFsmCard.kt` | `FsmMetricTile` (grid 2×2), `FsmValueText` → `AnimatedMetricText`, `AnimatedPercentageBadge`, kedip SL bergeser, stepper + bar beranimasi |
| Uji | `app/src/test/java/agu/analys/engine/DetailScalpPanelTest.kt` | **baru** — 6 kasus |

Tiga status (`NONE` terpisah) dipilih supaya kasus "scalping nonaktif" bisa diuji eksplisit dan FSM
tidak diam-diam muncul di mode non-scalping.

Saat mode SELL tetapi `paperSnapshot == null` (posisi manual tanpa mesin exit), kartu FSM **tidak**
dipaksa tampil: `EmptyFsmState` berbicara soal auto-buy menunggu sinyal, yang menyesatkan di mode
jual. Copy komponen bersama itu tidak diubah karena juga dipakai layar Simulasi.

### 22.4 Kosmetik — memakai komponen yang sudah ada

Semua dari `ui/animation/AppAnimations.kt`, tanpa gaya baru: `AnimatedMetricText`,
`AnimatedPercentageBadge`, `rememberLivePulseAlpha` + `Modifier.livePulse`, `animateFloatAsState`,
`animateColorAsState`. Kosmetik dipasang **tanpa syarat** (kartu juga dipakai `TradeSimulationScreen`)
dan menghormati `PriceAnimationMode.STATIC` lewat `LocalPriceAnimationMode` sebagai katup hemat baterai.

### 22.5 Kontrol negatif

Aturan `isHolding` dibalik (hold → `SCALPING_DESK`) → **4 dari 6** test gagal. Dua yang tetap lulus
adalah kasus `isScalping = false`, yang memang tidak boleh terpengaruh — sekaligus membuktikan `NONE`
independen. Dipulihkan → hijau.

### 22.6 Laporan

`docs/LAPORAN-mode-sell-fsm-2026-10-15.md`

---

## 23. Status setelah §22

| | |
|---|---|
| Unit test (diukur dari XML) | **435 lulus · 0 gagal · 1 dilewati** (57 kelas) |
| Baseline sebelum §22 | **429 lulus · 0 gagal · 1 dilewati** (56 kelas) |
| Tambahan | **+6 test, +1 kelas** |
| APK debug | 27.877.128 byte (27,9 MB desimal) |
| Kesiapan uang riil | **BELUM** — lihat §9.5c |

---

## 24. Seragamkan hitungan P&L ke basis FSM (2026-10-15)

### 24.1 Masalah

Owner menemukan angka yang **tidak cocok** antara FSM paper trade, radar jual, dan koin yang
dimiliki di halaman portofolio untuk posisi yang sama. Dugaan owner benar: FSM memakai fee truth +
slippage sehingga nilainya paling kecil. Setelah ditelusuri ternyata ada **empat** rumus berbeda,
bukan tiga — dan yang justru cacat adalah halaman portofolio.

| Tempat | Rumus lama | Friksi (Tokocrypto IDR) |
|---|---|---|
| Portofolio (3 tempat) | `(harga − avgBeli) / avgBeli` | **0%** — bruto |
| Radar jual & kartu posisi | fee jual maker saja | 0,3322% |
| FSM paper | taker + slippage 2 sisi | 0,8119% |

### 24.2 Keputusan owner

**Seragamkan ke basis FSM** (taker + slippage dua sisi, multiplikatif). Paling jujur karena
auto-buy/auto-exit adalah order market.

### 24.3 Implementasi

Satu titik kebenaran di `object FeeSchedule` (`config/AppConfiguration.kt`): `netProfitPct`,
`netProfitPctFor` (default **taker**), `costBasisNet`, `netSellValue`, `breakevenPrice`.

Invarian anti-drift: `(netSellValue / costBasisNet − 1) × 100` sama persis dengan `netProfitPct`.

| Pemanggil | Perubahan |
|---|---|
| `engine/scalping/PaperExitResolver.kt` | mendelegasi ke `FeeSchedule` (signature dipertahankan) |
| `model/PositionContext.kt` | pakai helper baru; maker → **taker** |
| `engine/sell/SellSignalEvaluator.kt` | pakai helper baru; parameter `slippagePct` di kedua overload |
| `ui/screens/PortfolioScreen.kt` | bruto → net |
| `ui/screens/portfolio/RealPortfolioView.kt` | bruto → net |
| `ui/components/dashboard/ActiveHoldingSection.kt` | bruto → net |
| `ui/components/detail/sell/SellPositionOverviewCard.kt` | label basis biaya |

**Tidak diubah**: `SimulationTradeStore`, `SimulationOrderEngine` — basisnya P&L **terealisasi**
(sudah dikunci), bukan floating.

### 24.4 Efek samping perilaku (disengaja)

Friksi radar jual 0,3322% → 0,8119%. Ambang jadi lebih ketat:

| Ambang | Sebelum | Sesudah |
|---|---|---|
| `netProfitPct > 0` | ≈0,33% | **≈0,82%** |
| `>= 1.0` | ≈1,34% | **≈1,83%** |
| `>= 2.0` (badge PROFIT) | ≈2,34% | **≈2,84%** |
| `>= 3.5` | ≈3,85% | **≈4,35%** |
| `>= 5.0` | ≈5,35% | **≈5,86%** |

Pada bruto +1,5%: radar jual dulu **+1,16%**, sekarang **+0,68%** (selisih 0,49 pp).
**Catatan:** TP1 yang hanya ~0,5% di atas entry tidak lagi memicu `READY_TO_SELL` — TP harus
≥≈0,82% di atas entry agar menguntungkan secara net. Ikut berubah: `SellCheckpointEvaluator`
(checkpoint 3), `ReadySellBadgeEvaluator`, `ProactiveProfitSummaryCard`, `SpotPositionCard`,
`AlertNotificationHelper`.

### 24.5 Uji & kontrol negatif

`app/src/test/java/agu/analys/engine/NetPnlParityTest.kt` — **baru**, 8 kasus (friksi −0,8119%,
impas 0,8186%, paritas dengan `PaperExitResolver`, invarian anti-drift, tarif Indodax, dll).

`SellSignalEvaluatorTest` diperbarui: seluruh komponen biaya dinolkan (`tanpaBiaya` +
`slippagePct = 0.0`) karena kelas itu menguji **ambang**, bukan besaran biaya.

Kontrol negatif: basis dipaksa **maker** → **4 dari 8** uji gagal. Empat yang tetap lulus memang
tidak bergantung pada basis taker (paritas delegasi, tanda, harga tidak valid, `costBasisNet`).
Dipulihkan → hijau.

### 24.6 Laporan

`docs/LAPORAN-seragam-pnl-basis-fsm-2026-10-15.md`

---

## 25. Status setelah §24

| | |
|---|---|
| Unit test (diukur dari XML) | **443 total · 442 lulus · 0 gagal · 1 dilewati** (58 kelas) |
| Baseline sebelum §24 | **435 lulus · 0 gagal · 1 dilewati** (57 kelas) |
| Tambahan | **+8 test, +1 kelas** |
| APK debug | 27.877.730 byte |
| Kesiapan uang riil | **BELUM** — lihat §9.5c |

> **Koreksi 2026-10-16:** baris "Unit test" di atas awalnya menulis "443 lulus". Pengukuran ulang
> dari XML menunjukkan 443 adalah **total test** (442 lulus + 1 dilewati). Sudah diperbaiki.

---

## 26. Batch 1 audit notifikasi — N1, N2, N3 (2026-10-16)

Sumber: `docs/laporan_temuan_bugs_notifikasi.md` (audit read-only dari agen lain, di-pull dari
`patch`). Batch 1 mengerjakan tiga temuan berisiko tertinggi. Sisanya (N4–N12) **belum dikerjakan**.

Perbaikan mendarat di commit **`cd6c032`**.

### 26.1 N2 — balapan edge trailing (paling merugikan)

`SpotPositionStore.updateTrailingPrice` bersifat **edge-once**: pemanggil pertama yang melihat
harga melewati batas mengonsumsi edge-nya, dan `justTriggered` hanya `true` sekali.

Dua loop memanggilnya bersamaan:
- `TradingForegroundService` (polling 4 detik) — **membuang** hasil `justTriggered`
- `TradingViewModel.checkTrailingForMode` (per tick) — satu-satunya yang memanggil
  `executeAutoSellOrder`

Bila service menang balapan, VM tidak pernah melihat `true` → **notifikasi trailing hilang dan
auto-sell tidak pernah terkirim**. Posisi menggantung sampai SL/TP manual.

Catatan yang memperkuat: service mengambil `pos` **sebelum** memanggil `updateTrailingPrice`, jadi
efek sampingnya pada `posContext`-nya sendiri memang tidak pernah terpakai. Satu-satunya efek nyata
dari panggilan itu adalah menaikkan peak, menambah counter, **dan mencuri edge**.

**Perbaikan:** parameter `consumeTrigger: Boolean = true` di `updateTrailingPrice`. Saat `false`,
hanya Rule 1 (peak) yang jalan; mesin pemicu (hitung batas, filter 2 tick, set flag) tidak
disentuh dan hasilnya selalu `Pair(<posisi terbaru>, false)`. Service mengirim `false`.

### 26.2 N1 — confidence tampil ×100

`AISignalState.confidence` bertipe `Int` skala **0–100**, tapi notifikasi kandidat menulis
`(signal.confidence * 100).toInt()` → skor `68` tampil **`6800%`**.

**Perbaikan:** badan pesan diekstrak ke `buildCandidateSignalMessage(marketKey, signal)` (fungsi
murni, bisa diuji tanpa Android) dan faktor `* 100` dihapus.

### 26.3 N3 — fee service tidak sama dengan fee user

`TradingForegroundService` memanggil `SellSignalEvaluator.evaluate(..., tradingFees = TradingFeeConfig(), ...)`.
Tanpa argumen, `TradingFeeConfig` jatuh ke preset **legacy Indodax-ish** (0,11 / 0,21 / 0,32 / 0,42),
bukan biaya bursa aktif milik user. Ini **regresi dari §24** — aturan "SATU rumus P&L" masih bocor
di permukaan notifikasi.

**Perbaikan:** service memakai `AppPreferences(applicationContext).tradingFees` (objek prefs
di-cache lewat `by lazy`, nilainya dibaca ulang tiap tick supaya perubahan di Pengaturan langsung
terpakai), sama dengan jalur VM.

### 26.4 Uji & kontrol negatif

| Berkas uji | Test | Menjaga |
|---|---|---|
| `util/CandidateSignalMessageTest.kt` | 4 | N1 |
| `trading/TrailingEdgeOwnershipTest.kt` | 3 | N2 |
| `service/NotificationFeeSourceTest.kt` | 3 | N3 |

Kontrol negatif (ketiganya dimatikan sekaligus, lalu dijalankan):

| Perbaikan yang dimatikan | Uji yang GAGAL |
|---|---|
| `* 100` dikembalikan di `buildCandidateSignalMessage` | 2 dari 4 `CandidateSignalMessageTest` |
| `if (!consumeTrigger)` → `if (false)` di store | 1 dari 3 `TrailingEdgeOwnershipTest` |
| `tradingFees = appPrefs.tradingFees` → `TradingFeeConfig()` | 1 dari 3 `NotificationFeeSourceTest` |
| **Total** | **4 dari 10 uji gagal** |

Uji yang tetap lulus saat perbaikan dimatikan memang tidak bergantung pada perbaikan itu: "skor
nol tetap nol" (0 × 100 = 0), "pesan tetap membawa entry/target/SL", "pengamat tetap menaikkan
peak", "edge hanya dikonsumsi sekali", serta dua uji premis di `NotificationFeeSourceTest`.

### 26.5 Catatan kejujuran tentang batas penjagaan N3

`NotificationFeeSourceTest` menjaga N3 lewat **invarian sumber**: berkas service dibaca, komentar
dibuang, lalu dipastikan tidak ada lagi `TradingFeeConfig()` di kodenya dan ada
`tradingFees = appPrefs.tradingFees`. Ini menangkap persis regresi yang diperbaiki, tapi ia
memeriksa **teks sumber**, bukan perilaku runtime — kalau nanti pemanggilan itu dipindah ke berkas
lain, penjaga ini tidak ikut memindah. Uji perilaku penuh butuh menjalankan service dengan
`Context` tiruan, yang belum ada di repo ini.

### 26.6 Laporan

`docs/LAPORAN-batch1-notifikasi-2026-10-16.md`

---

## 27. Status setelah §26

| | |
|---|---|
| Unit test (diukur dari XML) | **453 total · 452 lulus · 0 gagal · 1 dilewati** (61 kelas) |
| Baseline sebelum §26 (diukur via `git stash -u`) | **443 total · 442 lulus · 0 gagal · 1 dilewati** (58 kelas) |
| Tambahan | **+10 test, +3 kelas** |
| APK debug | 27.877.730 byte |
| Kesiapan uang riil | **BELUM** — lihat §9.5c |

Sisa temuan yang belum dikerjakan: **N4** (toggle push tidak dipatuhi di jalur alert/trailing),
**N5** (notifikasi kandidat tanpa disclaimer "uang riil masih diblokir"), **N6** (dua produsen satu
state machine), **N7** (`markTriggered` tidak menormalkan simbol — **aktif**, bukan latent),
**N8–N12**. Lihat `docs/laporan_temuan_bugs_notifikasi.md`.

---

## 28. Batch 2 audit notifikasi — N4, N5, N7 (2026-10-16)

Lanjutan §26. **N4 diperluas**: selain sakelar master yang diabaikan di `TradingViewModelAlerts`,
ditemukan bahwa **keempat sakelar kategori** juga tidak dibaca oleh jalur pengiriman mana pun.

Perbaikan mendarat di commit **`1a87e3c`**.

### 28.1 N4 (+ temuan baru) — sakelar notifikasi tidak dipatuhi

Keadaan sebelum perbaikan:

| Sakelar | Dibaca oleh jalur kirim? |
|---|---|
| `isNotificationsEnabled` (master) | Ya di `TradingViewModelSync`, `CandidateScanWorker`, `TradingForegroundService`. **Tidak** di `TradingViewModelAlerts` (0 kemunculan). |
| `isNotifyPriceAlertsEnabled` | **Tidak sama sekali** |
| `isNotifyCandidateBuyEnabled` | **Tidak sama sekali** |
| `isNotifyTrailingStopEnabled` | **Tidak sama sekali** |
| `isNotifyEmergencyExitEnabled` | **Tidak sama sekali** |

Keempat sakelar kategori hanya dibaca/ditulis di `SettingsViewModel` dan dirender di
`SettingsCategoryDetailContent` — jadi Pengaturan menawarkan empat sakelar yang **tidak melakukan
apa pun**. Ini temuan baru yang tidak ada di audit asli.

**Jebakan yang dihindari:** saran audit ("tambah guard di awal `checkAlertsAndTrailing`") akan
**mematikan auto-sell**, karena `checkTrailingForMode` dipanggil dari sana dan di dalamnya ada
`executeAutoSellOrder`. Mematikan notifikasi bukan berarti mematikan perlindungan posisi.

**Perbaikan:** gerbang tunggal, bukan guard di tiap call site (justru karena itulah akar N4 —
belasan call site dan tidak satu pun memeriksa):

- `util/NotificationGate.kt` (baru) — `NotificationCategory`, `NotificationSettings`, dan fungsi
  murni `allowsNotification(settings, category)`. Master mati ⇒ semua kategori mati (di UI sakelar
  kategori memang sub-pengaturan dari master).
- `AppPreferences.notificationSettings` — potret kelima sakelar sekali jadi.
- `AlertNotificationHelper.canNotify(context, category)` dipasang di **keenam** chokepoint
  pengiriman (kandidat, exit darurat, trailing hit, trailing peak, take profit, price alert).
  `AppPreferences` di-cache (`by lazy`-style, karena konstruktornya membangun
  `EncryptedSharedPreferences`). Bila prefs gagal dibaca, gerbang **terbuka** — sama seperti
  perilaku sebelumnya, supaya keystore rusak tidak diam-diam mematikan peringatan darurat.

Pemetaan kategori: take profit ikut **TRAILING_STOP** ("Trailing Stop & Eksekusi"), bukan
PRICE_ALERT — sesuai label di Pengaturan.

Semua default `true`, jadi pengguna yang belum pernah menyentuh Pengaturan tidak kehilangan
notifikasi apa pun.

### 28.2 N5 — disclaimer di notifikasi kandidat

Push kandidat membawa Entry/Target/Stop Loss tanpa konteks apa pun, padahal AGENTS.md mewajibkan
setiap permukaan status siap menyatakan eksekusi uang riil masih diblokir. Ditambahkan konstanta
`CANDIDATE_DISCLAIMER` yang ikut di badan pesan:

> `→ Eksekusi uang riil masih DIBLOKIR. Sinyal ini decision support, bukan ajakan beli.`

### 28.3 N7 — normalisasi simbol

`SignalLifecycleManager.markTriggered` **dan** `reset` memakai `"${symbol.uppercase()}#"`, sedangkan
key disimpan lewat `cacheKey` → `normalizeSymbol` (buang `/ _ -`). Akibatnya `markTriggered("eth_idr")`
membentuk prefix `"ETH_IDR#"` yang tidak pernah cocok dengan key `"ETHIDR#SCALPING"`.

Audit asli hanya menyebut `markTriggered` dan masih ragu apakah aktif; **`reset` punya cacat yang
sama** dan keduanya **aktif** — jalur real trade mengirim `pair` berformat Indodax (`btc_idr`),
terbukti dari `baseFromPair`/`quoteFromPair` yang melakukan `.replace("_","")` defensif.

### 28.4 Uji & kontrol negatif

| Berkas uji | Test | Menjaga |
|---|---|---|
| `util/NotificationGateTest.kt` (baru) | 6 | N4 + sakelar kategori (3 murni, 3 perilaku) |
| `engine/scalping/SignalLifecycleSymbolNormalizationTest.kt` (baru) | 2 | N7 |
| `util/CandidateSignalMessageTest.kt` | +1 (jadi 5) | N5 |

Uji perilaku memakai Robolectric + `NotificationManager` sungguhan, jadi ia membuktikan notifikasi
**benar-benar tidak terpasang** — bukan sekadar memanggil fungsi murni.

Kontrol negatif (ketiga perbaikan dimatikan sekaligus):

| Perbaikan dimatikan | Uji yang GAGAL |
|---|---|
| `canNotify` tidak lagi memanggil `allowsNotification` | 3 dari 6 `NotificationGateTest` (yang 3 murni tetap lulus — ia menguji `allowsNotification` langsung) |
| `CANDIDATE_DISCLAIMER` dilepas dari pesan | 1 dari 5 `CandidateSignalMessageTest` |
| `normalizeSymbol` → `symbol.uppercase()` | 2 dari 2 `SignalLifecycleSymbolNormalizationTest` |
| **Total** | **6 dari 13 uji gagal** |

### 28.5 Catatan harness yang perlu diketahui agen berikutnya

Di bawah Robolectric, **membuat `AppPreferences` kedua tidak melihat tulisan instance pertama**
(jalur pemulihan keystore menimpa prefs). Uji perilaku karena itu menulis lewat
`AlertNotificationHelper.notificationPrefsForTest(context)` — objek yang **benar-benar** dibaca
gerbang — bukan lewat `AppPreferences(context)` baru. Bila tidak, uji gagal karena artefak harness,
bukan karena gerbangnya. Batasnya: uji ini membuktikan "diberi setelan X, gerbang menahan kategori
Y", bukan "helper membaca berkas prefs aplikasi yang sama".

### 28.6 Laporan

`docs/LAPORAN-batch2-notifikasi-2026-10-16.md`

---

## 29. Status setelah §28

| | |
|---|---|
| Unit test (diukur dari XML) | **462 total · 461 lulus · 0 gagal · 1 dilewati** (63 kelas) |
| Baseline sebelum §28 | **453 total · 452 lulus · 0 gagal · 1 dilewati** (61 kelas) |
| Tambahan | **+9 test, +2 kelas** |
| APK debug | 27.877.730 byte |
| Kesiapan uang riil | **BELUM** — lihat §9.5c |

Sisa temuan: **N6** (dua produsen satu state machine — butuh keputusan desain), **N8** (param
`onlyWhenBackground` mati), **N9** (default `?: 75` mengarang skor), **N10** (snapshot pair lain
ikut tercatat), **N11** (tier confidence lintas-mode), **N12** (tanpa cooldown notifikasi kandidat).
Lihat `docs/laporan_temuan_bugs_notifikasi.md`.

---

## 30. Batch 3a audit jurnal & P&L — J2, J1 (2026-10-16)

Sumber: `docs/bugs/temuan_bugs_jurnal_pnl.md` (audit read-only agen lain, di-pull dari `patch` @
`eb01dc3`). Batch 3 dikerjakan dalam tiga commit terpisah; ini yang pertama.

Perbaikan mendarat di commit **`d97a539`**.

### 30.1 J2 — satu predikat menang/kalah yang eksklusif

`SignalLogRepository` punya **tiga** definisi menang/kalah. Hanya `buildGroupStats` yang eksklusif;
`buildTierStats` dan `calculateReliabilitySummary` memakai dua filter **independen**:

```kotlin
val wins   = resolved.filter { status in WIN_STATUSES || pnl > 0 }
val losses = resolved.filter { status in LOSS_STATUSES || pnl < 0 }
```

Sebuah log `HIT_SL` ber-P&L positif masuk **kedua** keranjang ⇒ `winCount + lossCount >
resolvedCount` dan win-rate bisa melewati 100%.

Akarnya di `recordSignal`: `entryPrice` diambil dari sinyal **pertama**, sedangkan
`targetPrice1/2` dan `stopLoss` diperbarui dari sinyal **terbaru**. Harga naik jauh ⇒ SL baru berada
**di atas** entry lama ⇒ harga yang turun menyentuh stop loss justru menghasilkan P&L **positif**.

Perbaikan:

- `database/SignalOutcome.kt` (baru) — `SignalOutcome` + `classifySignalOutcome(status, pnl)`.
  Prioritas: status menang → status kalah → tanda P&L. Satu predikat dipakai **empat** call site
  (reliability, tier, group, best-symbol/strategy) — dulu ada definisi keempat di best-symbol.
- `database/SignalLogPnl.kt` (baru) — `hasEntryDrifted(oldEntry, newEntry)`. Bila entry bergerak
  > 0,8% (`ENTRY_SPLIT_TOLERANCE`, sengaja sama dengan ambang anti-spam), episode lama ditutup
  (`resolveSupersededTrackingLog`) dan episode baru dicatat sebagai log terpisah. Level TP/SL
  **tidak pernah** digabung antar-entry.
- Rata-rata dan nilai ekstrem hanya dihitung dari log yang P&L-nya **searah** keranjangnya,
  sehingga keluaran tak masuk akal seperti "rata-rata kalah +90%" tidak mungkin muncul lagi.

### 30.2 J1 — basis net untuk `realizedPnlPct`

`updateTrackingLogWithPrice` menyimpan **selisih harga mentah** ke `realizedPnlPct`, lalu
`SignalLogScreen` melabelinya **"Net"**. Untuk Tokocrypto USDT, "+0,35%" yang tampil sebagai "Net"
sebenarnya **rugi** — impas net-nya 0,9735%.

Perbaikan:

- `FeeSchedule.netProfitPctForDirected(entry, price, isBuy, …)` — arah BUY/SELL dibedakan. SELL
  adalah cerminnya: menjual di `entry` (fee jual) lalu membeli kembali di `price` (fee beli);
  secara aljabar identik dengan menukar peran `entry` dan `price` di `netProfitPct`, bukan
  pendekatan.
- `netSignalPnlPct` dipakai di **tiga** tempat: `updateTrackingLogWithPrice`, `resolveLogManually`,
  dan `expireTrackingLogsForSymbol`.
- `maxProfitPct`/`maxDrawdownPct` **tetap kotor** (B6), dan labelnya diperbaiki: dulu UI menulis
  `"Net +1,20% (gross)"` — dua label bertentangan pada satu angka. Kini `signalPnlLabel()` memilih
  "Net" atau "Gross" sesuai basis yang benar-benar dipakai, dan `EXPIRED` tanpa P&L menulis
  "Hasil tidak diketahui" alih-alih mengarang "0,00%".

### 30.3 Efek amnesti — wajib dibaca sebelum membandingkan angka lama

Setelah J1, banyak sinyal yang dulu tampak "menang" akan berubah menjadi kalah / `EXPIRED` negatif.
Itu **bukan regresi**: friksi round-trip Tokocrypto USDT 0,9735% berarti kenaikan di bawah itu memang
belum menutup biaya. Angka terukur:

| Pergerakan | Gross | Net (Tokocrypto USDT) |
|---|---|---|
| +0,50% | +0,50% | **−0,468950%** |
| +1,00% | +1,00% | +0,026229% |
| 0,00% | 0,00% | −0,964130% |

### 30.4 Uji & kontrol negatif

Uji baru: `app/src/test/java/agu/analys/database/SignalOutcomeClassificationTest.kt` (14 kasus).

Kontrol negatif — tiga perbaikan dimatikan sekaligus (filter lama dikembalikan, `netSignalPnlPct`
dibuat bruto, `hasEntryDrifted` selalu `false`): **9 dari 22 uji GAGAL**, tepat pada penjaga
masing-masing perbaikan. Yang tetap lulus memang tidak bergantung perbaikan —
`SignalLogSetupStatsTest` (8 uji) lulus di kedua keadaan karena `buildGroupStats` memang sudah
eksklusif sejak awal, dan tiga uji `classifySignalOutcome` langsung tidak tersentuh karena yang
dimatikan adalah call site-nya.

### 30.5 Laporan

`docs/LAPORAN-batch3-jurnal-2026-10-16.md`

---

## 31. Batch 3b keamanan posisi paper — P2, P3, P4 (2026-10-16)

Sumber: `docs/bugs/temuan_bugs_paper_trade.md` (audit read-only agen lain, di-pull dari `patch` @
`eb01dc3`). Commit kedua dari batch 3.

Perbaikan mendarat di commit **`8d22fbb`**.

**Batas penting:** seluruh temuan P* hanya menyentuh posisi **paper** (`isReal = false`). Ini bukan
kerusakan uang riil — tapi posisi paper adalah dasar keputusan "apakah scalp punya edge", jadi
angkanya harus benar sebelum uang riil dipromosikan.

### 31.1 P2 — gerbang mode + lantai friksi untuk SL sinyal

`tryPaperAutoBuy` hanya memeriksa `signal.action == BUY` dan `stageQualifies(signal.scalpingStage)`;
`transition.mode` **tidak pernah** diperiksa. Evaluator SWING/INTRADAY memasang `ScalpingStage.ENTRY`
untuk sinyal BUY, sehingga sinyal mereka lolos ambang yang sama. SL-nya 1,5–2,8%
(`IntradayEvaluator` memakai `coerceAtMost(price * 0.985)`), sementara lantai friksi Tokocrypto USDT
= **5,8128%** ⇒ satu SL ≈ −1,3R…−2R, dan mesin exit scalping (time-stop `maxHoldBarsM1`, horizon
~2 jam) memotong posisi ber-horizon harian sebagai `TIMEOUT` palsu.

Perbaikan (dua bagian, keduanya diuji):

1. **Gerbang mode** di `tryPaperAutoBuy` — `if (transition.mode != StrategyMode.SCALPING) return false`.
   Ditempatkan **sebelum** `stageQualifies`, bukan sesudah: kalau sesudah, cabang log diagnostiknya
   tidak akan pernah tercapai (sinyal non-scalping sudah keluar lebih dulu).
2. **Resolver murni** `engine/scalping/PaperStopResolver.kt` — `frictionFloorPct(buyFee, sellFee,
   slippage, multiple)` + `resolve(signalSl, entry, floorPct)`. Aturannya **hanya melebar, tidak
   pernah menyempitkan** SL:

```kotlin
val signalValid = signalSl.isFinite() && signalSl > 0.0 && signalSl < entry
val floorPrice  = entry * (1.0 - floorPct / 100.0)
return if (signalValid && signalSl <= floorPrice) ResolvedPaperStop(signalSl, SIGNAL)
       else ResolvedPaperStop(floorPrice, FRICTION_FLOOR)
```

Resolver yang sama kini dipakai **empat** jalur: auto-buy (`armPaperExitEngine`), beli manual
(`TradingViewModelSync`), re-arm DCA, dan serah-terima ke jalur auto-sell lama saat rehidrasi.
Sebelumnya masing-masing menghitung sendiri, dan jalur "SL dari sinyal" **tidak** memeriksa lantai
sama sekali.

### 31.2 P3 — DCA me-re-arm mesin exit; SL ≥ entry tidak boleh menyalakan auto-sell

DCA mengubah `entry` posisi menjadi harga rata-rata, tetapi `armPaperExitEngine` dipanggil dengan
`force = false` (default), jadi mesin lama yang memakai entry fill **pertama** tidak pernah diganti.
Bila DCA dilakukan di harga lebih rendah, rata-rata turun **di bawah SL lama**; setelah restart
`PaperRehydrate` menolak SL itu (`stopLoss >= entry`) dan mengembalikannya sebagai
`HandBackToLegacy` — yang dulu menulis SL itu apa adanya **sambil menyalakan auto-sell**. Pada tick
berikutnya jalur legacy menjual **seluruh** posisi di harga pasar dengan label `STOP_LOSS`, padahal
tidak ada stop yang tersentuh.

Perbaikan:

- `TradingViewModelSync` — `isDca` dihitung sekali, `effectiveEntry` menggantikan `fillPrice`, dan
  `armPaperExitEngine(..., force = isDca)`. Ini satu-satunya jalur yang boleh menimpa mesin hidup.
- `HandBackToLegacy` di `PaperAutoTradeCoordinator` — SL diambil dari `PaperStopResolver` yang sama,
  dan `enabled = resolvedHandBack.usable`. Bila lantai friksi nonaktif **dan** SL tersimpan tidak
  valid, auto-sell sengaja **tidak** dinyalakan: posisi tanpa stop lebih baik daripada penjualan
  pasar berlabel `STOP_LOSS` yang salah.
- Catatan log per sumber SL (`SIGNAL` / `FRICTION_FLOOR` / `NONE`) supaya keputusan itu terbaca.

**Penyimpangan dari rekomendasi audit.** Audit menyarankan resolver `PaperRearmDecision.decide(...)`
dengan tiga cabang (`Rearm`/`Reset`/`Keep`). Yang dipakai di sini: `force = isDca` di pemanggil DCA,
dan invarian "SL < entry" ditegakkan di `PaperStopResolver` yang dipakai semua jalur. Alasannya:
cabang `Reset`/`Keep` belum punya konsumen nyata — menambah objek keputusan tanpa konsumen hanya
menghasilkan kode mati. Invariannya sendiri diuji lewat sweep.

### 31.3 P4 — `atrPct` diteruskan di jalur beli manual

`atrPctAt` adalah fungsi `private` di `PaperAutoTradeCoordinator`, sehingga `TradingViewModelSync`
tidak bisa memakainya dan mengirim `atrPct = 0.0` untuk setiap posisi hasil beli manual. Karena
`0.0` berarti "ATR tidak diketahui", mesin jatuh ke ambang BE/trailing persen tetap — dan nilai nol
itu ikut tersimpan ke disk, jadi kerusakannya persisten.

Perbaikan: rumusnya diekstrak ke `engine/scalping/PaperAtr.kt` (`PaperAtr.of(atr, referencePrice)`),
dipakai `atrPctAt` **dan** jalur beli manual. Ini sekaligus efek samping perbaikan P3: nilai
`atrPct` dikirim bersamaan dengan `force = isDca`.

### 31.4 Uji & kontrol negatif

Uji baru: `app/src/test/java/agu/analys/engine/scalping/PaperStopResolverTest.kt` (16 kasus) —
lantai 5,8128%, SL sinyal 2% dilebarkan, SL tepat di lantai dianggap memenuhi, skenario P3
(entry 94 / SL 95), sweep `resolve` atas 5 entry × 7 SL, penjaga ATR, dan penjaga
`ScalpingConfig.MIN_FRICTION_MULTIPLE >= 6.0`.

Kontrol negatif: pelebaran lantai dimatikan (syarat `signalSl < entry` dan `signalSl <= floorPrice`
dihapus dari `resolve`) + `PaperAtr.of` dibuat tanpa penjaga ⇒ **4 dari 16 uji GAGAL**, tepat pada
penjaga masing-masing perbaikan.

### 31.5 Laporan

`docs/LAPORAN-batch3-paper-safety-2026-10-16.md`

---

## 32. Batch 3c rehidrasi lintas bursa — P1 (2026-10-16)

Sumber: `docs/bugs/temuan_bugs_paper_trade.md`. Commit ketiga (terakhir) dari batch 3.

Perbaikan mendarat di commit **`e33b238`**. Dengan ini **ketiga temuan kritis** P1, P2, P3 tertutup.

### 32.1 Masalah

Rehidrasi hanya membaca bursa **aktif**:

```kotlin
val exchange = prefs.marketDataSource.name          // hanya bursa AKTIF
val symbols = positionStore.getPaperEngineSymbols(isReal = false, exchange = exchange)
```

Rantainya: posisi paper di Indodax (mesin hanya di memori, `auto_sell_enabled = false` karena
diambil alih engine) → pengguna pindah ke Tokocrypto → app di-kill → dibuka lagi (bursa aktif
Tokocrypto) → rehidrasi hanya membaca partisi `tokocrypto_sim_*` ⇒ mesin Indodax **tidak pernah
dibangun**. `setMarketDataSource` juga tidak memicu rehidrasi, jadi **kembali ke Indodax pun tidak
memulihkan apa pun**. Polling 10 detik menambah masalah: `getAllActiveTrailingSymbols` mensyaratkan
`trailing_enabled = true` sedangkan engine paper mematikannya, dan
`PaperAutoTradeRuntime.activeSymbols(ex)` juga hanya bursa aktif.

Akibatnya posisi itu tidak pernah punya SL, TP, BE, trailing, maupun time-stop — **selamanya** —
sementara `countActivePaperPositions()` (global, tanpa bursa) tetap menghitungnya sehingga ikut
memakan satu dari tiga slot auto-buy.

### 32.2 Perbaikan

1. **Enumerasi lintas bursa.** `trading/PaperEnginePosition.kt` (baru) +
   `SpotPositionStore.getPaperEnginePositions(isReal)`: menyisir semua key berakhiran
   `_paper_engine`, mem-parse `"${exchange}_${real|sim}_${symbol}"`, dan mengembalikan pasangan
   bursa + simbol. Kuncinya sudah memuat bursa, jadi tidak perlu tahu bursa mana yang aktif.
2. **Resolver murni** `engine/scalping/PaperRehydrateScope.kt` — `decide(storedExchanges,
   activeExchange)`: semua bursa tersimpan ikut, bursa aktif **didahulukan**, sisanya alfabetis
   supaya deterministik.
3. **Rehidrasi mengiterasi seluruh cakupan.** `rehydratePaperAutoExit` membangun target
   `(exchange, symbol)` dari `decide(...)`, bukan dari satu bursa.
4. **Ganti bursa memicu rehidrasi.** `initSubscriptionsAndPolling` mengumpulkan aliran
   `marketDataSource` dan memicu rehidrasi saat nilainya berubah (emisi pertama dilewati karena
   sudah ditangani rehidrasi awal), lalu `checkAndStopTrailingServiceIfEmpty()`.
5. **Polling menemukan simbol dari disk.** `startTrailingPolling` menambahkan
   `getPaperEngineSymbols(isReal = false, exchange = currentEx)` — `PaperAutoTradeRuntime` kosong
   setelah restart sampai rehidrasi selesai, jadi tanpa ini posisi paper bisa terlewat satu putaran.
6. **Peringatan bursa non-aktif.** Setelah rehidrasi, bursa yang tidak aktif dicatat eksplisit di
   log berikut alasannya.

### 32.3 Batas yang jujur — perbaikan ini memulihkan proteksi, bukan menghidupkannya

Mesin exit untuk bursa non-aktif **dibangun**, tetapi belum bisa dievaluasi: kedua pemanggil
`evaluatePaperAutoExit` (`TradingViewModel.kt:157` dan `TradingViewModelSync.kt:669`) memakai
`prefs.marketDataSource.name`, dan harga hanya tersedia untuk bursa yang sedang dipakai.

Jadi yang berubah:

| | Sebelum | Sesudah |
|---|---|---|
| Posisi di bursa non-aktif saat ini | Tidak terlindungi | Tidak terlindungi (sama) |
| Kembali ke bursa itu | Mesin **tetap kosong** — posisi menggantung permanen | Mesin sudah dipulihkan, proteksi langsung hidup |
| Progres FSM (BE/trailing/time-stop) | Hilang | Dipulihkan |
| Slot `maxConcurrentPositions` | Terpakai tanpa terlihat | Terpakai, tetapi ada peringatan di log |

Klaim audit bahwa posisi itu "menggantung **permanen**" memang benar dan itulah yang ditutup;
tetapi jangan dibaca sebagai "posisi di bursa non-aktif kini terlindungi otomatis".

### 32.4 Uji & kontrol negatif

Uji baru:

- `app/src/test/java/agu/analys/engine/scalping/PaperRehydrateScopeTest.kt` (7 kasus) — urutan bursa,
  bursa aktif di depan, duplikat/spasi/huruf kecil, bursa aktif di luar daftar, daftar kosong.
- `app/src/test/java/agu/analys/trading/SpotPositionStorePaperEngineScopeTest.kt` (6 kasus,
  Robolectric) — enumerasi lintas bursa, filter `real`/`sim`, hanya `HOLDING`, hanya flag
  `_paper_engine` aktif.

Kontrol negatif: `PaperRehydrateScope.decide` dikembalikan ke "hanya bursa aktif" + filter bursa
di-hardcode ke `tokocrypto` di `getPaperEnginePositions` ⇒ **8 dari 13 uji GAGAL**.

### 32.5 Laporan

`docs/LAPORAN-batch3-rehidrasi-2026-10-16.md`

---

## 33. Batch 4 — J7: radar jual ikut basis FSM (2026-10-16)

Sumber: `docs/bugs/temuan_bugs_jurnal_pnl.md` butir **J7**. Ini satu-satunya temuan sisa audit yang
menyentuh **permukaan uang riil**: kartu yang paling sering dibaca pengguna tepat sebelum menekan
tombol jual.

Perbaikan mendarat di commit **`d1097aa`**.

### 33.1 Masalah

`ui/components/detail/RadarSellSection.kt` menghitung sendiri, terpisah dari `FeeSchedule`:

```kotlin
val sellFeeIdr   = grossSellValueIdr * (activeFeePct / 100.0)   // hanya fee jual
val costBasisIdr = activeSellQty * effectiveBuyPrice            // TANPA fee beli
val netProfitIdr = netReceivedSellIdr - costBasisIdr            // tanpa slippage
```

Lalu `SellCalculationCard` menulis **"JUAL UNTUNG (PROFIT) … sudah bersih fee & pajak"** di atas
angka tersebut.

| Pair (taker) | Friksi di kartu | Friksi FSM | Selisih |
|---|---|---|---|
| Tokocrypto IDR | 0,4322% | 0,8186% | **0,3864 pp** |
| Tokocrypto USDT | 0,4044% | **0,9735%** | **0,5691 pp** |

> **Koreksi angka audit.** Dokumen temuan menulis friksi FSM Tokocrypto USDT sebagai **0,8226%**
> (selisih 0,4182 pp). Angka itu tidak cocok dengan rumus mana pun: bentuk aditif memberi 0,9688%
> dan bentuk multiplikatif — yang dipakai `FeeSchedule` dan sudah terdokumentasi sebagai 0,9735% di
> §24 dan `NetPnlParityTest` — memberi **0,9735%**. Selisih yang benar adalah **0,5691 pp**, bukan
> 0,4182 pp. Arah temuan tidak berubah; hanya angkanya yang perlu dikoreksi supaya tidak dikutip
> sebagai kebenaran.

Akibatnya kartu mengklaim untung pada kenaikan harga **+0,50%** (kartu lama: +0,0656%), padahal
net-nya **rugi −0,3160%**.

### 33.2 Akar penyebab — §24 memigrasi *evaluator*, bukan *kartunya*

§24.3 mencatat "Radar jual & kartu posisi" sudah diseragamkan. Yang benar-benar dipindahkan saat itu
adalah **`engine/sell/SellSignalEvaluator.kt`** (evaluator ambang `READY_TO_SELL`) dan
`model/PositionContext.kt`. Judul `NetPnlParityTest` sendiri menyebut "radar jual
(`SellSignalEvaluator`)" — jadi "radar jual" di §24 bermakna *evaluator*, sedangkan **kartu UI**
`RadarSellSection` + `SellCalculationCard` tidak pernah ikut.

Ikut tidak akurat: §24.1 menulis basis lama radar sebagai "fee jual **maker** saja". Kode hari ini
memakai `activeFeePct`, yaitu tarif sesuai toggle Limit·Maker / Instant·Taker — bukan maker tetap.

### 33.3 Perbaikan

1. **Resolver murni baru** `trading/SellEstimate.kt` — `SellEstimate` +
   `SellEstimateResolver.resolve(quantity, entryPrice, sellPrice, buyFeePct, sellFeePct, slippagePct)`.
   Seluruh angkanya mendelegasi ke `FeeSchedule.netSellValue` dan `FeeSchedule.costBasisNet`, jadi
   basisnya mustahil menyimpang dari FSM lagi.
2. **Basis biaya yang dipakai:**
   - fee **jual** = tarif order type yang dipilih pengguna (maker/taker) — itu yang benar-benar
     dibayar pada kaki jual yang sedang dipricilkan;
   - fee **beli** = tarif **taker**. Order beli sudah terjadi dan jenisnya tidak diketahui di sini,
     jadi dipakai asumsi terburuk yang juga basis FSM (`netProfitPctFor` default taker);
   - **slippage** dua sisi, mengikuti konvensi `FeeSchedule` (slippage tetap dibebankan walau maker).
3. **`RadarSellSection` tidak menghitung apa pun lagi**; `SellCalculationCard` menerima satu objek
   `SellEstimate` (sebelumnya 8 parameter angka terpisah).
4. **Label diperbaiki** supaya klaimnya bisa diverifikasi:
   - `"Potongan Biaya & Pajak (0,43%)"` → `"Potongan Biaya, Pajak & Slippage"` + sub-label yang
     menyebut komponennya satu per satu (`"Beli taker 0.22% + Jual taker 0.43% + Slippage 2×0.08%"`);
   - `"Total Modal Pembelian"` → `"Total Modal + Biaya Beli & Slippage"`;
   - `"sudah bersih fee & pajak"` → `"setelah biaya beli, biaya jual, pajak & slippage"`;
   - saat modal belum diketahui, sub-label berkata jujur `"(biaya beli belum diketahui)"` alih-alih
     menampilkan persen tunggal yang seolah sudah lengkap.
5. **`slippagePct` disalurkan** dari `scalpingRuntimeConfig.slippagePct`:
   `DetailChartScreen` → `WaitingEntryRadarCard` → `RadarTransactionFeeSection` → `RadarSellSection`.

Invarian yang dijaga: `(netProceeds / costBasisNet − 1) × 100` == `netProfitPct` ==
`FeeSchedule.netProfitPct` untuk tarif yang sama.

### 33.4 Efek dan selisih yang **sengaja** tersisa

| Mode | Friksi kartu (sebelum) | Friksi kartu (sesudah) | FSM |
|---|---|---|---|
| Tokocrypto IDR taker | 0,4322% | **0,8186%** | 0,8186% |
| Tokocrypto IDR maker | 0,3322% | **0,7174%** | 0,8186% |
| Tokocrypto USDT taker | 0,4044% | **0,9735%** | 0,9735% |

Pada mode **taker** kartu kini sama persis dengan FSM. Pada mode **maker** masih berbeda 0,1012 pp,
dan itu disengaja: pengguna memang memilih limit order untuk kaki jual, jadi fee maker-lah yang akan
dibayar. Yang tidak boleh lagi terjadi adalah memotong fee jual saja lalu mengklaim "sudah bersih".

Efek amnesti (kelas yang sama dengan J1) — bukan regresi:

| Bruto | Kartu lama | Kartu sekarang |
|---|---|---|
| +0,50% | **+0,0656% "UNTUNG"** | **−0,3160% RUGI** |
| Ambang pertama kali "JUAL UNTUNG" muncul | ≈ **+0,4341%** | ≈ **+0,8186%** |

### 33.5 Uji & kontrol negatif

Uji baru: `app/src/test/java/agu/analys/trading/SellEstimateTest.kt` — **17 kasus**: skenario J7,
impas, paritas `FeeSchedule` lintas rentang harga, invarian rupiah↔persen, komponen friksi,
per-bursa (Tokocrypto USDT, Indodax), mode maker, slippage dimatikan, penjaga masukan tidak valid,
dan tiga uji teks kartu.

Kontrol negatif: resolver dikembalikan ke rumus lama (hanya fee jual, tanpa fee beli, tanpa
slippage) **dan** label lama ("sudah bersih fee & pajak") dipulihkan ⇒ **13 dari 17 uji GAGAL**,
tepat pada penjaga masing-masing. Empat yang tetap lulus memang tidak menguji basis: invarian
internal rupiah↔persen (bertahan karena dihitung dari angka yang sama) dan tiga penjaga masukan
(modal tidak diketahui, harga jual nol, masukan NaN/tak hingga).

### 33.6 Laporan

`docs/LAPORAN-batch4-j7-2026-10-16.md`



---

## 34. Batch 5a — J4 & N10: jurnal memakai simbol yang dievaluasi engine (2026-10-16)

### 34.1 Masalah

Dua temuan, satu akar: **field turunan sinyal menempel pada simbol yang bukan pemiliknya.**
Audit menulisnya terpisah karena muncul di dua berkas:

- **J4** (`docs/bugs/temuan_bugs_jurnal_pnl.md` §3) — `TradingViewModel.listenToEngineSignals`
  **menimpa** `marketSymbol` yang sudah benar:
  ```kotlin
  list.add(0, signal.copy(marketSymbol = selectedPair.value.symbol))   // menimpa field engine
  signalLogRepository.recordSignal(symbol = selectedPair.value.symbol, …)
  ```
- **N10** (`docs/laporan_temuan_bugs_notifikasi.md`, baris tabel N10) — jalur beli memakai
  `engine.signalState.value` **tanpa memeriksa** `signal.marketSymbol == symbol`
  (`TradeSignalSnapshot.capture:275`, `TradingViewModelSync.kt:253-282`).

Jendelanya nyata: `selectPair()` mengganti pair lebih dulu, sedangkan `engine.signalState.value`
masih berisi hasil evaluasi pair **lama** sampai tick berikutnya. Selama jendela itu jurnal
menyimpan entry/TP1/TP2/SL/confidence **BTC** berlabel **ETH**, dan `SignalHistoryPanel.kt:74`
(filter `marketSymbol`) tidak bisa menyelamatkannya — field-nya sudah ditimpa, jadi kartu
"RIWAYAT & LOG SINYAL" untuk ETH menampilkan sinyal BTC lengkap dengan harga BTC.

### 34.2 Mengapa ini bukan sekadar salah label

Jurnal sinyal adalah **basis statistik reliability** (`SignalReliabilitySummary`) dan basis
jurnal trade. Angka pair lain yang menempel di pair ini membuat dua-duanya bohong, dan
kesalahannya tidak terlihat: barisnya rapi, hanya saja isinya milik koin lain.

### 34.3 Perbaikan

Satu resolver murni baru: `app/src/main/java/agu/analys/trading/SignalSymbolAttribution.kt`.

| Fungsi | Menegakkan |
|---|---|
| `normalize(symbol)` | `btc_idr`, `BTC/IDR`, `BTC-IDR`, `BTC IDR` → `BTCIDR` |
| `matches(requested, signalSymbol)` | **N10** — simbol kosong di salah satu sisi = **tidak cocok** ("tidak tahu" bukan "cocok") |
| `journalSymbol(signalSymbol, selected)` | **J4** — simbol engine; jatuh ke pair yang dibuka hanya bila engine tidak mengisi |
| `isRecordable(symbol)` | baris tanpa simbol tidak boleh ditulis |
| `entryPrice(signalEntryPrice, tickPrice)` | harga fallback milik **simbol jurnal**, bukan tick pair yang kebetulan dibuka |
| `attributedFields(...)` | **N10** — bila tidak cocok, confidence/TP/SL dikosongkan, harga & waktu jatuh ke transaksi nyata |

Yang berubah di pemanggil:

1. `TradeSignalSnapshot.capture` — sinyal digerbang sekali di pintu masuk
   (`signal?.takeIf { SignalSymbolAttribution.matches(symbol, it.marketSymbol) }`), sehingga
   **keempat** pemanggilnya (`TradingViewModelSync:101`, `:281`, `TradingViewModelOrders:82`,
   `:415`) terlindungi sekaligus. Field turunan sinyal (confidence, regime, pattern, alasan,
   setup/skor scalping, entry zone) ikut terbuang bila sinyal milik pair lain.
2. `TradingViewModelSync` jalur beli **real** dan **paper** — `signalPrice`, `signalConfidence`,
   `targetPrice1/2`, `stopLossPrice`, `signalTime` lewat `attributedFields`.
   `tp1`/`tp2` di jalur real **tidak** disentuh: nilainya datang dari parameter order, bukan engine.
3. `listenToEngineSignals` — memakai `journalSymbol`, berhenti bila simbol kosong, dan harga
   fallback diambil dari `dashboardTicks[journalSymbol]`.

**Yang sengaja dikosongkan, bukan dipalsukan.** Bila sinyal bukan milik simbol ini, confidence
menjadi `0` dan TP/SL menjadi `0.0` — bukan memakai angka pair lain dengan label pair ini.
`0` terbaca sebagai "tidak ada data", sedangkan angka pair lain terbaca sebagai data yang salah.

### 34.4 Uji & kontrol negatif

Uji baru: `app/src/test/java/agu/analys/trading/SignalSymbolAttributionTest.kt` — **20 kasus**:
normalisasi format simbol, `matches` (format beda/simbol beda/kosong), `journalSymbol` saat pair
berganti, `isRecordable`, `entryPrice`, dan tujuh kasus `attributedFields` (cocok, tidak cocok,
harga nol, waktu nol, `marketSymbol` kosong).

Kontrol negatif: resolver dikembalikan ke perilaku lama (`matches` selalu `true`,
`journalSymbol` mengembalikan pair yang dibuka, `entryPrice` tanpa fallback tick) ⇒
**10 dari 20 uji GAGAL**, tepat pada penjaga masing-masing. Sepuluh yang tetap lulus memang tidak
menguji atribusi: normalisasi format, `isRecordable`, `entryPrice` saat harga sinyal ada, dan
kasus "simbol sama" yang memang tidak berubah.

Angka terukur (dari XML): **68 kelas / 522 uji / 0 gagal / 1 dilewati → 69 kelas / 542 uji /
0 gagal / 1 dilewati** (+1 kelas, +20 uji).

### 34.5 Laporan

`docs/LAPORAN-batch5-j4n10-2026-10-16.md`

---

## 35. Batch 5b — J3: `SignalLogViewModel` mengikat bursa sekali saat konstruksi (2026-10-16)

### 35.1 Masalah

```kotlin
val currentExchange: String get() = prefs.marketDataSource.name      // getter, tapi…
val allSignalLogs: StateFlow<List<SignalLogEntity>> =
    signalLogRepository.getLogsByExchangeFlow(currentExchange)        // dievaluasi SEKALI
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

Parameter Room terikat saat flow dibuat (`dao.getLogsByExchangeFlow(exchange.uppercase())`), jadi
mengganti bursa **tidak** mengulang subscription. ViewModel-nya dibuat sekali di
`TradingViewModel.kt:52` dan bertahan sepanjang activity.

Dampaknya: buka app di Tokocrypto → pindah ke Indodax → buka tab "Log & Evaluasi Sinyal": seluruh
log **dan** tabel "RELIABILITAS SKOR KEYAKINAN" masih berisi data Tokocrypto, tanpa label penjelas.
Melanggar aturan isolasi exchange untuk state.

Jejak bahwa pola reaktif memang pernah dimaksudkan: `flatMapLatest` sudah di-import di berkas itu
tetapi tidak pernah dipakai.

### 35.2 Perbaikan

Bursa kini **mengalir** dari `settingsViewModel.marketDataSource` (sudah `StateFlow<MarketDataSource>`
dan diperbarui oleh `setMarketDataSource`), diteruskan ke `SignalLogViewModel` lewat konstruktor.

Pengikatannya diekstrak ke `database/SignalLogExchangeBinder.kt` supaya bisa diuji tanpa Android:

```kotlin
fun <T> bind(exchangeNames: Flow<String>, query: (String) -> Flow<T>): Flow<T> =
    exchangeNames.map { it.trim().uppercase() }.distinctUntilChanged().flatMapLatest { query(it) }
```

- `flatMapLatest` — subscription lama dibatalkan, query dijalankan ulang untuk bursa baru.
- `distinctUntilChanged` — nama yang sama berturut-turut tidak membuka query Room baru.
- `trim().uppercase()` — sepakat dengan DAO yang juga memanggil `uppercase()`.

`currentExchange` kini membaca `exchangeFlow.value.name`, jadi bursa punya **satu** sumber, bukan
dua (getter `prefs` + flow).

### 35.3 Uji & kontrol negatif

Uji baru: `app/src/test/java/agu/analys/database/SignalLogExchangeBinderTest.kt` — **7 kasus**:
query untuk bursa awal, ganti bursa mengulang subscription, nilai mengikuti bursa terakhir, nama
sama berturut-turut tidak mengulang subscription, pembakuan nama (huruf kecil, spasi), dan tiga
pergantian berturut-turut.

Kontrol negatif: binder dikembalikan ke perilaku lama (`take(1)` — bursa dibaca sekali, tanpa
pembakuan nama) ⇒ **5 dari 7 uji GAGAL**. Dua yang tetap lulus memang tidak membedakan reaktivitas:
"query dijalankan untuk bursa awal" dan "nama sama berturut-turut tidak mengulang subscription" —
keduanya juga benar pada versi lama, jadi keduanya bukan bukti perbaikan.

Angka terukur (dari XML): **69 kelas / 542 uji → 70 kelas / 549 uji**, 0 gagal, 1 dilewati.

### 35.4 Yang tidak ikut berubah (batas perbaikan)

- **`SignalLogScreen` tidak diuji otomatis.** Perbaikan diuji pada pengikat alirnya, bukan pada
  komposisi layarnya.
- **Hanya jurnal sinyal.** Pola "bursa dibaca sekali" kemungkinan ada di ViewModel lain; perbaikan
  ini tidak menyapu semuanya — hanya yang menjadi temuan J3.

### 35.5 Laporan

`docs/LAPORAN-batch5-j3-2026-10-16.md`

---

## 36. Batch 5c — P6: dua jalur pemantauan menghasilkan exit berbeda (2026-10-16)

### 36.1 Masalah

```kotlin
// TradingViewModel.kt — pair SEDANG DIBUKA: jendela momentum DIKIRIM
runCatching { evaluatePaperAutoExit(symbol, currentEx, price, recentCandles.takeLast(15)) }

// TradingViewModelSync.kt — posisi di LATAR: jendela momentum KOSONG
runCatching { evaluatePaperAutoExit(tick.symbol, currentEx, tick.price) }
```

`EARLY_PROFIT` hanya mungkin terjadi bila jendela tidak kosong
(`PaperExitResolver.onBar:635` — `window14.isNotEmpty()`). Jadi trade yang sama:

- keluar `EARLY_PROFIT` bila kebetulan pengguna membuka pair-nya,
- keluar `TP2` bila tidak.

Jurnal lalu berisi dua alasan berbeda untuk keputusan yang seharusnya identik, dan app tidak
sebanding dengan `tools/scalp_replay.py` — padahal paritas itulah alasan jurnal ini ada.

### 36.2 Perbaikan

**Jendela momentum tidak lagi dikirim pemanggil.** `evaluatePaperAutoExit` kini membacanya sendiri
dari satu sumber: cache M1 `MtfCacheManager`, digerbang oleh `trading/PaperExitMomentumWindow.kt`.

| Bagian | Keputusan |
|---|---|
| Sumber | `MtfCacheManager.getCachedCandles(symbol, Timeframe.M1, exchange)` — sama untuk kedua pemanggil |
| Syarat segar | **mengikuti `MtfCacheManager.isCacheValid(M1)`**: ≥20 bar, umur ≤180 detik — bukan ambang baru |
| Ukuran | `takeLast(15)` — sama dengan jendela replay (`bars.subList(max(0, j-14), j+1)`) |
| Bila tidak segar | jendela kosong → engine menahan diri dari early-profit |

Kedua pemanggil kini memanggil `evaluatePaperAutoExit(symbol, currentEx, price)` — **identik**.
Ini sengaja membuat `EARLY_PROFIT` lebih jarang di jalur foreground: persis itulah inti temuan.
Yang dijamin bukan "jendela selalu ada", melainkan **"kedua jalur mendapat jendela yang sama"**.

### 36.3 Uji & kontrol negatif

Uji baru: `app/src/test/java/agu/analys/trading/PaperExitMomentumWindowTest.kt` — **12 kasus**:
konversi stempel detik/milidetik, `isFresh` (segar, <20 bar, kosong/null, >180 detik, batas tepat
180.000 ms, stempel detik), dan `resolve` (ambil 15 terakhir, cache basi, kurang bar, null, tidak
pernah lebih dari 15).

Kontrol negatif: aturan kesegaran dihapus (`isFresh` selalu `true`, `resolve` meneruskan apa
adanya) ⇒ **5 dari 12 uji GAGAL**. Tujuh yang tetap lulus memang tidak menguji aturan kesegaran:
konversi stempel, "cache segar layak dipakai", "jendela diambil dari cache segar", "null
diperlakukan seperti kosong", dan "tidak pernah lebih dari 15".

Angka terukur (dari XML): **70 kelas / 549 uji → 71 kelas / 561 uji**, 0 gagal, 1 dilewati.

### 36.4 Yang belum ikut berubah (dicatat, bukan diklaim)

Bagian kedua P6 — `peak` di jalur latar tertinggal dari high M1 karena bar sintetis dibuat dengan
OHLC identik dan sampling 10 detik — **tidak** diperbaiki. Ini bukan cabang kode yang berbeda
antar pemanggil, melainkan keterbatasan sumber data: tidak ada umpan intrabar, jadi `peak` hanya
bisa setinggi harga tick yang kebetulan tersampel. Memaksa `bar.high` dari candle M1 terakhir
berarti menghitung ulang bar yang sudah dihitung mesin, dan berisiko menggandakan
peak/time-stop/ATR — itu perubahan berbeda yang perlu temuan dan uji sendiri.

### 36.5 Laporan

`docs/LAPORAN-batch5-p6-2026-10-16.md`

---

## 37. Batch 5d — P5: kartu FSM di layar simulasi dihapus tick pair lain (2026-10-16)

### 37.1 Masalah

```kotlin
// PaperAutoTradeCoordinator.kt — dievaluasi untuk SETIAP simbol pada loop polling
val machine = PaperAutoTradeRuntime.machineFor(exchange, symbol)
if (machine == null) {
    // Tidak ada posisi paper berjalan -> pastikan kartu FSM kosong.
    publishPaperExitSnapshot(null)   // ← menimpa snapshot SEMUA posisi
    return null
}
```

`publishPaperExitSnapshot` menulis ke **satu** `MutableStateFlow` global, sementara
`evaluatePaperAutoExit` dipanggil untuk setiap simbol yang mendapat tick
(`TradingViewModelSync.kt`) **dan** untuk pair yang dipilih (`TradingViewModel.kt`).

Dampaknya: pengguna membuka detail BTC sementara posisi paper-nya ada di AMP/USDT — kartu FSM di
layar simulasi berkedip hilang. Persis kebingungan "satu state untuk dua tampilan" yang dulu
membuat fitur §14/§15 dibuang.

### 37.2 Perbaikan

Snapshot kini **per posisi**:

- `_paperExitSnapshots: MutableStateFlow<Map<String, PaperExitSnapshot>>`, berkunci
  `PaperAutoTradeRuntime.key(exchange, symbol)` — kunci yang **sudah dipakai** mesin, bukan
  format baru, supaya tidak ada dua definisi kunci posisi.
- `publishPaperExitSnapshot(exchange, symbol, snapshot)` — `null` menghapus **entri posisi itu
  saja**.
- Reduksinya diekstrak ke `trading/PaperExitSnapshots.kt` agar bisa diuji tanpa Android.

Konsumen:

| Layar | Sebelum | Sekarang |
|---|---|---|
| `TradeSimulationScreen` | membaca snapshot global | `paperSnapshotAt(marketSource.name, selectedPair.symbol)` — posisi pair yang sedang dibuka |
| `DetailChartScreen` | membaca snapshot global (hanya pemicu hitung ulang) | membaca peta per posisi; kartunya tetap lewat `paperSnapshotFor` |

### 37.3 Uji & kontrol negatif

Uji baru: `app/src/test/java/agu/analys/trading/PaperExitSnapshotsTest.kt` — **10 kasus**: bentuk
kunci runtime (`TOKOCRYPTO|BTCIDR`, pemisahan bursa untuk simbol sama), publish menyimpan di kunci
posisi, **simbol tanpa mesin tidak menghapus snapshot simbol lain** (inti P5), memperbarui satu
posisi tidak menyentuh posisi lain, `null` pada kunci yang tidak ada mengembalikan peta yang sama,
kunci sama ditimpa, dua bursa untuk simbol sama terpisah, dan dua kasus `forPosition`.

Kontrol negatif: `publish` dikembalikan ke perilaku lama (`null` mengosongkan **seluruh** peta,
snapshot baru menggantikan peta) ⇒ **4 dari 10 uji GAGAL**. Enam yang tetap lulus memang tidak
menguji isolasi antar posisi: dua bentuk kunci, "snapshot tersimpan di bawah kunci posisinya",
"kunci sama ditimpa", dan dua kasus `forPosition`.

Angka terukur (dari XML): **71 kelas / 561 uji → 72 kelas / 571 uji**, 0 gagal, 1 dilewati.

### 37.4 Perubahan perilaku yang perlu diketahui

Layar simulasi kini menampilkan FSM **pair yang sedang dibuka**, bukan "posisi paper mana pun yang
terakhir diterbitkan". Bila pair yang dibuka tidak punya posisi paper, kartunya kosong — itu benar,
dan sama dengan perlakuan halaman detail. Sebelum perbaikan kartunya terisi, tetapi isinya bisa
milik pair lain (atau kosong secara acak).

### 37.5 Laporan

`docs/LAPORAN-batch5-p5-2026-10-16.md`

---

## 38. Batch 5e — J6 & N11: klaim statistik historis hardcoded di UI diganti deskripsi bergate sampel (2026-10-16)

### 38.1 Masalah

```kotlin
// SignalLogScreen.kt — kartu "RELIABILITAS SKOR KEYAKINAN", tanpa syarat apa pun
Text("Skor keyakinan tinggi (≥80%) menunjukkan rasio kemenangan tertinggi dan drawdown terendah pada eksekusi real-time.")
```

Kalimat ini melanggar aturan repo sendiri ("jangan menulis di UI bahwa sinyal punya
winrate/expectancy historis tanpa data cukup"), dan yang membuatnya berbahaya:

- **N11** — tier ≥80 nyaris mustahil terisi sinyal scalping pra-entry (`ScalpingMtfEvaluator`
  cap ≤59 kecuali READY). Jadi tier itu mengukur "apakah sudah READY", bukan kualitas berjenjang.
- Landasan statistiknya baru bisa dipakai setelah **J1** (PnL kotor → bersih) dan **J2**
  (predikat W/L tumpang tindih) — keduanya sudah ditutup di batch sebelumnya.
- Tanpa syarat: data kosong, sampel 1, atau semua tier kosong — teks tetap tercetak.

### 38.2 Perbaikan

Resolver murni baru `model/ConfidenceTierNote.kt`:

| Kondisi | Keluaran |
|---|---|
| `totalLogs == 0` | `null` — **tidak ada teks yang dicetak** (intinya J6) |
| `completedLogs < 10` (sama dengan `SetupStats.MIN_RELIABLE_SAMPLE`) | catatan kuning "Sampel belum cukup: N sinyal selesai dari M total (minimal 10)…", `isReliable = false` |
| sampel cukup | catatan netral: "Basis: N sinyal selesai (TP/SL tercapai) dari M total di bursa ini. Win rate hanya dari sinyal yang sudah selesai; PnL bersih fee & slippage." |
| tier tanpa sinyal | ditambahkan fakta data: "K dari 3 tier belum terisi sinyal." — N11 disebut sebagai fakta, bukan disembunyikan |

Ambil `MIN_RESOLVED_SAMPLE` dari `SetupStats` — satu definisi "sampel cukup" untuk seluruh layar
statistik, bukan angka kedua.

`SignalLogScreen` kini: `ConfidenceTierNoteResolver.resolve(summary)` → bila `null`, bar info
tidak dirender sama sekali; bila tidak, teksnya dari resolver dan berwarna kuning saat sampel
belum cukup.

### 38.3 Uji & kontrol negatif

Uji baru: `app/src/test/java/agu/analys/model/ConfidenceTierNoteTest.kt` — **11 kasus**:
tanpa data (dua bentuk), sampel kurang, semua TRACKING, tepat di ambang, teks basis menyebut
angka & "fee & slippage", tier kosong (2 dari 3, 1 dari 3, semua terisi), teks tidak lagi memuat
frase klaim lama, dan konsistensi threshold.

Kontrol negatif: resolver dikembalikan ke perilaku lama (tanpa gerbang sampel, teks klaim asli
"rasio kemenangan tertinggi… real-time", tanpa fakta tier) ⇒ **8 dari 11 uji GAGAL**. Tiga yang
tetap lulus memang tidak membedakan versi: threshold sama dengan konstanta, "tepat di ambang"
relatable di kedua versi, dan "semua tier terisi" tidak menyebut tier kosong di kedua versi.

Angka terukur (dari XML): **72 kelas / 571 uji → 73 kelas / 582 uji**, 0 gagal, 1 dilewati.

### 38.4 Batas perbaikan

- **Hanya satu kalimat.** Tabel tier sendiri, dan cap confidence `ScalpingMtfEvaluator`
  (akar N11), **tidak** diubah — keduanya perlu temuan & uji tersendiri.
- `SignalLogScreen` tidak diuji otomatis; yang diuji resolver-nya.

### 38.5 Laporan

`docs/LAPORAN-batch5-j6n11-2026-10-16.md`

---

## 39. Batch 5f — J5: fallback lintas bursa di jurnal trade dihapus (2026-10-16)

### 39.1 Masalah

Dua jalur di `TradeHistoryRecorder` jatuh ke query **tanpa filter exchange** bila query
berversi exchange kosong:

```kotlin
// recordSell
dao.getActiveHoldingForSymbolAndExchange(symbol, isReal, exchange)
    ?: dao.getActiveHoldingForSymbol(symbol, isReal)      // ← SEMUA bursa

// processPriceTick
dao.getHoldingRecordsByExchange(exchange)
    .filter { it.symbol == normSymbol }
    .ifEmpty { dao.getHoldingRecords().filter { it.symbol == normSymbol } }  // ← SEMUA bursa
```

Tokocrypto melayani **USDT + IDR**, Indodax **hanya IDR** (AGENTS.md §"Batasan pasar"), jadi
`BTCIDR`/`ETHIDR`/`XAUTIDR` ada di kedua bursa sekaligus — yang berbeda pasarnya, bukan simbolnya.
Akibatnya:

1. jual `BTCIDR` di Indodax menutup record HOLDING `BTCIDR` milik **Tokocrypto**, dengan
   `sellPrice`/`customPnlIdr` dari bursa Indodax;
2. `processPriceTick`: harga bursa A memperbarui `peakPriceDuringHold`/
   `troughPriceDuringHold`/`maxProfitPct` record HOLDING milik bursa B ⇒ statistik
   MFE/drawdown per posisi tercemar.

Pola yang benar sudah ada di `SignalLogRepository.processPriceTick` — query berversi exchange,
tanpa fallback, dengan komentar eksplisit. Batch ini meniru pola itu di jurnal trade.

### 39.2 Perbaikan

Resolver murni baru `database/HoldingResolution.kt` (fungsi `suspend` karena DAO `suspend`):

| Fungsi | Menegakkan |
|---|---|
| `resolveForSell` | record dicari lewat uuid bila ada (uuid kosong = tidak ada, bukan short-circuit miss), **selain itu hanya** query berversi exchange; kosong berarti kosong |
| `holdingsForPriceTick` | hanya record HOLDING milik bursa yang sama, filter simbol abaikan kapitalisasi; tidak ada percabangan "semua bursa" |

Konvensi lambda: exchange `""` berarti "tanpa filter bursa" — **tidak pernah** dipakai versi ini;
konvensi itu hanya untuk kontrol negatif.

`processBatchPriceTicks` sudah berversi exchange sejak awal — tidak diubah.

### 39.3 Uji & kontrol negatif

Uji baru: `app/src/test/java/agu/analys/database/HoldingResolutionTest.kt` — **11 kasus**:
pencarian uuid, uuid kosong diperlakukan seperti tidak ada, uuid miss tidak jatuh ke bursa lain,
**bursa ini kosong → null** (inti J5 bagian 1), `isReal` dibedakan, dan enam kasus tick
(termasuk **bursa ini kosong → kosong**, inti J5 bagian 2).

Kontrol negatif: fallback dikembalikan (`?: bySymbolAndExchange(…, "")` dan
`.ifEmpty { byExchange("") … }`) ⇒ **tepat 2 dari 11 uji GAGAL** — keduanya justru penjaga inti
J5. Sembilan yang tetap lulus memang tidak membedakan versi (jalur uuid, pencocokan simbol,
abaikan kapitalisasi, record bursa sendiri).

Angka terukur (dari XML): **73 kelas / 582 uji → 74 kelas / 593 uji**, 0 gagal, 1 dilewati.

### 39.4 Yang berubah secara perilaku

- Jual tanpa uuid yang dulu **kebetulan** menutup record bursa lain kini mencatat penjualan
  tanpa menutup record apa pun (record HOLDING bursa lain tetap utuh — memang bukan milik
  bursa ini). Posisi yang tidak sengaja tertutup dulu sekarang tetap terbuka sampai benar-benar
  dijual di bursanya sendiri.
- `processPriceTick` untuk simbol tanpa posisi di bursa ini tidak lagi memperbarui posisi
  bursa lain — `peakPriceDuringHold` per posisi hanya bergerak pada harga bursanya sendiri.

### 39.5 Laporan

`docs/LAPORAN-batch5-j5-2026-10-16.md`

---

## 40. Sinkronisasi dokumen temuan — 2026-10-16 (dokumen saja, nol perubahan kode)

### 40.1 Masalah

Status perbaikan **tercercecer di beberapa tempat**: badan `docs/bugs/*.md` (banyak butir sudah
ditutup tapi masih dituliskan sebagai temuan hidup dengan blok "✅ DIPERBAIKI"), `AGENTS.md`, dan
`docs/BELUM-DIKERJAKAN-2026-10-14.md`. Akibatnya **dokumen saling bertentangan**:

- `temuan_bugs_jurnal_pnl.md` menulis "J4 + N10 (batch 5a) **sudah DIPERBAIKI**" sekaligus
  "**J8–J10 masih hidup**" — padahal baris J10 mengakui J10 = N10.
- `AGENTS.md` menulis "Masih hidup: N6, N8–N17" — luber, karena N10 sudah ditutup dan N11 hanya
  sebagian.
- `temuan_bugs_paper_trade.md` (dan `temuan_bugs_jurnal_pnl.md`) kehilangan baris riwayat
  batch 3c/5a/5b/5e/5f, padahal status di header sudah menyebutnya.

Agen berikutnya akan membuang waktu membaca ulang kode untuk memastikan status yang sebenarnya
sudah diverifikasi owner.

### 40.2 Yang dikerjakan

1. **Pangkas butir yang sudah tertutup** dari badan ketiga dokumen; rangkum di tabel
   "Sudah ditutup" (commit + pointer `docs/LAPORAN-*.md`). Yang tersisa di badan dokumen hanya
   temuan yang **masih hidup** atau **sebagian**.
2. **Verifikasi ulang ke kode** `patch` @ `547048d` untuk setiap butir yang tersisa, lalu tulis
   nomor baris yang benar (bukan yang basi).
3. **Perjelas J10** jadi **sebagian**, dan tuliskan sisa yang ditemukan: `armPaperExitEngine` pada
   jalur beli paper manual masih mengoper SL/TP mentah dari `engine.signalState.value`.
4. **Hapus 3 stub DEPRECATED**: `workplan.md`, `checkpoint.md`, `checkpoint_progres.md`.
5. **`AGENTS.md`**: `docs/bugs/` jadi sumber kebenaran status + aturan eksplisit "agen tidak perlu
   cek kode lagi untuk memastikan status, kecuali akan memperbaiki butir itu atau ada commit baru
   setelah `547048d`". Ditambah aturan turunan baru di § J4 & N10 (nilai turunan sinyal untuk
   **keputusan** wajib teratribusi, bukan hanya untuk catatan).

### 40.3 Status akhir (21 tertutup · 3 sebagian · 13 masih hidup)

| Area | Ditutup | Sebagian | Masih hidup |
|---|---|---|---|
| Notifikasi | N1–N5, N7, N10 | **N11** | N6+N17, N8, N9, N12, N13, N14, N15, N16 |
| Jurnal & P&L | J1–J7 | **J10** | J8, J9 |
| Paper trading | P1–P5 | **P6** | P7, P8 |

### 40.4 Yang TIDAK dikerjakan

Nol perubahan kode. Tidak ada batch perbaikan baru di sini — dokumen hanya menyatakan kenyataan.
Semua butir yang masih hidup **tetap hidup**, dan urutannya ada di § "Rekomendasi" tiap berkas
temuan. Angka test tidak berubah dan tidak diukur ulang (tidak ada kode yang bergerak);
`./gradlew :app:testDebugUnitTest` tidak dijalankan karena sandbox tidak punya Android SDK.

### 40.5 Laporan

`docs/LAPORAN-sync-dokumen-temuan-2026-10-16.md`

---

## 41. Batch 6a — J10 (sisa): mesin exit paper tidak lagi memakai SL/TP pair lain (2026-10-17)

### 41.1 Masalah

Batch 5a menutup J10 di **jurnal** (`capture` digerbang + `attributedFields` di kedua jalur beli),
tetapi satu jalur masih membaca `engine.signalState.value` **mentah**: beli **paper manual**.

```kotlin
// TradingViewModelSync.kt:343-350 (sebelum)
val armed = armPaperExitEngine(
    symbol = symbol, exchange = currentEx, entryPrice = effectiveEntry,
    stopLoss = engine.signalState.value.stopLoss,          // ← bisa milik pair lain
    tp1 = engine.signalState.value.targetPrice1,
    tp2 = engine.signalState.value.targetPrice2,
    atrPct = atrPct, force = isDca
)
```

Jendelanya sama dengan J4: `selectPair()` mengganti pair lebih dulu, sedangkan `signalState`
masih berisi hasil pair lama sampai tick berikutnya. Bedanya dengan jurnal — di sini nilainya
dipakai untuk **keputusan**:

- `stopLoss` disaring `PaperStopResolver.resolve` (`0 < SL < entry`), jadi SL pair lain yang jauh
  di atas entry ditolak. Tetapi SL yang kebetulan di bawah entry **dan** di bawah lantai friksi
  tetap dipakai — SL terlalu lebar.
- `tp1`/`tp2` **tidak** disaring. `bar.high >= tp1` memicu partial TP + SL ke BE; TP1 pair lain
  yang berada **di bawah** entry posisi ini bisa memicu **jual sebagian pada tick pertama**.

### 41.2 Perbaikan

Resolver murni baru `trading/PaperArmSignal.kt`:

```kotlin
fun resolve(requestedSymbol, signalSymbol, stopLoss, tp1, tp2): PaperArmParams =
    if (SignalSymbolAttribution.matches(requestedSymbol, signalSymbol))
        PaperArmParams(stopLoss, tp1, tp2)
    else
        PaperArmParams(0.0, 0.0, 0.0)
```

Nol berarti "tidak ada TP/SL dari sinyal": SL jatuh ke lantai friksi, TP tidak ada. Itu perilaku
yang benar — memakai TP pair lain dengan label pair ini tidak pernah benar. Validasi sisi harga
tetap milik `PaperStopResolver`; resolver ini hanya soal atribusi.

`TradingViewModelSync` kini membaca `engine.signalState.value` **sekali** ke `engineSignal`, lalu
meng-arm mesin lewat `PaperArmSignal.resolve(...)`.

### 41.3 Uji & kontrol negatif

Uji baru: `app/src/test/java/agu/analys/trading/PaperArmSignalTest.kt` — **8 kasus**: simbol sama
meneruskan nilai, simbol beda mengosongkan ketiganya (inti), simbol sinyal kosong, simbol diminta
kosong, format simbol berbeda, nilai nol tetap nol, SL negatif tidak divalidasi di sini, dan
atribusi tidak bergantung bursa.

Kontrol negatif: resolver dikembalikan ke perilaku lama (nilai mentah tanpa cek kepemilikan) ⇒
**tepat 3 dari 8 uji GAGAL** — ketiganya penjaga kepemilikan. Lima yang tetap lulus memang tidak
membedakan versi (simbol sama, nilai nol, SL negatif, bursa).

Angka terukur (dari XML): **74 kelas / 593 uji → 75 kelas / 601 uji**, 0 gagal, 1 dilewati.

### 41.4 Batas

- Jalur **auto-buy** tidak diubah: sinyalnya berasal dari transisi untuk `pair.symbol` itu sendiri.
- Pemanggilan `armPaperExitEngine` di `TradingViewModelSync` (wiring) tidak diuji otomatis —
  yang diuji resolver-nya. Bila wiring dikembalikan ke nilai mentah, uji ini tidak akan gagal.

### 41.5 Laporan

`docs/LAPORAN-batch6-j10-2026-10-17.md`

---

## 42. Batch 6b — J9 (N9): default `?: 75` dihapus dari `recordBuy` (2026-10-17)

### 42.1 Masalah

```kotlin
// TradeHistoryRecorder.kt:61 (sebelum)
signalConfidence: Int = snapshot?.confidenceScore ?: 75,
```

Bila snapshot tidak ada dan pemanggil tidak mengirim skor, jurnal menulis **75** — angka yang
tidak pernah dihasilkan siapa pun. Pola yang sama dengan pelanggaran B1 (mengarang data agar baris
terlihat lengkap): pembaca tidak bisa membedakan "keyakinan 75" dari "tidak ada data".

**Latent** — kedua pemanggil saat ini mengirim eksplisit (`TradingViewModelSync.kt:134`, `:316`),
jadi belum ada baris salah di lapangan. Tetapi jebakan itu "tidur": pemanggil ketiga akan mewarisi
angka karangan tanpa sadar.

### 42.2 Perbaikan

Resolver murni baru `database/TradeSignalConfidence.kt`:

```kotlin
fun resolve(explicit: Int?, fromSnapshot: Int?): Int = explicit ?: fromSnapshot ?: 0
```

- nilai eksplisit menang (termasuk `0`);
- tanpa eksplisit → skor snapshot;
- keduanya kosong → **0** = "tidak diketahui".

`recordBuy` kini menerima `signalConfidence: Int? = null` dan menyimpan
`TradeSignalConfidence.resolve(...)` — tidak ada lagi angka default yang dikarang di signature.

### 42.3 Uji & kontrol negatif

Uji baru: `app/src/test/java/agu/analys/database/TradeSignalConfidenceTest.kt` — **6 kasus**:
eksplisit menang, snapshot dipakai, keduanya kosong → 0 (dan **bukan 75**), `0` eksplisit
dihormati, `0` dari snapshot tetap 0, 100 diteruskan.

Kontrol negatif: `?: 0` dikembalikan ke `?: 75` ⇒ **1 dari 6 uji GAGAL** — tepat uji yang
menyatakan "tidak diketahui ≠ 75". Lima lainnya tidak membedakan versi (nilai eksplisit/snapshot).

Angka terukur (dari XML): **75 kelas / 601 uji → 76 kelas / 607 uji**, 0 gagal, 1 dilewati.

### 42.4 Batas

- Tidak ada perubahan perilaku hari ini: kedua pemanggil sudah mengirim eksplisit. Yang berubah
  hanya nilai yang akan dipakai **bila** ada pemanggil baru yang lupa.
- `recordBuy` sendiri (Room + coroutine) tidak diuji otomatis; yang diuji resolver-nya.

### 42.5 Laporan

`docs/LAPORAN-batch6-j9-2026-10-17.md`

---

## 43. Batch 6c — J8: P&L trade real memakai satu rumus `FeeSchedule` (2026-10-17)

### 43.1 Masalah

Jalur eksekusi trade real menulis rumus P&L sendiri:

```kotlin
// TradingViewModelSync.kt:56-65 (sebelum)
val netSell = effectivePrice * (1.0 - sellRate)
val cost = before.entryPrice * (1.0 + buyRate)
customPnlPct = (netSell / cost - 1.0) * 100.0
customPnlIdr = (netSell - cost) * quantity
```

Tidak ada slippage di rumus itu, sedangkan `FeeSchedule.netProfitPctFor` menambahkan slippage
**dua sisi** (default 0,08%/sisi). Untuk Tokocrypto IDR selisihnya ≈ **0,16 pp** — satu trade
menghasilkan dua angka berbeda: jurnal vs portofolio/radar jual. Itu pelanggaran aturan
"SATU rumus P&L"; pengecualian yang sah hanya `SimulationTradeStore`/`SimulationOrderEngine`.

### 43.2 Perbaikan

Resolver murni baru `trading/RealTradePnl.kt`:

```kotlin
RealTradePnlResolver.resolve(quantity, entry, exitPrice, exchange, quoteAsset, useMaker, slippagePct)
```

- `pnlIdr` dari `netSellValue − costBasisNet` (keduanya sudah termasuk fee + slippage);
- `pnlPct` dari `FeeSchedule.netProfitPctFor` — **identik** dengan `(netSell/cost − 1) × 100`,
  jadi rupiah dan persen tidak mungkin berbeda;
- input tidak valid (`quantity/entry/exitPrice ≤ 0`, NaN/Inf) → `RealTradePnl.ZERO`.

`TradingViewModelSync` kini memanggil resolver itu; dua variabel lokal (`buyRate`/`sellRate`)
dihapus.

### 43.3 Uji & kontrol negatif

Uji baru: `app/src/test/java/agu/analys/trading/RealTradePnlTest.kt` — **12 kasus**: paritas
persis dengan `FeeSchedule` untuk TK IDR / TK USDT / Indodax, selisih ≈0,16 pp terhadap rumus
lama (bukti slippage ikut), konsistensi rupiah↔persen, rugi negatif, harga sama tetap rugi
karena friksi, tiga input tidak valid, penskalaan kuantitas, dan taker≠maker.

Kontrol negatif: resolver dikembalikan ke rumus lama tanpa slippage ⇒ **5 dari 12 uji GAGAL**
(tiga paritas bursa, selisih slippage, konsistensi rupiah↔persen). Tujuh yang tetap lulus memang
tidak membedakan versi (tanda rugi, impas, input nol, penskalaan, taker/maker).

Angka terukur (dari XML): **76 kelas / 607 uji → 77 kelas / 619 uji**, 0 gagal, 1 dilewati.

### 43.4 Batas

- Hanya jalur **jual trade real**. `recordSell` di `TradeHistoryRecorder` sudah memakai
  `FeeSchedule` untuk fee; angka `customPnl*` yang dikirim sekarang konsisten dengannya.
- `SimulationTradeStore`/`SimulationOrderEngine` sengaja tidak disentuh (pengecualian yang sah).
- Wiring di `TradingViewModelSync` tidak diuji otomatis; yang diuji resolver-nya.

### 43.5 Laporan

`docs/LAPORAN-batch6-j8-2026-10-17.md`

---

## 44. Batch 6d — P8: `barsHeld` tidak lagi dihitung dua kali sesudah rehidrasi (2026-10-17)

### 44.1 Masalah

`PaperRehydrate.mergedBarsHeld` menghitung `barsHeld` dari jam dinding
(`max(stored, (now − openedAt) / 60_000)`) — menit yang berjalan saat app tertutup sudah masuk.
Lalu `PaperAutoTradeRuntime.put(..., markFirstTickAsNewBar = true)` menandai bucket menit dengan
sentinel `FIRST_TICK_BUCKET = -1L`, sehingga tick pertama sesudah rehidrasi dianggap **bar baru**
dan `PaperExitStateMachine.onBar` menaikkan `barsHeld` **sekali lagi**.

Efek: time-stop keluar **1 bar (1 menit) lebih awal** ⇒ app dan `tools/scalp_replay.py` tidak lagi
sebanding.

Yang tidak boleh disederhanakan: tick pertama itu **tetap** harus dievaluasi sebagai bar baru, kalau
tidak posisi yang sudah kehabisan waktu baru ditutup pada batas menit berikutnya.

### 44.2 Perbaikan — pisahkan "dievaluasi" dari "dihitung"

`PaperExitResolver.onBar` menerima parameter keempat:

```kotlin
fun onBar(bar, window14, isNewBar: Boolean = true, countAsNewBar: Boolean = isNewBar): PaperExitDecision?
...
if (countAsNewBar) barsHeld++
```

Gate time-stop tetap membaca `isNewBar`. Default `= isNewBar` menjaga seluruh pemanggil lama
(termasuk `PaperTradeJournalTest` dan `PaperExitFsmStateTest`) tidak berubah.

Resolver murni baru `trading/PaperFirstBar.kt`:

```kotlin
object PaperFirstBarResolver {
    fun countsAsNewBar(isNewBar: Boolean, firstTickAfterRehydrate: Boolean): Boolean =
        isNewBar && !firstTickAfterRehydrate
}
```

`PaperAutoTradeRuntime.markBarBucket` kini mengembalikan fakta (`BarBucketMark(isNewBar,
firstTickAfterRehydrate)`), bukan boolean; situs evaluasi memetakannya lewat resolver.

**Kenapa tidak ada field runtime baru:** sentinel `-1L` sudah hidup di `lastBarBucketMap` dan
**terhapus pada pemanggilan `markBarBucket` pertama**, jadi pengecualian hanya berlaku untuk satu
tick. Penanda yang bertahan akan menahan time-stop lebih lama — arah kesalahan yang berlawanan.
Konsekuensi: `clear`/`clearAll` tidak perlu diubah.

### 44.3 Uji & kontrol negatif

Uji baru `app/src/test/java/agu/analys/trading/PaperFirstBarTest.kt` — **7 kasus**: 4 resolver murni
+ 3 integrasi mesin (barsHeld 4/5 tidak naik; time-stop tetap terpicu saat 5/5; bar M1 berikutnya
tetap menambah). Dua uji integrasi itu pasangan yang saling mengunci.

`PaperAutoTradeRuntimeTest`: enam pemanggil `markBarBucket` disesuaikan ke `.isNewBar` + asersi
`.firstTickAfterRehydrate`.

Kontrol negatif dua arah:

| Kontrol | Perubahan | Hasil |
|---|---|---|
| A | `countsAsNewBar` → `isNewBar` | **3/7 GAGAL** |
| B | `if (countAsNewBar)` → `if (isNewBar)` di `onBar` | **2/7 GAGAL** |

Kontrol B membuktikan uji juga menjaga **pemakaian** resolver, bukan hanya resolver-nya.

Angka terukur (dari XML): **77 kelas / 619 uji → 78 kelas / 626 uji**, 0 gagal, 0 error, 1 dilewati.

### 44.4 Batas

- **Bukan** P6 sisa `peak` — `peak` masih hanya bergerak dari bar sintetis
  (`high = low = close = currentPrice`), bukan high M1 dari cache. Tetap temuan hidup.
- `mergedBarsHeld` tidak diubah; ia sudah benar.
- Wiring di `PaperAutoTradeCoordinator` (fungsi besar ber-`ViewModel`) tidak diuji otomatis;
  pemetaan `barMark → countAsNewBar` hanya tiga baris tanpa cabang.
- `tools/scalp_replay.py` tidak menyentuh rehidrasi, jadi tidak perlu perubahan.

### 44.5 Laporan

`docs/LAPORAN-batch6-p8-2026-10-17.md`

---

## 45. Batch 6e — P7: kontrak `clearPaperExitState` dihapus; celah sebenarnya jadi P9 (2026-10-17)

### 45.1 Masalah

`TradingViewModel.clearPaperExitState` tidak punya satu pun pemanggil
(`grep -rn "clearPaperExitState" app/src` → hanya definisi), padahal `AGENTS.md` dan laporan P4.4
menyebutnya sebagai salah satu dari empat titik pembersihan state mesin. Kontrak "pengguna
mengambil alih exit engine" tidak pernah ada implementasinya.

### 45.2 Keputusan: hapus, bukan implementasikan

Fungsi itu bukan sekadar mati — **isinya salah sebagai serah-terima**: ia melepas mesin, penanda,
dan progres FSM, tetapi **tidak** menyalakan kembali SL/TP legacy. Karena `armPaperExitEngine`
menulis `setAutoSellParams(enabled = false)` (`:385-390`; auto-buy `:585` = `enabled = !p2OwnsExit`),
memanggil fungsi itu akan menghasilkan posisi paper **tanpa exit apa pun**.

Implementasi yang benar menunggu keputusan produk: setelah SL naik ke BE/trailing, `activeStopLoss`
bisa **di atas** entry, dan `PaperStopResolver.resolve` menganggapnya tidak valid
(`signalSl < entry`). Level pengganti tidak boleh ditebak.

Jadi: `clearPaperExitState` dihapus; daftar hook di `AGENTS.md` dibersihkan menjadi
`markSold` / `finalizePaperExit` / cabang "posisi sudah tidak ada".

### 45.3 Temuan baru P9 (dicatat, belum diperbaiki)

Mematikan auto-exit **saat posisi terbuka** meninggalkan posisi tanpa SL/TP sampai restart:

- arm → `auto_sell_enabled = false`;
- sakelar hanya menulis preferensi (`PaperAutoTradeSettings.kt:136` → `AppPreferences.kt:314`),
  tidak ada yang menyalakan auto-sell kembali saat runtime;
- `paperEngineOwnsExit` jadi `false`, tetapi blok SL/TP legacy masih terkunci
  `updatedPos.isAutoSellEnabled` (`TradingViewModelAlerts.kt:244`) ⇒ hanya trailing yang tersisa;
- setelah restart, `PaperRehydrate` → `HandBackToLegacy(REASON_AUTO_EXIT_OFF)` justru **menyalakan**
  auto-sell (`:1004-1014`), jadi aksi pengguna yang sama berakhir berbeda tergantung restart.

### 45.4 Verifikasi

Penghapusan kode mati tidak punya uji perilaku; kontrol negatif tidak berlaku (tidak ada cabang
yang diaktifkan/dimatikan). Yang dipakai sebagai penjaga:

- `./gradlew testDebugUnitTest` hijau — **78 kelas / 626 uji / 0 gagal / 0 error / 1 dilewati**,
  identik dengan baseline batch 6d. Kalau masih ada pemanggil, kompilasi akan gagal;
- `grep -rn "clearPaperExitState" app/src` → **0 hasil**.

### 45.5 Batas

- P9 belum diperbaiki — jangan perlakukan "matikan auto-exit" sebagai serah-terima yang aman.
- `docs/LAPORAN-p0-*` dan `docs/LAPORAN-p44-*` sengaja tidak diubah (laporan historis).

### 45.6 Laporan

`docs/LAPORAN-batch6-p7-2026-10-17.md`

---

## 46. Berkas temuan jurnal dihapus (2026-10-17)

`docs/bugs/temuan_bugs_jurnal_pnl.md` **dihapus** atas perintah owner: seluruh J1–J10 sudah
tertutup (batch 3a–6c), jadi berkas itu hanya berisi "tidak ada temuan hidup" + peta jalur yang
nomor barisnya sudah bergeser. Jejak audit ada di laporan per batch + riwayat git
(`git log --follow -- docs/bugs/temuan_bugs_jurnal_pnl.md`).

Rujukan hidup yang diperbarui: `AGENTS.md` (daftar `docs/bugs/` + butir jurnal ditandai dihapus,
larangan membuat ulang), `docs/PANDUAN-LANJUT-AGEN.md` (tabel status), dan catatan lintas-berkas
di `docs/laporan_temuan_bugs_notifikasi.md`. Laporan historis dan seksi `workplan2.md` yang lama
**tidak** ditulis ulang — keduanya benar untuk keadaan saat ditulis.

Tidak ada perubahan kode; suite terakhir tetap 78 kelas / 626 uji / 0 gagal / 1 dilewati.
Laporan: `docs/LAPORAN-hapus-temuan-jurnal-2026-10-17.md`.

---

## 47. Batch 6f — N13 + N14 + N8: satu peristiwa, satu pengirim, satu kategori (2026-10-17)

### 47.1 Masalah (satu akar, tiga gejala)

- **N13** — laporan "order jual berhasil/gagal" memakai `sendPriceAlertNotification` (kategori
  `PRICE_ALERT`). Akibat dua arah: mematikan "Trading & Price Alerts" membungkam konfirmasi order
  uang riil; mematikan "Trailing Stop & Eksekusi" tidak mematikan notifikasi trailing.
- **N14** — "Trailing Stop Naik" dikirim dua kali: `updateSimTrailingOrder`/`updateRealTrailingOrder`
  mengirim tanpa pembatas, lalu jalur peak mengirim lagi dengan jendela 30 dtk. Cooldown tertulis
  tidak pernah mengatur apa pun; user mendengar bunyi tiap ~10 detik.
- **N8** — `onlyWhenBackground` diteruskan ke helper tetapi tidak pernah dibaca.

### 47.2 Perbaikan

`util/NotificationRouting.kt` (baru) memetakan jenis peristiwa → kategori. Helper lama **dihapus
namanya** dan diganti dua helper yang membawa kategorinya:

```kotlin
sendUserPriceAlertNotification(...)   // PRICE_ALERT — pengingat harga buatan pengguna
sendExecutionResultNotification(...)  // TRAILING_STOP — hasil eksekusi jual/trailing/batch
```

Nama generik sengaja tidak dipertahankan: dengan nama lama hilang, compiler memaksa tiap call site
memilih makna, dan pemanggil baru tidak bisa salah kategori tanpa sadar.

8 call site dipindah (6 di `TradingViewModelAlerts`, 2 di `BatchSellCoordinator`); satu tetap
`PRICE_ALERT` (alert harga pengguna, `TradingViewModelAlerts.kt:126`).

N14: `updateSimTrailingOrder`/`updateRealTrailingOrder` kini **hanya memperbarui order**. Pembatas
diangkat jadi `util/TrailingPeakThrottle.kt` (jendela 30.000 ms, batas strict `>` seperti perilaku
lama). N8: parameter dihapus dari kedua overload.

### 47.3 Uji & kontrol negatif

13 uji baru (626 → 639): `TrailingPeakThrottleTest` (6), `NotificationSourceTest` (4, pindai sumber
mengikuti pola `NotificationFeeSourceTest`), `NotificationGateTest` (+3).

| Kontrol | Perubahan | Hasil |
|---|---|---|
| A | `EXECUTION` → `PRICE_ALERT` | **3 GAGAL** |
| B | pengiriman dikembalikan di `updateSimTrailingOrder` | **1 GAGAL** |
| C | `shouldNotify` memakai `>=` | **1 GAGAL** |
| D | `onlyWhenBackground` ditambahkan lagi | **1 GAGAL** |

Angka terukur (dari XML): **78 kelas / 626 uji → 80 kelas / 639 uji**, 0 gagal, 0 error, 1 dilewati.

### 47.4 Batas

- N15 dan N16 (keduanya di `TradingForegroundService`) **belum** disentuh.
- Kanal laporan eksekusi ikut pindah ke `CHANNEL_TRAILING_ID` — disengaja: satu peristiwa tidak
  boleh muncul di dua kanal, dan konfirmasi order tidak boleh hilang.
- `setOnlyAlertOnce(true)` tidak ditambahkan; dengan pengirim tunggal + jendela 30 dtk, bunyi
  maksimal sekali per 30 detik. Kalau terlalu sering, ubah `WINDOW_MS` (satu tempat, teruji).
- Kategori dipilih di call site sehingga tidak bisa diuji perilakunya tanpa Android; penjaganya
  uji pindai sumber + uji gerbang.

### 47.5 Laporan

`docs/LAPORAN-batch6f-n13n14-2026-10-17.md`

---

## 48. Batch 6g — N15 + N16: foreground service = keep-alive, bukan pengirim push (2026-10-17)

### 48.1 Masalah

- **N15** — `updateForegroundServiceState` memakai `hasActive && isNotificationsEnabled.value`,
  dan `startService`/`forceRefresh` juga menolak jalan saat sakelar notifikasi mati. Akibatnya
  mematikan "push notifikasi" menghentikan service — keep-alive pemantauan posisi — dan
  `livePrices` kosong membuat P&L tampil 0% untuk semua posisi.
- **N16** — `AppPreferences` dibangun 7× di service, termasuk `updatePrices` yang dipanggil tiap
  tick dashboard. Konstruktornya membangun `EncryptedSharedPreferences` + `MasterKey`, dan bila
  gagal ia **menghapus seluruh preferensi**.

### 48.2 Perbaikan

- `updateForegroundServiceState`: `if (hasActive)` — sakelar notifikasi tidak lagi jadi input.
- Guard `isNotificationsEnabled` dihapus dari `startService` + `forceRefresh`.
- `updatePrice` (tunggal, tanpa pemanggil) dihapus.
- `updatePrices(context, prices, exchange)` — bursa **wajib** dari pemanggil; jalur per-tick tidak
  menyentuh `AppPreferences` sama sekali.
- Tiga konstruksi instance diganti `appPrefs` (`by lazy`). Sisa: **1** konstruksi di berkas itu.

### 48.3 Uji & kontrol negatif

5 uji pindai sumber baru (`service/ForegroundMonitorSourceTest.kt`): badan
`updateForegroundServiceState` bebas `isNotificationsEnabled`; `startService`/`forceRefresh` bebas
prefs; hitungan `AppPreferences(` = 1; `fun updatePrice(` tidak ada; `updatePrices` menerima
`exchange: String` dan bebas prefs.

| Kontrol | Perubahan | Hasil |
|---|---|---|
| A | `&& isNotificationsEnabled.value` dikembalikan | **1 GAGAL** |
| B | `AppPreferences(context)` di `updatePrices` + `updatePrice` dihidupkan | **3 GAGAL** |

Angka terukur (dari XML): **80 kelas / 639 uji → 81 kelas / 644 uji**, 0 gagal, 0 error, 1 dilewati.

### 48.4 Batas

- Service tetap menampilkan notifikasi persisten (kategori `SERVICE`, tanpa suara) — Android
  mewajibkan foreground service punya notifikasi. Itu status pemantauan, bukan push.
- N6 + N17 (butuh keputusan desain), N12, dan sisa N11 masih terbuka.
- Perubahan signature `updatePrices` aman: hanya satu pemanggil (`MarketViewModel`).

### 48.5 Laporan

`docs/LAPORAN-batch6g-n15n16-2026-10-17.md`

---

## 49. Batch 6h — N12: cooldown per pair untuk notifikasi kandidat (2026-10-17)

### 49.1 Masalah

Notifikasi kandidat dipicu edge `READY`. State bisa flapping `READY → INVALIDATED → READY`, dan
setiap siklus memenuhi syarat edge ⇒ bel berulang untuk setup yang praktis sama. Peak sudah punya
jendela 30 dtk, emergency 60 dtk; kandidat belum punya apa pun.

### 49.2 Perbaikan

`util/CandidateNotifyThrottle.kt`:

- `CandidateNotifyThrottle.shouldNotify(last, now, windowMs = 5 menit)` — fungsi murni;
- `CandidateNotifyCooldown.allow(key, now)` — penjaga berstatus (peta `EXCHANGE_SYMBOL` → waktu).

`sendCandidateFoundNotification` memanggil `allow(...)` sebelum mengirim, sehingga jalur foreground
(VM) dan latar (worker) sama-sama terkena.

Alasan 5 menit: worker memindai tiap 15 menit (tidak pernah menelan dua siklus), dan sinyal
scalping M5 ⇒ 5 menit = satu candle penuh.

### 49.3 Uji & kontrol negatif

12 uji baru (644 → 656): `CandidateNotifyThrottleTest` 11 kasus (6 murni + 5 penjaga berstatus,
termasuk "pencatatan hanya terjadi saat diizinkan"), plus 1 uji pindai sumber bahwa helper benar-
benar memanggil `candidateCooldown.allow(...)`.

Catatan metodologi: uji "kirim dua kali, hitung notifikasi di shade" **tidak bisa membedakan**
karena ID yang sama hanya menimpa entri. Uji itu dibuang; yang diuji adalah keputusan `allow(...)`.

| Kontrol | Perubahan | Hasil |
|---|---|---|
| A | `allow` selalu `true` | **2 GAGAL** |
| B | panggilan `allow(...)` dihapus dari helper | **1 GAGAL** |

Angka terukur (dari XML): **81 kelas / 644 uji → 82 kelas / 656 uji**, 0 gagal, 0 error, 1 dilewati.

### 49.4 Batas

- Cooldown hanya di memori proses; restart mengosongkannya (sama seperti peak).
- Kunci per `EXCHANGE_SYMBOL` — pair sama di dua bursa punya jendela sendiri.
- N6 + N17 belum disentuh; selama N6 hidup flapping masih mungkin, N12 hanya membatasi akibatnya.

### 49.5 Laporan

`docs/LAPORAN-batch6h-n12-2026-10-17.md`

---

## 50. Batch 6i — N6 + N17 + N11: satu mode, satu mesin, satu angka (2026-10-17)

### 50.1 Keputusan owner

- **N6/N17 → opsi (a):** worker latar hanya menghitung **mode yang sedang aktif**
  (`prefs.strategyMode`). Saat mode itu dipakai, notifikasi dan engine memakai mesin yang sama,
  jadi angkanya satu sumber: **engine → worker → notifikasi**. Skor boleh berubah mengikuti tren;
  yang tidak boleh adalah dua metode untuk satu angka.
- **Hemat baterai:** jangan pindai seluruh pair. Worker hanya menyentuh **watchlist**, dan hanya
  mengambil data yang dibutuhkan mode aktif.
- **N11 → jangan tampilkan** tabel tier lintas-mode. Cap `ScalpingMtfEvaluator` tidak diubah.

### 50.2 Perbaikan

**Seam baru `engine/ModeSignalEvaluator.kt`.**

- `ModeSignalInput` membawa seluruh bahan evaluasi (candle, orderbook, fee, config, konteks global)
  tetapi **tidak** membawa `macroAnomalyResult` — penalti dihitung di dalam dari candle D1.
- `ModeSignalEvaluator.evaluate(input)` memilih evaluator berdasarkan `input.mode`; tidak ada jalur
  yang bisa mengevaluasi mode lain.
- `LearningTradingEngine.runScalping/runSwing/runIntraday` memakai seam ini. Import
  `ScalpingMtfEvaluator`/`SwingEvaluator` di engine dihapus.

**`CandidateScanWorker`.**

- Baca `prefs.strategyMode` sekali; `when (mode)` menentukan data + satu jalur evaluasi.
- `SignalLifecycleManager.process` **satu kali** per simbol (dulu tiga).
- SWING: H1 200 + D1 100, H1 `dropLast(1)`. OFFICE_DAILY: H4 200 + D1 100, H4 `dropLast(1)`.
  SCALPING: H1/M15/M1 60 `dropLast(1)` + **orderbook nyata**.
- `diagnosticIgnoreOrderBookWhenUnavailable = true` dihapus dari produksi: tanpa orderbook,
  Step2 GAGAL.
- Notifikasi memakai `tracked.activeSignalState ?: evaluation.signal` — sama dengan kartu engine.

**N11.**

- Hapus kartu `ConfidenceReliabilityBreakdownCard`, `ConfidenceTierRow`, section
  `confidence_tier_section`, `ConfidenceTierStats` + field tier, `buildTierStats`, serta
  `ConfidenceTierNote` + ujinya.
- Kartu **Akurasi per Setup** tetap; itu bukan tier lintas-mode.

### 50.3 Uji & kontrol negatif

11 uji baru: `ModeSignalEvaluatorTest` (4), `CandidateScanWorkerSourceTest` (5),
`ConfidenceTierRemovalTest` (2).

| Kontrol | Perubahan | Hasil |
|---|---|---|
| A | `macroAnomalyResult = null` di seam | **2 GAGAL** |
| B | worker memanggil `ScalpingMtfEvaluator` + `diagnosticIgnore... = true` | **2 GAGAL** |
| C | `SignalLifecycleManager.process` kedua di worker | **1 GAGAL** |
| D | engine menghitung `MacroAnomalyDetector.evaluate` sendiri | **1 GAGAL** |
| E | kartu tier dihidupkan kembali | **1 GAGAL** |

Angka terukur (dari XML): 82 kelas / 656 uji → (N11 dihapus, −12 uji) 81/644 → **84 kelas /
655 uji**, 0 gagal, 0 error, 1 dilewati.

### 50.4 Batas

- Snapshot tetap berbeda (worker 15 menit, tanpa forming volume/orderbook live). Yang dijamin:
  metode + gerbang sama, bukan detik yang sama.
- Mode non-aktif tidak punya produsen latar — konsekuensi keputusan (a).
- Scalping latar dibatasi 10 simbol; 4 permintaan/simbol.
- Tabel tier dihapus, tidak diganti. Statistik berjenjang berikutnya harus **per mode**.

### 50.5 Laporan

`docs/LAPORAN-batch6i-n6n17n11-2026-10-17.md`

---

## 51. Aturan baru — berkas temuan keluar dari `docs/bugs/`, tanpa laporan per batch (2026-10-17)

### 51.1 Keputusan owner

- Setiap kali **seluruh** isi sebuah berkas di `docs/bugs/` selesai diperbaiki, berkas itu
  **dipindahkan keluar** (atau dihapus kalau isinya tidak lagi berguna).
- Pola nama tujuan: `docs/laporan_temuan_bugs_<topik>.md`.
- Berkas hasil pindahan **adalah laporannya**. **Tidak perlu** membuat `docs/LAPORAN-batch*.md`
  baru; ringkasan batch masuk ke § Riwayat berkas itu dan ke `workplan2.md`.
- Penulisan `.md` harus bersih: UTF-8 tanpa BOM, LF, **tanpa aksara Mandarin/Jepang/Korea**, dan
  tanpa kalimat yang hanya bisa dipahami penulisnya.

### 51.2 Yang dikerjakan

- `git mv docs/bugs/temuan_bugs_notifikasi.md docs/laporan_temuan_bugs_notifikasi.md`.
  Judul + header diubah menjadi laporan penutupan; ditambah baris § Riwayat untuk pemindahan.
- Semua rujukan path di 13 berkas `.md` diperbarui ke path baru (termasuk laporan batch lama,
  supaya tautannya tidak menggantung).
- `AGENTS.md`: §3 (isi `docs/bugs/`), § "Folder temuan bug" (aturan pemindahan + larangan laporan
  per batch), § "Saat ini berisi" (kini hanya `temuan_bugs_paper_trade.md`), dan seksi baru
  **"Penulisan dokumen Markdown"**.
- `docs/PANDUAN-LANJUT-AGEN.md`: catatan bahwa berkas notifikasi sudah di luar `docs/bugs/`.
- **Baru:** `tools/check_md_text.py` — memeriksa BOM, CRLF, UTF-8 rusak, aksara CJK, mojibake.
  Hasil pada 68 berkas `.md`: **bersih**.
- **`trading/TradeLogExporter.kt`** — label jurnal `Skor Konfidensi (engine ∩ cap tahap)` diganti
  `Skor Konfidensi (skor engine, dibatasi tahap)`. Simbol irisan dibuang karena di sebagian font
  penampil ia terbaca seperti aksara CJK, dan berkas jurnal itu memang `.md`.
- **Uji baru:** `TradeLogExporterLabelTest` (2) — label tidak memuat simbol irisan dan memakai kata
  Indonesia. Kontrol negatif: label lama dikembalikan → **2 GAGAL**.

### 51.3 Angka uji

| Tahap | Kelas | Uji | Gagal | Error | Dilewati |
|---|---|---|---|---|---|
| Sebelum batch ini (batch 6i) | 84 | 655 | 0 | 0 | 1 |
| Setelah `TradeLogExporterLabelTest` | **85** | **657** | **0** | **0** | **1** |

### 51.4 Hasil pemeriksaan tulisan

Pemindaian seluruh `.md` yang dilacak git (68 berkas) dan seluruh riwayat commit untuk `.md`:
**tidak ada satu pun aksara Han/Hiragana/Katakana/Hangul**, tidak ada BOM, tidak ada CRLF, tidak ada
berkas yang gagal di-decode sebagai UTF-8. Jadi "tulisan Mandarin" yang terlihat kemungkinan berasal
dari **render font di penampil** (simbol matematika seperti ∩ ∧ √ ⇒ bisa tampil seperti aksara CJK
pada font fallback), bukan dari isi berkas. Yang bisa dilakukan dari sisi repo: skrip pemeriksa di
atas dijalankan sebelum commit, dan jargon pinjaman di dokumen diganti istilah Indonesia.

### 51.5 Batas

- Laporan batch lama **tidak dihapus** — isinya bukti uji dan kontrol negatif. Yang berubah: tidak
  ada lagi berkas baru dengan pola itu.
- `docs/bugs/` sekarang hanya berisi `temuan_bugs_paper_trade.md` (P6 sisa + P9 masih hidup).

---

## 52. Audit jalur koneksi Tokocrypto & Indodax — temuan K1–K15 (2026-10-17)

### 52.1 Pemicu

Owner menemukan pair **`UVXYBUSDT`** yang tidak ada di Tokocrypto, dan melaporkan WebSocket
Indodax sering putus sehingga jatuh ke REST. Order beli/jual & saldo riil di kedua bursa sudah
diuji owner dan berfungsi — jadi audit difokuskan ke **sumber daftar pair** dan **sumber data**.

### 52.2 Metode

Baca kode (discovery, service, WebSocket, koordinator, dashboard, watchlist) + **uji endpoint
langsung** dari sandbox: bentuk respons `common/symbols`, isi `exchangeInfo` mirror, ticker/depth
`UVXYBUSDT`, dan handshake WebSocket Indodax. Tidak ada perubahan kode.

### 52.3 Temuan pokok

- **K1 (kritis).** `TokocryptoSymbolRepository` membaca `optJSONArray("data")`, padahal endpoint
  resmi mengembalikan `data: { list: [...] }`. Endpoint resmi (864 simbol) selalu dianggap gagal,
  lalu dipakai `tokocrypto.site/api/v3/exchangeInfo` — daftar Binance **3.729 simbol**, termasuk
  `UVXYBUSDT` dengan `status: TRADING` dan filter order valid. Ticker pair itu juga hidup di host
  tersebut (18,82), sementara API resmi Tokocrypto membalas 404.
- **K2 (kritis).** Host cadangan adalah **gateway kompatibel Binance**: `exchangeInfo`-nya berisi
  3.729 simbol universe Binance, tetapi depth `BTC_USDT` identik dengan Tokocrypto resmi — jadi
  host itu bukan cermin buta. Cacatnya: daftar pair diambil dari `exchangeInfo` gateway, bukan dari
  `common/symbols` resmi, sehingga simbol yang tidak diperdagangkan Tokocrypto ikut lolos.
- **K3–K4 (serius).** Field resmi `type` dibaca sebagai `symbolType` → semua simbol jadi Type 1
  (17 simbol Type 3 salah rute). `spotTradingEnable` bertipe angka dan `status` tidak ada →
  gate listing gagal-terbuka.
- **K5 (serius).** Awalnya dicatat "token ditolak server". **Koreksi §53:** token di kode
  terpotong satu karakter; token aslinya berlaku sampai 2031. Setelah dibetulkan, kanal
  `chart:tick-<pair>` tetap tidak menerbitkan data — kanal yang benar `market:summary-24h` — dan
  bentuk barisnya berbeda dari parser lama.
- **K6–K7 (serius).** `lastSequence` tidak di-reset saat reconnect; `isStale` memanggil `start()`
  yang memutus socket, sehingga pair sepi mengalami restart tiap 30 detik.
- **K8–K14 (sedang).** Backoff tanpa jitter/batas; listener Indodax tanpa penjaga generasi; satu
  `wsLive` untuk dua bursa; worker Indodax mengunduh seluruh pasar; `fetchTickers` Indodax selalu
  `/api/summaries`; watchlist menerima simbol tanpa validasi; cabang dashboard melewati filter
  listing.
- **K15 (rendah).** `getSymbolType` default 1 untuk simbol tak dikenal.

### 52.4 Batas

- Belum ada perbaikan; seluruh K1–K15 masih hidup.
- Perilaku host pihak ketiga bisa berubah. Yang dicatat adalah hasil ukur 2026-10-17.
- Type 3 diuji dengan dua format stream dan tiga jalur REST, hasilnya negatif — masih perlu
  konfirmasi dokumentasi Tokocrypto sebelum menyimpulkan tidak didukung.

### 52.5 Dokumen

`docs/bugs/temuan_koneksi_tokocrypto_indodax.md`

## 53. Batch 6j — K5: WebSocket Indodax hidup lagi (2026-10-17)

### 53.1 Pemicu dan koreksi temuan

Owner menegaskan token Indodax **masih valid** dan melampirkan token utuh. Audit §52 mencatat
"token ditolak server" — itu **gejala**, bukan akar masalah. Setelah dibandingkan karakter per
karakter:

| | Panjang | Akhiran tanda tangan |
|---|---|---|
| Token di kode | 104 | `…NVdsske` |
| Token owner | 105 | `…NVdsskeo` |

Tanda tangan HS256 base64url selalu 43 karakter; versi di kode hanya 42. Payload
`{"exp":1946618415}` = **2031-09-08**, jadi token tidak kedaluwarsa — hanya terpotong.

Bukti handshake langsung ke `wss://ws3.indodax.com/ws/`:

```text
token 104 karakter  → 3002 {"reason":"invalid token","reconnect":false}
token 105 karakter  → {"id":1,"result":{"client":"…","version":"2.8.7","expires":true,"ttl":154367516}}
```

### 53.2 Tiga cacat berlapis

1. **Token terpotong** (di atas).
2. **Kanal salah.** `chart:tick-<pair>` hanya dijawab `{"recoverable":true,…}` lalu diam.
   Kanal yang benar `market:summary-24h`; terukur ±1 pesan/detik (±3 KB per 20 detik) karena
   mengalirkan semua pair. Varian per-pair (`market:summary-24h-<pair>`, `market:ticker-<pair>`,
   `market:price-<pair>`) semuanya diam. Yang tersedia per-pair hanya `market:order-book-<pair>`.
3. **Bentuk baris salah.** Server mengirim
   `[pair, epochDetik, last, low, high, open, vol_idr, vol_koin]`; parser lama membaca
   `[epoch, seq, harga, volume]` dan hanya menerima 4 kolom.

### 53.3 Perubahan

- **Baru** `service/IndodaxSummaryFeed.kt` — parser murni (tanpa Android `Context`), konstanta
  kanal, dan `isValidTokenShape` (3 segmen, tanda tangan tepat 43 karakter).
- `IndodaxMarketWebSocket` — token utuh; kanal `market:summary-24h`; saring satu pair;
  `volume24h` dari `vol_idr` (sama dengan REST); delta `vol_koin` untuk volume candle;
  deduplikasi `(timestamp, harga)`; tolak menyambung bila token tidak utuh.
- `lastSequence` dihapus → **K6 tutup** (tidak ada nomor urut pada kanal ringkasan).

### 53.4 Uji

`IndodaxSummaryFeedTest` — 9 kasus: token utuh & berlaku, token terpotong ditolak, baris nyata
sesuai urutan kolom, delta volume kumulatif, volume turun tidak negatif, pair lain diabaikan,
kanal lama ditolak (+ penjaga sumber), baris 4 kolom ditolak, pesan rusak tidak melempar.

Kontrol negatif (semua dijalankan, lalu berkas dikembalikan):

| Kerusakan yang disuntikkan | Uji yang GAGAL |
|---|---|
| token dipotong 1 karakter | 2 (token utuh, token terpotong) |
| kanal kembali ke `chart:tick-` | 1 (kanal lama) |
| parser kembali ke 4 kolom | 3 (baris nyata, delta, volume turun) |

### 53.5 Angka terukur

Angka diambil dari `app/build/test-results/testDebugUnitTest/*.xml` setelah seluruh suite:
**86 kelas / 666 uji**, 0 gagal, 0 error, 1 dilewati (sebelumnya 85/657).

### 53.6 Dokumen

`docs/bugs/temuan_koneksi_tokocrypto_indodax.md` — K5 ditulis ulang (K5a/K5b/K5c), K6 ditandai
tutup.
