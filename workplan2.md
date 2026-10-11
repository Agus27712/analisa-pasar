# workplan2.md — Workplan aktif (Oktober 2026)

> **Sumber kebenaran workplan.**  
> `workplan.md`, `checkpoint.md`, dan `checkpoint_progres.md` **DEPRECATED** — jangan diikuti agen. Baca ini + `AGENTS.md` + `APP_CONTEXT.md` (§ arah produk).

**Update:** 2026-10-10 (WIB)

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

### P2 — Paper scalp auto & exit (decision support) — [SEDANG BERJALAN 🔄]
> **Catatan Status:** Sinyal scalping + slider threshold aktif penuh. Auto-buy paper, engine exit 5 mode,
> dan paritas replay sudah masuk (lihat centang di bawah). Sisa: visual FSM + kartu statistik di layar paper trade.
- [x] **Paper Auto-Buy (Simulasi)**: Toggle di Pengaturan → Engine Scalping. Membuka posisi simulasi saat sinyal
      `BUY` dengan `scalpingStage` ≥ ambang terpilih (`ENTRY` / `STRONG_ENTRY`). Alokasi **nominal tetap** atau
      **berbasis risiko** (`PaperAutoTradeConfig`). Tidak pernah memicu order uang riil.
- [x] **Paper Auto-Exit Modes**: `PaperExitResolver` + `PaperExitStateMachine` meniru `resolve_trade()` di
      `tools/scalp_replay.py` untuk 5 mode (`classic`, `tp1_be`, `early`, `trail`, `full`), termasuk R seragam
      net setelah fee all-in + slippage. Parameter exit masuk `ScalpingRuntimeConfig.exit` dan ikut ter-ekspor
      sebagai blok `exit` di JSON (dibaca replay).
- [x] Paritas diuji: `PaperExitResolverParityTest` — angka harapan dihasilkan langsung dari Python
      `tools/scalp_replay.py`, bukan dihitung manual.
- [x] `PaperTradeStats` menutup 2 keterbatasan yang dicatat di `SCALP_EXIT.md`: TIMEOUT **tidak** dibuang
      diam-diam, dan exit breakeven (r = 0) berlabel BREAKEVEN (bukan LOSS).
- [ ] FSM visual di UI paper trade — `PaperExitPhase` + state machine sudah tersedia; kartu visualnya belum dibangun.
- [ ] Kartu ringkasan statistik (R net terakumulasi) di layar paper trade — mesinnya (`PaperTradeStats`) sudah ada,
      UI + persistensi hasil per trade belum.
- [x] Larangan keras: **tidak ada** jalur real auto-execute dari sinyal scalp di kode yang ditambahkan.

### P3 — Riset entry (di luar path live)
- [ ] Gate: mean gross ≳ 1% train **dan** test, n memadai, vs acak
- [ ] Tool hipotesis terpisah; label HIPOTESIS
- [ ] Hanya promote ke RuntimeConfig default jika lolos gate

---

## 4. Slider sensitifitas (map JSON)

Default = nilai di `ScalpingConfig.kt` / `data/config/scalp_config.json`.

| UI label | Key JSON | Default | Range usulan |
|----------|----------|---------|--------------|
| Skor min LONG | `min_score_long` | 60 | 45–80 |
| Skor STRONG | `min_score_strong` | 75 | 60–90 |
| Skor retest | `min_score_breakout_retest` | 75 | 60–90 |
| Min Net R:R | `min_net_rr` | 1.15 | 0.8–2.0 |
| Target Net R:R | `target_net_rr` | 1.25 | 1.0–2.5 |
| RVOL breakout | `rvol_breakout_min` | 1.5 | 1.0–2.5 |
| RVOL setup | `rvol_setup_min` | 1.2 | 0.8–2.0 |
| RVOL retest | `rvol_retest_min` | 1.5 | 1.0–2.5 |
| CHOP hard ranging | `chop_hard_ranging` | 65 | 55–75 |
| Jarak max retest % | `retest_max_dist_pct` | 1.2 | 0.5–2.5 |
| Strength struktur retest | `retest_min_structure_strength` | 60 | 40–80 |

Advanced (opsional): `buy_pressure_*`, `min_risk_pct`, `max_tp2_r`, `slippage_pct`, exit paper params.

**Bukan slider:** fee all-in, bobot skor internal (structure 25, dll.).

---

## 5. Temuan empiris (jangan diabaikan)

- Tokocrypto v2 (up) / v3 (down) + fee all-in: exit full mengatur loss, **bukan** +EV stabil.
- Matrix `min_score` / `min_net_rr`: bottleneck utama di **entry** (s1/s3), bukan parameter risk saja.
- Binance vision ~45 hari (XRP/SOL/BTC): full exit tetap −EV orde besar; BTC sering 0 sinyal.
- 5 hipotesis entry baru di 45d: tidak lolos gate gross ≥1% train+test.
- Upstream p0 friction ~0,72% RT vs all-in riset ~0,97% → UI “VIABLE” bisa terlalu optimis.

Detail: `SCALP_EXIT.md`, artefak lokal uji (bila ada).

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

**Jangan baca sebagai instruksi aktif:** `workplan.md`, `checkpoint.md`, `checkpoint_progres.md` (legacy checklist tahap lama).

---

## 8. Definition of Done (milestone berikutnya)

### Milestone P0 & P1 (Selesai):
- [x] RuntimeConfig + minimal 1 layar slider + export JSON
- [x] Paper trade memakai RuntimeConfig + log threshold
- [x] Fee Truth tersambung di app + JSON replay
- [x] AGENTS/APP_CONTEXT menunjuk workplan2 saja untuk arah

### Milestone P2 (Target Aktif):
- [x] Toggle Auto-Buy Paper Trade (eksekusi order beli simulasi otomatis saat transisi sinyal scalping terkonfirmasi READY/STRONG_ENTRY)
- [x] Engine Auto-Exit Paper Trade 5 mode (`classic`, `tp1_be`, `early`, `trail`, `full`) selaras `SCALP_EXIT.md`
- [x] Parameter exit tersambung ke `ScalpingRuntimeConfig` + ekspor JSON (blok `exit`) untuk paritas replay
- [x] Statistik performa paper trade (R net terakumulasi setelah fee all-in) — mesin `PaperTradeStats` + unit test
- [ ] FSM visual + kartu statistik di UI layar paper trade (sisa pekerjaan)
