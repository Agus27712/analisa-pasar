# FEE TRUTH — Struktur Biaya Resmi Tokocrypto & Indodax (All-In)

Dokumen ini mendokumentasikan standar kebenaran biaya transaksi (**Fee Truth**) untuk aplikasi Android *analisa-pasar* dan offline replay `tools/scalp_replay.py`.

---

## 1. Tokocrypto (Efektif 18 Juni 2026)

Tokocrypto menerapkan tarif all-in mencakup *Trading Fee*, *Pajak PPh Final*, dan *Pungutan Bursa Kripto (ICEx)*. Nilai di bawah adalah akun **reguler (tanpa diskon TKO / VIP)**.

### A. Pasangan USDT / Kripto-ke-Kripto
| Tipe Order | Trading Fee | PPh 22 Final | ICEx | Total All-In (per sisi) |
| :--- | :---: | :---: | :---: | :---: |
| **Maker (Limit)** | 0,15% | 0,21% | 0,0444% | **0,4044%** |
| **Taker (Market)** | 0,15% | 0,21% | 0,0444% | **0,4044%** |

- **Round-Trip Fee Kotor**: $0,4044\% + 0,4044\% = \mathbf{0,8088\%}$
- **Estimasi Slippage Realistis**: $0,08\%$ per sisi ($0,16\%$ round-trip)
- **Total Friksi Transaksi (Fee + Slippage)**: $\approx \mathbf{0,9688\%} \approx \mathbf{0,97\%}$

---

### B. Pasangan IDR (Rupiah)
| Arah | Tipe Order | Komponen Biaya | Total All-In (per sisi) |
| :--- | :--- | :--- | :---: |
| **BELI** | Maker | Trading fee 0,10% + ICEx 0,0222% (PPh Beli 0%) | **0,1222%** |
| **BELI** | Taker | Trading fee 0,20% + ICEx 0,0222% (PPh Beli 0%) | **0,2222%** |
| **JUAL** | Maker | Trading fee 0,10% + PPh 0,21% + ICEx 0,0222% | **0,3322%** |
| **JUAL** | Taker | Trading fee 0,20% + PPh 0,21% + ICEx 0,0222% | **0,4322%** |

- **Round-Trip Taker Kotor (Beli Taker + Jual Taker)**: $0,2222\% + 0,4322\% = \mathbf{0,6544\%}$
- **Round-Trip Maker Kotor (Beli Maker + Jual Maker)**: $0,1222\% + 0,3322\% = \mathbf{0,4544\%}$
- **Total Friksi Taker (dengan slippage 0,16%)**: $\approx \mathbf{0,8144\%}$

---

## 2. Indodax (Pasangan IDR Saja)

| Arah | Tipe Order | Total Biaya per Sisi |
| :--- | :--- | :---: |
| **BELI** | Maker | **0,11%** (atau 0% promosi koin tertentu) |
| **BELI** | Taker | **0,21%** |
| **JUAL** | Maker | **0,32%** (termasuk pajak PPh) |
| **JUAL** | Taker | **0,42%** (termasuk pajak PPh) |

- **Round-Trip Taker Kotor**: $0,21\% + 0,42\% = \mathbf{0,63\%}$
- **Total Friksi Taker (dengan slippage 0,16%)**: $\approx \mathbf{0,79\%}$

---

## 3. Implikasi Kritis Terhadap Scalping (Friction Barrier)

1. **Distorsi Stop Loss Sempit**:
   Jika strategi scalping menggunakan stop loss kotor $0,5\%$, total kerugian saat terkena stop loss pada pasangan USDT adalah:
   $$\text{Kerugian Bersih} \approx 0,5\% (\text{SL kotor}) + 0,97\% (\text{Friksi}) = 1,47\%$$
   Dalam satuan unit risiko $R$ ($1R = 0,5\%$), kerugian riil adalah $\mathbf{-2,94R}$ (atau $\mathbf{-2,49R}$ dengan formula kalkulasi multiplikatif). Hal ini menyebabkan penurunan saldo eksponensial meskipun win rate tampak moderat.
2. **Kebutuhan Minimal Target Profit**:
   Agar trade mencapai rasio $1:1\text{R}$ bersih di atas biaya:
   $$\text{Gross Target} \ge \text{Risk} + \text{Total Friction} = 0,5\% + 0,97\% = 1,47\%$$
3. **Larangan Model Asumsi Palsu**:
   - Dilarang memakai model upstream lama $\approx 0,72\%$ atau nominal $0,10\%$ tanpa mencantumkan label all-in bursa.
   - Replay offline dan kalkulator in-app wajib menggunakan konstanta all-in di atas.

---

## 4. Status implementasi lantai friksi (2026-10-12)

Peringatan §3 poin 1 di atas **sudah diimplementasikan** di engine, bukan lagi sekadar catatan.

- Parameter baru `min_friction_multiple` (default **6,0**, plateau optimum 5–10×) di `ScalpingConfig.kt` →
  `ScalpingRuntimeConfig` → `ScalpingRiskEngine.kt` dan mirror `tools/scalp_replay.py`.
- Efek: jarak stop loss dipaksa `≥ min_friction_multiple × (buy fee + sell fee + 2 × slippage)`.
  Untuk Tokocrypto USDT (friksi 0,9688%) lantai ≈ **5,81%**; IDR taker (0,8144%) ≈ 4,89%;
  IDR maker (0,6144%) ≈ 3,69%.
- Tambahan: `target_net_rr` default 1,25 → **2,0** (win-rate minimum agar impas turun dari
  ≈44% ke ≈33%).

**Bukti (30 hari data M1 riil, 40 pair Tokocrypto):**

| Konfigurasi | Trade | Win-rate | avg R |
|---|---:|---:|---:|
| Lama (`min_risk_pct` 0,5%, tanpa lantai, USDT) | 1.258 | 22,1% | **−1,8192** |
| Lantai friksi 3× (USDT) | 224 | 86,6% | −0,0300 |
| **Lantai friksi 6× (USDT, default)** | **186** | **98,9%** | **+0,0695** |
| Lantai friksi 6× + IDR maker | 297 | 93,9% | +0,0430 |

Artinya: **friksi adalah penyebab utama hasil negatif**, dan hasilnya baru "tidak rugi lagi",
belum terbukti untung. Lihat `workplan2.md` §9.4–9.5 untuk batasan lengkap.

**Konsekuensi praktis:** pada tarif Tokocrypto, stop loss 0,5–1% secara matematis tidak bisa
menghasilkan profit bersih apa pun strateginya — unit risiko harus beberapa kali friksi
(≈5,8% untuk USDT, ≈3,7–4,9% untuk IDR). Ini berarti **scalping 1–5 menit bukan kendaraan yang
layak di bursa ini**; horizon realistis adalah 1–8 jam.

