package kami.claims.service

import kami.claims.Country
import kami.claims.Realm
import kami.claims.gov.Perm
import kami.claims.gov.Permissions
import kami.claims.social.Perms
import net.minecraft.server.level.ServerPlayer

object Oversight {
    val grantable = setOf(
        Perm.CLAIM, Perm.UNCLAIM, Perm.CHUNK_TYPE, Perm.CAPITAL, Perm.CHUNK_RULES, Perm.TAXES, Perm.PLOTS, Perm.HOUSING,
        Perm.ASSIGN_JOBS, Perm.DEFINE_JOBS, Perm.ASSIGN_CHUNKS, Perm.SCOPE_JOBS, Perm.SECTORS, Perm.RESEARCH, Perm.TRADE
    )

    private fun delegating(c: Country, p: ServerPlayer): Country? =
        Realm.of(p.stringUUID)?.takeIf { c.parent == it.id && Permissions.holds(it, p.stringUUID, Perm.DELEGATED) }

    fun granted(c: Country, p: ServerPlayer, perm: Perm): Boolean =
        c.overlordManage && perm in grantable && perm in c.overlordPerms &&
            delegating(c, p)?.let { Permissions.holds(it, p.stringUUID, perm) } == true

    fun may(c: Country, p: ServerPlayer, perm: Perm): Boolean =
        if (p.stringUUID in c.members) Permissions.holds(c, p.stringUUID, perm) else granted(c, p, perm)

    fun perms(c: Country, p: ServerPlayer): List<Perm> = grantable.filter { granted(c, p, it) }

    fun canView(c: Country, p: ServerPlayer): Boolean = Perms.has(p, Perms.ADMIN) || delegating(c, p) != null

    fun canManage(c: Country): Boolean = c.overlordManage && c.overlordPerms.isNotEmpty()
}
