# LAPORAN — Temuan Akuntansi Replay: `UNRESOLVED` yang Dikecualikan (2026-10-14)

**Branch:** `patch`
**Status:** 🔴 Temuan kritis — angka edge lama (`u4`) **tidak sah**
**Berkas terkait:** `tools/scalp_replay.py`, `tools/split_validate.py`,
`data/hasil/u4_split_toko_usdt.md`, `data/hasil/u8_walk_forward_40pair.md`

---

## 1. Ringkasan satu paragraf

Seluruh klaim edge proyek ini bertumpu pada satu angka: **avg R keluar-sampel +0,0216** dengan
win rate ≈95% (`data/hasil/u4_split_toko_usdt.md`, verdict LOLOS). Angka itu **tidak sah**.
Penyebabnya bukan strateginya, melainkan **cara menghitung**: `summarize()` di `scalp_replay.py`
hanya mengitung trade ber-`result` `WIN`/`LOSS`; trade yang dalam jendela *lookahead* tidak
menyentuh SL/TP/early/trail ditandai `UNRESOLVED` dan **dikeluarkan dari seluruh statistik**
(`trades_resolved`, `avg_r`, `win_rate`). Pada data 40 pair, cara lama itu membuang **±80% sinyal**.
Ketika trade yang sama dihitung jujur (ditutup di harga close bar terakhir jendela), hasilnya
berbalik menjadi **avg R ≈ −0,17** dan **0 dari 40 pair positif**.

---

## 2. Mekanismenya

`summarize()` (`tools/scalp_replay.py:1079`):

```python
tr = [t for t in rep["trades"] if t["result"] in ("WIN", "LOSS")]
```

Trade `UNRESOLVED` hanya masuk hitungan `"unresolved"` — tidak pernah masuk `trades_resolved`,
`avg_r`, `win_rate`, atau `profit_factor`.

Sampai commit `0ca38eb` (S1–S9), `resolve_trade()` mengembalikan:

```python
return {"result": "UNRESOLVED", "r": 0.0, ..., "exit_reason": "TIMEOUT"}
```

