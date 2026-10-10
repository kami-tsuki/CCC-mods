package kami.essentials.skills

import kami.essentials.Config
import kami.essentials.admin.AdminServer
import kami.libs.admin.Admin
import kami.libs.admin.long
import kami.libs.db.Db.encode
import kami.libs.skills.SkillClass
import kami.libs.stats.Stats
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Serializable
class SkillRow(val key: String, val v: Long, val w: Long = 0)

@Serializable
class SkillClassStat(val cls: String, val players: Int, val avgLevel: Double, val xp: Long, val lost: Long, val active: Long, val sources: List<SkillRow>)

@Serializable
class SkillCurve(val from: Int, val values: List<Long>)

@Serializable
class SkillIssue(val kind: String, val a: String = "", val b: String = "")

@Serializable
class SkillStatsDto(
    val players: Int, val classed: Int, val classes: List<SkillClassStat>, val curves: Map<String, SkillCurve>, val gate: List<Long>,
    val top: List<SkillRow>, val least: List<SkillRow>, val unowned: Int, val forks: List<SkillRow>, val keystones: List<SkillRow>, val bonus: List<SkillRow>,
    val choices: List<SkillRow>, val takes: List<SkillRow>, val refunds: List<SkillRow>, val lostBy: List<SkillRow>, val uses: List<SkillRow>, val issues: List<SkillIssue>
)

@Serializable
class SkillRecent(val at: Long, val take: Boolean, val key: String, val rank: Long)

@Serializable
class SkillPlayer(
    val cls: String?, val level: Int, val xp: Long, val active: Long, val points: Int, val spent: Int, val refunds: Int, val refundsMax: Int,
    val gate: List<Long>, val sources: List<SkillRow>, val lost: List<SkillRow>, val history: List<SkillRow>, val nodes: List<SkillRow>, val recent: List<SkillRecent>
)

object SkillStats {
    private const val EVENTS = 5000
    private const val CURVE_EVENTS = 20000
    private const val RECENT = 30
    private const val ROWS = 10
    private const val MILLI = 1000L
    private const val MIN_ACTIVE = 36000L
    private const val DEVIATION = 0.4
    private const val SIDE_MIN = 0.15
    private const val SIDE_SAMPLE = 10
    private const val LOST_MAX = 0.3
    private const val UNOWNED_PLAYERS = 10
    private const val MIN_PLAYERS = 3
    private const val PLAYTIME = "playtime"
    private const val LOST_MIN_XP = 5000L
    private const val USES_PER_CLASS = 2

    private class Live(val levels: Map<String, List<Int>>, val owners: IntArray, val total: Int)

    private class Own(val cls: String?, val level: Int, val xp: Long, val active: Long, val spent: Int, val refunds: Int, val nodes: List<SkillRow>)

    fun register() {
        Admin.view("skills.stats", async = true) { ctx, a -> encode(SkillStatsDto.serializer(), global(ctx.onServer { live() }, a.long(0))) }
        Admin.view("skills.player", async = true) { ctx, a ->
            val id = AdminServer.target(ctx, a)
            encode(SkillPlayer.serializer(), player(id, ctx.onServer { own(id) }, a.long(1)))
        }
    }

    private fun live(): Live {
        val all = SkillServer.all().values
        return Live(all.mapNotNull { s -> s.cls?.let { it.id to s.level } }.groupBy({ it.first }, { it.second }), SkillServer.owners(), all.size)
    }

    private fun own(id: UUID): Own? = SkillServer.peek(id)?.let { s ->
        val nodes = s.nodes.entries.mapNotNull { (i, r) ->
            SkillTree.nodes.getOrNull(i)?.takeIf { it.kind != NodeKind.START && it.kind != NodeKind.TRAVEL }?.let { "${it.cls.id}|${it.name}|${it.kind.name.lowercase()}" to r }
        }.groupBy({ it.first }, { it.second }).map { (k, v) -> SkillRow(k, v.sum().toLong(), v.size.toLong()) }.sortedByDescending { it.v }
        Own(s.cls?.id, s.level, s.xp.toLong(), s.active, SkillTree.spent(s.nodes, s.cls), s.refunds, nodes)
    }

