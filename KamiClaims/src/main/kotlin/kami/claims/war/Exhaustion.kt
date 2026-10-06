package kami.claims.war

import kami.claims.Config
import kami.claims.now
import kotlin.math.max

object Exhaustion {
    private val s get() = Config.s.war

    fun update(war: War) {
        val t = now()
        if (war.warSince > 0) war.warMillis += (t - war.warSince).coerceAtLeast(0)
        war.warSince = t
        val days = war.warMillis.toDouble() / Config.s.dayMillis
        val cap = s.exhaustionLossPerDayCap * max(1.0, days)
        war.attacker.exhaustion = value(war.attacker, war.defender, days * s.exhaustionAttackerDay, cap)
        war.defender.exhaustion = value(war.defender, war.attacker, days * s.exhaustionDefenderDay, cap)
    }

    private fun value(side: WarSide, enemy: WarSide, base: Double, cap: Double) =
        (base + (s.exhaustionDeath * side.deaths.values.sum() + s.exhaustionPerThousand * enemy.bill.values.sum() / 1000.0).coerceAtMost(cap)).coerceIn(0.0, 100.0)
}
