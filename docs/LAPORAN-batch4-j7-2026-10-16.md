# Laporan Batch 4 — J7: radar jual ikut basis FSM

**Tanggal:** 2026-10-16
**Branch:** `patch`
**Commit perbaikan:** `d1097aa`
**Sumber temuan:** `docs/bugs/temuan_bugs_jurnal_pnl.md` butir **J7**
**Cakupan:** kartu jual pada radar (`RadarSellSection` + `SellCalculationCard`) dan penyaluran
asumsi slippage ke dalamnya.

J7 adalah **satu-satunya temuan sisa** dari ketiga audit (jurnal, paper, notifikasi) yang menyentuh
**permukaan uang riil**: kartu yang paling sering dibaca pengguna tepat sebelum menekan tombol jual.

Seluruh angka di §6 diukur pada pohon commit ini — tidak ada berkas di luar cakupan yang tertinggal
di pohon kerja saat pengukuran, jadi angkanya tidak perlu ditandai "diturunkan".

---

## 1. Ringkasan

| | Sebelum | Sesudah |
|---|---|---|
| Rumus angka kartu jual | ditulis sendiri di dalam Composable | satu resolver murni `SellEstimateResolver` |
| Friksi yang dipotong (Tokocrypto IDR taker) | **0,4322%** (hanya fee jual) | **0,8186%** (fee beli + fee jual + slippage 2 sisi) |
| Pada bruto **+0,50%** | **+0,0656% → "JUAL UNTUNG"** | **−0,3160% → RUGI** |
| Ambang pertama "JUAL UNTUNG" | ≈ **+0,4341%** | ≈ **+0,8186%** |
| Teks kartu | "sudah bersih fee & pajak" | "setelah biaya beli, biaya jual, pajak & slippage" |
| Uji | 0 | **17** (`SellEstimateTest`) |

---

## 2. Masalah

`ui/components/detail/RadarSellSection.kt` menghitung angka kartu sendiri, terpisah dari
`FeeSchedule` yang sudah disepakati di `workplan2.md` §24:

```kotlin
val grossSellValueIdr = activeSellQty * effectiveSellPrice
val sellFeeIdr        = grossSellValueIdr * (activeFeePct / 100.0)
val netReceivedSellIdr = (grossSellValueIdr - sellFeeIdr).coerceAtLeast(0.0)
val costBasisIdr      = activeSellQty * effectiveBuyPrice          // ← TANPA fee beli
val netProfitIdr      = if (effectiveBuyPrice > 0.0) netReceivedSellIdr - costBasisIdr else 0.0
val netProfitPct      = if (costBasisIdr > 0.0) (netProfitIdr / costBasisIdr) * 100.0 else 0.0
```

Lalu `SellCalculationCard` menulis **"JUAL UNTUNG (PROFIT) … sudah bersih fee & pajak"** di atas
angka itu. Yang dipotong hanya fee jual; fee beli dan slippage tidak pernah masuk.

| Pair (taker) | Friksi di kartu | Friksi FSM | Selisih |
|---|---|---|---|
| Tokocrypto IDR | 0,4322% | 0,8186% | **0,3864 pp** |
| Tokocrypto USDT | 0,4044% | **0,9735%** | **0,5691 pp** |

### Koreksi angka audit

Dokumen temuan menulis friksi FSM Tokocrypto USDT sebagai **0,8226%** (selisih 0,4182 pp). Angka itu
tidak cocok dengan rumus mana pun:

- bentuk **aditif** (fee dijumlahkan): 0,4044 + 0,4044 + 0,16 = **0,9688%**;
- bentuk **multiplikatif** (yang dipakai `FeeSchedule`, `PaperExitResolver`, dan
  `tools/scalp_replay.py`): **0,9735%** — angka yang sudah terdokumentasi di §24 dan di
  `NetPnlParityTest`.

Jadi selisih yang benar adalah **0,5691 pp**, bukan 0,4182 pp. Arah temuan tidak berubah (kartu tetap
memotong jauh lebih sedikit dari FSM); hanya angkanya perlu dikoreksi supaya tidak dikutip sebagai
kebenaran. Untuk Tokocrypto IDR angka audit sudah benar.

---

## 3. Akar penyebab — §24 memigrasi *evaluator*, bukan *kartunya*

