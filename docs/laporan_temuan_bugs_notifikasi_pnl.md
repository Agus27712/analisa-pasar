# Laporan Temuan Audit — Angka P&L di Notifikasi Memakai Basis Bruto

**Audit:** 2026-10-31 · **Ditutup seluruhnya:** 2026-10-31 · **Branch:** `main`
**Letak berkas:** `docs/laporan_temuan_bugs_notifikasi_pnl.md`. Berkas ini **tidak lagi** berada di
`docs/bugs/`, karena seluruh temuannya sudah tertutup. Aturannya: `docs/bugs/` hanya memuat temuan
yang **masih hidup**; berkas yang sudah beres dipindahkan keluar dengan nama
`docs/laporan_temuan_bugs_<topik>.md`, dan berkas itu **menjadi laporannya**.
**Isi dokumen:** laporan penutupan. Butir temuan diringkas di §2 beserta bukti angka, dan status
akhirnya ada di §2 tabel + §5 Riwayat.
**Cakupan:** `util/AlertNotificationHelper.kt` · `viewmodel/TradingViewModelAlerts.kt` ·
`service/TradingForegroundService.kt` · berkas baru `util/SpotMonitorText.kt`
**Batas penting:** temuan ini menyangkut **angka yang dibaca pengguna**, bukan kesalahan eksekusi
order. Yang salah adalah label untung/rugi dan status "SIAP JUAL", bukan harga jual yang dikirim ke
bursa (`limitSellPrice`, `stopLimitPrice` tetap apa adanya dari state posisi).

| Status | Temuan | Jumlah |
|---|---|---|
| 🔴 Kritis | — | 0 |
| 🟠 Serius | — | 0 |
| 🟡 Sedang | H1, H2, H3 | 3 |
| 🔵 Rendah | H4 (teks notifikasi belum bisa diuji) | 1 |
| ✅ Sudah diperbaiki | H1–H4 | 4 |

> **Tidak ada pekerjaan tersisa di berkas ini.** Kalau butuh pekerjaan baru di area notifikasi,
> lakukan **audit baru** dan tulis temuannya ke berkas temuan baru di `docs/bugs/`
> (`temuan_bugs_<topik>.md`), jangan menghidupkan kembali nomor lama.

---

## 1. Peta jalur dan basis angka

Halaman detail (`SellEstimate`, `ui/components/dashboard/ActiveHoldingSection`) sudah lama memakai
**net**: `FeeSchedule.netProfitPctFor(entry, price, exchange, quoteAsset)` — fee beli taker + fee
jual taker + slippage dua sisi, bentuk multiplikatif. Jalur notifikasi sebelumnya menghitung
`(hargaKeluar − entry) / entry`, yaitu **bruto**, jadi untuk posisi yang sama angkanya lebih besar.

```
SUMBER ANGKA NET (satu-satunya rumus)
└─ config/AppConfiguration.kt  FeeSchedule.netProfitPct / netProfitPctFor

PERMUKAAN NOTIFIKASI (sebelum perbaikan)
├─ util/AlertNotificationHelper.sendTrailingHitNotification      → bruto  (H1)
├─ util/AlertNotificationHelper.sendTrailingPeakUpdateNotification → bruto (H2, parameter `profitPct` dari pemanggil)
├─ viewmodel/TradingViewModelAlerts.kt  log TrailingAdjust       → bruto  (H2)
└─ service/TradingForegroundService.updateNotification           → bruto  (H3, `HoldingItem.diffPct` / `isProfit`)
```

Bukti angka (Tokocrypto IDR, taker 0,2222% beli / 0,4322% jual, slippage 0,08% per sisi):

