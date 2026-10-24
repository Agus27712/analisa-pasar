#!/usr/bin/env python3
"""Uji parity replay terhadap engine/fee truth. Jalankan: python3 tools/test_scalp_replay_parity.py"""
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import scalp_replay as replay  # noqa: E402
import entry_percentile as entry_pct  # noqa: E402
import exit_mode_sweep as exit_sweep  # noqa: E402


def candle(t, price=100.0, high=None, low=None, close=None, volume=1.0):
    return {
        "t": t,
        "o": price,
        "h": price if high is None else high,
        "l": price if low is None else low,
        "c": price if close is None else close,
        "v": volume,
    }


def flat_candles(n, price=100.0):
    return [candle(i * 60_000, price) for i in range(n)]


def trend_candles(n, start=100.0, step=0.5, t_step=60_000):
    """Candle monoton; dipakai untuk menguji leg MTF dan pulse BTC."""
    rows = []
    price = start
    for i in range(n):
        open_price = price
        close = price + step
        rows.append(candle(
            i * t_step,
            open_price,
            high=max(open_price, close) + 0.1,
            low=min(open_price, close) - 0.1,
            close=close,
        ))
        price = close
    return rows


def dump_btc_candles():
    rows = trend_candles(37, start=100.0, step=0.3, t_step=300_000)
    rows.append(candle(37 * 300_000, 111.6, high=111.8, low=111.4, close=111.8))
    rows.append(candle(38 * 300_000, 112.0, high=112.1, low=110.9, close=111.0))
    rows.append(candle(39 * 300_000, 111.0, high=111.1, low=103.9, close=104.0))
    return rows


def canonical_cfg():
    with open(ROOT / "data/config/scalp_config.json") as f:
        return replay.merge_defaults(json.load(f))


def usdt_fee():
    return {
        "quote": "USDT",
        "buy_taker_pct": 0.4044,
        "sell_taker_pct": 0.4044,
        "buy_maker_pct": 0.4044,
        "sell_maker_pct": 0.4044,
    }


class ConfigParityTest(unittest.TestCase):
    def test_canonical_config_matches_app_defaults(self):
        cfg = canonical_cfg()
        self.assertEqual(60, cfg["min_score_long"])
        self.assertEqual(75, cfg["min_score_strong"])
        self.assertEqual(75, cfg["min_score_breakout_retest"])
        self.assertEqual(1.15, cfg["min_net_rr"])
        self.assertEqual(5.0, cfg["max_tp2_r"])
        self.assertEqual(2.0, cfg["max_risk_atr"])
        self.assertEqual(0.0, cfg["max_change_24h_pct"])
        self.assertEqual(1.5, cfg["rvol_breakout_min"])
        self.assertEqual(1.5, cfg["rvol_retest_min"])
        self.assertEqual(1.15, cfg["buy_pressure_retest"])
        self.assertEqual(0.995, cfg["retest_above_factor"])
        self.assertEqual("classic", cfg["exit"]["mode"])
        self.assertEqual(90, cfg["exit"]["max_hold_bars_m1"])
        self.assertNotIn("order_type", cfg)
        # P0 — angka gate harus datang dari config yang sama dengan ScalpingRuntimeConfig.
        self.assertEqual(5, cfg["ttl_bars_m1"])
        self.assertEqual(3, cfg["mtf_min_bullish"])
        self.assertTrue(cfg["mtf_block_high_tf_bearish"])
        self.assertTrue(cfg["btc_veto_enabled"])
        self.assertEqual(-0.6, cfg["btc_return_veto_pct"])
        self.assertEqual(2.5, cfg["btc_atr_ratio_veto"])
        self.assertEqual(20, cfg["btc_ema_period"])
        self.assertEqual(25.0, cfg["regime_adx_trending"])
        self.assertEqual(20.0, cfg["regime_adx_weak"])
        self.assertEqual(61.8, cfg["regime_chop_ranging"])
        self.assertEqual(55.0, cfg["regime_chop_trend_max"])

    def test_idr_pair_uses_idr_reference(self):
        cfg = canonical_cfg()
        usdt = replay.resolve_fee(cfg, "BTCUSDT")
        idr = replay.resolve_fee(cfg, "BTCIDR")
        self.assertEqual("USDT", usdt["quote"])
        self.assertEqual(0.4044, usdt["buy_taker_pct"])
        self.assertEqual("IDR", idr["quote"])
        self.assertEqual(0.2222, idr["buy_taker_pct"])
        self.assertEqual(0.4322, idr["sell_taker_pct"])

    def test_standalone_idr_config_uses_top_level_fee(self):
        with open(ROOT / "data/config/scalp_config_exit_full_idr.json") as f:
            cfg = replay.merge_defaults(json.load(f))
        fee = replay.resolve_fee(cfg, "BTCIDR")
        self.assertEqual("IDR", fee["quote"])
        self.assertEqual(0.2222, fee["buy_taker_pct"])
        self.assertEqual(0.4322, fee["sell_taker_pct"])


