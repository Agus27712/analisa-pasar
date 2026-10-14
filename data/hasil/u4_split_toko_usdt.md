# Validasi Out-of-Sample (Train/Test Split)

> ⚠️ **VERDICT "LOLOS" DI BERKAS INI TIDAK SAH (ditemukan 2026-10-14).**
> Berkas ini dibuat sebelum commit `703c3b6`. Saat itu `summarize()` **mengeluarkan** trade yang
> tidak menyentuh SL/TP/early/trail dalam jendela *lookahead* (`UNRESOLVED`) dari seluruh
> statistik — pada data 40 pair itu membuang **±83% sinyal**. Dengan trade yang sama dihitung
> jujur (ditutup di akhir jendela), hasilnya berbalik menjadi **avg R ≈ −0,17** dan
> **0 dari 40 pair positif**. Jangan pakai angka di bawah sebagai bukti edge.
> Bukti kontrol: `docs/LAPORAN-temuan-akuntansi-replay-2026-10-14.md` §3.
> Berkas ini sengaja **tidak dihapus** sebagai jejak bahwa bias ini pernah terjadi.

- Direktori: `data/tokocrypto_live_usdt`
- Config: `data/config/scalp_config_exit_full.json`
- Split: train 70% / test 30%, pemanas 300 bar
- Friksi round-trip: **0.9688%**
- **Verdict: LOLOS**

## Agregat

| Metrik | Train | Test |
|---|---:|---:|
| Trade resolved | 1835 | 669 |
| avg R terbobot | 0.035 | 0.0216 |
| WR terbobot % | 95.42 | 94.02 |
| Pair bersinyal | 32 | 30 |
| Pair avg R > 0 | 28 | 23 |

- Pair layak (test n ≥ 13): rasio positif **84.6%**, balik tanda 5

## Per pair

| Pair | Train n | Train WR | Train avgR | Test n | Test WR | Test avgR | Test PF |
|---|---:|---:|---:|---:|---:|---:|---:|
| WLDUSDT | 66 | 100.0 | 0.0950 | 14 | 100.0 | 0.1090 | ∞ |
| SOLUSDT | 15 | 100.0 | 0.0855 | 2 | 100.0 | 0.0938 | ∞ |
| ENAUSDT | 94 | 96.8 | 0.0403 | 13 | 100.0 | 0.0888 | ∞ |
| AVAXUSDT | 53 | 98.1 | 0.0685 | 6 | 100.0 | 0.0835 | ∞ |
| SUIUSDT | 58 | 98.3 | 0.0642 | 9 | 100.0 | 0.0828 | ∞ |
| ADAUSDT | 32 | 100.0 | 0.0533 | 19 | 100.0 | 0.0814 | ∞ |
| ZECUSDT | 48 | 97.9 | 0.0713 | 12 | 100.0 | 0.0738 | ∞ |
| NEARUSDT | 119 | 95.8 | 0.0273 | 36 | 100.0 | 0.0694 | ∞ |
| FILUSDT | 67 | 94.0 | 0.0075 | 26 | 96.2 | 0.0583 | 2.31 |
| UNIUSDT | 76 | 97.4 | 0.0647 | 4 | 100.0 | 0.0539 | ∞ |
| ZROUSDT | 114 | 95.6 | 0.0336 | 31 | 93.5 | 0.0520 | 1.70 |
| APTUSDT | 66 | 97.0 | 0.0456 | 34 | 97.1 | 0.0496 | 2.46 |
| BNBUSDT | 3 | 100.0 | 0.0458 | 2 | 100.0 | 0.0483 | ∞ |
| PUMPUSDT | 90 | 97.8 | 0.0628 | 22 | 95.5 | 0.0413 | 1.78 |
| WUSDT | 52 | 96.2 | 0.0448 | 82 | 95.1 | 0.0408 | 1.72 |
| XRPUSDT | 30 | 96.7 | 0.0462 | 1 | 100.0 | 0.0248 | ∞ |
| SUSDT | 64 | 95.3 | 0.0318 | 58 | 93.1 | 0.0215 | 1.27 |
| LINKUSDT | 26 | 100.0 | 0.0684 | 1 | 100.0 | 0.0210 | ∞ |
| QNTUSDT | 167 | 87.4 | -0.0270 | 23 | 95.7 | 0.0152 | 1.30 |
| HYPEUSDT | 6 | 100.0 | 0.1096 | 2 | 100.0 | 0.0140 | ∞ |
| STRKUSDT | 82 | 90.2 | -0.0220 | 90 | 92.2 | 0.0138 | 1.15 |
| MUBARAKUSDT | 169 | 88.8 | -0.0262 | 50 | 92.0 | 0.0094 | 1.10 |
| FETUSDT | 55 | 100.0 | 0.0751 | 20 | 95.0 | 0.0040 | 1.07 |
| BTCUSDT | 3 | 100.0 | 0.1695 | 0 | 0.0 | 0.0000 | 0.00 |
| ETHUSDT | 7 | 100.0 | 0.0685 | 0 | 0.0 | 0.0000 | 0.00 |
| FDUSDUSDT | 0 | 0.0 | 0.0000 | 0 | 0.0 | 0.0000 | 0.00 |
| PAXGUSDT | 0 | 0.0 | 0.0000 | 0 | 0.0 | 0.0000 | 0.00 |
| RLUSDUSDT | 0 | 0.0 | 0.0000 | 0 | 0.0 | 0.0000 | 0.00 |
| TRXUSDT | 0 | 0.0 | 0.0000 | 0 | 0.0 | 0.0000 | 0.00 |
| USD1USDT | 0 | 0.0 | 0.0000 | 0 | 0.0 | 0.0000 | 0.00 |
| USDCUSDT | 0 | 0.0 | 0.0000 | 0 | 0.0 | 0.0000 | 0.00 |
| UUSDT | 0 | 0.0 | 0.0000 | 0 | 0.0 | 0.0000 | 0.00 |
| XAUTUSDT | 0 | 0.0 | 0.0000 | 0 | 0.0 | 0.0000 | 0.00 |
| ONDOUSDT | 58 | 98.3 | 0.0598 | 15 | 93.3 | -0.0067 | 0.91 |
| ICPUSDT | 49 | 98.0 | 0.0630 | 13 | 92.3 | -0.0259 | 0.71 |
| CFXUSDT | 18 | 100.0 | 0.0788 | 29 | 86.2 | -0.0509 | 0.68 |
| PLUMEUSDT | 59 | 100.0 | 0.0651 | 30 | 86.7 | -0.0763 | 0.50 |
| TAOUSDT | 44 | 100.0 | 0.0801 | 8 | 87.5 | -0.0937 | 0.35 |
| RSRUSDT | 27 | 100.0 | 0.0773 | 14 | 85.7 | -0.1017 | 0.38 |
| DOGEUSDT | 18 | 88.9 | -0.0459 | 3 | 66.7 | -0.3470 | 0.10 |
