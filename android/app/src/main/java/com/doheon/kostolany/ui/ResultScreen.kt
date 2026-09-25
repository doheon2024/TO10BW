package com.doheon.kostolany.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doheon.kostolany.data.UpdateResult
import com.doheon.kostolany.model.ASSETS
import com.doheon.kostolany.model.Analysis
import com.doheon.kostolany.model.PHASES
import com.doheon.kostolany.model.Risk
import com.doheon.kostolany.model.Scenario
import com.doheon.kostolany.model.Snapshot
import com.doheon.kostolany.model.analyze
import com.doheon.kostolany.model.heading
import com.doheon.kostolany.model.runScenarios
import kotlinx.coroutines.launch

private val TABS = listOf("달걀 위치", "진단", "자산배분", "시나리오")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(snapshot: Snapshot, update: UpdateResult?, risk: Risk, onRisk: (Risk) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val a = remember(snapshot, risk) { analyze(snapshot, risk) }
    val scenarios = remember(snapshot, risk) { runScenarios(snapshot, risk) }
    val pager = rememberPagerState { TABS.size }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = { Text("기준일 ${snapshot.asOf} · ${risk.label}", fontSize = 17.sp) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "처음 화면") }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Beige),
                )
                PrimaryTabRow(
                    selectedTabIndex = pager.currentPage,
                    containerColor = Beige,
                ) {
                    TABS.forEachIndexed { i, title ->
                        Tab(
                            selected = pager.currentPage == i,
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            text = { Text(title, fontWeight = if (pager.currentPage == i) FontWeight.Bold else FontWeight.Normal) },
                        )
                    }
                }
            }
        },
        containerColor = Beige,
    ) { inner ->
        HorizontalPager(pager, Modifier.fillMaxSize().padding(inner), beyondViewportPageCount = 1) { page ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (page) {
                    0 -> EggPage(a, update, scenarios)
                    1 -> DiagnosisPage(snapshot, a)
                    2 -> AllocationPage(a, risk, onRisk)
                    else -> ScenarioPage(a, scenarios)
                }
                PageHint(page)
            }
        }
    }
}

