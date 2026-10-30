# workplan2.md — Workplan aktif (Oktober 2026)

> **Sumber kebenaran workplan.**  
> `workplan.md`, `checkpoint.md`, dan `checkpoint_progres.md` **sudah dihapus (2026-10-16)** — jangan cari, jangan dibuat ulang. Baca ini + `AGENTS.md` + `APP_CONTEXT.md` (§ arah produk).

**Update:** 2026-10-26 (WIB) — §73: jeda entri-ulang auto-buy paper. Temuan owner
(25/10/2026, WINUSDT, sekitar 21:12 WIB): setelah sell paper terpicu, order buy
terpicu kembali tanpa jeda. Diukur (`docs/exp/exp_autobuy_reentry_winusdt.md`):
3 dari 10 entri replay WINUSDT hanya berjarak 60 detik dari exit sebelumnya, dan harga
di jendela itu memang sedang naik (puncak +4,75%). Perbaikan: jeda entri-ulang
`PaperReentryCooldown` per pair per bursa, default **15 menit** (`0` = nonaktif),
lurus persis setelah cek posisi dobel. Tidak menyentuh gate entry, lantai friksi,
exit, atau jalur uang riil. Verifikasi: 13 uji baru hijau, 339 test lingkup terkait
0 gagal, parity 30/30, kontrol negatif dua titik terbukti gagal lalu dipulihkan.
Laporan: `docs/laporan_temuan_bugs_paper_reentry_jeda.md`.
Sebelumnya, 2026-10-24 (WIB) — §70–§71: owner membuka kembali riset exit secara terbatas.
Sweep 5 mode exit (`classic`, `tp1_be`, `early`, `trail`, `full`) pada hold 90/240/420:
**0 dari 90 sel pair × mode × hold positif**; kontrol 620/14,84%/−0,1569 identik. Exit-only
kembali ditutup untuk sinyal P0. Diagnostik persentil entry: entry khas berada di **persentil
93** harga 1 hari terakhir dan **81,7%** rentang harian — keluhan "beli di pucuk" terkonfirmasi
secara posisi, tetapi bukan sinyal jual bersih. Tidak ada perubahan app/config.
Sebelumnya, 2026-10-22 — §65–§69: P0 KriptoYoi (TTL 5 candle, gate MTF 4 TF, veto BTC,
regime OR) disinkronkan lalu diukur — gate bekerja tetapi **edge belum ada** (620 trade, avg R
−0,1569, 616/620 TIMEOUT, 0 TP1/TP2). Dua line riset ditutup saat itu: exit-only (§68,
0/88 varian) dan sweep lantai friksi (§69, 0/27 kombinasi). Sebelumnya, 2026-10-21 — §64:
Spec v2/v3 dihentikan dan seluruh artefak v2 dihapus; fokus engine kriptoyoi yang sudah ada
+ slider.

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
| Edge | Belum terbukti di data historis (v2/v3/Binance45d). Slider mengubah **frekuensi** sinyal, **bukan** menciptakan expectancy. Status owner: slider entry sudah ada dan terbukti memaksimalkan entry; exit belum |
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

> **Catatan 2026-10-22 — tabel R di atas bukan lagi dasar pemilihan default.** R dihitung dari
> jarak SL, jadi membandingkan R antar-lantai friksi bias ke SL lebar. Sweep ulang berbasis uang
> (`docs/exp/exp_friction_multiple_ADA-DOGE-XRP.md`, 3 pair/60 hari, gate P0, exit sama) memberi:
> **semua kelipatan 1–8 rugi**; 3,0 sedikit lebih baik dari 6,0 (−0,993 vs −1,078 %/hari) tetapi
> tetap −1%/hari. Default 6,0 dipertahankan karena konservatif, bukan karena tabel R di atas.
| **6,0** | **usdt** | **186** | **98,9%** | **+0,0695** | **6/6** |
| **6,0** | **idr_maker** | **297** | **93,9%** | **+0,0430** | **4/6** |
| 8,0 | usdt | 181 | 99,4% | +0,0557 | 6/6 |
| 8,0 | idr_maker | 276 | 98,6% | +0,0735 | 6/6 |
| 10,0 | usdt | 179 | 99,4% | +0,0436 | 6/6 |
| 10,0 | idr_maker | 273 | 99,3% | +0,0639 | 6/6 |

Ada **plateau lebar 5–10×**; **6,0 dipilih** sebagai default (terbaik untuk pair USDT, dan masih
baik untuk IDR maker). Di bawah 5× belum konsisten positif.

> **Catatan parity 2026-10-20.** Baris `idr_maker` di tabel ini adalah eksperimen historis
> dengan knob maker yang **sudah dihapus** dari app dan replay (jalur sekarang taker-only).
> Angka `6× idr_maker = 297` **bukan** 297 sinyal BTCUSDT default; itu 6 pair × 20.000 bar
> memakai config IDR maker. Jangan menjalankan ulang dengan asumsi maker dan jangan mencampurnya
> dengan angka BTCUSDT 60 hari di §60.5.

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

`docs/laporan_temuan_bugs_koneksi_tokocrypto_indodax.md` (dulu
`docs/bugs/temuan_koneksi_tokocrypto_indodax.md` — dipindah 2026-10-18 setelah seluruh K1–K15 tutup).

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

`docs/laporan_temuan_bugs_koneksi_tokocrypto_indodax.md` (dulu `docs/bugs/…`) — K5 ditulis ulang
(K5a/K5b/K5c), K6 ditandai tutup.

## 54. Batch 6k — K1 + K2: daftar pair Tokocrypto hanya dari endpoint resmi (2026-10-17)

### 54.1 Akar masalah

`TokocryptoSymbolRepository` membaca `root.optJSONArray("data")`, padahal respons resmi berbentuk
`data: { list: [...] }`. Endpoint resmi (864 simbol) karena itu **selalu dianggap gagal**, lalu
kode turun ke `tokocrypto.site/api/v3/exchangeInfo` — universe Binance berisi 3.729 simbol,
termasuk `UVXYBUSDT` yang tidak diperdagangkan Tokocrypto. Metadata pair hantu itu lengkap, jadi
`validateOrder` lolos dan order baru ditolak di bursa.

### 54.2 Perubahan

- **Baru** `data/TokocryptoSymbolFeed.kt` — parser murni:
  - `extractList` menerima `data.list`, `data[]`, dan `symbols[]`;
  - `rejectionReason` menolak daftar <100 / >1.500 simbol, tanpa pair IDR, atau overlap <90%
    dengan snapshot resmi sebelumnya;
  - `readSpotTradingEnable` menerima Boolean/angka/string dan **fail-closed** untuk nilai tak
    dikenal (K4);
  - `type` dibaca lebih dulu, `symbolType` hanya cadangan (K3).
- `TokocryptoSymbolRepository`:
  - `SYMBOLS_ENDPOINTS` dihapus → satu `OFFICIAL_SYMBOLS_URL`;
  - `symbolsMap.clear()` hanya terjadi setelah daftar lolos `rejectionReason`;
  - gagal sync **tidak** menghapus snapshot; `lastSyncTime` hanya diperbarui saat sukses;
  - percobaan ulang setelah gagal 2 menit (bukan 30 menit).
- Gateway tetap dipakai `TokocryptoMarketService` untuk ticker/kline/depth — sesuai K2.

### 54.3 Uji

`TokocryptoSymbolFeedTest` — 10 kasus: bentuk `data.list`, bentuk array lama, `type` + gate
numerik, fail-closed, tolak 3.729, tolak 63, tolak tanpa IDR, tolak overlap rendah, terima
overlap tinggi, dan penjaga sumber (gateway tidak boleh kembali ke discovery).

Kontrol negatif:

| Kerusakan yang disuntikkan | Uji yang GAGAL |
|---|---|
| `data.list` tidak dibaca; `type` → `symbolType`; gate `optBoolean(..., true)` | 3 |
| `rejectionReason` dihapus sebelum `symbolsMap.clear()` | 1 (penjaga sumber) |

### 54.4 Angka terukur

Dari XML setelah seluruh suite: **87 kelas / 676 uji**, 0 gagal, 0 error, 1 dilewati
(sebelumnya 86/666).

### 54.5 Sisa

`UVXYBUSDT` tidak lagi masuk lewat discovery. Namun watchlist lama yang sudah memuat simbol itu
masih bisa tampil sampai **K14** (watchlist menerima simbol tanpa validasi) ditutup.

## 55. Batch 6l — K7: socket usang disambung ulang tanpa membuang histori (2026-10-17)

### 55.1 Cacat

`MarketDataCoordinator` memanggil `websocket.start(symbol)` begitu 30 detik tanpa frame data.
Ada dua hal yang salah:

1. **Ambang 30 detik hanya melihat frame data.** Pair sepi membuat koneksi sehat diputus, lalu
   disambung lagi, berulang terus.
2. **`start()` menjalankan `stop(false)`.** `candle` (Indodax) serta `streamSymbol`/`streamHost`
   (Tokocrypto) ikut kosong, jadi histori menit berjalan hilang tiap kali reconnect.

Padahal deteksi kematian sudah ada di lapisan transport: `webSocketClient` memakai
`pingInterval` 15 detik, sehingga OkHttp memfailkan koneksi saat pong tidak datang
(`onFailure` → `reconnect`). Pengecekan usang seharusnya hanya jaring pengaman.

### 55.2 Perubahan

- **Baru** `service/RealtimeSocketHealth.kt` — keputusan murni `needsRestart`:
  - zombie: socket terbuka tanpa satu frame pun melewati `HANDSHAKE_GRACE_MS` (20 detik);
  - kanal berhenti terbit: pernah ada frame lalu diam melewati `SILENCE_MS`
    (6 × 15 detik = 90 detik);
  - socket tertutup tidak dinilai (urusan job reconnect, supaya percobaan tidak bertumpuk).
- `IndodaxMarketWebSocket` dan `TokocryptoMarketWebSocket` — `reconnectNow()` dipisah dari
  `start()`: menutup socket lama dan menyambung lagi tanpa mengosongkan `candle`, `pairId`,
  `streamSymbol`, `streamHost`. `connectedAtMs` ditambahkan dan diisi di `onOpen` (sebelumnya
  `onOpen` menulis `lastMessageAt`, sehingga sesi tanpa frame selalu terlihat segar).
- `MarketDataCoordinator` — memanggil `reconnectIfStale()` untuk kedua bursa.

### 55.3 Uji

`RealtimeSocketHealthTest` — 9 kasus: socket tertutup tidak dinilai; sesi baru diberi kesempatan;
zombie terdeteksi; **pair sepi 45 detik tidak memicu restart** (kasus yang dulu gagal); diam
melewati ambang siklus ping memicu restart; ambang minimal 4 × interval ping; penjaga pemanggil
(coordinator tidak memanggil `start()` di blok pengecekan usang); penjaga candle Indodax;
penjaga konfigurasi stream Tokocrypto.

Kontrol negatif:

| Kerusakan yang disuntikkan | Uji yang GAGAL |
|---|---|
| ambang kembali ke 30 detik | 2 (pair sepi, ambang siklus ping) |
| blok coordinator kembali ke `isStale()` + `start()` | 1 (penjaga pemanggil) |
| `candle = null` di `reconnectNow` | 1 (penjaga candle) |

### 55.4 Angka terukur

Dari XML setelah seluruh suite: **88 kelas / 685 uji**, 0 gagal, 0 error, 1 dilewati
(sebelumnya 87/676).

### 55.5 Sisa

K8–K15 belum disentuh. Yang paling terasa setelah K7: **K8** (backoff tanpa jitter dan tanpa
batas percobaan) dan **K13/K14** (simbol tanpa validasi listing masih bisa masuk watchlist dan
dashboard).

## 56. Batch 6m — K13+K14: watchlist tervalidasi, dashboard satu gate listing (2026-10-17)

### 56.1 Cacat

**K13 — simbol bisa masuk watchlist tanpa dicek keberadaannya.** Kolom "ketik simbol" di
`WatchlistManagerSettings` menerima teks bebas; hasilnya langsung `onAddPair(...)`.
`setWatchlist`/`toggleWatchlist` hanya mengubah huruf besar dan memangkas spasi, dan
`TradingPair.fromCustomSymbol` **mengarang** `TradingPair` untuk simbol yang tidak dikenal.
Jadi `UVXYBUSDT` (atau string apa pun) bisa masuk watchlist, tampil sebagai kartu berharga
`-`, dan ikut dievaluasi engine — pintu masuk kedua bagi pair hantu, terlepas dari K1.
Ada pula fallback fail-open: `sanitizeSymbolForExchange(...) ?: typed`; kalau sanitasi gagal,
simbol mentah tetap disimpan.

**K14 — cabang dashboard melewati gate listing.** `pairListed(...)` dipakai untuk
`watchAndFav`, `explicit`, dan `popularHead`, tetapi tidak untuk `highVol`, `swingCandidates`,
dan `intradayCandidates` (dibangun dari `uniqueTicks`), dan tidak pula untuk quick filter
WATCHLIST yang membangun ulang `watchListPairs` langsung dari watchlist+favorit. Gate listing
juga hanya berlaku untuk Tokocrypto: `pairListed` selalu `true` di Indodax, sehingga simbol
sampah di watchlist Indodax bisa tampil sebagai kartu bernilai `-`.

### 56.2 Perubahan

- **Baru** `util/WatchlistEntryGate.kt` — gate murni `checkAdd(rawSymbol, source, isKnownSymbol)`
  dengan tiga syarat: format `[A-Z0-9]` panjang wajar, kuotasi sah untuk bursa aktif, dan
  keberadaan di repository bursa. Penolakan **fail-closed**; setiap penolakan punya pesan
  Indonesia siap tampil. Menghapus simbol dari watchlist tidak digate.
- `TokocryptoSymbolRepository.hasSymbol` / `IndodaxSymbolRepository.hasSymbol` — bukti
  keberadaan simbol. Repository Indodax, saat discovery belum dimuat, memakai batas
  `POPULAR_INDODAX_PAIRS` (sama seperti fallback `getAllTradingPairs`), bukan "semua lolos".
- `WatchlistViewModel` — `addToWatchlist`, `toggleWatchlist` (hanya arah menambah), dan
  `setCustomWatchlist` melewati gate; preset yang entrinya tidak ada di bursa aktif cukup
  gugur, bukan disimpan sebagai simbol hantu.
- `WatchlistManagerSettings` — fallback `?: typed` dihapus; putusan gate dipakai untuk status
  tombol Tambah, pesan galat di bawah kolom, dan chip populer (chip tak tersedia tampil redup
  dengan penjelasan saat diketuk).
- `DashboardRanking.symbolListed(source, symbol)` — satu gate listing untuk kedua bursa
  (Tokocrypto: status TRADING; Indodax: pair dikenal). `pairListed`, fallback statis
  `allVolumeSortedPairs`, dan quick filter WATCHLIST di `DashboardScreen` semuanya memakainya.
- `DashboardScreen` — `uniqueTicks` disaring **sekali di awal** dengan `symbolListed`, sehingga
  `highVol`, `swingCandidates`, dan `intradayCandidates` tidak bisa menerima simbol di luar
  daftar resmi lewat jalur tick mana pun.

### 56.3 Uji

`WatchlistEntryGateTest` — 17 kasus: keputusan gate (diterima, format, kuotasi per bursa,
keberadaan), pair hantu `UVXYBUSDT` ditolak, pesan untuk setiap alasan penolakan,
`hasSymbol` kedua repository (termasuk fallback POPULAR dan peta discovery),
`symbolListed` per bursa (termasuk status suspend), plus penjaga perkabelan: ViewModel,
UI tanpa fallback fail-open, `uniqueTicks` tersaring, quick filter WATCHLIST tersaring.

Kontrol negatif:

| Kerusakan yang disuntikkan | Uji yang GAGAL |
|---|---|
| `canAddToWatchlist` dilepas dari `addToWatchlist` | 1 (penjaga ViewModel) |
| fallback `?: typed` dikembalikan di UI | 1 (penjaga UI) |
| filter `symbolListed` dihapus dari `uniqueTicks` | 1 (penjaga dashboard) |

### 56.4 Angka terukur

Dari XML setelah seluruh suite: **89 kelas / 702 uji**, 0 gagal, 0 error, 1 dilewati
(sebelumnya 88/685).

### 56.5 Sisa

K8–K12 dan K15 masih terbuka. Yang paling terasa berikutnya: **K8** (backoff tanpa jitter dan
tanpa batas percobaan) dan **K10** (satu flag `wsLive` dipakai dua bursa).

## 57. Batch 6n — K8–K12 + K15: backoff berbatas, status live per bursa, lingkup ticker, tipe simbol jujur (2026-10-18)

### 57.1 Cacat

- **K8 — backoff tanpa jitter dan tanpa batas.** `reconnectAttempt = 0` di `onOpen` membuat server
  yang menerima lalu menutup koneksi (kasus K5) memicu siklus buka -> tutup -> tunggu 600–800 ms
  tanpa henti. Tidak ada jitter, tidak ada status "menyerah sementara".
- **K9 — listener Indodax tanpa penjaga generasi.** Socket lama bisa memanggil `onDisconnected()`
  dan `reconnect()` saat pengguna berganti pair, sehingga bursa terlihat "terputus" padahal socket
  aktif sehat. Tokocrypto sudah memakai `generation`; Indodax belum.
- **K10 — satu flag `wsLive` dipakai dua bursa.** `wsLive` di-set `false` oleh callback bursa mana
  pun. Socket bursa non-aktif yang terputus menjatuhkan status bursa yang sedang dipakai, sehingga
  REST fallback diforce tanpa perlu.
- **K11 — worker Indodax mengunduh seluruh pasar.** `CandidateScanWorker.fetchTicks` memanggil
  `fetchAllMarketTicks()` (`/api/summaries`, SEMUA pair) padahal pemindaian hanya butuh watchlist.
- **K12 — `fetchTickers` Indodax selalu seluruh pasar.** Meski dipanggil untuk satu/beberapa pair
  (polling trailing tiap 10 detik), fungsi ini mengunduh `/api/summaries` lalu memfilternya.
- **K15 — `getSymbolType` mengembalikan 1 untuk simbol tak dikenal.** Kesalahan konfigurasi
  diarahkan ke host Type 1 tanpa satu pun log.

### 57.2 Perubahan

- **Baru** `service/ReconnectBackoff.kt` — kebijakan murni (tanpa jaringan/keacakan):
  - `isStable(connectedAtMs, nowMs)` — sesi dianggap berhasil setelah `STABLE_AFTER_MS` (60 detik);
  - `delayMs(attempt, base, max, random01)` — jeda berlipat dengan jitter ±20% dan plafon;
  - `shouldGiveUp(attempt)` + `GIVE_UP_PAUSE_MS` (5 menit) setelah 6 percobaan berturut-turut.
- `service/IndodaxMarketWebSocket.kt`:
  - hitungan percobaan **tidak lagi** di-reset di `onOpen`; reset terjadi di `consume()` hanya
    setelah sesi stabil (K8);
  - penjaga generasi: `generation` naik di `stop()`/`reconnectNow()`; `connect()` menangkap
    `gen` dan listener memakai `Listener { gen == generation }` (K9);
  - `reconnect()` memakai `ReconnectBackoff`; konstanta `BASE_RECONNECT_MS`/`MAX_RECONNECT_MS`
    ditambahkan.
