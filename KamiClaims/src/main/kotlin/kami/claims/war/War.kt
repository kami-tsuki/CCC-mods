package kami.claims.war

import kami.claims.Key
import kotlinx.serialization.Serializable

@Serializable
enum class WarKind { REGULAR, REBELLION }

@Serializable
enum class WarPhase { DECLARATION, PREPARATION, WAR, CEASEFIRE, SETTLEMENT, ENDED }

@Serializable
enum class WarResult { ATTACKER, DEFENDER, PEACE, WHITE, WITHDRAWN }

@Serializable
data class ChunkRef(val dim: String, val x: Int, val z: Int) {
    val key get() = Key(dim, x, z)
}

@Serializable
class Stakes(
    val chunks: List<ChunkRef> = emptyList(),
    val money: Long = 0,
    val vassal: Boolean = false,
    val provinces: List<String> = emptyList()
)

@Serializable
class WarOffer(
    val from: String,
    val stakes: Stakes? = null,
    val ceasefireDays: Int = 0,
    val peace: Boolean = false,
    val extendDays: Int = 0,
    val at: Long = 0,
    val billPct: Int? = null
)

@Serializable
class WarSide(
    val main: String,
    val joined: MutableSet<String> = mutableSetOf(),
    var power: Double = 0.0,
    var exhaustion: Double = 0.0,
    val bill: MutableMap<String, Long> = mutableMapOf(),
    val deaths: MutableMap<String, Int> = mutableMapOf(),
    val powers: MutableMap<String, Double> = mutableMapOf(),
    val material: MutableMap<String, Double> = mutableMapOf(),
    var materialFirst: Long = 0,
    var farDamage: Double = 0.0
) {
    val countries get() = joined + main
}

@Serializable
class War(
    val id: String,
    val kind: WarKind,
    val attacker: WarSide,
    val defender: WarSide,
    val declaredAt: Long,
    var phase: WarPhase = WarPhase.DECLARATION,
    var phaseEnds: Long = 0,
    var extendedDays: Int = 0,
    var ceasefires: Int = 0,
    var stakes: Stakes = Stakes(),
    var offer: WarOffer? = null,
    var fee: Long = 0,
    var escrow: Long = 0,
    var result: WarResult? = null,
    var remindedAt: Long = 0,
    var endedAt: Long = 0,
    val picks: MutableList<ChunkRef> = mutableListOf(),
    var settled: Boolean = false,
    var surrendered: Boolean = false,
    var silent: Boolean = false,
    var stakedPower: Double = 0.0,
    var ceasefireStart: Long = 0,
    var warMillis: Long = 0,
    var warSince: Long = 0,
    var renegotiated: Boolean = false,
    val offeredAt: MutableMap<String, Long> = mutableMapOf(),
    var billPct: Int = 100,
    val rebels: MutableMap<String, String> = mutableMapOf(),
    val eligible: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    val embargoes: MutableList<String> = mutableListOf()
) {
    val sides get() = listOf(attacker, defender)
    val countries get() = attacker.countries + defender.countries
    val active get() = phase != WarPhase.ENDED
    fun side(country: String) = sides.firstOrNull { country in it.countries }
    fun opponent(side: WarSide) = if (side === attacker) defender else attacker
}
