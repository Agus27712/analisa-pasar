# LAPORAN — Bug auto-buy paper: nominal Rupiah dibaca sebagai USDT, dan anggaran tidak menyisihkan biaya

**Status:** ✅ Selesai — dua cacat diperbaiki, suite penuh **371 test / 0 gagal** (naik dari 358), kontrol negatif dijalankan
**Tanggal:** 2026-10-15
**Branch:** `patch`
**Pelapor:** owner (log runtime, AVAUSDT)
**Berkas kunci:** `engine/scalping/PaperAutoTradeConfig.kt`, `viewmodel/PaperAutoTradeCoordinator.kt`,
`ui/components/settings/PaperAutoTradeSettings.kt`, `ui/components/detail/scalp/PaperTradeDeskCard.kt`,
`util/AppPreferences.kt`

---

## 1. Gejala yang dilaporkan

```
01:52:09.296 INFO PaperAutoBuy ❌ [AVAUSDT] Auto-buy simulasi gagal:
Saldo USDT tidak cukup. Dibutuhkan $560.41, saldo $557.71.
```

Owner melaporkan auto-buy **tidak pernah** tereksekusi, dan menduga nominal yang ia set
(Rp 100.000) disalahartikan sebagai USDT. Dugaan itu benar — dan ternyata ada cacat kedua
di belakangnya yang membuat kegagalannya **sistematis**, bukan kebetulan.

## 2. Diagnosis

### Cacat A — nominal Rupiah dipakai apa adanya sebagai kas kuotasi

`PaperAutoTradeConfig.fixedQuoteAmount` disajikan di UI sebagai angka Rupiah:

| Petunjuk di UI | Nilai |
|---|---|
| Nilai default | `100_000` |
| Langkah tombol −/+ | `10_000` |
| Batas bawah | `10_000` |
| Batas atas | `100_000_000` |
| Teks yang tampil | `"100 rb"` (tanpa penanda mata uang) |

Tetapi `quantityFor()` memakainya sebagai **jumlah kas kuotasi pair**. Untuk pair ber-kuotasi
USDT (Tokocrypto), Rp 100.000 menjadi **$100.000** — sekitar 16.400× terlalu besar.

### Cacat B — batas anggaran tidak menyisihkan fee & slippage

`quantityFor()` memotong anggaran dengan `fixedQuoteAmount.coerceAtMost(availableQuote)`,
yaitu **saldo mentah**. Padahal `SimulationTradeStore.placeOrder()` untuk order MARKET BUY
menuntut:

```
required = quantity × (execPrice) × (1 + feeRate)
         = notional  × (1 + slippage) × (1 + fee)
```

Jadi begitu nominal ≥ saldo, kebutuhan **selalu** melebihi saldo sebesar fee + slippage.
Auto-buy tidak bisa berhasil — bukan "kadang gagal", tapi **tidak pernah**.

### Bukti aritmetika (persis angka di log)

Fee Tokocrypto USDT `0,4044%` per sisi, slippage default `0,08%` per sisi:

| Langkah | Nilai |
|---|---:|
| `budget = min(100.000, 557,71)` | 557,71 |
| `execPrice = price × 1,0008` | — |
| `required = 557,71 × 1,0008 × 1,004044` | **560,41** |
| Saldo tersedia | 557,71 |
| Hasil | **ditolak** |

Angka `560,41` yang dihitung ulang cocok dengan log sampai pembulatan 2 desimal
(selisih `+0,0034`), jadi penyebabnya terkonfirmasi, bukan dugaan.

Dengan semantik Rupiah yang benar (kurs contoh Rp 16.400/USDT):
Rp 100.000 → **$6,0976**, dan kebutuhannya **$6,1271** — jauh di bawah saldo $557,71.

## 3. Perbaikan

### 3.1 `engine/scalping/PaperAutoTradeConfig.kt`

- `fixedQuoteAmount` → **`fixedIdrAmount`**, didokumentasikan tegas sebagai **Rupiah**.
  Kunci preferensi `KEY_PAPER_AUTO_BUY_FIXED_QUOTE` **tidak** diubah, jadi nilai yang sudah
  tersimpan tetap terbaca — yang berubah hanya tafsirnya.
- `quantityFor(...) : Double` → **`plan(...) : AutoBuySizing`**, dengan `AutoBuySizing` berupa
  sealed interface `Ok(quantity, notionalQuote)` / `Skip(reason)`. Ukuran yang tidak layak
  sekarang membawa **alasan yang bisa dibaca**, bukan menyamar sebagai "kuantitas 0".
- Rumus anggaran memakai `notional * (1 + slippage) * (1 + fee)` — **identik** dengan yang
  dipakai order engine. Ini yang menutup Cacat B.
- Pair USDT: `notional = fixedIdrAmount / usdtIdrRate`. Kurs `≤ 0` → `Skip`.
- Nominal tidak muat → `Skip`. **Tidak ada lagi pemotongan diam-diam.**
- Mode `RISK_BASED` tetap memotong ke daya beli (itu memang semantiknya), tetapi batasnya kini
  juga sadar biaya, plus epsilon relatif supaya galat pembulatan float tidak membuat order
  melewati saldo.

### 3.2 `viewmodel/PaperAutoTradeCoordinator.kt`

`tryPaperAutoBuy()` sekarang mengambil `feeRate` dari `prefs.feeConfigFor(symbol).buyTakerPct`
dan `slippagePct` dari `prefs.scalpingRuntimeConfig.slippagePct` — **sumber yang sama** dengan
yang dipakai `SimulationCoordinator.setSlippagePct` (`TradingViewModelSync.kt:483`). Ini
disengaja: kelas bug yang sedang ditutup ini adalah "biaya yang dicek ≠ biaya yang dipotong".