- `service/TokocryptoMarketWebSocket.kt`:
  - `reconnect()` memakai `ReconnectBackoff`; reset dipindah dari `onOpen` ke `onMessage` setelah
    stabil (K8); `kotlin.math.min` yang tidak lagi terpakai dihapus.
- `viewmodel/MarketDataCoordinator.kt` (K10):
  - `wsLive` tunggal diganti `wsLiveToko` + `wsLiveIndodax`;
  - callback tiap socket hanya menyentuh flag bursanya sendiri;
  - `wsFresh` memakai `activeWsLive()` (status bursa yang sedang dipakai);
  - `handleWebSocketTick` menandai bursa aktif; `hardStopAndPurgeAll` mematikan keduanya.
- `service/CandidateScanWorker.kt` (K11): cabang Indodax memakai
  `IndodaxMarketService.fetchTickers(eligibleSymbols)`.
- `service/IndodaxMarketService.kt` (K12): `fetchTickers(pairIds)` mengambil per-pair lewat
  `fetchTicker` (`/api/ticker/<pair>`); `/api/summaries` tetap hanya di `fetchAllMarketTicks`
  untuk dashboard penuh.
- `data/TokocryptoSymbolRepository.kt` (K15): konstanta `SYMBOL_TYPE_UNKNOWN = 0`,
  `warnUnknownSymbolTypeOnce` (log sekali per simbol); `getSymbolType` tidak lagi default `1`.
  `TokocryptoMarketWebSocket.hostForSymbolType` diberi catatan bahwa 0 dan 1 sama-sama host Type 1
  (perilaku lama dipertahankan; semua pemanggil membandingkan `== 3`).

### 57.3 Uji

Lima kelas uji, 24 kasus:

- `service/ReconnectBackoffTest` (8) — murni: jeda berlipat, plafon, batas jitter, jeda positif,
  batas stabil, `connectedAt` nol, batas menyerah, jeda menyerah jauh lebih panjang.
- `service/RealtimeReconnectPolicyTest` (5) — pindai sumber kedua socket: pemakaian
  `ReconnectBackoff`, `onOpen` tidak lagi reset, reset setelah stabil, penjaga generasi, konstanta
  jeda.
- `viewmodel/MarketDataLiveFlagTest` (4) — pindai sumber coordinator: flag per bursa, tidak ada
  flag tunggal, `wsFresh` memakai `activeWsLive()`, callback menyentuh flag sendiri.
- `service/IndodaxTickerScopeTest` (3) — worker tanpa `fetchAllMarketTicks`; `fetchTickers` per-pair
  tanpa `summaries`; `fetchAllMarketTicks` tetap ada.
- `data/TokocryptoSymbolTypeUnknownTest` (4) — konstanta `UNKNOWN`, `getSymbolType` mengembalikan
  `UNKNOWN`, log sekali per simbol, host Type 1 untuk tipe tak dikenal.

Kontrol negatif (dijalankan sebagai simulasi di lingkungan ini — lihat 57.4):

| Kerusakan yang disuntikkan | Uji yang GAGAL |
|---|---|
| `reconnectAttempt = 0` dikembalikan ke `onOpen` Tokocrypto | 1 (onOpen tidak reset) |
| `wsLiveToko`/`wsLiveIndodax` diganti satu `wsLive` | 1 (tanpa flag tunggal) |
| worker kembali memanggil `fetchAllMarketTicks()` | 1 (lingkup watchlist) |
| `fetchTickers` kembali ke `/api/summaries` | 1 (per-pair) |
| `getSymbolType` kembali `return 1` | 1 (unknown) |

### 57.4 Angka terukur

Diukur dari XML `app/build/test-results/testDebugUnitTest/*.xml` lewat workflow
`unit-tests-manual.yml`, commit `a6c5e00`, branch `patch` (2026-10-18). Sandbox agen (PRoot
Android) tidak bisa menjalankan Gradle — tidak ada Android SDK dan `aapt2` aarch64 — jadi
angkanya datang dari CI.

| Jalankan | Cakupan | Kelas | Uji | Gagal | Error | Dilewati |
|---|---|---|---|---|---|---|
| Run `39083182133` | custom: 5 kelas baru + `RealtimeSocketHealthTest` + `CandidateScanWorkerSourceTest` | 7 | 38 | 0 | 0 | 0 |
| Run `39083423103` | `all-unit-tests` (suite penuh) | 94 | 726 | 0 | 0 | 1 |

Rincian kelas baru (semuanya lulus): `ReconnectBackoffTest` 8, `RealtimeReconnectPolicyTest` 5,
`MarketDataLiveFlagTest` 4, `IndodaxTickerScopeTest` 3, `TokocryptoSymbolTypeUnknownTest` 4 —
total **24 uji baru**, sesuai `+5` kelas / `+24` uji dari baseline batch 6m (89/702).

Kontrol negatif JUnit (menghidupkan kembali cacat, menjalankan ulang, lalu mengembalikan) belum
dijalankan di CI — yang sudah dilakukan: simulasi pindai-sumber dengan skrip Python yang meniru
ekstraksi uji; 48/48 pemeriksaan lulus dan 5 kontrol negatif memang menggigit. Bila owner ingin
kontrol negatif JUnit yang sebenarnya, jalankan workflow dengan sengaja mengembalikan satu cacat.

### 57.5 Dokumen

- `docs/laporan_temuan_bugs_koneksi_tokocrypto_indodax.md` — seluruh K1–K15 kini tutup; berkas
  **dipindah** dari `docs/bugs/` ke `docs/laporan_…` (aturan §51). Ditambah §7 Riwayat + §6
  Lampiran bukti.
- `AGENTS.md` — daftar isi `docs/bugs/` (kini hanya `temuan_bugs_paper_trade.md`), aturan turunan
  baru dari K8–K12/K15.
- `workplan2.md` — seksi ini.

## 58. Rilis 3.9.2 — batch 6j–6n (2026-10-18)

Versi `3.9.2` (`versionCode 91`). Tidak ada perubahan kode selain nomor versi; isi rilis adalah
seluruh perbaikan audit koneksi **K1–K15** (batch 6j–6n) yang sudah terverifikasi:

- daftar pair Tokocrypto hanya dari `common/symbols` resmi (864 simbol), gate listing fail-closed;
- WebSocket Indodax hidup (token utuh, kanal `market:summary-24h`, parser 8 kolom);
- reconnect berjitter ±20% dengan plafon dan jeda menyerah 5 menit; penjaga generasi di kedua bursa;
- status live per bursa (`wsLiveToko`/`wsLiveIndodax`), worker & ticker Indodax sesuai lingkup;
- gate entry watchlist dan satu gate listing dashboard (`symbolListed`).

Angka suite terukur: **94 kelas / 726 uji**, 0 gagal, 0 error, 1 dilewati.
Berkas temuan sudah dipindahkan ke `docs/laporan_temuan_bugs_koneksi_tokocrypto_indodax.md`
(seluruh butir tutup), sehingga `docs/bugs/` tidak lagi memuat topik koneksi.

---

## 59. Batch 6o — P6 (sisa) + P9: peak intrabar M1 & serah-terima auto-exit ke jalur lama (2026-10-18)

Penutup rangkaian audit paper trade: setelah batch ini **seluruh P1–P9 tutup** dan
`docs/bugs/` menjadi kosong.

### 59.1 Cacat

Dua butir terakhir dari `docs/bugs/temuan_bugs_paper_trade.md` (kini
`docs/laporan_temuan_bugs_paper_trade.md`), keduanya hanya menyentuh posisi paper:

- **P6 (sisa) — `peak` buta terhadap intrabar.** `evaluatePaperAutoExitLocked` membangun bar
  sintetis `open = high = low = close = currentPrice`, sehingga high intrabar antar-tick tidak
  pernah terlihat: trailing/BE baru ter-arm setelah harga *diperiksa* menembus level, bukan saat
  menyentuhnya, dan `peak` di laporan lebih rendah dari kenyataan.
- **P9 — mematikan auto-exit meninggalkan posisi tanpa stop.** Transisi `autoExitEnabled false`
  hanya melepas mesin dari memori; posisi tetap ditandai milik engine (`_paper_engine`) dengan
  `auto_sell_enabled` mati sampai restart. Lebih buruk: `updatePaperAutoTradeConfig` memanggil
  `clearAll()` saat `!autoBuy`, sehingga mematikan auto-buy **saja** ikut membunuh mesin exit
  posisi terbuka.

### 59.2 Perubahan

Berkas baru:

- `app/src/main/java/agu/analys/trading/PaperIntrabarPeakResolver.kt` (P6) — `effectiveHigh(candles,
  openedAtMs, nowMs, currentPrice)`: candle dengan `openTimeMs > openedAtMs`, cache wajib segar
  (reuse `PaperExitMomentumWindow.isFresh`, ≥ 20 bar / ≤ 180 dtk), high non-finite diabaikan,
  hasil `max(currentPrice, high eligible)`; `openedAt <= 0` atau cache basi/kosong → `currentPrice`.
- `app/src/main/java/agu/analys/trading/PaperHandBackResolver.kt` (P9) — `PaperHandBackSource`
  (`PROFIT`/`BREAKEVEN`/`ACTIVE_STOP`/`SIGNAL`/`FRICTION_FLOOR`/`NONE`), `ResolvedHandBackStop`,
  `resolveStopLoss(entryPrice, storedSl, activeSl, bePx, floorPct)`: SL ≥ entry dari mesin
  dipertahankan (`PROFIT`/`BREAKEVEN`), selain itu `max(SL aktif, PaperStopResolver.resolve(...))`;
  semua kosong → `NONE`.

`PaperAutoTradeCoordinator.kt`:

- `PaperAutoTradeRuntime.activePositions()` — pasangan (bursa, simbol) tersortir dari `machines`.
- `evaluatePaperAutoExitLocked` membaca cache M1 **sekali** (`m1Candles`) untuk
  `PaperExitMomentumWindow.resolve` + `effectiveHigh`; bar sintetis `high = intrabarHigh`,
  `low = currentPrice`.
- `TradingViewModel.handBackAllPaperEnginesToLegacy()` — cermin serah-terima cabang rehidrasi
  `HandBackToLegacy` (`PaperRehydrate.kt`): `setAutoSellParams(enabled = stop.usable,
  stopLossPrice = stop.price)`, `setPaperEngineOwned(false)`, `clearPaperFsmState`,
  `PaperAutoTradeRuntime.clear`, log per posisi + ringkasan (sumber `SIGNAL`/`FRICTION_FLOOR`/
  `NONE` terlihat), orphan cleanup, ditutup `checkAndStopTrailingServiceIfEmpty()`.

`TradingViewModel.kt`:

- `updatePaperAutoTradeConfig`: transisi `autoExitEnabled true → false` →
  `handBackAllPaperEnginesToLegacy()` (dibungkus `runCatching` + log); `clearAll()` hanya saat
  `!autoBuy && !autoExit`.

Catatan keputusan (jangan diubah tanpa alasan kuat): TP1 partial tetap legacy (tidak menambah
guard — setelah partial di akuntansi, R:R tidak lagi bergeser); re-enable auto-exit **tidak**
me-rearm posisi (tetap legacy sampai tutup); posisi hasil serah-terima dipantau seperti jalur
lama (pair dibuka / trailing aktif) — batas yang sama dengan serah-terima pasca-restart.

### 59.3 Uji

Empat kelas baru, 31 kasus:

- `trading/PaperIntrabarPeakResolverTest` (11) — murni: high intrabar dipakai, lantai
  `currentPrice`, cache basi, < 20 bar, kosong, batas `>` waktu buka (candle tepat saat posisi
  dibuka diabaikan), `openedAt` tak diketahui, high non-finite, stempel detik.
- `trading/PaperHandBackResolverTest` (13) — murni: trailing terkunci menang atas lantai
  (`max`), BE/profit ≥ entry dipertahankan, label `PROFIT`/`BREAKEVEN`, `NONE` tanpa kandidat,
  lantai friksi, SL tersimpan valid, `activeSl` tak masuk akal.
- `viewmodel/PaperIntrabarWiringTest` (3) — pindai sumber: bar sintetis pakai resolver, cache
  dibaca sekali, `openedAt` dioper.
- `viewmodel/PaperHandBackWiringTest` (4) — pindai sumber: transisi memicu serah-terima, auto-buy
  saja tidak menghapus mesin, serah-terima mengisi SL + melepas penanda + hapus FSM, mencakup
  semua bursa.

Kontrol negatif (simulasi Python, transliterasi 1:1 dari Kotlin — **6 menggigit**):

| Kerusakan yang disuntikkan | Uji kunci yang GAGAL |
|---|---|
| `max` hasil kembali ke `currentPrice` saja (`no-peak`) | high intrabar dipakai |
| cek `isFresh` dihapus (`no-fresh-check`) | cache basi kembali ke currentPrice |
| filter `>` menjadi `>=` (`gte-filter`) | candle tepat saat posisi dibuka diabaikan |
| cabang `activeSl >= entry` dihapus (`no-entry-branch`) | BE/profit dipertahankan |
| label profit selalu `BREAKEVEN` (`no-profit-label`) | label `PROFIT` |
| kandidat diambil `min`, bukan `max` (`min-pick`) | trailing terkunci menang atas lantai |

Percobaan pertama gagal menuai: mutasi `no-fresh-check` tidak menggigit karena semua high uji di
bawah `currentPrice` → uji basi/19-bar diperbaiki agar memakai high menipu (130), di skrip **dan**
Kotlin; percobaan kedua baru adil.

### 59.4 Angka terukur

Diukur dari XML `app/build/test-results/testDebugUnitTest/*.xml` lewat workflow
`unit-tests-manual.yml`, commit `220e7a0`, branch `patch` (2026-10-18). Sandbox agen tidak bisa
menjalankan Gradle, jadi angkanya datang dari CI.

| Jalankan | Cakupan | Kelas | Uji | Gagal | Error | Dilewati |
|---|---|---|---|---|---|---|
| Run `39144882774` | custom: 4 kelas baru | 4 | 31 | 0 | 0 | 0 |
| Run `39145158155` | `all-unit-tests` (suite penuh) | 98 | 757 | 0 | 0 | 1 |

Rincian kelas baru (semuanya lulus): `PaperIntrabarPeakResolverTest` 11,
`PaperHandBackResolverTest` 13, `PaperIntrabarWiringTest` 3, `PaperHandBackWiringTest` 4 — total
**31 uji baru** (= `+4` kelas / `+31` uji dari baseline batch 6n, 94/726).

Kontrol negatif JUnit (menghidupkan kembali cacat, menjalankan ulang, lalu mengembalikan) belum
dijalankan di CI — yang sudah dilakukan: simulasi Python transliterasi 1:1 dengan `6` mutasi yang
menggigit (lihat §59.3). Bila owner ingin kontrol negatif JUnit yang sebenarnya, jalankan
workflow dengan sengaja mengembalikan satu cacat.

### 59.5 Dokumen

- `docs/laporan_temuan_bugs_paper_trade.md` — berkas temuan **dipindah** dari `docs/bugs/`
  (aturan §51); seluruh P1–P9 tutup; §6 Riwayat + baris batch 6o; `docs/bugs/` kini **kosong**.
- `AGENTS.md` — daftar `docs/bugs/` (kini kosong), aturan turunan P6/P9 (batch 6o), pengecualian
  sadar invarian batch 3b (`SL >= entry` dari mesin), catatan batch 6e (keputusan P9).
- `docs/PANDUAN-LANJUT-AGEN.md` — status P6/P9 tutup; seluruh P1–P9 tutup.
- `workplan2.md` — seksi ini.

---

## 60. Batch 6p — Sinkronisasi FEE_TRUTH v1.1 ke codebase + replay (2026-10-19)

Pemilik memperbarui `FEE_TRUTH.md` ke **v1.1** (2026-10-19) dan `tools/scalp_replay.py`;
pekerjaan ini menyinkronkan konstanta/parameter ke codebase lalu menjalankan replay.

### 60.1 Apa yang sudah cocok (tidak diubah)

- `FeeSchedule` sudah memakai tarif all-in persis seperti FEE_TRUTH §1–§2 (USDT 0,4044%;
  IDR beli 0,1222/0,2222%; jual 0,3322/0,4322%; Indodax 0,11/0,21/0,32/0,42%).
- `ScalpingConfig`: `MIN_FRICTION_MULTIPLE = 6.0`, `TARGET_NET_RR = 2.0`,
  `DEFAULT_SLIPPAGE_PCT = 0.08` — sama dengan §8.
- `data/config/scalp_config.json` sudah memakai angka FEE_TRUTH v1.1.

### 60.2 Cacat yang ditemukan saat sinkronisasi

1. **Replay tidak bisa jalan dengan bawaannya.** `--config` masih menunjuk
   `tools/scalp_config.json` yang **tidak pernah ada** (config kanonik ada di
   `data/config/`). Perintah contoh di commit `28a2c70` langsung `FileNotFoundError`.
2. **Tarif tidak sadar kuotasi di jalur runtime.** `LearningTradingEngine` (3 call site),
   `CandidateScanWorker` (3 call site), `sellSignalState` di `TradingViewModel`, dan
   `balanceSlice` di `TradingViewModelDetailState` semua memakai `prefs.tradingFees` — **satu
   nilai global** yang bawaan Tokocrypto = preset **USDT** (0,4044%). Pair IDR
   (BTCIDR/ETHIDR/XAUTIDR) karena itu dievaluasi dengan tarif USDT: lantai SL **5,81%**
   (harusnya **4,89%**), TP jauh, net R:R di engine beda dari jalur paper yang sudah memakai
   `feeConfigFor(symbol)`. Ini persis kelas bug "dua angka untuk satu pertanyaan" yang dilarang
   sejak J7/J8.
3. **Dua definisi "friksi" dengan angka berbeda tanpa penjelasan.** Lantai SL memakai F
   **aditif** = **0,9688%** (FEE_TRUTH §8), sedangkan "impas net" kartu memakai bentuk
   **multiplikatif** = **0,9735%**. Keduanya benar untuk kegunaannya masing-masing; dokumen
   (`AGENTS.md`, `LAPORAN-batch4-j7`) hanya menyebut "0,9735%" tanpa menyatakan mana yang mana.

### 60.3 Perubahan

- `tools/scalp_replay.py` — konstanta `DEFAULT_CONFIG_PATH = "data/config/scalp_config.json"`;
  config yang hilang menghasilkan pesan JSON berisi lokasi yang dicoba + saran `--config`, bukan
  traceback; `DEFAULTS["min_net_rr"]` 0,5 → **1,15** (sebelumnya beda dari
  `ScalpingConfig.MIN_NET_RR`, diam-diam).
- `data/config/scalp_config.json` — `order_type` **dihapus** pada 2026-10-20; replay dan app
  hanya punya jalur taker, jadi knob maker tidak boleh ada.
- `LearningTradingEngine` — properti baru `feeConfigForSymbol: (String) -> TradingFeeConfig`;
  ketiga call site evaluate memakai tarif per-simbol.
- `TradingViewModelSync` — `engine.feeConfigForSymbol = { prefs.feeConfigFor(it) }`.
- `CandidateScanWorker` — ketiga call site memakai `prefs.feeConfigFor(symbol)`.
- `TradingViewModel.sellSignalState` — tarif mengikuti simbol tick yang aktif.
- `TradingViewModelDetailState` — flow turunan `pairAwareFees`; `balanceSlice` memakainya,
  sehingga kartu jual + `PositionContext` + P&L detail ikut kuotasi yang benar.