    private fun grouped(key: String, from: Long?) = Stats.top(key, from, "o", null, 600).groupBy({ it.key.substringBefore(':') }, { SkillRow(it.key.substringAfter(':'), it.v) })

    private fun node(o: String) = o.split('|').let { if (it.size == 3) "${it[2]}|${it[0]}" else o }

    private fun counted(key: String, from: Long?) = Stats.top(key, from, "o", null, ROWS).map { SkillRow(it.key, it.v) }

    private fun choices(from: Long?) =
        Stats.events("skill.class", from, null, EVENTS).groupingBy { it.second }.eachCount().entries.sortedByDescending { it.value }.map { SkillRow(it.key, it.value.toLong()) }

    private fun percent(x: Double) = (x * 100).roundToInt().let { if (it > 0) "+$it" else "$it" }

    private fun key(n: SkillNode) = "${n.cls.id}|${n.name}"

    private fun curve(list: List<Triple<String, Int, Long>>): SkillCurve {
        val by = list.groupBy({ it.second }, { it.third }).mapValues { it.value.average().toLong() }
        val from = by.keys.min()
        var last = 0L
        return SkillCurve(from, (from..by.keys.max()).map { l -> by[l]?.also { last = it } ?: last })
    }

    private fun uses(from: Long?) = Stats.top("skill.use", from, "o", null, 200).groupBy { it.key.substringBefore('|') }.values
        .flatMap { rows -> rows.sortedByDescending { it.v }.take(USES_PER_CLASS) }.sortedByDescending { it.v }.map { SkillRow(it.key, it.v) }

    private fun issues(classes: List<SkillClassStat>, splits: List<SkillRow>, unowned: List<SkillNode>) = buildList<SkillIssue> {
        val rates = classes.filter { it.active >= MIN_ACTIVE && it.players >= MIN_PLAYERS }.associate { c -> c.cls to c.sources.filter { it.key != PLAYTIME }.sumOf { it.v } * 3600.0 / c.active }
        if (rates.size >= 3) {
            val sorted = rates.values.sorted()
            val median = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
            if (median > 0) rates.forEach { (c, r) -> if (abs(r / median - 1) > DEVIATION) add(SkillIssue("xph", c, percent(r / median - 1))) }
        }
        classes.forEach { c -> if (c.players >= MIN_PLAYERS && c.xp + c.lost >= LOST_MIN_XP && c.lost.toDouble() / (c.xp + c.lost) > LOST_MAX) add(SkillIssue("lost", c.cls, (c.lost * 100 / (c.xp + c.lost)).toString())) }
        splits.forEach { r ->
            val total = r.v + r.w
            val parts = r.key.split('|')
            if (total >= SIDE_SAMPLE && min(r.v, r.w).toDouble() / total < SIDE_MIN) add(SkillIssue("fork", "${parts[0]}|${parts[if (r.v < r.w) 1 else 2]}", (min(r.v, r.w) * 100 / total).toString()))
        }
        if (unowned.isNotEmpty()) add(SkillIssue("unowned", unowned.size.toString(), key(unowned.first())))
    }

