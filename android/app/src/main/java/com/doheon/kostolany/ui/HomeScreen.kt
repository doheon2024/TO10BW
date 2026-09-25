package com.doheon.kostolany.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doheon.kostolany.R
import com.doheon.kostolany.model.Risk
import com.doheon.kostolany.model.Snapshot

@Composable
fun HomeScreen(snapshot: Snapshot, risk: Risk, onRisk: (Risk) -> Unit, onShow: () -> Unit, onUpdate: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(painterResource(R.drawable.ic_egg), contentDescription = null, Modifier.size(width = 112.dp, height = 140.dp))
        Text("코스톨라니 달걀", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("금리·물가로 보는 한국 경기 위치와 자산배분", color = Muted, textAlign = TextAlign.Center)

        Spacer(Modifier.height(8.dp))
        Section("저장된 정보") {
            KeyValue("기준일", snapshot.asOf.toString())
            KeyValue("기준금리", "%.2f%%".format(snapshot.currentRate))
            snapshot.cpiYoy?.let { KeyValue("소비자물가", "$it%" + (snapshot.cpiMonth?.let { m -> " ($m)" } ?: "")) }
        }

        Section("투자 성향") { RiskSelector(risk, onRisk) }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onShow,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = EggNavy),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("저장된 정보로 바로 보기", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("기준일 ${snapshot.asOf}", fontSize = 12.sp)
            }
        }
        OutlinedButton(onClick = onUpdate, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("최신 정보로 업데이트 후 보기", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = EggGreen)
                Text("한국은행에서 금리·물가·환율을 받아옵니다", fontSize = 12.sp, color = Muted)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("교육·참고용 모형입니다. 투자 판단과 책임은 본인에게 있습니다.",
            style = MaterialTheme.typography.bodySmall, color = Muted, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiskSelector(risk: Risk, onRisk: (Risk) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Risk.entries.forEachIndexed { i, r ->
            SegmentedButton(
                selected = r == risk,
                onClick = { onRisk(r) },
                shape = SegmentedButtonDefaults.itemShape(i, Risk.entries.size),
            ) { Text(r.label) }
        }
    }
}

@Composable
fun UpdatingScreen(progress: List<String>) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = EggGreen)
        Spacer(Modifier.height(24.dp))
        Text("최신 지표를 받는 중", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        progress.takeLast(6).forEach { Text(it, color = Muted, fontSize = 14.sp) }
    }
}