### 60.4 Uji

Dua kelas baru, 11 kasus:

- `config/FeeTruthConstantsTest` (7) — mengunci konstanta `FeeSchedule` ke angka
  FEE_TRUTH §1–§2, mengunci `min_friction_multiple`/`target_net_rr`/`slippage_pct` pada mirror
  JSON ke `ScalpingConfig`, dan membedakan **F aditif 0,9688%** vs **impas multiplikatif
  0,9735%** supaya keduanya tidak saling menggantikan.
- `viewmodel/FeeQuoteAwarenessSourceTest` (4) — pindai sumber: engine tak boleh memakai
  `fees = tradingFees`, worker wajib `prefs.feeConfigFor(symbol)`, kartu jual & detail wajib
  `feeConfigFor`.

Percobaan pertama **GAGAL** — `mirror replay sama dengan FeeSchedule` melempar
`NumberFormatException: For input string: ": 0.4044"`: parser mengambil substring setelah
`"buy_taker_pct"` termasuk tanda `:` dan koma. Diperbaiki dengan helper `angka(teks, key)` yang
melewati `:` sebelum `toDouble()`. Pelajaran: parser harus diuji pada data JSON asli, bukan
pada data yang kebetulan "sudah benar".

Kontrol negatif yang **benar-benar dijalankan** pada 2026-10-20:

| Kerusakan yang disuntikkan | Akibat terukur |
|---|---|
| `tools/scalp_replay.py` dikembalikan ke versi sebelum parity | `test_scalp_replay_parity.py` **8 dari 10 GAGAL** (3 failure, 5 error) |
| `ctx.symbol` di `sellSignalState` dikembalikan ke `tick?.symbol` | `FeeQuoteAwarenessSourceTest` **1 GAGAL** |
| `feeConfigFor(sym, ex)` di service diubah menjadi `feeConfigFor(sym)` | `FeeQuoteAwarenessSourceTest` **1 GAGAL** |

**Angka terukur** (workflow `unit-tests-manual.yml`, branch `patch`, 2026-10-19):

| Jalankan | Cakupan | Kelas | Uji | Gagal | Dilewati |
|---|---|---|---|---|---|
| Run `39243541785` | custom (percobaan pertama) | 3 | 28 | **1** | 0 |
| Run `39244023556` | custom (2 kelas baru + `ScalpingRiskEngineTest`) | 3 | 28 | 0 | 0 |
| Run `39244434459` | `all-unit-tests` (suite penuh) | 100 | 768 | 0 | 1 |

Baseline sebelum batch ini 98 kelas / 757 uji ⇒ **+2 kelas / +11 uji**.

**Suite penuh 2026-10-20 (lokal, JDK 17):** **101 kelas / 773 uji, 0 gagal, 1 dilewati**.
Kenaikan dari 100/768: `ScalpingRuntimeConfigFeeExportTest` (2 uji baru),
`FeeTruthConstantsTest` +1, `FeeQuoteAwarenessSourceTest` +2. Rincian: `FeeTruthConstantsTest`
8, `FeeQuoteAwarenessSourceTest` 6, `NotificationFeeSourceTest` 3,
`ScalpingRuntimeConfigFeeExportTest` 2.

### 60.5 Hasil replay (dikoreksi 2026-10-20)

**Koreksi penting.** Angka 2026-10-19 diukur dengan harness yang **belum sama dengan app**:
belum ada time-stop CLASSIC 90 bar, S1 masih meloloskan breakout tanpa RVOL, S9 belum
membaca `max_change_24h_pct`, dan `max_risk_atr` masih hardcode 2,0. Karena itu tabel lama
tidak boleh dipakai untuk menyimpulkan perilaku app.

Artefak lama (historis, pra-parity): `data/hasil/replay_fee_truth_v11_2026-10-19.json`.
Ringkasannya: BTCUSDT 60 hari default 6× = **297 ready / 0 selesai**; stress slippage 0,20%
dan 0,50% = **297 / 0** dengan lantai **7,2528%** dan **10,8528%** (bukan 5,81%); 8 jam =
**129 / 0**; lantai 3× = **129 / 1**; lantai 1× = **138 / 46**, avg R **−0,7398**; “tanpa
lantai” = **1 / 0** (lantai friksi 0, tetapi `min_risk_pct` 0,5% tetap aktif). Sweep 8 pair
(`data/tokocrypto_v2`) total **521 ready / 0 selesai** — BTCUSDT di dataset itu 73, bukan 297.

**Hasil setelah parity (terukur 2026-10-20, BTCUSDT `data/tokocrypto_v4`, 87.000 bar M1):**

| Skenario | Exit | Sinyal ready | Selesai | Win % | Avg R | Exit |
|---|---|---:|---:|---:|---:|---|
| Default 6×, 2 jam | classic + time-stop 90 | 350 | 350 | 4,00% | −0,1639 | 350 TIMEOUT |
| Default 6×, 2 jam | full + time-stop 90 | 354 | 354 | 4,52% | −0,1645 | 344 TIMEOUT, 10 TRAIL_SL |

Artefak ringkas: `data/hasil/replay_parity_2026-10-20.json`.

Jumlah ready naik dari 297 ke 350 **bukan karena gate dilonggarkan**: time-stop 90 bar membuat
sinyal berikutnya dievaluasi lebih cepat (replay memakai jendela non-overlap), sehingga lebih
banyak sinyal masuk ke sampel.

**Cara membaca hasil ini — jangan mengulang kesalahan lama:**

1. **0 trade selesai bukan parity app.** App menutup CLASSIC pada 90 bar M1; replay lama
   membiarkan posisi sampai lookahead habis. Setelah time-stop dipasang, semua sinyal selesai,
   tetapi hampir semuanya keluar karena waktu — bukan karena TP/SL.
2. **Avg R sekarang bisa dihitung, tetapi bukan bukti edge.** Nilai −0,16R didominasi exit
   time-stop dan friksi; full exit hanya mengubah 10 trade menjadi trailing stop.
3. **Lantai friksi sudah ada sejak harness lama.** Angka 186/+0,0695 di FEE_TRUTH §6 memakai
   `scalp_config_exit_full.json` (lantai 6×) dan dataset lain; klaim “harness lama belum
   memaksa 6×F” **salah** dan sudah dikoreksi.
4. **Stress slippage harus dibaca dari lantai efektifnya.** 0,20% → 7,2528%; 0,50% → 10,8528%.
   Menulis keduanya sebagai 5,81% membuat tabel tidak bisa direproduksi.
5. **“Tanpa lantai” bukan berarti risiko 0,5% efektif.** `friction_floor_pct` memang 0, tetapi
   `min_risk_pct` 0,5% masih menjadi lantai risiko; hanya 1 sinyal yang lolos.

**Artinya untuk keputusan produk:** pada tarif Tokocrypto, horizon scalp 90 menit dengan lantai
6× hampir selalu berakhir di time-stop dan rata-rata negatif. Menuju uang riil, ubah horizon,
pair, atau lantai **dengan pengukuran baru** — jangan memakai tabel pra-parity sebagai dasar.

### 60.6 Dokumen

- `FEE_TRUTH.md` §6 diberi catatan bahwa tabelnya berasal dari harness versi lama.
- `AGENTS.md` — aturan turunan baru (fee wajib sadar kuotasi; mirror JSON dikunci uji).
- `workplan2.md` — seksi ini.

### 60.7 Parity replay + fee (2026-10-20)

**Cacat yang ditutup.** Replay masih berbeda dari app di lima titik: (1) CLASSIC tidak punya
time-stop 90 bar; (2) S1 meloloskan breakout tanpa RVOL; (3) S9 tidak membaca
`max_change_24h_pct`; (4) `max_risk_atr` hardcode 2,0; (5) `order_type` masih bisa maker dan
pair IDR memakai fee USDT. Di app, `TradingForegroundService` masih memakai `appPrefs.tradingFees`
global (salah untuk posisi bursa/kuotasi lain) dan `sellSignalState` memakai `tick?.symbol`,
bukan simbol posisi. Ekspor JSON juga menulis `0.4044` sendiri dan tidak punya `idr_reference`.

**Perubahan.** `tools/scalp_replay.py`: DEFAULTS disamakan dengan `ScalpingConfig`, blok `exit`
di-merge, `resolve_fee` memilih `fee.idr_reference` untuk pair IDR, semua fee taker,
`roundtrip_fee` tanpa parameter maker, `risk_levels` membaca `max_risk_atr`, S1 mewajibkan
RVOL, `evaluate_bar` menerima `change24h`, `resolve_trade` menjalankan classic/tp1_be/trail/
early/full dengan time-stop 90, `summarize` menambah `by_exit_reason` + `exit_reasons_count`,
dan `--order-type` dihapus. `data/config/scalp_config.json`: `max_risk_atr`,
`max_change_24h_pct`, blok `exit`, tanpa `order_type`. App: resolver
`feeConfigFor(symbol, exchange)`, service memakai bursa posisi, `ctx.symbol` untuk kartu jual,
dan ekspor memakai `FeeSchedule` + `idr_reference`.

**Uji.** `tools/test_scalp_replay_parity.py` (10 uji) mengunci config, fee IDR, S1, S9,
`max_risk_atr`, floor denominator, time-stop, TP2 full, dan `resolve_fee`.
`ScalpingRuntimeConfigFeeExportTest` (2) mengunci ekspor; `FeeTruthConstantsTest` +1 mengunci
`idr_reference` + parameter parity; `FeeQuoteAwarenessSourceTest` +2 mengunci service dan
resolver bursa. Suite penuh 101 kelas / 773 uji, 0 gagal, 1 dilewati.

**Angka replay terukur.** Lihat §60.5: default 350 ready / 350 selesai (semua TIMEOUT,
4,0% menang, avg R −0,1639); full 354 / 354 (344 TIMEOUT, 10 TRAIL_SL, 4,52%, −0,1645).
Kontrol negatif dan angka suite ada di §60.4.

**Dokumen yang dikoreksi.** `FEE_TRUTH.md` §6 (klaim “belum memaksa 6×F” salah; harness lama
sudah memakai `exit_full` + lantai 6×), `workplan2.md` §60.5 (lantai stress 7,2528/10,8528,
“tanpa lantai” = `min_risk_pct` 0,5%, 521 sweep vs 297 BTCUSDT), `AGENTS.md` (fee per bursa,
time-stop CLASSIC, IDR replay), dan `docs/exp/exp_scalping_expectancy_ADA-DOGE-XRP.md`
(catatan parity: 42,7% bukan win rate app karena belum ada time-stop).

---

## 61. Eksperimen win rate engine scalping pada geometri TP kecil (2026-10-19)

Laporan lengkap: **`docs/exp/exp_scalping_expectancy_ADA-DOGE-XRP.md`**.

**Latar belakang.** Pada konfigurasi default (`min_friction_multiple = 6`), TP2 untuk pair USDT
mencapai **14,5%** dan SL **5,81%** — mustahil dalam horizon scalp (0,0% dan 0,8% peluang
tercapai dalam 8 jam pada data riil). Pemilik menandai ini sebagai red flag dan meminta
diuji dengan geometri scalping sungguhan: SL kecil, TP kecil, horizon pendek.

**Metode.** Satu config replay (`min_friction_multiple = 2,0` → SL 1,9376%; `target_net_rr = 0,5`
→ TP2 2,4220%; `min_net_rr = 0,5`; lookahead 480 bar = 8 jam), pada 3 pair dengan volatilitas dan
tren tertinggi dari dataset 60 hari (`data/tokocrypto_v4`): ADAUSDT, DOGEUSDT, XRPUSDT.

**Hasil terukur:**

| Angka | Nilai |
|---|---|
| Trade selesai | 349 dari 562 sinyal ready |
| Win rate (8 jam) | 42,7% |
| Win rate (+ lanjutan 48 jam) | 45,2% |
| Ambang impas geometri ini | 60,1% |
| Expectancy | −0,25R per trade |
| Gap | **14,9 poin persen** |

**Temuan yang mengubah gambaran:**

1. **Geometri scalping bisa menghasilkan trade** (349 selesai, bukan 0 seperti pada default 6×)
   — jadi renderer replay sehat dan angka default bukan "0 karena bug".
2. **Entry engine akurat 42,7%**, konsisten di ketiga pair (42,5–42,9%). Far di bawah 60,1%.
3. **Hipotesis "trade UNRESOLVED = tren berlanjut" terbantah.** Melanjutkan penelusuran
   sampai 48 jam: **92 WIN vs 92 LOSS** dari 213 trade unresolved — sepersis, tanpa bias.
   Win rate hanya bergerak 42,7% → 45,2%.
4. **Gap 14,9 poin persen adalah jarak akurasi entry, bukan jarak parameter.** Horizon,
   `min_friction_multiple`, dan `target_net_rr` tidak menutup gap ini.

**Batas yang jujur.** Satu config, tiga pair, 60 hari; akurasi diukur pada TP2 terdekat
(2,42%) sehingga kemungkinan **lebih tinggi** di TP2 yang lebih jauh — estimasi "k = 1,34 mungkin
unbiased" dari analisis awal terlalu optimistis dan belum diuji. Harness ini juga **belum punya
time-stop 90 bar**, jadi 42,7% bukan win rate app.

**Pengulangan parity (2026-10-20).** Setelah replay disamakan dengan app (CLASSIC time-stop
90 bar, S1 RVOL, S9, `max_risk_atr`, taker-only), geometri yang sama menghasilkan **1.149 trade
selesai, 171 menang / 978 kalah, win rate 14,88%, avg R −0,4898**; 963 trade keluar karena
TIMEOUT. TP2 efektif menjadi ≈2,91% karena kode menegakkan `TP2 ≥ 1,5 × risiko`. Ambang impas
dari payoff terukur 55,08%, jadi gap 40,2 poin persen. Laporan:
`docs/exp/exp_scalping_expectancy_parity_ADA-DOGE-XRP.md`; artefak:
`data/hasil/exp_scalping_expectancy_parity_2026-10-20.json`.

**Langkah berikutnya (memerlukan keputusan pemilik):** (a) akhiri program scalping,
(b) uji framing swing horizon 24 jam, atau (c) kejar perbaikan evaluator menuju win rate
≥ 60%. Untuk (b), time-stop CLASSIC 90 bar harus diganti lebih dulu; dengan exit sekarang,
horizon 24 jam tetap ditutup pada 90 menit. Rekomendasi penulis: (b) setelah exit swing
didefinisikan — murah dan informatif sebelum program besar.

**Dokumen.** `docs/exp/exp_scalping_expectancy_ADA-DOGE-XRP.md` (laporan lama, metode, angka,
batasan) dan `docs/exp/exp_scalping_expectancy_parity_ADA-DOGE-XRP.md` (pengulangan parity);
seksi ini sebagai ringkasan.

---

## 62. Spec Scalping Spot v2 — pengukuran offline 41 pair (2026-10-20)

> ⛔ **DIHENTIKAN 2026-10-21 — Spec v2 dihentikan.** Seluruh artefak v2 (dokumen spec, dua
> laporan hasil, tool, dataset, dan hasil JSON/CSV) sudah dihapus dari pohon kerja; perintah di
> seksi ini **tidak bisa dijalankan lagi**. Angka di seksi ini hanya arsip keputusan, bukan
> acuan aktif. Jangan membangun ulang spec/engine dari seksi ini. Keputusan lengkap ada di **§64**.

**Latar belakang (historis).** Spec v2 (`docs/exp/scalping_spot_strategy_spec.md`, **sudah
dihapus 2026-10-21**) dulu dipetakan ke codebase, tetapi belum punya satu angka edge pun.
Dataset `data/tokocrypto_spot` (41 pair, 90 hari, 15m dan 1h) dulu tersedia, sekarang sudah
dihapus. Batch ini membangun harness pengukuran offline lebih dulu, **tanpa menyentuh kode
aplikasi**, supaya Fase 1 Kotlin tidak dibangun di atas asumsi.

**Tool baru (historis; sudah dihapus 2026-10-21).**

| Berkas | Isi |
|---|---|
| `tools/spot_lib.py` | loader CSV, indikator (EMA/ATR/RSI/ADX/BB/Keltner/VWAP/volMA/percentile), swing/level, fee/P&L mirror `FeeSchedule`, parameter setup, agregasi HTF |
| `tools/spot_mfe.py` | MFE/MAE median 1h/4h/7h pada candle 15m; ambang `2 x F` |
| `tools/spot_backtest.py` | harness 5 setup, G0–G8, exit §7, funnel, metrik, split 70/30, timeframe 15m/1h, kebijakan TP1 `floor`/`literal`, override gate riset |
| `tools/test_spot_backtest.py` | 10 uji mekanika: SL-before-TP, TP1 60%, TP2 setelah TP1, stop tidak turun, time-stop, impas `FeeSchedule`, HTF tanpa lookahead, TP1 `floor` vs `literal` |

**MFE 15m (90 hari).** Hasil pengukuran (berkas hasil sudah dihapus 2026-10-21):

- Layak: **4/41** — MUBARAK 2,78x, PUMP 2,58x, ZRO 2,34x, PLUME 2,12x.
- Tabel lama 14 pair; **10 turun** (ENA, NEAR, WLD, STRK, W, S, FET, APT, FIL, QNT), **0 naik**.
- Median MFE 7 jam lintas pair 1,4371%; median rasio 1,4834x.
- Sampling `step=1` vs `step=2` pada BTC/MUBARAK/ZRO berbeda <0,03 pp.

**Backtest.** 14 varian (berkas hasil sudah dihapus 2026-10-21); ringkasan yang menentukan:

| Varian | n | avg R | WR | IS / OOS |
|---|---:|---:|---:|---|
| 15m produksi (G0, lantai 6x, gates default) | **0** | - | - | - |
| 15m literal (kontrol TP1) | **0** | - | - | - |
| 15m no-G0 | 1 | −0,8084 | 0,0% | 0 / 1 |
| 15m riset longgar (lantai 5x, G3 1,0F, G4–G6 longgar) | 44 | −0,2260 | 22,7% | −0,1542 / −0,2978 |
| 15m riset longgar (lantai 4x) | 37 | −0,2758 | 21,6% | −0,1965 / −0,3798 |
| 1h produksi | **7** | −0,3835 | 0,0% | −0,3769 / −0,3885 |
| 1h produksi, 4 pair MFE baru | 5 | −0,3997 | 0,0% | −0,3676 / −0,4211 |
| 1h literal | 6 | −0,3841 | 0,0% | −0,3769 / −0,3914 |
| 1h no-G0 | 13 | −0,4028 | 7,7% | −0,4265 / −0,3750 |
| 1h no-G0, lantai 5x + G4–G6 longgar | 60 | −0,2087 | 23,3% | −0,0869 / −0,4046 |
| **1h no-G0, lantai 4x + G4–G6 longgar** | **74** | **−0,2055** | 23,0% | **−0,0912 / −0,3828** |
| 1h no-G0, tanpa lantai | 54 | −0,3373 | 20,4% | −0,3419 / −0,3288 |

**Temuan.**

1. **Gates produksi tidak bisa dieksekusi di 15m.** 0 trade dari 41 pair/90 hari. Lantai 6x
   (5,8128%) + G2 maksimum 5 ATR + G3 minimum 1,5F (1,4532%) saling mengunci; ATR% 15m sebagian
   besar 0,2–1,1%.