| Entry | Harga acuan | Bruto (lama) | Net (detail) | Selisih |
|---|---|---|---|---|
| Rp 1.000.000 | Rp 1.050.000 | +5,00% | +4,15% | 0,85 poin |
| Rp 1.000.000 | Rp 1.002.000 | +0,20% | **−0,61%** | tanda berubah |
| 1.000 | 1.040 | +4,00% | +3,16% | 0,84 poin |
| 1.000 | 1.005 | +0,50% | **−0,32%** | tanda berubah |
| 1.000 | 1.000 | 0,00% | −0,81% | impas net butuh +0,818593% |

Baris bertanda berubah adalah gejalanya: posisi yang **masih rugi** setelah fee tampil sebagai
untung. Keluhan owner — angka di notifikasi tidak sama dengan halaman detail — cocok dengan kolom
"Bruto (lama)" versus "Net (detail)".

## 2. Temuan dan perbaikan

### H1 — notifikasi "Trailing Stop Terpicu" memakai persen bruto

`sendTrailingHitNotification` menghitung `((limitSellPrice - entryPrice) / entryPrice) * 100.0` dan
menuliskannya sebagai `Estimasi Profit: +$profitStr%` dan `(+$profitStr%)` di teks ringkas.

**Perbaikan:** persen dihitung oleh `trailingHitNetProfitPct()` (pembungkus
`FeeSchedule.netProfitPctFor`), badan pesan dipindah ke fungsi murni
`buildTrailingHitMessage()`, dan label menulis `Estimasi Profit Net: …` + `(… net)`.
Nominal harga (eksekusi, puncak, terkini) tetap diformat `PriceFormatter` dan tidak berubah.

### H2 — notifikasi "Trailing Peak Naik" menerima persen bruto dari pemanggil

Pemanggil (`TradingViewModelAlerts.checkTrailingForMode`) menghitung
`((peakPrice - entryPrice) / entryPrice) * 100.0` lalu meneruskannya sebagai parameter
`profitPct`; helper hanya memformatnya. Log `TrailingAdjust` memakai angka bruto yang sama.

**Perbaikan:** parameter `profitPct` **dihapus** dari kedua overload
`sendTrailingPeakUpdateNotification` (pola sama dengan N3: kalau parameternya tidak ada, tidak
mungkin ada pemanggil baru yang mengirim bruto). Helper menghitung net sendiri; pemanggil hanya
mengirim harga. Log `TrailingAdjust` menulis `Profit Puncak Net: …`. Badan pesan dipindah ke
`buildTrailingPeakUpdateMessage()`.

### H3 — notifikasi persisten "Spot Monitor" memakai persen bruto dan ambang "SIAP JUAL" bruto

`TradingForegroundService.HoldingItem` punya `isProfit = currentPrice > entryPrice` dan
`diffPct = (currentPrice − entryPrice) / entryPrice * 100.0`. Akibatnya aset dengan net −0,32%
(entry 1.000 → 1.005) tampil `▲ +0.50% [SIAP JUAL]` di notifikasi, sementara dashboard
`ActiveHoldingSection` menulis `▼ −0.32%` untuk posisi yang sama. Judul ("N Aset Siap Profit"),
teks ringkas ("Siap Jual: …"), dan hitungan per portofolio ikut memakai ambang bruto itu.

**Perbaikan:** seluruh teks dipindah ke fungsi murni di `util/SpotMonitorText.kt`
(`SpotMonitorHolding`, `buildSpotMonitorHoldingCard/Title/CollapsedText/Body`). Data class memakai
`FeeSchedule.netProfitPctFor` dan `isProfit = netProfitPct >= 0.0`, ambang yang sama dengan
`ActiveHoldingSection` (`pnlPct >= 0.0`). Service hanya menyiapkan `SpotMonitorHolding`
(saldo diformat di service karena butuh metadata bursa) dan memanggil pembentuk teks itu. Urutan
daftar (untung net dulu) juga dipindah ke satu tempat agar tidak ada dua aturan sortir.

### H4 — teks notifikasi tidak bisa diuji tanpa Android

Sebelum perbaikan, seluruh isi pesan dirakit langsung di dalam fungsi yang menyentuh
`NotificationCompat`/`Context`, sehingga tidak ada uji unit yang bisa membuktikan basis angkanya.
Itu sebabnya H1–H3 bisa hidup lama.

