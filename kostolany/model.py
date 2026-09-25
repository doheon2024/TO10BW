"""코스톨라니 달걀 모형 분석 엔진.

달걀을 금리 사이클 위의 원형 좌표(0~6)로 보고, 현재 금리 경로와
테일러 준칙 형태의 '적정 금리'를 비교해 사이클상 위치를 추정한다.

    0 A1 금리 정점   1 A2 금리 하락   2 A3 금리 바닥권
    3 B1 인상 개시   4 B2 인상 진행   5 B3 금리 정점 근접
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date

ASSETS = ["예금·단기채", "장기채권", "주식", "부동산·리츠", "금·달러·원자재"]


@dataclass(frozen=True)
class Phase:
    code: str
    name: str
    rate_view: str
    action: str
    allocation: dict  # 자산 -> 비중(%)


PHASES = [
    Phase("A1", "금리 정점", "금리가 고점에서 머물고 인하 기대가 생기는 구간",
          "예금을 빼서 장기채권을 산다. 주식은 공포 속에서 소량 분할 매수",
          {"예금·단기채": 20, "장기채권": 45, "주식": 20, "부동산·리츠": 5, "금·달러·원자재": 10}),
    Phase("A2", "금리 하락", "인하가 진행 중인 구간",
          "채권 평가이익을 누리며 주식·부동산을 분할 매수한다",
          {"예금·단기채": 10, "장기채권": 35, "주식": 30, "부동산·리츠": 15, "금·달러·원자재": 10}),
    Phase("A3", "금리 바닥권", "인하가 막바지이거나 저금리가 유지되는 구간",
          "채권 차익을 실현하고 주식 비중을 최대로 가져간다",
          {"예금·단기채": 10, "장기채권": 15, "주식": 45, "부동산·리츠": 20, "금·달러·원자재": 10}),
    Phase("B1", "인상 개시", "금리가 바닥을 벗어나 오르기 시작한 구간 (자산시장 과열)",
          "주식·부동산 차익실현을 시작하고 예금·단기채와 실물자산을 늘린다",
          {"예금·단기채": 20, "장기채권": 10, "주식": 40, "부동산·리츠": 15, "금·달러·원자재": 15}),
    Phase("B2", "인상 진행", "인상이 이어지고 긴축이 본격화되는 구간",
          "주식·부동산을 줄이고 예금·단기채와 인플레이션 헤지 자산을 늘린다",
          {"예금·단기채": 35, "장기채권": 10, "주식": 25, "부동산·리츠": 10, "금·달러·원자재": 20}),
    Phase("B3", "금리 정점 근접", "인상이 막바지이거나 멈춘 고금리 구간",
          "고금리 예금에 머물면서 장기채권 매수를 준비한다",
          {"예금·단기채": 40, "장기채권": 25, "주식": 15, "부동산·리츠": 5, "금·달러·원자재": 15}),
]

RISK_TILT = {
    # 주식에서 빼서(음수) 안전자산에 더하는 비율
    "conservative": {"주식": -10, "부동산·리츠": -5, "예금·단기채": 10, "장기채권": 5},
    "balanced": {},
    "aggressive": {"주식": 10, "예금·단기채": -7, "장기채권": -3},
}

HOLD_MONTHS = 4  # 마지막 금리 변경 후 이 기간이 지나면 '동결 국면'으로 본다
SIGMA = 0.6      # 위치 추정의 불확실성 (확률 분산 폭)


@dataclass
class Analysis:
    rate: float
    target_rate: float
    target_parts: dict
    regime: str            # hiking / cutting / flat
    holding: bool
    streak_bp: int
    turn_rate: float       # 이번 사이클이 시작된 금리 (바닥 또는 정점)
    progress: float
    position: float
    probabilities: dict    # 코드 -> 확률
    allocation: dict
    notes: list = field(default_factory=list)

    @property
    def phase(self) -> Phase:
        return phase_at(self.position)


def phase_at(position: float) -> Phase:
    return PHASES[int(position % 6)]


def heading(a: "Analysis") -> tuple[Phase, str]:
    """달걀은 한 방향으로만 돈다. 다음 국면과 그쪽으로 가는 속도를 추정한다."""
    nxt = phase_at(a.position + 1)
    gap = abs(a.target_rate - a.rate)
    if a.holding:
        speed = "동결 중이라 이동이 느림"
    elif gap >= 0.75:
        speed = "적정금리와 격차가 커서 빠르게 이동 중"
    else:
        speed = "완만하게 이동 중"
    return nxt, speed


def _clip(x, lo, hi):
    return max(lo, min(hi, x))


def _months_between(d1: date, d2: date) -> float:
    return (d2 - d1).days / 30.44


def target_rate(s: dict) -> tuple[float, dict]:
    """테일러 준칙을 단순화한 '경제 여건상 적정 기준금리' 추정."""
    parts = {"중립금리": s["neutral_rate"]}
    cpi = [v for v in (s.get("cpi_yoy"), s.get("core_cpi_yoy")) if v is not None]
    if cpi:
        gap = sum(cpi) / len(cpi) - s["inflation_target"]
        parts["물가 갭"] = 0.5 * _clip(gap, -3, 3)
    if s.get("gdp_growth") is not None:
        parts["성장 갭"] = 0.5 * _clip(s["gdp_growth"] - s["potential_growth"], -3, 3)
    parts["통화정책 기조"] = {"hawkish": 0.25, "dovish": -0.25}.get(s.get("policy_guidance"), 0.0)
    fin = {"rising": 0.25, "falling": -0.25}.get(s.get("house_price_trend"), 0.0)
    fin += {"rising": 0.125, "falling": -0.125}.get(s.get("household_debt_trend"), 0.0)
    fx = s.get("usdkrw")
    if fx is not None:
        fin += 0.25 if fx >= 1450 else (-0.125 if fx <= 1250 else 0.0)
    parts["금융안정(집값·가계부채·환율)"] = fin
    if s.get("ktb_3y") is not None:
        # 국고채 3년물이 기준금리보다 높으면 시장이 추가 인상을 반영 중
        parts["시장 기대(3년물-기준금리)"] = 0.5 * _clip(s["ktb_3y"] - current_rate(s), -1, 1)
    return sum(parts.values()), parts


def current_rate(s: dict) -> float:
    return s["base_rate_history"][-1]["rate"]


def _streak(history: list) -> tuple[str, int, float, date]:
    """마지막 금리 변경 방향으로 연속된 변경의 합(bp)과 사이클 시작 금리."""
    changes = []
    for prev, cur in zip(history, history[1:]):
        diff = round((cur["rate"] - prev["rate"]) * 100)
        if diff:
            changes.append((diff, prev["rate"], date.fromisoformat(cur["date"])))
    if not changes:
        return "flat", 0, history[-1]["rate"], date.fromisoformat(history[-1]["date"])
    sign = 1 if changes[-1][0] > 0 else -1
    total, turn = 0, changes[-1][1]
    for diff, before, _ in reversed(changes):
        if (diff > 0) != (sign > 0):
            break
        total += diff
        turn = before
    return ("hiking" if sign > 0 else "cutting"), abs(total), turn, changes[-1][2]


def analyze(s: dict, risk: str = "balanced") -> Analysis:
    history = sorted(s["base_rate_history"], key=lambda h: h["date"])
    s = {**s, "base_rate_history": history}
    r = current_rate(s)
    tr, parts = target_rate(s)
    regime, streak_bp, turn, last_change = _streak(history)
    holding = _months_between(last_change, date.fromisoformat(s["as_of"])) >= HOLD_MONTHS
    notes = []

    if regime == "hiking":
        span = tr - turn
        progress = _clip((r - turn) / span, 0, 1) if span > 0 else 1.0
        position = 3 + 3 * progress
        if holding and tr < r - 0.25:
            position = 6 + _clip((r - tr) / 1.0, 0, 1) * 0.9  # 정점 통과 → A1
            notes.append("인상이 멈췄고 적정금리가 현재보다 낮아 인하 전환(A1)이 가까워 보입니다.")
    elif regime == "cutting":
        span = turn - tr
        progress = _clip((turn - r) / span, 0, 1) if span > 0 else 1.0
        position = 3 * progress
        if holding and tr > r + 0.25:
            position = 3 + _clip((tr - r) / 1.0, 0, 1) * 0.9  # 바닥 통과 → B1
            notes.append("인하가 멈췄고 적정금리가 현재보다 높아 인상 전환(B1)이 가까워 보입니다.")
    else:
        progress = 0.5
        position = 3.5 if tr > r else 0.5

    if abs(tr - r) >= 0.75:
        direction = "인상" if tr > r else "인하"
        notes.append(f"적정금리({tr:.2f}%)와 현재 기준금리({r:.2f}%) 차이가 커서 추가 {direction} 여지가 큽니다.")

    probs = _probabilities(position)
    return Analysis(r, tr, parts, regime, holding, streak_bp, turn, progress,
                    position % 6, probs, blend_allocation(probs, risk), notes)


def _probabilities(position: float) -> dict:
    weights = {}
    for i, p in enumerate(PHASES):
        d = abs((position - (i + 0.5) + 3) % 6 - 3)  # 원형 거리
        weights[p.code] = math.exp(-(d ** 2) / (2 * SIGMA ** 2))
    total = sum(weights.values())
    return {k: v / total for k, v in weights.items()}


def blend_allocation(probs: dict, risk: str = "balanced") -> dict:
    alloc = {a: 0.0 for a in ASSETS}
    for p in PHASES:
        for a, w in p.allocation.items():
            alloc[a] += probs[p.code] * w
    for a, t in RISK_TILT[risk].items():
        alloc[a] = max(0.0, alloc[a] + t)
    total = sum(alloc.values())
    return _round_to_100({a: v * 100 / total for a, v in alloc.items()})


def _round_to_100(alloc: dict) -> dict:
    """5% 단위로 반올림하면서 합계 100%를 유지한다."""
    units = {a: int(v // 5) for a, v in alloc.items()}
    rest = sorted(alloc, key=lambda a: alloc[a] / 5 - units[a], reverse=True)
    for a in rest[: 20 - sum(units.values())]:
        units[a] += 1
    return {a: u * 5 for a, u in units.items()}
