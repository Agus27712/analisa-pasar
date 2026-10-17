# Laporan Batch 6g — N15 + N16: foreground service adalah keep-alive, bukan pengirim push

**Tanggal:** 2026-10-17
**Branch:** `patch`
**Temuan:** N15, N16 — `docs/bugs/temuan_bugs_notifikasi.md`

## Ringkasan

| # | Gejala |
|---|---|
| N15 | Mematikan sakelar "push notifikasi" **menghentikan** `TradingForegroundService` — yaitu keep-alive pemantauan posisi. `livePrices` jadi kosong ⇒ P&L tampil 0% untuk semua posisi |
| N16 | `AppPreferences` dibangun 7× di service, termasuk di `updatePrices` yang dipanggil **setiap tick dashboard**. Konstruktornya mahal dan, bila gagal, **menghapus seluruh preferensi** |

Keduanya melanggar aturan yang sama: *"mematikan notifikasi tidak boleh mematikan eksekusi"*.

## Perbaikan N15 — keputusan start/stop hanya dari posisi

```kotlin
// TradingViewModelSync.updateForegroundServiceState()
if (hasActive) {
    TradingForegroundService.startService(...)
    TradingForegroundService.forceRefresh(...)
} else {
    TradingForegroundService.stopService(...)
}
```

`isNotificationsEnabled.value` dihapus dari kondisi itu, dan guard yang sama dihapus dari
`startService` + `forceRefresh` di companion service. Bunyi notifikasi tetap diatur
`canNotify(...)` di dalam helper — jadi mematikan sakelar tetap mematikan **bunyi**, hanya tidak
lagi mematikan **pemantauan**.

**Yang perlu diketahui:** service tetap menampilkan notifikasi persisten (`setOngoing(true)`,
kategori `SERVICE`, tanpa suara) karena Android mewajibkan foreground service punya notifikasi.
Itu status pemantauan, bukan push; menghilangkannya berarti melepas status foreground dan
mengundang proses dibunuh sistem.

## Perbaikan N16 — satu `AppPreferences`, dan nol di jalur per-tick

| Lokasi | Sebelum | Sesudah |
|---|---|---|
| `formatCoinQuantity` | `AppPreferences(this)` | `appPrefs` (lazy, satu per instance) |
| `updateNotification` | `AppPreferences(this)` | `appPrefs` |
| `getHoldingsData` | `AppPreferences(context)` | `appPrefs` |
| `startService` / `forceRefresh` | `AppPreferences(context)` untuk guard | tidak ada (N15) |
| `updatePrice` | `AppPreferences(context)` | **fungsi dihapus** — tidak punya pemanggil |
| `updatePrices` | `AppPreferences(context)` tiap tick | **tidak ada**; bursa dioper pemanggil |

`updatePrices` sekarang wajib menerima `exchange`:

```kotlin
fun updatePrices(context: Context, prices: Map<String, Double>, exchange: String)
```

Pemanggilnya (`MarketViewModel.kt:328`) sudah punya `currentSource`, jadi tidak ada informasi yang
hilang — hanya satu pembacaan preferensi per tick yang dihapus.

Hasil akhir: `grep -n "AppPreferences(" TradingForegroundService.kt` → **1** (baris `by lazy`).

## Uji

**5 uji baru** (639 → 644), semuanya pindai sumber mengikuti pola `NotificationFeeSourceTest`:

| Uji | Yang dijaga |
|---|---|
| `start-stop service tidak boleh bergantung pada sakelar notifikasi` | badan `updateForegroundServiceState` tidak memuat `isNotificationsEnabled`, tetapi tetap memakai `hasActive` |
| `startService dan forceRefresh tidak membaca preferensi` | kedua fungsi companion tidak memuat `isNotificationsEnabled` |
| `service hanya membangun AppPreferences sekali` | hitungan `AppPreferences(` = 1 |
| `updatePrice yang tidak terpakai tidak boleh kembali` | tidak ada `fun updatePrice(` |
| `updatePrices menerima bursa dari pemanggil` | badan fungsi bebas `AppPreferences` dan memuat `exchange: String` |

Kenapa pindai sumber: keputusan start/stop ada di `ViewModel`, dan `updatePrices` di `Context`.
Keduanya tidak bisa dipanggil dari unit test JVM tanpa Android, sedangkan yang ingin dijaga adalah
**bentuk** kode — persis yang bisa dibaca dari sumber.

## Kontrol negatif

| Kontrol | Perubahan | Hasil |
|---|---|---|
| A | `if (hasActive && isNotificationsEnabled.value)` dikembalikan | **1 uji GAGAL** |
| B | `AppPreferences(context)` ditambahkan di `updatePrices` **dan** `fun updatePrice(...)` dihidupkan | **3 uji GAGAL** (hitungan prefs, badan `updatePrices`, keberadaan `updatePrice`) |

**Angka terukur (dari XML):** 80 kelas / 639 uji → **81 kelas / 644 uji**, **0 gagal, 0 error,
1 dilewati**.

## Batas

- **N6 + N17 belum disentuh** (butuh keputusan desain: satu produsen atau dua instance
  `SignalLifecycleManager`). N12 dan sisa N11 juga masih terbuka.
- Service tetap memakai satu `AppPreferences` **per instance**; ia masih dibangun ulang setiap
  service dibuat ulang (mis. setelah proses dibunuh). Itu batas desain `AppPreferences` sendiri,
  bukan N16 — N16 khusus soal konstruksi berulang di dalam satu proses.
- Perubahan `updatePrices` mengubah signature publik. Hanya ada satu pemanggil di repo
  (`MarketViewModel`), dan compiler memastikan tidak ada yang terlewat.
