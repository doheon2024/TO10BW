package com.doheon.kostolany.data

import android.content.Context
import com.doheon.kostolany.model.ASSETS
import com.doheon.kostolany.model.Analysis
import com.doheon.kostolany.model.Risk
import com.doheon.kostolany.model.Snapshot
import com.doheon.kostolany.model.analyze
import java.io.File
import java.time.LocalDate

/** 앱 내부 저장소의 snapshot.json을 읽고 쓴다. 처음에는 앱에 내장된 값을 쓴다. */
class SnapshotRepository(private val context: Context) {
    private val file get() = File(context.filesDir, "snapshot.json")

    fun load(): Snapshot {
        val text = if (file.exists()) file.readText() else
            context.assets.open("snapshot.json").bufferedReader().use { it.readText() }
        return Snapshot.fromJson(text)
    }

    private fun save(s: Snapshot) {
        if (file.exists()) file.copyTo(File(context.filesDir, "snapshot.json.bak"), overwrite = true)
        file.writeText(s.toJson())
    }

    /** 요청이 있을 때만 호출된다. 최신 값을 받아 저장하고 변화 요약을 돌려준다. */
    fun update(risk: Risk, onProgress: (String) -> Unit): UpdateResult {
        val old = load()
        val (new, log) = updateSnapshot(old, LocalDate.now(), httpFetch(), onProgress)
        val allFailed = log.all { "실패" in it }
        if (!allFailed) save(new)
        return UpdateResult(old, if (allFailed) old else new, log, allFailed, risk)
    }
}

data class DiffRow(val label: String, val before: String, val after: String, val change: String)

class UpdateResult(
    val old: Snapshot,
    val new: Snapshot,
    val log: List<String>,
    val allFailed: Boolean,
    risk: Risk,
) {
    val before: Analysis = analyze(old, risk)
    val after: Analysis = analyze(new, risk)
    val phaseChanged get() = before.phase != after.phase

    val rows: List<DiffRow> = listOf(
        Triple("기준금리", old.currentRate, new.currentRate) to "%",
        Triple("소비자물가", old.cpiYoy, new.cpiYoy) to "%",
        Triple("근원물가", old.coreCpiYoy, new.coreCpiYoy) to "%",
        Triple("국고채 3년", old.ktb3y, new.ktb3y) to "%",
        Triple("국고채 10년", old.ktb10y, new.ktb10y) to "%",
        Triple("원/달러", old.usdkrw, new.usdkrw) to "원",
    ).map { (t, unit) ->
        val (label, a, b) = t
        val change = when {
            a == b -> "변동 없음"
            a == null -> "새로 받음"
            b == null -> "값 없음"
            unit == "%" -> "%+.2f%%p".format(b - a)
            else -> "%+,.1f원".format(b - a)
        }
        DiffRow(label, fmt(a, unit), fmt(b, unit), change)
    }

    val allocationMoves: List<Pair<String, Int>> =
        ASSETS.map { it to after.allocation.getValue(it) - before.allocation.getValue(it) }.filter { it.second != 0 }

    private fun fmt(v: Double?, unit: String) = when {
        v == null -> "-"
        unit == "%" -> "%.2f%%".format(v)
        else -> "%,.1f원".format(v)
    }
}