§24.3 mencatat "Radar jual & kartu posisi" sudah diseragamkan ke basis FSM. Yang benar-benar
dipindahkan saat itu adalah:

- `engine/sell/SellSignalEvaluator.kt` — evaluator ambang `READY_TO_SELL`;
- `model/PositionContext.kt` — basis laba/rugi konteks posisi.

Judul `NetPnlParityTest` sendiri menyebut **"radar jual (`SellSignalEvaluator`)"** — jadi "radar jual"
di §24 bermakna *evaluator*, sedangkan **kartu UI** `RadarSellSection` + `SellCalculationCard`
tidak pernah ikut. Itulah kenapa evaluator dan kartu di halaman yang sama bisa berbeda 0,3864 pp.

Ikut tidak akurat: §24.1 menulis basis lama radar sebagai "fee jual **maker** saja" (0,3322%). Kode
hari ini memakai `activeFeePct`, yaitu tarif sesuai toggle **Limit·Maker / Instant·Taker** — bukan
maker tetap. Di mode taker (default saat mengikuti best bid) friksinya 0,4322%, bukan 0,3322%.

---

## 4. Perbaikan

### 4.1 Resolver murni baru — `trading/SellEstimate.kt`

```kotlin
data class SellEstimate(
    val quantity: Double, val entryPrice: Double, val sellPrice: Double,
    val buyFeePct: Double, val sellFeePct: Double, val slippagePct: Double,
    val grossSellValue: Double,
    val sellFeeAmount: Double, val sellSlippageAmount: Double,
    val buyFeeAmount: Double, val buySlippageAmount: Double,
    val netProceeds: Double,      // kas bersih masuk saldo
    val costBasisNet: Double,     // modal bersih (termasuk fee beli + slippage)
    val netProfit: Double, val netProfitPct: Double
)

object SellEstimateResolver {
    fun resolve(quantity, entryPrice, sellPrice, buyFeePct, sellFeePct, slippagePct): SellEstimate
}
```

Seluruh angkanya mendelegasi ke `FeeSchedule.netSellValue` dan `FeeSchedule.costBasisNet`, jadi
basisnya mustahil menyimpang dari FSM lagi. Invarian yang dijaga:

```
(netProceeds / costBasisNet − 1) × 100  ==  netProfitPct  ==  FeeSchedule.netProfitPct
```

### 4.2 Basis biaya yang dipakai

| Kaki | Tarif | Alasan |
|---|---|---|
| **Jual** | tarif order type yang dipilih pengguna (maker/taker) | itu yang benar-benar dibayar pada kaki jual yang sedang dipricilkan |
| **Beli** | **taker** | order beli sudah terjadi dan jenisnya tidak diketahui di sini; taker adalah asumsi terburuk dan sekaligus basis FSM (`netProfitPctFor` default taker) |
| **Slippage** | dua sisi, `scalpingRuntimeConfig.slippagePct` | mengikuti konvensi `FeeSchedule` (slippage tetap dibebankan walau maker) |

### 4.3 Titik pemasangan

| Berkas | Perubahan |
|---|---|
| `trading/SellEstimate.kt` | **baru** — `SellEstimate`, `SellEstimateResolver`, `frictionBreakdownLabel`, `profitBasisNote` |
| `ui/components/detail/RadarSellSection.kt` | berhenti menghitung; memanggil resolver. Parameter baru `buyTakerFeePct`, `slippagePct` |
| `ui/components/detail/sell/SellCalculationCard.kt` | menerima satu `SellEstimate` (dulu 8 parameter angka); label diperbaiki |
| `ui/components/detail/RadarTransactionFeeSection.kt` | meneruskan `fees.buyTakerPct` + `slippagePct` |
| `ui/components/detail/WaitingEntryRadarCard.kt` | parameter baru `slippagePct` |
| `ui/screens/DetailChartScreen.kt` | mengirim `scalpingRuntime.slippagePct` |

### 4.4 Perbaikan label (inti keluhan J7)

