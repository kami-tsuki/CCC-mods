package kami.claims.service

import kami.claims.Cap
import kami.claims.Config
import kami.claims.Country
import kami.claims.Rank
import kami.claims.Realm
import kami.claims.social.Perms
import net.minecraft.server.level.ServerPlayer

object Oversight {
    val actingRank = Rank.PRESIDENT
    val grantable = listOf(Cap.CLAIM, Cap.CAPITAL, Cap.TAX, Cap.RULES, Cap.JOBS, Cap.HOUSING, Cap.RESEARCH, Cap.TRADE, Cap.INVITE, Cap.WITHDRAW)

    private fun overlordRank(c: Country, p: ServerPlayer): Rank? =
        Realm.of(p.stringUUID)?.takeIf { c.parent == it.id }?.let { Service.rankOf(it, p) }

    private fun granted(c: Country, rank: Rank, cap: Cap): Boolean =
        c.overlordManage && cap in grantable && c.overlordCaps[cap]?.let { rank >= it } == true

    fun granted(c: Country, p: ServerPlayer, cap: Cap): Boolean = overlordRank(c, p)?.let { granted(c, it, cap) } == true

    fun may(c: Country, p: ServerPlayer, cap: Cap): Boolean =
        if (p.stringUUID in c.members) Config.s.can(Service.rankOf(c, p), cap) else granted(c, p, cap)

    fun caps(c: Country, p: ServerPlayer): List<String> {
        val rank = overlordRank(c, p) ?: return emptyList()
        return grantable.filter { granted(c, rank, it) }.map { it.name.lowercase() }
    }

    fun canView(c: Country, p: ServerPlayer): Boolean {
        if (Perms.has(p, Perms.ADMIN)) return true
        val rank = overlordRank(c, p) ?: return false
        return rank >= Rank.CHANCELLOR || grantable.any { granted(c, rank, it) }
    }

    fun canManage(c: Country): Boolean = c.overlordManage && c.overlordCaps.isNotEmpty()
}
