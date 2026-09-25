package com.doheon.kostolany

import com.doheon.kostolany.data.BASE_RATE
import com.doheon.kostolany.data.CORE_CPI
import com.doheon.kostolany.data.CPI
import com.doheon.kostolany.data.Fetch
import com.doheon.kostolany.data.MAX_ROWS
import com.doheon.kostolany.data.updateSnapshot
import com.doheon.kostolany.model.ASSETS
import com.doheon.kostolany.model.RateChange
import com.doheon.kostolany.model.Risk
import com.doheon.kostolany.model.Snapshot
import com.doheon.kostolany.model.analyze
import com.doheon.kostolany.model.heading
import com.doheon.kostolany.model.runScenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 파이썬 모형(kostolany/model.py)과 같은 결과를 내는지 확인한다. 기대값은 같은 snapshot.json으로 파이썬에서 계산한 값. */
class ModelParityTest {
    private val snapshot = Snapshot.fromJson(File("../../data/snapshot.json").readText())
    private fun alloc(vararg w: Int) = ASSETS.zip(w.toList()).toMap()

    @Test fun currentPhaseMatchesPython() {
        val a = analyze(snapshot)
        assertEquals("B1", a.phase.code)
        assertEquals(4.9, a.targetRate, 1e-9)
        assertEquals(3.625, a.position, 1e-9)
        assertEquals(0.6499, a.probabilities.getValue("B1"), 1e-4)
        assertEquals(0.2293, a.probabilities.getValue("B2"), 1e-4)
        assertEquals("B2", heading(a).first.code)
    }

    @Test fun allocationsMatchPython() {
        assertEquals(alloc(35, 15, 25, 10, 15), analyze(snapshot, Risk.CONSERVATIVE).allocation)
        assertEquals(alloc(25, 10, 35, 15, 15), analyze(snapshot, Risk.BALANCED).allocation)
        assertEquals(alloc(15, 10, 45, 15, 15), analyze(snapshot, Risk.AGGRESSIVE).allocation)
    }

    @Test fun scenariosMatchPython() {
        val r = runScenarios(snapshot, Risk.BALANCED).map { it.second }
        assertEquals(listOf("B2", "B3", "A1", "B2"), r.map { it.phase.code })
        assertEquals(alloc(35, 10, 25, 10, 20), r[0].allocation)
        assertEquals(alloc(35, 20, 20, 10, 15), r[1].allocation)
        assertEquals(alloc(20, 40, 20, 10, 10), r[2].allocation)
        assertEquals(alloc(30, 10, 30, 10, 20), r[3].allocation)
    }

    @Test fun jsonRoundTripKeepsExtraKeys() {
        val back = Snapshot.fromJson(snapshot.toJson())
        assertEquals(snapshot.copy(raw = ""), back.copy(raw = ""))
        assertTrue(back.toJson().contains("sources"))
    }

    @Test fun updateFindsMissedRateChanges() {
        val changes = listOf(LocalDate.of(2026, 7, 16) to 2.75, LocalDate.of(2026, 8, 27) to 3.0)
        val old = snapshot.copy(history = listOf(RateChange(LocalDate.of(2025, 5, 29), 2.5)))
        val (new, log) = updateSnapshot(old, LocalDate.of(2026, 9, 25), fakeFetch(changes))
        assertEquals(changes.map { RateChange(it.first, it.second) }, new.history.takeLast(2))
        assertEquals(3.1, new.cpiYoy!!, 1e-9)
        assertEquals(3.4, new.coreCpiYoy!!, 1e-9)
        assertTrue(log.first().contains("2026-07-16"))
    }

    private fun fakeFetch(changes: List<Pair<LocalDate, Double>>): Fetch {
        val ymd = DateTimeFormatter.BASIC_ISO_DATE
        fun rateOn(d: LocalDate) = changes.lastOrNull { !d.isBefore(it.first) }?.second ?: 2.5
        val cpi = mapOf(CPI to mapOf("202508" to 116.45, "202608" to 120.05),
            CORE_CPI to mapOf("202508" to 112.84, "202608" to 116.64))
        return { series, cycle, start, end ->
            val rows = when {
                series == BASE_RATE && cycle == "M" -> generateSequence(java.time.YearMonth.parse(start, DateTimeFormatter.ofPattern("yyyyMM"))) { it.plusMonths(1) }
                    .takeWhile { "%04d%02d".format(it.year, it.monthValue) <= end }
                    .map { "%04d%02d".format(it.year, it.monthValue) to rateOn(it.atEndOfMonth()) }.toList()
                series == BASE_RATE -> generateSequence(LocalDate.parse(start, ymd)) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(LocalDate.parse(end, ymd)) }.map { it.format(ymd) to rateOn(it) }.toList()
                series in cpi -> cpi.getValue(series).filterKeys { it in start..end }.toList().sortedBy { it.first }
                else -> if ("20260924" in start..end) listOf("20260924" to 1.0) else emptyList()
            }
            assertTrue("sample 키 10건 제한 초과", rows.size <= MAX_ROWS)
            rows
        }
    }
}
