package kami.claims.gov

import kami.claims.Country
import kami.claims.gov.ActionArgs.arg
import kami.claims.gov.ActionArgs.int
import kami.claims.now
import kami.claims.service.Fail
import kami.claims.service.Housing
import kami.claims.service.Service
import kami.claims.service.Words.v
import kami.libs.text.Phrase
import net.minecraft.server.level.ServerPlayer

object RankActions {
    private const val CONFIRM = "confirm"
    private const val BLANK = "blank"
    private const val RATE_LIMIT = 20
    private const val RATE_WINDOW_MS = 60_000L

    private class Plan(val warnings: List<Warning>, val apply: () -> Phrase)

    private val arity = mapOf("rank_create" to 3, "rank_edit" to 3, "rank_perms" to 2, "rank_plots" to 2, "rank_move" to 2, "rank_delete" to 2, "rank_assign" to 2, "group_perms" to 2)

    private val recent = HashMap<String, ArrayDeque<Long>>()

    fun reset() = recent.clear()

    fun act(p: ServerPlayer, name: String, args: List<String>): Phrase? {
        val fixed = arity[name] ?: 0
        val confirmed = args.getOrNull(fixed) == CONFIRM
        val plan = plan(p, name, args.take(fixed)) ?: return null
        val blocking = plan.warnings.any { it.severity >= WarnTier.DANGER }
        if (blocking && !confirmed) throw Fail("kami_claims.error.confirm_required", v(plan.warnings.joinToString(",") { "${it.key}:${it.severity}" }))
        throttle("${Service.home(p).id}:${p.stringUUID}")
        return plan.apply()
    }

    private fun plan(p: ServerPlayer, name: String, a: List<String>): Plan? = when (name) {
        "rank_create" -> create(p, a)
        "rank_edit" -> edit(p, a)
        "rank_perms" -> perms(p, a)
        "rank_plots" -> plots(p, a)
        "rank_move" -> move(p, a)
        "rank_delete" -> delete(p, a)
        "rank_assign" -> assign(p, a)
        "group_perms" -> group(p, a)
        else -> null
    }

    internal fun throttle(key: String) {
        val cutoff = now() - RATE_WINDOW_MS
        recent.values.removeIf { it.lastOrNull()?.let { last -> last < cutoff } != false }
        val times = recent.getOrPut(key) { ArrayDeque() }
        while (times.firstOrNull()?.let { it < cutoff } == true) times.removeFirst()
        if (times.size >= RATE_LIMIT) throw Fail("kami_claims.error.rank_rate")
        times.addLast(now())
    }

    private fun done(c: Country, actor: String, action: String, rank: RankDef, detail: String = ""): Phrase {
        Audit.record(c, actor, action, rank.id, detail)
        return Phrase.of("kami_claims.done.$action", v(rank.name))
    }

    private fun create(p: ServerPlayer, a: List<String>): Plan {
        val c = Service.need(p, Perm.CREATE_RANKS)
        val actor = p.stringUUID
        val name = RankRules.name(c, arg(a, 0))
        val color = int(a, 1)
        val perms = arg(a, 2).let { key -> if (key == BLANK) emptySet() else (RoleTemplate.entries.firstOrNull { it.key == key } ?: throw Fail("kami_claims.error.rank_template")).perms }
        RankRules.color(color)
        val index = RankRules.creatable(c, actor)
        RankRules.grantable(c, actor, perms)
        RankRules.criticalCap(c, null, perms)
        return Plan(Warnings.of(c, null, perms, emptySet())) {
            val rank = RankDef(ActionArgs.newId(), name, color, perms.toMutableSet())
            Housing.repriced(c) { c.ranks.add(index, rank) }
            done(c, actor, "rank_create", rank, PermCodec.diff(perms, emptySet()))
        }
    }

    private fun edit(p: ServerPlayer, a: List<String>): Plan {
        val c = Service.need(p, Perm.EDIT_RANKS)
        val actor = p.stringUUID
        val rank = RankRules.editable(c, actor, arg(a, 0))
        val name = RankRules.name(c, arg(a, 1), rank)
        val color = int(a, 2)
        RankRules.color(color)
        return Plan(emptyList()) {
            rank.name = name
            rank.color = color
            done(c, actor, "rank_edit", rank, name)
        }
    }

