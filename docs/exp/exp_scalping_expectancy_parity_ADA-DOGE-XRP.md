# Eksperimen: Expectancy Scalping dengan Replay Parity

**Tanggal:** 2026-10-20
**Tujuan:** mengulang eksperimen geometri TP kecil setelah `tools/scalp_replay.py` disamakan
dengan app: CLASSIC time-stop 90 bar, S1 wajib RVOL, S9 membaca `max_change_24h_pct`,
`max_risk_atr` dari config, dan fee taker-only.
**Data:** `data/tokocrypto_v4` — 87.000 bar M1 per pair, 60 hari.
**Pairs:** ADAUSDT, DOGEUSDT, XRPUSDT.
**Alat:** `tools/scalp_replay.py` pada commit `6fe26b6`.
**Eksperimen lama:** `exp_scalping_expectancy_ADA-DOGE-XRP.md` (42,7%). Angka lama **tidak
dibandingkan langsung** karena harness-nya belum punya time-stop.

---

## 1. Ringkasan

| Angka | Nilai |
|---|---:|
| Sinyal ready (3 pair) | **1.149** |
| Trade selesai | **1.149** |
| Menang / kalah | **171 / 978** |
| Win rate | **14,88%** |
| Avg R | **−0,4898** |
| Exit | 963 TIMEOUT, 116 SL, 70 TP2 |
| Ambang impas dari payoff terukur | **55,08%** |
| Gap ke ambang impas | **40,20 poin persen** |

**Kesimpulan:** dengan exit yang benar-benar dijalankan app, mayoritas trade ditutup time-stop
90 menit sebelum TP2/SL. Hasilnya jauh lebih negatif daripada eksperimen lama: win rate
**14,88%**, bukan 42,7%, dan expectancy **−0,49R**, bukan −0,25R. Ini bukan perbaikan engine,
melainkan koreksi parity: angka lama mengukur exit yang tidak dipakai app.

---

## 2. Geometri yang benar-benar diuji

Config memakai basis `data/config/scalp_config.json`, dengan override eksperimen:

| Parameter | Nilai | Catatan |
|---|---:|---|
| `min_friction_multiple` | 2,0 | SL efektif **≈1,94%** |
| `target_net_rr` | 0,5 | target awal |
| `min_net_rr` | 0,5 | agar sinyal tidak ditolak gerbang |
| `lookahead_bars` | 480 | 8 jam, tetapi time-stop lebih dulu |
| `exit.mode` | classic | SL/TP2 + **time-stop 90 bar** |
| `slippage_pct` | 0,08 | FEE_TRUTH §3 |
| fee | taker | 0,4044% per sisi |

Karena engine mewajibkan `TP2 ≥ 1,5 × risiko`, TP2 efektif menjadi **≈2,9064%**, bukan 2,4220%
seperti di laporan lama. Ini perbedaan kode, bukan perubahan config yang disengaja.

---

## 3. Hasil per pair

| Pair | Ready | Selesai | Menang | Kalah | Win rate | Avg R | TIMEOUT | SL | TP2 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| ADAUSDT | 410 | 410 | 68 | 342 | 16,59% | −0,5075 | 340 | 47 | 23 |
| DOGEUSDT | 353 | 353 | 47 | 306 | 13,31% | −0,4718 | 304 | 28 | 21 |
| XRPUSDT | 386 | 386 | 56 | 330 | 14,51% | −0,4876 | 319 | 41 | 26 |
| **Gabungan** | **1.149** | **1.149** | **171** | **978** | **14,88%** | **−0,4898** | **963** | **116** | **70** |

**Rata-rata per hasil:**

| Hasil | Jumlah | Avg R |
|---|---:|---:|
| Menang | 171 | +0,5473 |
| Kalah | 978 | −0,6712 |
| Ambang impas | — | 55,08% |

Dengan payoff itu, win rate 55,08% dibutuhkan untuk impas. Yang terukur 14,88%.

---

## 4. Mengapa berbeda dari laporan 42,7%

1. **Time-stop 90 bar mengubah definisi trade selesai.** Sebanyak 963 dari 1.149 trade
   (83,8%) keluar karena waktu, bukan TP2/SL.
2. **TP2 efektif berbeda.** Kode parity menegakkan `TP2 ≥ 1,5 × risiko`; laporan lama memakai
   2,42%, eksperimen ini ≈2,91%.
3. **S1 wajib RVOL** dan **S9 default nonaktif** sudah diterapkan; jumlah sinyal berubah.
4. **Tidak ada ekstensi 48 jam.** Posisi sudah ditutup pada 90 menit, jadi analisis UNRESOLVED
   lama tidak berlaku.

Karena itu angka 42,7% tetap boleh dikutip sebagai **eksperimen lama**, tetapi tidak boleh
disebut win rate app atau dibandingkan langsung dengan 14,88%.

---

## 5. Batasan

1. Satu geometri, tiga pair, satu dataset 60 hari.
2. Orderbook diasumsikan tersedia (`--assume-orderbook-ok`).
3. Fee hanya Tokocrypto USDT taker; Indodax dan pair IDR tidak diuji.
4. Time-stop 90 bar adalah exit app saat ini; mengubahnya mengubah seluruh hasil.
5. Angka ini mengukur replay, bukan eksekusi paper/uang riil.

---

## 6. Perintah

```bash
# Config sementara: salin data/config/scalp_config.json, lalu ubah
# min_friction_multiple=2.0, target_net_rr=0.5, min_net_rr=0.5,
# replay.lookahead_bars=480. exit.mode=classic dan max_hold_bars_m1=90 tetap.

python3 tools/scalp_replay.py --pair ADAUSDT --data data/tokocrypto_v4 \
  --config /tmp/opencode/cfg_expectancy_parity.json \
  --assume-orderbook-ok --out ADAUSDT_parity.json

python3 tools/scalp_replay.py --pair DOGEUSDT --data data/tokocrypto_v4 \
  --config /tmp/opencode/cfg_expectancy_parity.json \
  --assume-orderbook-ok --out DOGEUSDT_parity.json

python3 tools/scalp_replay.py --pair XRPUSDT --data data/tokocrypto_v4 \
  --config /tmp/opencode/cfg_expectancy_parity.json \
  --assume-orderbook-ok --out XRPUSDT_parity.json
```

Artefak ringkas: `data/hasil/exp_scalping_expectancy_parity_2026-10-20.json`.
