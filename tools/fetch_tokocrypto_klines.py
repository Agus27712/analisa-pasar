#!/usr/bin/env python3
"""
Ambil candle NYATA dari API Tokocrypto (type 1 market data) lalu simpan sebagai CSV.

Jalankan dari jaringan di Indonesia (Tokocrypto memblokir IP luar negeri dengan HTTP 451,
termasuk runner GitHub Actions). Hanya butuh Python 3 (library standar), tanpa pip.

Dua cara pakai:

1. Cara lama (tetap didukung, tidak berubah perilakunya):

       python3 tools/fetch_tokocrypto_klines.py
       python3 tools/fetch_tokocrypto_klines.py --pairs BTCUSDT,ETHUSDT --pages 5 --out data/tokocrypto

2. Cara baru dengan pemilihan pair otomatis dan jendela waktu tetap:

       # lihat daftar pair dulu, tanpa mengunduh candle
       python3 tools/fetch_tokocrypto_klines.py --select mixed --list-only --out data/tokocrypto_spot

       # unduh 90 hari, interval 15m dan 1h
       python3 tools/fetch_tokocrypto_klines.py --select mixed --days 90 \
           --intervals 15m,1h --out data/tokocrypto_spot --resume --write-readme

Hasil: <out>/<PAIR>_1m.csv, <out>/<PAIR>_15m.csv, <out>/<PAIR>_1h.csv
Kolom : open_time_ms,open,high,low,close,volume   (hanya candle yang sudah CLOSED)

Catatan penting:
- Daftar pair resmi HANYA dari `https://www.tokocrypto.com/open/v1/common/symbols`.
  Endpoint `exchangeInfo` gateway berisi universe Binance dan tidak boleh dipakai untuk
  discovery (lihat AGENTS.md, temuan K1-K15).
- Statistik 24 jam untuk pemilihan pair diambil dari API Tokocrypto sendiri. Bila endpoint
  itu tidak terbaca (mis. geolokasi), tool memakai snapshot referensi Binance di
  `data/config/binance_24h_movers_reference.json` dan mencatatnya di manifest. Snapshot itu
  hanya cadangan riset; tool ini tidak pernah memanggil Binance secara langsung.
- Candle selalu diunduh dari Tokocrypto.
"""
import argparse
import csv
import datetime as dt
import hashlib
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request

TOOL_VERSION = "2026-10-20.1"
DEFAULT_BASE = "https://www.tokocrypto.site/api/v3"
OPEN_API_BASE = "https://www.tokocrypto.com/open/v1"
OFFICIAL_SYMBOLS_URL = "https://www.tokocrypto.com/open/v1/common/symbols"
DEFAULT_REFERENCE = "data/config/binance_24h_movers_reference.json"
DEFAULT_PAIRS = "BTCUSDT,ETHUSDT,BNBUSDT,SOLUSDT,XRPUSDT,DOGEUSDT,ADAUSDT,TRXUSDT"
INTERVALS = {
    "1m": 60_000,
    "15m": 15 * 60_000,
    "1h": 60 * 60_000,
    "4h": 4 * 60 * 60_000,
    "1d": 24 * 60 * 60_000,
    "1w": 7 * 24 * 60 * 60_000,
}
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) TokoClient/3.5"

# Basis stablecoin: tidak dipakai untuk bucket "stabil" karena pergerakannya mendekati nol
# dan tidak mewakili pasar spot yang bisa di-scalp.
STABLECOIN_BASES = {
    "USDC", "USD1", "RLUSD", "U", "FDUSD", "TUSD", "USDP", "DAI", "USDD",
    "PYUSD", "USDE", "SUSDE", "GUSD", "LUSD", "BUSD",
}


def utc(ms):
    if ms is None:
        return None
    return dt.datetime.fromtimestamp(ms / 1000, dt.UTC).strftime("%Y-%m-%d %H:%M")


def http_get(url, retries=3, timeout=25):
    """GET JSON dengan retry. 429/5xx ditunggu, 4xx lain langsung gagal."""
    last = None
    for attempt in range(retries):
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "ignore")[:160]
            last = f"HTTP {e.code} {body}"
            if e.code in (418, 429) or 500 <= e.code < 600:
                time.sleep(min(20, 2 ** attempt))
                continue
            raise RuntimeError(last)
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            last = str(e)
            time.sleep(min(10, 2 ** attempt))
    raise RuntimeError(last or "permintaan gagal")