**Perbaikan:** tiga pembentuk pesan murni (`buildTrailingHitMessage`,
`buildTrailingPeakUpdateMessage`, seluruh `SpotMonitorText.kt`) dapat diuji tanpa Robolectric,
mengikuti preseden `buildCandidateSignalMessage`. Uji baru: `NotificationNetPnlTest` (5 uji) dan
`SpotMonitorTextTest` (5 uji).

## 3. Yang sudah benar (jangan diutak-atik)

- `sendEmergencyExitNotification` dan `sendTakeProfitNotification` **sudah** memakai
  `FeeSchedule` — jangan diubah dan jangan "diseragamkan" dengan cara lain.
- `PriceFormatter.formatPrice` hanya memformat nominal; tidak memuat rumus P&L. Jangan menambah
  perhitungan fee di dalamnya.
- `SpotPositionStore.resolveEffectiveTrailingPercent` dan `SellTrailingSection.maxProfitPct`
  sengaja memakai pergerakan harga **bruto**: keduanya memilih tier trailing, bukan mengklaim
  profit. Jangan diganti ke net.
- `SellSignalEvaluator` dan `SellEstimateResolver` tidak disentuh; halaman detail sudah benar.
- Gerbang sakelar notifikasi tetap hanya di `canNotify`; mematikan notifikasi tidak mengubah
  eksekusi order.

## 4. Uji dan kontrol negatif

| Berkas uji | Yang dijaga |
|---|---|
| `util/NotificationNetPnlTest.kt` | pesan trailing hit/peak memuat persen **net** (bukan bruto), pesan negatif di bawah impas, dan larangan rumus bruto atau parameter `profitPct` kembali ke jalur notifikasi |
| `util/SpotMonitorTextTest.kt` | ambang "SIAP JUAL" = untung net, kartu/judul/teks ringkas memakai persen net, aset tanpa harga masuk tidak diklaim untung, badan memisahkan real & simulasi |

Kontrol negatif (dijalankan di CI bila perbaikan dimatikan):
kembalikan `((limitSellPrice - entryPrice) / entryPrice)` ke `buildTrailingHitMessage`, atau
`isProfit = currentPrice > entryPrice` ke `SpotMonitorHolding` → `NotificationNetPnlTest` dan
`SpotMonitorTextTest` **GAGAL**.

**Verifikasi:** uji dijalankan lewat GitHub Actions (`all-unit-tests`). Lingkungan kerja agen
(Alpine/Termux) tidak dapat mengunduh dependensi Gradle (plugin portal 403), jadi `./gradlew` tidak
dijalankan di sana — bukan alasan untuk melewatkan uji.

## 5. Riwayat

| Tanggal | Aksi |
|---|---|
| 2026-10-31 | Audit dimulai dari keluhan owner: angka notifikasi tidak sama dengan halaman detail. Akar: persen profit bruto di tiga permukaan notifikasi (H1–H3), bukan pembulatan harga |
| 2026-10-31 | H1 ditutup: `buildTrailingHitMessage` + `trailingHitNetProfitPct`, label "net" |
| 2026-10-31 | H2 ditutup: parameter `profitPct` dihapus dari `sendTrailingPeakUpdateNotification`, net dihitung di helper, log `TrailingAdjust` ikut net |
| 2026-10-31 | H3 ditutup: `util/SpotMonitorText.kt` baru, `TradingForegroundService` hanya menyiapkan data; ambang "SIAP JUAL" = untung net |
| 2026-10-31 | H4 ditutup: `NotificationNetPnlTest` + `SpotMonitorTextTest` (10 uji) menahan basis net dan melarang rumus bruto kembali |
| 2026-10-31 | Seluruh temuan tutup ⇒ berkas dipindah ke `docs/laporan_temuan_bugs_notifikasi_pnl.md` |
