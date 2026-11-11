package kami.claims.gov

import kami.claims.Config
import kami.claims.Country
import kami.claims.Member
import kami.claims.now
import kami.claims.service.Housing
import kotlinx.serialization.Serializable

const val PRESIDENT = "president"
const val CITIZEN = "citizen"

fun Member.changeRank(to: String, c: Country? = null) {
    if (to == rank) return
    val held = rankPrev?.takeIf { c != null && now() - rankAt < Housing.RANK_DELAY_DAYS * Config.s.dayMillis && Permissions.position(c, it) < Permissions.position(c, rank) }
    rankPrev = held ?: rank
    if (held == null) rankAt = now()
    rank = to
}

object RankPlots {
    const val PRESIDENT = 25
    const val CITIZEN = 2
}

@Serializable
enum class Standing { ALLIED, BANISHED }

@Serializable
class RankDef(
    val id: String,
    var name: String,
    var color: Int,
    val perms: MutableSet<Perm> = mutableSetOf(),
    var plots: Int = 0
) {
    val fixed get() = id == PRESIDENT
}

enum class RoleTemplate(val perms: Set<Perm>) {
    DEPUTY(Perm.entries.toSet() - setOf(Perm.CAPITAL, Perm.PROVINCES, Perm.WITHDRAW, Perm.EDIT_RANKS, Perm.CREATE_RANKS, Perm.REORDER_RANKS, Perm.EDIT_GROUPS, Perm.BANISH, Perm.PARDON, Perm.WAR, Perm.BYPASS_RULES, Perm.DELEGATED)),
    TREASURER(setOf(Perm.TREASURY_VIEW, Perm.DEPOSIT, Perm.WITHDRAW, Perm.TAXES, Perm.SPEND, Perm.TRADE, Perm.VIEW_PEOPLE, Perm.VIEW_JOBS, Perm.AUDIT, Perm.BREAK, Perm.PLACE, Perm.INTERACT, Perm.CONTAINERS)),
    WARDEN(setOf(Perm.INVITE, Perm.KICK, Perm.BANISH, Perm.PARDON, Perm.ALLIES, Perm.VIEW_PEOPLE, Perm.CHUNK_RULES, Perm.TREASURY_VIEW, Perm.DEPOSIT, Perm.BREAK, Perm.PLACE, Perm.INTERACT, Perm.CONTAINERS)),
    FOREMAN(setOf(Perm.CLAIM, Perm.SPEND, Perm.CHUNK_TYPE, Perm.PLOTS, Perm.SECTORS, Perm.VIEW_JOBS, Perm.ASSIGN_JOBS, Perm.DEFINE_JOBS, Perm.ASSIGN_CHUNKS, Perm.SCOPE_JOBS, Perm.TREASURY_VIEW, Perm.DEPOSIT, Perm.VIEW_PEOPLE, Perm.BREAK, Perm.PLACE, Perm.INTERACT, Perm.CONTAINERS)),
    EDITOR(setOf(Perm.NEWS_WRITE, Perm.NEWS_PUBLISH, Perm.TREASURY_VIEW, Perm.DEPOSIT, Perm.VIEW_PEOPLE, Perm.VIEW_JOBS, Perm.BREAK, Perm.PLACE, Perm.INTERACT, Perm.CONTAINERS)),
    MEMBER(setOf(Perm.TREASURY_VIEW, Perm.DEPOSIT, Perm.VIEW_PEOPLE, Perm.VIEW_JOBS, Perm.BREAK, Perm.PLACE, Perm.INTERACT, Perm.CONTAINERS, Perm.PLOTS));

    val key get() = name.lowercase()
}
