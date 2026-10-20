# FEE TRUTH — Struktur Biaya Resmi Tokocrypto & Indodax (All-In)

Dokumen ini mendokumentasikan standar kebenaran biaya transaksi (**Fee Truth**) untuk aplikasi Android *analisa-pasar* dan offline replay `tools/scalp_replay.py`.

**Versi:** 1.1  
**Tanggal:** 2026-10-19  
**Status:** Konstanta biaya all-in. Dokumen ini bukan jaminan profit dan bukan bukti validasi strategi.

---

## 0. Prinsip Dasar

1. **Fee dan slippage wajib dipisah.**  
   Angka fee all-in bursa adalah konstanta biaya. Slippage adalah asumsi eksekusi dan harus di-stress-test per pair/likuiditas.

2. **Buy dan sell tidak simetris.**  
   PPh final hanya dikenakan di sisi jual. Model backtest tidak boleh memakai fee simetris untuk pasangan IDR.

3. **R dihitung dari SL gross.**  
   `1R` = jarak stop loss gross dari entry ke stop, dalam persen harga, sebelum biaya.  
   Kerugian net saat SL kena kira-kira:
   `SL_gross + F`, dengan `F` = total friksi round-trip (fee all-in beli + jual + slippage dua sisi).

4. **Target gross untuk net RR tertentu:**
   `Target_gross = k × (SL_gross + F) + F`, dengan `k` = target net RR.  
   Untuk `k = 1`: `Target_gross = SL_gross + 2F`.  
   Untuk `k = 2`: `Target_gross = 2 × SL_gross + 3F`.

5. **Dilarang memakai asumsi lama tanpa label all-in.**  
   Model upstream lama `≈0,72%` atau nominal `0,10%` tidak boleh dipakai tanpa mencantumkan label all-in bursa.

---

## 1. Tokocrypto (Efektif 18 Juni 2026)

Tokocrypto menerapkan tarif all-in mencakup *Trading Fee*, *Pajak PPh Final*, dan *Pungutan Bursa Kripto (ICEx)*. Nilai di bawah adalah akun **reguler (tanpa diskon TKO / VIP)**.

### A. Pasangan USDT / Kripto-ke-Kripto

| Tipe Order | Trading Fee | PPh Final | ICEx | Total All-In (per sisi) |
| :--- | :---: | :---: | :---: | :---: |
| **Maker (Limit)** | 0,15% | 0,21% | 0,0444% | **0,4044%** |
| **Taker (Market)** | 0,15% | 0,21% | 0,0444% | **0,4044%** |

- **Round-Trip Fee Kotor**: `0,4044% + 0,4044% = 0,8088%`
- **Slippage base case**: `0,08%` per sisi = `0,16%` round-trip
- **Total Friksi Transaksi Base Case**: `0,8088% + 0,16% = 0,9688% ≈ 0,97%`
- **Catatan:** pada pasangan USDT, maker dan taker all-in sama. Tidak ada insentif maker dari sisi biaya bursa.

---

### B. Pasangan IDR (Rupiah)

| Arah | Tipe Order | Komponen Biaya | Total All-In (per sisi) |
| :--- | :--- | :--- | :---: |
| **BELI** | Maker | Trading fee 0,10% + ICEx 0,0222% (PPh beli 0%) | **0,1222%** |
| **BELI** | Taker | Trading fee 0,20% + ICEx 0,0222% (PPh beli 0%) | **0,2222%** |
| **JUAL** | Maker | Trading fee 0,10% + PPh 0,21% + ICEx 0,0222% | **0,3322%** |
| **JUAL** | Taker | Trading fee 0,20% + PPh 0,21% + ICEx 0,0222% | **0,4322%** |

- **Round-Trip Taker Kotor**: `0,2222% + 0,4322% = 0,6544%`
- **Round-Trip Maker Kotor**: `0,1222% + 0,3322% = 0,4544%`
- **Total Friksi Taker Base Case**: `0,6544% + 0,16% = 0,8144%`
- **Total Friksi Maker Base Case**: `0,4544% + 0,16% = 0,6144%`

---

## 2. Indodax (Pasangan IDR Saja)

| Arah | Tipe Order | Total Biaya per Sisi |
| :--- | :--- | :---: |
| **BELI** | Maker | **0,11%** (atau 0% untuk promo koin tertentu; promo tidak dimodelkan) |
| **BELI** | Taker | **0,21%** |
| **JUAL** | Maker | **0,32%** (termasuk pajak PPh) |
| **JUAL** | Taker | **0,42%** (termasuk pajak PPh) |

