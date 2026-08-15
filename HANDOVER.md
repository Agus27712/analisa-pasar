# HANDOVER — analisa-pasar v1.2.0

**Tanggal:** 2026-08-16  
**Repo:** Agus27712/analisa-pasar  
**Branch stabil:** `main` (= `backup` setelah merge)  
**Versi:** versionName **1.2.0** · versionCode **8**

---

## 1. Apa aplikasi ini

Android app analisis crypto Indodax/IDR:

- Data **realtime** Indodax (REST + WebSocket)
- Watchlist (auto + manual)
- Detail chart + indikator + market structure
- Mode **Scalping (BUY-only)** dan **Swing**
- MTF: 1H bias → 15M setup → 1M trigger
- Fee-aware net R:R
- AI pendukung (Groq / Gemini) — bukan penentu market condition
- Update APK dari GitHub Releases

**Prinsip:** UI membantu keputusan; entry dilakukan user di Indodax. Dilarang data dummy di produksi.

---

## 2. Versi & riwayat singkat

| versionName | versionCode | Catatan |
|-------------|-------------|--------|
| 1.1.7 | 5 | Jangan dipakai ulang |
| 1.1.8 | 6 | Baseline sebelumnya |
| 1.1.9 | 7 | Websocket + buy-only fee blueprint |
| **1.2.0** | **8** | **Release ini** |

---

## 3. Perubahan utama di 1.2.0

### Engine scalping (longgar, tetap proteksi)
- Bias 1H: EMA20 > EMA50 + harga > EMA20 (struktur/GC = **bonus skor**, bukan wajib)
- Setup 15M: EMA searah saja
- RSI trigger: **38–62** (sebelumnya 40–55)
- Extended blok: 1M > 72 / HTF > 75
- Net R:R minimum **1.2** setelah fee (sebelumnya 1.5)
- Volume trigger ≥ 1.0×
- BUY-only; short tidak ada

### Data
- `IndodaxMarketWebSocket` → `wss://ws3.indodax.com/ws/` channel `chart:tick-{pair}`
- Tick live + candle 1m dari tick
- REST tetap bootstrap/history 15M & 1H

### UI (mengikuti mockup, data real)
- **Watchlist:** rank 01, ikon aset (Coil CDN + fallback huruf), badge aktivitas hijau/kuning/abu, bar volume/momentum, mini sparkline 24H dari tick real, harga anim ~160ms
- **Detail:** ChartLayout 280dp, Progress Entry 0/3–3/3, Important Levels + AREA OBSERVASI, Recommendation **BUY READY** (Entry/SL/TP/fee/net R:R)
- **Settings:** kartu SCALPING BUY MODE / SWING, fee, AI key, update, cache
- **Navbar:** tidak diubah di sesi ini

### CI
- Push ke `backup` → compile debug Kotlin (bukan APK otomatis)
- Checkout locked ke `github.ref` (tidak hardcode main)
- Release APK: manual setelah stabil

---

## 4. File penting

| Area | Path |
|------|------|
| Scalping MTF | `engine/scalping/ScalpingMtfEvaluator.kt` |
| WebSocket | `service/IndodaxMarketWebSocket.kt` |
| Watchlist card | `ui/components/dashboard/WatchlistCoinCard.kt` |
| Ikon / sparkline | `AssetAvatar.kt`, `MiniSparkline.kt` |
| Progress MTF | `ui/components/detail/ProgressEntryCard.kt` |
| BUY READY | `ui/components/detail/RecommendationCard.kt` |
| Level penting | `ui/components/detail/ImportantLevelsCard.kt` |
| Animasi harga | `ui/animation/AppAnimations.kt` |
| Chart tinggi | `ui/components/detail/ChartLayout.kt` |
| Settings | `ui/screens/SettingsScreen.kt` |
| Model MTF | `model/TradingViewModels.kt` → `ScalpingMtfSnapshot` |

---

## 5. Aturan pengembangan

1. Data market = Indodax real saja  
2. Mockup = layout/warna/UX, **bukan** angka produksi  
3. AI = pendukung, bukan sumber Market Condition  
4. Fee masuk kalkulasi net R:R (Settings, tanpa re-release untuk ubah fee)  
5. Branch kerja eksperimen: `backup` → merge ke `main` jika stabil  
6. versionCode selalu naik; jangan reuse 5/6/7  

---

## 6. Cara build & release

```bash
git checkout main && git pull
./gradlew assembleDebug    # test lokal
# Release: GitHub Actions Release workflow (manual) atau assembleRelease + tag v1.2.0
```

Update in-app membaca GitHub Releases repo `Agus27712/analisa-pasar` by versionCode.

---

## 7. Known gaps (bukan blocker 1.2.0)

- Sparkline = sintesis dari high/low/price/change 24H (bukan series candle penuh per card)
- Ikon CDN bisa gagal offline → fallback huruf
- Bottom nav style mockup tidak diadopsi
- Scalping tetap selektif di market sideways (by design)

---

## 8. Prioritas berikutnya (opsional)

1. Runtime test BUY READY frekuensi setelah longgar threshold  
2. Sparkline dari candle history singkat per pair (jika cache tersedia)  
3. Tombol “Buka di Indodax” konsisten di Detail  
4. Release signed APK + tag `v1.2.0`  

---

## 9. Definition of done 1.2.0

- [x] versionName 1.2.0 / versionCode 8  
- [x] MTF longgar + fee-aware  
- [x] Watchlist ikon + sparkline + aktivitas  
- [x] BUY READY card + Progress/Level  
- [x] Animasi harga ~160ms  
- [x] main diselaraskan dengan backup  
- [ ] APK release manual + validasi di device (oleh maintainer)

**Maintainer:** Agus27712  
**Handover oleh:** sesi development Grok · 2026-08-16
