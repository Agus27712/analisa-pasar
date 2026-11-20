"""Shared venue layer for the Tokocrypto day-trader pack. Standard library only.

One place for: market-data hosts, symbol rules, disk cache, rate limiting,
indicators, fees and UTC clock. Every script in this pack imports it, so the
venue contract is defined once.

Data host (Binance-compatible public API):
  https://data-api.binance.vision/api/v3/...

The Tokocrypto mirror (www.tokocrypto.site) is deliberately NOT contacted: it is
the app's own feed host and on 2026-11-18 it answered 429 across the whole edge.
Vision serves the identical book, so nothing is lost.
"""

import datetime
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

UA = "Mozilla/5.0 (compatible; day-trader-tokocrypto/1.0)"
PAUSE = 0.12          # seconds between requests: stay well inside the weight budget
BACKOFF = 2.0         # seconds before the single retry after a 5xx
RATE_LIMIT_PAUSE = 60  # a host that says 429 is out for this long; never retry it
TIMEOUT = 20

# data-api.binance.vision is the same book as the Tokocrypto mirror (identical
# klines, tickers and exchangeInfo, verified 2026-11-18) and it does not belong
# to the app.
#
# The mirror is NOT in HOSTS and must stay out of it. On 2026-11-18 the whole
# tokocrypto.site edge (CloudFront, including / and /ping) answered 429 with no
# rate-limit headers, and the standing instruction is to stop calling it so the
# household IP is not banned. The app's own feed already hits it; we add zero
# requests. MIRROR survives only so a test or an explicit `hosts=(...)` can name
# it — never as a fallback.
MIRROR = "https://www.tokocrypto.site/api/v3"
VISION = "https://data-api.binance.vision/api/v3"
HOSTS = (VISION,)
# Every host this module can be asked about by name. Used only to attribute a
# response (and therefore a quarantine) to a base URL.
KNOWN_HOSTS = (VISION, MIRROR)

INTERVAL_SECONDS = {
    "1m": 60, "3m": 180, "5m": 300, "15m": 900, "30m": 1800,
    "1h": 3600, "2h": 7200, "4h": 14400, "6h": 21600,
    "8h": 28800, "12h": 43200, "1d": 86400,
}
CACHE_TTL = {"1m": 20, "3m": 30, "5m": 60, "15m": 120, "30m": 180, "1h": 300,
             "2h": 300, "4h": 600, "6h": 600, "8h": 600, "12h": 600, "1d": 600}

# Fee schedule mirrors the Analisa Pasar app's FeeSchedule (all-in per side).
# USDT-like quotes: maker = taker. IDR: buy has no PPh, sell carries PPh + ICEx.
USDT_LIKE = ("USDT", "USDC", "BUSD", "FDUSD", "TUSD", "USD", "USD1", "U", "DAI")
SLIPPAGE_PER_SIDE = 0.0008   # mirrors the app's DEFAULT_SLIPPAGE_PCT

QUOTES = ("USDT", "IDR", "USDC", "BTC", "ETH", "BNB", "FDUSD", "TUSD", "USD1", "U")

SYMBOL_RE = re.compile(r"^[A-Z0-9]{5,20}$")


class VenueError(Exception):
    """A data or symbol problem the caller must report, never paper over."""


# ---------------------------------------------------------------------------
# clock (UTC everywhere: crypto never closes)
# ---------------------------------------------------------------------------

def utc_now():
    return datetime.datetime.now(datetime.timezone.utc)


def utc_of(ms):
    return datetime.datetime.fromtimestamp(ms / 1000.0, datetime.timezone.utc)


def ms_of(dt):
    return int(dt.timestamp() * 1000)


