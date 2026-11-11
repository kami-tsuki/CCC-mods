package kami.claims.press

import kami.libs.press.CostTable
import kotlinx.serialization.Serializable

@Serializable
data class ReportSchedule(val enabled: Boolean = true, val times: List<String> = emptyList())

@Serializable
data class PressSettings(
    val rev: Long = 0,
    val dailyCap: Int = 1,
    val perCountryCap: Map<String, Int> = emptyMap(),
    val draftSlots: Int = 5,
    val lockMinutes: Int = 5,
    val autosaveMinSeconds: Int = 10,
    val feeSpur: Long = 10,
    val titleMax: Int = 80,
    val subtitleMax: Int = 120,
    val bodyMax: Int = 5000,
    val retentionDays: Int = 90,
    val cost: CostTable = CostTable(),
    val events: Map<String, Boolean> = EVENTS,
    val warUpdatesPerWar: Int = 3,
    val warUpdatesPerDay: Int = 1,
    val systemPerHour: Int = 6,
    val digest: Boolean = true,
    val marketReport: ReportSchedule = ReportSchedule(times = listOf("12:00", "20:00")),
    val rankingReport: ReportSchedule = ReportSchedule(times = listOf("Mon 12:00")),
    val newsRoom: Boolean = true,
    val countryRoom: Boolean = true
) {
    fun sane() = copy(
        dailyCap = dailyCap.coerceIn(0, MAX_CAP),
        perCountryCap = perCountryCap.filterValues { it in 0..MAX_CAP },
        draftSlots = draftSlots.coerceIn(1, 50),
        lockMinutes = lockMinutes.coerceIn(1, 120),
        autosaveMinSeconds = autosaveMinSeconds.coerceIn(1, 300),
        feeSpur = feeSpur.coerceIn(0, MAX_FEE),
        titleMax = titleMax.coerceIn(10, 200),
        subtitleMax = subtitleMax.coerceIn(0, 300),
        bodyMax = bodyMax.coerceIn(100, 20_000),
        retentionDays = retentionDays.coerceIn(7, 3650),
        cost = cost.clamped(),
        events = EVENTS + events.filterKeys { it in EVENTS },
        warUpdatesPerWar = warUpdatesPerWar.coerceIn(0, MAX_CAP),
        warUpdatesPerDay = warUpdatesPerDay.coerceIn(0, MAX_CAP),
        systemPerHour = systemPerHour.coerceIn(1, 100),
        marketReport = marketReport.clamped(),
        rankingReport = rankingReport.clamped()
    )

    companion object {
        const val MAX_CAP = 100
        const val MAX_FEE = 1_000_000L
        const val MAX_TIMES = 12
        private const val MAX_CHARS = 20_000
        private const val MAX_UNIT = 100

        fun badTime(times: List<String>): String? = if (times.size > MAX_TIMES) "max $MAX_TIMES" else times.firstOrNull { PressSchedule.parse(it) == null }

        private fun ReportSchedule.clamped() = copy(times = times.map { it.trim() }.filter { PressSchedule.parse(it) != null }.distinct().take(MAX_TIMES))

        private fun CostTable.clamped() = copy(
            basePaper = basePaper.coerceIn(0, MAX_UNIT), baseInk = baseInk.coerceIn(0, MAX_UNIT), includedChars = includedChars.coerceIn(0, MAX_CHARS),
            subtitlePaper = subtitlePaper.coerceIn(0, MAX_UNIT), subtitleInk = subtitleInk.coerceIn(0, MAX_UNIT),
            extraChars = extraChars.coerceIn(1, MAX_CHARS), extraPaper = extraPaper.coerceIn(0, MAX_UNIT), extraInk = extraInk.coerceIn(0, MAX_UNIT),
            inkPerElement = inkPerElement.coerceIn(0, MAX_UNIT), inkPerStyle = inkPerStyle.coerceIn(0, MAX_UNIT),
            dyeChars = dyeChars.coerceIn(1, MAX_CHARS), dyePerRef = dyePerRef.coerceIn(0, MAX_UNIT)
        )

        val EVENTS = linkedMapOf(
            "founded" to true, "disbanded" to true, "warDeclared" to true, "warEnded" to true, "warPhase" to true, "warProvince" to true,
            "provinceBecame" to true, "provinceReleased" to true, "provinceGiven" to true, "leaderHandover" to true, "succession" to true,
            "allianceFormed" to true, "renamed" to true, "flagChanged" to true
        )
    }
}