class SetupGateTest(unittest.TestCase):
    def _structure(self):
        return {
            "bias": "BULLISH",
            "pattern": "RANGING",
            "bos": False,
            "choch": False,
            "strength": 70,
            "support": None,
            "resistance": 100.0,
            "sweep": False,
            "retest_valid": False,
            "hhhl": False,
            "lhll": False,
            "last_swing_low": None,
        }

    def test_breakout_requires_rvol(self):
        cfg = canonical_cfg()
        rg = {"regime": "RANGING", "chop": 0.0, "atr_pct": 1.0, "ema": "mixed"}
        m1 = [candle(i * 60_000, 100.0 + i * 0.01) for i in range(20)]
        without_volume = replay.detect_setup(
            101.0, self._structure(), rg, 0.5, 1.2, m1, cfg
        )
        with_volume = replay.detect_setup(
            101.0, self._structure(), rg, 2.0, 1.2, m1, cfg
        )
        self.assertNotEqual("BREAKOUT", without_volume)
        self.assertEqual("BREAKOUT", with_volume)

    def test_extended_move_blocks_step1(self):
        cfg = canonical_cfg()
        cfg["max_change_24h_pct"] = 5.0
        m1 = flat_candles(20)
        result = replay.evaluate_bar(
            100.0, m1, m1, m1, cfg, usdt_fee(), True, change24h=10.0
        )
        normal = replay.evaluate_bar(
            100.0, m1, m1, m1, cfg, usdt_fee(), True, change24h=0.0
        )
        self.assertFalse(result["steps"][0])
        self.assertNotEqual("ENTRY", result["stage"])
        self.assertTrue(normal["steps"][0])


class RiskTest(unittest.TestCase):
    def _inputs(self):
        st = {
            "bias": "BULLISH",
            "pattern": "RANGING",
            "bos": False,
            "choch": False,
            "strength": 70,
            "support": 80.0,
            "resistance": 120.0,
            "sweep": False,
            "retest_valid": False,
            "hhhl": False,
            "lhll": False,
            "last_swing_low": 80.0,
        }
        rg = {"regime": "RANGING", "chop": 0.0, "atr_pct": 5.0, "ema": "mixed"}
        return st, rg

    def test_max_risk_atr_is_read_from_config(self):
        cfg = canonical_cfg()
        st, rg = self._inputs()

        cfg["max_risk_atr"] = 2.0
        tight = replay.risk_levels(100.0, "BREAKOUT", st, rg, usdt_fee(), cfg)
        cfg["max_risk_atr"] = 3.0
        wide = replay.risk_levels(100.0, "BREAKOUT", st, rg, usdt_fee(), cfg)

        self.assertAlmostEqual(10.0, tight["entry"] - tight["sl"], places=6)
        self.assertAlmostEqual(15.0, wide["entry"] - wide["sl"], places=6)
        self.assertGreater(wide["entry"] - wide["sl"], tight["entry"] - tight["sl"])

    def test_sl_floor_active_when_structure_is_tighter_than_friction(self):
        cfg = canonical_cfg()
        st, rg = self._inputs()
        st["support"] = 98.5
        st["last_swing_low"] = 98.5
        result = replay.risk_levels(100.0, "BREAKOUT", st, rg, usdt_fee(), cfg)
        self.assertTrue(result["sl_floor_active"])


