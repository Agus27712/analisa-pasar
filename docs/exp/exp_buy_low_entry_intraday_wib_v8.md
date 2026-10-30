# Eksperimen: disiplin sesi intraday WIB (Open Pagi/Close Malam) di `entry_buy_low_lab.py`

**Status: GAGAL** kriteria kandidat (`docs/AGENTS.md`: net/trade > 0 di semua pair, CI 95% tidak
menyentuh nol, ≥60 trade/pair, bertahan di luar sampel). Cell terbaik **positif tapi CI 95%
menyentuh nol** dan sampel per-pair jauh di bawah 60. **Jangan di-port ke app.** Dokumen ini
melaporkan perbaikan alat (bukan hasil baru yang lolos gate) — lihat §4 untuk batasan cakupan.

**Tanggal:** 2026-10-30
**Latar belakang:** `entry_buy_low_lab.py` (lihat `docs/exp/exp_buy_low_entry_v8_26pair.md`)
awalnya mengukur mean-reversion generik — hold bebas sampai 7–21 hari — **bukan** siklus "beli
pagi, close malam" yang dipakai mode INTRADAY (`StrategyMode.OFFICE_DAILY`) di app. Eksperimen
ini menambal alat itu supaya menegakkan disiplin sesi WIB yang sama persis dengan
`IntradayEvaluator.getCurrentIntradayPhase` (Kotlin) produksi, lalu mengukur ulang satu
timeframe (H1) sebagai validasi pertama.

**Data:** `data/tokocrypto_v8` — 25 pair USDT Tokocrypto (AAVE, ADA, ARB, AVAX, BANK, BNB, BTC,
CHIP, DOGE, ENA, ETH, FET, HBAR, LINK, LTC, NEAR, PEPE, QNT, SOL, SUI, TAO, TRX, UNI, WLD, XRP),
timeframe 1h, 208 hari/pair. Rezim dari `BTCUSDT_1d.csv` tersedia.

**Alat:** `tools/entry_buy_low_lab.py`, flag baru `--intraday-session`.

**Perintah:**

```sh
python3 tools/entry_buy_low_lab.py --data data/tokocrypto_v8 --tf 1h \
  --intraday-session --slim \
  --out data/hasil/exp_buy_low_entry_intraday_wib_1h.json

# baseline pembanding (tanpa sesi, data sama)
python3 tools/entry_buy_low_lab.py --data data/tokocrypto_v8 --tf 1h --slim \
  --out data/hasil/exp_buy_low_entry_v8_1h_regime.json
```

**Biaya:** friksi all-in Tokocrypto USDT 0,9688%/trade round-trip, sama seperti eksperimen induk.

---

## 1. Perubahan di `tools/entry_buy_low_lab.py`

- `intraday_phase(t_ms)` — port 1:1 dari `IntradayEvaluator.getCurrentIntradayPhase`
  (`app/src/main/java/agu/analys/engine/intraday/IntradayEvaluator.kt`): Open Pagi
  (06:00–11:29 WIB), Siang Akumulasi (11:30–15:29), Sore Trailing (15:30–19:29), Close Malam
  (19:30–23:29), Istirahat (23:30–05:59). Selang setengah-terbuka `[mulai, selesai)`, aritmetika
  integer murni dari `open_time_ms` (epoch UTC) + offset tetap WIB (UTC+7, tanpa DST) — tanpa
  library `datetime`.
- `--intraday-session`: sinyal entry difilter ke jendela Open Pagi/Siang saja
  (`is_entry_window`); dalam `simulate()`, begitu bar pertama jatuh di Close Malam/Istirahat,
  posisi **dipaksa tutup** di open bar itu dengan alasan baru `SESSION_CLOSE` — diperiksa
  **sebelum** SL/TP intrabar bar itu sendiri, meniru keputusan evaluator yang dibuat di awal
  candle (konsisten dengan entry di `open bar sinyal+1` yang sudah dipakai alat ini).
- Hanya berlaku untuk `--tf 1h`/`4h` (script keluar dengan pesan error untuk `1d` — satu bar
  sudah mencakup satu hari penuh, sesi tidak relevan).
