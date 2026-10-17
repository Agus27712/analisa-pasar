# LAPORAN Perbaikan Temuan Audit A & B

**Tanggal:** 14 Oktober 2026  
**Branch:** `patch`  
**Test:** 358 lulus · 0 gagal · 1 dilewati (sama dengan baseline)

---

## Fase 1 — Perbaikan Label & Tampilan (Tanpa Perubahan Perilaku)

| Item | Perbaikan | File |
|---|---|---|
| A1 | Label `"24h Vol (quote)"` di TradeLogDetailDialog (TradeLogExporter sudah benar sejak S6) | `TradeLogDetailDialog.kt:330` |
| A6 | `bbMiddle` fallback `ema20` → `null` saat Bollinger tidak tersedia | `TradeSignalSnapshot.kt:241` |
| A8 | Parameter `exchange: String? = null` ditambahkan; fallback tebakan quote asset dipertahankan untuk backward compat | `TradeLogExporter.kt` |
| A9 | Kategori F (Pipeline Scalping) ditambah di export markdown | `TradeLogExporter.kt` |
| A10 | Label `"Skor Konfidensi"` → `"Skor Konfidensi (skor engine, dibatasi tahap)"` | `TradeLogExporter.kt` |

## Fase 2 — Perbaikan Jurnal (Perubahan Perilaku)

| Item | Perbaikan | File | Dampak |
|---|---|---|---|
| B5 | Confidence disimpan sejak entry, bukan MAX sepanjang pelacakan | `SignalLogRepository.kt:107` | Lebih jujur untuk evaluasi akurasi |
| B3 | PnL expired dihitung dari `exitPrice`, bukan `peakPrice` | `SignalLogRepository.kt:389-390` | Menghilangkan bias optimistis sistematis |
| B4 | `resolveLogManually`: clamp `max(1.5)/min(-1.5)` dihapus; PnL dihitung dari `exitPrice` atau `null` | `SignalLogRepository.kt:355` | Besaran PnL jujur, bukan fiktif |
| B6 | Label `Net ...% (gross)` eksplisit di SignalLogScreen | `SignalLogScreen.kt:1589-1593` | Pembaca bisa bedakan gross vs net |
| B1 | Fallback mengarang trade dihapus; `recordSell` return early jika tidak ada record | `TradeHistoryRecorder.kt` | Tidak ada trade palsu di statistik |

## Kontrol Negatif (Bukti Perubahan Bukan No-Op)

```bash
# B5: confidence seharusnya tidak berubah setelah update tracking
git diff app/src/main/java/agu/analys/database/SignalLogRepository.kt | grep -A2 "updatedConfidence"
# Berubah dari max(confidence, primaryLog.confidence) → primaryLog.confidence

# B3: approxPnl seharusnya tidak lagiakai peakPrice
git diff app/src/main/java/agu/analys/database/SignalLogRepository.kt | grep -B1 -A3 "approxPnl"
# Berubah dari peakPrice/troughPrice → exitPrice/realizedPnlPct

# B4: clamp max(1.5)/min(-1.5) seharusnya hilang
git diff app/src/main/java/agu/analys/database/SignalLogRepository.kt | grep -B1 -A3 "realizedPnlPct"
# Berubah dari clamp → kalkulasi exitPrice

# B1: fallback sell seharusnya hilang
git diff app/src/main/java/agu/analys/database/TradeHistoryRecorder.kt | grep -B1 -A3 "fallback"
# Berarang dari fallback → return@launch
```

## Catatan

- B2 (sudah selesai di P0–P2) tidak disentuh.
- B7 (peak/trough sampling) sebagian sudah tertutup P0-6 via label `Peak (candle)`; tidak diubah lebih lanjut.
- Semua perubahan backward-compatible (parameter baru ada di akhir dengan default value).