def parse_rows(text):
    data = json.loads(text)
    if isinstance(data, dict):
        data = data.get("data", [])
    rows = []
    for r in data:
        try:
            rows.append((int(r[0]), float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5])))
        except (ValueError, IndexError, TypeError):
            continue
    return rows


def fetch_official_symbols(quote):
    """Daftar pair resmi Tokocrypto, dipetakan ke format host market data (tanpa garis bawah)."""
    doc = json.loads(http_get(OFFICIAL_SYMBOLS_URL))
    lst = (doc.get("data") or {}).get("list") or []
    out = {}
    collisions = []
    for s in lst:
        base = str(s.get("baseAsset") or "").strip().upper()
        q = str(s.get("quoteAsset") or "").strip().upper()
        if not base or not q:
            continue
        if quote and q != quote.upper():
            continue
        pair = f"{base}{q}"
        if pair in out:
            collisions.append(pair)
            continue
        out[pair] = {
            "pair": pair,
            "base": base,
            "quote": q,
            "symbol_type": int(s.get("type") or 1),
            "official_symbol": str(s.get("symbol") or ""),
        }
    if collisions:
        print(f"! peringatan: {len(collisions)} bentrok nama pair dilewati: {sorted(set(collisions))[:5]}",
              file=sys.stderr)
    return out


def fetch_24h_all():
    """Statistik 24 jam seluruh pasar dari Tokocrypto (satu permintaan ~1,9 MB)."""
    data = json.loads(http_get(DEFAULT_BASE + "/ticker/24hr", timeout=45))
    if not isinstance(data, list):
        raise RuntimeError("bentuk respons ticker 24 jam tidak dikenal")
    out = {}
    for x in data:
        try:
            pair = str(x["symbol"]).upper()
            high = float(x.get("highPrice") or 0)
            low = float(x.get("lowPrice") or 0)
            out[pair] = {
                "change_pct": float(x.get("priceChangePercent") or 0),
                "quote_volume": float(x.get("quoteVolume") or 0),
                "high": high,
                "low": low,
                "last": float(x.get("lastPrice") or 0),
                "range_pct": ((high - low) / low * 100.0) if low > 0 else None,
            }
        except (KeyError, TypeError, ValueError):
            continue
    return out


def load_reference(path):
    """Snapshot cadangan (Binance + angka Tokocrypto saat snapshot dibuat)."""
    if not path or not os.path.exists(path):
        return None, f"snapshot referensi tidak ada: {path}"
    with open(path) as f:
        doc = json.load(f)
    stats = {}
    for x in doc.get("symbols", []):
        pair = str(x.get("pair") or "").upper()
        if not pair:
            continue
        tok = x.get("tokocrypto_change_pct")
        binance = x.get("binance_change_pct")
        change = tok if tok is not None else binance
        if change is None:
            continue
        stats[pair] = {
            "change_pct": float(change),
            "quote_volume": float(x.get("tokocrypto_quote_volume_usdt") or x.get("binance_quote_volume_usdt") or 0),
            "high": None,
            "low": None,
            "last": None,
            "range_pct": x.get("tokocrypto_range_pct"),
            "from_snapshot": True,
        }
    return {
        "generated_at_utc": doc.get("generated_at_utc"),
        "source": doc.get("source"),
        "stats": stats,
    }, None


def _row(pair, info, stat, bucket):
    return {
        "pair": pair,
        "bucket": bucket,
        "quote": info.get("quote") if info else None,
        "symbol_type": info.get("symbol_type") if info else 1,
        "change_24h_pct": round(stat["change_pct"], 4) if stat and stat.get("change_pct") is not None else None,
        "quote_volume_24h": round(stat["quote_volume"], 2) if stat and stat.get("quote_volume") is not None else None,
        "range_24h_pct": round(stat["range_pct"], 4) if stat and stat.get("range_pct") is not None else None,
        "last_price": stat.get("last") if stat else None,
        "from_snapshot": bool(stat and stat.get("from_snapshot")),
    }


