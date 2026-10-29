# AGENTS.md — aturan area `tools/` (tooling riset offline)

Berkas ini dipisahkan dari `AGENTS.md` akar pada **2026-10-27** supaya aturan area tidak ikut
dimuat pada setiap permintaan. Isinya **salinan apa adanya** dari aturan yang dulu ada di akar.

**Dibaca kapan:** setiap kali menyentuh `tools/`, `data/config/scalp_config.json`, atau menjalankan
replay/sweep. Perintah build & unit test ada di `AGENTS.md` akar; aturan engine ada di
`app/AGENTS.md`.

---

- **Mirror replay = satu berkas, dikunci uji.** `data/config/scalp_config.json` adalah satu-satunya
  config yang boleh dibaca `tools/scalp_replay.py` (bawaan `--config`). Fee, `min_friction_multiple`,
  `target_net_rr`, `slippage_pct`, `max_risk_atr`, `max_change_24h_pct`, dan blok `exit` di
  dalamnya diperiksa uji terhadap `FeeSchedule` dan `ScalpingConfig`, jadi revisi aplikasi ikut
  memakai angka yang sama dengan replay. Jangan membuat berkas config kedua di `tools/`.
- **Replay wajib memakai exit yang sama dengan app, bukan subset.** CLASSIC punya time-stop
  90 bar M1 (`ScalpExitParams.DEFAULT`), dan mode `full`/`tp1_be`/`trail`/`early` harus ada di
  replay. Jangan menghidupkan kembali `order_type`/tarif maker: jalur app hanya taker. Jangan
  menghapus time-stop agar “trade selesai” terlihat lebih baik — itu justru menyembunyikan
  exit yang benar-benar dijalankan app. Penjaga: `tools/test_scalp_replay_parity.py`.
- **Replay pair IDR wajib memakai `fee.idr_reference`.** `resolve_fee` menggabungkan referensi
  IDR sebelum menghitung friksi; tanpa itu pair IDR dinilai dengan tarif USDT dan lantai SL
  melenceng. Konfigurasi IDR mandiri boleh memakai blok `fee` tingkat atas, tetapi referensi
  yang disebut tetapi tidak ada harus **gagal**, bukan diam-diam memakai tarif USDT.
  Penjaga: `tools/test_scalp_replay_parity.py`.
- **Replay harus bisa dijalankan dari keadaan diam.** Kalau `--config` menunjuk berkas yang tidak
  ada, keluarannya JSON berisi lokasi yang dicoba + saran perintah — bukan traceback. Perintah
  yang tertulis di dokumen harus benar-benar berhasil dijalankan dari repo bersih.
- **Alat lab entri (`tools/entry_buy_low_lab.py`) hanya untuk riset offline.** Ia membaca CSV
  `data/tokocrypto_v8`, tidak membaca `scalp_config.json`, dan tidak boleh disambungkan ke app,
  gate, atau config. Hasil JSON-nya (`data/hasil/exp_buy_low_entry_v8_*_regime.json`) harus bisa
  diregenerasi dengan perintah di docstring berkas itu; jangan menyunting JSON hasil dengan tangan
  karena angka di `docs/exp/` harus berasal dari alat. `--slim` dan `--cells` hanya menghemat
  ukuran berkas, bukan mengubah perhitungan.
