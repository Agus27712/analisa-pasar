# Laporan — Batch 2 Perbaikan Audit Notifikasi (N4, N5, N7)

**Tanggal:** 2026-10-16
**Branch:** `patch`
**Sumber temuan:** `docs/laporan_temuan_bugs_notifikasi.md`
**Cakupan:** 3 temuan — N4 (serius, **diperluas**), N5 (serius), N7 (sedang, terverifikasi aktif)
**Lanjutan:** `docs/LAPORAN-batch1-notifikasi-2026-10-16.md` (N1, N2, N3)

---

## 1. Ringkasan

| # | Temuan | Sifat | Berkas yang diubah |
|---|---|---|---|
| **N4** | Sakelar notifikasi tidak dipatuhi — master diabaikan di satu jalur, **keempat sakelar kategori diabaikan di semua jalur** | kontrol user hilang | `util/NotificationGate.kt` (baru), `util/AppPreferences.kt`, `util/AlertNotificationHelper.kt` |
| **N5** | Notifikasi kandidat tanpa konteks "uang riil masih diblokir" | pelanggaran aturan AGENTS | `util/AlertNotificationHelper.kt` |
| **N7** | `markTriggered` **dan** `reset` tidak menormalkan simbol | bug aktif | `engine/scalping/SignalLifecycleManager.kt` |

Hasil ukur:

| | Kelas | Total test | Lulus | Gagal | Dilewati | APK |
|---|---|---|---|---|---|---|
| Baseline (setelah batch 1) | 61 | 453 | 452 | 0 | 1 | — |
| Setelah batch 2 | 63 | 462 | 461 | 0 | 1 | 27.877.730 byte |
| **Selisih** | **+2** | **+9** | **+9** | 0 | 0 | — |

---

## 2. N4 — sakelar notifikasi tidak dipatuhi (diperluas)

### 2.1 Keadaan sebelum perbaikan

Audit asli menemukan `TradingViewModelAlerts` tidak pernah membaca `isNotificationsEnabled`. Saya
memeriksa seluruh jalur pengiriman dan menemukan **temuan yang lebih besar**:

| Sakelar | Dibaca jalur kirim? |
|---|---|
| `isNotificationsEnabled` (master) | Ya di `TradingViewModelSync`, `CandidateScanWorker`, `TradingForegroundService`. **Tidak** di `TradingViewModelAlerts` (`grep -c` → 0). |
| `isNotifyPriceAlertsEnabled` | **Tidak sama sekali** |
| `isNotifyCandidateBuyEnabled` | **Tidak sama sekali** |
| `isNotifyTrailingStopEnabled` | **Tidak sama sekali** |
| `isNotifyEmergencyExitEnabled` | **Tidak sama sekali** |

Keempat sakelar kategori hanya dibaca/ditulis di `SettingsViewModel` dan dirender di
`SettingsCategoryDetailContent`. **Pengaturan menawarkan empat sakelar yang tidak melakukan apa
pun.** Ini tidak ada di audit asli.

### 2.2 Jebakan yang dihindari

Audit menyarankan: *"tambah guard `prefs.isNotificationsEnabled` di awal `checkAlertsAndTrailing`"*.

**Saran itu akan menimbulkan bug yang lebih buruk.** `checkAlertsAndTrailing` memanggil
`checkTrailingForMode`, dan di dalamnya ada `executeAutoSellOrder` (baris 203, 250, 262, 272).
Guard di awal fungsi akan **mematikan auto-sell** — jaring pengaman posisi — hanya karena user
mematikan notifikasi. Mematikan notifikasi bukan berarti mematikan perlindungan posisi.

Karena itu gerbang dipasang **di lapisan notifikasi saja**.

### 2.3 Perbaikan

Gerbang tunggal, bukan guard di tiap call site — justru karena itulah akar N4: ada belasan call
site dan **tidak satu pun** memeriksa sakelar. Guard per-call-site akan mengulang kesalahan yang
sama begitu ada call site baru.

**a. `util/NotificationGate.kt` (baru)** — fungsi murni:

```kotlin
enum class NotificationCategory(val label: String) {
    CANDIDATE_BUY, PRICE_ALERT, TRAILING_STOP, EMERGENCY_EXIT
}

data class NotificationSettings(
    val masterEnabled: Boolean = true,
    val candidateBuyEnabled: Boolean = true,
    val priceAlertsEnabled: Boolean = true,
    val trailingStopEnabled: Boolean = true,
    val emergencyExitEnabled: Boolean = true
)

fun allowsNotification(settings: NotificationSettings, category: NotificationCategory): Boolean {
    if (!settings.masterEnabled) return false
    return when (category) { /* satu cabang per kategori */ }
}
```

