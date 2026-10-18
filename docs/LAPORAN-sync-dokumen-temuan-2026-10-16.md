# Laporan Sinkronisasi Dokumen Temuan — 2026-10-16

**Tanggal:** 2026-10-16 · **Branch:** `patch` @ `547048d` · **Jenis:** **dokumen saja — nol perubahan kode**
**Pemicu:** owner meminta "perbarui docs terkait, hapus docs yang sudah selesai, perbarui docs yang
belum selesai, dan perbarui `AGENTS.md` supaya agen tidak perlu cek kode lagi karena sudah direview".

---

## 1. Apa yang dilakukan

| # | Aksi | Berkas |
|---|---|---|
| 1 | **Pangkas butir yang sudah ditutup** dari badan dokumen; pindahkan ke tabel "Sudah ditutup" (commit + pointer laporan) | `docs/laporan_temuan_bugs_notifikasi.md`, `temuan_bugs_jurnal_pnl.md`, `temuan_bugs_paper_trade.md` |
| 2 | **Perbarui butir yang masih hidup** dengan bukti terverifikasi ulang + nomor baris di `547048d` | idem |
| 3 | **Perjelas J10** dari "sudah selesai" (N10) ↔ "masih hidup" (judul dokumen) jadi **sebagian**, dan tuliskan sisa yang ditemukan | `temuan_bugs_jurnal_pnl.md` §2.1, `AGENTS.md` |
| 4 | **Hapus 3 stub DEPRECATED** (`workplan.md`, `checkpoint.md`, `checkpoint_progres.md`) dan semua rujukannya | root + `AGENTS.md` + `APP_CONTEXT.md` |
| 5 | **`AGENTS.md`**: `docs/bugs/` dipromosikan jadi **sumber kebenaran status**; aturan "agen tidak perlu cek kode lagi" ditulis eksplisit, lengkap dengan syarat kapan verifikasi ulang tetap perlu | `AGENTS.md` § Dokumen, § Folder temuan bug, § Aturan turunan J4 & N10 |
| 6 | Tambah §40 (jejak eksekusi) | `workplan2.md` |
| 7 | Tambah banner "snapshot" + pointer ke status terkini | `docs/BELUM-DIKERJAKAN-2026-10-14.md`, `docs/PANDUAN-LANJUT-AGEN.md` |

**Laporan batch 1–5f sengaja TIDAK dihapus** — semuanya adalah jejak audit yang dipakai untuk
memahami kenapa suatu perbaikan berbentuk seperti sekarang.

## 2. Status temuan setelah sinkronisasi

Semua status di bawah **diverifikasi ulang ke kode `patch` @ `547048d`** lalu direview owner.

| Area | Ditutup | Sebagian | Masih hidup |
|---|---|---|---|
| Notifikasi (N) | N1, N2, N3, N4, N5, N7, N10 | **N11** (klaim J6 tertutup; tabel tier & cap `ScalpingMtfEvaluator` belum) | N6 + N17, N8, N9, N12, N13, N14, N15, N16 |
| Jurnal & P&L (J) | J1, J2, J3, J4, J5, J6, J7 | **J10** (`capture` digerbang; `armPaperExitEngine` jalur beli paper manual masih mentah) | J8, J9 |
| Paper trading (P) | P1, P2, P3, P4, P5 | **P6** (jendela momentum satu sumber; `peak` masih tak pernah melihat high M1) | P7, P8 |

Ringkasan: **21 tertutup · 3 sebagian · 13 masih hidup.** Tiga temuan **kritis** di area paper
(P1–P3) dan seluruh temuan **kritis** jurnal (J1, J2) sudah tertutup.

## 3. Temuan baru yang muncul saat verifikasi (J10 — sisa)

Dokumen jurnal menulis dua kalimat yang saling bertentangan: judulnya "J4 + N10 (batch 5a) sudah
DIPERBAIKI; **J8–J10 masih hidup**", padahal baris J10 sendiri mengakui J10 = N10. Setelah dibaca
ulang ke kode:

- **Tertutup:** gerbang `SignalSymbolAttribution.matches(symbol, signal.marketSymbol)` di
  `TradeSignalSnapshot.capture` (`trading/TradeSignalSnapshot.kt:209`) + `attributedFields` di kedua
  jalur beli (`viewmodel/TradingViewModelSync.kt:114-121`, `:291-297`).
