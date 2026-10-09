# AGENTS.md — analisa-pasar

Aplikasi Android Kotlin 100% (Jetpack Compose M3) untuk analisis & trading kripto di **Tokocrypto** dan **Indodax**. Modul tunggal `:app`, package `agu.analys`, arsitektur MVVM + Coordinator (`TradingViewModel` + `MarketData/Position/Simulation/RealTradeCoordinator`). Kerja di branch `main`/`master`; abaikan catatan branch lain.

## Perintah build & tes

- `chmod +x gradlew` dulu bila wrapper belum executable (CI selalu melakukan ini).
- Tes unit: `./gradlew :app:testDebugUnitTest` (butuh JDK 17).
- Satu scope saja: `./gradlew :app:testDebugUnitTest --tests "agu.analys.engine.scalping.*"`.
- Pola scope yang ada: `agu.analys.engine.scalping.*`, `agu.analys.engine.regime.*`, `agu.analys.engine.indicators.*`, `agu.analys.engine.*`.
- Build: `./gradlew :app:assembleDebug`, rilis: `./gradlew :app:assembleRelease`.
- `compile_applet` yang disebut di `workplan.md`/`checkpoint.md` bukan perintah repo — pakai perintah `./gradlew` di atas.

## Secrets & environment

- Jangan commit `.env`, `*.keystore`, `*.jks` (sudah di `.gitignore`).
- `app/build.gradle.kts` memuat secret dengan urutan: file `.env` → `.env.example` → env var → properti Gradle. Tanpa `.env`, build memakai `.env.example` dan `BuildConfig.GEMINI_API_KEY`/`GROQ_API_KEY` jadi string kosong — itu normal untuk build offline.
- API key/secret bursa disimpan terenkripsi di aplikasi via menu Pengaturan, bukan via `.env`.
- Bila `EncryptedSharedPreferences` gagal (KeyStore corrupt), `AppPreferences` wajib hapus entri `MasterKey` corrupt dari AndroidKeyStore sebelum retry; fallback plaintext hanya jalan terakhir dan harus dilog sebagai error (kredensial jadi tak terenkripsi).

## Batasan pasar (jangan dilanggar)

- **Tokocrypto: hanya USDT + IDR. Indodax: hanya IDR.** Jangan tambah handling USDT untuk Indodax.
- Quote BIDR sudah dieliminasi total — jangan hidupkan lagi (kecuali koin yang memang bernama `...BIDR` seperti `BNBIDR`/`SHIBIDR`).
- **Dilarang keras** request ke `binance.com` / `stream.binance.com` (REST maupun WebSocket), domain itu terblokir di Indonesia.

## Isolasi exchange & kuotasi (sumber bug terbanyak)

- Semua cache in-memory, SharedPreferences, dan Room wajib terpartisi per exchange: kunci `${exchange}_...`, kolom Room `exchange` (DB v8). Query DAO wajib filter `exchange`. Jangan tambah fallback cache tanpa namespace.
- `OrderBookDepthCache.updateOrderBook()` punya default `exchange = "TOKOCRYPTO"` yang menipu — selalu teruskan exchange eksplisit (`if (isToko) "TOKOCRYPTO" else "INDODAX"`). Pernah jadi bug: depth Indodax tertulis ke key Tokocrypto (fix 2026-10-09).
- Filter aset kuotasi wajib case-insensitive + normalisasi lowercase karena saldo Tokocrypto dual-casing (`usdt`+`USDT`, `idr`+`IDR`): bandingkan `key.lowercase()` dengan himpunan `{"idr","idrt","bidr","usdt","usdc","busd","usd"}`, jangan `key != "idr"`.
- Ganti bursa hanya lewat Settings (`setMarketDataSource(..., forceHardStop = true)`): putus WebSocket, matikan polling, kosongkan StateFlow, purge `OrderBookDepthCache`/`MtfCacheManager`/`MarketDataCache`, baru konek ke bursa baru.
- USDT tampil `$` + ekuivalen `≈ Rp ...` (kurs live `ExchangeRateManager`), IDR tampil `Rp`. Jangan panggil `PriceFormatter.formatPrice()` tanpa `quoteAsset` (gunakan `PriceFormatter.extractQuote(symbol)`); cek `quoteForCoin(base)` agar `BTCUSDT` tidak terbaca sebagai holding `BTCIDR`.
- Fee + slippage selalu lewat `FeeCalculator` / `TradingFeeConfig`.
- `IndodaxTradeApiV2.decimal()`: metadata pair di-cache in-memory 30 menit via `loadPairsMetaSafe()` — jangan instansiasi `MarketDataCache(context)` per order (IO berulang + crash bila context belum init).
- `MarketDataCache.synthesizeLiveCandle()` wajib pakai `CandleTimeUtil.timeframeDurationMs()` (jangan hardcode `when`); H4/D1 pernah jatuh ke 60 detik (fix 2026-10-09).

## Kekhasan API Tokocrypto (wajib ditaati)

- Format simbol order: `BTC_USDT` (underscore), bukan `BTCUSDT`.
- Param signed API **di-sort alfabetis** sebelum hitung signature HMAC-SHA256, atau order ditolak.
- Selalu `syncServerTime()` (`GET /open/v1/common/time`) sebelum signed request agar bebas error `-1021`.
- Saldo: parsing harus tahan multiformat (`accountAssets`/`balances`/`assets`/array root), simpan dual-casing (`usdt` + `USDT`), dan fallback targeted `/open/v1/account/spot/asset?asset=USDT`.
- Histori (`myTrades`) & fallback open-order Tokocrypto wajib query **USDT + IDR** (`${base}_USDT` dan `${base}_IDR`); avg-buy dihitung **per kuotasi** (jangan campur harga USDT dengan IDR). Kandidat base dinormalisasi lowercase.
- Order LIMIT wajib bawa `price` eksplisit — `createOrder` menolak LIMIT tanpa price (validasi pakai reference lalu kirim tanpa price pasti ditolak exchange).

## Tes replay offline

- CSV candle ada di `data/tokocrypto` (dan `data/tokocrypto_v2`); format `<PAIR>_1m.csv` (`open_time_ms,open,high,low,close,volume`, hanya candle CLOSED).
- Unduh ulang hanya dari jaringan Indonesia: `python tools/fetch_tokocrypto_klines.py` (runner luar negeri kena HTTP 451 dari Tokocrypto).
- Replay Tokocrypto: `REAL_DATA_DIR=data/tokocrypto ./gradlew :app:testDebugUnitTest --tests "agu.analys.engine.scalping.replay.RealTokocryptoReplayTest"`.

## CI & rilis
- CI unit-test hanya manual (`Actions → Unit Tests (Manual) → Run workflow`); jangan tambah trigger push/PR otomatis.
- Rilis otomatis saat push `main`/`master`/`v*` dan butuh secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Versi dibaca dari `app/build.gradle.kts` (`versionName`/`VERSION_CODE`, kini 3.5.9/85).

## Aturan pencarian web

- Jangan lakukan pencarian web atas inisiatif sendiri — hanya bila pengguna memintanya secara eksplisit (biasanya untuk verifikasi API/endpoint bursa: WebSocket, order, dsb.).
