package kami.claims.war

import kami.claims.Action
import kami.claims.Claim
import kami.claims.Country
import kami.claims.Key
import kami.claims.Realm
import kami.libs.player.Presence
import net.minecraft.world.entity.player.Player

object WarGuard {
    private fun hostile(w: War, c: Country, other: String) = w.phase == WarPhase.WAR && w.side(c.id)?.let { other in w.opponent(it).countries } == true

    internal fun wars(who: Player, c: Country, other: String) = Realm.data.wars.filter { hostile(it, c, other) && Wars.combatant(it, c, who.stringUUID) }

    internal fun wars(attacker: Player, victim: Player, a: Country, v: Country) = wars(attacker, a, v.id).filter { Wars.combatant(it, v, victim.stringUUID) }

    fun safe(cl: Claim?): Boolean {
        if (cl == null || cl.capital) return cl != null
        val c = Realm.data.countries[cl.country] ?: return false
        val ref = ChunkRef(cl.dim, cl.x, cl.z)
        return ref in c.safeChunks && ref in SafeChunks.active(c)
    }

    fun shielded(cl: Claim?): Boolean {
        if (cl == null || !Wars.fighting()) return false
        val c = Realm.data.countries[cl.country] ?: return false
        return Wars.fighting(c) && safe(cl)
    }

    fun pvp(attacker: Player, victim: Player, cl: Claim?): Boolean? {
        if (!Wars.fighting()) return null
        val a = Realm.of(attacker.stringUUID) ?: return null
        val v = Realm.of(victim.stringUUID) ?: return null
        val wars = wars(attacker, victim, a, v)
        if (wars.isEmpty()) return null
        if (Presence.hidden(attacker.uuid) || safe(cl)) return false
        val from = Realm.index[Key(attacker.level().dimension().location().toString(), attacker.blockX shr 4, attacker.blockZ shr 4)]
        if (safe(from)) return false
        return wars.any { w -> (cl == null || w.side(cl.country) != null) && (from == null || w.side(from.country) != null) }
    }

    fun build(who: Player, cl: Claim, action: Action): Boolean {
        if (action == Action.CONTAINER || cl.type == "wilderness" || !Wars.fighting()) return false
        val me = Realm.of(who.stringUUID) ?: return false
        val owner = Realm.data.countries[cl.country] ?: return false
        return wars(who, me, owner.id).isNotEmpty() && !safe(cl) && Online.exposed(owner)
    }
}
