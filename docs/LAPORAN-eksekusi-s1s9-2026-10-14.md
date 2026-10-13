# Laporan Eksekusi S1–S9 (Perbaikan Strategi Scalping)

**Tanggal:** 14 Oktober 2026
**Repo:** `/tmp/analisa-pasar`, branch `patch`
**Baseline:** `1d956ce` (setelah P0–P2)
**Sumber kebutuhan:** `BELUM-DIKERJAKAN-2026-10-14.md` Bagian 1 (S1–S9), berasal dari audit GNS/USDT & AMP/USDT
**Hasil verifikasi:** `358 test lulus · 0 gagal · 1 dilewati (50 kelas)` · `assembleDebug` sukses

---

## Ringkasan satu paragraf

Sembilan saran strategi sudah diimplementasikan dan diuji. Dua di antaranya mengubah **perilaku default**
(lantai profit S2 aktif di 2× friksi; gerbang extended-move S9 **sengaja nonaktif**), satu mengubah
kepemilikan exit (S7: beli manual kini punya mesin exit), sisanya menambah parameter yang bisa diubah
dari Pengaturan. Replay GNS/USDT memperlihatkan perbaikan entry+exit mengubah 17 trade dengan
`avg R +0,0021 / PF 1,01` menjadi 12 trade dengan `avg R +0,3513 / PF 4,77` (hanya S2) atau 4 trade
`100% menang / tanpa drawdown` (S2+S8). Satu rekomendasi **saya batalkan sebagian**: ambang
extended-move 25% terbukti memotong fase tren yang justru sehat, jadi default-nya 0.

---

## 1. Per item: apa yang berubah

| # | Perubahan | Berkas utama | Status |
|---|---|---|---|
| **S1** | RVOL **wajib** untuk setup BREAKOUT. Logika lama "2 dari 3 konfirmasi" (pada regime BREAKOUT cukup 1 dari 3) dihapus; setelah RVOL lolos masih wajib 1 konfirmasi lain (arus beli **atau** momentum). | `ScalpSetupDetector.kt:194-208`, mirror di `tools/scalp_replay.py` | Selesai |
| **S2** | **Lantai profit tutup**: `minProfitFrictionMultiple` (default **2,0**). Exit profit sukarela (early) dan peng-arm-an BE/trailing hanya boleh setelah profit **kotor** ≥ 2 × friksi round-trip (fee beli + fee jual + 2× slippage = 1,9376% untuk Tokocrypto USDT). | `ScalpExitConfig.kt`, `PaperExitResolver.kt` (resolver + FSM), `tools/scalp_replay.py` | Selesai |
| **S3** | **SL keras eksplisit & bisa dikonfigurasi**: `MAX_RISK_ATR` naik dari konstanta privat menjadi `ScalpingRuntimeConfig.maxRiskAtr` (default 2,0; preset ketat 1,5 / longgar 2,5). Batas ini tidak pernah mempersempit SL di bawah lantai friksi (`maxRiskAbs = max(N×ATR, minRiskAbs)`). | `ScalpingConfig.kt`, `ScalpingRuntimeConfig.kt`, `ScalpingRiskEngine.kt`, `tools/scalp_replay.py` | Selesai |
| **S4** | Konstanta `ATR_PCT_*` **benar-benar dipakai**: `MarketRegimeEngine` tidak lagi memakai literal 3,5 / 0,8; evaluator menambahkan penanda ATR di luar band scalp ideal (0,3–1,5%). | `MarketRegimeEngine.kt:104-110`, `ScalpingConfig.kt` (`isAtrInScalpSweetBand`, `atrBandLabel`), `ScalpingMtfEvaluator.kt`, `tools/scalp_replay.py` | Selesai |
| **S5** | **Basis PnL seragam**: slippage per sisi kini dikenakan ke harga isi order MARKET simulasi, sehingga kas, PnL laporan, dan mesin exit memakai satu angka (fee + 2× slippage). Order LIMIT tidak kena slippage (limit tidak slip). | `SimulationTradeStore.slippagePct`, `SimulationCoordinator.setSlippagePct`, `TradingViewModel` / `TradingViewModelSync` | Selesai |
| **S6** | Label volume 24 jam di ekspor audit: sebelumnya `… $baseAsset` padahal isinya volume **quote**. | `TradeLogExporter.kt` | Selesai |
| **S7** | **Beli manual kini punya mesin exit**: `armPaperExitEngine()` dipanggil di jalur `syncSimulationTradeToPositionStore` (BUY). Bila sinyal tidak memberi SL, SL keras diturunkan dari lantai friksi; bila lantai pun nonaktif, posisi dibiarkan tanpa mesin dan hal itu **dicatat ke log**. Auto-sell lama & trailing lama dimatikan agar kepemilikan exit tunggal. | `PaperAutoTradeCoordinator.kt` (`armPaperExitEngine`), `TradingViewModelSync.kt:284-296` | Selesai |
| **S8** | **BE & trailing proporsional ATR**: `beTriggerAtrFraction` (0,5) dan `trailAtrFraction` (0,6). Nilai efektif = `max(nilai persen, fraksi × ATR%)`. GNS (ATR 3,31%): BE 0,35% → **1,655%**, trail 0,15% → **1,986%** (bukan 1/22 ATR). | `ScalpExitConfig.kt`, `PaperExitResolver.kt` (resolver + FSM, param `atrPct`), `tools/scalp_replay.py` | Selesai |
| **S9** | **Gerbang extended-move**: `maxChange24hPct` di `ScalpingRuntimeConfig`, diteruskan dari `tick.change24h` → evaluator. Menahan `ready` **dan** `early` (kualitas sinyal tetap sah, hanya waktunya salah), alasan penolakan `STEP0_EXTENDED_MOVE`, stage `HOLD`. **Default 0 = nonaktif** (lihat §3). | `ScalpingConfig.kt`, `ScalpingRuntimeConfig.kt`, `ScalpingMtfEvaluator.kt`, `LearningTradingEngine.kt`, `tools/scalp_replay.py` | Selesai (default nonaktif) |

