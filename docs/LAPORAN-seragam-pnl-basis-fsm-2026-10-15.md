# LAPORAN — Seragamkan hitungan P&L ke basis FSM

**Tanggal:** 2026-10-15
**Branch:** `patch`
**Berkas terkait:** `workplan2.md` §24–§25, `AGENTS.md`

---

## 1. Masalah

Owner menemukan angka yang **tidak cocok** antara FSM paper trade, radar jual, dan koin yang
dimiliki di halaman portofolio — untuk posisi yang sama. Dugaan owner: *"kenapa beda karena
mungkin ukurannya fee truth + slippage ya jadi nilainya kecil?"*

Dugaan itu **benar**, tapi penyebabnya dua komponen, bukan satu. Setelah ditelusuri, ada **empat**
rumus berbeda untuk hal yang sama — bukan tiga:

| Tempat | Rumus lama | Friksi |
|---|---|---|
| `PortfolioScreen.kt:125`, `RealPortfolioView.kt:122`, `ActiveHoldingSection.kt:167` | `(harga − avgBeli) / avgBeli` | **0%** — bruto, tanpa fee apa pun |
| `SellSignalEvaluator.kt:42-48` | `(qty·harga·(1−sellMaker) − qty·entry) / (qty·entry)` | 0,3322% |
| `PositionContext.kt:113-130` | `cost = investedAmount` (= `qty×entry`, tanpa fee beli), `net = gross×(1−sellMaker)` | 0,3322% |
| `PaperExitResolver.kt:80-91` (FSM) | `(harga/entry) × (1−sellTaker−slip) / (1+buyTaker+slip) − 1` | **0,8119%** |

Untuk Tokocrypto IDR: `buyMaker 0,1222%` · `buyTaker 0,2222%` · `sellMaker 0,3322%` ·
`sellTaker 0,4322%` · slippage `0,08%`/sisi.

Jadi FSM memang paling kecil, dan sebabnya **dua** hal: slippage 2 sisi (0,16 pp) **dan**
taker-vs-maker (0,20 pp).

## 2. Keputusan owner

**Seragamkan ke basis FSM** — taker + slippage dua sisi, bentuk multiplikatif. Basis ini dipilih
karena paling jujur: auto-buy dan auto-exit adalah order **market**, jadi taker + slippage
memang benar-benar dibayar.

Yang justru cacat bukan FSM, melainkan halaman portofolio yang **tidak mengurangi biaya sama
sekali** — di situ gap-nya paling besar, 0,81 poin persen.

## 3. Perubahan

### 3.1 Satu titik kebenaran

Ditambahkan di `object FeeSchedule` (`config/AppConfiguration.kt`) — lapisan `config`, supaya
lapisan UI tidak perlu mengimpor internal `engine`:

| Fungsi | Guna |
|---|---|
| `netProfitPct(entry, price, buyFeePct, sellFeePct, slippagePct)` | rumus inti, bentuk multiplikatif |
| `netProfitPctFor(entry, price, exchange, quoteAsset, useMaker = false, slippagePct)` | tarif diselesaikan dari bursa + kuotasi, default **taker** |
| `costBasisNet(quantity, entry, buyFeePct, slippagePct)` | biaya perolehan termasuk fee beli + slippage |
| `netSellValue(quantity, price, sellFeePct, slippagePct)` | nilai jual bersih setelah fee jual + slippage |
| `breakevenPrice(entry, buyFeePct, sellFeePct, slippagePct)` | harga impas net |

Invarian anti-drift: `(netSellValue / costBasisNet − 1) × 100` **sama persis** dengan
`netProfitPct`. Dijaga oleh uji, sehingga tidak mungkin lagi ada selisih antara angka persen dan
angka rupiah.

### 3.2 Pemanggil yang diubah

| Berkas | Perubahan |
|---|---|
| `engine/scalping/PaperExitResolver.kt` | `netProfitPct` & `breakevenPrice` **mendelegasi** ke `FeeSchedule` (signature dipertahankan) |
| `model/PositionContext.kt` | pakai `costBasisNet`/`netSellValue`; `buyMakerPct`/`sellMakerPct` → `buyTakerPct`/`sellTakerPct`; harga masuk diturunkan dari `investedAmount/qty` bila `entryPrice` kosong |
| `engine/sell/SellSignalEvaluator.kt` | pakai `costBasisNet`/`netSellValue`; parameter baru `slippagePct` (default `DEFAULT_SLIPPAGE_PCT`) di **kedua** overload |
| `ui/screens/PortfolioScreen.kt` | bruto → net; `pnlIdr = netValue − costBasis` |
| `ui/screens/portfolio/RealPortfolioView.kt` | bruto → net |
| `ui/components/dashboard/ActiveHoldingSection.kt` | bruto → net lewat `netProfitPctFor` |
| `ui/components/detail/sell/SellPositionOverviewCard.kt` | label "termasuk fee beli + slippage" + "fee beli + jual taker + slippage 2 sisi" |

**Sengaja TIDAK diubah** — basisnya berbeda, ini laba **terealisasi** (sudah dikunci), bukan
floating: `SimulationTradeStore.kt:775`, `SimulationOrderEngine.kt:175`.

