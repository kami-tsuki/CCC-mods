package kami.claims.gov

import kami.claims.Config
import kami.claims.Country
import kami.claims.Member
import kami.claims.now
import kami.claims.service.Fail
import kami.claims.service.Housing
import kotlinx.serialization.Serializable

@Serializable enum class Labour { MARKET, WAGE, QUOTA, DUTY }
@Serializable enum class Land { PRIVATE, LEASE, STATE, COMMUNAL }
@Serializable enum class TaxStyle { NONE, FLAT, BRACKETS, TRIBUTE }
@Serializable enum class TreasuryControl { RULER, COUNCIL, BUDGET }
@Serializable enum class Leadership { APPOINTED, ELECTED, HEREDITARY, SEIZED }
@Serializable enum class RankStructure { LADDER, FLAT }
@Serializable enum class ProvinceRule { DIRECT, VASSAL, AUTONOMOUS, FREE }
@Serializable enum class Membership { OPEN, APPLICATION, INVITE }

@Serializable
enum class PresetId { MIXED, CAPITALISM, SOCIALISM, COMMUNISM, MONARCHY, COMMUNE, STRATOCRACY, THEOCRACY, CUSTOM;
    val key get() = name.lowercase()
}

@Serializable
data class Levers(
    val labour: Labour,
    val land: Land,
    val taxes: TaxStyle,
    val treasury: TreasuryControl,
    val leadership: Leadership,
    val ranks: RankStructure,
    val provinces: ProvinceRule,
    val membership: Membership
) {
    companion object {
        fun parse(a: List<String>, from: Int) = Levers(
            govChoice(a, from), govChoice(a, from + 1), govChoice(a, from + 2), govChoice(a, from + 3),
            govChoice(a, from + 4), govChoice(a, from + 5), govChoice(a, from + 6), govChoice(a, from + 7)
        )
    }
}

internal inline fun <reified T : Enum<T>> govChoice(a: List<String>, i: Int): T =
    enumValues<T>().firstOrNull { it.name.equals(a.getOrNull(i), true) } ?: throw Fail("kami_claims.error.gov_value")

@Serializable
class Government(
    var preset: PresetId = PresetId.MIXED,
    var levers: Levers = Presets.levers(PresetId.MIXED),
    var changedAt: Long = 0,
    var changes: Int = 0,
    var heir: String? = null,
    val votes: MutableMap<String, String> = mutableMapOf()
) {
    val wageFloor: Double get() = if (levers.labour != Labour.WAGE) 0.0 else when (preset) {
        PresetId.SOCIALISM -> 0.5
        PresetId.MIXED -> 0.25
        else -> 0.0
    }

    fun switch(c: Country, to: PresetId, next: Levers, reset: Boolean) = Housing.repriced(c) {
        if (next.leadership != levers.leadership) votes.clear()
        if (next.leadership !in HEIR_LEADERSHIPS) heir = null
        if (next.treasury != levers.treasury) Council.clear(c)
        preset = to
        levers = next
        if (reset) RankRules.resetTo(c, to)
    }
}

fun Country.leaderChanged() {
    government.heir = null
    government.votes.clear()
    Council.clear(this)
}

const val HEIR_MIN_DAYS = 7

fun Government.heirEligible(m: Member) =
    levers.leadership != Leadership.HEREDITARY || now() - m.since >= HEIR_MIN_DAYS * Config.s.dayMillis

const val RESET_RANKS = "reset_ranks"
val HEIR_LEADERSHIPS = setOf(Leadership.HEREDITARY, Leadership.APPOINTED)

const val FLAT_RANK_CAP = 4

fun Government.rankCap(capacity: Int): Int = if (levers.ranks == RankStructure.FLAT) minOf(capacity, FLAT_RANK_CAP) else capacity
