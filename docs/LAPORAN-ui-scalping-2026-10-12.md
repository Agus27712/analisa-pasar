# Laporan — UI Baru Mode Scalping & Dukungan Auto Buy/Sell Paper

**Tanggal:** 2026-10-12 · **Repo:** `Agus27712/analisa-pasar` (branch `main`, HEAD `5816ef1`)
**Permintaan owner:** halaman detail koin di mode scalping perlu UI baru; saat ini **radar bilang "terdeteksi" tetapi setup bilang "none"** sehingga tidak sinkron; UI baru harus mendukung fitur **auto buy/sell paper**.

---

## A. Ringkasan eksekutif

Masalahnya bukan bug tampilan biasa. Tiga permukaan UI (`MarketConditionCard`, `WaitingEntryRadarCard`, dan kartu radar lama) masing-masing menyimpulkan status dari **sumber data yang berbeda**, dan kesimpulannya bisa saling bertentangan. Yang salah bukan datanya — **yang salah adalah kesimpulannya**.

Perbaikan yang saya lakukan:

1. **`ScalpReadiness`** — model murni (tanpa dependensi Android) yang menjadi **satu sumber kebenaran** untuk status scalping, dengan satu aturan yang tidak bisa dilanggar: bila `scalpingSetup` kosong atau `"NONE"`, status **tidak boleh** READY atau CONFIRMING.
2. **`ScalpDeskCard`** — meja kerja scalping: status, 5 checkpoint, penghalang utama, rencana risiko (entry/SL/TP1/TP2/Net R:R/lantai friksi), chip gerbang edge pair, dan backtest.
3. **`PaperTradeDeskCard`** — panel auto buy/sell paper: kontrol autoBuy/autoExit/minStage, detail FSM exit, dan peringatan gerbang uang riil.
4. **`paperSnapshotFor()`** — pembacaan posisi paper **per pair**, memperbaiki bug halus: `paperExitSnapshot` adalah StateFlow global yang diisi oleh tick pair mana pun.

Semua sudah terverifikasi: **183 test · 0 gagal · 1 dilewati**, dan `assembleDebug` **BUILD SUCCESSFUL**.

---

## B. Akar masalah: rantai kontradiksi radar vs setup

Ini bukan tebakan — saya lacak langsung ke baris kode engine:

| Baris | Isi | Masalah |
|---|---|---|
| `ScalpingMtfEvaluator.kt:246` | `early = !ready && !isDangerous && step2Ok` | `step2Ok` = tekanan orderbook, **tidak memeriksa pola** |
| `ScalpingMtfEvaluator.kt:294` | `early → ScalpingStage.EARLY_ENTRY` | Stage "early" diberikan tanpa pola valid |
| `SignalLifecycleManager.kt:112` | `EARLY_ENTRY, WAIT_PULLBACK → LifecycleState.DETECTED` | "DETECTED" diwarisi dari stage yang cacat |
| `WaitingEntryRadarCard.kt:317` | `DETECTED → "TERDETEKSI"` | Radar mengklaim terdeteksi |

Artinya: saat tekanan orderbook sudah ada tetapi **belum ada pola trading** (`scalpingSetup == "NONE"`), engine tetap memancarkan `EARLY_ENTRY`, lifecycle menaikkannya jadi `DETECTED`, dan kartu radar menampilkan **"TERDETEKSI"** — sementara kartu sebelahnya dengan benar menampilkan **setup: NONE**.

Dua-duanya "benar" menurut sumbernya masing-masing. Yang salah adalah kesimpulannya.

Satu detail teknis yang penting: field `ScalpingMtfSnapshot.setupOk` **menyesatkan namanya** — di engine, field itu diisi `step2Ok` (tekanan orderbook), **bukan** hasil deteksi pola. Yang benar-benar mensyaratkan pola adalah `triggerOk` (= `step3Ok`). UI lama memakai `setupOk` untuk baris berlabel "Area of Value", sehingga labelnya ikut menyesatkan.

---

## C. Solusi: `ScalpReadiness` — satu sumber kebenaran

Berkas baru: `app/src/main/java/agu/analys/engine/scalping/ScalpReadiness.kt`

### Invarian yang tidak bisa dilanggar

> **Bila `scalpingSetup` kosong atau `"NONE"`, status tidak boleh READY atau CONFIRMING** — walaupun `lifecycleState == DETECTED` atau `scalpingStage == EARLY_ENTRY`.

### Lima status keluaran