`TradingFeeConfig` tetap dipakai; yang berubah hanya **komponen mana** yang dibaca
(`buyTakerPct`/`sellTakerPct`, bukan `buyMakerPct`/`sellMakerPct`).

## 4. Efek samping perilaku — disengaja, wajib diketahui

Friksi yang dipotong radar jual naik dari 0,3322% → 0,8119% (kerugian di harga sama), setara
kenaikan harga impas 0,3333% → 0,8186%. Ambang di `SellSignalEvaluator` jadi lebih ketat:

| Ambang | Butuh kenaikan harga (sebelum) | Sesudah |
|---|---|---|
| `netProfitPct > 0` (TP1/TP2/RSI, baris 110/119/143) | ≈0,33% | **≈0,82%** |
| `>= 1.0` (Dekat High 24j, baris 152) | ≈1,34% | **≈1,83%** |
| `>= 2.0` (`ReadySellBadgeEvaluator.kt:61`) | ≈2,34% | **≈2,84%** |
| `>= 3.5` (baris 169) | ≈3,85% | **≈4,35%** |
| `>= 5.0` (baris 161) | ≈5,35% | **≈5,86%** |

Konsekuensi konkret: pada kenaikan bruto +1,5%, radar jual dulu melaporkan **+1,16%** dan sekarang
**+0,68%** — selisih 0,49 poin persen.

**Catatan penting:** karena cabang TP1/TP2 mensyaratkan `netProfitPct > 0`, sebuah TP1 yang hanya
~0,5% di atas harga masuk kini **tidak lagi** memicu `READY_TO_SELL` (dulu bisa). TP harus berada
minimal ≈0,82% di atas entry untuk dianggap menguntungkan secara net. Ini bukan bug, melainkan
konsekuensi jujur dari biaya riil.

Ikut berubah karena membaca nilai yang sama: `SellCheckpointEvaluator` (checkpoint 3,
`PositionContext.kt:220-287`), `ProactiveProfitSummaryCard.kt:107-114` (memilih koin lebih
sedikit), `SpotPositionCard.kt:89`, `AlertNotificationHelper.kt:242`.

## 5. Uji

`app/src/test/java/agu/analys/engine/NetPnlParityTest.kt` — **baru**, 8 kasus:

1. Beli lalu jual di harga sama → rugi **−0,8119%** (toleransi 1e-4)
2. Harga impas → `netProfitPct` tepat **0**, dan kenaikan yang dibutuhkan **0,8186%**
3. Paritas `FeeSchedule.netProfitPct` ↔ `PaperExitResolver.netProfitPct` pada 8 tingkat harga
4. Harga turun → negatif; harga naik → positif
5. **Invarian anti-drift**: persen dari rupiah = persen langsung (toleransi 1e-9)
6. `costBasisNet` = nominal + 0,3022% (fee taker + slippage)
7. Indodax memakai tarifnya sendiri (−0,7877%, berbeda dari Tokocrypto)
8. Harga tidak valid → 0 tanpa melempar

`SellSignalEvaluatorTest` diperbarui: uji di kelas itu menguji **ambang keputusan**, bukan besaran
biaya, jadi seluruh komponen biaya dinolkan lewat konfigurasi `tanpaBiaya` **dan**
`slippagePct = 0.0`. Besaran biayanya sendiri diuji di `NetPnlParityTest`.

### 5.1 Kontrol negatif

Basis dipaksa **maker** (`useMaker = true`) di `netProfitPctFor` → **4 dari 8** uji gagal:

```
8 tests completed, 4 failed
```

Yang gagal: `beli lalu jual di harga sama...`, `harga impas...`, `bursa lain memakai tarifnya
sendiri`, `rupiah dan persen memakai basis biaya yang sama`.

Empat yang tetap lulus memang **tidak bergantung** pada basis taker: uji paritas delegasi
(kedua sisi memakai fungsi yang sama), uji tanda positif/negatif, uji penanganan harga tidak
valid, dan uji `costBasisNet` yang memanggil `buyRate` secara eksplisit. Ini sekaligus
membuktikan kontrolnya menunjuk ke tempat yang benar.

Dipulihkan → suite hijau.

## 6. Angka (diukur dari XML)

| | |
|---|---|
| Sebelum §24 | 435 lulus · 0 gagal · 1 dilewati (57 kelas) |
| Sesudah §24 | **443 lulus · 0 gagal · 1 dilewati (58 kelas)** |
| Tambahan | **+8 test, +1 kelas** |
| `assembleDebug` | sukses · APK **27.877.730 byte** |

## 7. Verifikasi manual yang masih diperlukan

1. Buka koin yang sama di halaman **Portofolio**, **detail (kartu posisi)**, dan **FSM** — angka
   persen harus sekarang **sama** (dulu berbeda sampai 0,81 poin persen).
2. Angka portofolio akan **turun** dibanding sebelumnya — itu benar, bukan regresi.
3. Perhatikan badge "💰 PROFIT" di dasbor: sekarang butuh kenaikan ≈2,84% (dulu ≈2,34%).
