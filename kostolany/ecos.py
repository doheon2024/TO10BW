"""한국은행 ECOS Open API로 최신 금리·물가·환율을 받아 스냅샷을 갱신한다.

키가 없으면 공개 'sample' 키를 쓴다 (호출당 10건 제한 → 기간을 잘게 나눠 요청).
개인 키 발급: https://ecos.bok.or.kr/api/  (무료)
"""
from __future__ import annotations

import json
import urllib.request
from datetime import date, timedelta
from typing import Callable

SAMPLE_KEY = "sample"
MAX_ROWS = 10
URL = "https://ecos.bok.or.kr/api/StatisticSearch/{key}/json/kr/1/{n}/{stat}/{cycle}/{start}/{end}/{item}"

BASE_RATE = ("722Y001", "0101000")   # 한국은행 기준금리 (월: 월말 값, 일: 매일)
CPI = ("901Y009", "0")               # 소비자물가지수 총지수
CORE_CPI = ("901Y010", "DB")         # 식료품 및 에너지 제외 지수
KTB_3Y = ("817Y002", "010200000")    # 국고채 3년
KTB_10Y = ("817Y002", "010210000")   # 국고채 10년
USDKRW = ("731Y001", "0000001")      # 원/미국달러 매매기준율

Fetch = Callable[[tuple, str, str, str], list]


def http_fetch(key: str) -> Fetch:
    def fetch(series: tuple, cycle: str, start: str, end: str) -> list[tuple[str, float]]:
        stat, item = series
        url = URL.format(key=key, n=MAX_ROWS if key == SAMPLE_KEY else 1000,
                         stat=stat, cycle=cycle, start=start, end=end, item=item)
        with urllib.request.urlopen(url, timeout=15) as resp:
            raw = resp.read()
        try:
            body = json.loads(raw.decode("utf-8"))
        except UnicodeDecodeError:
            body = json.loads(raw.decode("cp949"))
        if "StatisticSearch" not in body:
            result = body.get("RESULT", {})
            if result.get("CODE") == "INFO-200":  # 해당 기간 데이터 없음
                return []
            raise RuntimeError(result.get("MESSAGE", "ECOS 응답 오류").splitlines()[0])
        return [(r["TIME"], float(r["DATA_VALUE"])) for r in body["StatisticSearch"]["row"]]
    return fetch


def _ymd(d: date) -> str:
    return d.strftime("%Y%m%d")


def _ym(d: date) -> str:
    return d.strftime("%Y%m")


def _add_months(d: date, n: int) -> date:
    m = d.year * 12 + d.month - 1 + n
    return date(m // 12, m % 12 + 1, 1)


def _daily(fetch: Fetch, series, start: date, end: date) -> list[tuple[str, float]]:
    """10일 이하 구간으로 나눠 일별 값을 받는다."""
    rows, d = [], start
    while d <= end:
        stop = min(end, d + timedelta(days=MAX_ROWS - 1))
        rows += fetch(series, "D", _ymd(d), _ymd(stop))
        d = stop + timedelta(days=1)
    return rows


def base_rate_changes(fetch: Fetch, last: dict, today: date) -> list[dict]:
    """마지막으로 알고 있는 금리 변경(last) 이후의 변경 내역을 찾는다."""
    since = date.fromisoformat(last["date"])
    rate, changes = last["rate"], []

    def record(day: date, value: float):
        nonlocal rate
        if value != rate:
            changes.append({"date": day.isoformat(), "rate": value})
            rate = value

    # 완결된 달은 월말 값으로 훑고, 값이 바뀐 달만 일별로 정확한 날짜를 찾는다
    month, this_month = date(since.year, since.month, 1), date(today.year, today.month, 1)
    while month < this_month:
        stop = min(_add_months(month, MAX_ROWS - 1), _add_months(this_month, -1))
        for t, v in fetch(BASE_RATE, "M", _ym(month), _ym(stop)):
            if v != rate:
                m = date(int(t[:4]), int(t[4:]), 1)
                first = max(m, since + timedelta(days=1))
                for dt, dv in _daily(fetch, BASE_RATE, first, _add_months(m, 1) - timedelta(days=1)):
                    record(date(int(dt[:4]), int(dt[4:6]), int(dt[6:])), dv)
        month = _add_months(stop, 1)
    # 이번 달은 월 통계가 아직 없으므로 일별로 확인
    for dt, dv in _daily(fetch, BASE_RATE, max(this_month, since + timedelta(days=1)), today):
        record(date(int(dt[:4]), int(dt[4:6]), int(dt[6:])), dv)
    return changes


def latest_daily(fetch: Fetch, series, today: date) -> tuple[str, float]:
    """가장 최근 영업일 값 (연휴를 고려해 최대 40일 전까지 거슬러 올라간다)."""
    end = today
    for _ in range(4):
        start = end - timedelta(days=MAX_ROWS - 1)
        rows = fetch(series, "D", _ymd(start), _ymd(end))
        if rows:
            t, v = rows[-1]
            return f"{t[:4]}-{t[4:6]}-{t[6:]}", v
        end = start - timedelta(days=1)
    raise RuntimeError("최근 데이터 없음")


def latest_yoy(fetch: Fetch, series, today: date) -> tuple[str, float]:
    """가장 최근 발표월의 전년동월비(%)."""
    rows = fetch(series, "M", _ym(_add_months(today, -3)), _ym(today))
    if not rows:
        raise RuntimeError("최근 데이터 없음")
    t, v = rows[-1]
    y, m = int(t[:4]), int(t[4:])
    prev = fetch(series, "M", f"{y - 1}{m:02d}", f"{y - 1}{m:02d}")
    return f"{y}-{m:02d}", round((v / prev[0][1] - 1) * 100, 1)


def update_snapshot(snapshot: dict, key: str | None = None, today: date | None = None,
                    fetch: Fetch | None = None) -> list[str]:
    """스냅샷을 제자리에서 갱신하고 항목별 결과 메시지를 돌려준다. 실패한 항목은 기존 값을 유지한다."""
    today = today or date.today()
    fetch = fetch or http_fetch(key or SAMPLE_KEY)
    log = []

    def attempt(label, fn):
        try:
            log.append(f"{label}: {fn()}")
        except Exception as e:  # 네트워크 오류 등은 기존 값 유지
            log.append(f"{label}: 실패 ({e}) → 기존 값 유지")

    def base_rate():
        history = sorted(snapshot["base_rate_history"], key=lambda h: h["date"])
        new = base_rate_changes(fetch, history[-1], today)
        snapshot["base_rate_history"] = history + new
        return ", ".join(f"{c['date']} {c['rate']:.2f}%" for c in new) + " 변경" if new else "변경 없음"

    def yoy(field, series):
        def run():
            month, value = latest_yoy(fetch, series, today)
            snapshot[field] = value
            snapshot[f"{field}_month"] = month
            return f"{value}% ({month})"
        return run

    def daily(field, series):
        def run():
            day, value = latest_daily(fetch, series, today)
            snapshot[field] = value
            return f"{value:,} ({day})"
        return run

    attempt("기준금리", base_rate)
    attempt("소비자물가", yoy("cpi_yoy", CPI))
    attempt("근원물가", yoy("core_cpi_yoy", CORE_CPI))
    attempt("국고채 3년", daily("ktb_3y", KTB_3Y))
    attempt("국고채 10년", daily("ktb_10y", KTB_10Y))
    attempt("원/달러", daily("usdkrw", USDKRW))
    snapshot["as_of"] = today.isoformat()
    return log
