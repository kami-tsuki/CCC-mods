package kami.claims.client.hud

import kami.claims.net.HomeLine
import kotlinx.serialization.Serializable

@Serializable
enum class TaxHudMode { OFF, ALWAYS, IMPORTANT }

class TaxInputs(val homes: List<HomeLine>, val receivedAt: Long, val now: Long, val treasury: Long, val net: Long, val hasCountry: Boolean)

class TaxRows(val hasHomes: Boolean, val debt: Long, val prepaid: Long, val prepaidDays: Long?, val rentDueIn: Long?, val hasLapse: Boolean, val claimsDays: Long?, val lowestPrepaidDays: Long? = null) {
    val empty get() = !hasHomes && !hasLapse
}

class TaxConditions(
    val debt: Boolean, val prepaid: Boolean, val prepaidDays: Long, val due: Boolean, val dueHours: Long, val lapse: Boolean, val lapseDays: Long
)

object TaxTimer {
    private const val HOUR_MS = 3_600_000L

    fun rows(i: TaxInputs): TaxRows {
        val rent = i.homes.sumOf { it.rent.toLong() }
        val prepaid = i.homes.sumOf { it.credit }
        val due = i.homes.filter { it.nextBill > 0 }.minOfOrNull { maxOf(0L, i.receivedAt + it.nextBill - i.now) }
        return TaxRows(
            i.homes.isNotEmpty(), i.homes.sumOf { it.debt }, prepaid, if (rent > 0) prepaid / rent else null, due,
            i.hasCountry, if (i.hasCountry && i.net < 0) i.treasury / -i.net else null,
            i.homes.filter { it.rent > 0 }.minOfOrNull { it.credit / it.rent }
        )
    }

    fun important(r: TaxRows, c: TaxConditions) =
        (c.debt && r.debt > 0) ||
            (c.prepaid && r.lowestPrepaidDays.let { it != null && it <= c.prepaidDays }) ||
            (c.due && r.rentDueIn.let { it != null && it <= c.dueHours * HOUR_MS }) ||
            (c.lapse && r.claimsDays.let { it != null && it <= c.lapseDays })
}
