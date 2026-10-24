# Eksperimen: Paritas entri BUY — engine app vs spek kriptoyoi frontend

**Tanggal:** 2026-10-22
**Pertanyaan owner:** apakah engine scalping app (port kriptoyoi `p0_engine`) dan spek
kriptoyoi asli menghasilkan **entri BUY yang sama** bila keduanya diuji pada data yang
sama dengan **fee truth yang sama dengan engine app**?

**Jawaban terukur: TIDAK SAMA.** Irisan bar entri hanya **77 dari 620 entri app (12,4%)**.
Jaccard terhadap 15.269 entri kriptoyoi ≈ **0,5%**. Dua engine ini menilai candle dengan
aturan yang hampir tidak tumpang tindih, dan di geometri exit + fee truth yang sama,
keduanya tetap negatif.

---

## 1. Ringkasan

| Angka | Engine app (port p0) | Spek kriptoyoi frontend | Selisih |
|---|---:|---:|---:|
| Entri BUY | **620** | **15.269** | kriptoyoi ≈ **24,6×** lebih sering |
| Win rate | **14,84%** | **10,33%** | −4,51 pp |
| Avg R | **−0,1569** | **−0,1627** | −0,0058R |
| Irisan bar entri | — | — | **77 bar** |
| Jaccard (A∪B) | — | — | **≈0,5%** |
| Cakupan entri app | — | — | 12,4% juga ditandai kriptoyoi |
| Cakupan entri kriptoyoi | — | — | 0,5% juga menjadi entri app |

Angka engine app **identik laporan P0** (`exp_p0_gatekeeper_ADA-DOGE-XRP.md`):
620 trade, WR 14,84%, avg R −0,1569 — jadi pembandingnya kompatibel, bukan jebakan.

---

## 2. Apa yang dibandingkan

**Cakupan masing-masing engine:**

| | Engine app | Spek kriptoyoi |
|---|---|---|
| Sumber | `tools/scalp_replay.py` (port `ScalpingMtfEvaluator` + gate P0) | `static/app.js` kriptoyoi master: `generateScalperSignals` (baris 554–780) + `getScalperParams` (1m) |
| Basis sinyal | skenario 8 langkah + skor ≥ ambang per setup, gate MTF 4 TF, veto BTC, regime OR | EMA 9/21/50, StochRSI (14,14,3,3), RSI 14 pada candle tutup; 4 syarat BUY (bounce EMA21/50, golden cross, breakout EMA9, stoch cross dari oversold); cooldown 7 candle (M1) |
| Disiplin posisi | satu posisi sampai exit | satu posisi sampai marker CLOSE app.js (auto-close + `maxActiveCandles` 30) |
| Exit yang dipakai di pengukuran | engine sendiri | **engine app** (`risk_levels` + `resolve_trade`) — geometri, TP1/TP2, lantai friksi, time-stop 90 menit identik |

**Fee truth (FEE_TRUTH v1.1, dibaca dari `data/config/scalp_config.json`, tidak ada
angka fee kedua):** Tokocrypto USDT taker 0,4044% per sisi, slippage 0,08% per sisi,
friksi aditif round-trip **0,9688%**, lantai SL `min_friction_multiple` **6,0**,
`target_net_rr` **2,0**. Kedua kumpulan entri dievaluasi dengan fungsi yang sama
persis — selisih hasil murni berasal dari **bar mana** yang dipilih sebagai entri.

Untuk entri kriptoyoi yang tidak punya pola engine, geometri dipakai bertahap dengan
setup generik `TREND_PULLBACK` pada bar itu (formulasi SL/TP tetap milik app).

---

## 3. Hasil per pair

| Pair | App N | App WR | App avg R | Kriptoyoi N | Kriptoyoi WR | Kriptoyoi avg R | Irisan | Jaccard | Cakupan app |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| ADAUSDT | 210 | 19,52% | −0,1568 | 4.874 | 13,34% | −0,1648 | 28 | 0,554% | 13,33% |
| DOGEUSDT | 193 | 11,92% | −0,1552 | 5.199 | 8,78% | −0,1643 | 24 | 0,447% | 12,44% |
| XRPUSDT | 217 | 12,90% | −0,1584 | 5.196 | 9,17% | −0,1606 | 25 | 0,464% | 11,52% |
| **Total** | **620** | **14,84%** | **−0,1569** | **15.269** | **10,33%** | **−0,1627** | **77** | **≈0,5%** | **12,4%** |

### 3.1 Kualitas 77 bar irisan

