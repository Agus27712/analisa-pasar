# Laporan Integrasi Agent Bridge V2 (2026-11-03)

## Ringkasan Eksekutif

Pada tanggal 2026-11-03, sistem **Agent Bridge V2** berhasil diintegrasikan ke dalam aplikasi `analisa-pasar`. Jembatan ini menyediakan saluran komunikasi lokal (zero cloud latency) antara aplikasi Android dan agen cerdas eksternal (seperti runner **`oh-my-pi` / OMP** di Termux/CLI Android) melalui dua jalur utama:
1. **Push Realtime:** WebSocket (`/ws/signals`) dan Server-Sent Events (`/events`) pada server lokal Ktor Netty port `8765`.
2. **Berkas & Polling Fallback:** Penyimpanan berkas riwayat (`signals.jsonl`, `latest.json`, `portfolio.json`) di direktori dokumen lokal serta endpoint HTTP polling (`/health`, `/signals/latest`, `/signals/history`, `/portfolio`).

Integrasi ini sepenuhnya tunduk pada batasan sistem `analisa-pasar`:
- Bersifat pendukung keputusan (*decision support*) dan pemantauan sinyal, bukan eksekusi order riil otomatis liar.
- Mematuhi aturan isolasi bursa dan fee truth (tarif biaya Tokocrypto USDT vs IDR & Indodax IDR disertakan dalam payload sinyal).
- Menjaga aturan penjagaan sumber `TradingForegroundService` (N15 & N16) tanpa membebani thread pemantauan tick.

---

## Berkas yang Ditambahkan & Dimodifikasi

### 1. Komponen Baru
- **`app/src/main/java/agu/analys/bridge/AgentOutputModels.kt`**: Model data serialisasi `@Serializable` (`AgentSignalOutput`, `TechnicalBreakdown`, `FeeTruthInfo`, `AgentPortfolioOutput`, `AgentAsset`, dll.) dan bus event reaktif `AgentEventBus` berbasis `MutableSharedFlow`.
- **`app/src/main/java/agu/analys/bridge/AgentOutputBridge.kt`**: Singleton pengelola keluaran data. Bertanggung jawab atas pengecekan toggle preferensi pengguna, rotasi berkas riwayat 1.000 baris, penulisan ke dokumen publik (dengan fallback aman jika terbentur batasan Scoped Storage), serta pemancaran event realtime.
- **`app/src/main/java/agu/analys/bridge/AgentHttpServer.kt`**: Server Ktor Netty lokal port `8765` host `0.0.0.0` yang melayani polling HTTP, WebSocket push stream, dan Server-Sent Events (SSE).
- **`tools/oh-my-pi-extension/index.ts`**: Ekstensi TypeScript untuk runner agen `oh-my-pi` di Termux yang menyediakan tool pemantauan sinyal, riwayat, portofolio, dan pencatat log riset Markdown.
- **`tools/oh-my-pi-extension/test-v2.sh`**: Skrip pengujian koneksi dan streaming sinyal di lingkungan Termux.
- **`tools/oh-my-pi-extension/APPLY_BRIDGE_V2.sh`**: Skrip instalasi cepat ekstensi ke `~/.config/oh-my-pi/extensions/analisa-pasar`.
- **`app/src/test/java/agu/analys/bridge/AgentBridgeTest.kt`**: Pengujian unit untuk serialisasi JSON, skema data, dan reaktivitas aliran data `AgentEventBus`.

### 2. Modifikasi Komponen Eksisting
- **`gradle/libs.versions.toml`**: Menambahkan pustaka Ktor 2.3.9 (`ktor-server-core`, `ktor-server-netty`, `ktor-server-websockets`, `ktor-server-content-negotiation`, `ktor-serialization-kotlinx-json`) dan plugin `kotlin-serialization`.
- **`build.gradle.kts` & `app/build.gradle.kts`**: Mengaktifkan plugin `kotlin.serialization` dan dependensi Ktor Server serta pengecualian resource packaging Netty.
- **`app/src/main/java/agu/analys/util/AppPreferences.kt`**: Menambahkan preferensi `isAgentOutputEnabled` (kunci `agent_output_enabled`).
- **`app/src/main/java/agu/analys/service/TradingForegroundService.kt`**: Menautkan siklus hidup `AgentHttpServer` (memulai saat service aktif jika diizinkan preferensi, dan mematikan saat service dihancurkan).
- **`app/src/main/java/agu/analys/viewmodel/TradingViewModelSync.kt`**: Menautkan emisi sinyal saat transisi sinyal kandidat terdeteksi (`onCandidateSignalTransition`) dan saat sinyal dicatat ke riwayat.
- **`app/src/main/java/agu/analys/ui/screens/SettingsScreen.kt`**: Menambahkan switch toggle "Agent Bridge V2 (Termux / OMP)" di kelompok pengaturan Sistem & Pemeliharaan.

---

## Verifikasi & Hasil Pengujian

1. **Serialisasi & Alur Data:**
   - Serialisasi `AgentSignalOutput` dan `AgentPortfolioOutput` terbukti menghasilkan JSON yang valid dan dapat di-decode kembali tanpa eror.
   - `AgentEventBus` terbukti memancarkan dan menerima sinyal via Coroutine Flow.
2. **Kepatuhan Terhadap Penjaga Sumber:**
   - Pengujian `ForegroundMonitorSourceTest` (N15 & N16) tetap terpenuhi: tidak ada konstruksi `AppPreferences` tambahan di dalam `TradingForegroundService` dan tidak ada keterikatan start/stop service dengan sakelar notifikasi biasa.
3. **Penyimpanan Berkas:**
   - Menulis ke `Documents/analisa-pasar/output` dengan penanganan galat dan fallback otomatis, sehingga tidak menyebabkan crash aplikasi di Android 10+.
