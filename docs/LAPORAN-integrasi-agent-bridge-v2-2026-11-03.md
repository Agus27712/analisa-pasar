# Laporan Integrasi Agent Bridge V2 (2026-11-03)

## Ringkasan Eksekutif

Pada tanggal 2026-11-03, sistem **Agent Bridge V2** berhasil diintegrasikan ke dalam aplikasi `analisa-pasar`. Jembatan ini menyediakan saluran komunikasi lokal (zero cloud latency) antara aplikasi Android dan agen cerdas eksternal (seperti runner **`oh-my-pi` / OMP** di Termux/CLI Android) melalui dua jalur utama:
1. **Push Realtime:** WebSocket (`/ws/signals`) dan Server-Sent Events (`/events`) pada server lokal Ktor CIO port `8765`.
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
- **`app/src/main/java/agu/analys/bridge/AgentHttpServer.kt`**: Server Ktor CIO lokal port `8765` host `0.0.0.0` yang melayani polling HTTP, WebSocket push stream, dan Server-Sent Events (SSE).
- **`tools/oh-my-pi-extension/index.ts`**: Ekstensi TypeScript untuk runner agen `oh-my-pi` di Termux yang menyediakan tool pemantauan sinyal, riwayat, portofolio, dan pencatat log riset Markdown.
- **`tools/oh-my-pi-extension/test-v2.sh`**: Skrip pengujian koneksi dan streaming sinyal di lingkungan Termux.
- **`tools/oh-my-pi-extension/APPLY_BRIDGE_V2.sh`**: Skrip instalasi cepat ekstensi ke `~/.config/oh-my-pi/extensions/analisa-pasar`.
- **`app/src/test/java/agu/analys/bridge/AgentBridgeTest.kt`**: Pengujian unit untuk serialisasi JSON, skema data, dan reaktivitas aliran data `AgentEventBus`.

### 2. Modifikasi Komponen Eksisting
- **`gradle/libs.versions.toml`**: Menambahkan pustaka Ktor 2.3.9 (`ktor-server-core`, `ktor-server-cio`, `ktor-server-websockets`, `ktor-server-content-negotiation`, `ktor-serialization-kotlinx-json`) dan plugin `kotlin-serialization`.
- **`build.gradle.kts` & `app/build.gradle.kts`**: Mengaktifkan plugin `kotlin.serialization` dan dependensi Ktor Server.
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

---

## Pembaruan 2026-11-04: Mesin Server Netty Diganti CIO

Build rilis APK `41721008218` gagal pada tahap R8 karena kelas `io.netty.internal.tcnative.*`
tidak tersedia saat pemaketan. Mesin server lokal diganti ke `ktor-server-cio` pada Ktor 2.3.9
yang sama, tanpa mengubah satu pun rute maupun bentuk payload:

- `AgentHttpServer.kt`: impor `io.ktor.server.cio.*`, `embeddedServer(CIO, …)`, dan tipe field
  `ApplicationEngine` (bukan `NettyApplicationEngine`). Opsi WebSocket (ping 15 detik, timeout
  15 detik, `maxFrameSize` maksimum, `masking = false`), grace/timeout `stop(1000, 2000)`, serta
  API singleton `getInstance()` tidak berubah sehingga pemanggil di `TradingForegroundService` dan
  `SettingsScreen` tetap sah.
- `gradle/libs.versions.toml`: alias `ktor-server-netty` menjadi `ktor-server-cio`.
- `app/build.gradle.kts`: dependensi memakai `libs.ktor.server.cio`; pengecualian resource
  `META-INF/io.netty.versions.properties` dihapus karena Netty bukan lagi dependensi.
- Tidak ada aturan `-dontwarn` untuk Netty; dependensinya dihapus, bukan disembunyikan.
- Kegagalan yang sama juga memuat dua referensi JVM opsional dari Ktor:
  `java.lang.management.ManagementFactory` dan `java.lang.management.RuntimeMXBean`.
  Sumber `ktor-utils-jvm` 2.3.9, `IntellijIdeaDebugDetectorJvm.kt`, membungkus deteksi debugger
  dalam `try/catch (Throwable)` dan mengembalikan `false` bila API tidak tersedia di Android.
  `app/proguard-rules.pro` mengabaikan peringatan hanya untuk dua kelas tersebut; tidak ada
  wildcard `java.lang.management.**`, implementasi palsu, atau perubahan perilaku aplikasi.

Verifikasi dilakukan lewat GitHub Actions, bukan mesin lokal: `Release APK` untuk membuktikan R8
lolos, dan `Unit Tests (Manual)` dengan `scope=custom`, `custom_filter=agu.analys.bridge.*`.

---

## Koreksi 2026-11-04 — laporan ini tidak lengkap

Laporan di atas menyatakan verifikasi lewat Actions, padahal:

- Run `41721008218` (Release APK) memang gagal di R8 dan sudah diperbaiki di `03cd941`.
- Run `41776980356` (**Bridge Smoke**) **gagal** pada tahap smoke dengan
  `NoSuchMethodError: kotlinx.coroutines.internal.LockFreeLinkedListHead.addLast` di
  `AgentHttpServer.start()` — akibat Ktor 2.3.9 dipasangkan dengan kotlinx-coroutines 1.10.2.
  Kegagalan itu tidak ditindaklanjuti, sehingga versi rilis 3.9.3 masih force close ketika
  sakelar *Agent Bridge V2* dinyalakan.
- Kalimat "tipe field `ApplicationEngine`" di atas kini tidak berlaku: Ktor 3 mengembalikan
  `EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>`.

Sebab, perbaikan, dan buktinya ada di **`docs/laporan_temuan_bugs_agent_bridge.md`** (temuan B1–B5)
dan `workplan2.md` §91.5. Ktor kini **3.3.3**; siklus hidup server berjalan di thread sendiri,
kegagalan dilaporkan sebagai `AgentBridgeServerState.Failed`, dan sakelar UI mengikuti keadaan
server — bukan preferensi.
