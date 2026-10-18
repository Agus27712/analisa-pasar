# Laporan Batch 6e — P7: kontrak `clearPaperExitState` dihapus, sisa masalahnya jadi P9

**Tanggal:** 2026-10-17
**Branch:** `patch`
**Temuan:** P7 — `docs/bugs/temuan_bugs_paper_trade.md`

## Ringkasan

P7 mencatat satu hal sederhana: `TradingViewModel.clearPaperExitState` **tidak punya satu pun
pemanggil**.

```
$ grep -rn "clearPaperExitState" app/src
app/src/main/java/agu/analys/viewmodel/PaperAutoTradeCoordinator.kt:829:fun TradingViewModel.clearPaperExitState(...)
```

Satu hasil — definisinya sendiri. Dokumen-dokumen lain (`AGENTS.md`, `workplan2.md`,
`docs/LAPORAN-p44-*`) menyebutnya sebagai salah satu dari **empat** titik pembersihan state mesin,
seolah-olah jalur "pengguna mengambil alih exit engine" ada. Tidak ada.

## Keputusan: hapus, bukan implementasikan

P7 memberi dua pilihan — implementasikan atau hapus kontraknya. Yang dipilih: **hapus**.

Alasannya bukan "kode mati itu tidak enak dilihat", melainkan **isi fungsinya salah sebagai
serah-terima**:

```kotlin
// sebelum dihapus
fun TradingViewModel.clearPaperExitState(symbol: String, exchange: String) {
    PaperAutoTradeRuntime.clear(exchange, symbol)
    positionStore.setPaperEngineOwned(..., owned = false)
    positionStore.clearPaperFsmState(...)
    publishPaperExitSnapshot(..., null)
}
```

Tidak ada satu baris pun yang menyalakan kembali SL/TP jalur legacy. Padahal
`armPaperExitEngine` menulis `setAutoSellParams(enabled = false)` saat mengambil alih
(`PaperAutoTradeCoordinator.kt:385-390`; jalur auto-buy `:585` memakai `enabled = !p2OwnsExit`).
Jadi kalau fungsi itu **dipanggil**, hasilnya: mesin lepas, penanda lepas, progres FSM dibuang —
dan posisi paper **tanpa exit apa pun**. Memanggilnya akan lebih buruk daripada membiarkannya mati.

Implementasi yang benar butuh keputusan produk yang belum ada: setelah SL naik ke BE/trailing,
`activeStopLoss` bisa berada **di atas** entry, dan `PaperStopResolver.resolve` menganggap SL
seperti itu tidak valid (`signalSl < entry`). Level pengganti apa yang dipakai saat serah-terima
tidak bisa ditebak di dalam batch perbaikan bug.

## Yang berubah

| Berkas | Perubahan |
|---|---|
| `app/src/main/java/agu/analys/viewmodel/PaperAutoTradeCoordinator.kt` | `clearPaperExitState` **dihapus** (6 baris) |
| `AGENTS.md` | dua daftar hook dibersihkan (`markSold` / `finalizePaperExit` / posisi hilang); ditambah aturan turunan P7 |
| `docs/bugs/temuan_bugs_paper_trade.md` | P7 dipangkas ke §4; **P9 dicatat sebagai temuan hidup baru** |

Tidak ada perubahan perilaku: fungsi itu tidak pernah dipanggil, jadi tidak ada jalur runtime yang
kehilangan sesuatu.

## Temuan baru: P9 (bukan perbaikan, sengaja dicatat)

Saat memeriksa apakah serah-terima memang mustahil, ditemukan celah nyata yang **berbeda** dari P7:

1. arm menulis `auto_sell_enabled = false`;
2. sakelar "auto-exit paper" hanya menulis preferensi (`PaperAutoTradeSettings.kt:136` →
   `AppPreferences.kt:314`) — **tidak ada** yang menyalakan kembali auto-sell saat runtime;
3. akibatnya `paperEngineOwnsExit` menjadi `false` (jalur legacy boleh jalan), tetapi blok SL/TP
   legacy masih terkunci `updatedPos.isAutoSellEnabled` (`TradingViewModelAlerts.kt:244`) yang
   nilainya `false`. Yang tersisa hanya trailing;
4. setelah **restart**, `PaperRehydrate` mengembalikan `HandBackToLegacy(REASON_AUTO_EXIT_OFF)` dan
   justru **menyalakan** auto-sell dengan SL hasil `PaperStopResolver` (`:1004-1014`).

Jadi aksi pengguna yang sama (mematikan auto-exit) berakhir berbeda tergantung apakah app
di-restart. P9 dicatat lengkap di `docs/bugs/temuan_bugs_paper_trade.md` §3 dan **tidak**
diperbaiki di batch ini — perbaikannya menunggu keputusan level serah-terima di atas.

## Uji & verifikasi

Penghapusan kode mati tidak bisa dibuktikan lewat uji perilaku (tidak ada perilaku yang berubah).
Yang dilakukan:

- **Kompilasi + seluruh suite:** `./gradlew testDebugUnitTest` hijau — **78 kelas / 626 uji /
  0 gagal / 0 error / 1 dilewati**, sama persis dengan baseline batch 6d. Ini sekaligus bukti
  tidak ada pemanggil tersisa (kalau ada, kompilasi gagal).
- **`grep -rn "clearPaperExitState" app/src`** → **0 hasil** (sebelumnya 1: definisi).
- **Kontrol negatif:** tidak berlaku — tidak ada cabang yang diaktifkan/dimatikan. Dicatat apa
  adanya, bukan diklaim sebagai uji.

## Batas

- P9 **belum** diperbaiki. Jangan menganggap auto-exit yang dimatikan di tengah posisi sebagai
  jalur serah-terima yang aman.
- `docs/LAPORAN-p0-rehidrasi-paper-2026-10-13.md` dan `docs/LAPORAN-p44-persistensi-fsm-2026-10-15.md`
  **sengaja tidak diubah** — keduanya laporan historis dan memang benar untuk keadaan saat itu.
  Yang diperbarui hanya dokumen hidup: `AGENTS.md`, `workplan2.md`, `docs/bugs/*`.
