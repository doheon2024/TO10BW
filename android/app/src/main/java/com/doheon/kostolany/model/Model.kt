package com.doheon.kostolany.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp

/*
 * 코스톨라니 달걀 모형 분석 엔진 (kostolany/model.py 이식).
 * 달걀을 금리 사이클 위의 원형 좌표(0~6)로 보고, 금리 경로와 테일러 준칙식 적정금리를 비교해 위치를 추정한다.
 *   0 A1 금리 정점   1 A2 금리 하락   2 A3 금리 바닥권
 *   3 B1 인상 개시   4 B2 인상 진행   5 B3 금리 정점 근접
 */

val ASSETS = listOf("예금·단기채", "장기채권", "주식", "부동산·리츠", "금·달러·원자재")

data class Phase(
    val code: String,
    val name: String,
    val rateView: String,
    val action: String,
    val shortAction: String,
    val nextSignal: String,
    val allocation: Map<String, Int>,
) {
    val isA get() = code.startsWith("A")
}

private fun alloc(vararg w: Int) = ASSETS.zip(w.toList()).toMap()

val PHASES = listOf(
    Phase("A1", "금리 정점", "금리가 고점에서 머물고 인하 기대가 생기는 구간",
        "예금을 빼서 장기채권을 산다. 주식은 공포 속에서 소량 분할 매수", "예금 → 장기채권",
        "첫 인하 단행 또는 인하 소수의견 등장, 물가 2%대 초반 안착", alloc(20, 45, 20, 5, 10)),
    Phase("A2", "금리 하락", "인하가 진행 중인 구간",
        "채권 평가이익을 누리며 주식·부동산을 분할 매수한다", "채권 보유, 주식·부동산 분할매수",
        "인하 폭 축소, 경기선행지수 반등, 신용스프레드 축소", alloc(10, 35, 30, 15, 10)),
    Phase("A3", "금리 바닥권", "인하가 막바지이거나 저금리가 유지되는 구간",
        "채권 차익을 실현하고 주식 비중을 최대로 가져간다", "채권 차익실현, 주식 비중 최대",
        "인하 사이클 종료 선언, 물가 재상승, 집값 급등", alloc(10, 15, 45, 20, 10)),
    Phase("B1", "인상 개시", "금리가 바닥을 벗어나 오르기 시작한 구간 (자산시장 과열)",
        "주식·부동산 차익실현을 시작하고 예금·단기채와 실물자산을 늘린다", "주식·부동산 차익실현 시작",
        "연속 인상, 국고채 3년물이 기준금리를 크게 상회, 물가 목표 상회 지속", alloc(20, 10, 40, 15, 15)),
    Phase("B2", "인상 진행", "인상이 이어지고 긴축이 본격화되는 구간",
        "주식·부동산을 줄이고 예금·단기채와 인플레이션 헤지 자산을 늘린다", "예금·실물자산 확대",
        "인상 속도 둔화·동결, 물가 정점 통과, 수출·소비 둔화", alloc(35, 10, 25, 10, 20)),
    Phase("B3", "금리 정점 근접", "인상이 막바지이거나 멈춘 고금리 구간",
        "고금리 예금에 머물면서 장기채권 매수를 준비한다", "예금 최대, 장기채 매수 준비",
        "연속 동결, 경기 둔화 신호, 시장금리 하락 반전", alloc(40, 25, 15, 5, 15)),
)

enum class Risk(val label: String, val tilt: Map<String, Int>) {
    CONSERVATIVE("안정형", mapOf("주식" to -10, "부동산·리츠" to -5, "예금·단기채" to 10, "장기채권" to 5)),
    BALANCED("중립형", emptyMap()),
    AGGRESSIVE("공격형", mapOf("주식" to 10, "예금·단기채" to -7, "장기채권" to -3)),
}

enum class Regime(val label: String) { HIKING("인상 사이클"), CUTTING("인하 사이클"), FLAT("변동 없음") }

const val HOLD_MONTHS = 4.0   // 마지막 금리 변경 후 이 기간이 지나면 '동결 국면'
const val SIGMA = 0.6         // 위치 추정의 불확실성

data class Analysis(
    val rate: Double,
    val targetRate: Double,
    val targetParts: List<Pair<String, Double>>,
    val regime: Regime,
    val holding: Boolean,
    val streakBp: Int,
    val turnRate: Double,
    val progress: Double,
    val position: Double,
    val probabilities: Map<String, Double>,
    val allocation: Map<String, Int>,
    val notes: List<String>,
) {
    val phase: Phase get() = phaseAt(position)
    val phaseProbability: Double get() = probabilities.getValue(phase.code)
}

fun phaseAt(position: Double): Phase = PHASES[position.mod(6.0).toInt()]

/** 달걀은 한 방향으로만 돈다. 다음 국면과 그쪽으로 가는 속도. */
fun heading(a: Analysis): Pair<Phase, String> {
    val next = phaseAt(a.position + 1)
    val gap = abs(a.targetRate - a.rate)
    val speed = when {
        a.holding -> "동결 중이라 이동이 느림"
        gap >= 0.75 -> "적정금리와 격차가 커서 빠르게 이동 중"
        else -> "완만하게 이동 중"
    }
    return next to speed
}

private fun clip(x: Double, lo: Double, hi: Double) = x.coerceIn(lo, hi)

