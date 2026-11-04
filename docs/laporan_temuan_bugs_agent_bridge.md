# Laporan Temuan — Agent Bridge V2 (force close saat sakelar dinyalakan)

**Ditemukan:** 2026-11-04 · **Ditutup:** 2026-11-04 · **Branch:** `main`
**Letak berkas:** `docs/laporan_temuan_bugs_agent_bridge.md`. Berkas ini **tidak berada** di
`docs/bugs/` karena seluruh temuannya sudah tertutup. Aturannya: `docs/bugs/` hanya memuat temuan
yang **masih hidup**; berkas yang sudah beres menjadi laporan dengan nama
`docs/laporan_temuan_bugs_<topik>.md`.
**Cakupan:** `bridge/AgentHttpServer.kt` · `ui/screens/SettingsScreen.kt` · `app/proguard-rules.pro` ·
`gradle/libs.versions.toml` · `tools/bridge-smoke/` · `.github/workflows/bridge-smoke.yml` ·
`app/src/test/java/agu/analys/bridge/`
**Batas penting:** yang diperbaiki adalah **cara server bridge dinyalakan dan dilaporkan**, bukan
isi sinyal/portofolio. Tidak ada perubahan pada engine scalping, fee, maupun jalur order.

| Status | Temuan | Jumlah |
|---|---|---|
| 🔴 Kritis | B1 (aplikasi force close saat sakelar bridge dinyalakan) | 1 |
| 🟠 Serius | B2 (sakelar UI mengklaim "aktif" tanpa melihat keadaan server) | 1 |
| 🟡 Sedang | B3 (verifikasi CI gagal tetapi tidak ditindaklanjuti) · B4 (versi dependensi ganda di alat smoke) | 2 |
| 🔵 Rendah | B5 (`public/assets/aistudio/` sampah · `gradlew` kehilangan bit eksekusi · versi UI hardcode) | 3 |
| ✅ Sudah diperbaiki | B1–B5 | 7 butir |

> **Tidak ada pekerjaan tersisa di berkas ini.** Kalau butuh pekerjaan baru di area bridge, lakukan
> audit baru dan tulis temuannya ke berkas baru di `docs/bugs/` (`temuan_bugs_<topik>.md`).

---

## 1. B1 — force close saat sakelar dinyalakan

**Gejala yang dilaporkan owner:** menyalakan *Agent Bridge V2* di Pengaturan → aplikasi langsung
tertutup.