def select_pairs(mode, args, official, stats, stats_source, warnings):
    exclude = {p.strip().upper() for p in (args.exclude or "").split(",") if p.strip()}
    if not args.keep_stablecoins:
        exclude |= STABLECOIN_BASES

    def usable(pair, info):
        if not info:
            return False
        if info.get("base") in exclude:
            return False
        if pair in exclude:
            return False
        if info.get("symbol_type") != 1:
            # Tipe 3 memakai host lain dan tidak punya ticker 24 jam di endpoint ini.
            return False
        return True

    eligible, stable, momentum, reference = [], [], [], []

    if mode in ("eligible", "mixed"):
        path = args.pair_gate
        if not os.path.exists(path):
            raise RuntimeError(f"pair_gate.json tidak ada: {path}")
        with open(path) as f:
            gate = json.load(f)
        block = (gate.get("quotes") or {}).get(args.quote.upper())
        if not block:
            raise RuntimeError(f"pair_gate.json tidak punya blok kuotasi {args.quote}")
        for p in block.get("pairs", []):
            if not p.get("eligible"):
                continue
            pair = str(p.get("pair") or "").upper()
            info = official.get(pair)
            if not usable(pair, info):
                warnings.append(f"eligible {pair} dilewati: tidak ada di daftar resmi / tipe bukan 1 / dikecualikan")
                continue
            eligible.append(_row(pair, info, stats.get(pair), "eligible"))

    need_stats = mode in ("stable", "momentum", "mixed", "all", "reference")
    if need_stats and not stats:
        if mode in ("stable", "mixed"):
            warnings.append("statistik 24 jam tidak tersedia; bucket stabil dilewati")
        if mode in ("momentum", "all", "reference"):
            raise RuntimeError("statistik 24 jam tidak tersedia dan tidak ada snapshot cadangan yang bisa dipakai")

    if mode in ("stable", "mixed"):
        if stats:
            cand = []
            for pair, st in stats.items():
                info = official.get(pair)
                if not usable(pair, info):
                    continue
                qv = st.get("quote_volume") or 0
                ch = st.get("change_pct")
                rng = st.get("range_pct")
                if qv < args.min_quote_volume:
                    continue
                if ch is None or abs(ch) > args.max_change_pct:
                    continue
                if rng is not None and rng > args.max_range_pct:
                    continue
                cand.append((qv, pair, st))
            cand.sort(reverse=True)
            for qv, pair, st in cand[: args.top_stable]:
                stable.append(_row(pair, official.get(pair), st, "stable"))

    if mode in ("momentum", "mixed", "all"):
        if stats:
            cand = []
            for pair, st in stats.items():
                info = official.get(pair)
                if not usable(pair, info):
                    continue
                qv = st.get("quote_volume") or 0
                ch = st.get("change_pct")
                if qv < args.min_quote_volume or ch is None:
                    continue
                if mode in ("momentum", "mixed") and ch < args.min_change_pct:
                    continue
                cand.append((ch, qv, pair, st))
            if mode == "all":
                cand.sort(key=lambda x: x[1], reverse=True)
            else:
                cand.sort(reverse=True)
            limit = args.top_momentum if mode in ("momentum", "mixed") else len(cand)
            for ch, qv, pair, st in cand[:limit]:
                momentum.append(_row(pair, official.get(pair), st, "momentum"))

    if mode in ("reference", "mixed") and args.include_reference:
        ref, err = load_reference(args.reference)
        if err:
            warnings.append(err)
        else:
            for pair, st in ref["stats"].items():
                info = official.get(pair)
                if not usable(pair, info):
                    continue
                if st.get("change_pct") is None or st["change_pct"] < args.min_change_pct:
                    continue
                reference.append(_row(pair, info, st, "reference"))

    if mode == "all" and stats:
        # 'all' memakai daftar momentum di atas (seluruh pair yang lolos ambang volume),
        # diurutkan berdasarkan perubahan 24 jam menurun.
        pass

    ordered, seen = [], set()
    groups = [eligible, stable, momentum, reference] if mode == "mixed" else {
        "eligible": [eligible],
        "stable": [stable],
        "momentum": [momentum],
        "reference": [reference],
        "all": [momentum],
        "pairs": [],
        "file": [],
    }.get(mode, [eligible])
    for group in groups:
        for row in group:
            if row["pair"] in seen:
                continue
            seen.add(row["pair"])
            ordered.append(row)
    return ordered


