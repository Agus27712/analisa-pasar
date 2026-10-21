#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Uji mekanika harness backtest Spec v3 (tanpa Android, tanpa data pasar).

Yang diuji:
- urutan SL sebelum TP1 (SL-before-TP);
- TP1 sebagian 60%, sisa 40%;
- TP2 hanya dievaluasi pada candle setelah TP1;
- stop tidak pernah turun (break-even lalu trailing);
- time-stop memakai maxFavorable < 0.5R;
- fee/P&L memakai bentuk FeeSchedule (break-even 0,9735%);
- pemetaan HTF tidak melihat candle yang belum close;
- kebijakan TP1 formula: `spec` menaikkan TP1 ke `tp1Floor`; `literal` ditolak G4;
- time-stop dan cooldown dikonversi dari jam/menit per timeframe;
- per-unit risk memakai `FeeSchedule`, bukan pendekatan aditif;
- R kotor dan friksi per R tersedia di rekor trade;
- filter posisi bersamaan menerima maksimal N trade yang tumpang tindih.

Jalankan: python3 tools/test_spot_backtest.py
"""
import os
import sys
import unittest
from types import SimpleNamespace

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import spot_lib as lib  # noqa: E402
import spot_backtest as bt  # noqa: E402
import spot_benchmark as bench  # noqa: E402
import spot_evidence as evidence  # noqa: E402


def make_ctx(rows, ema9=None, regime="UNDEFINED"):
    n = len(rows)
    return SimpleNamespace(
        o=[r[0] for r in rows], h=[r[1] for r in rows],
        l=[r[2] for r in rows], c=[r[3] for r in rows],
        atr=[1.0] * n, volratio=[0.5] * n, regime=[regime] * n,
        vwap=[r[3] for r in rows], adx=[10.0] * n, pdi=[10.0] * n, mdi=[10.0] * n,
        ema9=ema9 if ema9 is not None else [r[3] for r in rows], sw2=[],
    )


def make_trade(entry=100.0, sl=94.0, tp1=108.0, tp2=112.0, trail="NONE",
               time_stop=999, entry_index=0, qty=10.0):
    sig = {
        "setup": "TEST", "sl_eff": sl, "tp1": tp1, "tp2": tp2, "qty": qty,
        "risk_pct": (entry - sl) / entry * 100.0, "net_rr1": 1.0, "net_rr_final": 1.5,
        "score": 0, "trail": trail, "time_stop": time_stop, "data": {"signal_index": 0},
    }
    return bt.Trade("TESTUSDT", sig, entry_index, entry)


class ExitMechanicsTest(unittest.TestCase):
    def test_sl_before_tp_same_candle(self):
        ctx = make_ctx([(100.0, 110.0, 93.0, 105.0)])
        tr = make_trade()
        bt.process_open_trade(ctx, tr, 0, {}, [], 10_000.0)
        self.assertEqual(tr.qty_remaining, 0.0)
        self.assertEqual(tr.fills[-1][2], "STOP_LOSS")
        self.assertAlmostEqual(tr.fills[-1][1], 94.0)
        rec = tr.finalize(0, 0)
        self.assertLess(rec["r_net"], 0.0)
        self.assertAlmostEqual(sum(q for q, _, _ in tr.fills), tr.qty_total)

    def test_tp1_partial_then_tp2_next_candle(self):
        rows = [
            (100.0, 100.5, 99.5, 100.0),
            (100.0, 109.0, 99.0, 108.0),
            (108.0, 113.0, 107.0, 112.0),
        ]
        ctx = make_ctx(rows)
        tr = make_trade(entry_index=0)
        bt.process_open_trade(ctx, tr, 0, {}, [], 10_000.0)
        bt.process_open_trade(ctx, tr, 1, {}, [], 10_000.0)
        self.assertTrue(tr.tp1_hit)
        self.assertAlmostEqual(tr.qty_remaining, 4.0)
        self.assertGreaterEqual(tr.sl_current, lib.breakeven_price(100.0) - 1e-9)
        rec1 = tr.finalize(1, 0) if tr.qty_remaining == 0 else None
        self.assertIsNone(rec1)
        bt.process_open_trade(ctx, tr, 2, {}, [], 10_000.0)
        self.assertEqual(tr.qty_remaining, 0.0)
        self.assertEqual(tr.fills[-1][2], "TAKE_PROFIT_2")
        rec = tr.finalize(2, 0)
        self.assertGreater(rec["r_net"], 0.0)
        self.assertAlmostEqual(sum(q for q, _, _ in tr.fills), tr.qty_total)

    def test_tp2_not_evaluated_on_tp1_candle(self):
        rows = [
            (100.0, 100.5, 99.5, 100.0),
            (100.0, 113.0, 99.0, 108.0),  # TP1 dan TP2 tersentuh sekaligus
        ]
        ctx = make_ctx(rows)
        tr = make_trade(entry_index=0)
        bt.process_open_trade(ctx, tr, 0, {}, [], 10_000.0)
        bt.process_open_trade(ctx, tr, 1, {}, [], 10_000.0)
        self.assertEqual(tr.fills[-1][2], "TAKE_PROFIT_1")
        self.assertAlmostEqual(tr.qty_remaining, 4.0)
        self.assertNotEqual(tr.fills[-1][2], "TAKE_PROFIT_2")

    def test_stop_never_decreases(self):
        rows = [
            (100.0, 100.5, 99.5, 100.0),
            (100.0, 109.0, 99.0, 108.0),
            (108.0, 112.0, 110.5, 111.0),  # low di atas stop baru
            (111.0, 112.0, 110.0, 111.0),
        ]
        ctx = make_ctx(rows, ema9=[100.0, 100.0, 110.0, 105.0])
        tr = make_trade(entry_index=0, tp2=None, trail="EMA9")
        bt.process_open_trade(ctx, tr, 0, {}, [], 10_000.0)
        bt.process_open_trade(ctx, tr, 1, {}, [], 10_000.0)
        stop_after_tp1 = tr.sl_current
        bt.process_open_trade(ctx, tr, 2, {}, [], 10_000.0)
        stop_after_high_ema = tr.sl_current
        bt.process_open_trade(ctx, tr, 3, {}, [], 10_000.0)
        self.assertGreaterEqual(stop_after_high_ema, stop_after_tp1)
        self.assertGreaterEqual(tr.sl_current, stop_after_high_ema)
        self.assertEqual(tr.qty_remaining, 4.0)

    def test_time_stop_after_max_favorable_below_half_r(self):
        rows = [
            (100.0, 100.5, 99.5, 100.0),
            (100.0, 100.5, 99.5, 100.0),
            (100.0, 100.5, 99.5, 100.0),
        ]
        ctx = make_ctx(rows)
        tr = make_trade(entry_index=0, tp1=110.0, tp2=None, time_stop=2)
        bt.process_open_trade(ctx, tr, 0, {}, [], 10_000.0)
        bt.process_open_trade(ctx, tr, 1, {}, [], 10_000.0)
        self.assertIsNone(tr.exit_next_open)
        bt.process_open_trade(ctx, tr, 2, {}, [], 10_000.0)
        self.assertEqual(tr.exit_next_open, "TIME_STOP")


class FeeAndGateTest(unittest.TestCase):
    def test_breakeven_matches_fee_schedule(self):
        be = lib.breakeven_price(100.0)
        self.assertAlmostEqual(be, 100.97351571009973, places=9)
        self.assertAlmostEqual(lib.net_profit_pct(100.0, be), 0.0, places=9)

    def test_gate_structural_passes(self):
        ctx = SimpleNamespace(atr=[1.5])
        opts = dict(tp1_policy="floor", equity=10_000.0, risk_usd=50.0,
                    gates={"max_sl_atr": 5.0, "friction_multiple": 6.0, "min_atr_friction": 1.5,
                           "min_net_rr_tp1": 1.0, "min_net_rr_final": 1.5,
                           "min_reward_friction": 3.0})
        sig = dict(setup="TEST", entry=100.0, sl=94.0, tp1=108.0, tp2=112.0,
                   tp1_source="structural", trail="EMA9", time_stop=8, score=1,
                   atr=1.5, data={"signal_index": 0})
        out, why = bt.finalize_signal(ctx, 0, sig, opts)
        self.assertIsNone(why)
        self.assertAlmostEqual(out["tp1"], 108.0)

    def test_formula_tp1_floor_passes_g4(self):
        ctx = SimpleNamespace(atr=[1.5])
        opts = dict(tp1_policy="spec", equity=10_000.0, risk_usd=50.0, tf_minutes=15,
                    gates={"max_sl_atr": 5.0, "friction_multiple": 6.0, "min_atr_friction": 0.0,
                           "min_net_rr_tp1": 1.0, "min_net_rr_final": 1.5,
                           "min_reward_friction": 3.0})
        sig = dict(setup="TEST", entry=100.0, sl=97.0, tp1=103.0, tp2=112.0,
                   tp1_source="formula", trail="EMA9", time_stop_hours=2.0, score=1,
                   atr=1.5, data={"signal_index": 0})
        out, why = bt.finalize_signal(ctx, 0, sig, opts)
        self.assertIsNone(why)
        # SL efektif 94,1872 (lantai 5,8128%) -> tp1Floor = 107,7504
        self.assertAlmostEqual(out["tp1"], 107.7504, places=4)
        self.assertAlmostEqual(out["net_rr1"], 1.0, places=6)
        self.assertEqual(out["sl_source"], "FRICTION_FLOOR")

    def test_formula_tp1_literal_rejected_by_g4(self):
        ctx = SimpleNamespace(atr=[1.5])
        opts = dict(tp1_policy="literal", equity=10_000.0, risk_usd=50.0, tf_minutes=15,
                    gates={"max_sl_atr": 5.0, "friction_multiple": 6.0, "min_atr_friction": 0.0,
                           "min_net_rr_tp1": 1.0, "min_net_rr_final": 1.5,
                           "min_reward_friction": 3.0})
        sig = dict(setup="TEST", entry=100.0, sl=97.0, tp1=103.0, tp2=112.0,
                   tp1_source="formula", trail="EMA9", time_stop_hours=2.0, score=1,
                   atr=1.5, data={"signal_index": 0})
        out, why = bt.finalize_signal(ctx, 0, sig, opts)
        self.assertIsNone(out)
        self.assertEqual(why, "NET_RR_TP1")

    def test_legacy_floor_equals_spec_when_min_rr_is_one(self):
        ctx = SimpleNamespace(atr=[1.5])
        base = dict(equity=10_000.0, risk_usd=50.0, tf_minutes=15,
                    gates={"max_sl_atr": 5.0, "friction_multiple": 6.0, "min_atr_friction": 0.0,
                           "min_net_rr_tp1": 1.0, "min_net_rr_final": 1.5,
                           "min_reward_friction": 3.0})
        sig = dict(setup="TEST", entry=100.0, sl=97.0, tp1=103.0, tp2=112.0,
                   tp1_source="formula", trail="EMA9", time_stop_hours=2.0, score=1,
                   atr=1.5, data={"signal_index": 0})
        a, _ = bt.finalize_signal(ctx, 0, sig, dict(base, tp1_policy="spec"))
        b, _ = bt.finalize_signal(ctx, 0, sig, dict(base, tp1_policy="legacy-floor"))
        self.assertAlmostEqual(a["tp1"], b["tp1"], places=9)

    def test_structural_tp1_is_rejected_not_stretched(self):
        ctx = SimpleNamespace(atr=[2.0])
        opts = dict(tp1_policy="spec", equity=10_000.0, risk_usd=50.0, tf_minutes=15,
                    gates={"max_sl_atr": 5.0, "friction_multiple": 6.0, "min_atr_friction": 0.0,
                           "min_net_rr_tp1": 1.0, "min_net_rr_final": 1.5,
                           "min_reward_friction": 3.0})
        # SL 94 -> lantai 94,1872; TP1 struktural 106 < tp1Floor 107,7504
        sig = dict(setup="TEST", entry=100.0, sl=94.0, tp1=106.0, tp2=None,
                   tp1_source="structural", trail="NONE", time_stop_hours=3.0, score=1,
                   atr=2.0, data={"signal_index": 0})
        out, why = bt.finalize_signal(ctx, 0, sig, opts)
        self.assertIsNone(out)
        self.assertEqual(why, "NET_RR_TP1")


class StopResolverTest(unittest.TestCase):
    def test_floor_percent_matches_paper_stop_resolver(self):
        self.assertAlmostEqual(lib.friction_floor_pct(6.0), 5.8128, places=9)
        self.assertEqual(lib.friction_floor_pct(0.0), 0.0)
        self.assertEqual(lib.friction_floor_pct(-1.0), 0.0)

    def test_signal_sl_wider_than_floor_is_kept(self):
        price, source = lib.resolve_stop(94.0, 100.0, 5.8128)
        self.assertEqual(source, "SIGNAL")
        self.assertAlmostEqual(price, 94.0)

    def test_signal_sl_tighter_than_floor_is_widened(self):
        price, source = lib.resolve_stop(97.0, 100.0, 5.8128)
        self.assertEqual(source, "FRICTION_FLOOR")
        self.assertAlmostEqual(price, 94.1872, places=6)

    def test_missing_signal_sl_falls_to_floor(self):
        price, source = lib.resolve_stop(0.0, 100.0, 5.8128)
        self.assertEqual(source, "FRICTION_FLOOR")
        self.assertAlmostEqual(price, 94.1872, places=6)

    def test_invalid_entry_is_none(self):
        price, source = lib.resolve_stop(94.0, 0.0, 5.8128)
        self.assertEqual((price, source), (0.0, "NONE"))


class TimeConversionTest(unittest.TestCase):
    def test_time_stop_per_timeframe(self):
        self.assertEqual(lib.time_stop_candles(2.0, 15), 8)
        self.assertEqual(lib.time_stop_candles(2.0, 60), 3)
        self.assertEqual(lib.time_stop_candles(3.0, 15), 12)
        self.assertEqual(lib.time_stop_candles(3.0, 60), 3)
        self.assertEqual(lib.time_stop_candles(1.5, 15), 6)
        # ceil(1,5 jam / 1 jam) = 2, tetapi minimum Spec v3 adalah 3 candle.
        self.assertEqual(lib.time_stop_candles(1.5, 60), 3)

    def test_cooldown_and_pause_per_timeframe(self):
        self.assertEqual(lib.candles_for_minutes(15, 15), 1)
        self.assertEqual(lib.candles_for_minutes(60, 15), 4)
        self.assertEqual(lib.candles_for_minutes(15, 60), 1)
        self.assertEqual(lib.candles_for_minutes(60, 60), 1)


class SizingTest(unittest.TestCase):
    def test_per_unit_risk_is_net_fee_schedule_cost(self):
        entry, sl = 100.0, 94.1872
        expected = lib.cost_basis(1.0, entry) - lib.net_sell_value(1.0, sl)
        self.assertAlmostEqual(lib.per_unit_risk_net(entry, sl), expected, places=12)
        additive = (entry - sl) + entry * lib.F_ADD_PCT / 100.0
        self.assertGreater(abs(expected - additive), 0.0)

    def test_qty_uses_net_per_unit_risk(self):
        ctx = SimpleNamespace(atr=[2.0])
        opts = dict(tp1_policy="spec", equity=10_000.0, risk_usd=50.0, tf_minutes=15,
                    gates={"max_sl_atr": 5.0, "friction_multiple": 6.0, "min_atr_friction": 0.0,
                           "min_net_rr_tp1": 1.0, "min_net_rr_final": 1.5,
                           "min_reward_friction": 3.0})
        sig = dict(setup="TEST", entry=100.0, sl=94.0, tp1=112.0, tp2=None,
                   tp1_source="structural", trail="NONE", time_stop_hours=3.0, score=1,
                   atr=2.0, data={"signal_index": 0})
        out, why = bt.finalize_signal(ctx, 0, sig, opts)
        self.assertIsNone(why)
        expected_qty = 50.0 / lib.per_unit_risk_net(100.0, out["sl_eff"])
        self.assertAlmostEqual(out["qty"], expected_qty, places=10)


class GrossRTest(unittest.TestCase):
    def test_gross_r_exceeds_net_r_by_friction(self):
        sig = {
            "setup": "TEST", "sl_eff": 95.0, "tp1": 110.0, "tp2": None, "qty": 10.0,
            "risk_pct": 5.0, "net_rr1": 1.0, "net_rr_final": 1.5, "score": 0,
            "trail": "NONE", "time_stop": 8, "data": {"signal_index": 0},
            "sl_source": "SIGNAL", "tp1_source": "structural",
        }
        tr = bt.Trade("TESTUSDT", sig, 0, 100.0)
        tr.sell(10.0, 110.0, "TAKE_PROFIT_1")
        rec = tr.finalize(1, 0)
        self.assertAlmostEqual(rec["gross_pnl_pct"], 10.0, places=9)
        self.assertAlmostEqual(rec["r_gross"] - rec["r_net"], rec["friction_r"], places=12)
        self.assertGreater(rec["r_gross"], rec["r_net"])
        self.assertGreater(rec["risk_net_pct"], rec["risk_pct"])


class PortfolioCapTest(unittest.TestCase):
    def test_only_two_overlapping_trades_accepted(self):
        def tr(pair, entry_ms, exit_ms):
            return {"pair": pair, "entry_time_ms": entry_ms, "exit_time_ms": exit_ms}
        trades = [tr("A", 0, 100), tr("B", 10, 110), tr("C", 20, 30), tr("D", 200, 300)]
        accepted, rejected = bt.apply_portfolio_cap(trades, 2)
        self.assertEqual([t["pair"] for t in accepted], ["A", "B", "D"])
        self.assertEqual([t["pair"] for t in rejected], ["C"])

    def test_zero_cap_accepts_everything(self):
        trades = [{"pair": "A", "entry_time_ms": 0, "exit_time_ms": 10}] * 3
        accepted, rejected = bt.apply_portfolio_cap(trades, 0)
        self.assertEqual(len(accepted), 3)
        self.assertEqual(rejected, [])


class WindowTest(unittest.TestCase):
    def test_window_bounds_never_breaks_warmup(self):
        times = [k * 900_000 for k in range(100)]
        i0, i1 = bt.window_bounds(times, 30 * 900_000, 50 * 900_000, warmup=10)
        self.assertEqual((i0, i1), (30, 50))
        i0, i1 = bt.window_bounds(times, 0, 50 * 900_000, warmup=10)
        self.assertEqual((i0, i1), (10, 50))


class BenchmarkTest(unittest.TestCase):
    def test_percentile_linear(self):
        self.assertAlmostEqual(bench.percentile([0.0, 10.0], 99), 9.9, places=9)
        self.assertEqual(bench.percentile([5.0], 99), 5.0)
        self.assertIsNone(bench.percentile([], 99))

    def test_seed_is_stable(self):
        self.assertEqual(bench.stable_seed(7, "A", "X"), bench.stable_seed(7, "A", "X"))
        self.assertNotEqual(bench.stable_seed(7, "A", "X"), bench.stable_seed(7, "A", "Y"))

    def test_random_trade_uses_tp1_floor_and_closes_at_end(self):
        rows = [(t * 900_000, 100.0, 100.5, 99.5, 100.0) for t in range(6)]
        ctx = SimpleNamespace(
            pair="TESTUSDT",
            t=[r[0] for r in rows], o=[r[1] for r in rows], h=[r[2] for r in rows],
            l=[r[3] for r in rows], c=[r[4] for r in rows],
            atr=[1.0] * 6, regime=["UNDEFINED"] * 6, volratio=[0.5] * 6,
        )
        params = dict(lib.DEFAULT_SETUP_PARAMS["BREAKOUT_RETEST"])
        rec = bench.simulate_random_trade(ctx, 1, 2.0, "BREAKOUT_RETEST", params, 15, 5)
        self.assertIsNotNone(rec)
        self.assertEqual(rec["exit_reason"], "END_OF_DATA")
        self.assertAlmostEqual(rec["tp1"], lib.tp1_floor_price(100.0, 2.0), places=9)
        self.assertLess(rec["r_net"], 0.0)


class FeeMirrorTest(unittest.TestCase):
    def test_mirror_scalp_config(self):
        """Angka fee harness harus sama dengan mirror JSON replay (satu sumber)."""
        import json
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        cfg = json.load(open(os.path.join(root, "data/config/scalp_config.json")))
        self.assertAlmostEqual(cfg["fee"]["buy_taker_pct"], lib.BUY_FEE_PCT, places=9)
        self.assertAlmostEqual(cfg["fee"]["sell_taker_pct"], lib.SELL_FEE_PCT, places=9)
        self.assertAlmostEqual(cfg["slippage_pct"], lib.SLIPPAGE_PCT, places=9)
        self.assertAlmostEqual(cfg["min_friction_multiple"], lib.FLOOR_MULTIPLE, places=9)
        self.assertAlmostEqual(lib.F_ADD_PCT,
                               cfg["fee"]["buy_taker_pct"] + cfg["fee"]["sell_taker_pct"]
                               + 2.0 * cfg["slippage_pct"], places=9)


class EvidenceTableTest(unittest.TestCase):
    def test_variant_row_is_per_setup_not_overall(self):
        # Kontrol negatif: dulu tabel E4 memakai summary.overall, sehingga satu trade
        # SWEEP tercatat sebagai milik semua setup. Angka per setup wajib dari by_setup.
        doc = {"summary": {
            "overall": {"trades": 1, "avg_r": 1.403},
            "by_setup": {
                "SWEEP_RECLAIM": {"trades": 1, "avg_r": 1.403},
                "BREAKOUT_RETEST": {"trades": 0, "avg_r": None},
            },
        }}
        self.assertEqual(evidence.variant_row(doc, "BREAKOUT_RETEST"),
                         {"trades": 0, "avg_r": None})
        self.assertEqual(evidence.variant_row(doc, "SWEEP_RECLAIM")["trades"], 1)


class HtfTest(unittest.TestCase):
    def test_htf_never_uses_unclosed_candle(self):
        h1 = [0, 3_600_000]  # 11:00, 12:00
        exec_times = [3_600_000 + k * 900_000 for k in range(5)]  # 12:00 .. 13:00
        idx = lib.htf_index(exec_times, 15, h1, 60)
        # 12:00-12:30 hanya boleh memakai 11:00; 12:45 close 13:00 boleh memakai
        # candle 12:00 yang juga close 13:00; 13:00 close 13:15 memakai 12:00.
        self.assertEqual(idx, [0, 0, 0, 1, 1])


if __name__ == "__main__":
    unittest.main(verbosity=2)
