package kami.claims.admin

import kami.claims.Country
import kami.claims.LedgerKind
import kami.claims.Names
import kami.claims.Realm
import kami.claims.economy.Treasury
import kami.claims.gov.CITIZEN
import kami.claims.gov.changeRank
import kami.claims.gov.PRESIDENT
import kami.claims.gov.leaderChanged
import kami.claims.gov.Permissions
import kami.claims.net.ResearchSync
import kami.claims.research.Levels
import kami.claims.research.Queue
import kami.claims.research.Sweep
import kami.claims.service.Announce
import kami.claims.service.Fail
import kami.claims.service.Housing
import kami.claims.service.Naming
import kami.claims.service.Words
import kami.claims.social.Mail
import kami.claims.war.Stakes
import kami.claims.war.WarResult
import kami.claims.war.Wars
import kami.libs.chat.Tone
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
        if (Permissions.isPresident(c, id)) throw Fail("kami_claims.error.already_president")
        val old = Permissions.president(c)
        Housing.repriced(c) {
            old?.let { c.members.getValue(it).changeRank(CITIZEN) }
            next.changeRank(PRESIDENT)
        }
        c.leaderChanged()
        Mail.broadcast(c, Phrase.of("kami_claims.mail.president"))
        Announce.fire("leaderHandover", c, mapOf("country" to c.name, "old" to old?.let { Names.of(server, it) }.orEmpty(), "leader" to Names.of(server, id)))
        ResearchSync.refresh(c)
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

    fun endWar(c: Country, id: String, reason: String) {
        val war = Realm.data.wars.firstOrNull { it.id == id && it.active && c.id in it.countries } ?: throw Fail("kami_claims.error.war.none")
        war.stakes = Stakes()
        war.picks.clear()
        war.silent = true
        war.offer = null
        Wars.end(war, WarResult.WHITE)
        Wars.close(war, cooldown = false)
        Wars.mail(war.countries, Phrase.of("kami_claims.mail.war.admin_ended", Words.v(reason)), Tone.WARN)
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

    fun researchScope(c: Country, op: String, tree: String, category: String): Int {
        val scope = Sweep(tree, category)
        return when (op) {
            "finish" -> Queue.finishAll(c, scope)
            "reset" -> Queue.resetAll(c, scope)
            else -> throw Fail("kami_claims.admin.bad_op", op)
        }
    }

    fun level(c: Country, level: Int): Int = Levels.setLevel(c, level)

    fun money(c: Country, give: Boolean, amount: Long, actor: String?): Long {
        val n = if (give) amount else minOf(amount, c.treasury.coerceAtLeast(0))
        val moved = abs(Treasury.bank(c, LedgerKind.ADJUST, if (give) n else -n, actor, "admin", capped = false))
        return moved
    }
}