def pairs_from_file(path):
    out = []
    with open(path) as f:
        for line in f:
            line = line.split("#", 1)[0].strip().upper()
            if line:
                out.append(line)
    return out


def window_for(args, interval):
    step = INTERVALS[interval]
    end_ms = args.end_ms or int(time.time() * 1000)
    if args.days and args.days > 0:
        start_ms = end_ms - int(args.days * 24 * 3600 * 1000)
    elif args.start_ms:
        start_ms = args.start_ms
    else:
        return None, end_ms
    if start_ms >= end_ms:
        raise RuntimeError("start_ms harus lebih kecil dari end_ms")
    return start_ms, end_ms


def fetch_window(base, pair, symbol_type, interval, start_ms, end_ms):
    """Paginasi maju memakai startTime, berhenti saat tidak ada bar baru."""
    step = INTERVALS[interval]
    by_time = {}
    cursor = start_ms
    guard = 0
    while cursor < end_ms:
        guard += 1
        if guard > 2000:
            raise RuntimeError("paginasi melebihi 2000 permintaan; periksa parameter")
        if symbol_type == 3:
            url = (f"{OPEN_API_BASE}/market/klines?symbol={pair[: -len('USDT')]}_USDT"
                   f"&interval={interval}&limit=1000&startTime={cursor}&endTime={end_ms}")
        else:
            url = (f"{base}/klines?symbol={pair}&interval={interval}"
                   f"&limit=1000&startTime={cursor}&endTime={end_ms}")
        rows = parse_rows(http_get(url))
        if not rows:
            break
        before = len(by_time)
        for r in rows:
            by_time[r[0]] = r
        newest = max(r[0] for r in rows)
        if len(rows) < 1000 or newest + step >= end_ms:
            break
        if len(by_time) == before:
            break
        cursor = newest + step
        time.sleep(0.12)
    now_ms = int(time.time() * 1000)
    return [by_time[k] for k in sorted(by_time) if k + step <= now_ms and start_ms <= k < end_ms]


def fetch_pages(base, pair, symbol_type, interval, pages):
    """Perilaku lama: paginasi mundur dengan endTime, tanpa jendela tetap."""
    by_time = {}
    end_time = None
    for _ in range(pages):
        if symbol_type == 3:
            url = (f"{OPEN_API_BASE}/market/klines?symbol={pair[: -len('USDT')]}_USDT"
                   f"&interval={interval}&limit=1000")
        else:
            url = f"{base}/klines?symbol={pair}&interval={interval}&limit=1000"
        if end_time is not None:
            url += f"&endTime={end_time}"
        rows = parse_rows(http_get(url))
        if not rows:
            break
        before = len(by_time)
        for r in rows:
            by_time[r[0]] = r
        if len(by_time) == before:
            break
        end_time = min(r[0] for r in rows) - 1
        time.sleep(0.12)
    now_ms = int(time.time() * 1000)
    step = INTERVALS[interval]
    return [by_time[k] for k in sorted(by_time) if k + step <= now_ms]


def read_csv_rows(path):
    """Baca CSV yang sudah ada (untuk resume). Mengembalikan daftar bar terurut."""
    if not os.path.exists(path):
        return []
    out = []
    try:
        with open(path) as f:
            r = csv.reader(f)
            next(r, None)
            for x in r:
                try:
                    out.append((int(x[0]), float(x[1]), float(x[2]), float(x[3]), float(x[4]), float(x[5])))
                except (ValueError, IndexError):
                    continue
    except OSError:
        return []
    out.sort()
    return out


def write_csv(path, rows):
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["open_time_ms", "open", "high", "low", "close", "volume"])
        w.writerows(rows)


