# scalp_replay.py

Offline replay engine (entry + exit modes). Pure Python 3 stdlib, tanpa dependensi.

Source sudah terbaca langsung (tidak perlu restore/assemble). Jalankan dari root repo:

```bash
python3 tools/scalp_replay.py --pair XRPUSDT --data data/tokocrypto_v2 \
  --config data/config/scalp_config_exit_full.json --assume-orderbook-ok \
  --out data/hasil/hasil_xrp_exit_full.json
```

stdout = ringkasan (summary + exit_reasons). Trade lengkap ditulis ke `--out`.

## Satuan R (sejak fix 2026-10-09)

`R = profit_net% / risiko_kotor%`. Profit net = setelah buy+sell fee dan 2x slippage.
Semua exit (SL, BE, trail, TP1 partial, TP2, early) memakai satuan yang sama.
SL kena = -1R ditambah biaya, bukan -1R flat.

Break-even = harga impas net (profit net 0), bukan setengah biaya.

Catatan: hasil sebelum 2026-10-09 terlalu optimis (SL dihitung -1R tanpa fee).
Angka di `SCALP_EXIT.md` harus dihitung ulang dengan tool ini.

## Exit modes

Lihat `SCALP_EXIT.md` dan `data/config/scalp_config_exit_*.json`:
`classic` | `tp1_be` | `early` | `trail` | `full`