def iso(dt):
    return dt.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_now(text):
    """Accept 'YYYY-MM-DD HH:MM', 'YYYY-MM-DDTHH:MM', or ISO with Z. UTC."""
    if not text:
        return utc_now()
    t = text.strip().replace("T", " ")
    if t.endswith("Z"):
        t = t[:-1]
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            dt = datetime.datetime.strptime(t, fmt)
            return dt.replace(tzinfo=datetime.timezone.utc)
        except ValueError:
            continue
    raise VenueError("cannot read time %r (use 'YYYY-MM-DD HH:MM' UTC)" % text)


def day_start(dt):
    return dt.replace(hour=0, minute=0, second=0, microsecond=0)


def session_start(dt, hhmm="00:00"):
    """Start of the trading session that contains dt, in UTC."""
    h, m = [int(x) for x in hhmm.split(":")]
    s = dt.replace(hour=h, minute=m, second=0, microsecond=0)
    if s > dt:
        s -= datetime.timedelta(days=1)
    return s


# ---------------------------------------------------------------------------
# symbols
# ---------------------------------------------------------------------------

def split_symbol(symbol):
    """BTCUSDT -> ('BTC', 'USDT'). Longest quote first so BTCUSDT is not 'U'."""
    clean = (symbol or "").strip().upper().replace("/", "").replace("_", "").replace("-", "")
    if not SYMBOL_RE.match(clean):
        raise VenueError("not a venue symbol: %r (want BASEQUOTE, e.g. BTCUSDT)" % symbol)
    for q in sorted(QUOTES, key=len, reverse=True):
        if clean.endswith(q) and len(clean) > len(q):
            return clean[: -len(q)], q
    raise VenueError("unknown quote asset in %r" % symbol)


def pair(symbol):
    return split_symbol(symbol)[0] + split_symbol(symbol)[1]


def quote_of(symbol):
    return split_symbol(symbol)[1]


def fees(quote):
    """All-in per-side rates, mirroring the app's FeeSchedule."""
    q = quote.upper()
    if q in USDT_LIKE:
        return {"buy": 0.004044, "sell": 0.004044, "quote": q, "kind": "USDT"}
    return {"buy": 0.002222, "sell": 0.004322, "quote": q, "kind": "IDR"}


def round_trip_pct(quote):
    """Fee + slippage cost of a full round trip, in percent."""
    f = fees(quote)
    return (f["buy"] + f["sell"] + 2 * SLIPPAGE_PER_SIDE) * 100.0


# ---------------------------------------------------------------------------
# cache
# ---------------------------------------------------------------------------

def cache_dir(root="trader/cache"):
    d = os.path.join(root, "venue")
    os.makedirs(d, exist_ok=True)
    return d


def _cache_path(root, key):
    safe = re.sub(r"[^A-Za-z0-9._-]", "_", key)
    return os.path.join(cache_dir(root), safe + ".json")


