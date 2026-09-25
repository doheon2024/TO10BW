package com.doheon.kostolany.data

import com.doheon.kostolany.model.RateChange
import com.doheon.kostolany.model.Snapshot
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/*
 * 한국은행 ECOS Open API로 최신 금리·물가·환율을 받는다 (kostolany/ecos.py 이식).
 * 공개 'sample' 키는 호출당 10건 제한이라 기간을 잘게 나눠 요청한다.
 */

const val SAMPLE_KEY = "sample"
const val MAX_ROWS = 10

data class Series(val stat: String, val item: String)

val BASE_RATE = Series("722Y001", "0101000")   // 한국은행 기준금리 (월: 월말 값)
val CPI = Series("901Y009", "0")               // 소비자물가지수 총지수
val CORE_CPI = Series("901Y010", "DB")         // 식료품 및 에너지 제외 지수
val KTB_3Y = Series("817Y002", "010200000")    // 국고채 3년
val KTB_10Y = Series("817Y002", "010210000")   // 국고채 10년
val USDKRW = Series("731Y001", "0000001")      // 원/미국달러 매매기준율

/** (계열, 주기 D/M, 시작, 끝) → [(시점, 값)] */
typealias Fetch = (Series, String, String, String) -> List<Pair<String, Double>>

fun httpFetch(key: String = SAMPLE_KEY): Fetch = { series, cycle, start, end ->
    val n = if (key == SAMPLE_KEY) MAX_ROWS else 1000
    val url = URL("https://ecos.bok.or.kr/api/StatisticSearch/$key/json/kr/1/$n/" +
        "${series.stat}/$cycle/$start/$end/${series.item}")
    val conn = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 15_000
    }
    val bytes = try { conn.inputStream.use { it.readBytes() } } finally { conn.disconnect() }
    val text = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
        .getOrElse { String(bytes, Charset.forName("EUC-KR")) }
    val body = JSONObject(text)
    if (!body.has("StatisticSearch")) {
        val result = body.optJSONObject("RESULT")
        if (result?.optString("CODE") == "INFO-200") emptyList()  // 해당 기간 데이터 없음
        else throw RuntimeException(result?.optString("MESSAGE")?.lineSequence()?.first() ?: "ECOS 응답 오류")
    } else {
        val rows = body.getJSONObject("StatisticSearch").getJSONArray("row")
        (0 until rows.length()).map {
            val r = rows.getJSONObject(it)
            r.getString("TIME") to r.getString("DATA_VALUE").toDouble()
        }
    }
}

private val YMD = DateTimeFormatter.BASIC_ISO_DATE
private fun LocalDate.ymd() = format(YMD)
private fun YearMonth.ym() = "%04d%02d".format(year, monthValue)
private fun parseDay(t: String) = LocalDate.parse(t, YMD)

/** 10일 이하 구간으로 나눠 일별 값을 받는다. */
private fun daily(fetch: Fetch, series: Series, start: LocalDate, end: LocalDate): List<Pair<String, Double>> {
    val rows = mutableListOf<Pair<String, Double>>()
    var d = start
    while (!d.isAfter(end)) {
        val stop = minOf(end, d.plusDays(MAX_ROWS - 1L))
        rows += fetch(series, "D", d.ymd(), stop.ymd())
        d = stop.plusDays(1)
    }
    return rows
}

