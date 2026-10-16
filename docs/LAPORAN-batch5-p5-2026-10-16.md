# Laporan Batch 5d — P5: kartu FSM di layar simulasi dihapus tick pair lain

**Tanggal:** 2026-10-16 · **Branch:** `patch` · **Tier:** 🟠 serius
**Temuan:** P5 (`docs/bugs/temuan_bugs_paper_trade.md` §3)

---

## 1. Ringkasan

| | |
|---|---|
| Masalah | Kartu FSM di layar simulasi berkedip hilang bila posisi paper ada di pair lain |
| Akar | Satu `StateFlow` global ditimpa `null` oleh **setiap** simbol tanpa mesin |
| Perbaikan | Snapshot **per posisi** (`Map<String, PaperExitSnapshot>`, kunci = kunci mesin) |
| Uji baru | `PaperExitSnapshotsTest` — 10 kasus |
| Kontrol negatif | Perilaku lama dipulihkan ⇒ **4 dari 10 uji gagal** |
| Angka terukur | 71 kelas / 561 uji → **72 kelas / 571 uji**, 0 gagal, 1 dilewati |

---

## 2. Mengapa satu state global tidak pernah cukup di sini

```kotlin
// PaperAutoTradeCoordinator.kt
val machine = PaperAutoTradeRuntime.machineFor(exchange, symbol)
if (machine == null) {
    // Tidak ada posisi paper berjalan -> pastikan kartu FSM kosong.
    publishPaperExitSnapshot(null)   // ← menimpa snapshot SEMUA posisi
    return null
}
```

Fungsi ini dipanggil untuk:

- setiap simbol yang mendapat tick di loop polling (`TradingViewModelSync`) — **banyak simbol**,
- pair yang sedang dibuka (`TradingViewModel`) — **satu simbol**.

Jadi dalam satu detik bisa ada belasan panggilan, dan sebagian besar tidak punya mesin. Setiap
panggilan seperti itu menulis `null` ke satu-satunya slot yang dibaca layar. Kartu FSM karena itu
hanya stabil bila kebetulan simbol terakhir yang dievaluasi adalah simbol yang punya posisi.

Ini persis kebingungan "satu state untuk dua tampilan" yang dulu membuat fitur §14/§15 dibuang.

---

## 3. Yang diubah

### 3.1 `trading/PaperExitSnapshots.kt` (baru)

```kotlin
fun publish(current: Map<String, PaperExitSnapshot>, key: String, snapshot: PaperExitSnapshot?): Map<String, PaperExitSnapshot>
fun forPosition(current: Map<String, PaperExitSnapshot>, key: String): PaperExitSnapshot?
```

- `null` menghapus **satu entri**, bukan seluruh peta.
- Peta lama tidak dimutasi — aman dibaca dari Compose.
- `null` pada kunci yang tidak ada mengembalikan **peta yang sama** (`assertSame`), sehingga tidak
  memicu rekomposisi sia-sia.

### 3.2 `viewmodel/TradingViewModel.kt`

```kotlin
private val _paperExitSnapshots = MutableStateFlow<Map<String, PaperExitSnapshot>>(emptyMap())
val paperExitSnapshots: StateFlow<Map<String, PaperExitSnapshot>> = _paperExitSnapshots.asStateFlow()

internal fun publishPaperExitSnapshot(exchange: String, symbol: String, snapshot: PaperExitSnapshot?)
internal fun paperSnapshotAt(exchange: String, symbol: String): PaperExitSnapshot?
```

Kuncinya `PaperAutoTradeRuntime.key(exchange, symbol)` — format yang **sudah dipakai** mesin.
Sengaja tidak membuat format kunci baru: dua definisi kunci posisi dalam satu aplikasi adalah
cara cepat mendapatkan bug "snapshot tidak pernah ketemu".

### 3.3 Konsumen

| Layar | Sebelum | Sekarang |
|---|---|---|
| `TradeSimulationScreen` | snapshot global | `paperSnapshotAt(marketSource.name, selectedPair.symbol)` |
| `DetailChartScreen` | snapshot global (pemicu hitung ulang) | peta per posisi; kartu tetap lewat `paperSnapshotFor` |

---

## 4. Uji & kontrol negatif

**10 kasus** di `PaperExitSnapshotsTest`. Yang paling penting:

```kotlin
@Test
fun `publish - simbol tanpa mesin TIDAK menghapus snapshot simbol lain`() {
    var current = PaperExitSnapshots.publish(emptyMap(), keyBtc, snapBtc)
    current = PaperExitSnapshots.publish(current, keyAmp, snapAmp)
    current = PaperExitSnapshots.publish(current, keyAmp, null)   // AMP kehilangan mesin
    assertEquals(snapBtc, PaperExitSnapshots.forPosition(current, keyBtc))
    assertNull(PaperExitSnapshots.forPosition(current, keyAmp))
}
```

**Kontrol negatif.** `publish` dikembalikan ke perilaku lama: `null` mengosongkan seluruh peta,
snapshot baru menggantikan peta. Hasil: **4 dari 10 uji GAGAL**. Enam yang tetap lulus dicatat apa
adanya karena tidak menguji isolasi antar posisi: dua bentuk kunci, "snapshot tersimpan di bawah
kunci posisinya", "kunci sama ditimpa", dan dua kasus `forPosition`.

---

## 5. Perubahan perilaku yang perlu diketahui

Layar simulasi kini menampilkan FSM **pair yang sedang dibuka**, bukan "posisi paper mana pun yang
terakhir diterbitkan". Bila pair yang dibuka tidak punya posisi paper, kartunya kosong.

Sebelum perbaikan kartunya sering terisi — tetapi isinya bisa milik pair lain, dan bisa menghilang
kapan saja. Tampilan yang konsisten lebih berguna daripada tampilan yang terisi secara acak; ini
juga sejalan dengan halaman detail, yang sejak awal memang membaca per-pair lewat
`paperSnapshotFor`.