def cache_get(root, key, ttl):
    p = _cache_path(root, key)
    try:
        age = time.time() - os.path.getmtime(p)
    except OSError:
        return None
    if ttl is not None and age > ttl:
        return None
    try:
        with open(p, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


def cache_put(root, key, value):
    p = _cache_path(root, key)
    tmp = p + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(value, fh)
        os.replace(tmp, p)
    except OSError:
        pass


# ---------------------------------------------------------------------------
# http
# ---------------------------------------------------------------------------

_last_call = [0.0]


def _throttle():
    gap = time.time() - _last_call[0]
    if gap < PAUSE:
        time.sleep(PAUSE - gap)
    _last_call[0] = time.time()


_quarantine = {}   # host base -> epoch until it may be asked again


def _host_of(url):
    """Which known host a URL belongs to.

    KNOWN_HOSTS, not HOSTS: the app's mirror is not a default host, but a caller
    may still name it explicitly and a 429 from it has to be remembered too.
    """
    for h in KNOWN_HOSTS:
        if url.startswith(h):
            return h
    return None


def _is_quarantined(host):
    until = _quarantine.get(host)
    if until is None:
        return False
    if time.time() >= until:
        del _quarantine[host]
        return False
    return True


def http_json(url, timeout=TIMEOUT, retries=1):
    """GET a JSON document. One polite retry after a 5xx.

    A 429/418 is never retried on the same host: the host is quarantined and the
    caller moves to the next one. Retrying a throttled host costs a backoff per
    symbol, which is how a 120-symbol scan turns into a 300-second crawl, and it
    keeps the mirror throttled while the app needs it.
    """
    attempt = 0
    while True:
        _throttle()
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode("utf-8", "replace"))
        except urllib.error.HTTPError as exc:
            if exc.code in (429, 418):
                host = _host_of(url)
                if host:
                    _quarantine[host] = time.time() + RATE_LIMIT_PAUSE
                raise VenueError("rate limited (HTTP %s) by %s" % (exc.code, host or url))
            if exc.code in (500, 502, 503, 504) and attempt < retries:
                attempt += 1
                time.sleep(BACKOFF * attempt)
                continue
            raise VenueError("HTTP %s from %s" % (exc.code, url))
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            if attempt < retries:
                attempt += 1
                time.sleep(BACKOFF * attempt)
                continue
            raise VenueError("cannot reach %s (%s)" % (url, exc))
        except ValueError as exc:
            raise VenueError("bad JSON from %s (%s)" % (url, exc))



