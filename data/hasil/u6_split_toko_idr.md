# Validasi Out-of-Sample (Train/Test Split)

- Direktori: `data/tokocrypto_live_idr`
- Config: `data/config/scalp_config_exit_full_idr.json`
- Split: train 70% / test 30%, pemanas 300 bar
- Friksi round-trip: **0.8144%**
- **Verdict: TIDAK LOLOS**

## Agregat

| Metrik | Train | Test |
|---|---:|---:|
| Trade resolved | 88 | 6 |
| avg R terbobot | 0.1323 | -0.0558 |
| WR terbobot % | 95.46 | 83.33 |
| Pair bersinyal | 10 | 4 |
| Pair avg R > 0 | 9 | 3 |

- Pair layak (test n ≥ 0): rasio positif **0.0%**, balik tanda 0

## Per pair

| Pair | Train n | Train WR | Train avgR | Test n | Test WR | Test avgR | Test PF |
|---|---:|---:|---:|---:|---:|---:|---:|
| ADAIDR | 7 | 100.0 | 0.1291 | 2 | 100.0 | 0.2365 | ∞ |
| SUIIDR | 17 | 100.0 | 0.1534 | 1 | 100.0 | 0.1963 | ∞ |
| SOLIDR | 11 | 100.0 | 0.0788 | 1 | 100.0 | 0.0782 | ∞ |
| ASTERIDR | 1 | 100.0 | 0.1962 | 0 | 0.0 | 0.0000 | 0.00 |
| BNBIDR | 0 | 0.0 | 0.0000 | 0 | 0.0 | 0.0000 | 0.00 |
| BTCIDR | 5 | 100.0 | 0.1491 | 0 | 0.0 | 0.0000 | 0.00 |
| ETHIDR | 4 | 100.0 | 0.0869 | 0 | 0.0 | 0.0000 | 0.00 |
| ONDOIDR | 11 | 90.9 | 0.1538 | 0 | 0.0 | 0.0000 | 0.00 |
| TAOIDR | 0 | 0.0 | 0.0000 | 0 | 0.0 | 0.0000 | 0.00 |
| USDCIDR | 0 | 0.0 | 0.0000 | 0 | 0.0 | 0.0000 | 0.00 |
| USDTIDR | 0 | 0.0 | 0.0000 | 0 | 0.0 | 0.0000 | 0.00 |
| WLDIDR | 8 | 100.0 | 0.3915 | 0 | 0.0 | 0.0000 | 0.00 |
| XRPIDR | 13 | 100.0 | 0.1471 | 0 | 0.0 | 0.0000 | 0.00 |
| TKOIDR | 11 | 72.7 | -0.0689 | 2 | 50.0 | -0.5410 | 0.07 |