Bar entri yang dinilai "layak BELI" oleh **kedua** engine ternyata **tidak lebih baik**:

| Pair | Irisan | WR app di irisan | Avg R app di irisan |
|---|---:|---:|---:|
| ADAUSDT | 28 | 14,29% | −0,1707 |
| DOGEUSDT | 24 | 12,50% | −0,1397 |
| XRPUSDT | 25 | **0,00%** | −0,2008 |

Konsistensi penuh antara dua engine tidak menghasilkan keunggulan; sampel irisan kecil
(24–28) dan seluruhnya tetap negatif. **Kesamaan bukan tanda keunggulan.**

---

## 4. Kenapa keduanya tidak sama (bukan pengukuran yang salah)

1. **Sumber sinyal berbeda.** Lapisan frontend kriptoyoi adalah *indicator overlay*
   (EMA/StochRSI) yang dievaluasi **per candle tutup** dan menandai.chart — sekali
   kondisi teknikal tercukupi, ia menandai BUY, cooldown 7 candle. Engine app menilai
   **state pasar** (struktur M15/H1, regime, RVOL, room to resistance, skor ≥ ambang,
   gate MTF/veto BTC) — frekuensinya jauh lebih langka.
2. **Frekuensi 24,6× lebih tinggi dengan WR lebih rendah** konsisten dengan sifat
   sinyal teknikal-olahan: lebih banyak peluang masuk yang ternyata noise.
3. **Tidak ada korelasi komposisi.** Lanjut kriptoyoi menandai BUY di tengah posisi
   app (4.819 bar) dan melewatkan 543 entri app — bila engine app ditandai ulang dengan
   sinyal kriptoyoi, tidak ada trade yang "diselamatkan" secara terukur.

## 5. Batasan

1. **Interval M1 saja.** Frontend kriptoyoi default `state.interval = '1m'`
   (`app.js` baris 12) — `getScalperParams('1m')` dipakai. Varian M5/15m tidak diuji.
2. **Data 60 hari, 3 pair** (`data/tokocrypto_v4`, 87.000 bar M1), HTF disintesis dari M1
   (`htf_source: synthesize`) — sama dengan laporan P0.
3. **TTL tidak diuji.** Kedua engine direplay masuk di bar sinyal; TTL mengatur
   penantian setup di app, bukan di replay (aturan AGENTS.md).
4. **Regime/ordebook:** `--assume-orderbook-ok` (Step2 lolos tanpa depth nyata);
   kriptoyoi frontend tidak punya konsep orderbook sama sekali.
5. **Entri kriptoyoi dievaluasi dengan geometri app** (setup generik
   `TREND_PULLBACK`), bukan geometri milik frontend kriptoyoi (yang tak punya SL/TP dari
   ATR/SR) — ini disengaja supaya fee truth dan exit identik.

## 6. Verifikasi

| Pemeriksaan | Hasil |
|---|---|
| `python3 tools/entry_parity_kriptoyoi.py --selftest` | OK — kontrol negatif (turun terus): **0 BUY**; kontrol positif (golden cross sintetis): **1 BUY** |
| Konsistensi engine app | DOGE rerun harness **193** = CLI `scalp_replay.py` **193**; total 620/14,84%/−0,1569 = laporan P0 persis |

## 7. Perintah reproduksi

```sh
python3 tools/entry_parity_kriptoyoi.py --selftest
for p in ADAUSDT DOGEUSDT XRPUSDT; do
  python3 tools/entry_parity_kriptoyoi.py --pair "$p" \
    --data data/tokocrypto_v4 --config data/config/scalp_config.json \
    --out "/tmp/paritas_$p.json"
done
```

Hasil JSON tersimpan di `data/hasil/exp_entry_parity_kriptoyoi_<PAIR>_2026-10-22.json`.

## 8. Keputusan

1. **Tidak ada sama sekali indikasi mengganti pola entri app dengan lapisan A kriptoyoi**
   atau menjalankan keduanya di sisi aplikasi — frekuensinya 24,6× dengan WR lebih rendah;
   menurut owner lapisan A juga bukan bagian engine yang pernah di-port.
2. Paritas rendah ini **bukan penanda bug**: dua aturan entri memang berbeda semantik.
   Jangan campur angka keduanya di dokumen lain (aturan AGENTS.md: beri label geometri).
3. Angka tetap konsisten dengan temuan besar: **kedua engine negatif pada unit risiko
   5,81%**; perbaikan yang layak tetap kualitas entry/exit, bukan menumpuk gate baru.