Master mati ⇒ semua kategori mati, karena di UI sakelar kategori memang **sub-pengaturan** dari
master (disembunyikan saat master mati), bukan pengaturan sejajar.

**b. `AppPreferences.notificationSettings`** — potret kelima sakelar sekali jadi, supaya pemanggil
tidak perlu tahu nama kelima key-nya.

**c. `AlertNotificationHelper.canNotify(context, category)`** dipasang di **keenam** chokepoint:

| Fungsi | Kategori |
|---|---|
| `sendCandidateFoundNotification` | `CANDIDATE_BUY` |
| `sendEmergencyExitNotification` | `EMERGENCY_EXIT` |
| `sendTrailingHitNotification` | `TRAILING_STOP` |
| `sendTrailingPeakUpdateNotification` | `TRAILING_STOP` |
| `sendTakeProfitNotification` | `TRAILING_STOP` |
| `sendPriceAlertNotification` | `PRICE_ALERT` |

Take profit masuk `TRAILING_STOP` karena label di Pengaturan adalah *"Notifikasi Trailing Stop &
Eksekusi — sinyal kenaikan trailing profit dan eksekusi jual otomatis"*.

Objek `AppPreferences` di-cache di helper (konstruktornya membangun `EncryptedSharedPreferences` —
terlalu mahal bila dipanggil tiap notifikasi). Bila prefs gagal dibaca, gerbang **terbuka** — sama
dengan perilaku sebelum perbaikan, supaya keystore rusak tidak diam-diam mematikan peringatan
darurat.

### 2.4 Dampak ke pengguna

Semua default `true`, jadi pengguna yang belum pernah menyentuh Pengaturan **tidak kehilangan
notifikasi apa pun**. Yang berubah hanya pengguna yang secara eksplisit mematikan sesuatu — dan
mereka kini mendapat apa yang mereka minta.

---

## 3. N5 — disclaimer di notifikasi kandidat

Push kandidat menyajikan `Entry` / `Target 1` / `Stop Loss` tanpa konteks apa pun, padahal
AGENTS.md mewajibkan:

> *"Status SIAP bukan ajakan beli. Setiap permukaan yang menampilkan status siap wajib menyertakan
> konteks bahwa eksekusi uang riil masih diblokir."*

Push notification adalah permukaan **paling berisiko** dibaca sebagai ajakan beli nyata: ia muncul
di layar kunci tanpa kartu penjelas di sekitarnya.

**Perbaikan:** konstanta `CANDIDATE_DISCLAIMER` ikut di badan pesan:

> `→ Eksekusi uang riil masih DIBLOKIR. Sinyal ini decision support, bukan ajakan beli.`

---

## 4. N7 — normalisasi simbol di `markTriggered` dan `reset`

Key `activeSignals` dibangun lewat `cacheKey` = `"${normalizeSymbol(symbol)}#${mode}"`, dan
`normalizeSymbol` membuang `/ _ -`. Kedua fungsi itu memakai `"${symbol.uppercase()}#"` mentah:

| Pemanggilan | Prefix terbentuk | Key tersimpan | Cocok? |
|---|---|---|---|
| `markTriggered("eth_idr")` | `ETH_IDR#` | `ETHIDR#SWING` | **tidak** |
| `reset("eth_idr")` | `ETH_IDR#` | `ETHIDR#SWING` | **tidak** |

Akibatnya sinyal diam-diam tidak pernah berstatus TRIGGERED (efek sampingnya: siklus bisa langsung
mendeteksi ulang → notifikasi berulang), dan `reset` tidak menghapus apa pun sehingga state lama
tertinggal di mesin.

**Audit asli hanya menyebut `markTriggered`** dan masih ragu apakah bug ini aktif atau latent.
Dua koreksi saya:

1. **`reset` punya cacat yang sama** — tidak disebut audit.
2. **Bug ini aktif, bukan latent.** Buktinya: `RealTradeExecutor.baseFromPair` dan `quoteFromPair`
   sama-sama melakukan `pair.lowercase().replace("_", "")`. Strip `_` yang defensif itu hanya masuk
   akal bila `pair` memang berdelimiter (format Indodax `btc_idr`). Jadi prefix `"BTC_IDR#"` tidak
   akan pernah cocok dengan key `"BTCIDR#SCALPING"`.

**Perbaikan:** keduanya memakai `normalizeSymbol(symbol)`.

---

## 5. Kontrol negatif

Ketiga perbaikan dimatikan sekaligus, lalu suite dijalankan ulang.

