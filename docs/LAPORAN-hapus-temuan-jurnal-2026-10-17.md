# Laporan — Berkas temuan jurnal dihapus (semua J1–J10 tertutup)

**Tanggal:** 2026-10-17
**Branch:** `patch`
**Tindakan:** `docs/bugs/temuan_bugs_jurnal_pnl.md` **dihapus** (perintah owner)

## Alasan

Berkas itu sudah tidak berisi temuan hidup. Status terakhirnya, sesudah batch 6a–6c:

| Status | Temuan |
|---|---|
| ✅ Ditutup | **J1–J10 — seluruhnya** |

Batch penutupnya: 3a (J1, J2) · 4 (J7) · 5a (J4 + N10) · 5b (J3) · 5e (J6 + N11) · 5f (J5) ·
6a (J10 sisa, `PaperArmSignal`) · 6b (J9, `TradeSignalConfidence`) · 6c (J8, `RealTradePnlResolver`).

Dokumen yang hanya berisi "tidak ada temuan" lebih berbahaya daripada tidak ada dokumen: pembaca
berikutnya membuka berkas, membaca peta jalur yang nomor barisnya sudah bergeser, lalu mengira
masih ada pekerjaan di sana. Jejak auditnya sudah lengkap di laporan per batch dan di riwayat git.

## Yang diubah

| Berkas | Perubahan |
|---|---|
| `docs/bugs/temuan_bugs_jurnal_pnl.md` | **dihapus** (`git rm`) |
| `AGENTS.md` | daftar `docs/bugs/` kini dua berkas; butir jurnal ditandai ~~dihapus~~ + larangan membuat ulang; catatan angka friksi 0,9735% dipertahankan |
| `docs/PANDUAN-LANJUT-AGEN.md` | tabel status disinkronkan (J1–J10 masuk baris "sudah ditutup"; P7/P8/N9 ikut dikoreksi) |
| `docs/laporan_temuan_bugs_notifikasi.md` | catatan lintas-berkas menunjuk laporan batch, bukan berkas yang sudah hilang |

**Sengaja tidak diubah:** `docs/LAPORAN-batch3-*` … `docs/LAPORAN-batch6-j*` dan `workplan2.md`
§§ lama. Semuanya catatan historis — pada saat ditulis, berkas itu memang ada. Menulis ulang
sejarah hanya akan membuat laporan tidak lagi mencerminkan apa yang terjadi.

## Dampak

Tidak ada perubahan kode. Tidak ada uji yang dijalankan karena tidak ada perilaku yang berubah;
suite terakhir (batch 6e) tetap **78 kelas / 626 uji / 0 gagal / 1 dilewati**.

Rujukan yang masih menyebut berkas itu ada di laporan historis dan di `workplan2.md` § lama —
sengaja dibiarkan. Untuk menemukan isinya: `git log --follow -- docs/bugs/temuan_bugs_jurnal_pnl.md`.

## Berikutnya

Fokus pindah ke **`docs/laporan_temuan_bugs_notifikasi.md`**. Urutan yang disarankan dokumen itu
sendiri: **N13 + N14** (berpasangan — "satu peristiwa, satu pengiriman"), lalu **N15 + N16**
(berpasangan, keduanya di `TradingForegroundService`), lalu N17, N8, N12, dan terakhir N6 (butuh
desain) serta sisa N11 (butuh keputusan produk).