class ExitTest(unittest.TestCase):
    def _trade_input(self):
        bars = [candle(0, 100.0)]
        for i in range(1, 121):
            bars.append(candle(i * 60_000, 100.0, high=101.0, low=99.0))
        return bars

    def test_classic_still_has_time_stop(self):
        cfg = canonical_cfg()
        cfg["exit"]["mode"] = "classic"
        cfg["exit"]["max_hold_bars_m1"] = 90
        result = replay.resolve_trade(
            self._trade_input(), 0, 100.0, 90.0, 110.0, 120.0,
            usdt_fee(), cfg, 120, atr_pct=1.0
        )
        self.assertEqual("TIMEOUT", result["exit_reason"])
        self.assertEqual(90, result["exit_idx"])
        self.assertIn(result["result"], ("WIN", "LOSS"))

    def test_full_mode_hits_tp2(self):
        cfg = canonical_cfg()
        cfg["exit"]["mode"] = "full"
        bars = [candle(0, 100.0), candle(60_000, 100.0, high=121.0, low=99.0)]
        result = replay.resolve_trade(
            bars, 0, 100.0, 90.0, 110.0, 120.0,
            usdt_fee(), cfg, 120, atr_pct=1.0
        )
        self.assertEqual("TP2", result["exit_reason"])

    def test_net_tp_exits_at_configured_net_target(self):
        cfg = canonical_cfg()
        cfg["exit"]["mode"] = "net_tp"
        cfg["exit"]["net_tp_pct"] = 0.5
        target_px = replay._price_for_net(100.0, 0.5, usdt_fee(), 0.08)
        bars = [candle(0, 100.0),
                candle(60_000, 100.0, high=target_px + 0.5, low=99.0)]
        result = replay.resolve_trade(
            bars, 0, 100.0, 90.0, 110.0, 120.0,
            usdt_fee(), cfg, 120, atr_pct=1.0
        )
        self.assertEqual("NET_TP", result["exit_reason"])
        net = replay._net_profit_pct(100.0, target_px, usdt_fee(), 0.08)
        self.assertAlmostEqual(0.5, net, places=6)

    def test_net_tp_never_fires_below_target_and_sl_wins_ambiguity(self):
        cfg = canonical_cfg()
        cfg["exit"]["mode"] = "net_tp"
        cfg["exit"]["net_tp_pct"] = 0.5
        target_px = replay._price_for_net(100.0, 0.5, usdt_fee(), 0.08)
        below = [candle(0, 100.0),
                 candle(60_000, 100.0, high=target_px - 0.01, low=99.0)]
        result = replay.resolve_trade(
            below, 0, 100.0, 90.0, 110.0, 120.0,
            usdt_fee(), cfg, 5, atr_pct=1.0
        )
        self.assertEqual("TIMEOUT", result["exit_reason"])
        both = [candle(0, 100.0),
                candle(60_000, 100.0, high=target_px + 1.0, low=89.0)]
        result = replay.resolve_trade(
            both, 0, 100.0, 90.0, 110.0, 120.0,
            usdt_fee(), cfg, 5, atr_pct=1.0
        )
        self.assertEqual("SL", result["exit_reason"])

    def test_net_tp_stop_cuts_before_engine_sl_and_gap_stays_sl(self):
        cfg = canonical_cfg()
        cfg["exit"]["mode"] = "net_tp_stop"
        cfg["exit"]["net_tp_pct"] = 1.0
        cfg["exit"]["stop_gross_pct"] = 2.0
        cut = [candle(0, 100.0), candle(60_000, 100.0, high=101.0, low=97.5)]
        result = replay.resolve_trade(
            cut, 0, 100.0, 90.0, 110.0, 120.0,
            usdt_fee(), cfg, 5, atr_pct=1.0
        )
        self.assertEqual("NET_SL", result["exit_reason"])
        self.assertAlmostEqual(98.0, 100.0 * (1 - 2.0 / 100.0), places=6)
        gap = [candle(0, 100.0), candle(60_000, 100.0, high=101.0, low=89.0)]
        result = replay.resolve_trade(
            gap, 0, 100.0, 90.0, 110.0, 120.0,
            usdt_fee(), cfg, 5, atr_pct=1.0
        )
        self.assertEqual("SL", result["exit_reason"])


class RiskInputsTest(unittest.TestCase):
    def test_risk_inputs_flag_does_not_change_decision(self):
        cfg = canonical_cfg()
        m1 = flat_candles(60)
        m15 = flat_candles(60)
        h1 = flat_candles(60)
        a = replay.evaluate_bar(100.0, m1, m15, h1, cfg, usdt_fee(), True)
        b = replay.evaluate_bar(100.0, m1, m15, h1, cfg, usdt_fee(), True,
                                want_risk_inputs=True)
        self.assertEqual(a["ready"], b["ready"])
        self.assertEqual(a["entry"], b["entry"])
        self.assertEqual(a["sl"], b["sl"])
        self.assertNotIn("risk_inputs", a)
        self.assertIn("risk_inputs", b)
        self.assertEqual(b["risk_inputs"]["s3"], b["steps"][2])


