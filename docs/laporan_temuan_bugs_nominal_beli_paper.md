# Temuan bugs — nominal beli paper (2026-10-26)

## Status

| ID | Temuan | Status | Penjaga |
|---|---|---|---|
| M1 | Form beli paper memakai nominal default literal Rp 50.000 yang dipakai apa adanya sebagai nominal kuotasi — pada pair USDT berarti **$50.000** | ✅ DITUTUP 2026-10-26 | `PaperBuyNominalSourceTest` |
| M2 | `coerceAtLeast(minNominalQuote)` menaikkan nominal di bawah minimum secara diam-diam | ✅ DITUTUP 2026-10-26 | `PaperBuyNominalSourceTest` |
| M3 | Dialog "Atur Limit" menganggap saldo 0 sebagai "cukup" (`availableIdr <= 0 ||`) sehingga tombol PASANG BELI aktif walau saldo kosong | ✅ DITUTUP 2026-10-26 | `PaperBuyNominalSourceTest` |
| M4 | `PriceFormatter.defaultOrderNominal` (helper konversi nominal default) punya 0 pemanggil — kontrak palsu | ✅ DIHAPUS 2026-10-26 | `PaperBuyNominalSourceTest` |

Seluruh temuan ditutup pada hari yang sama; berkas keluar dari `docs/bugs/` dan
menjadi laporan ini (2026-10-26).
`docs/laporan_temuan_bugs_nominal_beli_paper.md` (lihat § Riwayat di sana).

## Yang sudah benar — jangan diutak-atik

- **Auto-buy paper sudah benar:** `PaperAutoTradeConfig.fixedIdrAmount` (Rp 100.000)
  dikonversi lewat kurs di `plan()` — ini bug §18.2 yang sudah ditutup; JANGAN ikut
  diubah. Form beli manual **tidak membaca** config auto-buy (keputusan owner: tanpa
  default sama sekali).
- **Engine order tetap gerbang terakhir:** `SimulationOrderEngine` menolak order bila
  saldo kuotasi kurang. Perbaikan UI di atas bukan pengganti gerbang itu.
- `minOrderNominal` (Rp 10.000 / ekuivalen USDT, 0 bila kurs belum ada) dipakai dan
  benar — jangan diganti dengan kurs tebakan.
- Chip cepat nominal (rekomendasi risiko / % saldo) adalah **aksi eksplisit user** —
  bukan default; tetap boleh ada.

## Riwayat

- **2026-10-26** — Owner melaporkan "tombol beli paper kuotasi USDT default buy-nya
  $50.000". Akar: `WaitingEntryRadarCard.kt` state `selectedNominal` berisi literal
  `50000.0` (Rupiah) tanpa key pair dan tanpa konversi — dipakai langsung sebagai
  nominal kuotasi (`qty = nominal / harga`, `invested = nominal`). Keputusan owner:
  **tanpa nominal default sama sekali** — user mengisi sendiri, tombol hanya mengirim
  perintah beli bila nominal valid. Perbaikan: nominal awal 0 dengan key
  `(baseAsset, quoteAsset)`; gerbang murni `resolveBuyNominalGate`
  (`util/BuyNominalGate.kt`: EMPTY / BELOW_MIN / OVER_BALANCE / OK) menjaga tombol;
  coerce diam-diam dibuang; dialog limit mulai kosong + gerbang saldo jujur;
  `defaultOrderNominal` dihapus. Penjaga: `BuyNominalGateTest` (7 test) +
  `PaperBuyNominalSourceTest` (5 test, source-scan tanpa komentar) — kontrol negatif
  terbukti: literal dikembalikan → uji GAGAL. Rincian: `workplan2.md` §77.
