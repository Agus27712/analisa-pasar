# Laporan Temuan — Smoke Agent Bridge tidak bisa dikompilasi (stub tertinggal)

**Ditemukan:** 2026-11-12 · **Ditutup:** 2026-11-12 · **Branch:** `main`
**Letak berkas:** `docs/laporan_temuan_bugs_smoke_bridge.md`. Berkas ini **tidak berada** di
`docs/bugs/` karena temuannya sudah tertutup pada sesi yang sama.
**Cakupan:** `tools/bridge-smoke/` (proyek Gradle terpisah) · `.github/workflows/bridge-smoke.yml`
**Batas penting:** temuan ini **tidak** menyentuh engine, fee, order, atau data pasar. Yang rusak
adalah **alat verifikasi** — smoke tidak bisa dikompilasi, jadi ia tidak lagi membuktikan apa pun.

| Status | Temuan | Jumlah |
|---|---|---|
| 🟠 Serius | S1 (proyek smoke gagal kompilasi ⇒ job `bridge-smoke` di Actions merah, verifikasi bridge mati sejak 6 Nov 2026) | 1 |
| 🔵 Rendah | S2 (assertion nama service usang `agent-bridge-v2` ⇒ smoke gagal saat dijalankan meski build lolos) | 1 |
| ✅ Sudah diperbaiki | S1–S2 | 2 butir |

---

## 1. S1 — stub smoke tertinggal, proyek tidak bisa dikompilasi

**Pemicu:** perubahan bridge tanggal 2026-11-06 (token endpoint tulis + `TechnicalBreakdown` dari
snapshot engine) menambah dependensi baru pada berkas produksi yang disalin ke smoke, tetapi
stub di `tools/bridge-smoke/src/main/kotlin/` tidak menyusul.

`tools/bridge-smoke/build.gradle.kts` menyalin **hanya dua** berkas produksi:

```
agu/analys/bridge/AgentHttpServer.kt
agu/analys/bridge/AgentOutputModels.kt
```

 sementara keduanya butuh:

| Kebutuhan | dramática | Perlengkapan |
|---|---|---|
| `AgentOutputModels.kt` memakai `ScalpingMtfSnapshot` (`agu.analys.model`) | stub **hilang** | `agu/analys/model/ScalpingMtfSnapshot.kt` |
| `AgentHttpServer.kt` membaca `agu.analys.util.AppPreferences(context).agentBridgeToken` | stub **hilang** | `agu/analys/util/AppPreferences.kt` |
| `AgentHttpServer.kt` memakai `AgentTradeRequest`/`AgentCancelRequest` + `AgentTradingCommandHandler` | berkas model tidak disalin, handler tidak di-stub | `AgentTradingModels.kt` ikut disalin + stub `AgentTradingCommandHandler` |

**Bukti (sebelum perbaikan), di mesin kerja ini:**

```
e: .../real-src/agu/analys/bridge/AgentOutputModels.kt:99:63 Cannot infer type for this parameter.
e: .../real-src/agu/analys/bridge/AgentOutputModels.kt:99:68 'operator' modifier is required on 'compareTo'.
e: .../real-src/agu/analys/bridge/AgentOutputModels.kt:100:29 Argument type mismatch: actual type is 'String?'...
e: .../real-src/agu/analys/bridge/AgentHttpServer.kt:380:30 Cannot infer type for this parameter.
```

## 2. S2 — assertion nama service usang

`smoke/Main.kt` memeriksa `get("/").contains("agent-bridge-v2")`, sedangkan server sejak V3
(2026-11-06) menjawab `"service":"analisa-pasar-agent-bridge-v3"`. Build bisa lolos, tetapi
smoke selalu gagal saat dijalankan:

```
Exception in thread "main" java.lang.IllegalStateException: Check failed.
	at smoke.MainKt.main(Main.kt:45)
```

---

## 3. Perbaikan

