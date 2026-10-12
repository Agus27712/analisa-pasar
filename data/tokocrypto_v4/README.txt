Dataset candle Tokocrypto untuk fase P3 (lab hipotesis entry).

Sumber   : API Tokocrypto (type 1 market data) via tools/fetch_tokocrypto_klines.py
Diambil  : 2026-10-12 (WIB)
Rentang  : 2026-08-12 14:46 UTC .. 2026-10-12 00:49 UTC (sekitar 60 hari)
Pair     : ADAUSDT, BNBUSDT, BTCUSDT, DOGEUSDT, ETHUSDT, SOLUSDT, TRXUSDT, XRPUSDT
Interval : 1m (87.000 bar/pair), 15m (5.999 bar/pair), 1h (1.999 bar/pair)
Kolom    : open_time_ms,open,high,low,close,volume  (hanya candle yang sudah CLOSED)
Kualitas : 0 celah antar bar M1, 0 duplikat timestamp (diverifikasi saat P3)

Perintah pengambilan ulang:
  python3 tools/fetch_tokocrypto_klines.py --pages 87 --out data/tokocrypto_v4

Catatan: menjalankan ulang perintah di atas akan menghasilkan jendela 60 hari yang
BERGESER (selalu 60 hari terakhir), jadi angkanya tidak akan identik dengan hasil P3
yang tersimpan di data/hasil/p3_entry_hypothesis_lab.*. Folder ini disimpan justru
supaya hasil P3 bisa direproduksi persis.

Dipakai oleh:
  python3 tools/entry_hypothesis_lab.py --data data/tokocrypto_v4 \
      --out-json data/hasil/p3_entry_hypothesis_lab.json \
      --out-md   data/hasil/p3_entry_hypothesis_lab.md