| Status | Label UI | Arti |
|---|---|---|
| `HOLDING` | POSISI AKTIF | Ada posisi simulasi terbuka untuk pair ini |
| `READY` | SIAP ENTRY | Semua checkpoint lolos — engine benar-benar akan membeli |
| `CONFIRMING` | KONFIRMASI | Setup + trigger valid, tinggal gate Net R:R dan skor |
| `WATCHING` | MEMANTAU | Belum ada dasar masuk — **termasuk saat orderbook sudah mendukung tapi pola belum ada** |
| `BLOCKED` | TERTAHAN | Volatilitas ekstrem / noise tinggi |

### Lima checkpoint dengan label yang jujur

Urutan ini **berbeda** dari `ScalpingMtfSnapshot.resolvedCheckpoints()` lama, karena tekanan orderbook dipisah dari status pola:

| # | Checkpoint | Sumber field |
|---|---|---|
| 1 | Bias & Ruang Naik | `mtf.biasOk` |
| 2 | **Setup Scalping** | `scalpingSetup != NONE` ← *baru, sebelumnya tidak ada* |
| 3 | Tekanan Orderbook | `mtf.setupOk` (= `step2Ok`) |
| 4 | Trigger VWAP / VSA | `mtf.triggerOk` (= `step3Ok`) |
| 5 | Net R:R & Skor | `mtf.entryPriceOk` |

`resolve()` juga menghitung `canAutoBuy` (syarat sisi sinyal untuk auto-buy paper): belum ada posisi ∧ setup ada ∧ aksi BUY ∧ stage ≥ ambang. Saldo kas dan kepemilikan posisi tidak dihitung di sini karena tidak bisa dipastikan dari model murni.

Model ini **murni** (tanpa dependensi Android) sehingga bisa diuji langsung — dan memang diuji.

---

## D. UI baru

### D.1 `ScalpDeskCard` — meja kerja scalping

`app/src/main/java/agu/analys/ui/components/detail/scalp/ScalpDeskCard.kt`

Menggantikan blok analisa radar lama **hanya saat mode scalping**. Semua angka berasal dari engine (`AISignalState`) — kartu tidak menghitung ulang threshold apa pun.

Isi kartu:

| Bagian | Isi |
|---|---|
| Header status | Label `ScalpReadinessState` + warna (hijau/amber/biru/merah) |
| Headline | Kalimat kesimpulan dari `ScalpReadiness` |
| 5 checkpoint | Checklist berurutan dengan detail dari engine |
| Penghalang | Penghalang utama, mis. "Setup scalping belum terbentuk" |
| Rencana risiko | Zona entry · Jarak harga ke SL · Lantai friksi · Friksi round-trip · TP1/TP2 · Net R:R |
| Gerbang edge pair | Chip `PairEdgeGate` (layak / tidak layak untuk pair ini) |
| Backtest | Bagian backtest yang sudah ada |
| Skor | Skor sinyal |

### D.2 `PaperTradeDeskCard` — panel auto buy/sell paper

`app/src/main/java/agu/analys/ui/components/detail/scalp/PaperTradeDeskCard.kt`

| Bagian | Isi |
|---|---|
| Status posisi | Snapshot posisi paper (atau "belum ada posisi") |
| Detail FSM | Memakai ulang `PaperExitFsmCard` yang sudah ada |
| Kontrol | Toggle **autoBuy**, toggle **autoExit**, pemilih **minStage** |
| Peringatan | Peringatan gerbang uang riil — auto-execute modal riil tetap **dilarang** |

Saat tidak ada posisi, panel menampilkan syarat `canAutoBuy` dari `ScalpReadiness` sehingga user tahu **kenapa** auto-buy belum jalan.

### D.3 Perubahan pada berkas lama

| Berkas | Perubahan |
|---|---|
| `DetailChartScreen.kt` | Wiring `ScalpDeskCard` + `PaperTradeDeskCard` saat `isScalping`; meneruskan `hideAnalysis = isScalping` |
| `WaitingEntryRadarCard.kt` | Parameter baru `hideAnalysis` — blok analisa lama (header radar + target level + backtest) dilewati di mode scalping agar entry/SL/TP tidak tampil dua kali. Zona eksekusi manual tetap tampil |
| `MarketConditionCard.kt` | Cabang scalping kini mengambil judul/warna/detail dari `ScalpReadiness`, bukan dari `scalpingStage` mentah. Import `ScalpingStage`/`SignalAction` dihapus |
| `PaperAutoTradeCoordinator.kt` | Fungsi baru `paperSnapshotFor(exchange, symbol, currentPrice)` |

