"""앞으로 일어날 수 있는 변화를 입력값 변경으로 표현하고 모형을 다시 돌린다."""
from __future__ import annotations

import copy
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Callable

from .model import Analysis, analyze, current_rate


@dataclass
class Scenario:
    name: str
    trigger: str               # 어떤 신호가 나오면 이 시나리오로 보는지
    apply: Callable[[dict], None]


def _after(s: dict, days: int) -> str:
    return (date.fromisoformat(s["as_of"]) + timedelta(days=days)).isoformat()


def _move(s: dict, bp: int, days: int) -> None:
    s["base_rate_history"].append({"date": _after(s, days), "rate": round(current_rate(s) + bp / 100, 2)})
    s["as_of"] = _after(s, days + 1)


def _extra_hikes(s):
    _move(s, +25, 35)
    _move(s, +25, 45)
    s.update(cpi_yoy=3.2, core_cpi_yoy=3.0)


def _pause_and_cool(s):
    s["as_of"] = _after(s, 150)
    s.update(cpi_yoy=2.2, core_cpi_yoy=2.2, gdp_growth=2.2, policy_guidance="neutral",
             house_price_trend="flat", household_debt_trend="flat")


def _hard_landing(s):
    s["as_of"] = _after(s, 120)
    _move(s, -25, 20)
    s.update(cpi_yoy=1.8, core_cpi_yoy=1.9, gdp_growth=1.2, policy_guidance="dovish",
             house_price_trend="falling", household_debt_trend="flat")


def _stagflation(s):
    _move(s, +25, 35)
    s.update(cpi_yoy=4.0, core_cpi_yoy=3.6, gdp_growth=1.5, usdkrw=1500)


SCENARIOS = [
    Scenario("추가 인상 지속", "10·11월 금통위에서 연속 인상, 물가 3%대 유지, 매파적 가이던스",
             _extra_hikes),
    Scenario("인상 종료·물가 안정", "2회 이상 연속 동결, 물가 2%대 초반 안착, 집값·가계부채 둔화",
             _pause_and_cool),
    Scenario("경기 급랭·인하 전환", "반도체 수출 꺾임, 성장률 1%대, 첫 금리 인하",
             _hard_landing),
    Scenario("스태그플레이션", "원/달러 1,500원 돌파, 물가 4%, 성장 둔화 속 인상",
             _stagflation),
]


def run_scenarios(snapshot: dict, risk: str) -> list[tuple[Scenario, Analysis]]:
    results = []
    for sc in SCENARIOS:
        s = copy.deepcopy(snapshot)
        sc.apply(s)
        results.append((sc, analyze(s, risk)))
    return results