/** 마지막으로 알고 있는 금리 변경(last) 이후의 변경 내역. */
fun baseRateChanges(fetch: Fetch, last: RateChange, today: LocalDate): List<RateChange> {
    val since = last.date
    var rate = last.rate
    val changes = mutableListOf<RateChange>()
    fun record(day: LocalDate, value: Double) {
        if (value != rate) {
            changes += RateChange(day, value)
            rate = value
        }
    }
    // 완결된 달은 월말 값으로 훑고, 값이 바뀐 달만 일별로 정확한 날짜를 찾는다
    var month = YearMonth.from(since)
    val thisMonth = YearMonth.from(today)
    while (month < thisMonth) {
        val stop = minOf(month.plusMonths(MAX_ROWS - 1L), thisMonth.minusMonths(1))
        for ((t, v) in fetch(BASE_RATE, "M", month.ym(), stop.ym())) {
            if (v != rate) {
                val m = YearMonth.of(t.take(4).toInt(), t.drop(4).toInt())
                val first = maxOf(m.atDay(1), since.plusDays(1))
                for ((dt, dv) in daily(fetch, BASE_RATE, first, m.atEndOfMonth())) record(parseDay(dt), dv)
            }
        }
        month = stop.plusMonths(1)
    }
    // 이번 달은 월 통계가 아직 없으므로 일별로 확인
    for ((dt, dv) in daily(fetch, BASE_RATE, maxOf(thisMonth.atDay(1), since.plusDays(1)), today)) {
        record(parseDay(dt), dv)
    }
    return changes
}

/** 가장 최근 영업일 값 (연휴를 고려해 최대 40일 전까지). */
fun latestDaily(fetch: Fetch, series: Series, today: LocalDate): Pair<LocalDate, Double> {
    var end = today
    repeat(4) {
        val start = end.minusDays(MAX_ROWS - 1L)
        val rows = fetch(series, "D", start.ymd(), end.ymd())
        if (rows.isNotEmpty()) return parseDay(rows.last().first) to rows.last().second
        end = start.minusDays(1)
    }
    throw RuntimeException("최근 데이터 없음")
}

/** 가장 최근 발표월의 전년동월비(%). */
fun latestYoy(fetch: Fetch, series: Series, today: LocalDate): Pair<String, Double> {
    val now = YearMonth.from(today)
    val rows = fetch(series, "M", now.minusMonths(3).ym(), now.ym())
    if (rows.isEmpty()) throw RuntimeException("최근 데이터 없음")
    val (t, v) = rows.last()
    val m = YearMonth.of(t.take(4).toInt(), t.drop(4).toInt())
    val prev = fetch(series, "M", m.minusYears(1).ym(), m.minusYears(1).ym())
    val yoy = Math.round((v / prev.first().second - 1) * 1000) / 10.0
    return "%04d-%02d".format(m.year, m.monthValue) to yoy
}

/** 스냅샷을 최신 값으로 갱신한 새 스냅샷과 항목별 결과 메시지. 실패한 항목은 기존 값을 유지한다. */
fun updateSnapshot(
    snapshot: Snapshot,
    today: LocalDate = LocalDate.now(),
    fetch: Fetch = httpFetch(),
    onProgress: (String) -> Unit = {},
): Pair<Snapshot, List<String>> {
    var s = snapshot
    val log = mutableListOf<String>()
    fun attempt(label: String, block: () -> String) {
        onProgress("$label 받는 중…")
        log += "$label: " + try { block() } catch (e: Exception) { "실패 (${e.message ?: e.javaClass.simpleName}) → 기존 값 유지" }
    }
    attempt("기준금리") {
        val new = baseRateChanges(fetch, s.history.last(), today)
        s = s.copy(history = s.history + new)
        if (new.isEmpty()) "변경 없음" else new.joinToString { "${it.date} %.2f%%".format(it.rate) } + " 변경"
    }
    attempt("소비자물가") {
        val (m, v) = latestYoy(fetch, CPI, today); s = s.copy(cpiYoy = v, cpiMonth = m); "$v% ($m)"
    }
    attempt("근원물가") {
        val (m, v) = latestYoy(fetch, CORE_CPI, today); s = s.copy(coreCpiYoy = v, coreCpiMonth = m); "$v% ($m)"
    }
    attempt("국고채 3년") { val (d, v) = latestDaily(fetch, KTB_3Y, today); s = s.copy(ktb3y = v); "$v% ($d)" }
    attempt("국고채 10년") { val (d, v) = latestDaily(fetch, KTB_10Y, today); s = s.copy(ktb10y = v); "$v% ($d)" }
    attempt("원/달러") { val (d, v) = latestDaily(fetch, USDKRW, today); s = s.copy(usdkrw = v); "%,.1f원 ($d)".format(v) }
    return s.copy(asOf = today) to log
}