def http_get(url, timeout=TIMEOUT, retries=1):
    """GET raw bytes (RSS, XML). Same throttle and retry policy as http_json."""
    attempt = 0
    while True:
        _throttle()
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/rss+xml, application/xml, text/xml, */*"})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.read()
        except urllib.error.HTTPError as exc:
            if exc.code in (429, 418):
                raise VenueError("rate limited (HTTP %s) by %s" % (exc.code, url))
            if exc.code in (500, 502, 503, 504) and attempt < retries:
                attempt += 1
                time.sleep(BACKOFF * attempt)
                continue
            raise VenueError("HTTP %s from %s" % (exc.code, url))
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            if attempt < retries:
                attempt += 1
                time.sleep(BACKOFF * attempt)
                continue
            raise VenueError("cannot reach %s (%s)" % (url, exc))


def _hosts_json(path, params, hosts=HOSTS, timeout=TIMEOUT):
    """First host that answers; quarantined hosts are skipped, not retried."""
    errors = []
    for host in hosts:
        if _is_quarantined(host):
            errors.append("%s: quarantined for %ds" % (host, RATE_LIMIT_PAUSE))
            continue
        url = "%s/%s?%s" % (host, path, params)
        try:
            return http_json(url, timeout=timeout), host
        except VenueError as exc:
            errors.append(str(exc))
    raise VenueError("no host available: " + " | ".join(errors))


# ---------------------------------------------------------------------------
# market data
# ---------------------------------------------------------------------------

def offline_klines(folder, symbol, interval):
    for name in ("%s-%s.json" % (symbol.upper(), interval), "%s.json" % symbol.upper()):
        p = os.path.join(folder, name)
        if os.path.exists(p):
            with open(p, encoding="utf-8") as fh:
                return json.load(fh)
    raise VenueError("offline: no %s-%s.json in %s" % (symbol.upper(), interval, folder))


def klines(symbol, interval="5m", limit=200, end_ms=None, offline=None,
           cache_root="trader/cache", ttl=None, host_out=None):
    """Closed+forming candles as dicts. Oldest first.

    Each row: {open_ms, open, high, low, close, volume, close_ms, closed}
    `closed` is computed against the wall clock (or the newest close time when
    replaying offline), so a forming candle is never mistaken for a finished one.
    """
    if interval not in INTERVAL_SECONDS:
        raise VenueError("unsupported interval %r" % interval)
    sym = pair(symbol)
    if offline:
        raw = offline_klines(offline, sym, interval)
        source = "offline"
    else:
        key = "klines-%s-%s-%s-%s" % (sym, interval, limit, end_ms or "")
        raw = cache_get(cache_root, key, CACHE_TTL[interval] if ttl is None else ttl)
        if raw is None:
            params = "symbol=%s&interval=%s&limit=%d" % (sym, interval, limit)
            if end_ms:
                params += "&endTime=%d" % end_ms
            raw, host = _hosts_json("klines", params)
            if host_out is not None:
                host_out.append(host)
            cache_put(cache_root, key, raw)
        source = "cache"
    if not isinstance(raw, list) or not raw:
        raise VenueError("no candles for %s %s" % (sym, interval))
    rows = []
    for r in raw:
        try:
            rows.append({
                "open_ms": int(r[0]),
                "open": float(r[1]),
                "high": float(r[2]),
                "low": float(r[3]),
                "close": float(r[4]),
                "volume": float(r[5]),
                "close_ms": int(r[6]),
            })
        except (IndexError, TypeError, ValueError):
            continue
    if not rows:
        raise VenueError("unreadable candles for %s %s" % (sym, interval))
    newest_close = max(r["close_ms"] for r in rows)
    now_ms = ms_of(utc_now())
    for r in rows:
        r["closed"] = r["close_ms"] <= (newest_close if offline else now_ms)
    return rows


def klines_paged(symbol, interval="5m", want=1000, offline=None,
                 cache_root="trader/cache", max_pages=12):
    """Fetch more candles than one request allows, walking backwards.

    The venue caps a single klines answer at 1000 rows, so a 60-day window of
    5m bars needs several requests. Pages are cached individually, so a repeat
    run costs nothing.
    """
    if interval not in INTERVAL_SECONDS:
        raise VenueError("unsupported interval %r" % interval)
    if offline:
        return klines(symbol, interval, limit=want, offline=offline, cache_root=cache_root)
    step = INTERVAL_SECONDS[interval] * 1000
    out = []
    end_ms = None
    for _ in range(max_pages):
        page = klines(symbol, interval, limit=min(1000, want - len(out)),
                      end_ms=end_ms, cache_root=cache_root)
        if not page:
            break
        out = page + out
        if len(out) >= want:
            break
        oldest = min(r["open_ms"] for r in page)
        end_ms = oldest - step
        if len(page) < 2:
            break
    # De-duplicate on open_ms, keep the newest copy, and stay oldest-first.
    seen = {}
    for r in out:
        seen[r["open_ms"]] = r
    rows = [seen[k] for k in sorted(seen)]
    return rows[-want:]


def closed_only(rows):
    return [r for r in rows if r["closed"]]


def ticker24h(symbol, offline=None, cache_root="trader/cache"):
    sym = pair(symbol)
    if offline:
        p = os.path.join(offline, "ticker-%s.json" % sym)
        if not os.path.exists(p):
            raise VenueError("offline: no ticker-%s.json in %s" % (sym, offline))
        with open(p, encoding="utf-8") as fh:
            body = json.load(fh)
    else:
        key = "ticker-%s" % sym
        body = cache_get(cache_root, key, 30)
        if body is None:
            body, _ = _hosts_json("ticker/24hr", "symbol=%s" % sym)
            cache_put(cache_root, key, body)
    if not isinstance(body, dict) or "lastPrice" not in body:
        raise VenueError("no 24h ticker for %s" % sym)
    return {
        "symbol": sym,
        "price": float(body["lastPrice"]),
        "change_pct": float(body.get("priceChangePercent") or 0.0),
        "quote_volume": float(body.get("quoteVolume") or 0.0),
        "high": float(body.get("highPrice") or 0.0),
        "low": float(body.get("lowPrice") or 0.0),
        "trades": int(body.get("count") or 0),
    }


def exchange_info(offline=None, cache_root="trader/cache"):
    if offline:
        p = os.path.join(offline, "exchangeInfo.json")
        if not os.path.exists(p):
            raise VenueError("offline: no exchangeInfo.json in %s" % offline)
        with open(p, encoding="utf-8") as fh:
            return json.load(fh)
    body = cache_get(cache_root, "exchangeInfo", 3600)
    if body is None:
        body, _ = _hosts_json("exchangeInfo", "")
        cache_put(cache_root, "exchangeInfo", body)
    return body

def trading_symbols(quote="USDT", offline=None, cache_root="trader/cache"):
    """Trading symbols for one quote asset, sorted. Never mixes quotes."""
    info = exchange_info(offline=offline, cache_root=cache_root)
    out = []
    for s in info.get("symbols", []):
        if s.get("status") != "TRADING":
            continue
        if s.get("quoteAsset") != quote.upper():
            continue
        if not SYMBOL_RE.match(s["symbol"]):
            continue
        out.append(s["symbol"])
    return sorted(set(out))



def symbol_filters(symbol, offline=None, cache_root="trader/cache"):
    """minQty / stepSize / minNotional for sizing and order validation."""
    info = exchange_info(offline=offline, cache_root=cache_root)
    sym = pair(symbol)
    for s in info.get("symbols", []):
        if s.get("symbol") != sym:
            continue
        out = {"min_qty": 0.0, "step": 0.0, "min_notional": 0.0, "tick": 0.0}
        for f in s.get("filters", []):
            t = f.get("filterType")
            if t == "LOT_SIZE":
                out["min_qty"] = float(f.get("minQty") or 0)
                out["step"] = float(f.get("stepSize") or 0)
            elif t in ("MIN_NOTIONAL", "NOTIONAL"):
                out["min_notional"] = float(f.get("minNotional") or 0)
            elif t == "PRICE_FILTER":
                out["tick"] = float(f.get("tickSize") or 0)
        return out
    raise VenueError("%s is not listed on this venue" % sym)


# ---------------------------------------------------------------------------
# indicators (closes/highs/lows are plain float lists, oldest first)
# ---------------------------------------------------------------------------

def sma(values, n):
    if len(values) < n or n <= 0:
        return None
    return sum(values[-n:]) / float(n)


def ema(values, n):
    if len(values) < n or n <= 0:
        return None
    k = 2.0 / (n + 1)
    e = sum(values[:n]) / float(n)
    for v in values[n:]:
        e = v * k + e * (1 - k)
    return e


def rsi(values, n=14):
    """Wilder RSI over the whole series; None when there is not enough data."""
    if len(values) < n + 1:
        return None
    gains = 0.0
    losses = 0.0
    for i in range(1, n + 1):
        d = values[i] - values[i - 1]
        gains += max(d, 0.0)
        losses += max(-d, 0.0)
    ag = gains / n
    al = losses / n
    for i in range(n + 1, len(values)):
        d = values[i] - values[i - 1]
        ag = (ag * (n - 1) + max(d, 0.0)) / n
        al = (al * (n - 1) + max(-d, 0.0)) / n
    if al == 0:
        return 100.0
    rs = ag / al
    return 100.0 - 100.0 / (1.0 + rs)


def atr(rows, n=14):
    """Average true range over candle dicts."""
    if len(rows) < n + 1:
        return None
    trs = []
    for i in range(1, len(rows)):
        h, l, pc = rows[i]["high"], rows[i]["low"], rows[i - 1]["close"]
        trs.append(max(h - l, abs(h - pc), abs(l - pc)))
    if len(trs) < n:
        return None
    return sum(trs[-n:]) / float(n)


def stdev_pct(values):
    """Standard deviation of simple returns, in percent."""
    if len(values) < 3:
        return None
    rets = [values[i] / values[i - 1] - 1.0 for i in range(1, len(values)) if values[i - 1] > 0]
    if len(rets) < 2:
        return None
    m = sum(rets) / len(rets)
    var = sum((r - m) ** 2 for r in rets) / (len(rets) - 1)
    return (var ** 0.5) * 100.0


def round_step(value, step):
    if not step or step <= 0:
        return value
    return int(value / step) * step


# ---------------------------------------------------------------------------
# first-passage barrier test (the no-trade gate)
# ---------------------------------------------------------------------------

def barrier_ev(rows, target_pct, stop_pct, horizon_bars, cost_pct, step=1):
    """First-passage expectancy over candle closes.

    Entry at a bar close, exit at the first touch of target or stop (stop wins
    on an ambiguous bar), or at the horizon close. Returns gross/net percent
    per trade plus the hit counts, so a caller can see whether the sample is
    real or an artifact of overlapping windows.
    """
    if horizon_bars < 1:
        raise VenueError("horizon must be at least one bar")
    n = len(rows)
    hits_t = hits_s = timed = 0
    gross = 0.0
    samples = 0
    for i in range(0, n - horizon_bars, step):
        entry = rows[i]["close"]
        if entry <= 0:
            continue
        up = entry * (1.0 + target_pct / 100.0)
        dn = entry * (1.0 - stop_pct / 100.0)
        outcome = None
        for j in range(i + 1, min(i + 1 + horizon_bars, n)):
            hi, lo = rows[j]["high"], rows[j]["low"]
            if lo <= dn:
                outcome = -stop_pct
                hits_s += 1
                break
            if hi >= up:
                outcome = target_pct
                hits_t += 1
                break
        if outcome is None:
            last = rows[min(i + horizon_bars, n - 1)]["close"]
            outcome = (last / entry - 1.0) * 100.0
            timed += 1
        gross += outcome
        samples += 1
    if samples == 0:
        return None
    g = gross / samples
    return {
        "samples": samples,
        "target_hits": hits_t,
        "stop_hits": hits_s,
        "timed_exits": timed,
        "gross_pct": g,
        "net_pct": g - cost_pct,
        "cost_pct": cost_pct,
        "target_pct": target_pct,
        "stop_pct": stop_pct,
        "horizon_bars": horizon_bars,
    }


def breakeven_hit_rate(target_pct, stop_pct, cost_pct):
    """Hit rate the target must reach for the trade to break even after cost."""
    denom = target_pct + stop_pct
    if denom <= 0:
        return None
    return (stop_pct + cost_pct) / denom * 100.0


# ---------------------------------------------------------------------------
# small io helpers
# ---------------------------------------------------------------------------

def read_json(path, default=None):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return default


def write_json(path, data):
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2, sort_keys=True)
        fh.write("\n")
    os.replace(tmp, path)


def money(x, quote="USDT"):
    return "%.8f %s" % (x, quote) if abs(x) < 1 else "%.2f %s" % (x, quote)


def pct(x):
    return "%+.2f%%" % x

def tickers24h_all(offline=None, cache_root="trader/cache"):
    """Every 24h ticker in one request: {symbol: row}. Used to prefilter."""
    if offline:
        p = os.path.join(offline, "tickers24h.json")
        if not os.path.exists(p):
            raise VenueError("offline: no tickers24h.json in %s" % offline)
        with open(p, encoding="utf-8") as fh:
            body = json.load(fh)
    else:
        body = cache_get(cache_root, "tickers24h", 60)
        if body is None:
            body, _ = _hosts_json("ticker/24hr", "")
            cache_put(cache_root, "tickers24h", body)
    if not isinstance(body, list):
        raise VenueError("unreadable 24h tickers")
    out = {}
    for r in body:
        try:
            out[r["symbol"]] = {
                "symbol": r["symbol"],
                "price": float(r["lastPrice"]),
                "change_pct": float(r.get("priceChangePercent") or 0.0),
                "quote_volume": float(r.get("quoteVolume") or 0.0),
                "high": float(r.get("highPrice") or 0.0),
                "low": float(r.get("lowPrice") or 0.0),
                "trades": int(r.get("count") or 0),
            }
        except (KeyError, TypeError, ValueError):
            continue
    return out
