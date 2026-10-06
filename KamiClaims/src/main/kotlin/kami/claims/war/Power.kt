package kami.claims.war

import kami.claims.Config
import kami.claims.Country
import kami.claims.Realm
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tanh

object Power {
    fun score(c: Country): Double {
        val x = mapOf(
            "research" to c.research.done.size.toDouble(),
            "treasury" to c.treasury.toDouble(),
            "members" to c.members.size.toDouble(),
            "chunks" to Realm.claims(c.id).size.toDouble(),
            "provinces" to c.provinces.size.toDouble(),
            "provinceMembers" to c.provinces.sumOf { Realm.country(it)?.members?.size ?: 0 }.toDouble(),
            "allies" to c.alliances.size.toDouble()
        )
        val s = Config.s.war.power.entries.sumOf { (k, f) -> f.weight * tanh((x[k] ?: 0.0).coerceAtLeast(0.0) / f.scale) }
        return max(1, c.level) * (1 + s)
    }

    fun snapshot(war: War) {
        war.sides.forEach { side ->
            side.power = Realm.country(side.main)?.let(::score) ?: side.power
            side.joined.forEach { id -> side.powers[id] = max(Realm.country(id)?.let(::score) ?: side.powers[id] ?: 0.0, side.power) }
        }
        war.stakedPower = staked(war.stakes)
    }

    fun staked(stakes: Stakes) = stakes.provinces.mapNotNull(Realm::country).sumOf { score(it) }

    fun multiplier(self: Double, opp: Double): Double {
        if (self <= 0 || opp <= 0) return 1.0
        val w = Config.s.war
        return (self / opp).pow(if (self >= opp) w.strongExponent else w.weakExponent)
    }
}
