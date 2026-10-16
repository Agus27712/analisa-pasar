# Laporan — Batch 3a: Jurnal sinyal & angka P&L (J2, J1)

**Tanggal:** 2026-10-16
**Branch:** `patch`
**Sumber temuan:** `docs/bugs/temuan_bugs_jurnal_pnl.md` (audit read-only agen lain, `patch` @ `eb01dc3`)
**Cakupan:** J2 (klasifikasi menang/kalah) + J1 (basis P&L jurnal sinyal). Batch 3 dikerjakan dalam
tiga commit terpisah; ini yang pertama. **Commit perbaikan: `d97a539`.**

---

## 1. Ringkasan

| # | Temuan | Status | Bukti |
|---|---|---|---|
| **J2** | Tiga definisi W/L; dua tidak eksklusif ⇒ win-rate bisa > 100% | ✅ **DIPERBAIKI** | `SignalOutcomeClassificationTest` (14 uji) |
| **J1** | P&L **kotor** dilabeli "Net" dan mencemari seluruh statistik | ✅ **DIPERBAIKI** | uji net-basis di berkas yang sama |

**Temuan yang tetap hidup di area ini:** J3 (bursa terikat sekali saat konstruksi `SignalLogViewModel`),
J4 (= N10, `selectedPair` dipakai alih-alih simbol yang dievaluasi engine), J5 (fallback lintas bursa
di jurnal trade), J6 (klaim statistik hardcoded), J7 (radar jual belum berbasis FSM), J8 (satu rumus
P&L ditulis ulang tanpa slippage di jalur trade real), J9 (= N9, default `?: 75`), J10 (= N10).

---

## 2. J2 — satu predikat menang/kalah yang eksklusif

### 2.1 Masalah

`SignalLogRepository` punya **tiga** definisi menang/kalah. Hanya `buildGroupStats` yang eksklusif:

```kotlin
// BENAR (eksklusif) — buildGroupStats
val wins   = resolved.filter { it.outcomeStatus in winStatuses || (it.outcomeStatus !in lossStatuses && pnl(it) > 0.0) }
val losses = resolved.filter { it.outcomeStatus in lossStatuses || (it !in wins && pnl(it) < 0.0) }
```

```kotlin
// TIDAK eksklusif — buildTierStats & calculateReliabilitySummary
val wins   = resolved.filter { it.outcomeStatus in listOf("HIT_TP1","HIT_TP2","MANUAL_WIN") || (it.realizedPnlPct ?: 0.0) > 0 }
val losses = resolved.filter { it.outcomeStatus in listOf("HIT_SL","MANUAL_LOSS")          || (it.realizedPnlPct ?: 0.0) < 0 }
```

Dua filter itu bebas satu sama lain, jadi sebuah log bisa masuk keduanya.

### 2.2 Akar penyebabnya bukan di statistik

Statistik hanyalah korban. Akarnya di `recordSignal`:

```kotlin
val updatedTp1 = if (targetPrice1 > 0.0) targetPrice1 else primaryLog.targetPrice1
val updatedSl  = if (stopLoss > 0.0) stopLoss else primaryLog.stopLoss
// copy() TIDAK pernah menyalin entryPrice ⇒ entryPrice tetap milik sinyal PERTAMA
```

Rantai nyatanya:

| Langkah | Keadaan |
|---|---|
| 1 | Log dibuat: `entryPrice = 100`, `stopLoss = 90` |
| 2 | Harga naik ke 200; sinyal baru terbit `entry 200 / SL 190` ⇒ `stopLoss` jadi **190**, `entryPrice` tetap **100** |
| 3 | Harga turun ke 190 ⇒ `currentPrice (190) <= stopLoss (190)` ⇒ status `HIT_SL` |
| 4 | P&L dihitung `(190 − 100)/100 = **+90%**` |

Langkah 4 melahirkan dua cacat sekaligus: log `HIT_SL` ber-P&L **positif** (masuk keranjang menang
**dan** kalah), dan "rata-rata kalah" yang bisa bernilai +90%.

### 2.3 Perbaikan

**a. Satu predikat eksklusif** — `database/SignalOutcome.kt` (baru):

```kotlin
enum class SignalOutcome { WIN, LOSS, NEUTRAL }

fun classifySignalOutcome(status: String, realizedPnlPct: Double?): SignalOutcome = when {
    status in WIN_STATUSES  -> SignalOutcome.WIN
    status in LOSS_STATUSES -> SignalOutcome.LOSS
    else -> /* tanda P&L, atau NEUTRAL bila nol/tidak diketahui */
}
```