**Sebab sebenarnya (terukur, bukan tebakan).** Workflow `Verify Release Fix (sementara)` run
[`41776980356`](https://github.com/Agus27712/analisa-pasar/actions/runs/41776980356) dijalankan
13 detik setelah commit `03cd941` masuk, dan **gagal** pada tahap smoke — tepat pada jalur yang
dipakai sakelar:

```
java.lang.NoSuchMethodError: 'void kotlinx.coroutines.internal.LockFreeLinkedListHead.addLast(
    kotlinx.coroutines.internal.LockFreeLinkedListNode)'
  at io.ktor.events.Events.subscribe(Events.kt:24)
  at io.ktor.server.engine.BaseApplicationEngine.<init>
  at io.ktor.server.cio.CIOApplicationEngine.<init>
  at agu.analys.bridge.AgentHttpServer.start(AgentHttpServer.kt:29)
```

Tiga hal bertumpuk menjadi crash:

1. **Ketidakcocokan versi.** Ktor 2.3.9 dikompilasi terhadap kotlinx-coroutines lama, sedangkan
   aplikasi memakai coroutines **1.10.2**. Di coroutines 1.10, `LockFreeLinkedListHead.addLast`
   menjadi `internal` (nama JVM-nya di-mangle), sehingga metode yang dicari Ktor tidak ada.
2. **Jenis galat salah ditangkap.** `NoSuchMethodError` turunan **`Error`**, sedangkan `start()`
   hanya memakai `catch (e: Exception)` → lolos → proses dimatikan.
3. **Pekerjaan socket di main thread.** `start()` memanggil `embeddedServer(...).start()` langsung
   dari `onCheckedChange` Compose, sehingga kelas kegagalan lain (mis.
   `NetworkOnMainThreadException`) juga berujung pada aplikasi berhenti.

## 2. B2 — sakelar mengklaim "aktif" tanpa bukti

`SettingsScreen` menyimpan `isAgentEnabled` dari preferensi dan menampilkan
"Aktif: ws://0.0.0.0:8765/ws/signals" segera setelah sakelar ditekan, tanpa pernah memeriksa apakah
port benar-benar melayani. Ketika server gagal (port dipakai aplikasi lain, kelas hilang), UI tetap
berkata aktif sampai aplikasi ditutup.

## 3. B3, B4 — verifikasi dan alat ukur

- **B3.** Run `41776980356` gagal dan **tidak pernah ditindaklanjuti**; pesan commit `03cd941`
  menyebut verifikasi lewat Actions, tetapi tidak ada run hijau untuk smoke. Workflow-nya bahkan
  berkomentar "dihapus setelah bukti tercatat" sementara berkasnya tetap ada.
- **B4.** `tools/bridge-smoke/build.gradle.kts` menulis ulang versi Ktor, coroutines, dan
  serialization dengan angka sendiri. Alat ukur yang punya versi sendiri bisa hijau/merah berbeda
  dari aplikasi — persis mekanisme yang membuat bug B1 mudah lolos.

## 4. B5 — higiene commit `dba9f39`

- `public/assets/aistudio/` (8 berkas `.txt` hasil ekspor, duplikat versi lama dari sumber asli)
  ikut ter-commit dan tidak dirujuk dokumen/kode/workflow mana pun.
- `gradlew` kehilangan bit eksekusi (`100755 → 100644`).
- Subjudul "Pembaruan & Versi Aplikasi" menulis versi secara hardcode (`Versi 3.9.2 (Build 91)`)
  sehingga tidak ikut naik saat versi berubah.

---

## 5. Perbaikan

| Butir | Perbaikan | Berkas |
|---|---|---|
| B1 | Ktor `2.3.9` → `3.3.3` (dibangun dengan Kotlin 2.2.21 + **coroutines 1.10.2**, sama dengan aplikasi). Ktor 3 memakai `kotlin.time.Duration`, jadi `java.time.Duration` (API 26) tidak lagi dipakai padahal `minSdk` 24 | `gradle/libs.versions.toml`, `AgentHttpServer.kt` |
| B1 | Siklus hidup server dipindah ke satu thread khusus (`agent-bridge-lifecycle`); `start()`/`stop()` hanya menjadwalkan pekerjaan dan langsung kembali | `AgentHttpServer.kt` |
| B1 | Semua kegagalan ditangkap sebagai `Throwable` dan dilaporkan sebagai `AgentBridgeServerState.Failed(reason)`; `Running` baru ditulis setelah `GET /health` di `127.0.0.1:8765` menjawab | `AgentHttpServer.kt` |
| B1 | `-keep class agu.analys.bridge.**` supaya model `@Serializable` + serializer hasil plugin tidak dibuang R8 | `app/proguard-rules.pro` |
| B1 | `start()` saat state **Stopping** tidak lagi dibuang — permintaan dijadwalkan setelah pekerjaan berhenti selesai (terbukti dari smoke: restart setelah `stop()` tidak pernah sehat); `stop()` saat **Stopping** jadi no-op | `AgentHttpServer.kt` |
| B2 | Sakelar membaca `AgentHttpServer.state`: `Running` → aktif, `Failed` → sakelar kembali mati + alasan, `Starting`/`Stopped` → teks "Menyalakan…"/"Menunggu…" | `SettingsScreen.kt` |
| B3 | Workflow dipertahankan sebagai **`Bridge Smoke`** (`workflow_dispatch`), komentar "sementara/dihapus" diganti keterangan riwayat kegagalan | `.github/workflows/bridge-smoke.yml` |
| B4 | Versi Ktor/coroutines/serialization proyek smoke **dibaca dari** `gradle/libs.versions.toml` (gagal keras kalau kunci tidak ada) | `tools/bridge-smoke/build.gradle.kts` |
| B5 | `public/assets/aistudio/` dihapus; bit eksekusi `gradlew` dikembalikan (`git update-index --chmod=+x`); subjudul versi memakai `BuildConfig.VERSION_NAME/VERSION_CODE` | repo |

## 6. Bukti

**Penjaga otomatis** (`./gradlew :app:testDebugUnitTest --tests "agu.analys.bridge.*"`):

| Uji | Yang dijaga |
|---|---|
| `AgentBridgeServerTest.sakelar menyala membuka port dan stop menutupnya` | `Running` hanya setelah `/health` 200 dan `/` menyebut `analisa-pasar-agent-bridge-v2`; setelah `stop()` port benar-benar menutup |
| `AgentBridgeServerTest.start tidak memblokir thread pemanggil` | `start()` kembali < 500 ms (bukti pekerjaan socket tidak di main thread) |
| `AgentBridgeServerTest.port terpakai dilaporkan Failed bukan melempar keluar` | kegagalan jadi `Failed` (tidak melempar), dan percobaan ulang setelah port bebas berhasil |
| `AgentBridgeTest` (3 uji lama) | serialisasi `AgentSignalOutput`/`AgentPortfolioOutput` + pancaran `AgentEventBus` |

**Kontrol negatif (wajib, dan sudah terbukti gagal pada kode lama):** run
`41776980356` adalah kontrol negatifnya sendiri — di pohon `03cd941` (Ktor 2.3.9) tahap smoke
melempar `NoSuchMethodError` di `AgentHttpServer.start()`. Bukti itu yang membuat B1 tidak bisa
dianggap "mungkin".

**Bukti lintas proses:** `.github/workflows/bridge-smoke.yml` menjalankan
`tools/bridge-smoke`, yang memakai `AgentHttpServer.kt` **asli** dari `app/src/main/java`
(disalin saat build) untuk menguji HTTP polling, WebSocket snapshot+push, SSE ping+push, dan
stop/restart. Hasil run dicatat di `workplan2.md` §91.5.

## 7. Riwayat

| Tanggal | Kejadian |
|---|---|
| 2026-11-04 04:19 | `dba9f39` — Agent Bridge V2 masuk `main` (Ktor **Netty** 2.3.9, `start()` di main thread, `catch (Exception)`) |
| 2026-11-04 05:04 | `03cd941` — Netty → CIO (R8 lolos), **tanpa** memperbaiki ketidakcocokan coroutines |
| 2026-11-04 05:04 | Run `41776980356` (Bridge Smoke) **gagal**: `NoSuchMethodError ... addLast` di `AgentHttpServer.start()` |
| 2026-11-04 (sesi ini) | Owner melaporkan force close saat sakelar dinyalakan; sebab dicocokkan dengan log run gagal |
| 2026-11-04 (sesi ini) | Ktor 3.3.3 + siklus hidup di luar main thread + `catch (Throwable)` + sakelar mengikuti `state` + R8 keep + smoke membaca versi dari `libs.versions.toml`; B5 dibereskan |
| 2026-11-04 (sesi ini) | Run `41794360203` (Bridge Smoke) **gagal**: `start()` dipanggil saat state `Stopping` langsung return → percobaan ulang restart tak pernah menyala (`Server did not become healthy`). `start()` kini menjadwalkan start setelah berhenti tuntas; `stop()` saat Stopping no-op. Smoke lokal **PASS** |
