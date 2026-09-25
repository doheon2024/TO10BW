import json
import unittest
from pathlib import Path

import xml.etree.ElementTree as ET

from kostolany.chart import render_svg
from kostolany.model import ASSETS, analyze, blend_allocation, heading
from kostolany.scenarios import run_scenarios

SNAPSHOT = json.loads((Path(__file__).resolve().parent.parent / "data" / "snapshot.json").read_text(encoding="utf-8"))


def make(history, as_of, **kw):
    s = {"as_of": as_of, "base_rate_history": [{"date": d, "rate": r} for d, r in history],
         "cpi_yoy": 2.0, "core_cpi_yoy": 2.0, "inflation_target": 2.0, "gdp_growth": 2.0,
         "potential_growth": 2.0, "neutral_rate": 2.5, "usdkrw": 1350,
         "policy_guidance": "neutral", "house_price_trend": "flat", "household_debt_trend": "flat"}
    s.update(kw)
    return s


class ModelTest(unittest.TestCase):
    def test_current_snapshot_is_early_hiking(self):
        a = analyze(SNAPSHOT)
        self.assertEqual(a.regime, "hiking")
        self.assertEqual(a.streak_bp, 50)
        self.assertIn(a.phase.code, {"B1", "B2"})

    CUTS_2024_25 = [("2024-09-01", 3.5), ("2024-10-11", 3.25), ("2024-11-28", 3.0),
                    ("2025-02-25", 2.75), ("2025-05-29", 2.5)]

    def test_mid_2025_was_late_cutting_cycle(self):
        s = make(self.CUTS_2024_25, "2025-06-30",
                 gdp_growth=0.9, policy_guidance="dovish", house_price_trend="rising")
        a = analyze(s)
        self.assertIn(a.phase.code, {"A2", "A3"})
        self.assertGreater(a.position, 1.5)

    def test_long_hold_after_cuts_is_rate_bottom(self):
        s = make(self.CUTS_2024_25, "2025-12-01",
                 cpi_yoy=2.1, core_cpi_yoy=2.1, gdp_growth=1.0, house_price_trend="rising")
        self.assertEqual(analyze(s).phase.code, "A3")

    def test_hold_after_hikes_with_low_target_turns_to_a1(self):
        s = make([("2022-01-01", 1.0), ("2022-07-01", 2.0), ("2023-01-01", 3.5)], "2024-01-01",
                 cpi_yoy=1.8, core_cpi_yoy=1.8, gdp_growth=1.2, policy_guidance="dovish")
        self.assertEqual(analyze(s).phase.code, "A1")

    def test_first_cut_from_peak_is_a1(self):
        s = make([("2023-01-01", 3.5), ("2024-10-11", 3.25)], "2024-10-20",
                 gdp_growth=1.5, policy_guidance="dovish")
        self.assertEqual(analyze(s).phase.code, "A1")

    def test_allocations_sum_to_100(self):
        for risk in ("conservative", "balanced", "aggressive"):
            a = analyze(SNAPSHOT, risk)
            self.assertEqual(sum(a.allocation.values()), 100)
            self.assertEqual(set(a.allocation), set(ASSETS))
        probs = {c: 1 / 6 for c in ("A1", "A2", "A3", "B1", "B2", "B3")}
        self.assertEqual(sum(blend_allocation(probs).values()), 100)

    def test_risk_profile_orders_equity_weight(self):
        eq = [analyze(SNAPSHOT, r).allocation["주식"] for r in ("conservative", "balanced", "aggressive")]
        self.assertEqual(eq, sorted(eq))

    def test_scenarios_do_not_mutate_snapshot(self):
        before = json.dumps(SNAPSHOT, sort_keys=True)
        results = dict((sc.name, a.phase.code) for sc, a in run_scenarios(SNAPSHOT, "balanced"))
        self.assertEqual(json.dumps(SNAPSHOT, sort_keys=True), before)
        self.assertEqual(results["추가 인상 지속"], "B2")
        self.assertEqual(results["경기 급랭·인하 전환"], "A1")

    def test_heading_points_to_next_phase(self):
        nxt, _ = heading(analyze(SNAPSHOT))
        self.assertEqual(nxt.code, "B2")

    def test_chart_is_valid_svg_with_current_phase(self):
        a = analyze(SNAPSHOT)
        svg = render_svg(SNAPSHOT, a, run_scenarios(SNAPSHOT, "balanced"))
        root = ET.fromstring(svg)
        texts = "".join(el.text or "" for el in root.iter("{http://www.w3.org/2000/svg}text"))
        self.assertIn(a.phase.code, texts)
        self.assertIn("B2 인상 진행 방향", texts)


if __name__ == "__main__":
    unittest.main()
