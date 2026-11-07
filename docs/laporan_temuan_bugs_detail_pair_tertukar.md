# Temuan bugs — detail menampilkan pair yang salah (2026-11-08)

## Status

| ID | Temuan | Status | Penjaga |
|---|---|---|---|
| D1 | Layar detail menampilkan **pair yang sebelumnya dibuka**, bukan pair yang baru diklik di dashboard. Akar: `LaunchedEffect` di `DetailChartScreen` memanggil `viewModel.selectPair(pair)`, padahal `pair` berasal dari `detailUiState` yang **diturunkan** dari `selectedPair` — siklus tulis-balik. | ✅ DITUTUP 2026-11-08 | `DetailPairSourceTest` (3 uji) |

Berkas ini adalah laporan penutup; tidak ada temuan hidup, jadi tidak ada berkas di `docs/bugs/`.

## Gejala

Owner mengklik pair baru (mis. BTC) dari dashboard. Aplikasi berpindah ke layar detail,
tetapi yang tampil kadang pair yang dibuka **sebelumnya** — harga, candle, indikator, dan
tombol beli/jual semuanya milik pair lama. Mengulang klik kadang "berhasil", jadi gejalanya
tampak acak.

## Akar masalah

`DetailChartScreen` dulu memuat:

```kotlin
LaunchedEffect(pair.symbol, marketDataSource) {
    if (pair.symbol.isNotBlank()) viewModel.selectPair(pair)
}
```

`pair` di layar itu adalah `ui.pair` dari `detailUiState`, dan `detailUiState` dibangun
seperti ini (`TradingViewModelDetailState.createDetailUiState`):

```
selectedPair ──combine──> detailUiState ──pair──> DetailChartScreen
       ^                                                  │
       └──────────── LaunchedEffect { selectPair } ────────┘
```

Jadi layar detail **menulis balik** sumber datanya sendiri. Begitu emisi pertama yang
diterima layar masih membawa pair lama — dan itu normal terjadi karena `initialValue` =
`DetailUiState.empty()` (`EMPTY_PAIR`, `symbol = ""`), cache `WhileSubscribed(5_000)` saat
kembali dari layar lain, serta urutan emisi `combine` yang masing-masing di-`throttleLeading(300ms)` —
maka effect memanggil `selectPair(pairLama)` dan pilihan yang baru diklik ditimpa.

Karena `selectPair` juga menjalankan kerja berat (`engine.resetForOffline()`,
`MtfCacheManager.setActiveSymbol`, `loadPairCache`/`clearPairData`, `startMarketPolling`,
`GlobalContextManager.subscribeCoin`), penimpaan ini membuang seluruh state pair baru dan
memuat ulang state pair lama. Itu sebabnya yang terlihat bukan sekadar label salah, tetapi
data lengkap pair lama.

## Perbaikan

Hapus effect tulis-balik itu (`DetailChartScreen.kt`, diganti catatan penjelasan). Semua
pintu masuk layar detail sudah memilih pair **sebelum** navigasi, jadi effect tersebut tidak
punya alasan hidup:

| Pintu masuk | Berkas / baris | Memanggil |
|---|---|---|
| Dashboard — kartu holding aktif | `DashboardScreen.kt:388` | `selectPair` → `onNavigateToDetail` |
| Dashboard — kartu pair | `DashboardScreen.kt:513` | `selectPair` → `onNavigateToDetail` |
| Dashboard — hasil AI screener | `DashboardScreen.kt:688` | `selectPair` → `onNavigateToDetail` |
| Portofolio | `MainActivity.kt:142` | `openCoinDetail` |
| Simulasi trade | `MainActivity.kt:149` | `openCoinDetail(selectedPair.value)` |
| Log sinyal | `MainActivity.kt:169` | `openCoinDetail` |
| Notifikasi (tap & aksi jual) | `MainActivity.kt:213`, `:222` | `openCoinDetail` |

`openCoinDetail(pair)` (`TradingViewModelNavigation.kt`) melakukan `selectPair(pair)` lalu
`navigateTo(AppScreen.DETAIL)`. Satu-satunya pemakai `AppScreen.DETAIL` adalah
`MainActivity.kt:135` (render) dan `TradingViewModelNavigation.kt:22` (navigasi), jadi tidak
ada jalur masuk yang melewatkan `selectPair`.

