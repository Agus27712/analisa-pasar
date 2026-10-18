# Laporan Batch 6h — N12: cooldown per pair untuk notifikasi kandidat

**Tanggal:** 2026-10-17
**Branch:** `patch`
**Temuan:** N12 — `docs/laporan_temuan_bugs_notifikasi.md`

## Ringkasan

Notifikasi kandidat dipicu transisi `READY` (edge-trigger). Itu menangkal sebagian pengulangan,
tetapi pada pasar berombak state bisa **flapping** `READY → INVALIDATED → READY`; setiap siklus
memenuhi syarat edge dan mengirim notifikasi lagi. Untuk scalping M5, itu berarti bel berulang
untuk setup yang praktis sama.

Pembanding yang sudah ada: peak trailing 30 detik, emergency 60 detik. Kandidat sengaja lebih
panjang.

## Perbaikan

`util/CandidateNotifyThrottle.kt` berisi dua bagian:

```kotlin
object CandidateNotifyThrottle {
    const val WINDOW_MS = 5 * 60_000L
    fun shouldNotify(lastNotifiedAt: Long, now: Long, windowMs: Long = WINDOW_MS): Boolean =
        now - lastNotifiedAt > windowMs
}

class CandidateNotifyCooldown(private val windowMs: Long = CandidateNotifyThrottle.WINDOW_MS) {
    private val lastNotifiedAt = ConcurrentHashMap<String, Long>()
    fun allow(key: String, now: Long): Boolean { … }
    fun clear() { … }
}
```

`AlertNotificationHelper.sendCandidateFoundNotification` memanggil `candidateCooldown.allow(...)`
**sebelum** mengirim, dengan kunci `EXCHANGE_SYMBOL`. Ditaruh di helper, bukan di pemanggil, supaya
jalur foreground (`TradingViewModelSync`) dan latar (`CandidateScanWorker`) sama-sama terkena —
keduanya lewat helper itu.

**Alasan 5 menit** (bisa diperiksa, bukan selera):
- worker latar memindai tiap **15 menit**, jadi cooldown ini tidak pernah menelan dua siklus
  pemindaian yang berbeda;
- sinyal scalping berbasis M5 — 5 menit = satu candle penuh, cukup untuk membedakan "setup yang
  sama berulang" dari "setup baru".

Kalau angka ini terasa terlalu ketat/longgar, ubah `WINDOW_MS`: satu tempat, teruji.

## Uji

**12 uji baru** (644 → 656):

| Berkas | Isi |
|---|---|
| `CandidateNotifyThrottleTest` (11) | 6 kasus murni: belum pernah kirim; 1 menit ditahan; **tepat 5 menit ditahan**; +1 ms boleh; jam mundur ditahan; jendela > peak 30 dtk. 5 kasus penjaga berstatus: pair sama ditahan; pair lain punya jendela sendiri; setelah jendela boleh; `clear` mengosongkan; **pencatatan hanya terjadi saat diizinkan** |
| `NotificationSourceTest` (+1) | helper kandidat benar-benar memanggil `candidateCooldown.allow(...)` |

Kasus terakhir penting: kalau penjaga mencatat waktu walau menolak, jendela akan bergeser terus dan
notifikasi tidak pernah terkirim lagi — bug yang halus dan tidak terlihat dari satu kali kirim.

**Catatan metodologi.** Percobaan pertama memakai uji perilaku "kirim dua kali, hitung notifikasi di
shade" — dan **gagal membedakan**: notifikasi dengan ID yang sama hanya *menimpa* entri, jadi
jumlahnya tetap 1. Uji itu dibuang, bukan dipertahankan sebagai formalitas. Yang diuji sekarang
adalah keputusan `allow(...)` (bisa dibedakan) plus pemanggilannya di sumber.

## Kontrol negatif

| Kontrol | Perubahan | Hasil |
|---|---|---|
| A | `allow` selalu mengembalikan `true` | **2 uji GAGAL** (pair sama ditahan, pencatatan hanya saat diizinkan) |
| B | panggilan `candidateCooldown.allow(...)` dihapus dari helper | **1 uji GAGAL** (uji pindai sumber) |

**Angka terukur (dari XML):** 81 kelas / 644 uji → **82 kelas / 656 uji**, **0 gagal, 0 error,
1 dilewati**.

## Batas

- Cooldown **hanya di memori proses**. Restart app mengosongkannya; itu disengaja — sama seperti
  cooldown peak, dan notifikasi yang sama setelah restart masih masuk akal.
- Kunci cooldown memakai `EXCHANGE_SYMBOL`, jadi pair yang sama di dua bursa punya jendela
  masing-masing. Itu benar: harganya berbeda dan sinyalnya datang dari mesin yang berbeda.
- **N6 + N17 belum disentuh** — keduanya butuh keputusan desain (satu produsen vs dua instance
  `SignalLifecycleManager`). Selama N6 hidup, flapping masih mungkin terjadi; N12 hanya membatasi
  akibatnya di sisi notifikasi.
