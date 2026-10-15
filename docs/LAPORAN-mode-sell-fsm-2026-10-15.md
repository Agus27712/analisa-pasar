# LAPORAN — Mode SELL yang benar & frame FSM jadi utama

**Tanggal:** 2026-10-15
**Branch:** `patch`
**Berkas terkait:** `workplan2.md` §22, `docs/LAPORAN-seragam-pnl-basis-fsm-2026-10-15.md`

---

## 1. Masalah

Owner melaporkan: **"masih sama, UI buy dan sell."** Setelah §20 mode halaman detail memang sudah
otoritatif (hold → SELL), tetapi secara visual mode beli dan mode jual tetap terasa identik.

Akar penyebabnya ditemukan di `ui/screens/DetailChartScreen.kt` baris 316:

```kotlin
if (isScalping) {
    ScalpDeskCard(...)
    PaperTradeDeskCard(...)
}
```

Blok itu bergantung pada `isScalping` **saja** — **tidak melihat status kepemilikan koin**.
Akibatnya `SCALP DESK` dan `PAPER TRADE DESK` tampil sama persis entah pengguna sedang memegang
koin atau tidak. Jadi meski radar di bawahnya sudah berganti mode, bagian terbesar layar tetap
statis — itulah yang membuat kedua mode terasa sama.

## 2. Keputusan owner

| Aspek | Keputusan |
|---|---|
| Mode SELL | Sembunyikan `ScalpDeskCard`, `PaperTradeDeskCard`, **dan** `MarketConditionCard` ("KONDISI SCALPING") |
| Yang tetap tampil di mode SELL | `PaperExitFsmCard` (FSM), `DetailTechnicalDetailsSection` (indikator observasi), `SignalHistoryPanel` (log sinyal) |
| Posisi FSM | **Paling atas** — tepat setelah kartu grafik, sebelum radar jual |
| Kosmetik FSM | **Lengkap** |

## 3. Perubahan

| Titik | Berkas | Perubahan |
|---|---|---|
| Resolver murni | `model/PositionContext.kt` | `enum DetailScalpPanel {NONE, SCALPING_DESK, EXIT_FSM}` + `resolveDetailScalpPanel(isScalping, isHolding)` |
| Layar | `ui/screens/DetailChartScreen.kt` | blok `if (isScalping)` → `when (resolveDetailScalpPanel(...))`; FSM dipanggil langsung dari layar; `MarketConditionCard` dibungkus `if (!(isScalping && isHolding))` |
| Kosmetik | `ui/components/simulation/PaperExitFsmCard.kt` | frame per nilai, nilai beranimasi, kedip SL bergeser, stepper beranimasi, bar meluncur |
| Uji | `app/src/test/.../engine/DetailScalpPanelTest.kt` | **baru** — 6 kasus |

### 3.1 Kenapa tiga status, bukan dua

`NONE` dipisah dari `SCALPING_DESK`/`EXIT_FSM` supaya kasus "scalping nonaktif" bisa diuji
eksplisit. Dengan dua status saja, `isScalping = false` akan diam-diam memilih salah satu panel —
dan FSM exit bisa muncul di mode non-scalping tanpa ada yang menyadari.

### 3.2 Posisi manual tanpa mesin exit

Saat mode SELL tetapi `paperSnapshot == null` (posisi dibeli manual, tidak ada state machine exit),
kartu FSM **sengaja tidak dipaksa tampil**. Alasannya: `EmptyFsmState` berbicara soal *"auto-buy
menunggu sinyal yang lolos ambang"* — kalimat itu menyesatkan di mode jual, dan mengubahnya berarti
menyentuh copy komponen bersama yang juga dipakai layar Simulasi. Kontrol exit manual (SL/TP/trailing)
sudah tersedia di radar posisi di bawahnya.

### 3.3 Kosmetik — memakai bahasa visual yang sudah ada

Tidak ada gaya baru yang dikarang. Semua memakai komponen yang sudah ada di
`ui/animation/AppAnimations.kt`:

| Butir | Cara |
|---|---|
| Frame/tile per nilai | `FsmMetricTile` baru — grid 2×2, tiap metrik `Surface(TvSurface)` + border |
| Nilai beranimasi | `AnimatedMetricText` lewat helper `FsmValueText` |
| Badge net % | `AnimatedPercentageBadge` di tile "Net" |
| Kedip saat SL bergeser | `LaunchedEffect(activeStopLoss)` → flag 220 ms → `animateColorAsState` pada background + border baris "SL aktif" |
| Stepper fase beranimasi | `animateFloatAsState` ukuran titik, `animateColorAsState` warna titik & garis penghubung |
| Titik fase aktif berdenyut | `rememberLivePulseAlpha` + `Modifier.livePulse` |
| Bar posisi meluncur | `animateFloatAsState(tween(SLOW_MS))` untuk penanda harga & garis BE |
| Petir "SL sudah digeser" | ikut `livePulse` |

Kosmetik dipasang **tanpa syarat** (bukan di balik parameter baru). Alasannya: kartu yang sama juga
dipakai `TradeSimulationScreen.kt`, dan dua varian presentasi akan menambah divergensi. Katup hemat
baterai sudah ada di aplikasi — `PriceAnimationMode.STATIC` lewat `LocalPriceAnimationMode`
dihormati di seluruh kartu: animasi dan denyut dimatikan, nilai kembali jadi teks biasa.

## 4. Kontrol negatif

Aturan `isHolding` di `resolveDetailScalpPanel` **dibalik** (hold → `SCALPING_DESK`):

```
6 tests completed, 4 failed
```

Yang gagal: `sedang hold ... menampilkan fsm exit`, `tidak hold ... meja kerja entry`,
`panel selaras dengan mode beli jual halaman detail`, `meja kerja entry tidak pernah tampil
bersamaan dengan mode jual`.

Dua yang tetap lulus adalah kasus `isScalping = false` — memang **tidak boleh** terpengaruh oleh
pembalikan aturan kepemilikan. Ini sekaligus membuktikan `NONE` benar-benar independen.

Aturan dipulihkan → suite kembali hijau.

## 5. Angka (diukur dari XML)

| | |
|---|---|
| Sebelum §22 | 429 lulus · 0 gagal · 1 dilewati (56 kelas) |
| Sesudah §22 | **435 lulus · 0 gagal · 1 dilewati (57 kelas)** |
| Tambahan | **+6 test, +1 kelas** |
| `assembleDebug` | sukses · APK **27.877.128 byte** (27,9 MB desimal) |

## 6. Yang TIDAK dikerjakan

- Tidak menyentuh `MIN_FRICTION_MULTIPLE`, `PairEdgeGate`, atau ambang gerbang uang riil.
- Tidak mengubah copy `EmptyFsmState` (dipakai bersama layar Simulasi).
- Tidak menambah Compose UI test — proyek belum punya infrastrukturnya; uji ditaruh pada resolver murni.

## 7. Risiko yang diterima

`MarketConditionCard` ("KONDISI SCALPING") disembunyikan di mode SELL, sehingga info struktur
MTF di baliknya ikut hilang. Ini keputusan sadar owner: keputusan masuk sudah tidak relevan saat
koin sedang dipegang, dan radar posisi + `DetailTechnicalDetailsSection` tetap menampilkan
indikator yang dibutuhkan untuk memutuskan keluar.

## 8. Verifikasi manual yang masih diperlukan

Kosmetik dan perpindahan panel **belum diuji di perangkat fisik** — sandbox tidak punya emulator.
Yang perlu dipastikan sendiri:

1. Buka koin yang **tidak** dipegang → `SCALP DESK` + `PAPER TRADE DESK` tampil, `KONDISI SCALPING` tampil.
2. Buka koin yang **sedang dipegang** → kedua frame itu hilang, `FSM EXIT PAPER TRADE` tampil paling atas.
3. Perhatikan saat SL bergeser (BE / trailing naik) → baris "SL aktif" berkedip dan petir berdenyut.
4. Setel mode animasi ke **Statis** di setelan → semua gerak berhenti, angka tetap akurat.