@Composable
private fun PageHint(page: Int) {
    val text = if (page < TABS.lastIndex) "옆으로 밀면 ‘${TABS[page + 1]}’ →" else "교육·참고용 모형입니다. 투자 판단과 책임은 본인에게 있습니다."
    Text(text, Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Muted, fontSize = 12.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

// ── 1. 달걀 위치 ────────────────────────────────────────────────

@Composable
private fun EggPage(a: Analysis, update: UpdateResult?, scenarios: List<Pair<Scenario, Analysis>>) {
    update?.let { UpdateCard(it) }
    Section("달걀 모형 위치") {
        EggChart(a, scenarios.map { it.second.position })
        Legend()
    }
    val (next, speed) = heading(a)
    Section("지금 할 일 · ${a.phase.code} ${a.phase.name}") {
        Text(a.phase.rateView, color = Muted, fontSize = 14.sp)
        Text(a.phase.action, fontSize = 15.sp)
        KeyValue("다음 국면", "${next.code} ${next.name}", NowOrange)
        KeyValue("이동 속도", speed)
    }
}

@Composable
private fun Legend() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(NowOrange); Text(" 현재 위치   ", fontSize = 12.sp)
            Text("- - ▶", color = NowOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(" 향하는 방향", fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(EggGreen); Text(" 금리 하락기(A)   ", fontSize = 12.sp)
            Dot(EggNavy); Text(" 금리 상승기(B)", fontSize = 12.sp)
        }
        Text("숫자 원 = 시나리오 도착 국면(‘시나리오’ 탭) · 궤도가 진할수록 확률이 높음", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}

@Composable
private fun UpdateCard(u: UpdateResult) {
    Section(if (u.allFailed) "업데이트 실패" else "업데이트 결과") {
        if (u.allFailed) {
            Text("한국은행 서버에 연결하지 못해 저장된 정보로 보여 드립니다. 인터넷 연결을 확인해 주세요.",
                color = DownColor, fontSize = 14.sp)
            return@Section
        }
        u.rows.forEach { r ->
            Row(Modifier.fillMaxWidth()) {
                Text(r.label, Modifier.width(84.dp), color = Muted, fontSize = 13.sp)
                Text("${r.before} → ${r.after}", Modifier.weight(1f), fontSize = 13.sp)
                Text(r.change, fontSize = 13.sp, color = if (r.change == "변동 없음") Muted else NowOrange)
            }
        }
        Spacer(Modifier.height(4.dp))
        if (u.phaseChanged) {
            Text("★ 달걀 위치 변경: ${u.before.phase.code} ${u.before.phase.name} → ${u.after.phase.code} ${u.after.phase.name}",
                color = NowOrange, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        } else {
            Text("달걀 위치 ${u.after.phase.code} 유지 (확률 %.0f%% → %.0f%%)".format(
                u.before.phaseProbability * 100, u.after.phaseProbability * 100), fontSize = 14.sp)
        }
        Text("자산배분 조정: " + (u.allocationMoves.joinToString { "${it.first} %+d%%p".format(it.second) }.ifEmpty { "변경 없음" }),
            fontSize = 14.sp)
        u.log.filter { "실패" in it }.forEach { Text(it, color = DownColor, fontSize = 12.sp) }
        Text("성장률·통화정책 기조·집값은 자동으로 바뀌지 않습니다.", color = Muted, fontSize = 12.sp)
    }
}

// ── 2. 진단 ─────────────────────────────────────────────────────

@Composable
private fun DiagnosisPage(s: Snapshot, a: Analysis) {
    Section("주요 지표") {
        KeyValue("기준금리", "%.2f%%".format(a.rate))
        KeyValue("소비자물가(전년비)", s.cpiYoy?.let { "$it%" } ?: "-")
        KeyValue("근원물가(전년비)", s.coreCpiYoy?.let { "$it%" } ?: "-")
        KeyValue("성장률(전망)", s.gdpGrowth?.let { "$it%" } ?: "-")
        KeyValue("국고채 3년 / 10년", "${s.ktb3y?.let { "%.2f%%".format(it) } ?: "-"} / ${s.ktb10y?.let { "%.2f%%".format(it) } ?: "-"}")
        KeyValue("원/달러", s.usdkrw?.let { "%,.1f원".format(it) } ?: "-")
        KeyValue("집값 / 가계부채", "${trend(s.housePriceTrend)} / ${trend(s.householdDebtTrend)}")
        KeyValue("통화정책 기조", when (s.policyGuidance) { "hawkish" -> "매파(긴축)"; "dovish" -> "비둘기파(완화)"; else -> "중립" })
    }
    Section("금리 사이클") {
        val turn = if (a.regime.name == "HIKING") "바닥" else "정점"
        Text("${a.regime.label} · $turn %.2f%%에서 누적 ${a.streakBp}bp${if (a.holding) " · 최근 동결 중" else ""}".format(a.turnRate), fontSize = 14.sp)
        BarRow("사이클 진행률", a.progress * 100, NowOrange)
    }
    Section("적정 기준금리 ≈ %.2f%%".format(a.targetRate)) {
        a.targetParts.forEach { (k, v) ->
            KeyValue(k, if (k == "중립금리") "%.2f".format(v) else "%+.2f".format(v))
        }
        a.notes.forEach { Text("※ $it", color = NowOrange, fontSize = 13.sp) }
    }
    Section("국면별 확률") {
        PHASES.forEach { p -> BarRow("${p.code} ${p.name}", a.probabilities.getValue(p.code) * 100, phaseColor(p.isA)) }
    }
}

private fun trend(t: String?) = when (t) { "rising" -> "상승"; "falling" -> "하락"; "flat" -> "보합"; else -> "-" }

// ── 3. 자산배분 ─────────────────────────────────────────────────

@Composable
private fun AllocationPage(a: Analysis, risk: Risk, onRisk: (Risk) -> Unit) {
    Section("투자 성향") { RiskSelector(risk, onRisk) }
    Section("추천 자산배분 (${risk.label})") {
        ASSETS.forEach { BarRow(it, a.allocation.getValue(it).toDouble(), EggGreen) }
        Text("국면별 기본 배분을 국면 확률로 가중 평균한 뒤 투자 성향을 반영했습니다.", color = Muted, fontSize = 12.sp)
    }
    Section("${a.phase.code} ${a.phase.name}에서의 행동") {
        Text(a.phase.action, fontSize = 15.sp)
    }
    Section("다음 국면으로 넘어가는 신호") {
        Text(a.phase.nextSignal, fontSize = 15.sp)
    }
}

// ── 4. 시나리오 ─────────────────────────────────────────────────

@Composable
private fun ScenarioPage(a: Analysis, scenarios: List<Pair<Scenario, Analysis>>) {
    Text("이런 변화가 생기면 이렇게 바꾸세요", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(PaddingValues(horizontal = 4.dp)))
    scenarios.forEachIndexed { n, (sc, r) ->
        Section("${n + 1}. ${sc.name} → ${r.phase.code} ${r.phase.name}") {
            Text("신호: ${sc.trigger}", color = Muted, fontSize = 13.sp)
            val diffs = ASSETS.map { it to r.allocation.getValue(it) - a.allocation.getValue(it) }
            val ups = diffs.filter { it.second > 0 }.sortedByDescending { it.second }
            val downs = diffs.filter { it.second < 0 }.sortedBy { it.second }
            Text("▲ 늘리기: " + ups.joinToString { "${it.first} +${it.second}%p" }.ifEmpty { "없음" }, color = UpColor, fontSize = 14.sp)
            Text("▼ 줄이기: " + downs.joinToString { "${it.first} ${it.second}%p" }.ifEmpty { "없음" }, color = DownColor, fontSize = 14.sp)
            Text("목표 배분: " + ASSETS.joinToString(" / ") { "$it ${r.allocation.getValue(it)}%" }, fontSize = 13.sp)
        }
    }
}
