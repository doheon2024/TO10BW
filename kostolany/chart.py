"""달걀 모형 위의 현재 위치와 진행 방향을 SVG 그림으로 그린다 (외부 패키지 없음)."""
from __future__ import annotations

import math
from xml.sax.saxutils import escape

from .model import ASSETS, PHASES, Analysis, heading

W, H = 1080, 820
CX, CY, RX, RY = 410, 350, 170, 245
FONT = "'Malgun Gothic','Apple SD Gothic Neo','Noto Sans KR',sans-serif"
A_COLOR, B_COLOR, NOW_COLOR = "#2f6fb3", "#d9772b", "#c0392b"
INK, MUTED = "#222", "#6b6b6b"

SHORT_ACTION = {
    "A1": "예금 → 장기채권",
    "A2": "채권 보유, 주식·부동산 분할매수",
    "A3": "채권 차익실현, 주식 비중 최대",
    "B1": "주식·부동산 차익실현 시작",
    "B2": "예금·실물자산 확대",
    "B3": "예금 최대, 장기채 매수 준비",
}
MARKS = "①②③④⑤⑥⑦⑧"


def _pt(pos: float, scale: float = 1.0) -> tuple[float, float]:
    """위치 0(꼭대기, 금리 정점)에서 시작해 왼쪽(하락)→바닥→오른쪽(상승)으로 돈다."""
    th = math.radians(90 + pos * 60)
    s = math.sin(th)
    x = CX + RX * scale * math.cos(th) * (1 - 0.12 * s)  # 위쪽이 좁은 달걀 모양
    y = CY - RY * scale * s
    return x, y


def _arc(p0: float, p1: float, scale: float = 1.0, n: int = 48) -> str:
    pts = [_pt(p0 + (p1 - p0) * i / n, scale) for i in range(n + 1)]
    return "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in pts)


def _text(x, y, s, size=14, color=INK, anchor="start", weight="normal"):
    return (f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size}" fill="{color}" '
            f'text-anchor="{anchor}" font-weight="{weight}">{escape(str(s))}</text>')


def _hbar(x, y, label, pct, color, width=150):
    return "".join([
        _text(x, y + 11, label, 13),
        f'<rect x="{x + 115}" y="{y}" width="{width}" height="13" rx="3" fill="#eee"/>',
        f'<rect x="{x + 115}" y="{y}" width="{width * pct / 100:.1f}" height="13" rx="3" fill="{color}"/>',
        _text(x + 120 + width, y + 11, f"{pct:.0f}%", 13),
    ])