2. **Gates produksi 1h menghasilkan 7 trade, semuanya rugi** (WR 0%, avg R −0,3835). Sampel kecil,
   tetapi cukup untuk menyatakan gates hampir tidak bisa dieksekusi.
3. **Semua varian riset tetap negatif.** Yang terbaik −0,2055R (74 trade) dan memburuk
   out-of-sample (−0,3828). Tidak ada tanda edge.
4. **SWEEP_RECLAIM sumber kerugian utama** dan konsisten rugi di semua konfigurasi (6–67 trade,
   −0,17 sampai −0,40R); alasan keluar terbanyak `SETUP_INVALIDATION` (32 dari 74 trade pada
   varian 1h lantai 4x). BREAKOUT_RETEST negatif pada 14–22 trade di 15m dan positif hanya n=6
   di 1h (+0,19 sampai +0,21R); SQUEEZE n=1–7 dengan tanda berubah-ubah; PULLBACK 2 trade
   (keduanya rugi); RANGE 0.
5. **Kontradiksi TP1/G4 terbukti.** TP1 formula `entry + 1,0R` selalu memberi `netRr < 1`, sehingga
   G4 menolaknya; mode `literal` menghasilkan 0 trade untuk setup formula. Mode `floor`
   (`entry + max(1,0R, R + 2F)`) adalah satu-satunya cara setup formula lolos.
6. **Memperbarui `PairEdgeGate` ke 4 pair tidak menyelamatkan strategi.** 1h produksi pada 4 pair
   itu: 5 trade, semua rugi, avg R −0,3997.

**Keputusan owner (2026-10-21).** Program **dihentikan**; Fase 1 Kotlin **tidak dimulai**.
Opsi yang dulu terbuka — (a) perbaiki kontradiksi gate/TP1 dan rancang ulang SWEEP, (b) pindah
horizon, atau (c) hentikan program — sudah dijawab dengan (c). Keputusan lengkap dan arah baru
ada di **§64**. Jangan menurunkan lantai 6x hanya untuk menambah sinyal; varian tanpa lantai
justru −0,3373R.

**Batas.** Harness Python, bukan mesin Kotlin; harga fill = level mentah (slippage lewat
`FeeSchedule`); G8/orderbook tidak diuji; risiko per simbol, bukan portofolio; time-stop dan
lookback dalam candle sehingga 1h mengubah horizon; universe dipilih dari jendela yang sama
(bukan out-of-sample); G0 memakai tabel lama yang kedaluwarsa. Uji mekanika 10/10 lulus;
determinisme CSV diverifikasi (SHA-256 identik).

**Dokumen (historis).** Dua laporan hasil v2 (`exp_mfe_m15_41pair.md` dan
`exp_spot_backtest_41pair.md`) serta laporan sinkronisasi Spec v2 sudah dihapus 2026-10-21.
Yang masih ada hanya ringkasan keputusan di `workplan2.md` §64 dan
`docs/PANDUAN-LANJUT-AGEN.md` §4 (kotak arsip Spec v2).

---

## 63. Riset Spec v3 dihentikan dan diarsipkan (2026-10-21)

**Keputusan owner:** riset Spec v3 **dihentikan**. Commit v3 `ada71dc` (dokumen Spec v3) dan
`796a9d1` (harness v3 + dataset 180 hari + laporan) **dibuang dari `patch`**. `patch` di-rebase
ke `main` dan sekarang berisi **13 commit konten** di atas `main` (batch 6o/6p) + commit
dokumentasi 2026-10-21. `40257e4` = commit konten terakhir; `main` = `578d0de` (merge rilis
3.9.2).

**Kenapa berhenti.** V3 dibuat untuk menutup kontradiksi TP1/G4 dan mencabut G3. Hasil
pengukurannya tetap negatif:

- Gates produksi 15m dan 1h: **0 trade out-of-sample**. Satu-satunya trade produksi ada di
  jendela A (1 trade, avg R −0,4849).
- Diagnostik tanpa G0: 15m jendela B **7 trade**, avg R **−0,2495** (R kotor −0,1061);
  1h jendela B **45 trade**, avg R **−0,2561** (R kotor −0,1146). Panel penuh 180 hari:
  15m **12 trade** (−0,2888), 1h **67 trade** (−0,2391).
- Benchmark entry: SWEEP 1h panel penuh (N=160) R kotor strategi **−0,1165** vs acak rata-rata
  **+0,0157** (p99 +0,1122). Entry kalah dari acak.

Angka di atas berasal dari laporan v3; rincian tabel, batas, dan kontrol negatifnya ada di
arsip v3 di bawah, bukan di branch ini.

**Arsip.** Branch `backup/patch-v3-2026-10-21` (remote) @ `796a9d1` berisi seluruh artefak v3:
`docs/exp/scalping_spot_strategy_spec.md` versi v3, `tools/spot_evidence.py`,
`tools/spot_benchmark.py`, dataset `data/tokocrypto_spot_180` (868.054 bar), serta
`docs/LAPORAN-harness-spec-v3-2026-10-21.md` + `docs/exp/exp_spot_evidence_v3.md`.
**Jangan membuat ulang berkas-berkas itu di `patch`.**

**Yang berlaku sekarang.** **Tidak ada spec baru yang aktif.** Spec v2 juga dihentikan pada
2026-10-21 dan **seluruh artefaknya sudah dihapus** (lihat **§64**). Fokus aktif: **engine
kriptoyoi yang sudah ada + slider**. Jangan membangun ulang v2/v3 dari dokumen lama. Kalau riset
dilanjutkan, mulai dari branch backup atau branch baru dengan keputusan owner tertulis.

**Berkas terkait.** `AGENTS.md` (kotak penghentian v2/v3 di Langkah 0 + aturan turunan) dan
`docs/PANDUAN-LANJUT-AGEN.md` (header + §2 + §4). Laporan sinkronisasi v2 sudah dihapus.

---

## 64. Spec v2 dihentikan; fokus engine kriptoyoi + slider (2026-10-21)

**Keputusan owner.** Riset **Spec v2 dihentikan**, menyusul Spec v3 yang sudah lebih dulu
dihentikan pada tanggal yang sama. Keduanya sudah diukur dan **tidak menghasilkan edge**; owner
menilai line riset ini hanya memperpanjang research. **Tidak ada spec/engine baru yang akan
dibangun.** Arah aktif adalah **engine scalping kriptoyoi yang sudah ada + slider**.

**Alasan terukur (arsip, bukan acuan aktif).**

- Spec v2, 2026-10-20: produksi 15m **0 trade** dari 41 pair/90 hari; produksi 1h **7 trade,
  semuanya rugi** (avg R −0,3835); varian riset terbaik **−0,2055R** dengan out-of-sample
  **−0,3828**; MFE 15m 90 hari hanya **4/41 pair** layak.
- Spec v3, 2026-10-21: gates produksi **0 trade out-of-sample**; diagnostik tanpa G0 tetap
  negatif; entry kalah dari acak. Commit v3 sudah dibuang dari `patch` dan diarsipkan di branch
  `backup/patch-v3-2026-10-21` (`796a9d1`, remote).
- Angka lengkap v2 ada di §62; dua laporan hasilnya sudah dihapus 2026-10-21. Angka v3 ada di
  arsip branch backup, bukan di branch ini.

**Yang dihapus.**

- `docs/exp/scalping_spot_strategy_spec.md` (Spec v2) **dihapus** dari pohon kerja 2026-10-21.
- Dua laporan hasil v2 (`docs/exp/exp_mfe_m15_41pair.md` dan
  `docs/exp/exp_spot_backtest_41pair.md`) **dihapus** 2026-10-21.
- Tool v2 (`tools/spot_lib.py`, `tools/spot_mfe.py`, `tools/spot_backtest.py`,
  `tools/test_spot_backtest.py`), dataset `data/tokocrypto_spot/`, dan hasil
  `data/hasil/mfe_m15_spot.json` + `data/hasil/spot_backtest_v2_*` **dihapus** 2026-10-21.
- Perluasan khusus v2 pada `tools/fetch_tokocrypto_klines.py` dikembalikan ke versi sebelum v2
  (tool unduh generik tetap ada untuk P4.1); snapshot
  `data/config/binance_24h_movers_reference.json` **dihapus**.
- Semua penghapusan ini sudah **di-commit dan di-push** ke `origin/patch`. Jangan membuat ulang
  berkas atau riset v2.

**Arah baru yang dicatat.**

- **Slider entry** sudah diimplementasikan dan, menurut owner, **terbukti memaksimalkan entry**.
  Ini status dari owner, bukan hasil pengukuran baru yang ditambahkan ke `docs/exp/` pada
  batch ini.
- **Exit strategi belum** diselesaikan lewat slider. Jangan mengklaim exit sudah beres atau
  menjadikan klaim entry sebagai jaminan profit.
- **Kode aplikasi tidak disentuh.** Penghapusan tool/data v2 adalah pembersihan artefak riset
  sesuai instruksi owner, bukan perubahan engine. Penghapusan dan dokumen ini sudah
  **di-commit dan di-push**; perubahan kode aplikasi lain menunggu instruksi owner.

**Konsekuensi dokumen.** `AGENTS.md` (Langkah 0, `docs/exp/`, aturan turunan, arah produk,
perintah build) dan `docs/PANDUAN-LANJUT-AGEN.md` (header, §2, §4) sudah disesuaikan. Dua
laporan hasil v2 dan laporan sinkronisasi v2 sudah dihapus, bukan diberi banner. Dokumen ini dan
`AGENTS.md` adalah acuan status; §62/§63 bersifat historis.

---

## 65. P0 KriptoYoi disinkronkan: TTL, MTF 4 TF, veto BTC, regime OR (2026-10-22)

**Keputusan.** P0 KriptoYoi **disinkronkan ke engine yang sudah ada**, bukan sebagai engine baru.
Lingkupnya hanya empat hal: TTL 5 candle M1, gate MTF 4 TF, veto BTC, dan regime OR. Struktur
setup, bobot skor, dan exit **tidak** diubah. Patokan implementasi adalah `p0_engine.py`; bila
keputusan owner lebih ketat, keputusan owner yang dipakai.

### 65.1 Yang berubah di kode

| Area | Berkas | Aturan |
|---|---|---|
| TTL | `engine/scalping/SignalTtl.kt`, `SignalLifecycleManager.kt` | Umur dihitung dari jam dinding (`elapsed_sec // 60`), bukan jumlah tick. `EXPIRED` **terminal** untuk setup + arah yang sama; setup yang hilang boleh memulai episode baru. |
| Gate MTF | `MtfAlignmentPolicy.kt`, `MtfConfluenceMatrix.kt`, `ScalpingMtfEvaluator.kt` | 4 TF: H1, M15, M5, M1. H1/M15 tidak boleh bearish; minimal `mtfMinBullish` (default 3) bullish; **fail-closed** bila `knownCount < 4`. Confidence non-ready di-cap 39 saat gate menolak. |
| Veto BTC | `BtcPulseAnalyzer.kt`, `GlobalContextManager.kt` | Veto LONG altcoin bila close 5M < EMA20 dan return 5M terburuk < −0,6%, atau ATR ratio > 2,5× dengan ≥2/3 candle bearish. Hanya candle tutup. Pair BTC sendiri tidak diveto. |
| Regime | `MarketRegimeEngine.kt` | Urutan p0: LOW_VOL (ATR rendah + CHOP tinggi), BREAKOUT, trending (`ADX ≥ 25 && CHOP < 55`), choppy OR (`CHOP ≥ 61,8 || ADX < 20`), mild trend. Semua ambang dari `ScalpingRuntimeConfig`. |
| Wiring | `LearningTradingEngine.kt`, `CandidateScanWorker.kt`, `MtfCacheManager.kt` | M5 dimuat bersama H1/M15/M1; readiness foreground dan worker mensyaratkan 4 TF. Evaluator tidak dijalankan sebelum M5 siap. |
| Config | `ScalpingConfig.kt`, `ScalpingRuntimeConfig.kt`, `data/config/scalp_config.json` | Seluruh angka P0 ada di config runtime dan mirror replay. Tidak ada angka gate baru yang ditulis sebagai literal di engine. |

Perbaikan kecil yang ikut: `GlobalContextManager` tidak lagi menulis harga **BTCIDR** ke field
`btcPriceUsdt`. Harga pulse hanya mengisi field itu bila sumbernya memang USDT; status veto tetap
dihitung dari kuotasi bursa yang dipakai.

### 65.2 Replay

`tools/scalp_replay.py` sekarang:

1. Mengagregasi M5 dari M1 (grid UTC) dan memakai gate MTF 4 TF yang sama.
2. Membaca BTC 5M/1M dari `BTCUSDT_1m.csv` (atau `BTCIDR_1m.csv` untuk pair IDR), hanya candle
   tutup. Tanpa berkas BTC, output menulis `btc_source.available=false` dan veto **tidak diukur**.
3. Memakai regime OR dan seluruh ambang P0 dari config.
4. Menulis `gate_blocks`, `btc`, `p0`, dan `btc_source` di JSON.

`tools/test_scalp_replay_parity.py` diperluas menjadi **18 test** dan lulus, termasuk gate MTF,
fail-closed tanpa M5, veto BTC, dan sakelar config.

### 65.3 Hasil terukur (ADA, DOGE, XRP)

Data 87.000 bar M1 per pair, 2026-08-12 s/d 2026-10-12, orderbook diasumsikan tersedia.
Baseline dijalankan dari commit `2dd69ad` (kode + config sebelum P0).

| Metrik | Baseline | P0 |
|---|---:|---:|
| Trade | 996 | 620 |
| Win rate | 12,85% | 14,84% |
| Avg R | −0,1642 | −0,1569 |
| Exit TIMEOUT | 990 | 616 |
| Exit SL | 6 | 4 |
| Exit TP1/TP2 | 0 | 0 |

**Tidak ada edge.** Avg R masih negatif, dan **616 dari 620 trade P0 keluar karena time-stop
90 menit**. Tidak satu pun trade menyentuh TP1/TP2. Perbaikan +0,0073R berada di dalam noise
(CI 95% baseline dan P0 saling tumpang tindih). MTF adalah gate yang dominan; veto BTC hanya
aktif 0,57% pemeriksaan per menit dan praktis netral pada jendela ini.

Ablasi ADA: `regime_only` 347 trade / −0,1622R; `mtf_only` 210 / −0,1560R; `btc_only` 346 /
−0,1623R; `full` 210 / −0,1568R. Gate MTF memotong frekuensi dan sedikit memperbaiki rata-rata;
BTC tidak memperbaiki apa pun pada sampel ini.

### 65.4 Batasan yang tidak boleh diklaim

- **TTL tidak diuji replay.** Replay masuk pada bar sinyal; TTL mengatur penantian setup di app.
  Manfaat TTL harus dibuktikan lewat paper/observasi app, bukan dari tabel trade ini.
- **M5 disintesis dari M1**, bukan data M5 bursa langsung.
- **Win rate bukan hit rate TP.** 99,4% trade P0 berakhir TIMEOUT; menang berarti profit kecil
  saat time-out. Jangan membandingkan langsung dengan laporan yang punya TP2 tercapai.
- **Satu jendela data, tiga pair, fee USDT taker.** Pair IDR dan Indodax tidak diuji.
- **Angka gate adalah bar, bukan trade.** 3.525 bar ditolak MTF bukan berarti 3.525 trade hilang.

### 65.5 Keputusan lanjutan

1. P0 dipertahankan karena aturannya benar dan gate bekerja sesuai spesifikasi.
2. **Jangan promosikan ke uang riil.** Edge belum ada.
3. Prioritas berikutnya **exit strategi**, bukan menambah gate entry. Seluruh exit saat ini
   bergantung pada time-stop; TP1/TP2 tidak pernah tercapai dalam 90 menit.
   *(Diperbarui §68–§69, 2026-10-22: riset exit-only diukur dan ditutup; arah berikutnya
   pengukuran entry/universe.)*
4. Setiap perubahan exit wajib diukur dengan tabel yang sama (termasuk TIMEOUT dan trade
   `UNRESOLVED`), bukan hanya win rate.

**Dokumen.** Rincian angka ada di `docs/exp/exp_p0_gatekeeper_ADA-DOGE-XRP.md`. `AGENTS.md` dan
`docs/PANDUAN-LANJUT-AGEN.md` sudah diperbarui pada tanggal yang sama. Verifikasi: **797 unit
test Kotlin (0 gagal, 1 dilewati)** dan **18 test parity replay**.

---

## 66. Eksperimen exit: MFE/MAE dari bar sinyal + mode EARLY (2026-10-22)

Laporan lengkap: **`docs/exp/exp_exit_modes_ADA-DOGE-XRP.md`**. Dataset P0 dipakai ulang persis
(ADA/DOGE/XRP, 60 hari); kontrol `classic` terukur dua kali dan keduanya **persis angka laporan
P0** (620 trade / WR 14,84% / avg R −0,1569 / pf 0,172), membuktikan komparabilitas.

**Fase A — MFE/MAE dari bar masuk sinyal** (field baru di `tools/scalp_replay.py`
`mfe_/mae_` 30/60/90 menit, dibaca helper `_entry_excursions`, diagregasi di
`summary.entry_mfe`): median MFE 90 menit **+0,506%** (≈ setengah friksi 0,9688%), median MFE
selalu positif di semua horizon — **observasi owner "posisi naik setelah sinyal buy" terkonfirmasi**.
Tapi ≥1× friksi hanya 31,6% sinyal, ≥2× friksi (lantai S2) hanya 13,4%, ≥TP1 hanya 1/618 (0,16%),
dan SL 5,81% tersentuh ≤0,65% sinyal. TP1 praktis mustahil dalam 90 menit; SL lebar bukan pengikat.

**Fase B — mode EARLY** (config varian `scalp_config_exit_early_p0.json`, satu perubahan
`exit.mode = early`, S2 tetap 2,0×): 53 `EARLY_PROFIT` rata-rata **+0,3208R** + 28 `BE_SL`
ke impas (0,00R). Perbaikan avg R **+0,0047R — di dalam noise** (CI 95% (−0,1692, −0,1352) vs
(−0,1748, −0,1389) tumpang tindih). Gerbang Fase C (grid S2) tidak tercapai, jadi tidak dijalankan.

**Keputusan yang tetap:**
1. Gate entry P0 tidak diutak-atik; tidak ada gate baru.
2. Bukan bukti menambah/menghapus mode exit — angkanya bilang geometri saat ini (risiko 5,81%,
   kunci profit ≈0,32R, payoff ≈0,99) butuh WR ~50% untuk impas, sedangkan hit rate ~15%.
   *(Diperbarui §69, 2026-10-22 — opsi "unit risiko lebih kecil" lewat revisi
   `min_friction_multiple` **sudah diukur dan tertutup**: 0 dari 27 kombinasi untung, dan biaya
   per trade ≈ satu friksi di semua kelipatan. Default 6,0 dipertahankan dengan alasan plateau
   uang. Yang tersisa hanya kualitas entry/universe, dan gate entry baru menunggu instruksi
   owner.)*
3. Verifikasi: **22 test parity OK** (18 lama + 4 uji MFE).

---

## 67. Eksperimen: paritas entri BUY app vs spek kriptoyoi frontend (2026-10-22)