| Perbaikan dimatikan | Uji yang GAGAL | Dari |
|---|---|---|
| `canNotify` tidak lagi memanggil `allowsNotification` | 3 uji **perilaku** gerbang | 6 |
| `CANDIDATE_DISCLAIMER` dilepas dari badan pesan | 1 uji disclaimer | 5 |
| `normalizeSymbol` → `symbol.uppercase()` | 2 uji normalisasi | 2 |
| **Total** | **6 uji gagal dari 13** | |

Uji yang **tetap lulus** saat perbaikan dimatikan, beserta alasannya:

- 3 uji murni `allowsNotification` (`default semua kategori diizinkan`, `master mati mematikan
  seluruh kategori`, `sakelar kategori hanya mematikan kategorinya sendiri`) — ia menguji fungsi
  murni secara langsung, tidak lewat `canNotify`.
- 4 uji `CandidateSignalMessageTest` lainnya (skor ×100, batas 100, skor nol, struktur pesan) —
  menguji hal lain di fungsi yang sama.

Perbaikan dikembalikan setelah kontrol dijalankan; suite hijau kembali.

---

## 6. Catatan harness untuk agen berikutnya

Uji perilaku gerbang memakai Robolectric + `NotificationManager` sungguhan, jadi ia membuktikan
notifikasi **benar-benar tidak terpasang** — bukan sekadar memanggil fungsi murni.

Tapi ada jebakan yang perlu diketahui: **di bawah Robolectric, membuat `AppPreferences` kedua tidak
melihat tulisan instance pertama.** Saya menemukannya lewat uji diagnostik (`instance baru harus
melihat tulisan instance lama expected:<false> but was:<true>`) — penyebabnya jalur pemulihan
keystore di konstruktor yang menimpa prefs.

Karena itu uji menulis lewat `AlertNotificationHelper.notificationPrefsForTest(context)` — objek
yang **benar-benar** dibaca gerbang. **Batas kejujuran uji ini:** ia membuktikan *"diberi setelan X,
gerbang menahan kategori Y"*, bukan *"helper membaca berkas prefs aplikasi yang sama"*.

Catatan sampingan (bukan bagian batch ini): `AppPreferences` **bukan singleton**, dan
`TradingForegroundService.updatePrice/updatePrices` membangunnya **tiap tick harga** — artinya
`EncryptedSharedPreferences` dibuat ulang terus-menerus. Itu kandidat temuan terpisah di area
performa.

---

## 7. Yang belum dikerjakan

| # | Temuan | Kenapa ditahan |
|---|---|---|
| **N6** | Dua produsen (worker 15 menit + engine per-tick) memajukan satu state machine → notifikasi ganda/keliru | Butuh **keputusan desain**: worker hanya mengevaluasi mode aktif, atau beri instance `SignalLifecycleManager` terpisah |
| **N8** | Param `onlyWhenBackground` diteruskan tapi tidak pernah dibaca | Pilihan fitur: implementasikan atau hapus parameternya |
| **N9** | `recordBuy` default `signalConfidence = snapshot?.confidenceScore ?: 75` mengarang skor | Latent (kedua pemanggil kini eksplisit) |
| **N10** | Snapshot tidak memverifikasi `signal.marketSymbol == symbol` yang dibeli | Latent |
| **N11** | Tier confidence mencampur mode | Masalah interpretasi statistik, bukan bug fungsional |
| **N12** | Tidak ada cooldown per-pair untuk notifikasi kandidat | Risiko spam |

---

## 8. Berkas yang berubah

**Kode**
- `app/src/main/java/agu/analys/util/NotificationGate.kt` — **baru** (N4)
- `app/src/main/java/agu/analys/util/AppPreferences.kt` — `notificationSettings` (N4)
- `app/src/main/java/agu/analys/util/AlertNotificationHelper.kt` — gerbang 6 chokepoint (N4), disclaimer (N5)
- `app/src/main/java/agu/analys/engine/scalping/SignalLifecycleManager.kt` — normalisasi simbol (N7)

**Uji**
- `app/src/test/java/agu/analys/util/NotificationGateTest.kt` — **baru** (6 test)
- `app/src/test/java/agu/analys/engine/scalping/SignalLifecycleSymbolNormalizationTest.kt` — **baru** (2 test)
- `app/src/test/java/agu/analys/util/CandidateSignalMessageTest.kt` — +1 test

**Dokumen**
- `workplan2.md` (§28, §29)
- `AGENTS.md` (aturan baru)
- `docs/laporan_temuan_bugs_notifikasi.md` (N4/N5/N7 ditandai selesai, temuan baru dicatat)
- `docs/LAPORAN-batch2-notifikasi-2026-10-16.md` (berkas ini)