**Kenapa `paperSnapshotFor()` perlu:** `viewModel.paperExitSnapshot` adalah StateFlow tunggal yang di-`publish` dari tick **pair mana pun** (`MarketDataCoordinator.onPriceUpdate` memanggil `evaluatePaperAutoExit` untuk semua simbol). Halaman detail wajib membaca posisi pair yang sedang dibuka saja — kalau tidak, kartu bisa menampilkan posisi milik pair lain.

---

## E. Verifikasi

| Uji | Hasil |
|---|---|
| Unit test | **183 test · 0 gagal · 1 dilewati** (`BUILD SUCCESSFUL`) |
| Test baru | +15 di `ScalpReadinessTest.kt` |
| Cakupan test baru | Matriks **6 × 6**: seluruh kombinasi `lifecycleState` × `scalpingStage` dengan `setup = NONE` → wajib `WATCHING`, tidak pernah READY/CONFIRMING |
| Build APK debug | **BUILD SUCCESSFUL** — `app-debug.apk` **27,8 MB** |

Test matriks itu inti pertahanannya: ia mengunci invarian sehingga regresi tidak bisa lolos diam-diam.

> Catatan lingkungan: `assembleDebug` semula gagal di `validateSigningDebug` karena `debug.keystore` tidak ada di sandbox (memang gitignored). Setelah keystore debug dibuat lokal, build selesai tanpa error. **Tidak ada perubahan kode yang terlibat.**

---

## F. Yang belum dikerjakan & risiko

1. **Perbaikan akar di engine sengaja ditunda.** Memperbaiki `ScalpingMtfEvaluator` agar `EARLY_ENTRY` mensyaratkan pola akan mengubah statistik log sinyal dan perilaku alert. Itu perubahan perilaku, bukan kosmetik — saya catat di `workplan2.md` §11.4 agar dikerjakan sebagai perubahan tersendiri yang disadari.
2. **UI baru ini belum di-commit.** Working tree masih berisi 6 berkas berubah + 3 berkas baru.
3. **Auto-execute uang riil tetap DILARANG.** Panel paper sudah memberi peringatan ini di UI. Alasannya ada di laporan sebelumnya: profil exit **skew negatif**, margin hanya ~2,3 poin persentase di atas titik impas.
4. **Belum ada UI untuk riwayat trade paper per pair** — saat ini baru posisi aktif.

---

## G. Langkah berikutnya

1. **Commit & push** UI baru ini ke `Agus27712/analisa-pasar`.
2. Jalankan walk-forward lintas bulan (P4.1) — pemisahan train/test saat ini masih satu potong.
3. Verifikasi manual di perangkat: buka pair dengan `setup = NONE` dan pastikan ketiga permukaan (kartu kondisi, scalp desk, radar) **konsisten** mengatakan MEMANTAU.
4. Preferensi maker untuk pair IDR (P4.2) dan rehidrasi FSM paper saat restart (P4.4).
5. Baru setelah itu sentuh Indodax (P5): ambil data historis, jalankan harness replay dengan fee 0,79%, uji jalur TAPI v2 end-to-end.

> **Ringkas satu kalimat:** masalah "radar terdeteksi tapi setup none" sudah ditutup dengan satu sumber kebenaran yang tidak bisa dilanggar dan dikunci oleh test matriks 6×6, plus UI scalping baru yang siap mendukung auto buy/sell paper — tetapi ini perbaikan **konsistensi**, bukan lampu hijau untuk uang riil.

---

## Lampiran — berkas yang terlibat

**Baru:**
- `app/src/main/java/agu/analys/engine/scalping/ScalpReadiness.kt`
- `app/src/main/java/agu/analys/ui/components/detail/scalp/ScalpDeskCard.kt`
- `app/src/main/java/agu/analys/ui/components/detail/scalp/PaperTradeDeskCard.kt`
- `app/src/test/java/agu/analys/engine/scalping/ScalpReadinessTest.kt`

**Berubah:**
- `app/src/main/java/agu/analys/ui/screens/DetailChartScreen.kt`
- `app/src/main/java/agu/analys/ui/components/detail/WaitingEntryRadarCard.kt`
- `app/src/main/java/agu/analys/ui/components/detail/MarketConditionCard.kt`
- `app/src/main/java/agu/analys/viewmodel/PaperAutoTradeCoordinator.kt`
- `AGENTS.md`, `workplan2.md` (§11)

**Pratinjau visual:** `ui-scalping-preview.html` (perbandingan sebelum/sesudah, dua keadaan: tanpa posisi dan dengan posisi + FSM)