Laporan lengkap: **`docs/exp/exp_entry_parity_kriptoyoi_ADA-DOGE-XRP.md`**; alat
`tools/entry_parity_kriptoyoi.py` (port setia `generateScalperSignals` app.js kriptoyoi,
fee truth dari `data/config/scalp_config.json` saja). Data yang sama dengan laporan P0
(ADA/DOGE/XRP, 87.000 bar M1, 60 hari); angka engine app di harness **persis laporan P0**
(620 / WR 14,84% / −0,1569) sehingga pembandingnya kompatibel.

**Jawaban owner: entri BUY tidak sama.** Kriptoyoi frontend (lapisan A, EMA/StochRSI per
candle tutup) menandai **15.269 entri** vs **620** entri engine app (≈24,6× lebih sering);
irisan bar entri hanya **77 (12,4% entri app)**, Jaccard ≈0,5%. Dengan geometri exit,
lantai friksi 6× dan fee truth yang identik, entri kriptoyoi juga terukur lebih buruk:
WR 10,33%, avg R **−0,1627** (app: 14,84%, −0,1569). Kualitas 77 bar irisan tidak lebih
baik (WR 14,29%/12,50%/0,00%, avg R −0,1707/−0,1397/−0,2008).

**Keputusan:**
1. Lapisan A kriptoyoi **tidak di-port** ke app (owner juga memaknainya frontend visual,
   bukan engine); experimen membuktikan menggantikan/menumpuk gate baru tidak layak —
   keduanya negatif di unit risiko 5,81%.
2. Paritas rendah bukan bug; jangan mencampur angka kedua sumber di dokumen lain
   (aturan label geometri FEE_TRUTH).
3. Verifikasi: `--selftest` OK (kontrol negatif 0 BUY, positif 1 BUY); konsistensi
   harness vs CLI DOGE = 193 trade (ditemukan & diperbaiki dulu: HTF harus disintesis
   dari M1, bukan CSV 15m/1h).

---

## 68. Eksperimen exit net-TP: TP kecil + stop rapat (2026-10-22)

Laporan lengkap: **`docs/exp/exp_exit_net_tp_ADA-DOGE-XRP.md`**; alat `tools/exit_grid.py`
(evaluasi gate sekali per pair, lalu grid exit di atas cache; kontrol `classic` identik CLI:
620 trade / WR 14,84% / avg R −0,1569). Dataset sama dengan P0: ADA/DOGE/XRP, 87.000 bar M1,
60,42 hari, fee truth `data/config/scalp_config.json`.

**Mode baru (replay-only):** `net_tp` (keluar saat profit NET ≥ `net_tp_pct`, harga dari
`_price_for_net`) dan `net_tp_stop` (tambahan stop rugi harga `stop_gross_pct` lebih rapat
daripada SL engine; prioritas SL engine → NET_SL → NET_TP → TP2). Keduanya **tidak** ada di
app dan tidak boleh disambungkan tanpa instruksi owner: stop rapat melanggar lantai friksi 6×.

**Hasil: 0 dari 88 varian positif** (+ kontrol `classic` di tiap keluarga). Tiga keluarga diuji:
20 varian `net_tp` (5 TP × 4 hold), 60 varian `net_tp_stop` (TP 0,4–1,5% × stop 1,0–2,5% ×
hold 240/420/1440m), dan 8 varian horizon 1–2 hari (TP sampai 3%). Terbaik `net_tp 3,0%/2880m`: −0,410%/hari dengan hanya
0,39 exit net/hari. WR tinggi bukan keberhasilan: ADA mencapai WR 75,7% tetapi avg R −0,1156.

**Batas atas membuktikan exit-only mustahil di sinyal ini.** Pada 1440m, sinyal yang HIGH-nya
pernah mencapai TP net 1,0% hanya 72,0% (ADA) / 54,5% (DOGE) / 60,4% (XRP) — itu skenario exit
sempurna tanpa stop. WR impas varian `tp1.0/sl2.5/1440m` justru 76,8/72,6/72,8%. Untuk
`net_tp 3,0%/2880m`: batas atas 60,9/48,1/46,6% vs WR impas 64,2/59,3/53,0%. Semua di bawah
WR impas, jadi tidak ada parameter exit yang bisa menutup selisihnya.

**Kriteria owner "1 exit net/hari" tidak tercapai.** Frekuensi tertinggi 1,82 exit/hari (ADA,
`tp0.4/sl2.0/1440m`) tetapi hanya 54,6% hari punya ≥1 exit net dan net −2,06%/hari; varian
dengan kerugian terkecil justru 0,39 exit/hari. Frekuensi dan expectancy saling bertukar,
tidak ada titik yang memenuhi keduanya.

**Keputusan:**
1. Line riset exit-only ditutup untuk sinyal P0; tidak ada parameter exit yang direkomendasikan,
   jangan promosikan ke uang riil.
2. Perbaikan berikutnya harus dari **entry/universe** (median MFE ≥ 2× friksi), bukan exit.
   Sesuai `AGENTS.md`, gate entry baru menunggu instruksi eksplisit owner.
3. Verifikasi: **25 test parity OK** (22 lama + 3 uji net-TP/net-SL).

---

## 69. Sweep lantai friksi berbasis uang (2026-10-22)

Laporan lengkap: **`docs/exp/exp_friction_multiple_ADA-DOGE-XRP.md`**; alat
`tools/friction_sweep.py`. Menjawab pertanyaan owner: `min_friction_multiple = 6.0` diwarisi
dari eksperimen lama yang menilai **R**, padahal R didefinisikan oleh SL sehingga bias ke SL
lebar. Sweep ini mengubah **hanya** kelipatan friksi; data (ADA/DOGE/XRP, 87.000 bar M1, 60,42
hari), gate P0, fee, `target_net_rr`, dan aturan exit identik. Kontrol mult 6,0/hold 90m
identik CLI: 620 trade, WR 14,84%, avg R −0,1569.

**Hasil: 0 dari 27 kombinasi (9 kelipatan × 3 hold) positif.** Pada hold 420m (rata-rata 3
pair): mult 1,0 −1,735%/hari; 2,0 −1,075; **3,0 −0,993 (terbaik)**; 4,0 −1,024; 5,0 −1,045;
6,0 −1,078; 8,0 −1,080. Mult 0,5 degenerate (3–10 trade/60 hari) karena `tp2/risk` melampaui
`max_tp2_r` dan sinyal ditolak.

**Temuan:**
1. **6,0 bukan penyebab kerugian.** Semua kelipatan rugi; 3,0 hanya +0,085%/hari lebih baik
   dan tetap ≈ −60% dalam 60 hari. Jangan mengutip bukti lama "0,5% → −1,8R di 28/28 pair".
2. **R menyesatkan.** Hold 420m: mult 8 paling baik di R (−0,096) tetapi lebih buruk di uang
   (−0,747%/trade) daripada mult 3 (−0,230R, −0,667%/trade). Peringkat R ≠ peringkat uang.
3. **Jumlah putaran lebih menentukan daripada lantai.** Mult 6: hold 90m −3,119%/hari; 240m
   −1,668; 420m −1,078. Menahan lebih lama hanya mengurangi frekuensi membayar friksi.
4. **Biaya per trade ≈ satu friksi** (−0,65 s.d. −0,75%) di semua kelipatan — tanda entry tidak
   punya edge; lantai friksi tidak bisa memperbaikinya.

**Keputusan:** default **6,0 tetap** (konservatif, stop-out 3,8% vs 12,6% pada 3,0, DD
setara), tetapi **alasannya diganti**: plateau uang, bukan klaim R lama. Perbaikan harus dari
entry/universe dan menunggu instruksi owner. Verifikasi: **26 test parity** (25 + 1 uji bahwa
`want_risk_inputs` tidak mengubah keputusan gate).

---

## 70. Sweep 5 mode exit pada sinyal P0 (2026-10-24)

Laporan lengkap: **`docs/exp/exp_exit_mode_sweep_ADA-DOGE-XRP.md`**; alat
`tools/exit_mode_sweep.py`. Owner membuka kembali riset exit secara terbatas pada 2026-10-24
(paper/offline saja). Data dan gate sama dengan P0: ADA/DOGE/XRP, 87.000 bar M1, 60,4 hari,
fee USDT, `min_friction_multiple = 6,0`.

**Hasil: 0 dari 90 sel pair × mode × hold positif; 0 dari 30 sel agregat positif.**
Kontrol `classic` 90m identik CLI: 620 trade / WR 14,84% / avg R −0,1569. Dua metode
dibandingkan: *sequential* (skip sampai exit, jumlah trade bisa berbeda) dan *same-entry*
(entri classic yang sama di-resolve ulang semua mode).

| Hold | Mode terbaik (net/trade) | Angka | Classic | Selisih |
|---|---:|---:|---:|---:|
| 90m | early | −0,8792% | −0,9118% | +0,0326 |
| 240m | tp1_be | −0,7756% | −0,8420% | +0,0664 |
| 420m | classic | −0,7430% | −0,7430% | 0 |

Semua CI 95% bootstrap (same-entry) berada di bawah nol. `tp1_be` hanya sedikit menolong pada
240m karena beberapa TP1 tersentuh; pada 90m ia identik dengan classic karena TP1 (+7,99%
kotor) praktis tidak tercapai. `full` hampir identik dengan `trail` (early hanya 1 kali).
Menahan lebih lama mengurangi frekuensi membayar friksi (net/hari classic −3,12 → −1,08),
tetapi tidak pernah positif.

**Keputusan:** tidak ada kandidat; exit-only untuk sinyal P0 kembali ditutup. Tidak ada
perubahan app/config/realtrade. Bottleneck tetap entry/universe (median MFE 90m +0,506% <
friksi 0,9688%). Verifikasi: **30 test parity** (26 lama + 4 baru) dan kontrol negatif
`bucket_of()` yang sengaja dirusak (uji GAGAL, lalu OK setelah dikembalikan).

---

## 71. Persentil harga entri P0 — jawaban "beli di pucuk" (2026-10-24)

Laporan lengkap: **`docs/exp/exp_entry_price_percentile_ADA-DOGE-XRP.md`**; alat
`tools/entry_percentile.py`. Sampel 620 entri `classic` hold 90m; jendela 1/3/7 hari.

**Temuan:** entry khas berada di **persentil 93,3** harga 1 hari terakhir (75,6% di kuintil
teratas) dan **81,7%** rentang high-low harian; persentil 3 hari 84,6 dan 7 hari 82,7. Return
close maju: 1 hari **−0,108%**, 3 hari **−0,051%**, 7 hari **+1,148%**. Kuintil teratas 7 hari
median **−2,596%** vs kuintil terbawah **+8,259%** (n=293 vs 43; kuintil bawah kecil dan bias
periode).

**Pembacaan:** keluhan "beli di pucuk" benar secara posisi, tetapi bukan sinyal jual bersih —
kuintil teratas 1 hari masih +0,264%, dan persentil tinggi sebagian mekanis dalam tren naik.
Ini menjelaskan kesulitan scalp: entry membeli kekuatan yang sudah naik, sementara friksi
0,9688% dibayar lebih dulu. **Keputusan:** diagnostik saja; tidak ada perubahan gate/exit/app.

---

## 72. Fase 1 auto-order paper — integritas eksekusi dan sizing SL (2026-10-24)

Fase 1 (bukan partial TP1 / gap fill) menutup kelas bug di jalur paper agar kegagalan jual
tidak tercatat sebagai sukses, SL yang dipakai sizing sama dengan SL mesin exit, dan posisi
tidak dijual melebihi quantity-nya. Jalur real trade tidak diubah; resolver yang ditambah
murni dan bisa dipakai ulang.

Perubahan kode:
- `engine/scalping/PaperStopResolver.kt` — tambah `PaperStopPlan` dan `resolveForEntry(...)`:
  lantai friksi + SL hasil resolver dalam satu langkah.
- `viewmodel/PaperAutoTradeCoordinator.kt` — `armPaperExitEngine` dan `tryPaperAutoBuy` memakai
  `resolveForEntry`; sizing RISK_BASED memakai SL hasil resolver, bukan SL sinyal mentah;
  entry/qty memakai harga dan quantity fill; kepemilikan exit ditentukan
  `PaperAutoTradeRuntime.ownsExit`; fallback legacy memakai SL hasil resolver dan tidak
  dinyalakan bila SL tidak valid; `evaluatePaperAutoExitLocked` mempertahankan FSM + snapshot
  bila SELL gagal; `finalizePaperExit` mengembalikan `false` dan tidak melepas mesin/FSM bila
  SELL gagal.
- `viewmodel/TradingViewModelAlerts.kt` — `executeAutoSellOrder` jalur paper menjual hanya
  `quantity.coerceAtMost(simBal)`, tidak melepas ownership saat order gagal atau saldo 0, dan
  mengembalikan sukses aktual.

Verifikasi: GitHub Actions `Unit Tests (Manual)` run `40098405104` pada commit `f317368`,
scope `all-unit-tests`, status **success**. Parity test dan pemeriksaan markdown belum dijalankan
di commit ini; jalankan bila mengubah dokumen/config.

---

## 73. Jeda entri-ulang auto-buy paper (2026-10-26)

**Temuan owner (25/10/2026, WINUSDT, sekitar 21:12 WIB):** setelah sell paper terpicu,
order buy terpicu kembali tanpa jeda — dugaan karena koin sedang trending naik.

**Pengukuran** (`docs/exp/exp_autobuy_reentry_winusdt.md`):

- Data bursa terbaru via `tools/fetch_tokocrypto_klines.py` (2.999 candle M1 WINUSDT,
  CSV BTC untuk veto). Harga di jendela 20:00–23:00 WIB naik sampai +4,75% (puncak
  21:40 WIB) lalu koreksi — sesuai dugaan "trending up".
- Replay CLI (`classic`): 10 trade, **3 dari 10 entri +60 detik setelah exit**
  (25/10 08:38, 25/10 20:47, 26/10 05:11 WIB). Posisi #6 (entry 20:47, time-stop 22:17)
  mencakup jam keluhan 21:12. WR 10%, avg R −0,2759 — edge masih negatif, tidak berubah.

**Sebab:** `tryPaperAutoBuy` hanya mencegah posisi dobel (`existing.isHolding`) dan menjaga
`maxConcurrentPositions` — tidak ada jeda per pair setelah `finalizePaperExit`/`markSold`.
Begitu posisi hilang, sinyal BUY berikutnya (evaluator masih membaca kondisi trending)
langsung dibeli ulang: dua friksi round-trip beruntun tanpa evaluasi baru.

**Perbaikan — paper saja, jalur uang riil tidak disentuh:**

1. `engine/scalping/PaperReentryCooldown.kt` — resolver **murni** `verify(lastSoldAtMs,
   nowMs, cooldownMinutes)` → `Allow`/`Wait`; logika jeda satu sumber, teruji tanpa Android.
2. `PaperAutoTradeConfig.reentryCooldownMinutes` = **15 menit** default; `0` menonaktifkan.
   Disinkron ke `AppPreferences` (`paper_auto_reentry_cooldown_min_v1`, fallback 15).
3. `SpotPositionStore.markSold(isReal = false)` menulis `…_last_sold_at`;
   `lastPaperSoldAt(symbol, exchange)` membacanya — **persisten**, jeda tetap berlaku
   setelah app di-restart. Posisi uang riil tidak dicatat.
4. `tryPaperAutoBuy`: gerbang jeda ditaruh persis setelah cek posisi dobel, sebelum
   batas posisi dan pembacaan saldo; selama jeda, auto-buy ditunda dengan log alasan
   (`⏳ [SYMBOL] Auto-buy ditunda: jeda entri-ulang …`). Beli manual tidak digated.

**Tetap berlaku / tidak diubah:** gate entry P0, lantai friksi, exit classic, aturan fee
truth, dan semua angka replay CLI (kontrol 620/14,84%/−0,1569 tetap sah).
Jeda hidup di app saja (senada TTL) dan **belum direplay** — dampaknya pada frekuensi
trade terlihat pada paper berikutnya; jangan mengutip angka replay lama sebagai angka
app yang sudah berjeda.

**Verifikasi:**

- Uji baru 13/13 hijau: `PaperReentryCooldownTest` (6), `SpotPositionStorePaperLastSoldTest`
  (4), `PaperAutoBuyReentryCooldownWiringTest` (3).
- Lingkup terkait: 339 test, 0 gagal (`agu.analys.engine.scalping.*`,
  `SpotPositionStore*`, wiring paper). Parity `tools/test_scalp_replay_parity.py` 30/30 OK.
- Kontrol negatif dua titik: gerbang `cfg.reentryAllowed` dilepas → test wiring GAGAL;
  pencatatan `…_last_sold_at` dilepas → test store GAGAL. Keduanya dipulihkan sebelum commit.

Laporan: `docs/laporan_temuan_bugs_paper_reentry_jeda.md`.

---

## 74. Dokumentasi slider exit dalam bahasa awam (2026-10-26)

**Permintaan owner:** kartu setting exit belum menjelaskan artinya — mis. "Profit net
minimum early exit (0,20%)", "Trigger breakeven", "Trigger BE (x ATR)" tidak jelas bagi
trader awam. Gate entry, lantai friksi, dan exit sendiri tidak diubah (semuanya tetap
diatur slider).

**Perubahan** (`ui/components/settings/ScalpingSensitivitySettings.kt`):

- `ExitParamRow` kini punya parameter `hint` — teks penjelasan ditampilkan di bawah baris
  nilai (9 sp), otomatis redup saat parameter tidak aktif untuk mode terpilih.
- Tujuh baris parameter exit diberi penjelasan, semuanya diverifikasi dulu ke engine
  (`ScalpExitConfig.kt`):
  - **Profit net minimum early exit** — laba bersih minimal (sudah memotong fee dua sisi +
    slippage) pemicu EARLY_PROFIT; 0,20% = hasil akhir sekitar +0,20% dari modal.
  - **Trigger breakeven (net)** — setelah laba bersih menembus angka ini, SL dinaikkan ke
    `breakevenPrice` (harga jual yang menghasilkan sekitar 0 setelah semua biaya).
  - **Jarak trailing dari peak** — SL mengikuti puncak; jual bila harga turun sejauh ini
    dari puncak; trailing baru berjalan setelah trigger BE.
  - **Lantai profit (x friksi)** — keluasan bruto minimal = angka x friksi round-trip
    (contoh Tokocrypto USDT tarif bawaan sekitar 0,97%); di bawah itu jual profit ditahan;
    0 = tanpa lantai.
  - **Trigger BE (x ATR)** — BE efektif = nilai terbesar antara persen trigger dan
    ATR x nilai.
  - **Trailing (x ATR)** — jarak trailing = ATR x nilai bila ATR tersedia, kalau tidak
    jatuh ke persen.
  - **Time-stop (bar M1)** — 1 bar = 1 menit; posisi ditutup apa pun hasilnya setelah N
    bar; 0 menonaktifkan.

**Verifikasi:** `:app:compileDebugKotlin` sukses. Tidak ada perubahan angka, gate, atau
engine — murni teks penjelasan UI. Label `contentDescription` tombol +/− mengikuti label
param.

---

## 75. Jeda entri-ulang jadi setting di kartu Paper Trade Otomatis (2026-10-26)

**Permintaan owner:** jeda entri-ulang (fix §73) sebaiknya diatur dari slider — range
**1–30 menit**, bukan angka tetap.