class P0GateTest(unittest.TestCase):
    def test_mtf_gate_allows_three_of_four_when_high_tf_safe(self):
        cfg = canonical_cfg()
        h1 = trend_candles(60, 100.0, 0.5, 3_600_000)
        m15 = trend_candles(60, 100.0, 0.4, 900_000)
        m5 = trend_candles(60, 100.0, 0.2, 300_000)
        m1 = flat_candles(60, 100.0)  # netral, bukan bearish
        result = replay.evaluate_mtf_gate(h1, m15, m5, m1, cfg)
        self.assertTrue(result["allow"], result["reason"])
        self.assertEqual(4, result["known"])
        self.assertGreaterEqual(result["bull"], 3)

    def test_mtf_gate_blocks_bearish_high_timeframe(self):
        cfg = canonical_cfg()
        h1 = trend_candles(60, 120.0, -0.5, 3_600_000)
        m15 = trend_candles(60, 120.0, -0.4, 900_000)
        m5 = trend_candles(60, 120.0, -0.2, 300_000)
        m1 = trend_candles(60, 120.0, -0.1, 60_000)
        result = replay.evaluate_mtf_gate(h1, m15, m5, m1, cfg)
        self.assertFalse(result["allow"])
        self.assertTrue(result["high_bear"])
        self.assertIn("H1/M15", result["reason"])

    def test_mtf_gate_fails_closed_without_m5(self):
        cfg = canonical_cfg()
        h1 = trend_candles(60, 100.0, 0.5, 3_600_000)
        m15 = trend_candles(60, 100.0, 0.4, 900_000)
        m1 = trend_candles(60, 100.0, 0.2, 60_000)
        result = replay.evaluate_mtf_gate(h1, m15, [], m1, cfg)
        self.assertFalse(result["allow"])
        self.assertEqual(3, result["known"])
        self.assertIn("belum lengkap", result["reason"])

    def test_mtf_gate_can_be_disabled_from_config(self):
        cfg = canonical_cfg()
        cfg["mtf_min_bullish"] = 0
        down = trend_candles(60, 120.0, -0.5, 300_000)
        result = replay.evaluate_mtf_gate(down, down, down, down, cfg)
        self.assertTrue(result["allow"])

    def test_btc_veto_fires_on_sharp_dump_below_ema(self):
        cfg = canonical_cfg()
        result = replay.evaluate_btc_veto(dump_btc_candles(), [], cfg)
        self.assertTrue(result["veto"], result["reason"])
        self.assertEqual("DUMP_RISK", result["status"])
        self.assertLess(result["btc_price"], result["ema"])

    def test_btc_veto_safe_uptrend(self):
        cfg = canonical_cfg()
        result = replay.evaluate_btc_veto(
            trend_candles(40, 100.0, 0.5, 300_000), [], cfg
        )
        self.assertTrue(result["sufficient"])
        self.assertFalse(result["veto"])

    def test_btc_veto_without_data_does_not_invent_veto(self):
        cfg = canonical_cfg()
        result = replay.evaluate_btc_veto([], [], cfg)
        self.assertFalse(result["sufficient"])
        self.assertFalse(result["veto"])

    def test_btc_veto_can_be_disabled_from_config(self):
        cfg = canonical_cfg()
        cfg["btc_veto_enabled"] = False
        result = replay.evaluate_btc_veto(dump_btc_candles(), [], cfg)
        self.assertFalse(result["veto"])
        self.assertNotEqual("", result["reason"])


class SummaryTest(unittest.TestCase):
    def test_floor_percentage_uses_all_ready_signals(self):
        rep = {
            "evals": 0,
            "bottleneck": {},
            "setup_hits": {},
            "exit_reasons": {},
            "trades": [
                {
                    "setup": "BREAKOUT",
                    "result": "UNRESOLVED",
                    "r": 0.0,
                    "sl_floor_active": True,
                    "gross_risk_pct": 5.0,
                    "friction_pct": 0.9688,
                }
            ],
            "max_dd_r": 0.0,
        }
        summary = replay.summarize(rep)
        self.assertEqual(1, summary["ready_signals"])
        self.assertEqual(0, summary["trades_resolved"])
        self.assertEqual(100.0, summary["sl_floor_active_pct"])


