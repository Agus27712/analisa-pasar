# Laporan — Batch 1 Perbaikan Audit Notifikasi (N1, N2, N3)

**Tanggal:** 2026-10-16
**Branch:** `patch`
**Sumber temuan:** `docs/laporan_temuan_bugs_notifikasi.md` (audit read-only agen lain, di-pull dari `patch` pada commit `36e0594`)
**Cakupan:** 3 dari 12 temuan — N2 (kritis), N1 (kritis), N3 (serius)

---

## 1. Ringkasan

| # | Temuan | Sifat | Berkas yang diubah |
|---|---|---|---|
| **N2** | Balapan edge trailing → auto-sell bisa tidak pernah terkirim | race condition | `trading/SpotPositionStore.kt`, `service/TradingForegroundService.kt` |
| **N1** | Notifikasi kandidat menampilkan confidence ×100 (`6800%`) | bug display | `util/AlertNotificationHelper.kt` |
| **N3** | Service menghitung P&L notifikasi dengan fee legacy, bukan fee user | pelanggaran "SATU rumus P&L" | `service/TradingForegroundService.kt` |

Hasil ukur:

| | Kelas | Total test | Lulus | Gagal | Dilewati | APK |
|---|---|---|---|---|---|---|
| Baseline (via `git stash -u`) | 58 | 443 | 442 | 0 | 1 | — |
| Setelah batch 1 | 61 | 453 | 452 | 0 | 1 | 27.877.730 byte |
| **Selisih** | **+3** | **+10** | **+10** | 0 | 0 | — |

---

## 2. N2 — balapan edge trailing

### 2.1 Akar masalah

`SpotPositionStore.updateTrailingPrice` mengembalikan `Pair<SpotPosition, Boolean(justTriggered)>`
dan bersifat **edge-once** — begitu flag `_trailing_triggered` dipersist, pemanggilan berikutnya
selalu mengembalikan `false`.

Dua loop memanggilnya bersamaan saat posisi aktif:

| Pemanggil | Irama | Memakai `justTriggered`? |
|---|---|---|
| `TradingForegroundService` | polling 4 detik | **tidak** — hasilnya dibuang |
| `TradingViewModel.checkTrailingForMode` | per tick harga | **ya** — gerbang `executeAutoSellOrder` |

Bila service menang balapan, VM tidak pernah melihat `true` → **notifikasi trailing hilang dan
auto-sell tidak terkirim**.

Bukti tambahan yang memperkuat diagnosis: service mengambil `pos` **sebelum** memanggil
`updateTrailingPrice`, lalu memakai `pos` itu untuk `posContext`. Jadi efek samping panggilan itu
terhadap `posContext`-nya sendiri memang **tidak pernah terpakai**. Satu-satunya efek nyata:
peak naik, counter bertambah, dan **edge dicuri**.

### 2.2 Perbaikan

Parameter baru `consumeTrigger: Boolean = true` pada `updateTrailingPrice`:

```kotlin
// Rule 1 (peak) selalu jalan …
if (currentPrice > newPeak) { newPeak = currentPrice; … }

// … lalu pengamat non-pemilik berhenti di sini.
if (!consumeTrigger) {
    return Pair(get(symbol, isReal, exchange), false)
}
```

Service mengirim `consumeTrigger = false`. Peak tetap dinaikkan (pelacakan background tidak
kehilangan apa pun), tapi mesin pemicu — hitung batas, filter 2 tick, set flag — hanya dijalankan
oleh pemilik eksekusi.

### 2.3 Uji

`app/src/test/java/agu/analys/trading/TrailingEdgeOwnershipTest.kt` (3 test, Robolectric):

1. **pengamat tidak boleh mengonsumsi edge milik pemilik eksekusi** — tick 1 di bawah batas →
   pengamat dengan `consumeTrigger=false` → VM tetap menerima `justTriggered = true`.
2. **pengamat tetap menaikkan peak walau tidak mengonsumsi edge** — menjaga agar perbaikan tidak
   dilakukan dengan cara mematikan pelacakan peak di background.
3. **edge trailing hanya bisa dikonsumsi sekali** — mendokumentasikan sifat edge-once yang jadi
   akar masalah.

---

## 3. N1 — confidence tampil ×100

`AISignalState.confidence` bertipe `Int` skala **0–100** (`model/TradingViewModels.kt`). Baris
notifikasi kandidat menulis:

```kotlin
"• Skor Keyakinan: ${(signal.confidence * 100).toInt()}%"
```

→ skor `68` tampil sebagai **`6800%`**. Permukaan lain sudah konsisten memakai nilai mentah
(`SignalHistoryPanel` → `Skor 68/100`, `SignalLogScreen` → `68%`), jadi notifikasi ini satu-satunya
yang menyimpang.

**Perbaikan:** badan pesan diekstrak ke fungsi murni `buildCandidateSignalMessage(marketKey, signal)`
sehingga bisa diuji tanpa Android, dan faktor `* 100` dihapus.

**Uji:** `app/src/test/java/agu/analys/util/CandidateSignalMessageTest.kt` (4 test) — memastikan
`68%` (bukan `6800%`), `100%` (bukan `10000%`), `0%` tetap `0%`, dan struktur pesan tidak rusak.

---

## 4. N3 — fee service tidak sama dengan fee user

`TradingForegroundService` memanggil:

```kotlin
SellSignalEvaluator.evaluate(context = posContext, indicators = null,
    tradingFees = TradingFeeConfig(), riskSnapshot = riskSnapshot)
```

Tanpa argumen, `TradingFeeConfig` jatuh ke preset **legacy Indodax-ish** (0,11 / 0,21 / 0,32 / 0,42)
— bukan biaya bursa aktif milik user.