- **Round-Trip Taker Kotor**: `0,21% + 0,42% = 0,63%`
- **Total Friksi Taker Base Case**: `0,63% + 0,16% = 0,79%`

---

## 3. Slippage: Asumsi, Bukan Fee Truth

Angka slippage `0,08%` per sisi adalah **asumsi base case**, bukan tarif resmi bursa. Untuk altcoin dengan likuiditas rendah atau order size besar, slippage bisa jauh lebih tinggi.

| Skenario | Slippage per Sisi | Round-Trip | Catatan |
| :--- | ---: | ---: | :--- |
| Optimistis | 0,05% | 0,10% | Hanya pair sangat likuid |
| Base case | 0,08% | 0,16% | Asumsi default dokumen |
| Konservatif | 0,20% | 0,40% | Altcoin likuiditas sedang |
| Stress test | 0,50%–1,00% | 1,00%–2,00% | Wajib untuk pair tipis |

Replay dan backtest wajib melaporkan hasil pada minimal **base case** dan **stress case**.

---

## 4. Implikasi Kritis Terhadap Scalping (Friction Barrier)

1. **Distorsi Stop Loss Sempit**  
   Jika strategi scalping memakai stop loss gross `0,5%`, total kerugian saat SL kena pada pasangan USDT adalah:

   - Aditif: `0,5% + 0,9688% = 1,4688%`
   - Multiplikatif: `(1 - 0,005) × (1 - 0,009688) - 1 ≈ -1,464%`

   Dalam satuan `R` (`1R = 0,5%`):

   - Aditif: `-1,4688% / 0,5% ≈ -2,94R`
   - Multiplikatif: `-1,464% / 0,5% ≈ -2,93R`

   **Koreksi:** angka `-2,49R` pada versi sebelumnya tidak valid dan dihapus. Nilai yang benar sekitar **`-2,93R` sampai `-2,94R`** tergantung metode.

2. **Kebutuhan Minimal Target Profit**  
   Untuk mencapai net RR `1:1` di atas biaya:

   `Target_gross = SL_gross + 2F`

   Dengan `SL_gross = 0,5%` dan `F = 0,9688%`:

   `Target_gross = 0,5% + 1,9376% = 2,4376%`

   Untuk `target_net_rr = 2,0`:

   `Target_gross = 2 × 0,5% + 3 × 0,9688% = 1,0% + 2,9064% = 3,9064%`

   Artinya, target profit gross harus jauh lebih besar dari sekadar `risk + friction`; friction muncul di sisi entry dan exit.

3. **Larangan Model Asumsi Palsu**
   - Dilarang memakai model upstream lama `≈0,72%` atau nominal `0,10%` tanpa mencantumkan label all-in bursa.
   - Replay offline dan kalkulator in-app wajib menggunakan konstanta all-in di atas.
   - Fee all-in dan slippage wajib ditampilkan sebagai dua parameter terpisah.

---

## 5. Status Implementasi Lantai Friksi (2026-10-12)

Peringatan §4 poin 1 di atas **sudah diimplementasikan** di engine, bukan lagi sekadar catatan.

- Parameter baru `min_friction_multiple` (default **6,0**, plateau optimum 5–10× menurut backtest awal) di `ScalpingConfig.kt` → `ScalpingRuntimeConfig` → `ScalpingRiskEngine.kt` dan mirror `tools/scalp_replay.py`.
- Efek: jarak stop loss dipaksa:

  `SL_min ≥ min_friction_multiple × (buy fee + sell fee + 2 × slippage)`

  Untuk Tokocrypto USDT (`F = 0,9688%`), lantai SL ≈ **5,81%**.  
  Untuk IDR taker (`F = 0,8144%`), lantai SL ≈ **4,89%**.  
  Untuk IDR maker (`F = 0,6144%`), lantai SL ≈ **3,69%**.

- Tambahan: `target_net_rr` default `1,25` → **`2,0`**.  
  Win-rate minimum agar impas turun dari ≈44% ke ≈33%.

---

## 6. Bukti Awal Backtest 30 Hari (Preliminary, Bukan Sertifikat Profit)

**Data:** 30 hari data M1 riil, 40 pair Tokocrypto.  
**Status:** Indikasi awal. Belum out-of-sample, belum walk-forward, belum uji signifikansi.

| Konfigurasi | Trade | Win-rate | avg R |
|---|---:|---:|---:|
| Lama (`min_risk_pct` 0,5%, tanpa lantai, USDT) | 1.258 | 22,1% | **−1,8192** |
| Lantai friksi 3× (USDT) | 224 | 86,6% | −0,0300 |
| **Lantai friksi 6× (USDT, default)** | **186** | **98,9%** | **+0,0695** |
| Lantai friksi 6× + IDR maker | 297 | 93,9% | +0,0430 |

