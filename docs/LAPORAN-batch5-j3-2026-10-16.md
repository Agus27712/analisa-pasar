# Laporan Batch 5b — J3: `SignalLogViewModel` mengikat bursa sekali saat konstruksi

**Tanggal:** 2026-10-16 · **Branch:** `patch` · **Tier:** 🟠 serius
**Temuan:** J3 (`docs/bugs/temuan_bugs_jurnal_pnl.md` §3)

---

## 1. Ringkasan

| | |
|---|---|
| Masalah | Mengganti bursa tidak pernah mengulang subscription jurnal; tabel log & reliabilitas tetap berisi bursa lama |
| Akar | Parameter Room terikat saat flow dibuat; `currentExchange` dibaca **sekali** walau berbentuk getter |
| Perbaikan | Bursa **mengalir** dari `settingsViewModel.marketDataSource`; pengikat diekstrak ke `SignalLogExchangeBinder` |
| Uji baru | `SignalLogExchangeBinderTest` — 7 kasus |
| Kontrol negatif | Perilaku lama dipulihkan (`take(1)`) ⇒ **5 dari 7 uji gagal** |
| Angka terukur | 69 kelas / 542 uji → **70 kelas / 549 uji**, 0 gagal, 1 dilewati |

---

## 2. Mengapa getter tidak menyelamatkan keadaan

```kotlin
val currentExchange: String get() = prefs.marketDataSource.name     // dievaluasi tiap dibaca…
val allSignalLogs: StateFlow<List<SignalLogEntity>> =
    signalLogRepository.getLogsByExchangeFlow(currentExchange)       // …tetapi hanya sekali di sini
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
```

Getter memang membaca ulang `SharedPreferences`, tetapi yang dipakai flow hanyalah **nilai saat
flow dibuat** — yaitu saat `SignalLogViewModel` dikonstruksi di `TradingViewModel.kt:52`, sekali
seumur activity. Setelah itu, `dao.getLogsByExchangeFlow(exchange.uppercase())` terkunci pada
nama bursa itu.

Jejaknya jelas: `flatMapLatest` **sudah di-import** di berkas itu (baris 18) tetapi tidak pernah
dipakai — pola reaktifnya pernah direncanakan, tinggal tidak selesai.

---

## 3. Yang diubah

### 3.1 `database/SignalLogExchangeBinder.kt` (baru)

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
object SignalLogExchangeBinder {
    fun <T> bind(exchangeNames: Flow<String>, query: (String) -> Flow<T>): Flow<T> =
        exchangeNames.map { it.trim().uppercase() }.distinctUntilChanged().flatMapLatest { query(it) }
}
```

| Bagian | Alasan |
|---|---|
| `flatMapLatest` | subscription lama dibatalkan; query dijalankan ulang untuk bursa baru |
| `distinctUntilChanged` | nama sama berturut-turut tidak membuka query Room baru |
| `trim().uppercase()` | sepakat dengan DAO yang juga memanggil `uppercase()` |

### 3.2 `viewmodel/SignalLogViewModel.kt`

- Konstruktor menerima `exchangeFlow: StateFlow<MarketDataSource>`.
- `allSignalLogs` dan `signalReliabilitySummary` dibangun lewat `SignalLogExchangeBinder.bind`.
- `currentExchange` kini membaca `exchangeFlow.value.name` — bursa punya **satu** sumber, bukan
  dua (getter `prefs` + flow). `AppPreferences` tidak lagi dipakai di berkas ini, sehingga tidak
  ada lagi `AndroidViewModel` kedua yang bisa tidak sepakat.

### 3.3 `viewmodel/TradingViewModel.kt`

```kotlin
val signalLogViewModel = SignalLogViewModel(application, settingsViewModel.marketDataSource)
```

`settingsViewModel.marketDataSource` sudah `StateFlow<MarketDataSource>` dan diperbarui di
`setMarketDataSource`, jadi sumber reaktifnya tersedia tanpa menambah mekanisme baru.

---

## 4. Uji & kontrol negatif

**7 kasus** di `SignalLogExchangeBinderTest`. Alirnya diuji dengan `MutableStateFlow` +
`runBlocking`, tanpa Robolectric dan tanpa Android.

| Kasus | Yang dibuktikan |
|---|---|
| query dijalankan untuk bursa awal | subscription pertama terjadi |
| ganti bursa mengulang subscription | **inti J3** |
| nilai yang keluar mengikuti bursa terakhir | bukan hanya query, angkanya juga berganti |
| nama sama berturut-turut tidak mengulang subscription | `distinctUntilChanged` |
| nama dibakukan (huruf kecil) | `uppercase()` |
| spasi dibuang | `trim()` |
| tiga pergantian berturut-turut | tidak ada yang terlewat |

**Kontrol negatif.** Binder dikembalikan ke perilaku lama — bursa dibaca sekali (`take(1)`), tanpa
pembakuan nama. Hasil: **5 dari 7 uji GAGAL**.

Dua yang tetap lulus dicatat apa adanya karena keduanya **bukan bukti perbaikan**: "query
dijalankan untuk bursa awal" dan "nama sama berturut-turut tidak mengulang subscription" —
keduanya juga benar pada versi lama. Hanya lima yang gagal itu yang benar-benar menguji reaktivitas.

---

## 5. Batas yang jangan dibaca lebih dari kenyataannya

1. **Yang diuji pengikat alirnya, bukan layarnya.** `SignalLogScreen` tidak diuji otomatis;
   perbaikan diuji pada `bind()`.
2. **Hanya jurnal sinyal yang disapu.** Pola "bursa dibaca sekali saat konstruksi" kemungkinan ada
   di ViewModel lain. Perbaikan ini menutup J3, bukan seluruh kelas bug itu.
3. **Data lama tetap milik bursa lama** — yang berubah adalah *bursa mana yang ditampilkan*, bukan
   isi barisnya.