def render_svg(snapshot: dict, a: Analysis, scenarios=()) -> str:
    cur = a.phase
    nxt, speed = heading(a)
    out = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="{FONT}">',
        '<defs><marker id="head" viewBox="0 0 10 10" refX="6" refY="5" markerWidth="5" markerHeight="5" orient="auto">'
        f'<path d="M0,0 L10,5 L0,10 z" fill="{NOW_COLOR}"/></marker></defs>',
        f'<rect width="{W}" height="{H}" fill="#fff"/>',
        _text(30, 38, "코스톨라니 달걀 모형 · 한국 경기 위치", 22, weight="bold"),
        _text(30, 60, f"기준일 {snapshot['as_of']} · 기준금리 {a.rate:.2f}% · 적정금리 추정 {a.target_rate:.2f}%", 13, MUTED),
        _text(CX, CY - RY * 1.1 - 10, "▲ 금리 정점", 13, MUTED, "middle"),
        _text(CX, CY + RY * 1.1 + 22, "▼ 금리 바닥", 13, MUTED, "middle"),
    ]

    # 국면별 궤도: 확률이 높을수록 진하게
    for i, p in enumerate(PHASES):
        color = A_COLOR if p.code.startswith("A") else B_COLOR
        prob = a.probabilities[p.code]
        out.append(f'<path d="{_arc(i + 0.02, i + 0.98)}" fill="none" stroke="{color}" '
                   f'stroke-width="16" stroke-linecap="round" opacity="{0.18 + 0.82 * prob:.2f}"/>')
        lx, ly = _pt(i + 0.5, 1.2)
        anchor = "end" if lx < CX else "start"
        weight = "bold" if p is cur else "normal"
        out.append(_text(lx, ly - 4, f"{p.code} {p.name}  {prob * 100:.0f}%", 15, color, anchor, weight))
        out.append(_text(lx, ly + 14, SHORT_ACTION[p.code], 12, MUTED, anchor))
    for k in (0, 3):  # A/B 경계
        x0, y0 = _pt(k, 0.9)
        x1, y1 = _pt(k, 1.1)
        out.append(f'<line x1="{x0:.1f}" y1="{y0:.1f}" x2="{x1:.1f}" y2="{y1:.1f}" stroke="{INK}" stroke-width="2"/>')

    # 진행 방향 화살표 (달걀은 반시계 방향으로만 돈다)
    out.append(f'<path d="{_arc(a.position + 0.12, a.position + 1.0, 0.84)}" fill="none" '
               f'stroke="{NOW_COLOR}" stroke-width="5" stroke-dasharray="10 6" marker-end="url(#head)"/>')

    # 시나리오 도착 지점
    placed = []
    for n, (sc, r) in enumerate(scenarios):
        scale = 1.0
        for q in placed:
            if abs((r.position - q + 3) % 6 - 3) < 0.25:
                scale -= 0.13
        placed.append(r.position)
        x, y = _pt(r.position, scale)
        out.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="11" fill="#fff" stroke="{INK}" stroke-width="1.5"/>')
        out.append(_text(x, y + 5, MARKS[n], 14, INK, "middle", "bold"))

    # 현재 위치
    x, y = _pt(a.position)
    out.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="20" fill="{NOW_COLOR}" opacity="0.2"/>')
    out.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="11" fill="{NOW_COLOR}" stroke="#fff" stroke-width="3"/>')

    # 달걀 가운데 요약
    out += [
        _text(CX, CY - 48, "지금", 14, MUTED, "middle"),
        _text(CX, CY - 8, cur.code, 44, NOW_COLOR, "middle", "bold"),
        _text(CX, CY + 18, f"{cur.name} ({a.probabilities[cur.code] * 100:.0f}%)", 16, INK, "middle", "bold"),
        _text(CX, CY + 48, f"→ {nxt.code} {nxt.name} 방향", 15, NOW_COLOR, "middle", "bold"),
        _text(CX, CY + 68, speed, 12, MUTED, "middle"),
    ]

    # 오른쪽 패널
    px = 765
    turn = "바닥" if a.regime == "hiking" else "정점"
    out.append(_text(px, 110, "금리 사이클 진행률", 16, weight="bold"))
    out.append(_text(px, 132, f"{turn} {a.turn_rate:.2f}% → 현재 {a.rate:.2f}% → 적정 {a.target_rate:.2f}%", 12, MUTED))
    out.append(_hbar(px, 142, "진행률", a.progress * 100, NOW_COLOR))

    out.append(_text(px, 200, "국면별 확률", 16, weight="bold"))
    for i, p in enumerate(PHASES):
        color = A_COLOR if p.code.startswith("A") else B_COLOR
        out.append(_hbar(px, 212 + i * 22, f"{p.code} {p.name}", a.probabilities[p.code] * 100, color))

    out.append(_text(px, 370, "추천 자산배분", 16, weight="bold"))
    for i, asset in enumerate(ASSETS):
        out.append(_hbar(px, 382 + i * 22, asset, a.allocation[asset], "#4d8b5a"))

    out.append(_text(px, 530, "범례", 16, weight="bold"))
    out.append(f'<circle cx="{px + 8}" cy="{552}" r="8" fill="{NOW_COLOR}"/>')
    out.append(_text(px + 24, 557, "현재 위치", 13))
    out.append(f'<line x1="{px}" y1="576" x2="{px + 18}" y2="576" stroke="{NOW_COLOR}" stroke-width="4" stroke-dasharray="6 3"/>')
    out.append(_text(px + 24, 581, "앞으로 향하는 방향", 13))
    out.append(f'<circle cx="{px + 8}" cy="600" r="8" fill="#fff" stroke="{INK}"/>')
    out.append(_text(px + 24, 605, "시나리오별 도착 국면 (아래 번호)", 13))
    out.append(_text(px, 629, "궤도가 진할수록 해당 국면 확률이 높음", 12, MUTED))

    # 아래: 시나리오 설명
    if scenarios:
        out.append(_text(30, 675, "변화가 생기면", 16, weight="bold"))
        for n, (sc, r) in enumerate(scenarios):
            diffs = sorted(((r.allocation[k] - a.allocation[k], k) for k in ASSETS), reverse=True)
            ups = ", ".join(f"{k} +{d}" for d, k in diffs if d > 0)
            downs = ", ".join(f"{k} {d}" for d, k in reversed(diffs) if d < 0)
            y = 700 + n * 28
            out.append(_text(30, y, f"{MARKS[n]} {sc.name} → {r.phase.code} {r.phase.name}", 14, weight="bold"))
            out.append(_text(320, y, f"▲ {ups or '-'}", 12, "#4d8b5a"))
            out.append(_text(640, y, f"▼ {downs or '-'}", 12, NOW_COLOR))

    out.append("</svg>")
    return "\n".join(out)
