package kami.claims.admin

import kami.claims.Country
import kami.claims.LedgerKind
import kami.claims.Names
import kami.claims.Rank
import kami.claims.Realm
import kami.claims.economy.Treasury
import kami.claims.net.ResearchSync
import kami.claims.research.Queue
import kami.claims.service.Announce
import kami.claims.service.Fail
import kami.claims.service.Naming
import kami.claims.service.Service
import kami.claims.social.Mail
import kami.claims.war.Stakes
import kami.claims.war.WarPhase
import kami.claims.war.WarResult
import kami.claims.war.Wars
import kami.claims.now
import kami.libs.text.Phrase
import net.minecraft.server.MinecraftServer
import kotlin.math.abs

object CountryAdmin {
    const val ALL = "all"
    const val MAX_MONEY = 1_000_000_000_000L
    val researchOps = listOf("grant", "revoke", "finish", "reset")

    fun disband(c: Country) = Realm.disband(c)

    fun rename(c: Country, name: String) = Naming.rename(c, name, null, fee = false)

    fun leader(c: Country, id: String, server: MinecraftServer?) {
        val next = c.members[id] ?: throw Fail("kami_claims.error.not_member")
        if (next.rank == Rank.PRESIDENT) throw Fail("kami_claims.error.already_president")
        val old = c.president()
        old?.let { c.members.getValue(it).rank = Service.stepDown(c, next) }
        next.rank = Rank.PRESIDENT
        Mail.broadcast(c, Phrase.of("kami_claims.mail.president"))
        Announce.fire("leaderHandover", c, mapOf("country" to c.name, "old" to old?.let { Names.of(server, it) }.orEmpty(), "leader" to Names.of(server, id)))
        Realm.changed()
    }

    fun unclaim(c: Country, target: String): Int {
        val batch = if (target == ALL) Realm.claims(c.id) else listOf(
            target.split('|').takeIf { it.size == 3 }?.let { (d, x, z) -> Realm.at(d, x.toIntOrNull() ?: return@let null, z.toIntOrNull() ?: return@let null) }
                ?.takeIf { it.country == c.id } ?: throw Fail("kami_claims.error.not_your_chunk")
        )
        batch.forEach { Realm.unclaim(it, false) }
        ResearchSync.refresh(c)
        return batch.size
    }

    fun endWar(c: Country, id: String) {
        val war = Realm.data.wars.firstOrNull { it.id == id && it.active && c.id in it.countries } ?: throw Fail("kami_claims.error.war.none")
        if (war.phase == WarPhase.SETTLEMENT) throw Fail("kami_claims.admin.war_settling")
        war.stakes = Stakes()
        war.picks.clear()
        war.silent = true
        Wars.end(war, WarResult.WHITE)
        war.phaseEnds = now()
    }

    fun research(c: Country, op: String, node: String?): String? {
        val key = node?.takeIf { it.isNotBlank() }?.let { Queue.key(c, it) }
        fun need() = key ?: throw Fail("kami_claims.error.missing_argument")
        when (op) {
            "grant" -> Queue.grant(c, need())
            "revoke" -> Queue.revoke(c, need())
            "finish" -> Queue.finish(c, need())
            "reset" -> Queue.reset(c, key)
            else -> throw Fail("kami_claims.admin.bad_op", op)
        }
        return key
    }

    fun money(c: Country, give: Boolean, amount: Long, actor: String?): Long {
        val n = if (give) amount else minOf(amount, c.treasury.coerceAtLeast(0))
        val moved = abs(Treasury.bank(c, LedgerKind.ADJUST, if (give) n else -n, actor, "admin", capped = false))
        return moved
    }
}
