package com.doheon.kostolany.model

/** 앞으로 일어날 수 있는 변화를 입력값 변경으로 표현하고 모형을 다시 돌린다 (kostolany/scenarios.py 이식). */
data class Scenario(val name: String, val trigger: String, val apply: (Snapshot) -> Snapshot)

private fun Snapshot.after(days: Long) = asOf.plusDays(days)

private fun Snapshot.move(bp: Int, days: Long): Snapshot {
    val change = RateChange(after(days), Math.round((currentRate + bp / 100.0) * 100) / 100.0)
    return copy(history = history + change, asOf = after(days + 1))
}

val SCENARIOS = listOf(
    Scenario("추가 인상 지속", "10·11월 금통위에서 연속 인상, 물가 3%대 유지, 매파적 가이던스") {
        it.move(25, 35).move(25, 45).copy(cpiYoy = 3.2, coreCpiYoy = 3.0)
    },
    Scenario("인상 종료·물가 안정", "2회 이상 연속 동결, 물가 2%대 초반 안착, 집값·가계부채 둔화") {
        it.copy(asOf = it.after(150), cpiYoy = 2.2, coreCpiYoy = 2.2, gdpGrowth = 2.2, policyGuidance = "neutral",
            housePriceTrend = "flat", householdDebtTrend = "flat")
    },
    Scenario("경기 급랭·인하 전환", "반도체 수출 꺾임, 성장률 1%대, 첫 금리 인하") {
        it.copy(asOf = it.after(120)).move(-25, 20).copy(cpiYoy = 1.8, coreCpiYoy = 1.9, gdpGrowth = 1.2,
            policyGuidance = "dovish", housePriceTrend = "falling", householdDebtTrend = "flat")
    },
    Scenario("스태그플레이션", "원/달러 1,500원 돌파, 물가 4%, 성장 둔화 속 인상") {
        it.move(25, 35).copy(cpiYoy = 4.0, coreCpiYoy = 3.6, gdpGrowth = 1.5, usdkrw = 1500.0)
    },
)

fun runScenarios(snapshot: Snapshot, risk: Risk): List<Pair<Scenario, Analysis>> =
    SCENARIOS.map { it to analyze(it.apply(snapshot), risk) }
