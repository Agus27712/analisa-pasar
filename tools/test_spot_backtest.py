#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Uji mekanika harness backtest Spec v2 (tanpa Android, tanpa data pasar).

Yang diuji:
- urutan SL sebelum TP1 (SL-before-TP);
- TP1 sebagian 60%, sisa 40%;
- TP2 hanya dievaluasi pada candle setelah TP1;
- stop tidak pernah turun (break-even lalu trailing);
- time-stop memakai maxFavorable < 0.5R;
- fee/P&L memakai bentuk FeeSchedule (break-even 0,9735%);
- pemetaan HTF tidak melihat candle yang belum close;
- kebijakan TP1 formula: `floor` menaikkan TP1 ke netRr 1,0; `literal` ditolak G4.

Jalankan: python3 tools/test_spot_backtest.py
"""
import os
import sys
import unittest
from types import SimpleNamespace

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import spot_lib as lib  # noqa: E402
import spot_backtest as bt  # noqa: E402


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
        opts = dict(tp1_policy="floor", equity=10_000.0, risk_usd=50.0,
                    gates={"max_sl_atr": 5.0, "friction_multiple": 6.0, "min_atr_friction": 1.5,
                           "min_net_rr_tp1": 1.0, "min_net_rr_final": 1.5,
                           "min_reward_friction": 3.0})
        sig = dict(setup="TEST", entry=100.0, sl=97.0, tp1=103.0, tp2=112.0,
                   tp1_source="formula", trail="EMA9", time_stop=8, score=1,
                   atr=1.5, data={"signal_index": 0})
        out, why = bt.finalize_signal(ctx, 0, sig, opts)
        self.assertIsNone(why)
        self.assertAlmostEqual(out["tp1"], 107.7504, places=4)
        self.assertAlmostEqual(out["net_rr1"], 1.0, places=6)

    def test_formula_tp1_literal_rejected_by_g4(self):
        ctx = SimpleNamespace(atr=[1.5])
        opts = dict(tp1_policy="literal", equity=10_000.0, risk_usd=50.0,
                    gates={"max_sl_atr": 5.0, "friction_multiple": 6.0, "min_atr_friction": 1.5,
                           "min_net_rr_tp1": 1.0, "min_net_rr_final": 1.5,
                           "min_reward_friction": 3.0})
        sig = dict(setup="TEST", entry=100.0, sl=97.0, tp1=103.0, tp2=112.0,
                   tp1_source="formula", trail="EMA9", time_stop=8, score=1,
                   atr=1.5, data={"signal_index": 0})
        out, why = bt.finalize_signal(ctx, 0, sig, opts)
        self.assertIsNone(out)
        self.assertEqual(why, "NET_RR_TP1")


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
