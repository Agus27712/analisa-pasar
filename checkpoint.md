# Checkpoint: Audit & Perbaikan Penanganan Mata Uang & Prefix Kuotasi di Halaman Detail Koin (`ui/components/detail/`)

- **Tanggal / Waktu:** 2026-10-01
- **Status:** Selesai (Completed & Verified Build Clean)
- **Komponen Terdampak:**
  1. `TechnicalDetailsCard.kt` & `DetailTechnicalDetailsSection.kt`
  2. `GlobalMarketShieldCard.kt`
  3. `SpreadGuardAndEntrySection.kt`
  4. `CustomBuyOrderDialog.kt`
  5. `RadarTransactionFeeSection.kt`
  6. `RadarBuySection.kt`
  7. `SellPositionHeader.kt`
  8. `SellManualBuyDialog.kt`
  9. `WaitingEntryRadarCard.kt`
  10. `CreateAlertTabContent.kt`, `ActiveAlertsTabContent.kt`, & `PriceAlertDialog.kt`
  11. `AiAssistantCard.kt` & `ProgressEntryCard.kt`
- **Perbaikan yang Dilakukan:**
  1. **Volume 24H Dinamis (`TechnicalDetailsCard.kt`)**:
     - Menambahkan parameter `quoteAsset: String = "IDR"` pada `TechnicalDetailsCard` dan meneruskannya dari `DetailTechnicalDetailsSection`.
     - Mengubah formatting volume dari `formatPrice` statis menjadi `PriceFormatter.formatVolume(volume24h, quoteAsset = quoteAsset)` dan label menjadi `"volume 24 jam ($quoteAsset)"`.
  2. **OrderBook Shield Dinding Beli/Jual (`GlobalMarketShieldCard.kt`)**:
     - Menambahkan parameter `quoteAsset` pada `GlobalMarketShieldDialog`.
     - Format harga dinding beli dan jual terbesar kini menggunakan `PriceFormatter.formatPrice(maxBid.price, quoteAsset = quoteAsset)`.
     - Netralisasi teks `"server Indodax"` menjadi `"server pasar"`.
  3. **Spread Guard Placeholder (`SpreadGuardAndEntrySection.kt`)**:
     - Memperbaiki fallback tampilan rekomendasi harga entri saat nilai 0 agar menampilkan `"$ —"` jika pair USDT dan `"Rp —"` jika pair IDR.
  4. **Dialog Beli Kustom (`CustomBuyOrderDialog.kt`)**:
     - Menghapus label statis `"Real Indodax TAPI v2"` menjadi `"Mode Real Trading Spot"`.
     - Mengizinkan input angka desimal (titik/koma) pada modal pembelian pair USDT (`input.filter { it.isDigit() || (isUsdtQuote && (it == '.' || it == ',')) }`).
     - Menggunakan `PriceFormatter.formatPrice(p, showSymbol = false, quoteAsset = quoteAsset)` pada `formatPriceForInput`.
  5. **Dialog Rincian Fee Transaksi (`RadarTransactionFeeSection.kt`)**:
     - Meneruskan parameter `quoteAsset = quoteAsset` ke `RadarFeeDetailDialog`.
     - Mengganti pembatasan nominal minimal order yang sebelumnya hardcoded 10.000 menjadi dinamis ($1 USDT untuk pair USDT dan Rp 10.000 untuk pair IDR).
  6. **Seksi Pembelian Radar (`RadarBuySection.kt`)**:
     - Format input default TP1 dan TP2 menggunakan `PriceFormatter.formatPrice(..., showSymbol = false, quoteAsset = quoteAsset)` agar presisi desimal koin USDT atau koin kecil tidak terpotong (sebelumnya `%.0f`).
     - Batas tombol `2% Risk` disesuaikan dengan `minNominalQuote` bukan hardcoded `>= 10000.0`.
     - Tombol nominal persentase (25%, 50%, 75%, 100%) dan custom nominal mengizinkan nilai desimal pada pair USDT.
  7. **Header dan Dialog Jual Manual (`SellPositionHeader.kt` & `SellManualBuyDialog.kt`)**:
     - Menghapus teks statis `"Real Indodax"` dan `"nota Indodax Anda"`.
     - Format harga rata-rata beli menggunakan `PriceFormatter.formatPrice(..., quoteAsset = quoteAsset)`.
  8. **Inisialisasi TP1/TP2 di `WaitingEntryRadarCard.kt`**:
     - Memformat nilai awal TP1 dan TP2 berdasarkan presisi `quoteAsset` koin (menghindari pembulatan integer nol desimal).
  9. **Alert & Notifikasi Pasar (`CreateAlertTabContent.kt`, `ActiveAlertsTabContent.kt`, `PriceAlertDialog.kt`)**:
     - Mengganti `formatIdrNumber` dengan `PriceFormatter.formatPrice(..., quoteAsset = quoteAsset)`.
  10. **Kompilasi & Verifikasi**:
      - `compile_applet` berhasil tanpa error.
