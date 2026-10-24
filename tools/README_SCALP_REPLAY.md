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

## Tool turunan (2026-10-24)

### `exit_mode_sweep.py`

Membandingkan 5 mode exit pada kandidat P0 yang sama. Gate dievaluasi sekali per pair lalu
dipakai ulang untuk semua mode/hold. Dua metode dilaporkan: **sequential** (paritas CLI,
jumlah trade bisa berbeda) dan **same-entry** (N identik, isolasi exit).

```bash
python3 tools/exit_mode_sweep.py \
  --pairs ADAUSDT,DOGEUSDT,XRPUSDT --data data/tokocrypto_v4 \
  --config data/config/scalp_config.json \
  --modes classic,tp1_be,early,trail,full --holds 90,240,420 \
  --cache-dir /tmp/friction_sweep_cache \
  --out data/hasil/exp_exit_mode_sweep.json
```

Kontrol wajib: `classic` 90m harus menghasilkan **620 trade / WR 14,84% / avg R −0,1569**.
Bila tidak, cache tidak sah — jalankan dengan `--rebuild`. Hasil penuh:
`docs/exp/exp_exit_mode_sweep_ADA-DOGE-XRP.md`.

### `entry_percentile.py`

Mengukur posisi harga entry terhadap jendela 1/3/7 hari: persentil close, posisi dalam rentang
high-low, return close maju, serta MFE/MAE kotor. Memakai kandidat `classic` yang sama (620
entry), tanpa mengubah gate.

```bash
python3 tools/entry_percentile.py \
  --pairs ADAUSDT,DOGEUSDT,XRPUSDT --data data/tokocrypto_v4 \
  --config data/config/scalp_config.json \
  --cache-dir /tmp/friction_sweep_cache \
  --out data/hasil/exp_entry_price_percentile.json
```

Hasil: `docs/exp/exp_entry_price_percentile_ADA-DOGE-XRP.md`. Ini diagnostik, bukan gate.

### Uji

```bash
python3 tools/test_scalp_replay_parity.py
```

30 test; mencakup kontrol `classic`, mode baru, dan validasi persentil/kuintil.