**Perubahan** (`ui/components/settings/PaperAutoTradeSettings.kt`): seksi baru
"JEDA ENTRI-ULANG" di dalam blok `autoBuyEnabled` — stepper menit 1–30 (`StepperRow`
yang sudah ada) + teks penjelasan: fungsinya mencegah beli-ulang seketika setelah sell
saat harga masuk naik kuat (contoh terukur: entri ulang 60 detik setelah sell pada
WINUSDT 25/10/2026); beli manual tidak dijeda; auto-exit tidak terpengaruh.

Jeda menulis ke `config.reentryCooldownMinutes` lewat draft Pengaturan (pola yang sama
dengan sakelar auto-buy; tersimpan di `paper_auto_reentry_cooldown_min_v1`). Default
tetap 15 menit; field config tetap menerima 0 = nonaktif, tetapi UI menjepit 1–30.

**Verifikasi:** `:app:compileDebugKotlin` sukses; tidak ada perubahan engine/gate/angka
selain sumber nilai jeda yang kini bisa diatur dari UI.

---

## 76. Default timeframe chart mengikuti mode strategi (2026-10-26)

**Keluhan owner:** pindah dari mode swing ke scalping, chart tetap terpilih **4H**
("kenapa scalping default-nya 1m/15m?").

**Akar:** `MarketViewModel` membawa bawaan global `Timeframe.H4`
(`MarketViewModel.kt:41`) dan `TradingViewModel.setStrategyMode` tidak pernah
menyentuh `selectedTimeframe`. Jadi default 4H itu **bukan desain per-mode** —
hanya bawaan chart global yang tidak pernah dihubungkan ke mode. Timeframe engine
scalping sendiri sudah benar (H1 bias / M15 setup / M5+M1 trigger, gate P0 MTF
4 TF); yang salah hanya chart.

**Keputusan (owner 2026-10-26, lewat pertanyaan pilihan):**

1. **Peta per-mode:** SCALPING → **M15**, INTRADAY (OFFICE_DAILY) → **H1**,
   SWING → **D1**. Pemetaan mengikuti peran timeframe yang sudah dideklarasikan
   tiap mode (kartu settings: "Bias: 1H · Setup: 15M · Trigger: 1M"; deskripsi
   swing "1H–1D"; intraday "Open Pagi · Close Malam").
2. **Chip tetap bebas dipilih manual** kapan saja — resolver hanya menentukan
   default saat perpindahan mode, tidak mengunci pilihan user.
3. **Tidak persisten per-mode:** selalu ikut mode; pilihan manual user tidak
   diingat antar perpindahan mode.

**Implementasi:**

- Resolver murni **`resolveDefaultTimeframe(mode)`** di
  `model/PositionContext.kt` — satu-satunya tempat pemetaan ini ada; `when`
  tanpa `else` sehingga mode baru membuat kompilasi gagal sampai pemetaannya
  diputuskan sadar (fail-closed, pola resolver lain di berkas itu).
- `TradingViewModel.setStrategyMode`: saat mode **benar-benar berganti** (guard
  `mode != previousMode` — simpan ulang pengaturan tidak memaksa chart lompat),
  TF chart di-set ke hasil resolver, cache pair TF baru dimuat (`loadPairCache`;
  kosong → `clearPairData`, semantik sama dengan `selectPair`), dan polling di
  akhir fungsi otomatis memakai TF baru tanpa start dobel.
- **Startup ikut mode:** `initSubscriptionsAndPolling` kini menyetel TF dari
  `prefs.strategyMode` sebelum `selectPair(initialPair)` — app yang dibuka dengan
  mode scalping tersimpan langsung menampilkan chart M15, bukan H4.

**Verifikasi (terukur):** `DefaultTimeframeResolverTest` **4 test, 0 failures**
(dari XML, bukan ingatan). **Kontrol negatif:** resolver dirusak
(M15 → H4) → test **GAGAL 2 failures**; dipulihkan → hijau lagi. Uji ini bukan
hiasan: ia membuktikan resolver terpakai.

**Yang tidak berubah:** engine, gate, angka P0, frekuensi polling, dan perilaku
`selectTimeframe` manual (tetap reload cache + polling seperti sebelumnya).

---

## 77. Form beli paper tanpa nominal default (2026-10-26)

**Laporan owner:** tombol beli paper pada pair kuotasi USDT menunjukkan default beli
**$50.000**.

**Akar (terverifikasi ke kode):** `WaitingEntryRadarCard.kt` menampung state
`selectedNominal` dengan literal `50000.0` — Rupiah — yang dipakai **apa adanya**
sebagai nominal kuotasi pair: mengalir ke `grossBuyOrderAmount` → tombol beli →
`qty = nominal / harga` dan `invested = nominal` (posisi paper $50.000 bila saldo
USDT cukup; bila tidak, order ditolak engine dengan pesan saldo). State tanpa key
pair, jadi sisa nominal pair lama bocor ke pair baru. Bukan dari config auto-buy —
`PaperAutoTradeConfig.fixedIdrAmount` (Rp 100.000) sudah dikonversi benar di `plan()`
(§18.2). Pola yang sama (nominal IDR dianggap kuotasi), jalur berbeda.

**Keputusan owner (2026-10-26):** form beli paper **TANPA nominal default sama
sekali** — user mengisi sendiri; tombol hanya mengirim perintah beli bila nominal
valid. Tidak ada angka default di Pengaturan.

**Perubahan:**

- `WaitingEntryRadarCard.kt` — nominal awal `0.0` dengan key
  `remember(baseAsset, quoteAsset)` (reset per pair). Literal dihapus.
- **Resolver murni `resolveBuyNominalGate`** (`util/BuyNominalGate.kt`) — satu-satunya
  tempat aturan gerbang: `EMPTY` (kosong/tidak valid; kondisi awal normal),
  `BELOW_MIN` (di bawah `minOrderNominal`; minimum 0 = kurs belum ada → cek dilewati,
  bukan kurs tebakan), `OVER_BALANCE` (saldo 0 dihitung saldo kosong yang diketahui —
  fail-closed), `OK`. Pesan teks dibentuk di UI, bukan di resolver.
- `RadarBuySection.kt` — `coerceAtLeast(minNominalQuote)` dibuang (tidak ada potong/
  naik diam-diam); tombol `[SIM]/[REAL] BELI` hanya aktif bila gerbang OK, dengan
  alasan terbaca di tombol ("Isi nominal pembelian dulu", "Minimum order …",
  "Melebihi saldo …"). Chip cepat (rekomendasi risiko / % saldo) tetap ada — itu
  aksi eksplisit user.
- `CustomBuyOrderDialog.kt` — input nominal mulai kosong bila form utama belum
  berisi nominal valid (literal Rp 50.000 / 10 USDT dihapus); gerbang saldo jujur:
  `availableIdr <= 0 ||` dibuang, saldo 0 kini memblokir.
- `PriceFormatter.defaultOrderNominal` — **dihapus**: helper konversi nominal default
  dengan 0 pemanggil (kontrak palsu, preseden P7).
- `RadarTransactionFeeSection.kt` — default param `selectedNominalIdr = 50000.0`
  (nilai mati) dihapus; parameter kini wajib.

**Penjaga & verifikasi (terukur):**

- `BuyNominalGateTest` — 7 test, 0 failures (semantik gerbang, termasuk kasus owner:
  50.000 pada pair USDT ber-saldo kosong TIDAK boleh submit).
- `PaperBuyNominalSourceTest` — 5 test source-scan **tanpa komentar** (pemindaian
  hanya membaca kode): larang literal `50000.0`, wajib gerbang di tombol, larang
  coerce diam-diam, larang nominal awal karangan dialog, larang helper hidup kembali.
- **Kontrol negatif terbukti:** literal `50000.0` dikembalikan → uji sumber **GAGAL
  (1 failure)**; dipulihkan → hijau. Perbaikan gerbang saldo dialog juga tertangkap
  uji saat sisa pola lama masih ada di berkas (terbukti 1 failure sebelum bersih).
- Seluruh suite `:app:testDebugUnitTest` dijalankan ulang; angka dari XML
  `testDebugUnitTest` (lihat laporan temuan).

**Temuan & pelaporan:** `docs/bugs/temuan_bugs_nominal_beli_paper.md` (M1–M4, semua
ditutup) → dipindah ke `docs/laporan_temuan_bugs_nominal_beli_paper.md`.

**Yang tidak berubah:** config auto-buy paper (`fixedIdrAmount` Rp 100.000 + konversi
kurs di `plan()`), engine order sebagai gerbang terakhir, jalur uang riil, dan semua
gate engine.

## 78. Pemecahan `AGENTS.md` per area untuk hemat konteks (2026-10-27)

**Masalah terukur.** Sesi kerja disk 2026-10-27 menghabiskan **62.614 token (31%)** jendela
sebelum pekerjaan nyata dimulai. Penyebabnya bukan obrolan, melainkan biaya tetap: `AGENTS.md`
akar **96.988 byte ≈ 26.700 token** yang dikirim ulang di **setiap** permintaan, ditambah
`AGENTS.md` global (8.167 byte ≈ 2.300 token) dan skema tool MCP. Dokumentasi OpenCode V2
menyebut compaction **tidak bisa** membuat ruang bila yang mendominasi adalah instruksi tetap.

**Tindakan.** Aturan per area dipindahkan ke berkas `AGENTS.md` bertingkat, yang pada OpenCode V2
hanya dimuat **ketika berkas di area itu dibaca**:

| Berkas | Ukuran | Isi |
|---|---:|---|
| `AGENTS.md` (akar) | **16.300 byte ≈ 4.500 token** | identitas repo, Langkah 0 (branch), status riset, alur kerja dokumen & release, urutan baca, aturan kerja (bahasa/angka/kontrol negatif), tabel aturan per area, arah produk, perintah build & tes, secrets, batasan pasar, isolasi exchange, penulisan Markdown, Git, larangan agen |
| `app/AGENTS.md` | 58.031 byte | larangan bangun ulang multi-horizon, aturan turunan audit (N/J/P/K), FEE_TRUTH, engine scalping, P0, UI & Compose, Fase 1 auto-order, jeda entri-ulang |
| `docs/AGENTS.md` | 18.503 byte | aturan folder `bugs/` dan `exp/`, penghentian Spec v2/v3, kriteria sweep exit & persentil entry |
| `tools/AGENTS.md` | 2.097 byte | mirror replay `scalp_config.json`, exit yang sama dengan app, fee IDR, jalan dari keadaan diam |

**Bukti tidak ada aturan yang hilang.** Pemindahan dilakukan skrip dengan rentang baris, bukan
ditulis ulang: 40 heading lama → 39 ada **verbatim** di berkas baru; satu-satunya yang tidak
dipindah adalah `## Dokumen yang harus dibaca dulu`, yang isinya digabung ke "Urutan baca" akar.
Pemeriksaan 170 butir tebal (`- **…**`) pada berkas lama: **0 hilang**.
`python3 tools/check_md_text.py` → **81 berkas bersih** (tanpa BOM/CRLF/CJK/mojibake).

**Efek.** Untuk tugas yang tidak menyentuh `app/` (dokumen, tooling, eksperimen), konteks tetap
turun dari ±26.700 menjadi **±4.500 token** per permintaan (−22.200). Untuk tugas yang menyentuh
`app/`, `app/AGENTS.md` ikut dimuat sehingga penghematannya lebih kecil — itu memang harga dari
memindahkan aturan yang bersangkutan.

**Batas yang perlu diingat.**
- Berkas bertingkat baru dipakai **penuh pada sesi baru** bila sudah terlanjur dimuat di sesi
  berjalan; perubahan di tengah sesi tidak dideteksi otomatis.
- Menambah aturan baru: taruh di berkas area yang tepat, **jangan** di akar, kecuali berlaku untuk
  seluruh repo (bahasa, git, build, larangan).
- Backup lokal sebelum pemecahan: `AGENTS.md.bak-20261027-before-split` (tidak di-commit).
- Penghematan ini murni soal **ruang konteks**, bukan biaya dolar: `opencode stats --cost`
  mencatat cache input 96,8%.

### 78.1 Compaction lebih awal dan pembatasan tool MCP (2026-10-27)

**Compaction** (`~/.config/opencode/opencode.jsonc`, **per mesin**, tidak ikut repo):

```jsonc
"compaction": { "auto": true, "keep": { "tokens": 8000 }, "buffer": 32000 }
```

Bawaan V2: `keep.tokens` 15.000 dan `buffer` = 15% batas model (≈30.000 pada jendela 200k).
`keep` 8.000 memperkecil sisa obrolan yang dibawa setelah ringkasan; `buffer` 32.000 memicu
compaction sedikit lebih awal. Sifatnya **lossy** — detail percakapan lama diganti ringkasan, jadi
bila detail terbaru penting, naikkan kembali `keep.tokens`. Diverifikasi lewat `opencode debug
config` (blok `compaction` muncul pada dokumen global).

**Pembatasan tool MCP** (`opencode.jsonc` di repo, ikut git):

```jsonc
"permissions": [ { "action": "opencode_console_*", "resource": "*", "effect": "deny" } ]
```

Tool konsol workspace (member, provider, model, billing) tidak dipakai saat kerja kode; menolaknya
membuat katalognya tidak ikut ke system prompt. Dokumentasi V2 menyebut instruksi server juga
dihilangkan bila seluruh tool-nya ditolak, sedangkan `/mcps` tetap melaporkan
`opencode_console connected` — jadi koneksi tidak diputus, hanya tidak dibebankan ke konteks.
**Bukti:** setelah `opencode.jsonc` ditulis, katalog tool Code Mode di sesi berjalan berubah dan
`opencode_console` hilang dari daftar; `opencode mcp list` tetap menunjukkan ketiga server
**Yang tidak berubah:** isi aturan, gate engine, dan seluruh kode aplikasi. Perubahan ini hanya
soal ruang konteks, bukan perilaku mesin.

## 79. Mode INTRADAY — paket A+B: angka biaya replay, jam sesi, gate R:R palsu (2026-10-27)

**Latar.** Mode `OFFICE_DAILY` belum pernah masuk audit mana pun, padahal ia memakai jalur kode
yang berbeda dari scalping: evaluator sendiri, replay sendiri, dan tidak punya slider. Pemeriksaan
menemukan bahwa replay **menilai dirinya sendiri dengan angka gross**, fase sesi memakai timestamp
buka candle, dan gate R:R memakai TP2 yang di-clamp `coerceAtLeast(1.6)` sehingga selalu lolos.
Temuan lengkap ada di **`docs/laporan_temuan_bugs_intraday.md`**; § ini hanya ringkasan keputusan.

**Yang dikerjakan (A+B).**

1. **Satu rumus P&L juga berlaku di replay intraday.** `IntradayReplayEngine.netPnlPct` memakai
   `FeeSchedule.netProfitPct` (taker dua sisi + slippage dua sisi, multiplikatif). Bentuk lama
   `gross − (buyTaker + sellTaker)` tanpa slippage membuat trade Indodax tampak lebih untung
   ≈0,16 pp. Parameter `feeConfig` sekarang **wajib** (tanpa nilai bawaan) supaya preset legacy
   `TradingFeeConfig()` tidak bisa terpakai lagi.
2. **Menang/kalah = net, dan setiap trade dihitung tepat sekali.** `validEntries =
   trades.count { isWin(it.netPnlPct) }`, `falseSignals = trades.size − validEntries`. Dulu
   `AMBIGUOUS` tidak pernah masuk hitungan dan Close Malam dinilai dari gross — itulah cara laporan
   menulis **Win Rate 100%** di atas rata-rata net **−0,21%**. Profit factor ikut net.
3. **Fase sesi setengah-terbuka.** Konstanta bernama `OPEN_PAGI_START` … `REST_MALAM_START` dengan
   selang `[mulai, selesai)`: 11:30 milik Siang, 15:30 Sore, 19:30 Close Malam, 23:30 Istirahat.
   Tidak ada menit yang hilang atau dihitung dua kali (diuji 1.440 menit).
4. **Jam evaluasi dari jam sekarang.** `ModeSignalEvaluator.evaluateIntraday` meneruskan `nowMs`;
   replay memakai `timestamp buka + durasi bar` (median selisih, jadi H1 maupun H4 benar). Memakai
   timestamp buka candle menggeser fase ≈4 jam pada H4.
5. **Gate R:R dari TP1 tanpa clamp.** `netRrTp1` dihitung dari TP1 dan dibandingkan dengan
   `IntradayConfig.minNetRrTp1` (bawaan **1,5**). Teks "Min 1:1.8" dan klaim "72 jam" diganti nilai
   config / jumlah candle (12 candle ≈ 48 jam pada H4).
6. **Config intraday terpisah** (`engine/intraday/IntradayConfig.kt`): `slippagePct`,
   `useMakerFee=false`, `minNetRrTp1`, `flashDumpScanCandles`, `flashDumpTraumaCandles`. **Tanpa**
   state global dan tanpa slider — mode intraday memang belum punya slider, jadi menumpang
   `ScalpingRuntimeConfig` akan membuat geseran slider scalping diam-diam mengubah sinyal intraday.

**Konsekuensi yang harus dibaca sebelum menyimpulkan apa pun.** Dengan geometri sekarang
(SL paling rapat −1,5%, TP1 paling rapat +2,8%) dan biaya Indodax IDR (impas net 0,7940%), net R:R
TP1 ≈ **0,87** (risiko net ≈2,28% / imbalan net ≈1,99%, multiplikatif) — jauh di bawah ambang 1,5.
Koreksi 28 Okt 2026: laporan pertama menulis **0,74** — angka itu milik Tokocrypto USDT
(0,9735%), bukan Indodax; Tokocrypto IDR ≈0,85. Karena gate sekarang jujur, **sinyal BUY intraday
menjadi nol** pada semua dataset uji: BTC/IDR live 279 bar H1 dan tiga jendela September 2026
(dulu 6 / 9 / 5 / 6 BUY). Ini **bukan regresi**; ini gate yang berhenti menipu diri sendiri.
Sampai paket C selesai, **sinyal intraday jangan dipakai** sebagai dasar keputusan. Perbaikan
berikutnya ada di **paket C (geometri SL/TP)**, dan itu mengubah output sinyal sehingga
menunggu instruksi owner.

**Angka terukur (PC, JDK 17, Gradle 9.7).**

| Ukuran | Sebelum | Sesudah |
|---|---|---|
| Test intraday | 12 hijau | **20 hijau** |
| Seluruh unit test | **833** hijau | **841** hijau, 0 gagal |
| `test01` BTC/IDR live | 6 BUY · WR 100% · avg net −0,21% | 0 BUY · 0 trade |
| Gross +0,30% Indodax IDR | tampak untung | net **−0,49%** ⇒ kalah |

**Kontrol negatif.** Mengembalikan `coerceAtLeast(1.6)`, menilai Close Malam dari `grossPnlPct`, dan
mengoper `evaluationTimestamp = null` membuat 4 test gagal; setelah dipulihkan 20/20 hijau.

**Batas yang belum ditutup.** C (geometri SL/TP + ambang `macdHist` yang tidak berskala), D (alat
replay 11 pair + pemisahan `UNRESOLVED` vs tutup-akhir-jendela), E (mesin exit intraday — belum
ada), F (UI/kartu intraday belum menyebut basis net & sampel). Semuanya di
`docs/laporan_temuan_bugs_intraday.md`.

**Yang tidak berubah.** Engine scalping, gate P0, lantai friksi, dan exit paper. `ConfluenceEvaluator`
hanya mendapat parameter `useMaker`/`slippagePct` dengan **bawaan lama** (`true`), jadi pemanggil
SWING berperilaku persis seperti sebelumnya.