**Ini regresi dari §24.** Commit `7fa3be9` menyeragamkan seluruh hitungan P&L ke basis FSM, tapi
melewatkan permukaan ini, sehingga aturan **SATU rumus P&L** masih bocor di notifikasi — tepat di
tempat yang paling sering dilihat user.

**Perbaikan:** service memakai `AppPreferences(applicationContext).tradingFees`, sama dengan jalur
VM. Objek `AppPreferences` di-cache lewat `by lazy` (membuat EncryptedSharedPreferences tiap tick
akan boros), tapi nilainya dibaca ulang tiap tick supaya perubahan di Pengaturan langsung terpakai.

**Uji:** `app/src/test/java/agu/analys/service/NotificationFeeSourceTest.kt` (3 test) — invarian
sumber + bukti dampak (legacy membuat PnL terlihat lebih besar dari kenyataan).

### 4.1 Batas penjagaan — dibaca sebelum mempercayai uji ini

Uji invarian N3 memeriksa **teks sumber** `TradingForegroundService.kt` (komentar dibuang lebih
dulu, lalu dipastikan tidak ada `TradingFeeConfig()` dan ada `tradingFees = appPrefs.tradingFees`).
Ia menangkap persis regresi yang diperbaiki, **tapi tidak memeriksa perilaku runtime**: kalau
pemanggilan itu dipindah ke berkas lain, penjaga ini tidak ikut. Uji perilaku penuh butuh
menjalankan service dengan `Context` tiruan, yang belum ada infrastrukturnya di repo ini.

---

## 5. Kontrol negatif

Ketiga perbaikan dimatikan sekaligus, lalu suite dijalankan ulang.

| Perbaikan dimatikan | Uji yang GAGAL | Dari |
|---|---|---|
| `* 100` dikembalikan | `skor keyakinan dipakai apa adanya…`, `batas atas 100 tidak pernah membengkak…` | 4 |
| `if (!consumeTrigger)` → `if (false)` | `pengamat tidak boleh mengonsumsi edge…` | 3 |
| `appPrefs.tradingFees` → `TradingFeeConfig()` | `service foreground dilarang memakai default legacy…` | 3 |
| **Total** | **4 uji gagal dari 10** | |

Uji yang **tetap lulus** saat perbaikan dimatikan, beserta alasannya:

- `skor nol tetap nol` — `0 × 100 = 0`, memang tidak bergantung pengali.
- `pesan tetap membawa entry, target, dan stop loss` — menguji struktur, bukan skor.
- `pengamat tetap menaikkan peak…` — Rule 1 tidak terpengaruh `consumeTrigger`.
- `edge trailing hanya bisa dikonsumsi sekali` — menguji sifat edge-once, bukan kepemilikan.
- `default legacy berbeda dari biaya bursa aktif`, `friksi legacy menggeser angka PnL…` — uji
  **premis**, sengaja tidak bergantung pada perbaikan.

Perbaikan dikembalikan setelah kontrol dijalankan; suite hijau kembali.

---

## 6. Koreksi dokumen lama

Tabel §25 `workplan2.md` menulis "**443 lulus** · 0 gagal · 1 dilewati (58 kelas)". Pengukuran
ulang dari XML menunjukkan 443 adalah **total test** (442 lulus + 1 dilewati). Sudah diperbaiki di
§25, dan angka baseline §27 kini diukur langsung via `git stash -u`, bukan dikutip dari catatan.

---

## 7. Yang belum dikerjakan

Temuan **N4–N12** masih hidup di `docs/laporan_temuan_bugs_notifikasi.md`. Yang paling perlu perhatian
berikutnya:

- **N7** — `markTriggered` memakai `symbol.uppercase()` tanpa normalisasi, sedangkan key disimpan
  ternormalisasi. Audit menyebut ini "harus diverifikasi"; **verifikasi menunjukkan ini AKTIF**,
  bukan latent: `baseFromPair`/`quoteFromPair` melakukan `.replace("_","")` defensif, bukti `pair`
  memang berdelimiter (`btc_idr`) sehingga prefix `"BTC_IDR#"` tidak akan pernah cocok dengan key
  `"BTCIDR#SCALPING"`.
- **N4** — toggle "Push notifikasi" tidak pernah dibaca di `TradingViewModelAlerts.kt`
  (`grep -c isNotificationsEnabled` → **0**), jadi user yang mematikannya masih menerima bel.
- **N5** — notifikasi kandidat tidak menyertakan disclaimer "eksekusi uang riil masih DIBLOKIR"
  yang diwajibkan AGENTS.md.
- **N6** — dua produsen (worker 15 menit + engine per-tick) memajukan satu state machine.

---

## 8. Berkas yang berubah

**Kode**
- `app/src/main/java/agu/analys/util/AlertNotificationHelper.kt` — N1
- `app/src/main/java/agu/analys/trading/SpotPositionStore.kt` — N2
- `app/src/main/java/agu/analys/service/TradingForegroundService.kt` — N2 + N3

**Uji baru**
- `app/src/test/java/agu/analys/util/CandidateSignalMessageTest.kt`
- `app/src/test/java/agu/analys/trading/TrailingEdgeOwnershipTest.kt`
- `app/src/test/java/agu/analys/service/NotificationFeeSourceTest.kt`

**Dokumen**
- `workplan2.md` (§25 koreksi, §26, §27)
- `AGENTS.md` (aturan baru)
- `docs/laporan_temuan_bugs_notifikasi.md` (N1/N2/N3 ditandai DIPERBAIKI)
- `docs/LAPORAN-batch1-notifikasi-2026-10-16.md` (berkas ini)
