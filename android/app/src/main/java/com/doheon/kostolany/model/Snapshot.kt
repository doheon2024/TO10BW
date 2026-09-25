package com.doheon.kostolany.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class RateChange(val date: LocalDate, val rate: Double)

/** data/snapshot.json과 같은 형식의 지표 스냅샷. 모르는 키(sources 등)는 raw에 그대로 보존한다. */
data class Snapshot(
    val asOf: LocalDate,
    val history: List<RateChange>,
    val cpiYoy: Double?,
    val coreCpiYoy: Double?,
    val inflationTarget: Double,
    val gdpGrowth: Double?,
    val potentialGrowth: Double,
    val neutralRate: Double,
    val ktb3y: Double?,
    val ktb10y: Double?,
    val usdkrw: Double?,
    val housePriceTrend: String?,
    val householdDebtTrend: String?,
    val policyGuidance: String?,
    val cpiMonth: String? = null,
    val coreCpiMonth: String? = null,
    val raw: String = "{}",
) {
    val currentRate: Double get() = history.last().rate

    fun toJson(): String {
        val o = JSONObject(raw)
        o.put("as_of", asOf.toString())
        o.put("base_rate_history", JSONArray().apply {
            history.forEach { put(JSONObject().put("date", it.date.toString()).put("rate", it.rate)) }
        })
        o.put("cpi_yoy", cpiYoy ?: JSONObject.NULL)
        o.put("core_cpi_yoy", coreCpiYoy ?: JSONObject.NULL)
        o.put("inflation_target", inflationTarget)
        o.put("gdp_growth", gdpGrowth ?: JSONObject.NULL)
        o.put("potential_growth", potentialGrowth)
        o.put("neutral_rate", neutralRate)
        o.put("ktb_3y", ktb3y ?: JSONObject.NULL)
        o.put("ktb_10y", ktb10y ?: JSONObject.NULL)
        o.put("usdkrw", usdkrw ?: JSONObject.NULL)
        o.put("house_price_trend", housePriceTrend ?: JSONObject.NULL)
        o.put("household_debt_trend", householdDebtTrend ?: JSONObject.NULL)
        o.put("policy_guidance", policyGuidance ?: JSONObject.NULL)
        cpiMonth?.let { o.put("cpi_yoy_month", it) }
        coreCpiMonth?.let { o.put("core_cpi_yoy_month", it) }
        return o.toString(2)
    }

    companion object {
        fun fromJson(text: String): Snapshot {
            val o = JSONObject(text)
            fun num(key: String): Double? = if (o.isNull(key)) null else o.getDouble(key)
            fun str(key: String): String? = if (o.isNull(key)) null else o.getString(key)
            val h = o.getJSONArray("base_rate_history")
            return Snapshot(
                asOf = LocalDate.parse(o.getString("as_of")),
                history = (0 until h.length()).map {
                    val e = h.getJSONObject(it)
                    RateChange(LocalDate.parse(e.getString("date")), e.getDouble("rate"))
                }.sortedBy { it.date },
                cpiYoy = num("cpi_yoy"),
                coreCpiYoy = num("core_cpi_yoy"),
                inflationTarget = num("inflation_target") ?: 2.0,
                gdpGrowth = num("gdp_growth"),
                potentialGrowth = num("potential_growth") ?: 2.0,
                neutralRate = num("neutral_rate") ?: 2.5,
                ktb3y = num("ktb_3y"),
                ktb10y = num("ktb_10y"),
                usdkrw = num("usdkrw"),
                housePriceTrend = str("house_price_trend"),
                householdDebtTrend = str("household_debt_trend"),
                policyGuidance = str("policy_guidance"),
                cpiMonth = str("cpi_yoy_month"),
                coreCpiMonth = str("core_cpi_yoy_month"),
                raw = text,
            )
        }
    }
}
