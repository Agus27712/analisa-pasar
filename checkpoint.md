# Checkpoint: Audit & Perbaikan Jalur Perdagangan Real Tokocrypto & Metadata Discovery (`TokocryptoSymbolRepository.kt` & `TokocryptoTradeApi.kt`)

- **Tanggal / Waktu:** 2026-10-02
- **Status:** Selesai (Completed & Verified Build Clean)
- **Komponen Terdampak:**
  1. `TokocryptoSymbolRepository.kt`
  2. `TokocryptoTradeApi.kt`
  3. `RealTradeExecutor.kt`
- **Akar Masalah & Resolusi:**
  1. **Atasi Toast Error "Metadata trading Tokocrypto gagal dimuat"**:
     - Sebelumnya, `ensureSymbolsLoaded(force = true)` hanya mencoba 1 endpoint tunggal (`/open/v1/common/symbols`). Jika endpoint tersebut lambat, terblokir Cloudflare, atau mengembalikan struktur non-zero code, `fetchFromTokocrypto()` gagal dan order langsung dibatalkan dengan toast error.
     - **Fix**: Menambahkan *Fallback Chain Multi-Endpoint* resmi Tokocrypto pada `fetchFromTokocrypto()`:
       1) `https://www.tokocrypto.com/open/v1/common/symbols` (Open API Tokocrypto)
       2) `https://www.tokocrypto.site/api/v3/exchangeInfo` (Type 1 MBX Cloud Tokocrypto)
       3) `https://cloudme-toko.2meta.app/api/v1/exchangeInfo` (Type 3 NextMe Tokocrypto)
       *(Tanpa endpoint Binance sama sekali agar 100% kompatibel dan dapat diakses bebas tanpa terblokir di Indonesia)*
  2. **Inisialisasi Standard Default Metadata Pair Populer**:
     - Menambahkan fungsi `populateDefaultSymbols()` saat inisialisasi `TokocryptoSymbolRepository`.
     - Seluruh pair utama IDR & USDT (BTC, ETH, SOL, DOGE, XRP, SUI, ADA, BNB, SHIB, NEAR, AVAX, PEPE, TRX, LINK, RENDER, FET, FLOKI, BONK) memiliki metadata trading bawaan dengan precision, stepSize, minQty, dan minNotional yang valid.
     - Mengubah `ensureSymbolsLoaded()` agar tidak pernah membatalkan order jika `symbolsMap` sudah terisi dengan metadata bawaan/cache, melainkan memperbarui parameter live dari network di background.
  3. **Penyesuaian HMAC SHA-256 Signature Query Ordering (`TokocryptoTradeApi.kt`)**:
     - Mengurutkan `formParams` secara alfabetis berdasarkan kunci (`formParams.sortedBy { it.first }`) sebelum membentuk `queryString` dan menghitung HMAC SHA-256 signature pada `createOrder`. Hal ini mencegah penolakan signature invalid oleh server Tokocrypto.
  4. **Pembersihan Parameter Order & timeInForce**:
     - Menghilangkan pengiriman `clientId` kustom agar tidak memicu error `3703: Invalid client ID`. Tokocrypto akan menutupi ID secara internal dan mengembalikannya pada respons.
     - Memastikan `timeInForce` dikonversi ke format numeric code Tokocrypto (1=GTC, 2=IOC, 3=FOK, 4=GTX).
  5. **Debug Output Lengkap dari Server Tokocrypto saat Order Ditolak**:
     - Menambahkan field `httpCode`, `serverCode`, `serverBody`, dan `requestDebug` pada model `TokocryptoOrderResult`.
     - Logging otomatis ke `AppLogManager.trade("TokocryptoOrderRejected", ...)` yang mencatat secara mendetail: Simbol, Side, Type, Status HTTP, Kode Error Server, Pesan Error, Parameter Query Terkirim, dan Respons Raw JSON Server Tokocrypto.
     - Logging pada `RealTradeExecutor.kt` (`RealOrderRejected`, `RealSellRejected`, `TokocryptoCancelRejected`, `TokocryptoQueryFailed`) sehingga pengguna dapat membaca log diagnosa lengkap langsung dari dialog diagnostik logcat di aplikasi.
  6. **Verifikasi Kompilasi & Unit Tests**:
     - `compile_applet` berhasil tanpa error.
     - `gradle :app:testDebugUnitTest` berhasil (BUILD SUCCESSFUL, semua 32 actionable tasks sukses).
