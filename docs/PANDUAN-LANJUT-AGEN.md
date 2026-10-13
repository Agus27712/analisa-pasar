# PANDUAN LANJUT AGEN — analisa-pasar

> **Baca ini dulu.** Dokumen ini adalah titik masuk untuk agen/engineer berikutnya: status terkini,
> apa yang sudah selesai, apa berikutnya, dan **perangkap yang sudah pernah ditemukan** supaya tidak
> diulang. Terakhir diperbarui: **2026-10-13**.

---

## 1. Status terkini

| | |
|---|---|
| Branch kerja | **`patch`** (owner me-review di sini dulu; `main` hanya menerima merge) |
| Titik masuk kode | package `agu.analys`, modul tunggal `:app` |
| Unit test | **364 lulus · 0 gagal · 1 dilewati** (50 kelas) |
| APK debug | 26,5 MB (`app/build/outputs/apk/debug/app-debug.apk`) |
| Kesiapan uang riil | **BELUM** — lihat §5 |

**Cara mengukur angka di atas sendiri (jangan percaya ingatan, termasuk dokumen ini):**

```bash
export JAVA_HOME=/root/.sdkman/candidates/java/17.0.20-amzn
export GRADLE_USER_HOME=/tmp/gradle-home
./gradlew testDebugUnitTest
python3 -c "
import glob,xml.etree.ElementTree as ET
t=f=s=n=0
for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    r=ET.parse(p).getroot(); n+=1
    t+=int(r.get('tests',0)); f+=int(r.get('failures',0))+int(r.get('errors',0)); s+=int(r.get('skipped',0))
print('kelas',n,'| test',t,'| gagal',f,'| dilewati',s)"
```

**Mengukur baseline dengan jujur** (pola yang sudah dipakai beberapa kali di repo ini):

```bash
git stash push -u -m wip      # simpan pekerjaan
rm -rf app/build/test-results/testDebugUnitTest
./gradlew testDebugUnitTest   # hitung baseline
git stash pop                 # kembalikan pekerjaan
```

---

## 2. Peta pekerjaan — apa yang sudah selesai

| # | Pekerjaan | Berkas kunci | Bukti |
|---|---|---|---|
| §9 | **Lantai risiko berbasis friksi** (`min_friction_multiple = 6,0`, `target_net_rr = 2,0`) | `ScalpingConfig.kt`, `ScalpingRiskEngine.kt` | `workplan2.md` §9 |
| §9 | Uji universe 30 hari × 40 pair, gate out-of-sample | `tools/split_validate.py` | `data/hasil/u3_*.json`, `u4_*.md` |
| §9 | **Gerbang edge pair** (MFE ≥ 2× friksi) | `PairEdgeGate.kt`, `tools/pair_gate.py` | `data/config/pair_gate.json` |
| §11 | **UI scalping baru** (radar vs setup tidak sinkron) | `ScalpReadiness.kt`, `ScalpDeskCard.kt`, `PaperTradeDeskCard.kt` | `ScalpReadinessTest.kt` |
| §12 | **Rehidrasi state machine exit paper** setelah restart | `PaperRehydrate.kt`, `SpotPositionStore.kt` | `PaperRehydrateTest.kt` |
| §13 | **Akar `EARLY_ENTRY`** — stage tinggi wajib punya pola | `ScalpingMtfEvaluator.kt`, `SignalLifecycleManager.kt` | `workplan2.md` §13 |
| §14 | **Kesiapan multi-horizon** (scalp/intraday/swing) + papan UI | `engine/readiness/*`, `ui/components/readiness/*` | `HorizonReadinessEngineTest.kt` |

Semua di atas **sudah masuk `main`** kecuali §14 (ada di `patch`).

---

## 3. Arsitektur ringkas (yang perlu diketahui sebelum mengubah apa pun)

```
MarketTick/Candle  ──►  LearningTradingEngine  ──►  ScalpingMtfEvaluator ─┐
   (per exchange)          (routing per              SwingEvaluator       ├─► AISignalState
                            StrategyMode)             IntradayEvaluator   ─┘        │
                                                                                    ▼
                                                                    SignalLifecycleManager
                                                                     (IDLE→DETECTED→…)
                                                                                    │
   MtfCacheManager (cache memori) ──► HorizonReadinessEngine ──► ReadinessBoard      │
             │                                                        │              │
             └────────────► PaperAutoTradeCoordinator ────────────────┘              │
                              (auto buy/sell paper)                                  │
                                                                                     ▼
                                                              TradingViewModel (Facade, banyak berkas)
                                                                                     │
                                                                            Compose screens
```

