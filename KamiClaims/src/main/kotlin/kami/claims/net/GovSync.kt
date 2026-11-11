package kami.claims.net

import kami.claims.Country
import kami.claims.Names
import kami.claims.Config
import kami.claims.gov.Council
import kami.claims.gov.GovernmentActions
import kami.claims.gov.Permissions
import kami.claims.gov.rankCap
import kami.claims.now
import kami.claims.research.Capacity
import kami.claims.research.Levels
import kami.claims.research.Tokens
import kami.claims.service.Work
import kotlinx.serialization.Serializable
import net.minecraft.server.MinecraftServer

@Serializable class RankLine(val id: String, val name: String, val color: Int, val position: Int, val perms: List<String>, val plots: Int, val members: Int)
@Serializable class GovLine(
    val preset: String, val labour: String, val land: String, val taxes: String, val treasury: String, val leadership: String, val ranks: String,
    val provinces: String, val membership: String, val changedAt: Long, val hasToken: Boolean, val cooldownUntil: Long, val cost: Long,
    val heir: String = "", val myVote: String = ""
)
@Serializable class WithdrawLine(val id: String, val requester: String, val amount: Long, val ageMinutes: Int)
@Serializable class SectorLine(val id: String, val name: String, val color: Int, val chunks: List<String>, val manager: String, val fragmented: Boolean)
@Serializable class TaskLine(val kind: String, val filter: String, val cap: Int)
@Serializable class JobSpecLine(
    val id: String, val name: String, val color: Int, val scope: String, val scopeRef: String, val tasks: List<TaskLine>, val pay: Int, val quota: Int,
    val period: Int, val payModel: String, val assignment: String, val active: Boolean, val holders: List<String>, val basePay: Int,
    val perms: List<String> = emptyList()
)
@Serializable class ContractLine(val id: String, val job: String, val player: String, val offeredBy: String, val pay: Int, val quota: Int, val period: Int, val until: Long, val accepted: Boolean)
@Serializable class AuditLine(val at: Long, val actor: String, val action: String, val target: String, val detail: String)

object GovSync {
    private const val PLAYER_AUDIT_LINES = 50
    private const val MAX_LINES = 64
    private const val MINUTE_MS = 60_000L

    fun ranks(c: Country) = c.ranks.mapIndexed { i, r ->
        RankLine(r.id, r.name, r.color, i, r.perms.map { it.name }, r.plots, Permissions.holders(c, r.id))
    }

    fun groups(c: Country): Map<String, List<String>> = c.groups.entries.associate { (k, v) -> k.name to v.map { it.name } }

    fun rankCap(c: Country) = c.government.rankCap(Levels.capacity(c, Capacity.RANKS))

    fun government(c: Country, viewer: String = "", showHeir: Boolean = true): GovLine {
        val g = c.government
        val l = g.levers
        return GovLine(
            g.preset.key, l.labour.lower(), l.land.lower(), l.taxes.lower(), l.treasury.lower(), l.leadership.lower(), l.ranks.lower(), l.provinces.lower(), l.membership.lower(),
            g.changedAt, (c.tokens[Tokens.SETTINGS] ?: 0) > 0, g.changedAt + GovernmentActions.COOLDOWN_DAYS * Config.s.dayMillis, GovernmentActions.FEE,
            if (showHeir) g.heir.orEmpty() else "", g.votes[viewer].orEmpty()
        )
    }

    fun withdrawals(server: MinecraftServer, c: Country) = Council.pending(c).take(MAX_LINES).map {
        WithdrawLine(it.id, Names.of(server, it.requester), it.amount, ((now() - it.at) / MINUTE_MS).toInt())
    }

    fun sectors(c: Country, withChunks: Boolean = true) = c.sectors.values.take(MAX_LINES).map { s ->
        SectorLine(
            s.id, s.name, s.color, if (withChunks) s.chunks.map { it.wire() } else emptyList(), s.manager.orEmpty(), s.fragmented
        )
    }

    fun jobSpecs(c: Country) = c.jobDefs.values.take(MAX_LINES).map { j ->
        JobSpecLine(
            j.id, j.name, j.color, j.scope.lower(), j.scopeRef, j.tasks.map { TaskLine(it.kind.lower(), it.filter, it.cap) }, j.pay, j.quota, j.period,
            j.payModel.lower(), j.assignment.lower(), j.active, Work.holders(c, j.id).toList(), j.basePay, j.perms.map { it.name }
        )
    }

    fun contracts(c: Country) = c.contracts.take(MAX_LINES).map { ContractLine(it.id, it.job, it.player, it.offeredBy, it.pay, it.quota, it.period, it.until, it.accepted) }

    fun audit(server: MinecraftServer, c: Country, limit: Int = PLAYER_AUDIT_LINES) =
        c.audit.takeLast(limit).asReversed().map { AuditLine(it.at, Names.of(server, it.actor), it.action, it.target, it.detail) }

    private fun Enum<*>.lower() = name.lowercase()
}