- **Belum:** `armPaperExitEngine` pada jalur **beli paper manual**
  (`viewmodel/TradingViewModelSync.kt:343-350`) masih mengoper
  `engine.signalState.value.stopLoss / targetPrice1 / targetPrice2` **mentah**.

Analisis dampak (tertulis di `temuan_bugs_jurnal_pnl.md` §2.1, dengan kehati-hatian agar tidak
dilebih-lebihkan):

- `stopLoss` disaring `PaperStopResolver.resolve` (`0 < SL < entry`), jadi SL pair lain yang jauh di
  atas entry ditolak → lantai friksi. SL yang kebetulan di bawah entry **dan** di bawah lantai
  friksi tetap dipakai — SL terlalu lebar.
- `tp1`/`tp2` **tidak** disaring. Mesin memakai `bar.high >= tp1` (partial TP + SL ke BE) dan `tp2`.
  TP1 pair lain yang di bawah entry posisi ini bisa memicu **jual sebagian pada tick pertama**.
- Jendelanya sama dengan J4: user berganti pair, `engine.signalState` masih berisi hasil pair lama.
- Perbaikannya murah: `paperSignal.stopLoss / targetPrice1 / targetPrice2` sudah ada di scope yang
  sama, tinggal pakai.

## 4. Koreksi angka yang ikut dicatat

Friksi FSM Tokocrypto USDT = **0,9735%** (bentuk multiplikatif, sama dengan `FeeSchedule`).
Angka **0,8226%** di dokumen audit awal tidak cocok dengan rumus mana pun dan sudah dikoreksi di
batch 4 (`d1097aa`). Angka ini kini dicatat di `temuan_bugs_jurnal_pnl.md` §3 dan `AGENTS.md`
supaya tidak muncul lagi sebagai "koreksi baru".

## 5. Angka test

**Tidak ada test yang dijalankan** — perubahan ini dokumen/markdown saja, nol berkas
`app/src/main` atau `app/src/test` tersentuh. `./gradlew :app:testDebugUnitTest` tidak dijalankan
karena tidak ada Android SDK di sandbox (lihat catatan pengukuran di masing-masing dokumen
temuan). Angka test yang tertulis di dokumen tetap berasal dari laporan batch 1–5f dan **tidak
berubah** karena tidak ada kode yang bergerak.

## 6. Berkas yang dihapus

| Berkas | Alasan |
|---|---|
| `workplan.md` | Stub "DEPRECATED", isinya hanya menunjuk ke `workplan2.md` |
| `checkpoint.md` | Stub "DEPRECATED" (275 byte) |
| `checkpoint_progres.md` | Stub "DEPRECATED" (275 byte), duplikat `checkpoint.md` |

Rujukan di `AGENTS.md` (§ Dokumen yang harus dibaca dulu, § Yang tidak boleh dilakukan agen) dan
`APP_CONTEXT.md` sudah disesuaikan; tidak ada rujukan tersisa (dicek dengan
`grep -rn 'checkpoint\.md\|checkpoint_progres\.md\|workplan\.md' --include='*.md'`).

## 7. Sisa pekerjaan (tidak dikerjakan di sini — dokumen saja)

Urutan yang disarankan, dari nilai ÷ risiko:

1. **J10 sisa** (3 baris) + **J9** (hapus `?: 75`) — kecil dan terlokalisasi.
2. **J8** — ganti `customPnlPct` dengan `FeeSchedule.netProfitPctFor` + kontrol negatif.
3. **P8**, lalu **P7** (implementasikan atau hapus kontrak `clearPaperExitState`).
4. **N13 + N14** satu paket (kategori & cooldown trailing), lalu **N15 + N16** di
   `TradingForegroundService`, **N17** di worker, **N8**, **N12**.
5. **N6** paling akhir — butuh keputusan desain (worker hanya mode aktif, atau instance lifecycle
   terpisah).
6. **P6 sisa** & **N11 sisa** keduanya butuh **keputusan produk** lebih dulu, bukan sekadar kode:
   umpan intrabar untuk `peak`, dan perlakuan tabel tier confidence.