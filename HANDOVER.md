# HANDOVER — analisa-pasar v1.2.1

**Tanggal:** 2026-08-16  
**Repo:** Agus27712/analisa-pasar  
**Branch stabil:** `main` (= `backup`)  
**Versi:** **1.2.1** · versionCode **8**

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
|-------------|-------------|---------|
| 1.1.7 | 5 | Jangan dipakai ulang |
| 1.1.8 | 6 | Baseline sebelumnya |
| 1.1.9 | 7 | Websocket + buy-only fee blueprint |
| 1.2.0 | 8 | Release sebelumnya |
| **1.2.1** | **8** | **Baseline polishing saat ini** |

> Catatan: versionName 1.2.1 dipertahankan sesuai edit manual `build.gradle.kts`. versionCode tetap 8 pada baseline ini; release berikutnya wajib menaikkan versionCode.

---

## 3. Perubahan utama yang menjadi baseline

### Engine scalping
- RSI 38–62
- Setup EMA saja
- Net R:R minimum 1.2
- Extended lebih longgar
- BUY-only

### Data
- WebSocket `wss://ws3.indodax.com/ws/`
- REST history
- Data produksi wajib berasal dari Indodax real

### UI
- Watchlist dengan rank, ikon, aktivitas, sparkline
- Progress entry 0/3
- Level + AREA OBSERVASI
- BUY READY card
- Settings mode cards
- Animasi harga sekitar 160 ms
- Navbar tidak diubah

### Update APK
- Update melalui GitHub Releases
- Kegagalan download/update harus terlihat oleh user
- Error HTTP, file APK tidak valid/kosong, installer gagal, dan kebutuhan izin instalasi tidak boleh gagal secara diam-diam

---

## 4. Aturan pengembangan

1. Data = Indodax real
2. Mockup = layout/hierarchy, bukan sumber angka market
3. AI bukan penentu market condition
4. Fee di Settings masuk net R:R
5. Eksperimen di `backup` → validasi → merge `main`
6. versionCode selalu naik untuk release berikutnya
7. Jangan mengubah engine hanya untuk mengejar tampilan mockup
8. Dashboard dan Settings dipoles berdasarkan mockup yang disepakati

---

## 5. File kunci

- `ScalpingMtfEvaluator.kt`
- `IndodaxMarketWebSocket.kt`
- `WatchlistCoinCard.kt`
- `AssetAvatar.kt`
- `MiniSparkline.kt`
- `ProgressEntryCard.kt`
- `RecommendationCard.kt`
- `ImportantLevelsCard.kt`
- `AppAnimations.kt`
- `ChartLayout.kt`
- `SettingsScreen.kt`

---

## 6. Build

```bash
git checkout main && git pull
./gradlew assembleDebug
```

Release: workflow Release manual / tag sesuai version release.

---

## 7. Status polishing

Baseline source `main` dan `backup` sudah diselaraskan pada v1.2.1.

Target polishing berikutnya:
- Dashboard/Watchlist dibuat lebih faithful terhadap mockup
- Settings dibuat lebih faithful terhadap mockup
- Presentation layer saja; engine dan sumber data real tetap
- Validasi build Debug sebelum merge

---

**Maintainer:** Agus27712
