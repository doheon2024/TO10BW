"""한국은행 ECOS Open API로 최신 지표를 받아 스냅샷을 갱신한다.

API 키 발급: https://ecos.bok.or.kr/api/  (무료)
"""
from __future__ import annotations

import json
import urllib.request
from datetime import date, timedelta

BASE = "https://ecos.bok.or.kr/api/StatisticSearch/{key}/json/kr/1/1000/{stat}/{cycle}/{start}/{end}/{item}"

BASE_RATE = ("722Y001", "D", "0101000")   # 한국은행 기준금리
CPI = ("901Y009", "M", "0")               # 소비자물가지수 총지수
KTB_3Y = ("817Y002", "D", "010200000")    # 국고채 3년
KTB_10Y = ("817Y002", "D", "010210000")   # 국고채 10년
USDKRW = ("731Y001", "D", "0000001")      # 원/미국달러 매매기준율


def _fetch(key: str, series: tuple, start: str, end: str) -> list[tuple[str, float]]:
    stat, cycle, item = series
    url = BASE.format(key=key, stat=stat, cycle=cycle, start=start, end=end, item=item)
    with urllib.request.urlopen(url, timeout=15) as resp:
        body = json.load(resp)
    if "StatisticSearch" not in body:
        raise RuntimeError(body.get("RESULT", {}).get("MESSAGE", "ECOS 응답 오류"))
    return [(r["TIME"], float(r["DATA_VALUE"])) for r in body["StatisticSearch"]["row"]]


def _day(t: str) -> str:
    return f"{t[:4]}-{t[4:6]}-{t[6:8]}"


def update_snapshot(snapshot: dict, key: str) -> list[str]:
    """스냅샷을 제자리에서 갱신하고, 갱신된 항목 설명을 돌려준다. 실패한 항목은 건너뛴다."""
    today = date.today()
    d_end = today.strftime("%Y%m%d")
    d_start = (today - timedelta(days=365 * 3)).strftime("%Y%m%d")
    updated = []

    def attempt(label, fn):
        try:
            fn()
            updated.append(label)
        except Exception as e:  # 네트워크·키 오류는 수동 값으로 대체
            updated.append(f"{label} 실패({e}) → 수동 값 사용")

    def base_rate():
        rows = _fetch(key, BASE_RATE, d_start, d_end)
        history = [{"date": _day(rows[0][0]), "rate": rows[0][1]}]
        for t, v in rows[1:]:
            if v != history[-1]["rate"]:
                history.append({"date": _day(t), "rate": v})
        snapshot["base_rate_history"] = history

    def cpi():
        rows = _fetch(key, CPI, (today - timedelta(days=500)).strftime("%Y%m"), today.strftime("%Y%m"))
        snapshot["cpi_yoy"] = round((rows[-1][1] / rows[-13][1] - 1) * 100, 1)

    def daily(field, series):
        def run():
            rows = _fetch(key, series, (today - timedelta(days=14)).strftime("%Y%m%d"), d_end)
            snapshot[field] = rows[-1][1]
        return run

    attempt("기준금리", base_rate)
    attempt("소비자물가", cpi)
    attempt("국고채 3년", daily("ktb_3y", KTB_3Y))
    attempt("국고채 10년", daily("ktb_10y", KTB_10Y))
    attempt("원/달러 환율", daily("usdkrw", USDKRW))
    snapshot["as_of"] = today.isoformat()
    return updated