def quality(rows, interval):
    step = INTERVALS[interval]
    if not rows:
        return {"rows": 0, "gaps": 0, "gap_examples": []}
    times = [r[0] for r in rows]
    gaps = 0
    examples = []
    for a, b in zip(times, times[1:]):
        missing = (b - a) // step - 1
        if missing > 0:
            gaps += int(missing)
            if len(examples) < 3:
                examples.append({"after": utc(a), "missing_bars": int(missing)})
    return {"rows": len(rows), "gaps": gaps, "gap_examples": examples}


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def write_readme(out_dir, args, selection, manifest):
    lines = [
        "Dataset candle Tokocrypto (Spec Scalping Spot v3)",
        "",
        f"Sumber    : API Tokocrypto {DEFAULT_BASE} (type 1) dan {OPEN_API_BASE} (type 3)",
        f"Diambil   : {manifest['generated_at_utc']} (UTC)",
        f"Jendela   : {manifest['window']['start_utc']} .. {manifest['window']['end_utc']} UTC"
        + (f" ({args.days} hari)" if args.days else ""),
        f"Interval  : {', '.join(manifest['intervals'])}",
        f"Pair      : {len(selection)} (lihat _selection.json untuk kriteria dan angka 24 jam)",
        "Kolom     : open_time_ms,open,high,low,close,volume  (hanya candle yang sudah CLOSED)",
        "",
        "Perintah pengambilan ulang:",
        "  " + " ".join([sys.executable] + sys.argv),
        "",
        "Catatan:",
        "- Pair dipilih dari daftar resmi Tokocrypto (common/symbols), bukan dari exchangeInfo gateway.",
        "- Statistik 24 jam untuk pemilihan berasal dari endpoint Tokocrypto; bila tidak terbaca,",
        "  tool memakai snapshot cadangan dan mencatatnya di _manifest.json.",
        "- Candle selalu diunduh dari Tokocrypto. Tool ini tidak memanggil Binance.",
        "- Saat --resume dipakai, daftar pair dibekukan dari _selection.json supaya universe tidak",
        "  berubah di tengah pengukuran. Pakai --reselect hanya bila memang ingin memilih ulang.",
        "- _manifest.json memuat jumlah bar, celah, duplikat, SHA-256 tiap CSV, dan kriteria pemilihan.",
        "",
        "Kualitas:",
    ]
    for f in manifest["files"]:
        lines.append(f"  {f['pair']:<14} {f['interval']:<4} bar={f['rows']:<6} celah={f['gaps']:<4} "
                     f"duplikat={f['duplicates']}")
    with open(os.path.join(out_dir, "README.txt"), "w") as fh:
        fh.write("\n".join(lines) + "\n")