class MfeTest(unittest.TestCase):
    """Fase A — field MFE/MAE dari resolve_trade + agregasi summarize."""

    def _ramp_bars(self, count=130, step=0.1):
        bars = [candle(0, 100.0)]
        for j in range(1, count + 1):
            bars.append(candle(j * 60_000, 100.0, high=100.0 + j * step, low=99.9))
        return bars

    def test_resolve_trade_exposes_horizon_excursions(self):
        cfg = canonical_cfg()
        cfg["exit"]["mode"] = "classic"
        cfg["exit"]["max_hold_bars_m1"] = 90
        result = replay.resolve_trade(
            self._ramp_bars(), 0, 100.0, 90.0, 110.0, 120.0,
            usdt_fee(), cfg, 120, atr_pct=1.0
        )
        self.assertEqual("TIMEOUT", result["exit_reason"])
        # High bar ke-h = 100 + 0.1h → MFE horizon h = h * 0.1%.
        self.assertAlmostEqual(3.0, result["mfe_30"], places=6)
        self.assertAlmostEqual(6.0, result["mfe_60"], places=6)
        self.assertAlmostEqual(9.0, result["mfe_90"], places=6)
        self.assertAlmostEqual(-0.1, result["mae_90"], places=6)
        self.assertEqual(30, result["bars_30"])
        self.assertEqual(90, result["bars_90"])
        # MFE kotor sampai bar exit (bar ke-90) = 9.0%.
        self.assertAlmostEqual(9.0, result["mfe_exit_pct"], places=6)

    def test_resolve_trade_without_forward_data_is_unresolved(self):
        cfg = canonical_cfg()
        cfg["exit"]["max_hold_bars_m1"] = 90
        result = replay.resolve_trade(
            [candle(0, 100.0)], 0, 100.0, 90.0, 110.0, 120.0,
            usdt_fee(), cfg, 120, atr_pct=1.0
        )
        self.assertEqual("UNRESOLVED", result["result"])
        self.assertEqual(0, result["bars_90"])
        self.assertEqual(0.0, result["mfe_90"])
        self.assertEqual(0.0, result["mfe_exit_pct"])

    def _trade(self, result, r, mfe_90, mae_90, bars_90=90, friction=0.9688,
               risk=5.0, entry=100.0, tp1=108.0, exit_idx=None):
        return {
            "setup": "BREAKOUT", "result": result, "r": r,
            "entry": entry, "sl": entry * 0.95, "tp1": tp1, "tp2": 120.0,
            "gross_risk_pct": risk, "friction_pct": friction,
            "sl_floor_active": False, "exit_reason": "TIMEOUT",
            "exit_idx": exit_idx,
            "mfe_exit_pct": mfe_90 if exit_idx is not None else 0.0,
            "mfe_30": mfe_90 / 2.0, "mae_30": mae_90, "bars_30": 30,
            "mfe_60": mfe_90 * 0.75, "mae_60": mae_90, "bars_60": 60,
            "mfe_90": mfe_90, "mae_90": mae_90, "bars_90": bars_90,
        }

    def test_summarize_entry_mfe_aggregates(self):
        rep = {
            "evals": 0, "bottleneck": {}, "setup_hits": {}, "exit_reasons": {},
            "max_dd_r": 0.0,
            "trades": [
                self._trade("WIN", 0.2, 5.0, -1.0, exit_idx=30),
                self._trade("LOSS", -0.9, 0.9, -5.0, exit_idx=50),
                self._trade("UNRESOLVED", 0.0, 2.0, -0.5, bars_90=10),
            ],
        }
        s = replay.summarize(rep)
        m = s["entry_mfe"]
        self.assertEqual(3, m["n_signals"])
        h = m["horizons"]["90"]
        self.assertEqual(2, h["n_usable"])
        self.assertEqual(1, h["n_truncated"])
        # median dari [5.0, 0.9] = 2.95
        self.assertAlmostEqual(2.95, h["median_mfe_pct"], places=6)
        # share mfe >= 1x friksi (0.9688): hanya trade WIN (5.0) → 50%
        self.assertEqual(50.0, h["share_mfe_ge_cost_pct"])
        # share mfe >= 2x friksi (1.9376): hanya WIN → 50%
        self.assertEqual(50.0, h["share_mfe_ge_2cost_pct"])
        # share mfe >= 4x friksi (3.8752): hanya WIN → 50%
        self.assertEqual(50.0, h["share_mfe_ge_4cost_pct"])
        # tp1 kotor = (108-100)/100*100 = 8.0% → tidak ada yang mencapai → 0%
        self.assertEqual(0.0, h["share_mfe_ge_tp1_pct"])
        # MAE <= -risiko kotor (5.0): trade LOSS (mae -5.0) → 50%
        self.assertEqual(50.0, h["share_sl_hit_pct"])
        # mfe_exit hanya dihitung untuk trade resolved (exit_idx terisi);
        # median dari [5.0 (WIN), 0.9 (LOSS)] = 2.95
        self.assertEqual(2, m["mfe_exit_resolved"]["n"])
        self.assertAlmostEqual(2.95, m["mfe_exit_resolved"]["median_pct"], places=6)

    def test_summarize_without_mfe_fields_still_works(self):
        """Trade bergaya lama (tanpa field MFE) tidak boleh membuat summarize crash."""
        rep = {
            "evals": 0, "bottleneck": {}, "setup_hits": {}, "exit_reasons": {},
            "max_dd_r": 0.0,
            "trades": [{"setup": "BREAKOUT", "result": "UNRESOLVED", "r": 0.0,
                        "sl_floor_active": True, "gross_risk_pct": 5.0,
                        "friction_pct": 0.9688}],
        }
        s = replay.summarize(rep)
        self.assertEqual(0, s["entry_mfe"]["horizons"]["90"]["n_usable"])
        self.assertEqual(1, s["entry_mfe"]["n_signals"])