---

## 2. Bukti numerik (replay offline, data asli Tokocrypto)

Perintah: `python3 tools/scalp_replay.py --pair <PAIR> --data data/tokocrypto_<pair> --config <cfg> --assume-orderbook-ok`
Fee 0,4044%/sisi, slippage 0,08%, lookahead 120 bar M1. Kolom `S2` = `min_profit_friction_multiple`,
`be/tr` = `be_trigger_atr_fraction` / `trail_atr_fraction` (0 = nilai persen lama).

### GNS/USDT — 2000 bar M1

| Konfigurasi | ready | trade resolve | win rate | avg R | profit factor | max DD (R) |
|---|---|---|---|---|---|---|
| **Sebelum** (skrip lama, tanpa S1/S2/S8) | 23 | 17 | 76,5% | **+0,0021** | **1,01** | 3,89 |
| S1 saja (S2=0, be/tr=0) | 20 | 14 | 78,6% | +0,0220 | 1,09 | 3,28 |
| S1+S2 (S8=0) | 18 | 12 | 91,7% | **+0,3513** | **4,77** | 1,12 |
| **S1+S2+S8 (default baru)** | 10 | 4 | **100%** | +0,1782 | ∞ | **0,00** |

> Catatan: `ready` ikut berubah karena setelah trade tidak resolve skrip melompat `lookahead` bar —
> jadi angka `ready` bukan ukuran kualitas, melainkan konsekuensi dari exit yang berbeda.

### AMP/USDT — 1999 bar M1

| Konfigurasi | ready | trade resolve | win rate | avg R | profit factor |
|---|---|---|---|---|---|
| **Sebelum** | 15 | 10 | 80,0% | −0,0630 | 0,75 |
| S1 saja | 15 | 10 | 80,0% | −0,0602 | 0,76 |
| S1+S2 (S8=0) | 13 | 7 | 71,4% | −0,0967 | 0,73 |
| **S1+S2+S8 (default baru)** | 13 | 7 | 71,4% | −0,0680 | **0,81** |

AMP tetap negatif di semua konfigurasi — memang begitu adanya: pair ini bergerak berlawanan dengan
asumsi entry. Yang bisa diharapkan dari perbaikan exit hanyalah **mengurangi kerugian** (PF 0,75 → 0,81),
dan itu yang terjadi.

---

## 3. Kenapa S9 saya nonaktifkan secara default (keputusan yang saya ambil sendiri)

Rekomedasi awal: tahan sinyal bila koin sudah naik ≥25% dalam 24 jam (dari kasus AMP +28,09%).
Begitu diuji pada data GNS, ambang itu **memotong fase tren yang justru sehat**:

| `max_change_24h_pct` | ready | trade resolve | win rate | avg R |
|---|---|---|---|---|
| 0 (nonaktif) | 10 | 4 | 100% | +0,1782 |
| 25% | 5 | **0** | — | — |
| 40% | 5 | **0** | — | — |
| 60% | 9 | 4 | 100% | +0,1782 |

Artinya: dengan ambang 25% **seluruh** trade GNS hilang, padahal tanpa gerbang keempat trade itu
**4/4 menang dan tanpa drawdown**. Satu pair (AMP) bukan dasar yang cukup untuk mengubah perilaku entry
di semua pair. Maka:

- Implementasi **tetap dikirim** (kode, alasan penolakan, stage HOLD, slider UI 0–60%).
- Default = **0 (nonaktif)**, termasuk di preset Ketat & Longgar.
- Kalibrasi ambang masuk agenda **P4.1 walk-forward multi-pair**.

Catatan teknis: replay menghitung change-24h dari jendela **1440 bar M1 berjalan**, sedangkan ticker
bursa memakai reset harian. Karena itu AMP tidak terpengaruh gerbang di replay (sinyalnya tidak jatuh
pada bar dengan change-24h ≥25%), meski laporan audit mencatat +28,09% dari ticker.

---

## 4. Perubahan perilaku yang WAJIB Anda tahu

