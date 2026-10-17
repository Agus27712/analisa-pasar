# Laporan Batch 6b — J9 (N9): default `?: 75` dihapus dari `recordBuy`

**Tanggal:** 2026-10-17
**Branch:** `patch`
**Temuan:** J9 = N9 — `docs/bugs/temuan_bugs_jurnal_pnl.md` · `docs/bugs/temuan_bugs_notifikasi.md`

## Ringkasan

```kotlin
// TradeHistoryRecorder.kt:61 (sebelum)
signalConfidence: Int = snapshot?.confidenceScore ?: 75,
```

Bila snapshot tidak ada dan pemanggil tidak mengirim skor, jurnal menulis **75** — angka yang tidak
pernah dihasilkan siapa pun. Pola yang sama dengan pelanggaran B1 (mengarang data agar baris
terlihat lengkap): pembaca tidak bisa membedakan "keyakinan 75" dari "tidak ada data".

**Latent:** kedua pemanggil saat ini mengirim eksplisit (`TradingViewModelSync.kt:134`, `:316`),
jadi belum ada baris salah di lapangan. Tetapi jebakan itu "tidur" — pemanggil ketiga akan
mewarisi angka karangan tanpa sadar.

## Perbaikan

Resolver murni baru `app/src/main/java/agu/analys/database/TradeSignalConfidence.kt`:

```kotlin
fun resolve(explicit: Int?, fromSnapshot: Int?): Int = explicit ?: fromSnapshot ?: 0
```

- nilai eksplisit menang (termasuk `0`);
- tanpa eksplisit → skor snapshot;
- keduanya kosong → **0** = "tidak diketahui".

`recordBuy` menerima `signalConfidence: Int? = null` dan menyimpan hasil resolver — tidak ada lagi
angka default yang dikarang di signature.

## Uji & kontrol negatif

- **Uji baru:** `app/src/test/java/agu/analys/database/TradeSignalConfidenceTest.kt` — **6 kasus**:
  eksplisit menang, snapshot dipakai, keduanya kosong → 0 (dan **bukan 75**), `0` eksplisit
  dihormati, `0` dari snapshot tetap 0, 100 diteruskan.
- **Kontrol negatif:** `?: 0` dikembalikan ke `?: 75` ⇒ **1 dari 6 uji GAGAL** — tepat uji yang
  menyatakan "tidak diketahui ≠ 75". Lima lainnya tidak membedakan versi. Dicatat apa adanya.
- **Angka terukur (dari XML):** 75 kelas / 601 uji → **76 kelas / 607 uji**, 0 gagal, 1 dilewati.

## Batas

- Tidak ada perubahan perilaku hari ini: kedua pemanggil sudah mengirim eksplisit. Yang berubah
  hanya nilai yang dipakai **bila** ada pemanggil baru yang lupa.
- `recordBuy` (Room + coroutine) tidak diuji otomatis; yang diuji resolver-nya.