/** 테일러 준칙을 단순화한 '경제 여건상 적정 기준금리'. */
fun targetRate(s: Snapshot): Pair<Double, List<Pair<String, Double>>> {
    val parts = mutableListOf("중립금리" to s.neutralRate)
    val cpi = listOfNotNull(s.cpiYoy, s.coreCpiYoy)
    if (cpi.isNotEmpty()) parts += "물가 갭" to 0.5 * clip(cpi.average() - s.inflationTarget, -3.0, 3.0)
    s.gdpGrowth?.let { parts += "성장 갭" to 0.5 * clip(it - s.potentialGrowth, -3.0, 3.0) }
    parts += "통화정책 기조" to when (s.policyGuidance) { "hawkish" -> 0.25; "dovish" -> -0.25; else -> 0.0 }
    var fin = when (s.housePriceTrend) { "rising" -> 0.25; "falling" -> -0.25; else -> 0.0 }
    fin += when (s.householdDebtTrend) { "rising" -> 0.125; "falling" -> -0.125; else -> 0.0 }
    s.usdkrw?.let { fin += if (it >= 1450) 0.25 else if (it <= 1250) -0.125 else 0.0 }
    parts += "금융안정(집값·가계부채·환율)" to fin
    // 국고채 3년물이 기준금리보다 높으면 시장이 추가 인상을 반영 중
    s.ktb3y?.let { parts += "시장 기대(3년물-기준금리)" to 0.5 * clip(it - s.currentRate, -1.0, 1.0) }
    return parts.sumOf { it.second } to parts
}

private data class Streak(val regime: Regime, val bp: Int, val turn: Double, val lastChange: LocalDate)

/** 마지막 금리 변경 방향으로 연속된 변경의 합(bp)과 사이클 시작 금리. */
private fun streak(history: List<RateChange>): Streak {
    data class Change(val diff: Int, val before: Double, val date: LocalDate)
    val changes = history.zipWithNext().mapNotNull { (prev, cur) ->
        val diff = Math.round((cur.rate - prev.rate) * 100).toInt()
        if (diff != 0) Change(diff, prev.rate, cur.date) else null
    }
    if (changes.isEmpty()) return Streak(Regime.FLAT, 0, history.last().rate, history.last().date)
    val up = changes.last().diff > 0
    var total = 0
    var turn = changes.last().before
    for (c in changes.asReversed()) {
        if ((c.diff > 0) != up) break
        total += c.diff
        turn = c.before
    }
    return Streak(if (up) Regime.HIKING else Regime.CUTTING, abs(total), turn, changes.last().date)
}

fun analyze(snapshot: Snapshot, risk: Risk = Risk.BALANCED): Analysis {
    val s = snapshot.copy(history = snapshot.history.sortedBy { it.date })
    val r = s.currentRate
    val (tr, parts) = targetRate(s)
    val st = streak(s.history)
    val holding = ChronoUnit.DAYS.between(st.lastChange, s.asOf) / 30.44 >= HOLD_MONTHS
    val notes = mutableListOf<String>()
    val progress: Double
    var position: Double

    when (st.regime) {
        Regime.HIKING -> {
            val span = tr - st.turn
            progress = if (span > 0) clip((r - st.turn) / span, 0.0, 1.0) else 1.0
            position = 3 + 3 * progress
            if (holding && tr < r - 0.25) {
                position = 6 + clip((r - tr) / 1.0, 0.0, 1.0) * 0.9  // 정점 통과 → A1
                notes += "인상이 멈췄고 적정금리가 현재보다 낮아 인하 전환(A1)이 가까워 보입니다."
            }
        }
        Regime.CUTTING -> {
            val span = st.turn - tr
            progress = if (span > 0) clip((st.turn - r) / span, 0.0, 1.0) else 1.0
            position = 3 * progress
            if (holding && tr > r + 0.25) {
                position = 3 + clip((tr - r) / 1.0, 0.0, 1.0) * 0.9  // 바닥 통과 → B1
                notes += "인하가 멈췄고 적정금리가 현재보다 높아 인상 전환(B1)이 가까워 보입니다."
            }
        }
        Regime.FLAT -> {
            progress = 0.5
            position = if (tr > r) 3.5 else 0.5
        }
    }
    if (abs(tr - r) >= 0.75) {
        val direction = if (tr > r) "인상" else "인하"
        notes += "적정금리(%.2f%%)와 현재 기준금리(%.2f%%) 차이가 커서 추가 %s 여지가 큽니다.".format(tr, r, direction)
    }
    val probs = probabilities(position)
    return Analysis(r, tr, parts, st.regime, holding, st.bp, st.turn, progress,
        position.mod(6.0), probs, blendAllocation(probs, risk), notes)
}

private fun probabilities(position: Double): Map<String, Double> {
    val weights = PHASES.mapIndexed { i, p ->
        val d = abs((position - (i + 0.5) + 3).mod(6.0) - 3)  // 원형 거리
        p.code to exp(-(d * d) / (2 * SIGMA * SIGMA))
    }
    val total = weights.sumOf { it.second }
    return weights.associate { (k, v) -> k to v / total }
}

fun blendAllocation(probs: Map<String, Double>, risk: Risk = Risk.BALANCED): Map<String, Int> {
    val a = ASSETS.associateWith { 0.0 }.toMutableMap()
    for (p in PHASES) for ((asset, w) in p.allocation) a[asset] = a.getValue(asset) + probs.getValue(p.code) * w
    for ((asset, t) in risk.tilt) a[asset] = maxOf(0.0, a.getValue(asset) + t)
    val total = a.values.sum()
    return roundTo100(ASSETS.associateWith { a.getValue(it) * 100 / total })
}

/** 5% 단위로 반올림하면서 합계 100%를 유지한다. */
private fun roundTo100(a: Map<String, Double>): Map<String, Int> {
    val units = ASSETS.associateWith { (a.getValue(it) / 5).toInt() }.toMutableMap()
    val rest = ASSETS.sortedByDescending { a.getValue(it) / 5 - units.getValue(it) }
    for (asset in rest.take(20 - units.values.sum())) units[asset] = units.getValue(asset) + 1
    return ASSETS.associateWith { units.getValue(it) * 5 }
}