- Validasi batas fase: 7 kasus di titik tepat menit (06:00, 11:30, 15:30, 19:30, 23:30 WIB)
  dites manual terhadap `intraday_phase()` — seluruhnya cocok dengan enum Kotlin (lihat riwayat
  percakapan; tidak disertakan sebagai file tes karena `tools/` belum punya suite tes offline).

---

## 2. Hasil terukur (H1, 25 pair, 208 hari)

| Pertanyaan | Jawaban terukur |
|---|---|
| Apakah SESSION_CLOSE benar-benar menggigit? | **Ya.** Cell terbaik: 54 dari 97 trade (55,7%) ditutup paksa sebelum sempat kena SL/TP alami |
| Cell terbaik? | `dip_ema20_min3pct` + target EMA50 (SL 4 ATR): **+0,3245%/trade**, n=97, WR 58,8%, CI 95% **[−0,67, 1,37]** — masih menyentuh nol |
| Train/test cell terbaik | Train +0,3594% (n=38) → Test +0,3019% (n=59) — searah, tapi sampel kecil |
| Kontrol `control_top_rsi70` (beli di puncak) | **Negatif di seluruh 10 varian exit** yang lolos ambang sampel (−0,10% s/d −0,74%) — tesis "jangan beli puncak" tetap valid di bawah disiplin sesi |
| Kontrol `control_random` (entri acak) | Cell terbaiknya **−1,03%**, lebih buruk dari hipotesis real terbaik — sesuai harapan |
| Berapa dari 303 cell (lolos ambang n≥30) yang net positif? | **15/303 (4,9%)** |
| Dibanding baseline tanpa sesi (cell sama) | n turun **164 → 97 (−40,9%)**; net/trade mirip (+0,2302% → +0,3245%); **WR anjlok 79,3% → 58,8%** karena banyak posisi profitabel dipotong paksa sebelum sempat sentuh TP |
| Lolos kriteria kandidat repo? | **Tidak.** CI 95% cell terbaik menyentuh nol dan sampel per-pair jauh di bawah 60 trade |

**Kesimpulan satu baris:** mekanisme sesi WIB sudah berfungsi dan konsisten dengan evaluator
produksi (kontrol tetap berperilaku seperti seharusnya), tetapi memaksa close di jam 19:30 WIB
memotong banyak trade yang sebenarnya masih profitable sebelum sempat TP — net/trade bertahan
mirip baseline, tapi win rate dan jumlah sampel turun signifikan, dan tidak ada cell yang lolos
gate kandidat.

---

## 3. Catatan metodologi

- `SESSION_CLOSE` diperiksa di **open** bar Close Malam, bukan presisi menit 19:30 — untuk bar
  1h/4h, batas sesi bisa jatuh di tengah bar (mis. bar H4 12:00–16:00 UTC = 19:00–23:00 WIB
  mencakup 19:30). Ini aproksimasi yang sama levelnya dengan batasan "bar H1/4h/D1; bukan tick"
  yang sudah ada di eksperimen induk.
- Jumlah sinyal mentah tiap hipotesis turun ke kira-kira proporsi jendela entry per hari
  (Open Pagi + Siang ≈ 9,5 dari 24 jam ≈ 39,6%), ditambah hilangnya sinyal yang kena
  `SESSION_CLOSE` dini sebelum sempat capai `min_tp_multiple`.

## 4. Batasan run ini (pekerjaan lanjutan)

- **Baru H1.** Belum diuji di H4/D1 maupun walk-forward multi-jendela seperti eksperimen induk —
  perlu unduh data tambahan (`*_4h.csv`) dan waktu komputasi lebih.
- **Belum breakdown per rezim bull/bear** untuk mode sesi (field `by_regime` tersedia di JSON
  tapi belum dianalisis di sini).
- Siang Akumulasi (11:30–15:29) masih dianggap entry-window penuh seperti Open Pagi; app
  menyebutnya "selektif" (lihat komentar `IntradayEvaluator.kt`) — lab ini belum membedakan
  keduanya secara bobot/skor, cuma biner boleh/tidak boleh entry.

**Jangan di-port ke app** — belum ada cell yang lolos gate train+test dan kriteria kandidat
repo, dan baru satu timeframe yang diuji.
