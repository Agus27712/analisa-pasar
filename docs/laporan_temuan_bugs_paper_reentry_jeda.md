# Laporan temuan bugs: jeda entri-ulang auto-buy paper (2026-10-26)

## Tabel status

| ID | Temuan | Status | Diperbaiki di |
|---|---|---|---|
| R1 | Tidak ada jeda antar siklus sell → buy pada auto-buy paper; sinyal BUY berikut langsung memicu entri ulang pada pair yang sama | **TERTUTUP** (2026-10-26) | commit fix ini — gerbang `PaperReentryCooldown` + config `reentryCooldownMinutes` (default 15 menit) |

## Riwayat

### 2026-10-26 — R1 ditemukan, diukur, dan ditutup

**Laporan owner:** mode scalping tidak mempunyai cooldown; setelah sell paper terpicu,
order buy terpicu kembali — dugaan karena koin sedang trending naik. Diterima 25/10/2026
sekitar 21:12 WIB pada WINUSDT.

**Ukuran (lihat `docs/exp/exp_autobuy_reentry_winusdt.md` untuk perintah dan tabel penuh):**

- Data 2.999 candle M1 WINUSDT (Tokocrypto) diunduh dengan `tools/fetch_tokocrypto_klines.py`.
  Di jendela 20:00–23:00 WIB harga naik sampai +4,75% (puncak 21:40 WIB) lalu koreksi.
- Replay `tools/scalp_replay.py` pada data itu: 10 trade, dan **3 dari 10 entri terjadi
  60 detik setelah sell** (satu bar M1) — 25/10 08:38, 25/10 20:47, dan 26/10 05:11 WIB.
- Posisi #6 (entry 20:47, exit time-stop 22:17) mencakup waktu keluhan 21:12 WIB.

**Sebab:** `tryPaperAutoBuy` hanya mencegah posisi dobel (`existing.isHolding`) dan menjaga
batas jumlah posisi bersamaan — **tidak ada jeda per pair setelah sell**. Begitu
`finalizePaperExit` menutup posisi, sinyal BUY berikutnya dari evaluator (yang membaca
kondisi masih trending) langsung sah dibeli ulang. Pada pair yang sedang naik kuat itu
dua friksi round-trip dibayar beruntun tanpa evaluasi baru.

**Perbaikan (paper saja; jalur uang riil tidak disentuh):**

1. `engine/scalping/PaperReentryCooldown.kt` — resolver **murni** yang memutuskan
   `Allow`/`Wait` dari (waktu sell terakhir, waktu sekarang, jeda menit). Logika jeda
   satu sumber, teruji tanpa Android.
2. `PaperAutoTradeConfig.reentryCooldownMinutes` — default **15 menit**; `0` menonaktifkan.
   Sinkron ke `AppPreferences` (kunci `paper_auto_reentry_cooldown_min_v1`, fallback 15).
3. `SpotPositionStore.markSold(isReal = false)` menulis `…_last_sold_at`; dibaca
   `lastPaperSoldAt(symbol, exchange)` — persisten, jeda tetap berlaku setelah restart.
   Posisi uang riil tidak dicatat dan tidak dibatasi jeda.
4. `tryPaperAutoBuy` menolak beli selama jeda, dengan log alasan yang bisa dibaca
   (judul `PaperAutoBuy`, `⏳ … jeda entri-ulang … sisa ±N mnt`).

**Verifikasi:**

- Uji baru 13/13 hijau: `PaperReentryCooldownTest` (6), `SpotPositionStorePaperLastSoldTest` (4),
  `PaperAutoBuyReentryCooldownWiringTest` (3).
- Kontrol negatif dua titik: gerbang `cfg.reentryAllowed` dilepas → uji wiring GAGAL;
  `editor.putLong(…_last_sold_at)` dilepas → uji store GAGAL. Keduanya dipulihkan.
- Lingkup scalping + store + storage + wiring: 339 test, 0 gagal.
- Paritas replay `tools/test_scalp_replay_parity.py`: 30/30 OK (config mirror replay tidak
  berubah; jeda hidup di app saja, senada dengan TTL yang juga tidak direplay).

**Yang tetap perlu diperhatikan (tidak menjadi bagian dari fix ini):**

- Jeda TIDAK mengubah gate entry, lantai friksi, atau mode exit — masalah edge yang
  sudah diukur (avg R masih negatif) tidak berubah oleh fix ini.
- Dampak jeda pada frekuensi/jumlah trade paper terlihat praktis pada paper berikutnya.
