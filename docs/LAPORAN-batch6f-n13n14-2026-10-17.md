# Laporan Batch 6f — N13 + N14 + N8: satu peristiwa, satu pengirim, satu kategori

**Tanggal:** 2026-10-17
**Branch:** `patch`
**Temuan:** N13, N14, N8 — `docs/laporan_temuan_bugs_notifikasi.md`

## Ringkasan

Tiga temuan ini satu akar: **jalur notifikasi trailing memakai satu helper generik untuk dua makna,
dan mengirim dari dua tempat.**

| # | Gejala |
|---|---|
| N13 | Laporan "order jual berhasil/gagal" memakai kategori `PRICE_ALERT`. Mematikan "Trading & Price Alerts" membungkam konfirmasi order uang riil; mematikan "Trailing Stop & Eksekusi" tidak mematikan notifikasi trailing |
| N14 | "Trailing Stop Naik" dikirim dua kali untuk satu peristiwa — sekali dari `update*TrailingOrder` tanpa pembatas, sekali dari jalur ber-cooldown 30 dtk |
| N8 | `onlyWhenBackground` diteruskan ke helper tetapi tidak pernah dibaca |

## Perbaikan

### N13 — kategori mengikuti jenis peristiwa

`util/NotificationRouting.kt` (baru) memuat pemetaannya di satu tempat:

| Peristiwa | Kategori | Sakelar |
|---|---|---|
| kandidat BUY | `CANDIDATE_BUY` | Notifikasi Pair Ready & Sinyal Buy |
| exit darurat / SL | `EMERGENCY_EXIT` | Notifikasi Exit Darurat & Stop Loss |
| **hasil eksekusi jual, trailing, batch sell** | `TRAILING_STOP` | Notifikasi Trailing Stop & Eksekusi |
| **pengingat harga milik pengguna** | `PRICE_ALERT` | Trading & Price Alerts |

`AlertNotificationHelper` sekarang punya dua helper bernama jelas:

```kotlin
sendUserPriceAlertNotification(...)   // kategori PRICE_ALERT
sendExecutionResultNotification(...)  // kategori TRAILING_STOP, kanal trailing
```

Nama generik `sendPriceAlertNotification` **dihapus**, bukan sekadar tidak dipakai. Itu keputusan
penting: dengan nama lama hilang, compiler memaksa setiap call site memilih maknanya, dan pemanggil
baru tidak bisa "salah kategori tanpa sadar".

**Call site yang dipindah (8):** `TradingViewModelAlerts.kt` — hasil auto-sell (`:355`, `:404`),
"Trailing Stop Aktif" (`:503`), "Trailing Sell/Limit Sell Terlaksana/Gagal" (`:598`), "Jual
dibatalkan" (`:610`), hasil limit sell simulasi (`:654`); `BatchSellCoordinator.kt` — dua laporan
batch sell (`:193`, `:333`). Satu call site tetap `PRICE_ALERT`: alert harga buatan pengguna
(`TradingViewModelAlerts.kt:126`, `alert.id.hashCode()`).

### N14 — satu peristiwa, satu pengirim

`updateSimTrailingOrder` / `updateRealTrailingOrder` kini **hanya memperbarui order**. Notifikasi
"Trailing Stop Naik" hanya datang dari jalur peak yang ber-jendela.

Pembatasnya diangkat jadi resolver murni `util/TrailingPeakThrottle.kt`:

```kotlin
object TrailingPeakThrottle {
    const val WINDOW_MS = 30_000L
    fun shouldNotify(lastNotifiedAt: Long, now: Long): Boolean = now - lastNotifiedAt > WINDOW_MS
}
```

Batasnya tetap **strict** (`>`), sama seperti perilaku lama — jendela 30.000 ms persis masih ditahan,
sehingga dua tick pada milidetik yang sama tidak lolos dua kali. Angka `30_000L` yang dulu tertulis
inline sekarang punya nama, dan itulah yang diuji.