Commit **`703c3b6`** (*"implement time-stop logic for trade resolution with partial profit
calculation"*) menambahkan blok yang menutup trade di harga close bar terakhir:

```python
    # Time-stop / akhir data: tutup di harga close bar terakhir yang tersedia.
    # Profit parsial TP1 (partial_r) ikut dihitung. Tidak lagi r = 0.
    if end > i + 1:
        last_idx = end - 1
        rem = r_at(m1[last_idx]["c"])
        total_r = partial_r + 0.5 * rem if partial_done else rem
        return _done(total_r, last_idx, "TIMEOUT", ambiguous)
```

Commit inilah yang membuat angkanya berubah drastis — **bukan** perubahan entry/exit S1–S9.
Perhatikan: blok ini aktif **apa pun** nilai `max_hold_bars_m1` (nilai itu hanya memperpendek
`end`), jadi mematikan time-stop **tidak** mengembalikan perilaku lama.

---

## 3. Kontrol negatif — satu variabel, satu perubahan

Data: `data/tokocrypto_live_usdt` (40 pair, 87.000 bar M1/pair) · config
`data/config/scalp_config_exit_full.json` · split train 70% / test 30% · friksi 0,9688%.
Semua baris memakai **data, config, dan kode yang sama** — hanya akuntansi exit yang berbeda.

| # | Perlakuan | Test n | Test avgR | Pair+ | Verdict |
|---|---|---:|---:|---:|---|
| 1 | **Akuntansi LAMA** — blok `703c3b6` dinonaktifkan, `UNRESOLVED` dikecualikan | 817 | **+0,0577** | 26/29 | LOLOS |
| 2 | **Akuntansi BARU** — config apa adanya | 4.403 | **−0,1733** | 0/40 | TIDAK LOLOS |
| 3 | Akuntansi baru + `max_hold_bars_m1 = 0` (time-stop mati) | 3.910 | −0,1722 | 0/40 | TIDAK LOLOS |
| 4 | Akuntansi baru + time-stop mati + lantai profit S2 mati | 4.263 | −0,1686 | 0/40 | TIDAK LOLOS |
| 5 | Akuntansi baru + lantai profit S2 mati (time-stop tetap 90) | 4.711 | −0,1697 | 0/40 | TIDAK LOLOS |

> Baris 1 dijalankan dengan blok `703c3b6` dinonaktifkan sementara di `tools/scalp_replay.py`
> (kontrol negatif), lalu berkas dikembalikan **identik** ke keadaan `HEAD` — `git diff` kosong.

**Bacaan:**

1. Baris 1 vs 2: **satu-satunya** yang berubah adalah apakah trade tak tuntas dihitung. Tanda
   hasil berbalik dari positif ke negatif — dan jumlah trade berubah 817 → 4.403.
2. Baris 3–5: mematikan time-stop **dan** lantai profit tidak mengembalikan hasil positif.
   Jadi −0,17 **bukan** efek samping setelan exit — melainkan apa yang terjadi bila seluruh
   sinyal dihitung.
3. Akuntansi lama membuang **±81% sinyal** (817 dari 4.403), dan yang dibuang justru bukan yang
   menguntungkan.

---

## 4. Kenapa angkanya persis ≈ −0,17R

Lantai risiko friksi memaksa jarak SL ≥ `6 × friksi` = **5,81%** (`min_friction_multiple = 6.0`).
Biaya round-trip = **0,9688%**.

Kalau sebuah trade tidak punya edge, hasil rata-ratanya = menanggung biaya lalu ditutup di harga
pasar:

```
−0,9688% / 5,81% = −0,167 R
```

Yang terukur: **−0,168 … −0,172 R**. Cocok hampir persis.

**Kesimpulan:** pada horizon evaluasi (120 bar M1 ≈ 2 jam), rata-rata trade bergerak **sebesar
biaya friksi dan tidak lebih**. Tidak ada edge kotor yang tersisa setelah friksi.

---

## 5. Yang harus diperbaiki di dokumen lain

| Berkas | Klaim lama | Status |
|---|---|---|
| `data/hasil/u4_split_toko_usdt.md` | verdict **LOLOS**, test avgR **+0,0216** | ⚠️ Bias *survivorship* — diberi peringatan di berkas |
| `docs/PANDUAN-LANJUT-AGEN.md` §5 | "avg R terbobot keluar-sampel **+0,0216**" | ⚠️ Diganti |
| `docs/BELUM-DIKERJAKAN-2026-10-14.md` Bagian 5 | angka yang sama | ⚠️ Diganti |
| `docs/LAPORAN-p41-walk-forward-2026-10-14.md` §4 | hasil 40 pair | ✅ ditulis dengan akuntansi baru |

**Aturan baru yang diusulkan (sudah ditambahkan ke `AGENTS.md`):** trade `UNRESOLVED` **wajib**
ikut dihitung atau dilaporkan jumlahnya di samping setiap angka avg R. Menampilkan avg R tanpa
menyebut berapa sinyal yang dibuang = klaim yang menyesatkan.

---

## 6. Yang **tidak** dibuktikan oleh temuan ini

- **Bukan** bukti bahwa engine entry-nya salah. Yang diukur di sini adalah hasil **keluar**
  (exit) pada horizon tetap.
- **Bukan** berarti `u7`/`u8` walk-forward menjadi sah — keduanya masih memakai akuntansi baru
  (bagus), tetapi `u7` memakai 8 pair yang ditolak `PairEdgeGate`.
- **Bukan** berarti S1–S9 salah. Justru sebaliknya: perbaikan-perbaikan itu tetap berlaku, dan
  `703c3b6` adalah perbaikan yang **membongkar** masalah lama.