    private fun global(live: Live, from: Long?): SkillStatsDto {
        val src = grouped("skill.xp.src", from)
        val lost = grouped("skill.xp.lost", from)
        val active = Stats.top("skill.active", from, "o", null, 20).associate { it.key to it.v }
        val classes = SkillClass.entries.map { c ->
            val levels = live.levels[c.id].orEmpty()
            val rows = src[c.id].orEmpty()
            SkillClassStat(c.id, levels.size, if (levels.isEmpty()) 0.0 else levels.average(), rows.sumOf { it.v } / MILLI, lost[c.id].orEmpty().sumOf { it.v } / MILLI, active[c.id] ?: 0, rows.map { SkillRow(it.key, it.v / MILLI) }.sortedByDescending { it.v })
        }
        val curves = Stats.events("skill.level", from, null, CURVE_EVENTS).mapNotNull { (_, o, v) -> o.substringAfter(':').toIntOrNull()?.let { Triple(o.substringBefore(':'), it, v) } }
            .groupBy { it.first }.mapValues { curve(it.value) }
        val gate = (1..min(Config.s.skills.maxLevel, max(10, (curves.values.maxOfOrNull { it.from - 1 + it.values.size } ?: 0) + 5))).map { Curve.gate(it) }
        val owners = live.owners
        val tree = SkillTree.nodes
        val ranked = tree.filter { it.kind in SkillTree.listed }.map { it to owners[it.id].toLong() }
        val owned = ranked.filter { it.second > 0 }.map { SkillRow(key(it.first), it.second) }
        val reached = live.levels.filterValues { it.size >= UNOWNED_PLAYERS }.mapValues { it.value.max() }
        val unowned = ranked.filter { (n, c) -> c == 0L && (n.kind == NodeKind.NOTABLE || n.kind == NodeKind.BONUS) && n.alt < 0 && (reached[n.cls.id] ?: -1) >= (n.gate?.level(1) ?: 0) }.map { it.first }
        fun pairs(kind: NodeKind) = tree.filter { it.kind == kind && it.alt > it.id }.map { SkillRow("${it.cls.id}|${it.name}|${tree[it.alt].name}", owners[it.id].toLong(), owners[it.alt].toLong()) }
        val forks = pairs(NodeKind.CORE)
        val keystones = pairs(NodeKind.KEYSTONE)
        val lostBy = lost.values.flatten().groupBy({ it.key }, { it.v }).map { (k, v) -> SkillRow(k, v.sum() / MILLI) }.sortedByDescending { it.v }.take(ROWS)
        return SkillStatsDto(
            live.total, live.levels.values.sumOf { it.size }, classes, curves, gate,
            owned.sortedByDescending { it.v }.take(ROWS), owned.sortedBy { it.v }.take(ROWS), unowned.size, forks, keystones,
            ranked.filter { it.first.kind == NodeKind.BONUS }.map { SkillRow(key(it.first), it.second) }.sortedByDescending { it.v },
            choices(from), counted("skill.take", from), counted("skill.refund", from), lostBy, uses(from), issues(classes, forks + keystones, unowned)
        )
    }

    private fun player(id: UUID, own: Own?, from: Long?): SkillPlayer {
        val subject = "p:$id"
        fun by(key: String) = Stats.top(key, from, "o", subject, 100).groupBy({ it.key.substringAfter(':') }, { it.v }).map { (k, v) -> SkillRow(k, v.sum() / MILLI) }.sortedByDescending { it.v }
        val recent = listOf(true to "skill.take", false to "skill.refund").flatMap { (take, key) -> Stats.events(key, from, subject, RECENT).map { (at, o, v) -> SkillRecent(at, take, node(o), v) } }
            .sortedByDescending { it.at }.take(RECENT)
        val history = Stats.events("skill.level", from, subject, 400).mapNotNull { (_, o, v) -> o.substringAfter(':').toIntOrNull()?.let { SkillRow(it.toString(), v) } }.sortedBy { it.key.toInt() }
        val level = own?.level ?: 0
        return SkillPlayer(own?.cls, level, own?.xp ?: 0, own?.active ?: 0, Curve.points(level), own?.spent ?: 0, own?.refunds ?: 0, Curve.refunds(level), (1..min(Config.s.skills.maxLevel, max(10, level + 5))).map { Curve.gate(it) }, by("skill.xp.src"), by("skill.xp.lost"), history, own?.nodes.orEmpty(), recent)
    }
}