1. **Lantai profit (S2) aktif dengan default 2× friksi.** Konfigurasi lama yang diimpor **tanpa** kunci
   `min_profit_friction_multiple` akan langsung memakai 2,0. Dampaknya: early-profit dan peng-arm-an
   BE/trailing di bawah +1,94% kotor **tidak lagi terjadi**. Ini sengaja — itulah inti perbaikan —
   tetapi bila Anda ingin perilaku lama, setel slider **Lantai profit (x friksi) = 0**.
2. **S8 mengubah jarak trailing secara drastis pada pair ber-ATR tinggi.** GNS: 0,15% → 1,99%. Efek
   samping yang wajar: lebih sedikit trade ditutup trailing dan lebih banyak yang berakhir di
   **time-stop** (TIMEOUT). Pastikan `max_hold_bars_m1` tidak 0 (Anda memakai 10 bar).
3. **Beli manual (S7) sekarang bisa menjual sendiri.** Bila Anda terbiasa menutup posisi manual
   sepenuhnya tanpa SL, matikan `autoExitEnabled` atau SL akan dipasang dari lantai friksi.
4. **Order MARKET simulasi (S5) kini kena slippage 0,08%/sisi**, jadi harga isi dan PnL sedikit
   berbeda dari versi sebelumnya. Ini yang membuat angka laporan = angka mesin.

---

## 5. Parameter baru (semua bisa diubah di Pengaturan)

| Kunci JSON | Default | Arti |
|---|---|---|
| `max_risk_atr` | 2,0 | Batas keras jarak SL sebagai kelipatan ATR (S3) |
| `max_change_24h_pct` | **0** | Gerbang extended-move, %/24 jam; 0 = nonaktif (S9) |
| `exit.min_profit_friction_multiple` | 2,0 | Lantai profit kotor = kelipatan × friksi RT (S2) |
| `exit.be_trigger_atr_fraction` | 0,5 | Trigger BE = fraksi × ATR%, minimal nilai persen lama (S8) |
| `exit.trail_atr_fraction` | 0,6 | Jarak trailing = fraksi × ATR% (S8) |

---

## 6. Pengujian

Berkas baru:
- `app/src/test/java/agu/analys/engine/scalping/ScalpStrategyFixTest.kt` — 9 uji (S1, S2, S3, S4, S8, S9, JSON round-trip).
- `app/src/test/java/agu/analys/trading/SimulationSlippageParityTest.kt` — 3 uji Robolectric (S5).

Berkas yang disesuaikan (bukan karena bug, melainkan karena default berubah):
- `PaperExitResolverParityTest.kt` — skenario lama bergerak di bawah lantai, jadi lantai
  **dimatikan eksplisit**; ditambah 2 skenario baru untuk lantai aktif (angka harapan dihasilkan ulang
  dari Python).
- `PaperTradeJournalTest.kt` — sama; `machine()` kini menerima `minProfitFrictionMultiple`.

Uji yang mereproduksi kasus nyata:
- S1: breakout dengan RVOL 0,20× pada regime BREAKOUT (arus beli kuat) **ditolak** — dulu lolos.
- S2: exit di +1,5% kotor diblokir oleh lantai 1,9376%; tanpa lantai tetap keluar (EARLY_PROFIT).
- S8: wick 0,4% dari peak menyapu trailing 0,15% (TRAIL_SL) tetapi **tidak** menyentuh 1,99% (TIMEOUT).

**Sebelum:** 344 test · **Sesudah:** 358 test (0 gagal, 1 dilewati, 50 kelas).

---

## 7. Yang belum selesai (dari daftar yang sama)

- **Bagian 2** (A1, A6, A8, A9, A10, B1, B3, B4, B5, B6, B7) — belum disentuh. A1 (label volume) sudah
  tertutup sebagian oleh S6 di jalur ekspor, tetapi field `volume24h` itu sendiri belum diperbaiki.
- **Bagian 3**: P4.1 walk-forward (kini makin penting karena S9 butuh kalibrasi), P4.4 pemulihan
  progres FSM, P4.2 maker IDR, P5 data Indodax.
- **Bagian 4**: verifikasi lapangan APK, replay lebih banyak pair, pertanyaan sinyal AMP 00:51.

---

## 8. Cara mengulang verifikasi ini

```bash
cd /tmp/analisa-pasar
python3 - <<'PY'   # konfigurasi "hardened" untuk replay
import json, copy
c = json.load(open('data/config/scalp_config_user.json'))
c['max_risk_atr'] = 2.0
c['max_change_24h_pct'] = 0.0          # S9 nonaktif (default)
c['exit'].update(min_profit_friction_multiple=2.0,
                 be_trigger_atr_fraction=0.5, trail_atr_fraction=0.6)
json.dump(c, open('/tmp/cfg_hard.json','w'))
PY
python3 tools/scalp_replay.py --pair GNSUSDT --data data/tokocrypto_gns \
  --config /tmp/cfg_hard.json --assume-orderbook-ok --out /tmp/rp_gns_new.json
```
