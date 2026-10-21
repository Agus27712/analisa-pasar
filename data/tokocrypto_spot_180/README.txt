Dataset candle Tokocrypto (Spec Scalping Spot v3)

Sumber    : API Tokocrypto https://www.tokocrypto.site/api/v3 (type 1) dan https://www.tokocrypto.com/open/v1 (type 3)
Diambil   : 2026-10-21T04:15:07Z (UTC)
Jendela   : 2026-04-23 12:09 .. 2026-10-20 12:09 UTC
Interval  : 15m, 1h
Pair      : 41 (lihat _selection.json untuk kriteria dan angka 24 jam)
Kolom     : open_time_ms,open,high,low,close,volume  (hanya candle yang sudah CLOSED)

Perintah pengambilan ulang:
  /usr/bin/python3 tools/fetch_tokocrypto_klines.py --select file --file data/tokocrypto_spot_180/_pairs.txt --quote USDT --intervals 15m,1h --start-ms 1776946140657 --end-ms 1792498140657 --out data/tokocrypto_spot_180 --resume --sleep 1.0 --write-readme

Catatan:
- Pair dipilih dari daftar resmi Tokocrypto (common/symbols), bukan dari exchangeInfo gateway.
- Statistik 24 jam untuk pemilihan berasal dari endpoint Tokocrypto; bila tidak terbaca,
  tool memakai snapshot cadangan dan mencatatnya di _manifest.json.
- Candle selalu diunduh dari Tokocrypto. Tool ini tidak memanggil Binance.
- Saat --resume dipakai, daftar pair dibekukan dari _selection.json supaya universe tidak
  berubah di tengah pengukuran. Pakai --reselect hanya bila memang ingin memilih ulang.
- _manifest.json memuat jumlah bar, celah, duplikat, SHA-256 tiap CSV, dan kriteria pemilihan.

