# scalp_replay.py

Offline replay engine (entry + exit modes). Pure Python 3 stdlib.

## Restore (setelah clone)

Full script disimpan terkompresi di `tools/_hex/scalp_replay.*.hex`.

```bash
# Opsi A — assemble eksplisit (disarankan sekali setelah clone)
python tools/assemble_scalp_replay.py
# menulis tools/scalp_replay.py penuh (~44KB, hash match sumber)

# Opsi B — bootstrap otomatis
# tools/scalp_replay.py saat ini = stub; saat dijalankan, restore dari _hex lalu re-exec
python tools/scalp_replay.py --pair XRPUSDT --data data/tokocrypto_v2 \
  --config data/config/scalp_config_exit_full.json --assume-orderbook-ok --out out.json
```

Verified 2026-10-09: assemble dari 5 chunk hex → 44236 bytes, `def resolve_trade` + `EARLY_PROFIT` OK.

## Exit modes

Lihat `SCALP_EXIT.md` dan `data/config/scalp_config_exit_*.json`:
`classic` | `tp1_be` | `early` | `trail` | `full`
