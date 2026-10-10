# AGENTS.md — analisa-pasar

Aplikasi Android Kotlin 100% (Jetpack Compose M3) untuk analisis & **scalp mode** kripto di **Tokocrypto** dan **Indodax**. Modul tunggal `:app`, package `agu.analys`, MVVM + Coordinator (`TradingViewModel` + MarketData/Position/Simulation/RealTradeCoordinator). Kerja di branch `main`/`master`.

## Dokumen yang harus dibaca dulu

1. **`workplan2.md`** — workplan **aktif** (arah produk, fase P0–P3, slider, alur paper/replay)
2. **`APP_CONTEXT.md`** — arsitektur; utamanya § arah produk / scalp
3. **`SCALP_EXIT.md`** — exit paper & R net
4. **`data/config/scalp_config.json`** — mirror threshold + fee untuk `tools/scalp_replay.py`

**DEPRECATED (jangan diikuti):** `workplan.md`, `checkpoint.md`, `checkpoint_progres.md`.

## Arah produk (ringkas)

- Fokus: **scalp mode** + **paper trade** untuk menguji engine.
- Real money untuk strategi scalp: **belum**, sampai paper + fee truth stabil.
- Slider sensitifitas = ubah threshold (frekuensi sinyal), **bukan** klaim edge.
- Fee: **all-in** Tokocrypto/Indodax (Fee Truth) — jangan pakai model upstream p0 ~0,72% RT sebagai default tanpa label.
- Engine scalping di-port dari logika [dikigambol/kriptoyoi](https://github.com/dikigambol/kriptoyoi) `p0_engine` / spec — decision support, bukan bot profit terjamin.

## Perintah build & tes

- `chmod +x gradlew` bila perlu.
- Unit test: `./gradlew :app:testDebugUnitTest` (JDK 17).
- Scope scalping: `./gradlew :app:testDebugUnitTest --tests "agu.analys.engine.scalping.*"`.
- Build: `./gradlew :app:assembleDebug` / `assembleRelease`.
- String `compile_applet` di dokumen lama **bukan** perintah repo — pakai `./gradlew` di atas.

## Secrets & environment

- Jangan commit `.env`, `*.keystore`, `*.jks`.
- `app/build.gradle.kts`: `.env` → `.env.example` → env → Gradle props. Tanpa `.env`, key AI boleh kosong.
- API key bursa: terenkripsi di Pengaturan app, bukan `.env`.
- Jika `EncryptedSharedPreferences` gagal: hapus MasterKey corrupt di AndroidKeyStore sebelum retry; fallback plaintext hanya last resort + log error.

## Batasan pasar (jangan dilanggar)

- **Tokocrypto: hanya USDT + IDR. Indodax: hanya IDR.** Jangan USDT di Indodax.
- Quote BIDR dieliminasi (kecuali koin bernama `…BIDR` seperti `BNBIDR`/`SHIBIDR`).
- **Dilarang** request ke `binance.com` / `stream.binance.com` (REST/WS) — diblok di Indonesia.
- Data historis riset boleh dari arsip publik (mis. data.binance.vision) **hanya** di tools offline, bukan di app runtime.

## Isolasi exchange & kuotasi

- Cache, SharedPreferences, Room: partisi per exchange (`${exchange}_…`, kolom `exchange`). Query DAO filter `exchange`.
- `OrderBookDepthCache.updateOrderBook()` default exchange menipu — **selalu** teruskan exchange eksplisit.
- Format harga/PnL mengikuti `quoteAsset` (Rp vs $).

## Engine scalping — aturan agen

- Threshold keputusan: pusat di `ScalpingConfig` (default) → target **`ScalpingRuntimeConfig`** (slider/JSON). Jangan hardcode angka gate di banyak file.
- Mirror offline: `data/config/scalp_config.json` + `tools/scalp_replay.py`.
- Ubah parameter untuk eksperimen: export JSON → replay; jangan mengarang “edge” dari satu window data.
- Historical Edge palsu dilarang (`HistoricalEdgeStub` / pesan insufficient).
- Exit scalp paper: lihat `SCALP_EXIT.md`; real auto-execute dari sinyal scalp **bukan** prioritas sampai gate edge terpenuhi.
- Commit non-rilis (docs/fee/config): sertakan **`[skip ci]`** jika owner meminta agar rilis tidak terpicu.

## UI & Compose

- Material 3; jangan pecah state chart tanpa alasan (lihat pola `DetailUiState` bila ada di branch).
- Throttle UI ticker/depth bila diminta di catatan performa Tokocrypto (hindari spam recomposition).

## Git

- Branch kerja utama: `main` / `master`.
- Jangan commit artefak data candle besar tanpa diminta.
- Pesan commit jelas; `[skip ci]` untuk docs/config-only sesuai kebijakan owner.

## Yang tidak boleh dilakukan agen

- Menghidupkan Binance di path app production.
- Longgarkan semua gate “supaya banyak sinyal” tanpa paper/replay log.
- Menulis di UI bahwa sinyal punya winrate/expectancy historis tanpa data cukup.
- Mengikuti checklist `checkpoint*.md` / `workplan.md` lama sebagai urutan kerja.
- Port auto-trading real dari state machine exit tanpa instruksi eksplisit owner + fee truth.