Kualitas:
  ZROUSDT        15m  bar=17280  celah=0    duplikat=0
  ZROUSDT        1h   bar=4320   celah=0    duplikat=0
  NEARUSDT       15m  bar=17280  celah=0    duplikat=0
  NEARUSDT       1h   bar=4320   celah=0    duplikat=0
  MUBARAKUSDT    15m  bar=17280  celah=0    duplikat=0
  MUBARAKUSDT    1h   bar=4320   celah=0    duplikat=0
  PUMPUSDT       15m  bar=17280  celah=0    duplikat=0
  PUMPUSDT       1h   bar=4320   celah=0    duplikat=0
  WUSDT          15m  bar=17280  celah=0    duplikat=0
  WUSDT          1h   bar=4320   celah=0    duplikat=0
  STRKUSDT       15m  bar=17280  celah=0    duplikat=0
  STRKUSDT       1h   bar=4320   celah=0    duplikat=0
  ENAUSDT        15m  bar=17280  celah=0    duplikat=0
  ENAUSDT        1h   bar=4320   celah=0    duplikat=0
  QNTUSDT        15m  bar=17280  celah=0    duplikat=0
  QNTUSDT        1h   bar=4320   celah=0    duplikat=0
  PLUMEUSDT      15m  bar=17280  celah=0    duplikat=0
  PLUMEUSDT      1h   bar=4320   celah=0    duplikat=0
  APTUSDT        15m  bar=17280  celah=0    duplikat=0
  APTUSDT        1h   bar=4320   celah=0    duplikat=0
  SUSDT          15m  bar=17280  celah=0    duplikat=0
  SUSDT          1h   bar=4320   celah=0    duplikat=0
  FETUSDT        15m  bar=17280  celah=0    duplikat=0
  FETUSDT        1h   bar=4320   celah=0    duplikat=0
  WLDUSDT        15m  bar=17280  celah=0    duplikat=0
  WLDUSDT        1h   bar=4320   celah=0    duplikat=0
  FILUSDT        15m  bar=17280  celah=0    duplikat=0
  FILUSDT        1h   bar=4320   celah=0    duplikat=0
  BTCUSDT        15m  bar=17280  celah=0    duplikat=0
  BTCUSDT        1h   bar=4320   celah=0    duplikat=0
  ETHUSDT        15m  bar=17280  celah=0    duplikat=0
  ETHUSDT        1h   bar=4320   celah=0    duplikat=0
  SOLUSDT        15m  bar=17280  celah=0    duplikat=0
  SOLUSDT        1h   bar=4320   celah=0    duplikat=0
  XRPUSDT        15m  bar=17280  celah=0    duplikat=0
  XRPUSDT        1h   bar=4320   celah=0    duplikat=0
  BNBUSDT        15m  bar=17280  celah=0    duplikat=0
  BNBUSDT        1h   bar=4320   celah=0    duplikat=0
  TRXUSDT        15m  bar=17280  celah=0    duplikat=0
  TRXUSDT        1h   bar=4320   celah=0    duplikat=0
  XAUTUSDT       15m  bar=17280  celah=0    duplikat=0
  XAUTUSDT       1h   bar=4320   celah=0    duplikat=0
  DOGEUSDT       15m  bar=17280  celah=0    duplikat=0
  DOGEUSDT       1h   bar=4320   celah=0    duplikat=0
  LINKUSDT       15m  bar=17280  celah=0    duplikat=0
  LINKUSDT       1h   bar=4320   celah=0    duplikat=0
  HBARUSDT       15m  bar=17280  celah=0    duplikat=0
  HBARUSDT       1h   bar=4320   celah=0    duplikat=0
  ADAUSDT        15m  bar=17280  celah=0    duplikat=0
  ADAUSDT        1h   bar=4320   celah=0    duplikat=0
  NVDABUSDT      15m  bar=12553  celah=0    duplikat=0
  NVDABUSDT      1h   bar=3139   celah=0    duplikat=0
  GNSUSDT        15m  bar=17280  celah=0    duplikat=0
  GNSUSDT        1h   bar=4320   celah=0    duplikat=0
  ALTUSDT        15m  bar=17280  celah=0    duplikat=0
  ALTUSDT        1h   bar=4320   celah=0    duplikat=0
  GTCUSDT        15m  bar=17280  celah=0    duplikat=0
  GTCUSDT        1h   bar=4320   celah=0    duplikat=0
  PHAUSDT        15m  bar=17280  celah=0    duplikat=0
  PHAUSDT        1h   bar=4320   celah=0    duplikat=0
  BEAMXUSDT      15m  bar=17280  celah=0    duplikat=0
  BEAMXUSDT      1h   bar=4320   celah=0    duplikat=0
  BMTUSDT        15m  bar=17280  celah=0    duplikat=0
  BMTUSDT        1h   bar=4320   celah=0    duplikat=0
  ERAUSDT        15m  bar=17280  celah=0    duplikat=0
  ERAUSDT        1h   bar=4320   celah=0    duplikat=0
  SOXSBUSDT      15m  bar=7969   celah=0    duplikat=0
  SOXSBUSDT      1h   bar=1993   celah=0    duplikat=0
  BATUSDT        15m  bar=17280  celah=0    duplikat=0
  BATUSDT        1h   bar=4320   celah=0    duplikat=0
  METISUSDT      15m  bar=17280  celah=0    duplikat=0
  METISUSDT      1h   bar=4320   celah=0    duplikat=0
  REZUSDT        15m  bar=17280  celah=0    duplikat=0
  REZUSDT        1h   bar=4320   celah=0    duplikat=0
  AUSDT          15m  bar=17280  celah=0    duplikat=0
  AUSDT          1h   bar=4320   celah=0    duplikat=0
  IOUSDT         15m  bar=17280  celah=0    duplikat=0
  IOUSDT         1h   bar=4320   celah=0    duplikat=0
  TIAUSDT        15m  bar=17280  celah=0    duplikat=0
  TIAUSDT        1h   bar=4320   celah=0    duplikat=0
  COMPUSDT       15m  bar=17280  celah=0    duplikat=0
  COMPUSDT       1h   bar=4320   celah=0    duplikat=0