| Butir | Perbaikan | Berkas |
|---|---|---|
| S1 | Stub `ScalpingMtfSnapshot` (hanya field yang dipakai `AgentOutputModels`) dan stub `AppPreferences` (token non-kosong supaya uji 401 tetap menguji fail-closed) ditambahkan | `tools/bridge-smoke/src/main/kotlin/agu/analys/model/ScalpingMtfSnapshot.kt`, `.../agu/analys/util/AppPreferences.kt` |
| S1 | `AgentTradingModels.kt` ikut disalin apa adanya (murni Kotlin + kotlinx.serialization), dan `AgentTradingCommandHandler` di-stub (stub handler order: rute ada, token 401 bekerja, payload ter-serialisasi dengan bentuk produksi) | `tools/bridge-smoke/build.gradle.kts`, `.../agu/analys/bridge/SmokeTradingCommandHandler.kt` |
| S1 | Stub `AgentOutputBridge` dilengkapi `startPortfolioPush()` (dipanggil `AgentHttpServer.boot()`) | `tools/bridge-smoke/src/main/kotlin/agu/analys/bridge/SmokeBridge.kt` |
| S2 | Assertion nama service diselaraskan ke `analisa-pasar-agent-bridge-v3` | `tools/bridge-smoke/src/main/kotlin/smoke/Main.kt` |
| S1 (pencegahan) | Rute baru `/api/v1/engine/analysis` ikut punya assertion smoke; DTO-nya (`AgentEngineAnalysisModels.kt`) disalin apa adanya supaya bentuk JSON yang diuji identik dengan produksi | `tools/bridge-smoke/build.gradle.kts`, `.../smoke/Main.kt`, `.../agu/analys/bridge/SmokeEngineAnalysisProvider.kt` |

**Yang sengaja tidak diperbaiki:** proyek smoke **tidak** meng-copy seluruh `app/src/main/java`.
Meng-copy seluruhnya akan menarik mesin engine, layanan bursa, dan preferensi terenkripsi — tidak
bisa jalan di JVM biasa. Pola yang dipakai: berkas produksi yang **murni** disalin apa adanya;
yang menarik Android/mesin digantikan stub yang **wajib** sejalan dengan aslinya (kalau nama
properti berubah, smoke gagal compile — bukan lolos diam-diam).

## 4. Bukti

- **Smoke lokal setelah perbaikan: PASS.**

  ```
  PASS: actual AgentHttpServer HTTP, file/history, engine analysis, WebSocket snapshot/push, SSE ping/push, stop/restart
  ```

  (perintah: `cd tools/bridge-smoke && gradle run --no-daemon --console=plain`)

- **Kontrol negatif (wajib, dan terbukti gagal pada kode lama):** sebelum stub ditambahkan,
  `gradle compileKotlin` **gagal** dengan 4 error pada `AgentOutputModels.kt` + 1 error pada
  `AgentHttpServer.kt` (kutipan lengkap di §1); setelah stub ditambahkan tapi assertion usang
  belum diperbaiki, build **lolos** tetapi `gradle run` **gagal** dengan
  `IllegalStateException: Check failed` di `Main.kt:45` (§2). Jadi kedua butir punya bukti
  gagal, bukan sekadar "kayaknya sudah benar".

- **Batas bukti ini:** smoke memakai **fixture**, bukan data bursa. Yang dibuktikan adalah
  kontrak HTTP/JSON dan matinya gerbang token — bukan angka pasar dan bukan hasil engine.

## 5. Riwayat

| Tanggal | Kejadian |
|---|---|
| 2026-11-04 | Proyek smoke dibuat (B4: versi Ktor/coroutines dibaca dari `libs.versions.toml`) |
| 2026-11-06 | Bridge V3: token endpoint tulis + `TechnicalBreakdown` dari snapshot engine — stub smoke **tidak** menyusul ⇒ proyek tidak bisa dikompilasi sejak saat itu |
| 2026-11-12 (sesi ini) | Smoke lokal **merah** (build gagal) — ditemukan saat mem-port fitur "engine analysis" dari repo `agentfeed`; job `bridge-smoke` di Actions pasti merah selama ini |
| 2026-11-12 (sesi ini) | Stub `ScalpingMtfSnapshot` + `AppPreferences` + `AgentTradingCommandHandler` ditambahkan, `AgentTradingModels.kt` ikut disalin, `startPortfolioPush()` ditambahkan, assertion nama service diselaraskan → **smoke lokal PASS** |