class ExitModeSweepTest(unittest.TestCase):
    def test_percentile_rank_and_bucket(self):
        vals = [1.0, 2.0, 3.0, 4.0]
        self.assertEqual(0.0, exit_sweep.percentile_rank(vals, 0.0))
        self.assertEqual(75.0, exit_sweep.percentile_rank(vals, 3.0))
        self.assertEqual(100.0, exit_sweep.percentile_rank(vals, 4.0))
        self.assertEqual("0-20", exit_sweep.bucket_of(0.0))
        self.assertEqual("0-20", exit_sweep.bucket_of(19.99))
        self.assertEqual("20-40", exit_sweep.bucket_of(20.0))
        self.assertEqual("80-100", exit_sweep.bucket_of(100.0))

    def test_summarize_net_negative_and_exit_mix(self):
        trades = [
            {"result": "WIN", "r": 1.0, "risk_pct": 5.0,
             "exit_reason": "TP2", "exit_t": 60_000, "t": 0},
            {"result": "LOSS", "r": -1.0, "risk_pct": 5.0,
             "exit_reason": "TIMEOUT", "exit_t": 120_000, "t": 0},
            {"result": "UNRESOLVED", "r": 0.0, "risk_pct": 5.0,
             "exit_reason": "TIMEOUT", "exit_t": None, "t": 0},
        ]
        s = exit_sweep.summarize(trades, days=1.0)
        self.assertEqual(2, s["n"])
        self.assertEqual(1, s["unresolved"])
        self.assertEqual(50.0, s["win_rate_pct"])
        self.assertEqual(0.0, s["avg_net_pct_per_trade"])
        self.assertEqual(0.0, s["net_pct_per_day"])
        self.assertEqual({"TIMEOUT": 1, "TP2": 1}, s["exits"])

    def test_bootstrap_ci_is_deterministic_and_negative(self):
        a = exit_sweep.bootstrap_ci([-1.0, -1.0, -1.0], resamples=50, seed=1)
        b = exit_sweep.bootstrap_ci([-1.0, -1.0, -1.0], resamples=50, seed=1)
        self.assertEqual(a, b)
        self.assertEqual([-1.0, -1.0], a)
        self.assertIsNone(exit_sweep.bootstrap_ci([], resamples=10))

    def test_range_position_and_percentile(self):
        bars = [
            candle(0, 100.0, high=110.0, low=90.0, close=100.0),
            candle(60_000, 100.0, high=120.0, low=80.0, close=120.0),
        ]
        self.assertEqual(100.0, entry_pct.range_position(bars, 1, 1))
        self.assertEqual(100.0, entry_pct.trailing_window(bars, 1, 1)[-1]["c"] / 120.0 * 100.0)
        self.assertEqual(100.0, exit_sweep.percentile_rank(
            [b["c"] for b in bars], 120.0))


if __name__ == "__main__":
    unittest.main(verbosity=2)
