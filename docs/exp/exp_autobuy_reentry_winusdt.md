# exp_autobuy_reentry_winusdt — Jeda entri-ulang auto-buy paper WINUSDT (2026-10-26)

**Status: DIAGNOSTIK (pengukuran insiden, bukan eksperimen strategi)**

Pertanyaan owner: setelah sell paper terpicu, order buy terpicu kembali tanpa jeda —
dugaan karena koin sedang trending naik. Diterima 25/10/2026 sekitar 21:12 WIB pada
WINUSDT. Diukur dengan data bursa terbaru, bukan dari ingatan.

## Perintah

```sh
python3 tools/fetch_tokocrypto_klines.py --pairs WINUSDT --pages 3 --intervals 1m --out /tmp/opencode/tokowin
python3 tools/fetch_tokocrypto_klines.py --pairs WINUSDT --pages 2 --intervals 15m,1h --out /tmp/opencode/tokowin/data
python3 tools/fetch_tokocrypto_klines.py --pairs BTCUSDT --pages 3 --intervals 1m --out /tmp/opencode/tokowin/data
python3 tools/scalp_replay.py --pair WINUSDT --data /tmp/opencode/tokowin/data --assume-orderbook-ok
```

Dataset: 2.999 candle M1 WINUSDT (24/10 11:29 → 26/10 13:27 WIB, hanya candle tutup),
fee USDT Tokocrypto all-in 0,4044%/sisi, slippage 0,08%, lantai friksi SL 5,8128%,
veto BTC aktif dari BTCUSDT_1m.csv.

## Kondisi harga di sekitar insiden (25/10 WIB)

- Jendela 20:00–23:00 WIB: low 6,291e-05, high 6,75e-05, open 6,354e-05, close 6,416e-05.
- Dari basis harga 21:00 WIB: 20:50 +4,29%, puncak 21:40 +4,75%, lalu melemah ke +0,4% di 22:00.
- Bentuk datanya sesuai deskripsi keluhan: harga **naik kuat lalu koreksi** di jendela
  21:00–22:00 WIB.

## Hasil replay engine (config default CLI, mode classic)

10 trade. Jarak antara satu entri dan exit trade sebelumnya:

| # | setup | entry WIB | exit WIB | jarak entri dari exit sebelumnya |
|---|---|---|---|---|
| 1 | TREND_PULLBACK | 25/10 07:07 | 08:37 | — |
| 2 | BREAKOUT_RETEST | 25/10 08:38 | 10:08 | **+60 detik** |
| 3 | BREAKOUT_RETEST | 25/10 10:36 | 12:06 | +1.680 detik |
| 4 | LIQUIDITY_SWEEP | 25/10 13:02 | 14:32 | +3.360 detik |
| 5 | BREAKOUT_RETEST | 25/10 19:16 | 20:46 | +17.040 detik |
| 6 | TREND_PULLBACK | 25/10 20:47 | 22:17 | **+60 detik** |
| 7 | TREND_PULLBACK | 25/10 22:45 | 26/10 00:15 | +1.680 detik |
| 8 | TREND_PULLBACK | 26/10 03:40 | 05:10 | +12.300 detik |
| 9 | BREAKOUT_RETEST | 26/10 05:11 | 06:41 | **+60 detik** |
| 10 | BREAKOUT | 26/10 10:41 | 12:11 | +14.400 detik |

- WR 10%, avg R −0,2759, 10/10 exit TIMEOUT — selaras dengan hasil seluruh pair
  (`exp_p0_gatekeeper_ADA-DOGE-XRP.md`): edge masih negatif.
- **3 dari 10 entri terjadi hanya 60 detik setelah sell** (jarak terpendek yang mungkin:
  satu bar M1). Contoh terdekat dengan keluhan: trade #5 exit 20:46 WIB, entri ulang
  20:47 WIB; posisi itu masih terbuka pada 21:12 WIB lalu keluar via time-stop di 22:17.
- Pola entri-ulang seketika muncul di dua hari berbeda dan pada setup berbeda —
  ini perilaku struktural engine, bukan kejadian satu kali.

## Kesimpulan

1. Saat pengukuran ini dibuat, engine **belum punya jeda entri-ulang sama sekali**.
   Gerbang `tryPaperAutoBuy` hanya mencegah posisi dobel (cek `existing.isHolding`) dan
   membatasi jumlah posisi bersamaan — tidak ada jeda per pair setelah sell. Begitu
   sinyal BUY berikutnya datang, order beli langsung boleh terkirim.
2. Keluhan owner terbukti benar secara mekanisme, dan harganya nyata: dua friksi
   round-trip dibayar beruntun tanpa penilaian baru, khususnya ketika harga sedang naik.
   Perbaikannya adalah **jeda entri-ulang** (detail di `workplan2.md` §73 dan
   `docs/laporan_temuan_bugs_paper_reentry_jeda.md`) — bukan gate entry baru.

## Batasan

- Replay memakai config CLI default (mode classic). Slider/setting yang dipakai app
  pada saat kejadian bisa berbeda, sehingga waktu sell/beli di app bisa bergeser dari
  tabel di atas. Mekanismenya (entri-ulang tanpa jeda) tetap terbukti: 3 dari 10 entri
  berjarak 60 detik dari sell sebelumnya.
- Jeda entri-ulang yang dipasang hidup di app saja (senada dengan TTL 5 candle yang
  juga tidak direplay); tabel di atas masih dihitung **tanpa jeda**. Dampak jeda pada
  frekuensi trade belum diukur — akan terlihat pada paper trade berikutnya.