| Sebelum | Sesudah |
|---|---|
| `"Potongan Biaya & Pajak (0,43%)"` | `"Potongan Biaya, Pajak & Slippage"` + sub-label `"Beli taker 0.22% + Jual taker 0.43% + Slippage 2×0.08%"` |
| `"Taker Fee + Pajak PPh/PPN"` | sub-label enumerasi di atas (menyebut maker/taker kaki jual apa adanya) |
| `"Total Modal Pembelian"` | `"Total Modal + Biaya Beli & Slippage"` |
| `"Kas … bersih yang langsung masuk dompet"` | `"Kas … bersih setelah biaya jual, pajak & slippage"` |
| `"Hasil bersih melebihi modal (sudah bersih fee & pajak)"` | `"Hasil bersih melebihi modal setelah biaya beli, biaya jual, pajak & slippage"` |
| saat modal belum diketahui: persen tunggal yang seolah lengkap | `"Jual taker 0.43% + Slippage 0.08% (biaya beli belum diketahui)"` |

Persen tunggal di judul sengaja **dihapus**: angka itu tidak punya basis yang jelas (persen dari
nilai jual bruto? dari modal?), dan justru itulah yang dulu menyesatkan. Komponennya kini disebut
satu per satu sehingga pengguna bisa mencocokkannya sendiri.

---

## 5. Efek dan selisih yang **sengaja** tersisa

| Mode | Friksi kartu (sebelum) | Friksi kartu (sesudah) | FSM |
|---|---|---|---|
| Tokocrypto IDR taker | 0,4322% | **0,8186%** | 0,8186% |
| Tokocrypto IDR maker | 0,3322% | **0,7174%** | 0,8186% |
| Tokocrypto USDT taker | 0,4044% | **0,9735%** | 0,9735% |

Pada mode **taker** kartu kini **sama persis** dengan FSM. Pada mode **maker** masih berbeda
**0,1012 pp**, dan itu disengaja: pengguna memang memilih limit order untuk kaki jual, jadi fee
maker-lah yang akan dibayar. Menagih fee taker pada limit order sama salahnya dengan tidak menagih
fee beli sama sekali — yang ditutup J7 adalah yang kedua.

### Efek amnesti — bukan regresi

| Bruto | Kartu lama | Kartu sekarang |
|---|---|---|
| +0,50% | **+0,0656% "UNTUNG"** | **−0,3160% RUGI** |
| +0,80% | +0,3643% "UNTUNG" | **−0,0184% RUGI** |
| +1,00% | +0,5635% "UNTUNG" | +0,1799% UNTUNG |

Ambang pertama kali kartu boleh berkata "JUAL UNTUNG" naik dari **≈+0,4341%** menjadi **≈+0,8186%**
(Tokocrypto IDR taker). Ini kelas efek yang sama dengan J1: angka akan tampak "lebih buruk", dan itu
angka yang benar.

---

## 6. Angka terukur (dari XML, bukan klaim)

| Pohon | Kelas | Total | Lulus | Gagal | Dilewati |
|---|---|---|---|---|---|
| Baseline setelah batch 3 (§32, commit `e33b238`) | 67 | 505 | 504 | 0 | 1 |
| **Pohon J7 (commit ini)** | **68** | **522** | **521** | **0** | **1** |
| Selisih | **+1** | **+17** | **+17** | 0 | 0 |

Diukur dari `app/build/test-results/testDebugUnitTest/*.xml` setelah direktori hasil dihapus lebih
dulu supaya Gradle benar-benar menjalankan ulang, bukan menyajikan hasil basi.

Uji baru: `app/src/test/java/agu/analys/trading/SellEstimateTest.kt` — **17 kasus, semuanya lulus**:

1. pada kenaikan 0,50 persen kartu tidak lagi mengklaim untung
2. klaim untung baru muncul setelah harga melewati harga impas
3. beli lalu jual di harga sama rugi sebesar friksi round-trip
4. `netProfitPct` sama persis dengan `FeeSchedule` untuk seluruh rentang harga
5. persen dari rupiah sama dengan persen langsung
6. rupiah konsisten dengan komponen friksi
7. modal bersih selalu di atas nominal karena fee beli dan slippage
8. friksi Tokocrypto USDT memakai tarifnya sendiri
9. friksi Indodax memakai tarifnya sendiri
10. mode maker memakai fee jual maker tetapi fee beli tetap taker
11. slippage dapat dimatikan dan friksinya ikut turun
12. tanpa modal yang diketahui laba tidak dikarang
13. harga jual nol menghasilkan kas nol dan rugi penuh
14. masukan tidak valid tidak menghasilkan NaN atau melempar
15. label friksi menyebut fee beli dan slippage saat modal diketahui
16. label friksi jujur mengakui biaya beli belum diketahui
17. klaim untung tidak lagi memakai frasa "sudah bersih"