## 80. Review owner atas commit intraday `7c8d822` (2026-10-28)

Owner me-review commit §79 dan mengajukan 10 temuan. Tindak lanjut yang dieksekusi:

1. **Angka R:R 0,74 keliru konteks — dikoreksi ke 0,87 (Indodax IDR).** Tabel paket C sendiri
   memberi 2,01/2,29 ≈ 0,87; angka 0,74 ternyata hasil biaya **Tokocrypto USDT** (0,9735%)
   yang keliru dipindah ke konteks Indodax. Dihitung ulang dengan rumus yang sama dengan app
   (`FeeCalculator.roundTrip`, multiplikatif): Indodax IDR risiko net 2,2759% / imbalan net
   1,9902% ⇒ **0,87**; Tokocrypto IDR **0,85**; Tokocrypto USDT **0,74**. Dikoreksi di
   `docs/laporan_temuan_bugs_intraday.md`, `docs/AGENTS.md`, `app/AGENTS.md`, dan §79 di atas.
   Pesan commit `7c8d822` tidak di-rewrite (sudah di-push); koreksinya tercatat di riwayat temuan.
   Tambahan: di ujung atas band TP1 (+5,0%), R:R maksimum hanya ≈1,83 (IDR) / ≈1,63 (USDT) —
   ambang 1,5 hanya bisa dilampaui ekor atas band.
2. **Sinyal BUY = 0 adalah perilaku gate yang jujur, bukan bug baru.** Diringkas di
   temuan Paket C dan dinyatakan eksplisit: **sampai paket C selesai, sinyal intraday jangan
   dipakai sebagai dasar keputusan.** Owner melarang menurunkan `minNetRrTp1` hanya untuk
   memunculkan sinyal (sesuai aturan paket B3).
3. **Uji pindai sumber diganti uji perilaku; `println` dan tautologi dihapus.** Penggantinya
   berjalan di atas dataset riset ter-commit `data/tokocrypto_v7/DOGEUSDT_1h.csv`:
   - `ambangGateMemakaiConfig_bukanLiteral` → ambang config 0,0 membuktikan sinyal (77 BUY,
     25 trade) vs 99,0 (0 sinyal); kode literal tidak bisa bersikap begitu;
   - `feeMakerDanSlippageDariConfig_ikutMengubahNetRr` (baru) → maker + slippage 0,30% dari
     config mengubah angka R:R sesuai `FeeCalculator` dan jelas beda dari taker default;
   - `ringkasanReplayMemakaiNetDanPartisiLengkap` → ringkasan (partisi, win rate, profit factor,
     kasus Close Malam gross-positif/net-negatif) dihitung ulang dari daftar trade rekaman;
   - `labelPeristiwaDanKlasifikasiNet_tidakBisaBertentangan` (baru) → label `VALID_ENTRY_*`
     tidak pernah bertentangan dengan klasifikasi net: di dataset nyata, plus invarian geometri
     (impas net 3 preset di antara lantai SL −1,5% dan lantai TP1 +2,8%). `outcome` sengaja
     tetap mencatat peristiwa (aturan A5 §79).
   `println` debug di `ambangGateMemakaiConfig_bukanLiteral` dan asersi tautologi
   `assertTrue(gross >= 0.0)` atas konstanta di `closeMalamMemakaiNet_bukanGross` dihapus.
4. **`nowMs <= 0` tidak lagi jatuh ke `System.currentTimeMillis()`.** `ModeSignalEvaluator.evaluate`
   kini **fail-closed** (null) untuk input jam tidak valid — hasil deterministik walau jam dinding
   berjalan. Penjaga: `nowMs tidak valid ditolak fail-closed, bukan ditutupi jam dinding`
   (`ModeSignalEvaluatorTest`, 3 mode × 0/−1, dengan data candle lengkap).
5. **Kontrol negatif terukur (28 Okt):** (a) gate literal + fee/slip config diabaikan → 3 test
   gagal; (b) menang dari gross lagi → `ringkasanReplay…` gagal tepat; (c) label TP↔SL ditukar →
   `labelPeristiwa…` gagal tepat; (d) fallback jam dinding dikembalikan → uji `nowMs` gagal tepat.
   Dipulihkan → 27/27 hijau (22 intraday + 5 `ModeSignalEvaluatorTest`).
6. **Unit `macdHist` (harga, bukan persen): arahan owner — ditangani di paket C**, diperbaiki unitnya
   lalu dampak ke frekuensi sinyal diukur; tidak boleh dibiarkan.
7. **Aturan `docs/LAPORAN-*.md` dipertegas (di `AGENTS.md` akar):** butir "Setiap perubahan wajib
   memperbarui markdown" dibatasi catatan bahwa aturan detail area ada di `AGENTS.md` per area dan
   **yang lebih spesifik menang** — sesuai §78, jadi laporan batch intraday ditulis di § Riwayat
   `docs/laporan_temuan_bugs_intraday.md` + workplan ini, bukan berkas `docs/LAPORAN-batch*-*.md` baru.
8. **Angka uji (diukur 28 Okt 2026, PC, JDK 17):** 8 → 10 test di `IntradayAccountingAndSessionTest`;
   intraday 20 → **22 hijau**; seluruh unit test 841 → **844 hijau, 0 gagal, 1 skipped**
   (+1 uji `nowMs`, +2 uji perilaku baru).

**Yang menunggu instruksi owner (tidak dieksekusi):** paket D (alat replay/ukur 11 pair),
E (mesin exit intraday), F (UI/kartu). Paket C sudah dikerjakan §81.

## 81. Mode INTRADAY — paket C: geometri SL/TP diturunkan dari R:R + unit macdHist (2026-10-28)

Keputusan owner (28 Okt 2026): perbaikan C1 memakai **opsi A** — TP1 diturunkan dari SL+ATR,
ambang `minNetRrTp1` **tetap 1,5** dan tidak boleh dilonggarkan. C2 (`macdHist` bersatuan harga)
dikerjakan di paket yang sama dengan dampak frekuensi **diukur**.

### Perubahan kode

| Berkas | Isi |
|---|---|
| `engine/intraday/IntradayGeometry.kt` (**baru**) | Fungsi murni `resolve(...)`: ATR clamp band, SL = support − `slAtrMult`×ATR dalam band `[minSlPct, maxSlPct]` + **lantai friksi** `minSlFrictionMultiple × impas net`, TP1 = `max(TP1-ATR, TP1-dari-R)` dibatasi `min(resistance, price×(1+maxTp1Pct))`, TP2 selalu > TP1, `netRr` dari TP1 final **tanpa clamp** |
| `engine/intraday/IntradayConfig.kt` | 12 knob geometri + `macdHistMinPct` (bawaan 0,0). Tanpa state global, tanpa slider |
| `engine/intraday/IntradayEvaluator.kt` | Blok geometri lama (band persen) diganti `IntradayGeometry.resolve`; `macdHistPct = macdHist/price×100` dipakai `isEarlyReversal` + `step3Ok`; konfluensi menerima ambang + geometri yang sama |
| `engine/confluence/ConfluenceEvaluator.kt` | Parameter baru `netRrThreshold = 2.0`, `overrideStopLoss = null`, `overrideTp1 = null` — **bawaan = perilaku SWING lama**, tidak berubah |

### Dua hal yang harus dibaca sebelum memakai angka R:R intraday

1. **R:R tampil ≈1,50 pada hampir semua sinyal.** TP1 di-solve tepat pada ambang, jadi R:R berhenti
   menjadi pembeda mutu. Yang menentukan lolos/gagal adalah **ruang menuju resistance** —
   `maxTp1Pct`. Batas atas sengaja dipertahankan supaya gate **masih bisa gagal jujur**;
   menghapusnya membuat gate tautologis. **Bawaan 5,0%** (batas band TP1 lama) memberi **0 BUY**
   di 11 pair; 6% ⇒ 17 BUY; 7–8% ⇒ 20 BUY (diperluas di §83: 7–10% **identik** 20 BUY). Nilai 8%
   yang sempat menjadi bawaan di `f6faf90` **belum disetujui owner** (lihat §82).
2. **`netRr` negatif dibiarkan.** TP1 yang terpotong di bawah impas net memberi R:R negatif →
   gate gagal. Kalau dipipihkan `coerceAtLeast(0.0)` (perilaku lama), gate ambang 0,0 lolos pada
   TP1 yang tidak menutup biaya (terukur avg net −0,8…−1,0%/trade di 7 pair).

### Angka terukur (11 pair `data/tokocrypto_v7` `*_1h.csv`, fee Tokocrypto USDT)

| Konfigurasi | BUY | Trade | Avg net/trade |
|---|---|---|---|
| Ambang **1,5** + `maxTp1Pct` **5,0%** (**bawaan**) | **0** | 0 | — |
| Ambang 1,5 + `maxTp1Pct` 6,0% | 17 | 8 | **−0,810%** (BANKUSDT 16 BUY, SUIUSDT 1) |
| Ambang 1,5 + `maxTp1Pct` 7,0% | 20 | 11 | −1,336% (BANKUSDT 17, SUIUSDT 3) |
| Ambang 1,5 + `maxTp1Pct` 8,0% (config pengukuran, bukan bawaan) | 20 | 11 | −1,336% |
| `maxTp1Pct` 2,0% / 4,0% | **0** / **0** | 0 | — |
| Gate MACD lama (praktis tanpa gate, cap 8%) | 35 | 20 | −0,890% (5 pair) |
| `macdHistMinPct` 0,0 (bawaan baru, cap 8%) | 20 | 11 | −1,336% (2 pair) |
| `minSlFrictionMultiple` 1,0 / 3,0 (cap 8%) | 7 / 30 | — | BANK −2,041% / −0,559% |

Kesimpulan yang **tidak boleh** dilebihkan: paket C memperbaiki geometri dan kejujuran angka;
**edge belum ada** (avg net masih negatif pada semua config yang menghasilkan trade, sampel
11 pair satu window). Sinyal intraday tetap **bukan** dasar keputusan uang riil. Ambang 1,5
dipertahankan meski sinyal sedikit — bahkan nol pada batas atas bawaan.

### Kontrol negatif (4/4 menggigit, dipulihkan setelahnya)

| Provokasi | Uji yang gagal |
|---|---|
| TP1-dari-R dimatikan | `geometriDiturunkanDariRr_tp1MemenuhiAmbang` |
| Normalisasi `macdHist` dimatikan | `macdHistDinormalisasi_konsistenAntarSkalaHarga` |
| `coerceAtLeast(0.0)` dikembalikan | `labelPeristiwaDanKlasifikasiNet_tidakBisaBertentangan` |
| Override geometri checkpoint 6 dimatikan | `checkpointKonfluensiMemakaiGeometriDanAmbangIntraday` (`Net 1:0.91`, gagal) |

### Uji

`IntradayGeometryAndMacdHistTest` (8 test baru, seluruhnya uji perilaku di dataset ter-commit),
`IntradayAccountingAndSessionTest` (10 test; `netRrDariTp1_tidakDiClamp` dan
`labelPeristiwaDanKlasifikasiNet_*` ditulis ulang mengikuti geometri baru),
`IntradayEvaluatorTest` (3), `RealBtcIndodaxIntradayReplayTest` (9).

**Masih terbuka (menunggu instruksi):** paket D (alat replay/ukur 11 pair), E (mesin exit intraday),
F (UI/kartu menyebut basis net & sampel). Rincian temuan: `docs/laporan_temuan_bugs_intraday.md`.

## 82. Tindak lanjut review owner atas paket C intraday (2026-10-28)

Review owner atas `f6faf90` menemukan tiga cacat teknis dan satu keputusan yang belum sah.
Semuanya ditindaklanjuti; tidak ada gate yang dilonggarkan.

| Temuan review | Perbaikan | Penjaga |
|---|---|---|
| `maxTp1Pct` 8% **belum disetujui owner**; itu yang membuka 20 BUY, jadi praktis melonggarkan gate lewat pintu lain | Bawaan dikembalikan **5,0%** (batas band TP1 lama). Nilai 8% tetap boleh dipakai sebagai **config pengukuran** di uji, bukan bawaan | `batasAtasBawaan5Persen_membuatGateGagalJujur` |
| Cabang tanpa support memakai literal `1,2` + ATR **belum di-clamp**, sedangkan bagian lain memakai ATR ter-clamp | Knob baru `IntradayConfig.noSupportAtrMult` (bawaan 1,2); SL tanpa support = `(noSupportAtrMult + slAtrMult) × ATR ter-clamp`. Fallback `supportLevel` di evaluator memakai knob yang sama (perilaku sama, literal hilang) | `tanpaSupport_memakaiKnobDanAtrTerClamp` |
| Dua net R:R untuk sinyal yang sama: evaluator tidak memipihkan, checkpoint 6 lewat `FeeCalculator.roundTrip` yang memipihkan ke 0 | `ConfluenceEvaluator.evaluate` menerima `overrideNetRr`; sinyal intraday mengirim `geometry.netRrTp1`. Checkpoint 6 tidak menghitung ulang dan tidak memipihkan. Bawaan `null` = perilaku SWING lama | `checkpointKonfluensiMemakaiGeometriDanAmbangIntraday`, `checkpointMemakaiRrNegatifApaAdanya_bukanDipipihkan` |
| `require(price > 0.0)` di `resolve` akan crash bila harga 0 | Fail-closed: `resolve` mengembalikan `null` untuk harga `<= 0`, `NaN`, `∞`; evaluator memperlakukan `null` sebagai "jangan bersinyal" | `hargaTidakValid_failClosedBukanCrash` |

### Angka terukur ulang (11 pair, fee Tokocrypto USDT, ambang 1,5)

| `maxTp1Pct` | BUY | Trade | Avg net/trade |
|---|---|---|---|
| **5,0% (bawaan)** | **0** | 0 | — |
| 6,0% | 17 | 8 | −0,810% |
| 7,0% | 20 | 11 | −1,336% |
| 8,0% | 20 | 11 | −1,336% |

Bacaan: hasil **tidak monoton** terhadap batas atas (6% sedikit lebih baik dari 8% pada sampel
ini), dan 7% identik dengan 8% — angka 8% tidak punya dasar pembeda. Batas atas adalah penentu
lolos/tidak, jadi keputusannya milik owner dan wajib diuji di data lebih banyak (walk-forward).

### Kontrol negatif (4/4 menggigit, dipulihkan setelahnya)

| Provokasi | Uji yang gagal |
|---|---|
| Fail-closed dikembalikan ke `require` | `hargaTidakValid_failClosedBukanCrash` |
| Cabang tanpa support dikembalikan ke literal `1,2` + ATR mentah | `tanpaSupport_memakaiKnobDanAtrTerClamp` |
| `overrideNetRr` tidak dikirim | `checkpointMemakaiRrNegatifApaAdanya_bukanDipipihkan` |
| Bawaan `maxTp1Pct` dikembalikan ke 8,0 | `batasAtasBawaan5Persen_membuatGateGagalJujur` |

### Uji

855 hijau, 0 gagal, 1 skipped (total 856); intraday **34** test (30 saat paket C ditutup;
`IntradayGeometryAndMacdHistTest` 12 test). Kontrol negatif 4/4 menggigit.

**Masih menunggu keputusan owner:** nilai `maxTp1Pct` (5/6/7/8%) setelah uji data lebih banyak.
Paket D–F intraday tetap terbuka.

## 83. Sweep `maxTp1Pct` 3%–10% dan alat ukurnya (2026-10-29)

Permintaan owner: uji `maxTp1Pct` **satu per satu 3% sampai 10%** untuk menentukan nilai final.
Yang dikerjakan: mengukur, bukan memilih. `IntradayConfig` **tidak** diubah (bawaan tetap 5,0%).

### Alat ukur

`app/src/test/java/agu/analys/engine/intraday/replay/IntradayMaxTp1SweepTest.kt` — hanya jalan
dengan `RUN_INTRADAY_MAXTP1_SWEEP=true`, tanpanya **dilewati** (suite harian tetap cepat).
Dibuat di Kotlin, bukan Python, karena geometri TP1 + gate net R:R hidup di Kotlin; menulis ulang
rumusnya berarti mengukur rumus lain.

```
RUN_INTRADAY_MAXTP1_SWEEP=true ./gradlew :app:testDebugUnitTest \
  --tests "agu.analys.engine.intraday.replay.IntradayMaxTp1SweepTest"
```

Jalannya ±33 menit (replay 11 pair × 8 nilai cap + potongan window); keluaran
`app/build/intraday-maxtp1-sweep.md` + `app/build/intraday-maxtp1-progress.txt`. Alat ini
**mereproduksi** angka 5/6/7/8% dari §81–§82 (0/17/20/20) sampai digit terakhir, jadi
pengukurannya memakai jalur yang sama.

### Angka (11 pair `data/tokocrypto_v7` H1, fee Tokocrypto USDT, ambang 1,5)

| `maxTp1Pct` | BUY | Trade | Menang | Win rate | Avg net/trade | Total net | Pair yang BUY |
|---|---|---|---|---|---|---|---|
| 3,0% | 0 | 0 | — | — | — | — | — |
| 4,0% | 0 | 0 | — | — | — | — | — |
| **5,0% (bawaan)** | **0** | 0 | — | — | — | — | — |
| 6,0% | 17 | 8 | 2 | 25,0% | **−0,810%** | −6,482% | BANKUSDT 16, SUIUSDT 1 |
| 7,0% | 20 | 11 | 2 | 18,2% | −1,336% | −14,693% | BANKUSDT 17, SUIUSDT 3 |
| 8,0% | 20 | 11 | 2 | 18,2% | −1,336% | −14,693% | BANKUSDT 17, SUIUSDT 3 |
| 9,0% | 20 | 11 | 2 | 18,2% | −1,336% | −14,693% | BANKUSDT 17, SUIUSDT 3 |
| 10,0% | 20 | 11 | 2 | 18,2% | −1,336% | −14,693% | BANKUSDT 17, SUIUSDT 3 |

Potongan window (60% awal / 40% akhir), hanya BANKUSDT + SUIUSDT karena hanya dua itu bersinyal:

| `maxTp1Pct` | Awal 60% | Akhir 40% |
|---|---|---|
| 3–5% | 0 BUY | 0 BUY |
| 6,0% | 6 BUY, 2 trade, avg −0,094%, WR 50% | 10 BUY, 5 trade, avg −1,272%, WR 0% |
| 7–10% | 9 BUY, 5 trade, avg −1,680%, WR 20% | 10 BUY, 5 trade, avg −1,272%, WR 0% |

### Bacaan

1. **Tidak ada nilai di 3–10% yang memberi net positif.** Semua config yang menghasilkan trade
   rugi (−0,810% s.d. −1,336%/trade). 6% paling ringan, tetapi paruh akhirnya **−1,272% dengan
   0 dari 5 trade menang** — jadi "6% terbaik" tidak bertahan antar potongan.
2. **Di atas 7% batas atas config berhenti menjadi penentu.** 7–10% identik sampai digit
   terakhir: yang memotong TP1 sudah resistance candle, bukan `maxTp1Pct`. Menaikkan cap di atas
   7% **tidak menambah satu sinyal pun** di dataset ini.
3. **9 dari 11 pair tidak pernah bersinyal** di seluruh rentang 3–10% (BTC, CHIP, DOGE, ETH,
   NEAR, QNT, SOLIDR, SOL, XRP). Cap hanya membuka BANKUSDT + SUIUSDT; pair lain ditahan gate
   lain (mis. `macdHistMinPct`).
