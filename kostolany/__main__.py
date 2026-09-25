"""사용법: python -m kostolany [--update] [--ecos KEY] [--risk conservative|balanced|aggressive] [--snapshot PATH] [--chart [PATH]] [--open]"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from .model import ASSETS, PHASES, Analysis, analyze, heading
from .scenarios import run_scenarios
from .text import pad

DEFAULT_SNAPSHOT = Path(__file__).resolve().parent.parent / "data" / "snapshot.json"
FROZEN = getattr(sys, "frozen", False)  # PyInstaller로 만든 실행 파일인지


def default_snapshot() -> Path:
    """실행 파일 옆에 snapshot.json이 있으면 그것을, 없으면 내장된 값을 쓴다."""
    if FROZEN:
        beside = Path(sys.executable).parent / "snapshot.json"
        return beside if beside.exists() else Path(sys._MEIPASS) / "data" / "snapshot.json"
    return DEFAULT_SNAPSHOT


# 현재 국면에서 다음 국면으로 넘어가는 신호
NEXT_SIGNALS = {
    "A1": "첫 인하 단행 또는 인하 소수의견 등장, 물가 2%대 초반 안착",
    "A2": "인하 폭 축소, 경기선행지수 반등, 신용스프레드 축소",
    "A3": "인하 사이클 종료 선언, 물가 재상승, 집값 급등",
    "B1": "연속 인상, 국고채 3년물이 기준금리를 크게 상회, 물가 목표 상회 지속",
    "B2": "인상 속도 둔화·동결, 물가 정점 통과, 수출·소비 둔화",
    "B3": "연속 동결, 경기 둔화 신호, 시장금리 하락 반전",
}

EGG = """
                 A1 금리 정점  ·  B3 정점 근접
              A1 ●────────────────────────● B3
           ↙ 매수: 채권                     매도: 채권 ↖
      A2 ●  금리 하락                      금리 상승  ● B2
           ↘ 매수: 부동산·주식         매도: 주식·부동산 ↗
              A3 ●────────────────────────● B1
                 A3 바닥권  ·  B1 인상 개시
