package kami.claims.gov

import kami.claims.Country
import kami.claims.Data
import kami.claims.Member
import kami.claims.Realm
import kami.claims.WithDb
import kami.claims.service.Fail

fun rankWith(id: String, vararg perms: Perm, plots: Int = 0) = RankDef(id, id.replaceFirstChar { it.uppercase() }, 0, perms.toMutableSet(), plots)

abstract class GovTest : WithDb() {
    protected fun country(members: Map<String, String>, vararg middle: RankDef, name: String = "Gov"): Country {
        val c = Country(name)
        c.ranks += RankDef(PRESIDENT, "President", 0, plots = 25)
        c.ranks += middle
        c.ranks += RankDef(CITIZEN, "Citizen", 0, RoleTemplate.MEMBER.perms.toMutableSet(), 2)
        members.forEach { (id, rank) -> c.members[id] = Member(rank) }
        val data = Data(schema = Data.CURRENT)
        data.countries[c.id] = c
        Realm.reset(data)
        return c
    }

    protected fun standard(): Country = country(
        mapOf("pres" to PRESIDENT, "chan" to "chancellor", "off" to "officer", "cit" to CITIZEN),
        rankWith("chancellor", Perm.ASSIGN_RANKS, Perm.EDIT_RANKS, Perm.CREATE_RANKS, Perm.REORDER_RANKS, Perm.KICK, Perm.INVITE, Perm.CLAIM, Perm.BREAK, Perm.DEPOSIT),
        rankWith("officer", Perm.KICK, Perm.INVITE, Perm.BREAK, Perm.DEPOSIT)
    )

    protected fun failure(block: () -> Unit): String = try {
        block()
        ""
    } catch (e: Fail) {
        e.phrase.json()
    }
}