### N8 — parameter mati dihapus

`onlyWhenBackground` dihapus dari kedua overload `sendUserPriceAlertNotification`. Setelah N14,
tidak ada lagi pemanggil yang mengisinya (`grep -rn "onlyWhenBackground" app/src` → 0), dan
sebelumnya pun nilainya tidak pernah dibaca di dalam fungsi.

## Uji

**13 uji baru** (626 → 639):

| Berkas | Isi |
|---|---|
| `util/TrailingPeakThrottleTest.kt` (6) | belum pernah kirim → boleh; 10 dtk → tahan; **tepat 30 dtk → tahan**; +1 ms → boleh; jam mundur → tahan; jendela = 30.000 ms |
| `util/NotificationSourceTest.kt` (4) | tidak ada `onlyWhenBackground`; tidak ada `fun sendPriceAlertNotification`; tidak ada pemanggil nama lama di seluruh `src/main/java`; `sendTrailingPeakUpdateNotification` dipanggil **tepat 1×** di `TradingViewModelAlerts.kt` |
| `util/NotificationGateTest.kt` (+3) | price alert mati → **laporan eksekusi tetap terkirim**; trailing mati → laporan eksekusi tertahan; `NotificationRouting` tidak tertukar |

Uji `NotificationSourceTest` mengikuti pola yang sudah dipakai repo di `NotificationFeeSourceTest`
(pindai sumber, komentar dibuang lebih dulu). Ini bukan kemalasan: kategori dipilih di call site,
dan call site ber-`ViewModel`/`Context` tidak bisa diuji tanpa Android. Yang bisa dijaga adalah
**bentuk sumbernya**, dan itu yang diuji.

## Kontrol negatif (empat arah)

| Kontrol | Perubahan | Hasil |
|---|---|---|
| A | `NotificationRouting.EXECUTION` → `PRICE_ALERT` | **3 uji GAGAL** (dua uji perilaku kategori + uji routing) |
| B | kembalikan pengiriman di `updateSimTrailingOrder` | **1 uji GAGAL** ("satu pengirim") |
| C | `shouldNotify` memakai `>=` | **1 uji GAGAL** (batas tepat 30 dtk) |
| D | tambahkan lagi parameter `onlyWhenBackground` | **1 uji GAGAL** (pemindai sumber) |

Kontrol A yang paling penting: ia membuktikan uji gerbang benar-benar membedakan kategori, bukan
sekadar memanggil `allowsNotification`.

**Angka terukur (dari XML, direktori hasil dihapus lebih dulu):** 78 kelas / 626 uji →
**80 kelas / 639 uji**, **0 gagal, 0 error, 1 dilewati**.

## Batas

- **N15 dan N16 belum disentuh** — keduanya di `TradingForegroundService` dan tetap hidup.
- Kanal notifikasi laporan eksekusi ikut pindah dari `CHANNEL_PRICE_ALERT_ID` ke
  `CHANNEL_TRAILING_ID`. Ini disengaja (satu peristiwa tidak boleh muncul di dua kanal), tetapi
  berarti pengguna yang sudah mem-mute kanal "Target Harga & Market" akan menerima laporan eksekusi
  di kanal trailing — sesuai maksud N13: konfirmasi order tidak boleh hilang.
- `setOnlyAlertOnce(true)` **tidak** ditambahkan. Dengan pengirim tunggal + jendela 30 dtk, bunyi
  maksimal sekali per 30 detik selama harga menaik; itu perilaku yang diminta dokumen temuan.
  Kalau nanti dianggap masih terlalu sering, ubah `WINDOW_MS` — satu tempat, teruji.
- Pemetaan `NotificationRouting` hanya mencakup empat jenis peristiwa yang ada hari ini. Peristiwa
  baru wajib menambah entri di sana, bukan memilih kategori langsung di call site.