- **`TradingViewModel` adalah facade** yang dipecah ke banyak berkas (`TradingViewModelSync.kt`,
  `TradingViewModelAlerts.kt`, `TradingViewModelReadiness.kt`, …). Menambah state berarti menambah
  properti di `TradingViewModel.kt` **dan** logika di berkas ekstensinya — extension tidak bisa
  menyimpan state.
- **Tanpa Hilt.** Semua ViewModel dibuat manual di `MainActivity.kt`.
- **Tanpa Navigation-Compose.** Navigasi = enum `AppScreen` + `AnimatedContent` di `MainActivity`,
  dengan helper `openXxx()` di `TradingViewModelNavigation.kt`.
- **Teks UI ditulis langsung dalam Bahasa Indonesia** di Compose. `strings.xml` hanya berisi
  `app_name`.

**Menambah layar baru (3 langkah):**

1. tambah konstanta di `enum AppScreen` (`model/TradingViewModels.kt`);
2. tambah cabang `when (screen)` di `MainActivity.kt` + import;
3. tambah `fun TradingViewModel.openXxx()` di `viewmodel/TradingViewModelNavigation.kt`.

---

## 4. Berikutnya — prioritas dan alasannya

Diurutkan berdasarkan **nilai dibagi risiko**. Jangan mengerjakan yang bawah sebelum yang atas
selesai, kecuali ada instruksi eksplisit.

### P4.1 — Walk-forward antar-bulan (tertinggi)

**Masalah:** validasi keluar-sampel saat ini hanya **satu potongan** (train 70% / test 30% menurut
waktu). Satu potongan bisa kebetulan menguntungkan.

**Kerjakan:** perluas `tools/split_validate.py` menjadi beberapa jendela bergulir (mis. 4 jendela
per bulan) dan laporkan sebaran hasil, bukan satu angka. Pertanyaan yang harus dijawab: **apakah
tanda positif bertahan di semua jendela?**

**Selesai bila:** ada tabel per-jendela, dan kesimpulannya eksplisit (bertahan / tidak).

### P4.4 — Rehidrasi FSM paper: pulihkan progres

**Masalah:** `barsHeld`, `isBeArmed`, `isPartialDone`, `peak` **reset** saat restart
(`workplan2.md` §12). Mesin yang dipulihkan tidak identik dengan yang hilang.

**Kerjakan:** persistensikan keempat nilai itu di `SpotPositionStore` bersama penanda
`_paper_engine`, lalu pakai di `PaperRehydrate`.

**Perhatikan:** jangan mengubah perilaku untuk posisi yang dibeli **sebelum** penanda ada —
SL-nya tersimpan salah (§12).

### P4.2 — Preferensi maker untuk pair IDR

**Masalah:** tarif taker IDR 0,8144% vs maker 0,6144%. Semua perhitungan saat ini memakai taker.

**Kerjakan:** dukung `useMaker = true` di jalur yang relevan; pastikan `ScalpingRiskEngine` dan
`HorizonReadinessEngine` menerima flag yang sama supaya angkanya tidak berbeda.

**Perhatikan:** ini mengubah angka net R:R di seluruh UI. Ukur ulang, jangan hanya ganti konstanta.

### P5 — Data Indodax + replay TAPI v2

**Masalah:** folder `data/` belum punya data Indodax; `IndodaxTradeApiV2.kt` ada tetapi belum
diuji ujung-ke-ujung.

**Kerjakan:** harness pengambilan data + replay, lalu jalankan pipeline §9 pada data Indodax.
Perhatikan: Indodax **hanya IDR**; fee 0,79% (lihat `FEE_TRUTH.md`).

### P6 — Auto-execute uang riil

**DIBLOKIR** sampai P4.1 dan P5 selesai. Jangan disentuh tanpa instruksi eksplisit owner.

---

## 5. Kenapa kesiapan uang riil masih BELUM (jangan lupa ini)

| Fakta | Angka |
|---|---|
| avg R terbobot, keluar-sampel | **+0,0216** |
| Profil exit | 88,7% `TRAIL_SL` **+0,09R** · 4,9% hard SL **−1,16R** |
| Win rate | ≈ 95% |
| Margin di atas titik impas | hanya **≈ 2,3 poin persentase** |
| Gate ketat keluar-sampel | **5 pair** dari 40 (APT, NEAR, SU, W, ZRO) |

