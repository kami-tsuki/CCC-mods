package kami.claims.war

import kami.claims.Claim
import kami.claims.Config
import kami.claims.Country
import kami.claims.Realm
import net.minecraft.world.entity.player.Player
import kotlin.math.floor

object Bill {
    private val s get() = Config.s.war

    fun hit(attacker: Player, victim: Player, at: Claim?) {
        versus(attacker, victim)?.let { (w, a) -> add(w, a, at, s.billHit) }
    }

    fun kill(attacker: Player, victim: Player, at: Claim?) {
        val (w, a, v) = versus(attacker, victim) ?: return
        add(w, a, at, s.billKill)
        w.side(v.id)?.deaths?.merge(v.id, 1) { x, y -> x + y }
        Realm.dirty = true
    }

    fun block(raider: Player, cl: Claim) {
        against(raider, cl, s.billBlock)
        Reputation.far(raider, cl)
    }

    fun explosion(raider: Player, cl: Claim, blocks: Int) = against(raider, cl, WarDebt.plus(s.billExplosion, WarDebt.times(s.billExplosionBlock, blocks.toLong())))

    fun animal(killer: Player, cl: Claim) {
        against(killer, cl, s.billAnimal)
        Reputation.far(killer, cl)
    }

    private fun versus(attacker: Player, victim: Player): Triple<War, Country, Country>? {
        if (!Wars.fighting()) return null
        val a = Realm.of(attacker.stringUUID) ?: return null
        val v = Realm.of(victim.stringUUID) ?: return null
        return WarGuard.wars(attacker, victim, a, v).firstOrNull()?.let { Triple(it, a, v) }
    }

    private fun against(who: Player, cl: Claim, amount: Long) {
        if (!Wars.fighting()) return
        val c = Realm.of(who.stringUUID) ?: return
        WarGuard.wars(who, c, cl.country).firstOrNull()?.let { add(it, c, cl, amount) }
    }

    private fun add(w: War, c: Country, at: Claim?, amount: Long) {
        val side = w.side(c.id) ?: return
        val value = if (at != null && at.country in side.countries) floor(amount * s.ownClaimsFactor).toLong() else amount
        if (value <= 0) return
        side.bill.merge(c.id, value) { x, y -> WarDebt.plus(x, y) }
        Realm.dirty = true
    }
}