---

## 7. Kontrol negatif (wajib)

Perbaikan dimatikan dengan mengembalikan resolver ke rumus lama — hanya fee jual, tanpa fee beli,
tanpa slippage — **dan** memulihkan label lama (`"sudah bersih fee & pajak"`).

**Hasil: 13 dari 17 uji GAGAL** (`kelas=68 tests=522 gagal=13 dilewati=1`), tepat pada penjaga
masing-masing perbaikan:

| Uji yang gagal | Yang dijaganya |
|---|---|
| pada kenaikan 0,50 persen kartu tidak lagi mengklaim untung | klaim profit di bruto +0,50% |
| klaim untung baru muncul setelah harga melewati harga impas | ambang impas 0,8186% |
| beli lalu jual di harga sama rugi sebesar friksi round-trip | friksi −0,8119% |
| `netProfitPct` sama persis dengan `FeeSchedule` … | paritas satu rumus P&L |
| rupiah konsisten dengan komponen friksi | angka rupiah |
| modal bersih selalu di atas nominal … | fee beli masuk modal |
| friksi Tokocrypto USDT memakai tarifnya sendiri | tarif per bursa |
| friksi Indodax memakai tarifnya sendiri | tarif per bursa |
| mode maker memakai fee jual maker tetapi fee beli tetap taker | fee beli tidak ikut murah |
| slippage dapat dimatikan dan friksinya ikut turun | slippage benar-benar masuk |
| label friksi menyebut fee beli dan slippage saat modal diketahui | teks kartu |
| label friksi jujur mengakui biaya beli belum diketahui | teks kartu |
| klaim untung tidak lagi memakai frasa sudah bersih | teks kartu |

Empat uji yang **tetap lulus** memang tidak menguji basis, dan itu memang diharapkan:

- **persen dari rupiah sama dengan persen langsung** — invarian *internal*; dihitung dari angka yang
  sama, jadi bertahan pada rumus apa pun. Ia menjaga konsistensi kartu, bukan kebenaran basis.
- **tanpa modal yang diketahui laba tidak dikarang** — penjaga masukan.
- **harga jual nol menghasilkan kas nol dan rugi penuh** — penjaga masukan.
- **masukan tidak valid tidak menghasilkan NaN atau melempar** — penjaga masukan.

Setelah dipulihkan, seluruh 522 uji hijau kembali (diverifikasi ulang lewat XML).

---

## 8. Batas yang dicatat apa adanya

1. **Uji menutup resolver murni dan teks kartu, bukan komposisi Compose.** Tidak ada uji UI maupun
   instrumentasi di repo ini, jadi uji ini tidak membuktikan kartu benar-benar merender angka
   tersebut di layar — hanya membuktikan angka yang diserahkan ke kartu sudah benar.
2. **Mode maker sengaja berbeda 0,1012 pp dari FSM** (0,7174% vs 0,8186%). Itu keputusan, bukan sisa
   bug. Bila owner ingin kartu selalu identik dengan FSM, cukup teruskan `fees.sellTakerPct` sebagai
   `activeFeePct` di `RadarTransactionFeeSection` — satu baris.
3. **Fee beli selalu diasumsikan taker.** Bila posisi sebenarnya dibeli lewat limit order, modal
   sebenarnya lebih rendah daripada yang ditampilkan. Arah kesalahannya **pesimistis** — aman, dan
   itu arah yang benar untuk peringatan sebelum menjual.
4. **Slippage tetap dibebankan pada maker** karena mengikuti konvensi `FeeSchedule`. Secara teknis
   limit order tidak mengalami slippage; ini konsistensi satu-rumus, bukan model yang sempurna.
5. **Belum diuji di perangkat fisik** (tidak ada emulator di sandbox).
6. **Kaki beli tidak disentuh** — J7 hanya soal kartu jual. Sebagai petunjuk untuk agen berikutnya,
   `RadarBuySection.kt:104` dan `CustomBuyOrderDialog.kt:92` masih memakai
   `effectiveFeePct = if (isMakerOrder) 0.0 else activeFeePct`, yaitu **menolkan fee untuk limit
   order dan mengabaikan slippage sepenuhnya** pada estimasi biaya beli. Belum ada temuan audit yang
   menutup ini; tidak dikerjakan di batch ini karena di luar cakupan J7.
