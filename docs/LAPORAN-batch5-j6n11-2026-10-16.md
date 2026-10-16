# Laporan Batch 5e — J6 & N11: klaim statistik hardcoded diganti deskripsi bergate sampel

**Tanggal:** 2026-10-16
**Branch:** `patch`
**Temuan:** J6 (`docs/bugs/temuan_bugs_jurnal_pnl.md` §6) = N11 (`docs/bugs/temuan_bugs_notifikasi.md`)

## Ringkasan

`SignalLogScreen.kt` mencetak, tanpa syarat apa pun:

> "Skor keyakinan tinggi (≥80%) menunjukkan rasio kemenangan tertinggi dan drawdown terendah pada eksekusi real-time."

Kalimat itu melanggar aturan repo sendiri ("jangan menulis di UI bahwa sinyal punya winrate/expectancy
historis tanpa data cukup"), dan lebih berbahaya karena (N11) tier ≥80 nyaris mustahil terisi sinyal
scalping pra-entry — jadi yang "dibuktikan" kalimat itu bukan kualitas keyakinan, melainkan
"apakah sinyal sudah READY". Landasan statistiknya juga baru layak setelah J1 (PnL kotor → bersih)
dan J2 (predikat W/L tumpang tindih) — keduanya sudah ditutup di batch sebelumnya.

## Perbaikan

Resolver murni baru `app/src/main/java/agu/analys/model/ConfidenceTierNote.kt`:

| Kondisi | Keluaran |
|---|---|
| `totalLogs == 0` | `null` — **tidak ada teks yang dicetak** |
| `completedLogs < 10` (= `SetupStats.MIN_RELIABLE_SAMPLE`) | catatan kuning "Sampel belum cukup: N sinyal selesai dari M total (minimal 10)…", `isReliable = false` |
| sampel cukup | catatan netral: "Basis: N sinyal selesai (TP/SL tercapai) dari M total di bursa ini. Win rate hanya dari sinyal yang sudah selesai; PnL bersih fee & slippage." |
| tier tanpa sinyal | fakta data tambahan: "K dari 3 tier belum terisi sinyal." |

`SignalLogScreen` merender bar info hanya bila resolver tidak `null`; teks & warnanya dari resolver.

## Uji & kontrol negatif

- **Uji baru:** `app/src/test/java/agu/analys/model/ConfidenceTierNoteTest.kt` — **11 kasus**:
  tanpa data (dua bentuk), sampel kurang, semua TRACKING, tepat di ambang, teks basis menyebut
  angka & "fee & slippage", tier kosong (2 dari 3, 1 dari 3, semua terisi), teks tidak lagi memuat
  frase klaim lama, dan konsistensi threshold dengan `SetupStats.MIN_RELIABLE_SAMPLE`.
- **Kontrol negatif:** resolver dikembalikan ke perilaku lama (tanpa gerbang sampel, teks klaim asli,
  tanpa fakta tier) ⇒ **8 dari 11 uji GAGAL**. Tiga yang tetap lulus memang tidak membedakan versi
  (threshold = konstanta; "tepat di ambang" relatable di dua versi; "semua tier terisi" tidak
  menyebut tier kosong di dua versi). Dicatat apa adanya, tidak diklaim sebagai bukti.
- **Angka terukur (dari XML):** 72 kelas / 571 uji → **73 kelas / 582 uji**, 0 gagal, 1 dilewati.

## Batas perbaikan

- Hanya satu kalimat di kartu yang diganti. **Tabel tier itu sendiri dan cap confidence
  `ScalpingMtfEvaluator` (akar N11) tidak diubah** — keduanya perlu temuan & uji tersendiri.
- `SignalLogScreen` tidak diuji otomatis; yang diuji resolver murninya.
- Angka "10 sinyal selesai" adalah ambang yang sudah dipakai layar setup akurasi, bukan ambang baru.
