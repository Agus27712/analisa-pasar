# Validasi Walk-Forward (Jendela Bergulir)

- Direktori: `data/tokocrypto_v4`
- Config: `data/config/scalp_config_exit_full.json`
- Jendela: 30000 bar (±20 hari) | langkah 20000 bar (±13 hari)
- Split per jendela: train 70% / test 30%, pemanas 300 bar
- Friksi round-trip: **0.9688%**
- Jendela lolos: **0/3**
- **Verdict keseluruhan: TIDAK BERTAHAN**

## Ringkasan per jendela

| Jendela | Tanggal Mulai | Train n | Train avgR | Test n | Test WR | Test avgR | Pair+ | Rasio+ | Verdict |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 2026-08-12 | 714 | -0.1455 | 214 | 1.87 | -0.1837 | 0/8 | 0.0% | TIDAK LOLOS |
| 2 | 2026-08-26 | 616 | -0.1682 | 210 | 7.62 | -0.1737 | 0/8 | 0.0% | TIDAK LOLOS |
| 3 | 2026-09-09 | 652 | -0.1577 | 247 | 7.69 | -0.1794 | 0/8 | 0.0% | TIDAK LOLOS |

## Agregat gabungan

| Metrik | Train | Test |
|---|---:|---:|
| Trade resolved | 1982 | 671 |
| avg R terbobot | -0.1566 | -0.179 |

- Total pair positif di semua jendela: 0

## Per pair per jendela

| Pair | Jendela | Tanggal | Train n | Train avgR | Test n | Test avgR |
|---|---|---|---:|---:|---:|---:|
| ADAUSDT | 1 | 2026-08-12 | 91 | -0.1518 | 26 | -0.2101 |
| ADAUSDT | 2 | 2026-08-26 | 78 | -0.1727 | 22 | -0.1940 |
| ADAUSDT | 3 | 2026-09-09 | 91 | -0.1567 | 36 | -0.1873 |
| BNBUSDT | 1 | 2026-08-12 | 88 | -0.1517 | 23 | -0.1704 |
| BNBUSDT | 2 | 2026-08-26 | 67 | -0.1504 | 26 | -0.1624 |
| BNBUSDT | 3 | 2026-09-09 | 70 | -0.1580 | 26 | -0.1723 |
| BTCUSDT | 1 | 2026-08-12 | 85 | -0.1528 | 32 | -0.1748 |
| BTCUSDT | 2 | 2026-08-26 | 77 | -0.1649 | 23 | -0.1615 |
| BTCUSDT | 3 | 2026-09-09 | 76 | -0.1546 | 35 | -0.1955 |
| DOGEUSDT | 1 | 2026-08-12 | 87 | -0.1478 | 31 | -0.1956 |
| DOGEUSDT | 2 | 2026-08-26 | 77 | -0.1638 | 19 | -0.1753 |
| DOGEUSDT | 3 | 2026-09-09 | 80 | -0.1710 | 33 | -0.2041 |
| ETHUSDT | 1 | 2026-08-12 | 84 | -0.1356 | 31 | -0.1636 |
| ETHUSDT | 2 | 2026-08-26 | 81 | -0.1687 | 33 | -0.1660 |
| ETHUSDT | 3 | 2026-09-09 | 85 | -0.1497 | 33 | -0.1866 |
| SOLUSDT | 1 | 2026-08-12 | 94 | -0.1370 | 25 | -0.1881 |
| SOLUSDT | 2 | 2026-08-26 | 77 | -0.1809 | 27 | -0.1755 |
| SOLUSDT | 3 | 2026-09-09 | 83 | -0.1527 | 33 | -0.1810 |
| TRXUSDT | 1 | 2026-08-12 | 82 | -0.1608 | 23 | -0.1609 |
| TRXUSDT | 2 | 2026-08-26 | 84 | -0.1590 | 27 | -0.1594 |
| TRXUSDT | 3 | 2026-09-09 | 80 | -0.1577 | 26 | -0.1572 |
| XRPUSDT | 1 | 2026-08-12 | 103 | -0.1301 | 23 | -0.2083 |
| XRPUSDT | 2 | 2026-08-26 | 75 | -0.1843 | 33 | -0.1943 |
| XRPUSDT | 3 | 2026-09-09 | 87 | -0.1616 | 25 | -0.1317 |