Prioritas **status di atas P&L**: status adalah peristiwa yang benar-benar terjadi ("stop loss
tersentuh"), sedangkan besaran P&L bisa menyesatkan bila datanya warisan. Konsekuensinya `HIT_SL`
ber-P&L positif dihitung **kalah**.

Dipasang di **empat** call site: `calculateReliabilitySummary` (win/loss), `buildTierStats`,
`buildGroupStats`, dan `bestSymbol`/`bestStrategy` — yang terakhir dulu memakai definisi keempat
(`count { it.outcomeStatus in listOf("HIT_TP1", …) }`).

**b. Pemisahan episode** — `database/SignalLogPnl.kt` (baru):

```kotlin
internal const val ENTRY_SPLIT_TOLERANCE = 0.008
internal fun hasEntryDrifted(oldEntry: Double, newEntry: Double): Boolean
```

Ambang 0,8% sengaja **sama** dengan ambang anti-spam di `recordSignal`, supaya kedua aturan tidak
saling bertabrakan. Bila entry bergerak melebihi ambang, log lama ditutup apa adanya
(`resolveSupersededTrackingLog` → `EXPIRED` pada harga terkini, memakai `entry`/`TP`/`SL` miliknya
sendiri) lalu episode baru dicatat sebagai log terpisah. Level antar-episode **tidak pernah** digabung.

Harga tidak valid (`<= 0`, `NaN`, `∞`) **tidak** dianggap perpindahan material — supaya pemanggil
yang mengirim harga kosong tidak memecah pelacakan yang sedang berjalan.

**c. Rata-rata tidak lagi tercemar** — `avgProfitPct`/`avgLossPct`/`maxSingleWinPct`/
`maxSingleLossPct` kini hanya dihitung dari log yang P&L-nya **searah** keranjangnya.

---

## 3. J1 — basis net untuk `realizedPnlPct`

### 3.1 Masalah

`updateTrackingLogWithPrice` menyimpan selisih harga **mentah** (tanpa fee beli, fee jual, maupun
slippage) ke `realizedPnlPct`, dan `SignalLogScreen` melabelinya "Net". Angka itu lalu jadi dasar
`avgProfitPct`, `avgLossPct`, `profitFactor`, tabel tier, dan "LAPORAN AUDIT DIAGNOSTIK".

### 3.2 Arah SELL

`netProfitPctFor` selalu memodelkan posisi **long**. Sinyal `SELL` adalah cerminnya: menjual di
`entry` (kena fee jual + slippage) lalu membeli kembali di `price` (kena fee beli + slippage).
Secara aljabar itu **identik** dengan menukar peran `entry` dan `price`:

```
long  = price·(1 − sellF − slip) / (entry·(1 + buyF + slip)) − 1
short = entry·(1 − sellF − slip) / (price·(1 + buyF + slip)) − 1   =  long dengan entry↔price ditukar
```

Karena itu ditambahkan `FeeSchedule.netProfitPctForDirected(entry, price, isBuy, …)`. Tanpa pembeda
arah, friksi beli akan dibebankan pada kaki yang seharusnya jual.

### 3.3 Titik pemasangan

`netSignalPnlPct` dipakai di tiga tempat — ketiganya dulu menulis rumus sendiri:

1. `updateTrackingLogWithPrice` — penentu `realizedPnlPct` saat TP1/TP2/SL/EXPIRED;
2. `resolveLogManually` — resolusi manual tanpa `customPnlPct`;
3. `expireTrackingLogsForSymbol` — saat posisi sudah dijual.

`maxProfitPct`/`maxDrawdownPct` **tetap kotor** (aturan B6) dan sengaja tidak diubah.

### 3.4 Label UI

Sebelumnya: `"Net +1,20% (gross)"` ketika P&L net belum ada — dua label bertentangan pada satu
angka. Kini `signalPnlLabel()` memilih "Net" atau "Gross" sesuai basis yang benar-benar dipakai, dan
`EXPIRED` tanpa P&L menulis "Hasil tidak diketahui" alih-alih mengarang "0,00%".

---

## 4. Efek amnesti — **wajib dibaca sebelum membandingkan angka lama**

Setelah J1, banyak sinyal yang dulu tampak "menang" berubah menjadi kalah / `EXPIRED` negatif. Itu
**bukan regresi**. Friksi round-trip Tokocrypto USDT = 0,9735%, jadi kenaikan di bawah itu memang
belum menutup biaya.

| Pergerakan | Gross | Net (Tokocrypto USDT) | Kesimpulan |
|---|---|---|---|
| 0,00% | 0,00% | −0,964130% | diam = rugi friksi |
| +0,50% | +0,50% | **−0,468950%** | dulu tampil "Net +0,50%" padahal rugi |
| +1,00% | +1,00% | +0,026229% | baru sedikit di atas impas |

Basis lain untuk pembanding:

| Bursa / kuotasi | Net pada +1,00% |
|---|---|
| Tokocrypto IDR | +0,179934% |
| Indodax IDR | +0,204407% |

**Implikasi yang harus disampaikan apa adanya:** angka win-rate jurnal akan **turun** setelah
perbaikan ini, dan itu memang angka yang benar.

---

## 5. Angka terukur (dari XML, bukan klaim)

| Pohon | Kelas | Total | Lulus | Gagal | Dilewati | APK debug |
|---|---|---|---|---|---|---|
| Baseline (setelah batch 2, §29) | 63 | 462 | 461 | 0 | 1 | 27.877.730 B |
| Commit ini (§30) — *diturunkan* | 64 | 476 | 475 | 0 | 1 | — |
| Keadaan akhir batch 3 (§32) | 67 | 505 | 504 | 0 | 1 | 27.880.361 B |

Angka baseline §29 dikonfirmasi ulang lewat `git stash -u` + `./gradlew :app:testDebugUnitTest`
sebelum batch ini dimulai. Catatan: `app/build` **tidak dilacak git**, jadi berkas XML di repo selalu
milik mesin yang menjalankan tes terakhir — bukan angka yang bisa dikutip dari dokumen.

Uji baru batch ini: `SignalOutcomeClassificationTest` (14).

> ⚠️ **Koreksi cara membaca angka** (ditambahkan 2026-10-16, setelah batch 3 selesai). Versi pertama
> laporan ini menulis "Setelah §30 = 67 kelas / 505 test". Angka itu adalah **angka pohon kerja**
> saat §30 dikerjakan — pohon itu sudah memuat berkas P1 (`PaperRehydrateScope.kt`,
> `PaperEnginePosition.kt`, dan dua berkas ujinya) yang **belum di-commit**. Angka pohon commit 1
> yang sebenarnya adalah **64 / 476 / 475** (462 + 14 uji `SignalOutcomeClassificationTest`,
> 63 + 1 kelas), dan baris itu ditandai *diturunkan* karena tidak diukur ulang. Angka 67/505 tetap
> benar sebagai angka **akhir** batch 3, bukan sebagai angka commit 1. Aturan turunannya sekarang
> ada di `AGENTS.md`.

---

## 6. Kontrol negatif

Wajib, dan sudah dijalankan. Ketiga perbaikan dimatikan sekaligus:

| Perbaikan | Cara mematikan |
|---|---|
| J2 (predikat eksklusif) | `calculateReliabilitySummary` dikembalikan ke dua filter independen |
| J1 (basis net) | `netSignalPnlPct` mengembalikan selisih harga mentah |
| J2 (pemisahan episode) | `hasEntryDrifted` selalu `false` |

**Hasil: 9 dari 22 uji GAGAL** — tepat pada penjaga masing-masing perbaikan.

Yang **tetap lulus** dan alasannya:

- `SignalLogSetupStatsTest` (8 uji) — `buildGroupStats` memang sudah eksklusif sejak awal; kontrol
  negatif ini tidak menyentuhnya. Ini sekaligus membuktikan perbaikan tidak mengubah perilakunya.
- 3 uji `classifySignalOutcome` langsung (`HIT_SL … KALAH`, `status tidak dikenal`, `perpindahan
  entry … harga tidak valid`) — yang dimatikan adalah call site-nya, bukan fungsinya.
- `kuotasi simbol dikenali` — `quoteAssetForSymbol` tidak ikut dimatikan.

---

## 7. Batas yang perlu diketahui

1. **Uji ini menguji fungsi murni, bukan `recordSignal` end-to-end.** `recordSignal` adalah `suspend`
   yang menulis ke Room DAO; memanggilnya di uji unit butuh DAO tiruan yang belum ada infrastrukturnya
   di repo ini. Jadi yang terbukti adalah *"diberi log berstatus `HIT_SL` ber-P&L positif, klasifikasi
   dan statistik menghasilkan angka yang benar"* — bukan *"`recordSignal` benar-benar memisahkan
   episode"*. Jalur pemisahan episode diuji lewat `hasEntryDrifted` (predikatnya) saja.
2. **Data lama tetap ada.** Baris warisan di `signal_logs` yang sudah terlanjur menyimpan `HIT_SL`
   ber-P&L positif tidak diperbaiki otomatis. Statistik sekarang menanganinya dengan benar (masuk
   keranjang kalah, tidak mencemari rata-rata), tetapi `realizedPnlPct` di baris itu tetap salah
   sebagai angka. Belum ada migrasi data.
3. **Belum diuji di perangkat fisik.** Sandbox tidak punya emulator; label UI (`signalPnlLabel`) baru
   terbukti lewat pembacaan kode, bukan render.

---

## 8. Riwayat

| Tanggal | Aksi |
|---|---|
| 2026-10-16 | Audit read-only jurnal & P&L diekspor ke `docs/bugs/temuan_bugs_jurnal_pnl.md` |
| 2026-10-16 | **Batch 3a:** J2 + J1 diperbaiki + diuji di `d97a539` (14 uji baru, kontrol negatif 9/22 gagal) |
| 2026-10-16 | Koreksi cara membaca angka §5 setelah batch 3 selesai: 67/505 adalah angka pohon kerja saat §30, bukan angka pohon commit 1 (64/476/475) |