**Catatan audit wajib:**

- Win-rate `98,9%` dengan `avg R +0,0695` dan `target_net_rr = 2,0` terlihat tidak intuitif. Kemungkinan penyebab: banyak exit kecil, definisi `R` tidak konsisten, denominator `R` sangat besar, atau ada bug replay.
- Tabel ini **tidak boleh** dipakai untuk klaim profitable sebelum trade ledger direkonsiliasi.
- Hasil saat ini baru menunjukkan “tidak rugi besar lagi”, belum terbukti menghasilkan edge bersih.
- Lihat `workplan2.md` §9.4–9.5 untuk batasan lengkap.

> ⚠️ **Tabel di atas berasal dari harness versi lama — koreksi 2026-10-20.**
> Angka "186 trade / +0,0695 avg R" **bukan** bukti bahwa harness lama belum memaksa
> SL ≥ `min_friction_multiple × F`. Harness `28a2c70^` sudah memakai lantai 6× **dan**
> `exit.mode = "full"`; `data/config/scalp_config_exit_full.json` serta
> `data/hasil/u3_toko_usdt_after.json` membuktikannya. Perbedaan angka lama berasal dari
> kombinasi exit full + dataset, bukan dari lantai yang hilang.
>
> Pada 2026-10-20 replay disamakan dengan app: S1 mewajibkan RVOL, S9 membaca
> `max_change_24h_pct`, S3 membaca `max_risk_atr`, dan **CLASSIC juga memakai time-stop 90 bar**
> (`ScalpExitParams.DEFAULT`). Hasil terukur BTCUSDT 60 hari (`data/tokocrypto_v4`):
> default 6× → **350 ready, 350 selesai, 14 menang (4,0%), avg R −0,1639**; semuanya
> `TIMEOUT` 90 bar. Exit full → **354 ready, 354 selesai (344 TIMEOUT, 10 TRAIL_SL),
> 16 menang (4,52%), avg R −0,1645**. Angka "0 trade selesai / 521 sinyal" adalah hasil
> harness **tanpa time-stop** dan tidak boleh dipakai sebagai parity app.
> Baris **IDR maker** di tabel juga historis: app dan replay sekarang **taker-only**, jadi
> angka itu tidak bisa direproduksi tanpa menghidupkan kembali jalur maker yang sudah dihapus.
> Rincian: `workplan2.md` §60.5 dan `data/hasil/replay_parity_2026-10-20.json`.

**Konsekuensi praktis:** pada tarif Tokocrypto, stop loss `0,5%–1%` secara matematis tidak bisa menghasilkan profit bersih apa pun strateginya. Unit risiko harus beberapa kali friksi, yaitu sekitar **5,8% untuk USDT** dan **3,7%–4,9% untuk IDR**. Ini berarti **scalping 1–5 menit bukan kendaraan yang layak di bursa ini**; horizon realistis adalah **1–8 jam**.

---

## 7. Sumber Tarif dan Perawatan Dokumen

- Sumber tarif: halaman resmi Tokocrypto dan Indodax.  
  **Tautan resmi wajib diisi saat publikasi.**
- Tanggal akses tarif: `[isi tanggal]`
- Tarif dapat berubah karena promo, diskon TKO/VIP, perubahan ICEx, atau kebijakan bursa.
- Setiap perubahan tarif wajib memperbarui:
  1. tabel all-in,
  2. konstanta replay,
  3. default `min_friction_multiple`,
  4. dokumentasi `target_net_rr`.

---

## 8. Ringkasan Konstanta Default

| Bursa / Pair | Mode | Fee Round-Trip | Slippage Round-Trip | Total Friksi `F` |
| :--- | :--- | ---: | ---: | ---: |
| Tokocrypto USDT | Maker/Taker | 0,8088% | 0,16% | **0,9688%** |
| Tokocrypto IDR | Taker | 0,6544% | 0,16% | **0,8144%** |
| Tokocrypto IDR | Maker | 0,4544% | 0,16% | **0,6144%** |
| Indodax IDR | Taker | 0,63% | 0,16% | **0,79%** |

**Default engine:**

- `min_friction_multiple = 6,0`
- `target_net_rr = 2,0`
- Slippage base case = `0,08%` per sisi
- Slippage stress case wajib diuji = `0,20%` dan `0,50%` per sisi
