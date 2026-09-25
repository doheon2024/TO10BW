import io
import json
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path

from kostolany.ecos import BASE_RATE, CORE_CPI, CPI, KTB_3Y, KTB_10Y, MAX_ROWS, USDKRW, update_snapshot
from kostolany.update import run_update

SNAPSHOT = Path(__file__).resolve().parent.parent / "data" / "snapshot.json"


def fake_fetch(rate_changes, cpi=None, calls=None):
    """ECOS를 흉내 낸다. rate_changes: [(date, rate)], cpi: {series: {YYYYMM: 지수}}."""
    cpi = cpi or {CPI: {"202508": 116.45, "202608": 120.05}, CORE_CPI: {"202508": 112.84, "202608": 116.64}}

    def rate_on(d):
        r = 2.5
        for cd, cr in rate_changes:
            if d >= cd:
                r = cr
        return r

    def fetch(series, cycle, start, end):
        if calls is not None:
            calls.append((series, cycle, start, end))
        if series == BASE_RATE:
            if cycle == "M":
                rows, y, m = [], int(start[:4]), int(start[4:])
                while f"{y}{m:02d}" <= end:
                    last = date(y + m // 12, m % 12 + 1, 1) - timedelta(days=1)
                    rows.append((f"{y}{m:02d}", rate_on(last)))
                    y, m = y + m // 12, m % 12 + 1
            else:
                d0, d1 = (date(int(x[:4]), int(x[4:6]), int(x[6:])) for x in (start, end))
                rows = [((d0 + timedelta(i)).strftime("%Y%m%d"), rate_on(d0 + timedelta(i)))
                        for i in range((d1 - d0).days + 1)]
        elif series in cpi:
            rows = [(t, v) for t, v in sorted(cpi[series].items()) if start <= t <= end]
        else:
            value = {KTB_3Y: 3.2, KTB_10Y: 3.5, USDKRW: 1400.0}[series]
            rows = [("20260924", value)] if start <= "20260924" <= end else []
        assert len(rows) <= MAX_ROWS, "sample 키 10건 제한 초과"
        return rows
    return fetch


class UpdateTest(unittest.TestCase):
    def base(self):
        s = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
        s["base_rate_history"] = [{"date": "2025-05-29", "rate": 2.5}]
        return s

    def test_finds_exact_dates_of_missed_changes(self):
        s = self.base()
        changes = [(date(2026, 7, 16), 2.75), (date(2026, 8, 27), 3.0)]
        update_snapshot(s, today=date(2026, 9, 25), fetch=fake_fetch(changes))
        self.assertEqual(s["base_rate_history"][-2:], [{"date": "2026-07-16", "rate": 2.75},
                                                       {"date": "2026-08-27", "rate": 3.0}])
        self.assertEqual((s["cpi_yoy"], s["core_cpi_yoy"]), (3.1, 3.4))
        self.assertEqual((s["ktb_3y"], s["usdkrw"]), (3.2, 1400.0))

    def test_change_in_current_month_is_found(self):
        s = self.base()
        update_snapshot(s, today=date(2026, 10, 25), fetch=fake_fetch([(date(2026, 10, 16), 2.75)]))
        self.assertEqual(s["base_rate_history"][-1], {"date": "2026-10-16", "rate": 2.75})

    def test_failed_item_keeps_old_value(self):
        s = self.base()
        before = s["usdkrw"]
        good = fake_fetch([])

        def flaky(series, *a):
            if series == USDKRW:
                raise OSError("network down")
            return good(series, *a)
        log = update_snapshot(s, today=date(2026, 9, 25), fetch=flaky)
        self.assertEqual(s["usdkrw"], before)
        self.assertTrue(any("원/달러" in line and "실패" in line for line in log))

    def test_run_update_saves_and_backs_up(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "snapshot.json"
            path.write_text(SNAPSHOT.read_text(encoding="utf-8"), encoding="utf-8")
            out = io.StringIO()
            changes = [(date(2026, 7, 16), 2.75), (date(2026, 8, 27), 3.0), (date(2026, 10, 16), 3.25)]
            new = run_update(path, path, None, "balanced", today=date(2026, 10, 20),
                             fetch=fake_fetch(changes), out=out)
            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), new)
            self.assertTrue((Path(d) / "snapshot.json.bak").exists())
            self.assertEqual(new["base_rate_history"][-1]["rate"], 3.25)
            self.assertIn("+0.25%p", out.getvalue())


if __name__ == "__main__":
    unittest.main()