Artinya: **tandanya benar, besarnya belum cukup.** Profil skew negatif berarti sebagian besar trade
menang kecil dan sesekali kalah besar — satu rentetan SL bisa menghapus puluhan trade menang.

Aturan yang sudah berlaku dan **jangan dilonggarkan**:

- `MIN_FRICTION_MULTIPLE = 6.0` — jangan turunkan di bawah 5 tanpa bukti replay baru.
- `PairEdgeGate` rasio ≥ 2,0 — jangan dilemahkan supaya sinyal bertambah.
- `TradeHorizon.validatedOutOfSample` hanya `true` untuk SCALP.
- Setiap permukaan yang menampilkan status `READY` wajib menyertakan konteks bahwa eksekusi uang
  riil masih diblokir.

---

## 6. Perangkap yang sudah pernah ditemukan (hemat waktu Anda)

1. **`setupOk` di `ScalpingMtfSnapshot` bukan hasil deteksi pola.** Field itu diisi `step2Ok`
   (tekanan orderbook). Yang benar-benar mensyaratkan pola adalah `triggerOk` (= `step3Ok`).
   Memakai `setupOk` untuk menyimpulkan "ada setup" adalah sumber bug asli §11/§13.
2. **`ScalpExitParams` dan `ScalpingRuntimeConfig` bisa berubah saat runtime.** Jangan menyalin
   nilainya ke variabel saat inisialisasi; baca dari `ScalpingRuntimeConfig.current` saat dipakai.
3. **`PaperAutoTradeRuntime.machines` hanya hidup di memori.** Posisi dan `auto_sell_enabled`
   persisten — tiga hal ini harus dijaga sinkron (§12).
4. **SL dari `setHolding` itu sintetis** (`entry * 0.99` ≈ 1%), bukan SL engine. Selalu tulis SL
   asli lewat `setAutoSellParams(stopLossPrice = ...)`.
5. **`paperExitSnapshot` adalah StateFlow tunggal** yang diisi tick pair mana pun. Di halaman detail
   pakai `paperSnapshotFor(exchange, symbol, price)`.
6. **Candle bisa ber-timestamp detik atau milidetik.** `MtfCacheManager.isCacheValid` menangani
   keduanya — tiru pola itu bila membandingkan waktu.
7. **`MtfCacheManager` tidak pernah mem-prefetch D1.** Horizon SWING akan `DATA KURANG` sampai
   diminta lewat `retryTimeframe`. Jangan menyimpulkan "engine rusak".
8. **Clone di sandbox kadang dibatasi** `remote.origin.fetch=+refs/heads/main:...`, sehingga
   `git branch -r` terlihat hanya berisi `main` padahal remote punya 13 branch. Perbaiki refspec
   dulu sebelum menyimpulkan.
9. **`debug.keystore` di-gitignore.** Build pertama di mesin baru bisa gagal di
   `validateSigningDebug` — buat keystore debug lokal.
10. **Test dengan `Math.random()` tidak membuktikan apa pun.** Semua fixture di repo ini
    deterministik; pertahankan itu.
11. **Gelombang sinus membuat bar terakhir bisa kebetulan turun.** Saat membuat fixture "tren naik",
    paksa bar terakhir naik (lihat `finalBarUpPct` di `HorizonReadinessEngineTest`), kalau tidak uji
    momentum akan gagal karena alasan yang salah.
12. **Uji yang selalu hijau tidak membuktikan apa pun.** Untuk setiap perbaikan bug, matikan
    perbaikannya, buktikan test GAGAL, lalu kembalikan. Pola ini dipakai di §12 dan §13.

---

## 7. Checklist sebelum menyelesaikan pekerjaan

- [ ] `./gradlew testDebugUnitTest assembleDebug` hijau; angka test **diukur**, bukan diingat.
- [ ] Baseline dibandingkan lewat `git stash` (bukan dari pesan commit lama).
- [ ] Kontrol negatif dijalankan untuk setiap perbaikan bug.
- [ ] `workplan2.md` diperbarui (seksi baru bila perlu).
- [ ] `AGENTS.md` diperbarui bila ada aturan baru.
- [ ] `docs/LAPORAN-<topik>-<tanggal>.md` ditulis (dalam Bahasa Indonesia).
- [ ] Tidak ada token/keystore/`.env` yang ikut ter-commit.
- [ ] Push ke **`patch`**, bukan `main`.
