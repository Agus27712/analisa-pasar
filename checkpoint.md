# Checkpoint: Perbaikan & Pembacaan Saldo Riil USDT Tokocrypto & Indodax (`TokocryptoTradeApi.kt`, `RealTradeCoordinator.kt`, `PortfolioScreen.kt`, `DetailChartScreen.kt`)

- **Tanggal / Waktu:** 2026-10-02
- **Status:** Selesai (Completed & Verified Build Clean)
- **Komponen Terdampak:**
  1. `TokocryptoTradeApi.kt`
  2. `RealTradeCoordinator.kt`
  3. `OrderViewModel.kt` & `TradingViewModel.kt` & `TradingViewModelOrders.kt`
  4. `PortfolioScreen.kt` & `RealPortfolioView.kt` & `RealPortfolioSummaryCard.kt`
  5. `DetailChartScreen.kt`
  6. `AppPreferences.kt`
- **Akar Masalah & Resolusi:**
  1. **Akar Masalah Saldo Riil USDT Tidak Terbaca**:
     - *Parsing JSON Response Token/Assets*: `extractAssets()` sebelumnya hanya mencari `data.accountAssets`. Jika server Tokocrypto merespons struktur array langsung, `balances`, atau `assets`, aset tidak terdeteksi dan parsing saldo spot gagal.
     - *Ketiadaan Sinkronisasi Waktu Server*: Selisih jam perangkat (device clock drift) memicu error `-1021: Timestamp for this request is outside of recvWindow`.
     - *Case-Sensitivity Kunci Aset*: Kunci saldo hanya tersimpan dalam huruf kecil (`usdt`), sementara beberapa komponen ViewModel dan UI melakukan lookup huruf kapital (`USDT`).
     - *Ketiadaan Fallback Targeted Aset*: Jika endpoint umum `/open/v1/account/spot` gagal atau mengembalikan daftar koin tanpa baris USDT, saldo USDT tidak diperbarui.
     - *UI Summary Card Menyembunyikan Kartu USDT*: `RealPortfolioSummaryCard.kt` sebelumnya hanya menampilkan bagian USDT jika `realUsdt > 0.00000001 || lockedUsdt > 0.0`. Pada Tokocrypto (yang berbasis USDT), pengguna melihat seolah saldo USDT tidak terbaca sama sekali jika saldo awal masih belum termuat atau bernilai 0.
     - *DetailChartScreen Tidak Memiliki Fallback Resolusi*: `availableQuote` untuk pair USDT hanya memeriksa `realBalance` StateFlow tanpa memeriksa `realFreeBalanceForQuote("USDT")`, `realBalanceForQuote("USDT")`, atau disk cache `prefs.getSavedRealBalance()`.
  2. **Implementasi Solusi & Peningkatan**:
     - **Sinkronisasi Jam Server Tokocrypto (`syncServerTime`)**: Mengambil waktu resmi dari `https://www.tokocrypto.com/open/v1/common/time` dan menghitung offset milidetik agar query `timestamp` selalu selaras dengan server exchange (menghindari error `-1021`).
     - **Ekstraksi Aset Multiformat (`extractAssets`)**: Mendukung `accountAssets`, `balances`, `assets`, `userAssets`, array root, dan Map object aset sintetis.
     - **Penyimpanan Dual-Casing (`parseBalances`)**: Menyimpan semua aset ke `freeMap`, `holdMap`, dan `totalMap` baik dalam huruf kecil (`usdt`, `idr`) maupun huruf besar (`USDT`, `IDR`).
     - **Targeted Fallback USDT (`/open/v1/account/spot/asset?asset=USDT`)**: Jika endpoint umum tidak memuat USDT, sistem otomatis mengeksekusi request targeted ke `/open/v1/account/spot/asset?asset=USDT` dengan query param terurut dan signature HMAC-SHA256 yang valid.
     - **Penyimpanan Cache Disk Dual-Casing (`AppPreferences.kt`)**: Memastikan `getSavedRealBalance()` mengembalikan key lowercase dan uppercase.
     - **Dukungan Force Refresh & Auto-Fetch (`RealTradeCoordinator.kt`)**: Menambahkan parameter `force: Boolean = false` agar pengguna bisa melakukan refresh instan tanpa terhalang cooldown, dan trigger auto-fetch saat inisialisasi jika API key tersedia.
     - **Penyelarasan Tampilan Portofolio & Detail (`PortfolioScreen.kt` & `DetailChartScreen.kt`)**:
       - `PortfolioScreen.kt` menyegarkan saldo saat PIN di-unlock atau mode Real dibuka, serta menampilkan label bursa yang dinamis ("Aset Riil Tokocrypto Terhubung").
       - `RealPortfolioSummaryCard.kt` selalu menampilkan kartu "SALDO USDT ($)" secara transparan dan jelas jika bursa aktif adalah Tokocrypto.
       - `DetailChartScreen.kt` menyegarkan saldo real di background saat membuka koin di Mode Real dan melakukan resolusi bertingkat (free balance -> total balance -> in-memory map -> disk cache) untuk mencegah nominal 0 palsu.
  3. **Verifikasi Kompilasi**:
     - `compile_applet` berhasil (BUILD SUCCESSFUL).