"""


def bar(pct: float, width: int = 30) -> str:
    n = round(pct / 100 * width)
    return "█" * n + "·" * (width - n)


def print_report(s: dict, a: Analysis, risk: str) -> None:
    p = a.phase
    print("=" * 64)
    print(f" 코스톨라니 달걀 모형 · 한국 경기 분석   (기준일 {s['as_of']})")
    print("=" * 64)

    print("\n[1] 주요 지표")
    rows = [
        ("기준금리", f"{a.rate:.2f}%"),
        ("소비자물가(전년비)", _fmt(s.get("cpi_yoy"), "%")),
        ("근원물가(전년비)", _fmt(s.get("core_cpi_yoy"), "%")),
        ("성장률(전망)", _fmt(s.get("gdp_growth"), "%")),
        ("국고채 3년 / 10년", f"{_fmt(s.get('ktb_3y'), '%')} / {_fmt(s.get('ktb_10y'), '%')}"),
        ("원/달러", _fmt(s.get("usdkrw"), "원")),
        ("집값 / 가계부채", f"{s.get('house_price_trend')} / {s.get('household_debt_trend')}"),
        ("통화정책 기조", s.get("policy_guidance")),
    ]
    for k, v in rows:
        print(f"  {pad(k, 20)}{v}")

    print("\n[2] 금리 사이클 진단")
    regime = {"hiking": "인상 사이클", "cutting": "인하 사이클", "flat": "변동 없음"}[a.regime]
    turn = "바닥" if a.regime == "hiking" else "정점"
    print(f"  현재 {regime} · {turn} {a.turn_rate:.2f}%에서 누적 {a.streak_bp}bp"
          f"{' · 최근 동결 중' if a.holding else ''}")
    print(f"  경제 여건상 적정 기준금리 ≈ {a.target_rate:.2f}%")
    for k, v in a.target_parts.items():
        print(f"    {pad(k, 32)}{v:+.2f}" if k != "중립금리" else f"    {pad(k, 32)}{v:.2f}")
    print(f"  사이클 진행률 {a.progress * 100:.0f}%")
    for n in a.notes:
        print(f"  ※ {n}")

    print("\n[3] 달걀 모형 위치")
    print(EGG)
    print(f"  ▶ 현재 위치: {p.code} ({p.name}) — {p.rate_view}")
    print(f"  ▶ 코스톨라니식 행동: {p.action}")
    nxt, speed = heading(a)
    print(f"  ▶ 진행 방향: {p.code} {p.name} ──▶ {nxt.code} {nxt.name} ({speed})")
    print("  국면별 확률:")
    for ph in PHASES:
        pr = a.probabilities[ph.code] * 100
        if pr >= 1:
            print(f"    {ph.code} {pad(ph.name, 16)}{bar(pr, 20)} {pr:4.0f}%")

    risk_kr = {"conservative": "안정형", "balanced": "중립형", "aggressive": "공격형"}[risk]
    print(f"\n[4] 추천 자산배분 ({risk_kr})")
    for asset in ASSETS:
        print(f"    {pad(asset, 18)}{bar(a.allocation[asset])} {a.allocation[asset]:3d}%")
    print(f"  다음 국면으로 넘어가는 신호: {NEXT_SIGNALS[p.code]}")


def print_scenarios(results, base: Analysis) -> None:
    print("\n[5] 앞으로의 변화별 대응")
    for sc, a in results:
        print(f"\n  ◆ {sc.name}  →  {a.phase.code} ({a.phase.name})")
        print(f"    신호: {sc.trigger}")
        diffs = [(k, a.allocation[k] - base.allocation[k]) for k in ASSETS]
        ups = [f"{k} +{d}%p" for k, d in diffs if d > 0]
        downs = [f"{k} {d}%p" for k, d in diffs if d < 0]
        print(f"    늘리기: {', '.join(ups) or '없음'}")
        print(f"    줄이기: {', '.join(downs) or '없음'}")
        print(f"    목표 배분: " + " / ".join(f"{k} {a.allocation[k]}%" for k in ASSETS))


def _fmt(v, unit):
    return "-" if v is None else f"{v:,}{unit}"


def save_path(snapshot: Path) -> Path:
    """업데이트 결과를 저장할 곳. 실행 파일의 내장 데이터는 읽기 전용이라 실행 파일 옆에 저장한다."""
    if FROZEN and Path(sys._MEIPASS) in snapshot.resolve().parents:
        return Path(sys.executable).parent / "snapshot.json"
    return snapshot


def main(argv=None) -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    if FROZEN and argv is None and len(sys.argv) == 1:
        _menu()  # 실행 파일을 더블클릭한 경우
    else:
        _run(argv)


def _menu() -> None:
    """더블클릭 실행용 메뉴. 업데이트는 사용자가 고를 때만 한다."""
    import tempfile
    chart = str(Path(tempfile.gettempdir()) / "kostolany_egg.svg")
    while True:
        as_of = json.loads(default_snapshot().read_text(encoding="utf-8"))["as_of"]
        print("\n" + "=" * 48)
        print(" 코스톨라니 달걀 모형 · 한국 경기 분석")
        print("=" * 48)
        print(f"  1) 저장된 지표로 분석 (기준일 {as_of})")
        print("  2) 최신 금리·물가로 업데이트 후 분석")
        print("  q) 종료")
        try:
            choice = input("선택 > ").strip().lstrip("﻿").lower()
        except EOFError:
            return
        if choice in ("q", "3"):
            return
        if choice not in ("1", "2"):
            continue
        try:
            _run((["--update"] if choice == "2" else []) + ["--chart", chart, "--open"])
        except Exception as e:  # 창이 닫히지 않도록 오류를 보여주고 메뉴로 돌아간다
            print(f"\n오류: {e}")
        try:
            input("\n엔터를 누르면 메뉴로 돌아갑니다...")
        except EOFError:
            return


def _run(argv) -> None:
    ap = argparse.ArgumentParser(description="코스톨라니 달걀 모형으로 한국 경기 국면과 자산배분을 분석합니다.")
    ap.add_argument("--snapshot", type=Path, default=default_snapshot(), help="지표 스냅샷 JSON 경로")
    ap.add_argument("--update", action="store_true",
                    help="한국은행 ECOS에서 최신 금리·물가·환율을 받아 스냅샷을 갱신·저장한 뒤 분석")
    ap.add_argument("--ecos", metavar="KEY", default=os.environ.get("ECOS_API_KEY"),
                    help="--update에 쓸 ECOS API 키 (없으면 공개 sample 키, 환경변수 ECOS_API_KEY)")
    ap.add_argument("--risk", choices=["conservative", "balanced", "aggressive"], default="balanced")
    ap.add_argument("--json", action="store_true", help="결과를 JSON으로 출력")
    ap.add_argument("--chart", nargs="?", const=Path("egg.svg"), type=Path, metavar="PATH",
                    help="달걀 모형 위치 그림을 SVG로 저장 (기본 egg.svg)")
    ap.add_argument("--open", action="store_true", help="저장한 그림을 브라우저로 열기 (--chart 포함)")
    args = ap.parse_args(argv)

    if args.update:
        from .update import run_update
        s = run_update(args.snapshot, save_path(args.snapshot), args.ecos, args.risk,
                       out=sys.stderr if args.json else sys.stdout)
        if not args.json:
            print()
    else:
        s = json.loads(args.snapshot.read_text(encoding="utf-8"))

    a = analyze(s, args.risk)
    results = run_scenarios(s, args.risk)

    if args.open and not args.chart:
        args.chart = Path("egg.svg")
    if args.chart:
        from .chart import render_svg
        args.chart.write_text(render_svg(s, a, results), encoding="utf-8")
        print(f"[그림] {args.chart.resolve()} 저장", file=sys.stderr)
        if args.open:
            import webbrowser
            webbrowser.open(args.chart.resolve().as_uri())

    if args.json:
        out = {
            "as_of": s["as_of"], "phase": a.phase.code, "phase_name": a.phase.name,
            "probabilities": {k: round(v, 3) for k, v in a.probabilities.items()},
            "target_rate": round(a.target_rate, 2), "allocation": a.allocation,
            "scenarios": [{"name": sc.name, "phase": r.phase.code, "allocation": r.allocation}
                          for sc, r in results],
        }
        print(json.dumps(out, ensure_ascii=False, indent=2))
        return

    print_report(s, a, args.risk)
    print_scenarios(results, a)
    print("\n※ 교육·참고용 모형입니다. 투자 판단과 책임은 본인에게 있습니다.")


if __name__ == "__main__":
    main()