Log penolakan kini memuat alasan spesifik, dan log sukses memuat nominal yang benar-benar dipakai:

```
🤖 [AVAUSDT] Auto-buy SIMULASI 1234.56789000 AVA @ $0.00494
   | Rp 100.000 → $6.098 (kurs Rp 16.400) | Stage ENTRY | Exit: ...
```

### 3.3 `ui/components/settings/PaperAutoTradeSettings.kt`

- Label: `"Nominal per posisi"` → **`"Nominal per posisi (Rp)"`**.
- Nilai: `"100 rb"` → **`"Rp 100.000"`** (lewat `PriceFormatter.formatMoney(_, "IDR")`).
- Ditambah keterangan langsung di bawah stepper:
  `Dikonversi per pair saat sinyal: ≈ $6.098 untuk pair USDT (kurs Rp 16.400).`
  Bila kurs belum ada, teksnya berubah menjadi peringatan berwarna bahwa auto-buy pair USDT
  akan dilewati.
- Fungsi `formatCompact()` dihapus karena tidak lagi terpakai.

### 3.4 `ui/components/detail/scalp/PaperTradeDeskCard.kt`

Baris ringkasan alokasi dulu hanya menulis `"nominal tetap per posisi"` tanpa angka. Kini
menampilkan nominalnya: `nominal Rp 100.000 per posisi (dikonversi per pair)`.

## 4. Verifikasi

### 4.1 Test baru — `app/src/test/java/agu/analys/engine/scalping/PaperAutoBuySizingTest.kt`

13 test, mencakup: kasus nyata AVAUSDT, kurs belum tersedia, nominal melebihi anggaran,
saldo tepat sebesar nilai posisi (tetap harus gagal karena fee+slippage), batas inklusif,
pair IDR, mode berbasis risiko, dan masukan tidak valid.

### 4.2 Dijalankan sungguhan (bukan diklaim)

**Suite unit test penuh repo** — `./gradlew :app:testDebugUnitTest`, AGP 9.1.1, Kotlin 2.2.10,
`compileSdk = 36`, Android SDK 36 + build-tools 36.0.0:

```
BUILD SUCCESSFUL in 4m 37s
32 actionable tasks: 32 executed
```

Angka diambil dari `app/build/test-results/testDebugUnitTest/*.xml` (51 berkas hasil):

| | Jumlah |
|---|---:|
| Total test | **371** |
| Gagal | **0** |
| Error | **0** |
| Dilewati | 1 |
| Lulus | **370** |

Sebelum perubahan, dokumen repo mencatat **358** test. `358 + 13 = 371` — konsisten, jadi tidak ada
test lama yang hilang dan tidak ada yang regresi. Kelas baru `PaperAutoBuySizingTest`:
13 test, 0 gagal, 0 error.

### 4.3 Kontrol negatif

Logika `plan()` dibalik ke perilaku lama (nominal dipakai apa adanya + potong ke saldo mentah),
lalu test yang sama dijalankan ulang **lewat jalur yang sama persis** (kompilasi mandiri atas
berkas asli, JUnit 4.13.2):

```
Tests run: 13,  Failures: 6
pairUsdt_nominalRupiahDikonversi_bukanDipakaiApaAdanya:
    java.lang.AssertionError: expected:<6.0976> but was:<557.71>
```

Enam kegagalan itu membuktikan test-nya benar-benar menangkap kedua cacat — khususnya
`expected:<6.0976> but was:<557.71>` yang memperlihatkan perilaku lama memakai **seluruh saldo**
sebagai ukuran posisi. Tanpa kontrol negatif, 13 test yang lulus tidak membuktikan apa pun
selain "kode hari ini konsisten dengan dirinya sendiri".

## 5. Konsekuensi perilaku

| | Sebelum | Sesudah |
|---|---|---|
| Ukuran posisi pair USDT | seluruh saldo (lalu ditolak) | Rp 100.000 ≈ $6,10 |
| Auto-buy | tidak pernah tereksekusi | tereksekusi bila nominal muat |
| Nominal tidak muat | dipotong diam-diam | dilewati, dengan alasan di log |
| Kurs belum tersedia | nominal dipakai sebagai USDT | sinyal dilewati |

Karena auto-buy belum pernah tereksekusi, **tidak ada statistik paper lama yang tercemar**.
Perhatikan: dengan Rp 100.000 per posisi pada saldo ~$557, satu posisi ≈ **1,1% saldo**. Kalau
owner ingin porsi lebih besar, ubah nominalnya di Pengaturan.

## 6. Batasan & yang belum dikerjakan

- Verifikasi di atas dijalankan di sandbox yang tidak punya Android SDK; SDK 36 + build-tools 36.0.0
  dipasang khusus untuk ini. Satu catatan lingkungan: `/root/.gradle/init.gradle` bawaan sandbox
  punya dua salah ketik (`maven { url https://… }` tanpa tanda kutip dan `mavelCentral()`), sehingga
  Gradle gagal di tahap inisialisasi. Berkas itu **disisihkan sementara** selama build lalu
  **dikembalikan** ke tempatnya. Ini murni artefak sandbox, bukan masalah repo.
- `assembleDebug` (build APK) belum dijalankan — hanya kompilasi + unit test. Perintahnya:
  `./gradlew testDebugUnitTest assembleDebug`.
- Tidak ada jalur ke eksekusi uang riil yang tersentuh. Ini tetap murni simulasi.
- `data/` tidak diubah.