def main():
    ap = argparse.ArgumentParser()
    # --- perilaku lama ---
    ap.add_argument("--pairs", default="", help="Daftar manual dipisah koma, format BTCUSDT")
    ap.add_argument("--pages", type=int, default=5,
                    help="Halaman @1000 candle (hanya cara lama / --select pairs). M1: 5 = sekitar 3,5 hari.")
    ap.add_argument("--intervals", default="",
                    help="Daftar interval dipisah koma (1m,15m,1h,4h,1d,1w). Kosong = perilaku lama.")
    ap.add_argument("--out", default="data/tokocrypto")
    ap.add_argument("--base", default=DEFAULT_BASE)
    # --- pemilihan pair ---
    ap.add_argument("--select", default="",
                    choices=["", "pairs", "mixed", "eligible", "stable", "momentum", "reference", "all", "file"],
                    help="Mode pemilihan pair. Kosong = pakai --pairs (perilaku lama).")
    ap.add_argument("--quote", default="USDT", choices=["USDT", "IDR"])
    ap.add_argument("--min-quote-volume", type=float, default=1_000_000.0,
                    help="Ambang volume 24 jam dalam mata uang kuotasi.")
    ap.add_argument("--max-change-pct", type=float, default=2.0,
                    help="Batas |perubahan 24 jam| untuk bucket stabil.")
    ap.add_argument("--max-range-pct", type=float, default=3.0,
                    help="Batas rentang (high-low)/low 24 jam untuk bucket stabil.")
    ap.add_argument("--min-change-pct", type=float, default=5.0,
                    help="Ambang kenaikan 24 jam minimum untuk bucket momentum/referensi.")
    ap.add_argument("--top-stable", type=int, default=12)
    ap.add_argument("--top-momentum", type=int, default=17)
    ap.add_argument("--exclude", default="", help="Daftar basis yang dilewati, dipisah koma.")
    ap.add_argument("--keep-stablecoins", action="store_true",
                    help="Jangan otomatis mengecualikan stablecoin dari bucket stabil.")
    ap.add_argument("--pair-gate", default="data/config/pair_gate.json")
    ap.add_argument("--reference", default=DEFAULT_REFERENCE,
                    help="Snapshot cadangan daftar kenaikan tinggi.")
    ap.add_argument("--include-reference", action="store_true",
                    help="Ikutkan bucket referensi pada mode mixed.")
    ap.add_argument("--file", default="", help="Berkas daftar pair (satu per baris) untuk --select file.")
    # --- jendela dan keluaran ---
    ap.add_argument("--days", type=int, default=0, help="Jendela tetap N hari terakhir.")
    ap.add_argument("--start-ms", type=int, default=0)
    ap.add_argument("--end-ms", type=int, default=0)
    ap.add_argument("--resume", action="store_true",
                    help="Lanjutkan berkas yang sudah ada; daftar pair lama dibekukan.")
    ap.add_argument("--reselect", action="store_true",
                    help="Pilih ulang pair walau --resume dipakai (daftar lama tidak dibekukan).")
    ap.add_argument("--list-only", action="store_true", help="Tampilkan pilihan tanpa mengunduh candle.")
    ap.add_argument("--selection-out", default="", help="Path JSON hasil pemilihan.")
    ap.add_argument("--manifest", default="", help="Path manifest. Kosong = <out>/_manifest.json.")
    ap.add_argument("--write-readme", action="store_true", help="Tulis README.txt di folder keluaran.")
    ap.add_argument("--sleep", type=float, default=0.12)
    args = ap.parse_args()

    if args.base.rstrip("/") != DEFAULT_BASE and args.select:
        print("! peringatan: --base diubah; pemilihan pair tetap memakai host resmi.", file=sys.stderr)

    mode = args.select or ("pairs" if args.pairs else "pairs")
    explicit_pairs = [p.strip().upper() for p in args.pairs.split(",") if p.strip()]
    if mode == "pairs" and not explicit_pairs and args.pairs == "":
        explicit_pairs = [p for p in DEFAULT_PAIRS.split(",") if p]

    os.makedirs(args.out, exist_ok=True)
    warnings = []
    selection = []
    selection_meta = {}

    if mode in ("pairs", "file"):
        if mode == "file":
            if not args.file:
                raise SystemExit("--select file membutuhkan --file")
            explicit_pairs = pairs_from_file(args.file)
        official = {}
        try:
            official = fetch_official_symbols(args.quote)
        except Exception as e:  # noqa: BLE001
            warnings.append(f"daftar resmi gagal diambil ({e}); tipe simbol dianggap 1")
        selection = []
        for pair in explicit_pairs:
            info = official.get(pair) or {"pair": pair, "quote": args.quote, "symbol_type": 1}
            selection.append(_row(pair, info, None, mode))
        selection_meta = {"mode": mode, "quote": args.quote, "count": len(selection), "stats_source": None}
    else:
        official = fetch_official_symbols(args.quote)
        if not official:
            raise SystemExit("daftar simbol resmi kosong; tidak bisa memilih pair")
        stats = None
        stats_source = "tokocrypto"
        try:
            stats = fetch_24h_all()
        except Exception as e:  # noqa: BLE001
            warnings.append(f"ticker 24 jam Tokocrypto gagal ({e}); memakai snapshot cadangan")
            ref, err = load_reference(args.reference)
            if err:
                warnings.append(err)
            else:
                stats = ref["stats"]
                stats_source = f"snapshot:{args.reference}@{ref.get('generated_at_utc')}"
        selection = []
        frozen = None
        prev_path = args.selection_out or os.path.join(args.out, "_selection.json")
        if args.resume and not args.reselect and os.path.exists(prev_path):
            try:
                with open(prev_path) as f:
                    prev = json.load(f)
                if prev.get("mode") == mode and prev.get("quote") == args.quote:
                    frozen = prev
                else:
                    warnings.append(
                        f"daftar lama di {prev_path} memakai mode/kuotasi berbeda; memilih ulang")
            except (OSError, ValueError) as e:
                warnings.append(f"daftar lama tidak terbaca ({e}); memilih ulang")

        if frozen:
            for p in frozen.get("pairs", []):
                pair = str(p.get("pair") or "").upper()
                info = official.get(pair)
                if not info:
                    warnings.append(f"{pair} ada di daftar beku tetapi tidak ada di daftar resmi; dilewati")
                    continue
                row = dict(p)
                row["symbol_type"] = info.get("symbol_type", 1)
                selection.append(row)
            selection_meta = dict(frozen)
            selection_meta["count"] = len(selection)
            selection_meta["frozen_from"] = prev_path
            selection_meta["frozen_at"] = frozen.get("generated_at_utc")
            print(f"Daftar pair dibekukan dari {prev_path} ({len(selection)} pair). "
                  f"Pakai --reselect untuk memilih ulang.")
        else:
            selection = select_pairs(mode, args, official, stats, stats_source, warnings)
            selection_meta = {
            "mode": mode,
            "quote": args.quote,
            "count": len(selection),
            "stats_source": stats_source,
            "criteria": {
                "min_quote_volume": args.min_quote_volume,
                "max_change_pct": args.max_change_pct,
                "max_range_pct": args.max_range_pct,
                "min_change_pct": args.min_change_pct,
                "top_stable": args.top_stable,
                "top_momentum": args.top_momentum,
                "exclude": sorted({p.strip().upper() for p in (args.exclude or "").split(",") if p.strip()}
                                  | (set() if args.keep_stablecoins else STABLECOIN_BASES)),
                "pair_gate": args.pair_gate,
                "reference": args.reference if args.include_reference or mode == "reference" else None,
            },
        }

    if not selection:
        print("Tidak ada pair yang lolos kriteria.", file=sys.stderr)
        for w in warnings:
            print(f"  ! {w}", file=sys.stderr)
        return 1

    buckets = {}
    for row in selection:
        buckets.setdefault(row["bucket"], []).append(row["pair"])
    print(f"=== PILIHAN (mode {selection_meta['mode']}, kuotasi {args.quote}) ===")
    print(f"statistik 24 jam: {selection_meta.get('stats_source') or 'tidak dipakai'}")
    print(f"{'pair':<14}{'bucket':<12}{'24j%':>9}{'volume24j':>16}{'rentang%':>10}{'tipe':>6}")
    for row in selection:
        ch = f"{row['change_24h_pct']:.2f}" if row["change_24h_pct"] is not None else "-"
        qv = f"{row['quote_volume_24h']:,.0f}" if row["quote_volume_24h"] is not None else "-"
        rng = f"{row['range_24h_pct']:.2f}" if row["range_24h_pct"] is not None else "-"
        print(f"{row['pair']:<14}{row['bucket']:<12}{ch:>9}{qv:>16}{rng:>10}{row['symbol_type']:>6}")
    print(f"Total: {len(selection)} pair")
    for bucket, pairs in buckets.items():
        print(f"  {bucket:<10} {len(pairs):>3}: {', '.join(pairs)}")
    for w in warnings:
        print(f"  ! {w}")

    sel_path = args.selection_out or os.path.join(args.out, "_selection.json")
    if (args.select or args.selection_out) and not selection_meta.get("frozen_from"):
        with open(sel_path, "w") as f:
            json.dump({"generated_at_utc": dt.datetime.now(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
                       **selection_meta, "buckets": buckets, "pairs": selection,
                       "warnings": warnings}, f, indent=2)
        print(f"Pilihan ditulis: {sel_path}")

    if args.list_only:
        return 0

    # --- rencana unduhan ---
    intervals = [iv.strip() for iv in args.intervals.split(",") if iv.strip()]
    if args.days or args.start_ms:
        if not intervals:
            intervals = ["15m", "1h"]
        plan = []
        for iv in intervals:
            if iv not in INTERVALS:
                raise SystemExit(f"interval tidak dikenal: {iv}")
            start_ms, end_ms = window_for(args, iv)
            plan.append((iv, start_ms, end_ms))
    else:
        if intervals:
            plan = []
            for iv in intervals:
                if iv not in INTERVALS:
                    raise SystemExit(f"interval tidak dikenal: {iv}")
                end_ms = args.end_ms or int(time.time() * 1000)
                start_ms = end_ms - args.pages * 1000 * INTERVALS[iv]
                plan.append((iv, start_ms, end_ms))
        else:
            m1_minutes = args.pages * 1000
            end_ms = args.end_ms or int(time.time() * 1000)
            start_ms = end_ms - m1_minutes * 60_000
            plan = [("1m", start_ms, end_ms),
                    ("15m", start_ms, end_ms),
                    ("1h", start_ms, end_ms)]

    manifest = {
        "tool": "tools/fetch_tokocrypto_klines.py",
        "tool_version": TOOL_VERSION,
        "generated_at_utc": dt.datetime.now(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "base_url": args.base,
        "open_api_base": OPEN_API_BASE,
        "window": {
            "start_ms": min(p[1] for p in plan),
            "start_utc": utc(min(p[1] for p in plan)),
            "end_ms": max(p[2] for p in plan),
            "end_utc": utc(max(p[2] for p in plan)),
            "days": args.days or None,
        },
        "intervals": [p[0] for p in plan],
        "selection": {"meta": selection_meta, "buckets": buckets},
        "files": [],
        "warnings": warnings,
    }

    ok_pairs = 0
    for row in selection:
        pair = row["pair"]
        stype = int(row["symbol_type"])
        pair_ok = True
        for interval, start_ms, end_ms in plan:
            path = os.path.join(args.out, f"{pair}_{interval}.csv")
            step = INTERVALS[interval]
            existing_rows = read_csv_rows(path) if args.resume else []
            missing = []
            if existing_rows:
                have_first, have_last = existing_rows[0][0], existing_rows[-1][0]
                if start_ms < have_first:
                    missing.append((start_ms, have_first))
                if have_last + step < end_ms:
                    missing.append((have_last + step, end_ms))
                if not missing:
                    q = quality(existing_rows, interval)
                    print(f"{pair} {interval}: dilewati (resume, {len(existing_rows)} bar)")
                    manifest["files"].append({
                        "pair": pair, "interval": interval, "path": path, "rows": len(existing_rows),
                        "first_ms": existing_rows[0][0], "first_utc": utc(existing_rows[0][0]),
                        "last_ms": existing_rows[-1][0], "last_utc": utc(existing_rows[-1][0]),
                        "gaps": q["gaps"], "gap_examples": q["gap_examples"],
                        "duplicates": 0, "fetched_bars": 0,
                        "sha256": sha256_file(path), "resumed": True,
                    })
                    continue
            else:
                missing = [(start_ms, end_ms)]

            rows = list(existing_rows)
            fetched = 0
            try:
                for win_start, win_end in missing:
                    part = fetch_window(args.base, pair, stype, interval, win_start, win_end)
                    rows.extend(part)
                    fetched += len(part)
                    time.sleep(args.sleep)
            except Exception as e:  # noqa: BLE001
                print(f"{pair} {interval}: GAGAL - {e}", file=sys.stderr)
                warnings.append(f"{pair} {interval}: {e}")
                pair_ok = False
                break

            now_ms = int(time.time() * 1000)
            by_time = {r[0]: r for r in rows}
            rows = [by_time[k] for k in sorted(by_time)
                    if start_ms <= k < end_ms and k + step <= now_ms]
            if not rows:
                print(f"{pair} {interval}: 0 bar (lewati)", file=sys.stderr)
                warnings.append(f"{pair} {interval}: 0 bar")
                pair_ok = False
                break
            write_csv(path, rows)
            q = quality(rows, interval)
            manifest["files"].append({
                "pair": pair, "interval": interval, "path": path, "rows": len(rows),
                "first_ms": rows[0][0], "first_utc": utc(rows[0][0]),
                "last_ms": rows[-1][0], "last_utc": utc(rows[-1][0]),
                "gaps": q["gaps"], "gap_examples": q["gap_examples"],
                "duplicates": 0, "fetched_bars": fetched,
                "sha256": sha256_file(path), "resumed": bool(existing_rows),
            })
            extra = f", +{fetched} baru" if existing_rows else ""
            print(f"{pair} {interval}: {len(rows)} bar{extra} -> {path} (celah {q['gaps']})")
        if pair_ok:
            ok_pairs += 1

    manifest_path = args.manifest or os.path.join(args.out, "_manifest.json")
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=2)
    if args.write_readme:
        write_readme(args.out, args, selection, manifest)

    total_rows = sum(int(x["rows"] or 0) for x in manifest["files"])
    total_gaps = sum(int(x["gaps"] or 0) for x in manifest["files"])
    print(f"\nSelesai: {ok_pairs}/{len(selection)} pair berhasil, {total_rows} bar, celah {total_gaps}.")
    print(f"Manifest: {manifest_path}")
    if warnings:
        print(f"Peringatan: {len(warnings)} (lihat manifest dan keluaran di atas).", file=sys.stderr)
    return 0 if ok_pairs else 1


if __name__ == "__main__":
    sys.exit(main())
