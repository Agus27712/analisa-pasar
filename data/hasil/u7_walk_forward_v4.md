# Validasi Walk-Forward (Jendela Bergulir)

- Direktori: `data/tokocrypto_v4`
- Config: `data/config/scalp_config_exit_full.json`
- Jendela: 30000 bar (±20 hari) | langkah 20000 bar (±13 hari)
- Split per jendela: train 70% / test 30%, pemanas 300 bar
- Friksi round-trip: **0.9688%**
- Jendela lolos: **0/9**
- **Verdict keseluruhan: TIDAK BERTAHAN**

## Ringkasan per jendela

| Jendela | Tanggal Mulai | Train n | Train avgR | Test n | Test WR | Test avgR | Pair+ | Rasio+ | Verdict |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 2026-08-12 | 85 | -0.1528 | 32 | 0.0 | -0.1748 | 0/1 | 0.0% | TIDAK LOLOS |
| 2 | 2026-08-12 | 88 | -0.1517 | 23 | 0.0 | -0.1704 | 0/1 | 0.0% | TIDAK LOLOS |
| 3 | 2026-08-12 | 178 | -0.1498 | 57 | 1.76 | -0.2022 | 0/2 | 0.0% | TIDAK LOLOS |
| 4 | 2026-08-26 | 77 | -0.1649 | 23 | 4.35 | -0.1615 | 0/1 | 0.0% | TIDAK LOLOS |
| 5 | 2026-08-26 | 67 | -0.1504 | 26 | 3.85 | -0.1625 | 0/1 | 0.0% | TIDAK LOLOS |
| 6 | 2026-08-26 | 155 | -0.1683 | 41 | 7.32 | -0.1854 | 0/2 | 0.0% | TIDAK LOLOS |
| 7 | 2026-09-09 | 76 | -0.1546 | 35 | 0.0 | -0.1955 | 0/1 | 0.0% | TIDAK LOLOS |
| 8 | 2026-09-09 | 70 | -0.158 | 26 | 3.85 | -0.1723 | 0/1 | 0.0% | TIDAK LOLOS |
| 9 | 2026-09-09 | 171 | -0.1634 | 69 | 10.15 | -0.1953 | 0/2 | 0.0% | TIDAK LOLOS |

## Agregat gabungan

| Metrik | Train | Test |
|---|---:|---:|
| Trade resolved | 967 | 332 |
| avg R terbobot | -0.1578 | -0.1849 |

- Total pair positif di semua jendela: 0

## Per pair per jendela

| Pair | Jendela | Tanggal | Train n | Train avgR | Test n | Test avgR |
|---|---|---|---:|---:|---:|---:|
| ADAUSDT | 3 | 2026-08-12 | 91 | -0.1518 | 26 | -0.2101 |
| ADAUSDT | 6 | 2026-08-26 | 78 | -0.1727 | 22 | -0.1941 |
| ADAUSDT | 9 | 2026-09-09 | 91 | -0.1567 | 36 | -0.1873 |
| BNBUSDT | 2 | 2026-08-12 | 88 | -0.1517 | 23 | -0.1704 |
| BNBUSDT | 5 | 2026-08-26 | 67 | -0.1504 | 26 | -0.1625 |
| BNBUSDT | 8 | 2026-09-09 | 70 | -0.1580 | 26 | -0.1723 |
| BTCUSDT | 1 | 2026-08-12 | 85 | -0.1528 | 32 | -0.1748 |
| BTCUSDT | 4 | 2026-08-26 | 77 | -0.1649 | 23 | -0.1615 |
| BTCUSDT | 7 | 2026-09-09 | 76 | -0.1546 | 35 | -0.1955 |
| DOGEUSDT | 3 | 2026-08-12 | 87 | -0.1478 | 31 | -0.1956 |
| DOGEUSDT | 6 | 2026-08-26 | 77 | -0.1638 | 19 | -0.1753 |
| DOGEUSDT | 9 | 2026-09-09 | 80 | -0.1710 | 33 | -0.2041 |
