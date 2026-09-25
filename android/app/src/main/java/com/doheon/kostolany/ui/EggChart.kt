package com.doheon.kostolany.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.doheon.kostolany.model.Analysis
import com.doheon.kostolany.model.PHASES
import com.doheon.kostolany.model.heading
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// 설계 좌표계 (화면 폭에 맞게 통째로 확대/축소한다)
private const val W = 600f
private const val H = 660f
private const val CX = 300f
private const val CY = 335f
private const val RX = 165f
private const val RY = 235f

/** 위치 0(꼭대기, 금리 정점)에서 왼쪽(하락)→바닥→오른쪽(상승)으로 반시계 방향으로 돈다. */
private fun pt(pos: Double, scale: Float = 1f): Offset {
    val th = Math.toRadians(90 + pos * 60)
    val s = sin(th).toFloat()
    val x = CX + RX * scale * cos(th).toFloat() * (1 - 0.12f * s)  // 위가 좁은 달걀 모양
    val y = CY - RY * scale * s
    return Offset(x, y)
}

private fun arcPath(p0: Double, p1: Double, scale: Float = 1f, n: Int = 48) = Path().apply {
    for (i in 0..n) {
        val p = pt(p0 + (p1 - p0) * i / n, scale)
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
}

private enum class Anchor { START, MIDDLE, END }

@Composable
fun EggChart(a: Analysis, scenarioPositions: List<Double>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val (next, speed) = heading(a)
    val cur = a.phase
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(W / H)
            .semantics { contentDescription = "달걀 모형: 현재 ${cur.code} ${cur.name}, ${next.code} 방향으로 이동 중" }
    ) {
        val k = size.width / W
        scale(k, pivot = Offset.Zero) {
            val label = Labeler(this, measurer, density)

            label.draw("▲ 금리 정점", CX, CY - RY * 1.1f - 14, 17f, Muted, Anchor.MIDDLE)
            label.draw("▼ 금리 바닥", CX, CY + RY * 1.1f + 24, 17f, Muted, Anchor.MIDDLE)

            // 국면별 궤도: 확률이 높을수록 진하게
            PHASES.forEachIndexed { i, p ->
                val prob = a.probabilities.getValue(p.code)
                val color = phaseColor(p.isA)
                drawPath(arcPath(i + 0.03, i + 0.97), color.copy(alpha = (0.18 + 0.82 * prob).toFloat()),
                    style = Stroke(width = 18f, cap = StrokeCap.Round))
                val l = pt(i + 0.5, 1.2f)
                val anchor = if (l.x < CX) Anchor.END else Anchor.START
                val bold = p == cur
                label.draw("${p.code} · ${"%.0f".format(prob * 100)}%", l.x, l.y - 11, 20f, color, anchor, bold)
                label.draw(p.name, l.x, l.y + 13, 17f, if (bold) Ink else Muted, anchor, bold)
            }
            for (b in listOf(0.0, 3.0)) {  // A/B 경계
                drawLine(Ink, pt(b, 0.9f), pt(b, 1.1f), strokeWidth = 2.5f)
            }

            // 진행 방향 화살표
            val start = a.position + 0.12
            val end = a.position + 1.0
            drawPath(arcPath(start, end, 0.84f), NowOrange,
                style = Stroke(width = 6f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f))))
            val tip = pt(end, 0.84f)
            val before = pt(end - 0.03, 0.84f)
            val ang = atan2(tip.y - before.y, tip.x - before.x)
            drawPath(Path().apply {
                moveTo(tip.x + 14 * cos(ang), tip.y + 14 * sin(ang))
                lineTo(tip.x + 13 * cos(ang + 2.3f), tip.y + 13 * sin(ang + 2.3f))
                lineTo(tip.x + 13 * cos(ang - 2.3f), tip.y + 13 * sin(ang - 2.3f))
                close()
            }, NowOrange)

            // 시나리오 도착 지점 (겹치면 안쪽으로 비켜 둔다)
            val placed = mutableListOf<Double>()
            scenarioPositions.forEachIndexed { n, pos ->
                var sc = 1f
                for (q in placed) if (abs((pos - q + 3).mod(6.0) - 3) < 0.25) sc -= 0.13f
                placed += pos
                val c = pt(pos, sc)
                drawCircle(Color.White, 15f, c)
                drawCircle(Ink, 15f, c, style = Stroke(2f))
                label.draw("${n + 1}", c.x, c.y, 17f, Ink, Anchor.MIDDLE, true)
            }

            // 현재 위치
            val now = pt(a.position)
            drawCircle(NowOrange.copy(alpha = 0.22f), 28f, now)
            drawCircle(Color.White, 17f, now)
            drawCircle(NowOrange, 13f, now)

            // 가운데 요약
            label.draw("지금", CX, CY - 70, 18f, Muted, Anchor.MIDDLE)
            label.draw(cur.code, CX, CY - 28, 60f, NowOrange, Anchor.MIDDLE, true)
            label.draw("${cur.name} (${"%.0f".format(a.phaseProbability * 100)}%)", CX, CY + 18, 21f, Ink, Anchor.MIDDLE, true)
            label.draw("→ ${next.code} ${next.name} 방향", CX, CY + 54, 19f, NowOrange, Anchor.MIDDLE, true)
            label.draw(speed, CX, CY + 80, 14f, Muted, Anchor.MIDDLE)
        }
    }
}

/** 설계 좌표계 단위로 글자 크기를 지정해 그린다 (y는 글자 줄의 세로 가운데). */
private class Labeler(val scope: DrawScope, val measurer: TextMeasurer, val density: Density) {
    fun draw(text: String, x: Float, y: Float, size: Float, color: Color, anchor: Anchor = Anchor.START, bold: Boolean = false) {
        // scale(k) 안에서 그리므로 픽셀 크기 = size 가 되도록 sp로 환산
        val sp = (size / (density.density * density.fontScale)).sp
        val layout = measurer.measure(text, TextStyle(fontSize = sp, color = color,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
        val w = layout.size.width
        val left = when (anchor) { Anchor.START -> x; Anchor.MIDDLE -> x - w / 2f; Anchor.END -> x - w }
        scope.drawText(layout, topLeft = Offset(left, y - layout.size.height / 2f))
    }
}