Yang **tidak** diubah dan tetap berlaku: `SharingStarted.WhileSubscribed(5_000)`,
`initialValue = DetailUiState.empty()`, dan `throttleLeading(300ms)`. Setelah penulis balik
hilang, emisi berikutnya selalu membawa `selectedPair` terbaru; menurunkan cache hanya akan
menambah start ulang polling tanpa memperbaiki apa pun.

## Yang sudah benar — jangan diutak-atik

- **`openCoinDetail` tetap satu-satunya penulis pair untuk layar detail.** Urutannya
  `selectPair` **lalu** `navigateTo`; membalikkannya membuat layar sempat merender pair lama.
- **Efek lain di `DetailChartScreen` sah dan harus tetap ada:**
  `DisposableEffect(pair.baseAsset)` → `GlobalContextManager.subscribeCoin`/`stop`,
  `LaunchedEffect(isRealBuyMode, pair.symbol)` → `fetchRealBalance`,
  `LaunchedEffect(effectivePositionContext.hasPosition, ...)` → `markBought`,
  `LaunchedEffect(buyCooldownRemainingMs > 0)` → hitung mundur cooldown.
  Menghapus effect tulis-balik **bukan** izin menghapus semuanya.
- **`selectPair` di dashboard (`:388`, `:513`, `:688`) dibiarkan.** Pemanggilan ganda
  (`selectPair` di dashboard + di `openCoinDetail`) redundan dan idempoten pada nilai akhir;
  bukan penyebab gejala, dan mengubahnya memperluas cakupan di luar bug yang dilaporkan.
- **`marketSymbol` pada sinyal tidak ditimpa** (`trading/SignalSymbolAttribution.kt`):
  `engine.signalState` bisa masih berisi hasil pair lama sesaat setelah `selectPair`.

## Uji & kontrol negatif

`app/src/test/java/agu/analys/ui/screens/DetailPairSourceTest.kt` — uji pindai sumber
(pola `ConfidenceTierRemovalTest`), komentar `//` dan `/* */` dibuang lebih dulu supaya
catatan penjelasan tidak dianggap kode:

1. `layar detail tidak memanggil selectPair` — `DetailChartScreen.kt` tidak boleh memuat
   `selectPair(` sama sekali. Pesan gagal menyebut penyebab siklus `detailUiState` ↔
   `selectedPair`.
2. `layar detail masih punya efek subscribe koin yang sah` — menjaga sisi lain: penghapusan
   harus terarah. `DisposableEffect(pair.baseAsset)`, `GlobalContextManager.subscribeCoin`,
   dan `LaunchedEffect(isRealBuyMode, pair.symbol)` wajib tetap ada.
3. `pintu masuk detail tetap memilih pair sebelum navigasi` — `openCoinDetail` wajib
   memanggil `selectPair(pair)` **dan** indeks `selectPair` harus lebih kecil dari indeks
   `navigateTo(AppScreen.DETAIL)`.

Uji hanya memindai teks sumber karena `DetailChartScreen` adalah Composable Compose: perilaku
effect itu tidak dapat dijalankan dari JVM tanpa emulator Compose, sedangkan regresinya
terbaca jelas di teks sumber.

**Kontrol negatif (terbukti):** blok lama
`LaunchedEffect(pair.symbol, marketDataSource) { if (pair.symbol.isNotBlank()) viewModel.selectPair(pair) }`
dikembalikan ke berkas → pemindaian dengan logika uji yang sama menemukan `selectPair(` →
uji **GAGAL**. Blok dibuang kembali → pemindaian bersih → uji **LULUS**. Perintah build lokal
(`./gradlew`) tidak tersedia di mesin ini (tanpa JDK/Android SDK), jadi eksekusi Gradle
sebenarnya diverifikasi lewat GitHub Actions.

## Riwayat

- **2026-11-08** — Owner melaporkan layar detail kadang menampilkan pair sebelumnya.
  Akar: `LaunchedEffect` di `DetailChartScreen` memanggil `selectPair(pair)` sementara `pair`
  berasal dari `detailUiState` yang diturunkan dari `selectedPair` (siklus tulis-balik;
  emisi pertama `EMPTY_PAIR`/cache `WhileSubscribed(5_000)`/urutan `combine` menimpa pilihan
  baru). Perbaikan: effect dihapus, diganti catatan; semua pintu masuk sudah memanggil
  `openCoinDetail` sebelum navigasi. Penjaga: `DetailPairSourceTest` (3 uji); kontrol negatif
  terbukti MERAH saat blok lama dikembalikan. Rincian: `workplan2.md` §95.