4. **Potongan window bukan penjumlahan window penuh** (6%: 6 + 10 = 16 vs 17 pada window penuh)
   karena lookahead 24 bar terpotong di batas. Angka potong untuk arah/stabilitas, bukan
   rekonsiliasi.
5. **Usulan:** bawaan **tetap 5,0%**. Tidak ada dasar pengukuran untuk menaikkannya; menaikkan
   ke 6–10% hanya menambah trade yang rata-ratanya rugi. Keputusan akhir tetap milik owner.

### Uji

855 hijau, 0 gagal, **2 skipped** (total 857). Satu skipped adalah alat ukur sweep (hanya jalan
dengan env), satu lagi `RealTokocryptoReplayTest` yang sudah ada. Intraday **35** test.
`IntradayConfig.maxTp1Pct` tetap **5,0%**; tidak ada gate yang dilonggarkan.

## 84. Entri "beli di bawah, jual di atas" diukur — arah benar, edge belum lolos (2026-10-29)

Lanjutan dari §73 dan `docs/exp/exp_entry_price_percentile_ADA-DOGE-XRP.md`: entry engine lama
khas di **persentil 93** harga 1 hari, dan semua sweep exit gagal. Yang belum pernah diukur
adalah sisi sebaliknya — **membeli murah**. Alat baru `tools/entry_buy_low_lab.py` menguji 11
hipotesis beli-bawah + 2 kontrol (`control_top_rsi70` = beli di puncak, `control_random` =
ber-seed) pada 26 pair `data/tokocrypto_v8` di H1/H4/D1, 10 exit, split 60/40, dan walk-forward
6 jendela. Laporan lengkap: **`docs/exp/exp_buy_low_entry_v8_26pair.md`** (status **GAGAL**).

### Hasil utama (H4, exit 3/5 ATR, hold 18 bar = 3 hari, 833 hari/pair)

| | n | net/trade | CI 95% | WR | Pair positif | Train | Test |
|---|---:|---:|---|---:|---:|---:|---:|
| `dip_rsi25` (RSI14<25) | 636 | **+1,39%** | [+0,69; +2,19] | 57,1% | **21/24** | +2,94% | **−0,89%** |
| `control_top_rsi70` | 1.348 | +0,35% | [−0,16; +0,88] | 46,3% | 16/26 | +0,30% | +0,41% |
| `control_random` | 2.628 | −0,68% | [−1,00; −0,38] | 40,8% | 5/26 | −0,65% | −0,72% |

- **Arahnya benar dan terukur:** beli-bawah mengalahkan beli-puncak **+1,04 poin persen** dan
  entri acak **+2,07 poin persen** pada exit yang identik. Median entry `dip_rsi25` di
  **persentil 0** (0% trade di persentil ≥80), kebalikan entry lama di persentil 93.
- **Tetapi tidak bertahan di luar sampel:** train +2,94% → test −0,89%. Keuntungan hanya di
  rezim bull BTC (+2,81%, n=281) vs bear (+0,26%, n=355). Walk-forward 5/6 jendela positif, dan
  satu-satunya jendela negatif (2026-01-24..2026-06-12, −1,79%) adalah jendela yang **seluruhnya**
  bear (BTC D1 di bawah EMA200 pada 180 dari 181 hari semester I 2026).
- **Sampel tidak cukup:** maksimum 36 trade per pair (ETHUSDT); kriteria repo ≥60 trade/pair
  tidak terpenuhi satu pair pun.
- **H1 tidak bisa menutup friksi:** sel terbaik +1,07% dengan CI menyentuh nol; gross
  `dip_rsi25|atr3.0/3.0|24` hanya +0,62% vs friksi 0,9688%. Filter sadar-friksi membuang 32%
  sinyal pada ATR 1,5/2,0.
- **D1 positif tetapi tidak sah sebagai bukti:** `tgt_ema20_sl3|21` +3,19% (CI [+0,31; +6,29])
  dari hanya 200 trade/26 pair, dan kontrol beli-puncak pada exit ATR juga untung **+2,60%**
  (CI seluruhnya di atas nol; median −0,28% → ekor kanan).

### Bacaan dan keputusan

1. **Tidak ada yang di-port.** Tidak ada perubahan app, `scalp_config.json`, `IntradayConfig`,
   atau gate P0. Ini riset offline, sama seperti temuan lain di `docs/exp/`.
2. **Friksi 0,9688% tetap lantai keras.** Trade perlu menangkap ≥1% bersih; di H4 itu hanya
   terjadi kalau posisi ditahan berhari-hari (median 18 bar), dan 64,9% trade keluar TIMEOUT,
   bukan TP. Jadi hasil ini soal waktu + rezim, bukan soal exit.
3. **Yang bisa dipakai berikutnya (menunggu instruksi owner):** hipotesis beli-bawah layak
   diuji sebagai **gate rezim**, bukan sebagai strategi berdiri sendiri — dengan syarat diukur
   pada data baru (bukan diulang pada 26 pair yang sama) dan tetap memakai fee all-in.
   Jangan menambah filter/gate ke engine hanya karena angka train bagus.
4. **Pembanding pasif belum diukur di repo.** Bunga produk stablecoin di bursa (Flexible/Locked/
   Earn) disebut owner sebagai pembanding; angka bunganya **tidak** diukur di sini dan tidak boleh
   dikutip sebagai hasil eksperimen. Kalau dipakai sebagai pembanding, ia perlu pengukuran
   sendiri (suku bunga berubah, ada risiko bursa, tanpa risiko pasar).

### Alat & reproduksi

`tools/entry_buy_low_lab.py` mendapat tiga opsi baru: `--walk-forward N` (jendela berurutan,
memakai ulang perdagangan yang sama), `--slim` (JSON ramping: blok rezim per pair dibuang, sel
`n < --min-n` tidak disimpan), dan `--cells`/`--pairs-for` (hanya menyimpan sel yang dikutip
laporan). Hasil: `data/hasil/exp_buy_low_entry_v8_{1h,4h,1d}_regime.json` (76K/264K/104K, sebelumnya
15–17 MB per berkas) + `..._4h_tp_first.json` untuk batas optimistis urutan intrabar. Perintah
lengkap ada di berkas laporan § Perintah.

## 85. Mode INTRADAY — paket D–F: alat ukur, label akhir jendela, mesin exit diukur, UI net (2026-10-30)

Paket **D–F** temuan intraday ditutup. Berkas temuan dipindahkan ke
`docs/laporan_temuan_bugs_intraday.md` (tidak ada butir hidup).

### D — alat ukur + `LOOKAHEAD_EXPIRY` ≠ `END_OF_DATA`

Tiga alat ukur Kotlin (semua memakai `IntradayReplayEngine` + `IntradayGeometry` yang sama dengan
aplikasi; dilewati tanpa env supaya suite harian cepat):

| Alat | Env | Cakupan | Durasi terukur |
|---|---|---|---|
| `IntradayMaxTp1SweepTest` | `RUN_INTRADAY_MAXTP1_SWEEP` | v7 11 pair H1, cap 3–10% | ±33 menit |
| `IntradayResearchReplayTest` | `RUN_INTRADAY_RESEARCH_REPLAY` | v8 26 pair H4 + D1 | **22 menit 33 detik** |
| `IntradayExitModeSweepTest` | `RUN_INTRADAY_EXIT_SWEEP` | v8 H4, 8 varian exit | **42 menit 28 detik** |

Label akhir jendela dipisah: `LOOKAHEAD_EXPIRY` (data masih ada) vs `END_OF_DATA` (dataset habis);
keduanya tetap dihitung **kalah** (A5), dan ringkasan memisahkan `unresolvedOutcomes` /
`endOfDataOutcomes` dengan `unresolvedTotal = keduanya`.

**Hasil replay riset 26 pair H4** (fee Tokocrypto USDT 0,974% RT, `minNetRrTp1 = 1,5`, lookahead
6 bar, pemanasan 220 bar):

| Config | BUY | Trade | Win rate | Avg net/trade | Total net | Tidak tuntas | Data habis |
|---|---|---|---|---|---|---|---|
| `maxTp1Pct = 5,0%` (bawaan) | **0** | 0 | — | — | — | 0 | 0 |
| `maxTp1Pct = 8,0%` (config pengukuran) | 105 | 102 | 30,392% | **−0,646%** | −65,856% | 0 | 0 |

Alasan keluar cap 8%: 64 `CLOSE_MALAM_SESSION_EXIT`, 28 `STOP_LOSS_HIT`, 10 `TP1_HIT`. Tidak ada
`END_OF_DATA` di dataset ini — pemisahan label terbukti tanpa mengubah angka. **Bawaan 5,0% tetap
0 BUY**, jadi tidak ada alasan menaikkannya.

### E — mesin exit intraday: dibangun, diukur, **tidak** disambungkan

`engine/intraday/IntradayExitEngine.kt` (murni): `SESSION_CLOSE` (**bawaan** = perilaku lama),
`TP1_BREAKEVEN`, `TRAIL_ATR`, `TIME_STOP`; replay memakai mesin yang sama lewat
`replay(exitConfig = …)`. Invarian dijaga uji: bawaan == eksplisit (trade/net/win rate/harga
identik) dan mode non-bawaan benar-benar dipakai.

Sweep exit (6 pair v8 H4, cap 8,0%, kontrol `SESSION_CLOSE`):

| Varian | Trade | Win rate | Avg net/trade |
|---|---|---|---|
| kontrol `SESSION_CLOSE` | 52 | 28,846% | −0,636% |
| `TP1_BREAKEVEN` | 52 | 28,846% | −0,739% |
| `TRAIL_ATR` 1,0×/1,5× (arm TP1) | 52 | 28,846% | −0,739% (identik, 0 trail hit) |
| `TRAIL_ATR` 1,0× (arm entry) | 52 | 25,000% | −0,753% |
| `TIME_STOP` 3 bar | 52 | 30,769% | **−0,587%** (terbaik, tetap negatif) |
| `TIME_STOP` 6/12 bar | 52 | 28,846% | −0,636% |

**0 dari 7 varian positif**; kriteria kandidat (net > 0, lebih baik di semua pair, CI 95% tidak
menyentuh nol, ≥60 trade/pair) tidak terpenuhi satu pun — sampel maksimum 12 trade/pair. Kontrol
sweep **identik** dengan alat D pada enam pair yang sama (53 BUY / 52 trade / −33,046%), jadi kedua
alat mengukur jalur yang sama. Keputusan: **tidak di-port**; `IntradayExitConfig.DEFAULT` tetap
`SESSION_CLOSE`, `maxTp1Pct` tetap 5,0%, ambang 1,5 tetap. Laporan:
`docs/exp/exp_intraday_exit_modes_v8_6pair.md`.

### F — UI/kartu menyebut basis net, TP1, fase, sampel

- Checkpoint 6 konfluensi: label `Net R:R TP1 1:x` + kalimat komponen biaya (`ConfluenceEvaluator`,
  fallback `TradingViewModels`).
- Kartu target intraday: baris `Net R:R TP1` + catatan "persen target = gross, net = setelah fee
  beli + fee jual + slippage dua sisi, sampel belum cukup". Teks diekstrak ke fungsi murni
  `engine/intraday/IntradayTargetText.kt` supaya bisa diuji tanpa Robolectric
  (`IntradayTargetTextTest`). Mode lain tidak berubah (`isIntradayMode` bawaan `false`).
- Label fase sesi memakai menit terakhir fase (`06:00–11:29`, `11:30–15:29`, `15:30–19:29`,
  `19:30–23:29`, `23:30–05:59`).

### Penjaga & kontrol negatif

Penjaga: `IntradayReplayWindowAccountingTest` (label exit, partisi net, anchor `DOGEUSDT_1h` =
11 BUY / 7 trade, bawaan == eksplisit, mode non-bawaan dipakai), `IntradayExitEngineTest` (6
perilaku), `IntradayTargetTextTest` (4 syarat teks), plus `ConfluenceEvaluatorTest` /
`IntradayGeometryAndMacdHistTest` yang diselaraskan. Kontrol negatif diukur satu per satu lalu
dipulihkan (tabel di `docs/laporan_temuan_bugs_intraday.md`).

### Batas yang tetap berlaku

Satu window data, hanya USDT, hanya H4 untuk replay riset dan sweep exit. Tidak ada klaim edge
baru: sinyal intraday tetap **negatif** sebelum dan sesudah exit diubah. Jangan memakai angka ini
sebagai dasar uang riil, dan jangan menyambungkan mode exit baru tanpa keputusan owner.

## 86. Mode INTRADAY — paket G: slider sensitifitas dengan holder sendiri (2026-10-30)

**Latar.** Sampai paket F, `IntradayConfig` (19 knob) hanya bisa diubah dengan menyunting kode:
tidak ada state runtime, tidak ada UI, dan `ModeSignalEvaluator` selalu memakai
`IntradayConfig.DEFAULT`. Akibatnya setiap eksperimen intraday harus lewat unit test, dan pengguna
tidak bisa menguji setting sendiri tanpa build ulang. Permintaan owner (30 Okt 2026): nilai
intraday **tidak boleh hardcode di Kotlin**, gunakan slider seperti mode scalping.

**Keputusan owner yang mengikat paket ini.**

| Pertanyaan | Keputusan |
|---|---|
| Cakupan knob | **19 knob `IntradayConfig` saja.** Literal di badan `IntradayEvaluator` (skor ≥50/52, RSI 35–65, `0,988`/`0,965`, `0,075`, `0,12`, ekstensi ATR 1,8, jarak high 1,2%) **tidak disentuh** — dicatat sebagai utang paket berikutnya |
| `minNetRrTp1` (1,5) dan `maxTp1Pct` (5,0%) | **Boleh digeser**, tetapi slider diberi label peringatan + dialog "catatan keputusan owner"; laporan wajib menyebut config yang dipakai |
| Tombol "ukur dengan replay" di UI | **Tidak.** Pengukuran tetap lewat `IntradayResearchReplayTest` / `IntradayMaxTp1SweepV8Test` dari JSON hasil ekspor |

**Yang dibangun.**

1. **Holder tipis, bukan data class kedua.** `engine/intraday/IntradayRuntimeConfig.kt` menyimpan
   instance `IntradayConfig` (bukan menyalin 19 field). Alasan: salinan field adalah definisi
   kedua yang cepat melenceng — pola bug yang berulang di repo ini. Holder menyediakan
   `current`/`currentFlow`, `toJson`/`fromJson`/`fromJsonString`, preset
   `STANDARD`/`STRICT`/`LOOSE`/`CUSTOM`, dan `toSummaryString`.
   `STRICT`/`LOOSE` adalah **preset frekuensi**, bukan klaim profit.
2. **Satu pintu.** `ModeSignalInput.intradayConfig` (bawaan `IntradayRuntimeConfig.current`)
   diteruskan `ModeSignalEvaluator.evaluateIntraday` → `IntradayEvaluator.evaluate(config = …)`.
   Engine foreground (`LearningTradingEngine.runIntraday`) dan `CandidateScanWorker` sama-sama
   lewat pintu ini, jadi tidak ada dua jalur dengan angka berbeda (aturan N6/N17).
3. **Persistensi + hidrasi.** `AppPreferences.intradayRuntimeConfig` (kunci
   `intraday_runtime_config_v1`) dan `intradaySensitivityPreset`; blok `init` menghidrasi holder.
   Tanpa hidrasi itu slider "hilang" setiap restart karena evaluator kembali membaca DEFAULT.
4. **`slippagePct` intraday TIDAK disalurkan ke `simCoordinator`.** Basis P&L simulasi tetap satu
   nilai global; menggeser slider intraday tidak boleh mengubah angka P&L scalping.
5. **UI** `ui/components/settings/IntradaySensitivitySettings.kt`: chip preset, slider + tombol
   −/+, dialog penjelasan bahasa awam per knob, ekspor/impor JSON, reset. Kartu mode INTRADAY di
   `TradingModeSettings` menampilkan parameter aktif + tombol membuka layar ini; `SettingsScreen`
   memakai pola draft → **Simpan & Aktifkan** yang juga menetapkan `StrategyMode.OFFICE_DAILY`.
   Peringatan tetap tampil: *slider mengubah frekuensi sinyal, bukan menjamin profit*.
6. **Mirror offline** `data/config/intraday_config.json` (bawaan = `IntradayConfig.DEFAULT`) untuk
   replay/eksperimen; dijaga uji agar kunci **dan nilainya** tidak menyimpang dari aplikasi.

**Bukti bahwa knob benar-benar dipakai (bukan dekorasi).** Diukur pada dataset ter-commit
`data/tokocrypto_v8/DOGEUSDT_4h.csv` (4.999 bar ≈ 833 hari, fee Tokocrypto USDT, lookahead 6 bar
H4). Satu variabel diubah, `flashDumpTraumaCandles` 12 → 16:

| Config | Bar diblokir trauma (`bottleneck.flashDumpTraumaBlocks`) |
|---|---|
| bawaan (12 candle) | 165 |
| trauma 16 candle | 209 |

Bar yang **hanya** diblokir pada 12 vs 16 berjumlah 44; 16 di antaranya jatuh di sesi Open Pagi
(06:00–11:29 WIB). Bar ke-731 (tutup 2024-11-17 07:00 WIB) dipakai sebagai anchor uji pintu
evaluator: dump terakhir 15 candle lalu → bawaan tidak trauma, config 16 menolak dengan alasan
"Anti Flash Dump".

**Penjaga & kontrol negatif.**

- `IntradayRuntimeConfigTest` (5 uji): round-trip JSON, preset berbeda dari bawaan, `current`
  dan `currentFlow` sinkron, trauma 16 memblokir lebih banyak bar, mirror == DEFAULT.
- `ModeSignalEvaluatorIntradayConfigTest`: dua config berbeda di bar yang sama harus memberi
  reasoning berbeda — **kontrol negatifnya** adalah melepas `config = input.intradayConfig` di
  `ModeSignalEvaluator`: kedua evaluasi kembali identik dan uji gagal.
- Uji lama tidak berubah perilaku: seluruh uji geometri/replay intraday memakai
  `IntradayConfig.DEFAULT` secara eksplisit, dan bawaan holder juga DEFAULT.

**Batas yang tetap berlaku (jujur).**

- **Edge tidak berubah.** Slider hanya memindahkan frekuensi sinyal; sinyal intraday tetap
  rata-rata **negatif** pada semua konfigurasi yang menghasilkan trade (§79.3, §83, §85).
- **Ambang di badan evaluator masih literal** (skor ≥50/52, RSI 35–65, toleransi support
  0,988/0,965, flash dump 7,5%/12%, ekstensi ATR 1,8, jarak recent-high 1,2%). Ini utang yang
  dicatat, bukan pekerjaan yang selesai.
- **Belum diukur ulang di 26 pair.** Angka 165/209 hanya satu pair (DOGEUSDT H4); ia membuktikan
  sambungan config, bukan kualitas config.
- **Verifikasi build di sesi ini terbatas.** Pekerjaan dikerjakan pada Alpine Linux tanpa JDK/
  Android SDK (lingkungan HP owner), jadi `./gradlew :app:testDebugUnitTest` **belum dijalankan**
  untuk paket ini. Jalankan lewat GitHub Actions `all-unit-tests` atau mesin ber-JDK 17 sebelum
  mengklaim hijau.