    private fun perms(p: ServerPlayer, a: List<String>): Plan {
        val c = Service.need(p, Perm.EDIT_RANKS)
        val actor = p.stringUUID
        val rank = RankRules.editable(c, actor, arg(a, 0))
        val perms = PermCodec.parse(a.getOrElse(1) { "" })
        val added = perms - rank.perms
        val removed = rank.perms - perms
        RankRules.grantable(c, actor, added)
        RankRules.criticalCap(c, rank, perms)
        RankRules.lockout(c, rank, perms)
        return Plan(Warnings.of(c, rank, added, removed)) {
            rank.perms.clear()
            rank.perms.addAll(perms)
            done(c, actor, "rank_perms", rank, PermCodec.diff(added, removed))
        }
    }

    private fun plots(p: ServerPlayer, a: List<String>): Plan {
        val c = Service.need(p, Perm.EDIT_RANKS)
        val actor = p.stringUUID
        val rank = RankRules.editable(c, actor, arg(a, 0))
        val plots = int(a, 1)
        RankRules.plots(plots)
        return Plan(emptyList()) {
            rank.plots = plots
            done(c, actor, "rank_plots", rank, plots.toString())
        }
    }

    private fun move(p: ServerPlayer, a: List<String>): Plan {
        val c = Service.need(p, Perm.REORDER_RANKS)
        val actor = p.stringUUID
        val rank = RankRules.editable(c, actor, arg(a, 0))
        val to = when (val direction = arg(a, 1)) {
            "up" -> RankRules.moved(c, actor, rank, true)
            "down" -> RankRules.moved(c, actor, rank, false)
            else -> RankRules.movedTo(c, actor, rank, direction.toIntOrNull() ?: throw Fail("kami_claims.error.rank_move"))
        }
        return Plan(emptyList()) {
            Housing.repriced(c) {
                c.ranks.remove(rank)
                c.ranks.add(to, rank)
            }
            done(c, actor, "rank_move", rank, to.toString())
        }
    }

    private fun delete(p: ServerPlayer, a: List<String>): Plan {
        val c = Service.need(p, Perm.CREATE_RANKS)
        val actor = p.stringUUID
        val rank = RankRules.editable(c, actor, arg(a, 0))
        val target = RankRules.successor(c, rank, a.getOrNull(1))
        RankRules.deletable(c, actor, rank, target)
        return Plan(Warnings.of(c, rank, emptySet(), rank.perms - target.perms)) {
            Housing.repriced(c) {
                RankRules.reassign(c, rank, target)
                c.ranks.remove(rank)
            }
            done(c, actor, "rank_delete", rank, target.id)
        }
    }

    private fun assign(p: ServerPlayer, a: List<String>): Plan {
        val c = Service.need(p, Perm.ASSIGN_RANKS)
        val actor = p.stringUUID
        val member = c.members[arg(a, 0)] ?: throw Fail("kami_claims.error.not_member")
        val dest = RankRules.rank(c, arg(a, 1))
        RankRules.assignable(c, actor, a[0], dest)
        return Plan(emptyList()) {
            Housing.repriced(c) { member.changeRank(dest.id, c) }
            Audit.record(c, actor, "rank_assign", a[0], dest.id)
            Phrase.of("kami_claims.done.rank_assign", v(dest.name))
        }
    }

    private fun group(p: ServerPlayer, a: List<String>): Plan {
        val c = Service.need(p, Perm.EDIT_GROUPS)
        val actor = p.stringUUID
        val claimant = Permissions.group(arg(a, 0))
        val perms = RankRules.groupPerms(a.getOrElse(1) { "" })
        val current = c.groups[claimant].orEmpty()
        val added = perms - current
        val removed = current - perms
        RankRules.grantable(c, actor, added)
        return Plan(Warnings.of(c, null, added, removed, claimant)) {
            c.groups[claimant] = perms.toMutableSet()
            Audit.record(c, actor, "group_perms", claimant.name, PermCodec.diff(added, removed))
            Phrase.of("kami_claims.done.group_perms")
        }
    }
}
