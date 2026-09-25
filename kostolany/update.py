"""요청이 있을 때만 최신 지표를 받아 스냅샷을 갱신하고, 무엇이 바뀌었는지 보여준다."""
from __future__ import annotations

import copy
import json
import sys
from datetime import date
from pathlib import Path

from .ecos import update_snapshot
from .model import ASSETS, analyze, current_rate
from .text import pad

FIELDS = [  # (라벨, 값 꺼내기, 단위)
    ("기준금리", current_rate, "%"),
    ("소비자물가", lambda s: s.get("cpi_yoy"), "%"),
    ("근원물가", lambda s: s.get("core_cpi_yoy"), "%"),
    ("국고채 3년", lambda s: s.get("ktb_3y"), "%"),
    ("국고채 10년", lambda s: s.get("ktb_10y"), "%"),
    ("원/달러", lambda s: s.get("usdkrw"), "원"),
]
MANUAL = "성장률·통화정책 기조·집값·가계부채는 자동으로 바뀌지 않습니다. 금통위 발표 후 snapshot.json에서 직접 고쳐 주세요."


def diff_lines(old: dict, new: dict) -> list[str]:
    lines = []
    for label, get, unit in FIELDS:
        a, b = get(old), get(new)
        if a == b:
            mark = "변동 없음"
        elif a is None or b is None:
            mark = "새로 받음" if a is None else "값 없음"
        else:
            mark = f"{b - a:+.2f}%p" if unit == "%" else f"{b - a:+,.1f}{unit}"
        lines.append(f"  {pad(label, 12)}{_fmt(a, unit):>11} → {_fmt(b, unit):>11}   {mark}")
    return lines


def _fmt(v, unit):
    if v is None:
        return "-"
    return f"{v:.2f}%" if unit == "%" else f"{v:,.1f}{unit}"


def run_update(path: Path, save_to: Path, key: str | None, risk: str, today: date | None = None,
               fetch=None, out=sys.stdout) -> dict:
    """path의 스냅샷을 최신 값으로 갱신해 save_to에 저장하고 변화 요약을 출력한다."""
    def say(*a):
        print(*a, file=out)

    old = json.loads(path.read_text(encoding="utf-8"))
    new = copy.deepcopy(old)
    say(f"\n최신 지표를 받는 중... (한국은행 ECOS{'' if key else ', 공개 sample 키'})")
    for line in update_snapshot(new, key, today, fetch):
        say(f"  · {line}")

    say(f"\n[업데이트 결과] {old['as_of']} → {new['as_of']}")
    for line in diff_lines(old, new):
        say(line)

    a0, a1 = analyze(old, risk), analyze(new, risk)
    if a0.phase is a1.phase:
        say(f"\n  달걀 위치: {a1.phase.code} {a1.phase.name} 유지 "
              f"(확률 {a0.probabilities[a0.phase.code] * 100:.0f}% → {a1.probabilities[a1.phase.code] * 100:.0f}%)")
    else:
        say(f"\n  ★ 달걀 위치 변경: {a0.phase.code} {a0.phase.name} → {a1.phase.code} {a1.phase.name}")
    moves = [f"{k} {a1.allocation[k] - a0.allocation[k]:+d}%p" for k in ASSETS
             if a1.allocation[k] != a0.allocation[k]]
    say(f"  자산배분 조정: {', '.join(moves) if moves else '변경 없음'}")
    say(f"  ※ {MANUAL}")

    if save_to.exists():
        save_to.with_name(save_to.name + ".bak").write_text(save_to.read_text(encoding="utf-8"), encoding="utf-8")
    save_to.parent.mkdir(parents=True, exist_ok=True)
    save_to.write_text(json.dumps(new, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    say(f"  저장: {save_to}")
    return new
