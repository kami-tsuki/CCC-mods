package kami.essentials.client.admin

import kami.essentials.client.label
import kami.essentials.skills.SkillClassStat
import kami.essentials.skills.SkillIssue
import kami.essentials.skills.SkillPlayer
import kami.essentials.skills.SkillRecent
import kami.essentials.skills.SkillRow
import kami.essentials.skills.SkillStatsDto
import kami.libs.admin.StatPoint
import kami.libs.admin.StatRow
import kami.libs.skills.SkillClass
import kami.libs.ui.app.Page
import kami.libs.ui.app.Remote
import kami.libs.ui.app.STAT_CHART_H
import kami.libs.ui.app.STAT_TOP_H
import kami.libs.ui.app.StatBoard
import kami.libs.ui.app.StatCard
import kami.libs.ui.app.StatLine
import kami.libs.ui.app.TimeRange
import kami.libs.ui.app.sliceDonut
import kami.libs.ui.app.statChartCard
import kami.libs.ui.app.statPage
import kami.libs.ui.app.statTopCard
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.text.trOr
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.KpiTile
import kami.libs.ui.widget.Series
import kami.libs.ui.widget.Slice
import kami.libs.ui.widget.TableState
import kami.libs.ui.widget.card
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.legend
import kami.libs.ui.widget.lineChart
import kami.libs.ui.widget.stackedBar
import kami.libs.ui.widget.table
import kotlin.math.min

private const val REFRESH_MS = 15_000L
private const val BAR_H = 12
private const val LABEL_W = 56
private const val MILLI = 1000.0
private const val SOURCE_LEGEND = 8
private const val PLAYTIME = "playtime"

private fun clsLabel(id: String) = SkillClass.of(id)?.label() ?: id

private fun clsColor(id: String) = SkillClass.of(id)?.let { Palette.opaque(it.color) } ?: Palette.textMuted

private fun nameText(name: String) = tr("kami_essentials.skill.name.$name")

private fun nodeText(key: String) = key.split('|').let { if (it.size < 2) key else tr("kami_libs.format.paren", nameText(it[1]), clsLabel(it[0])) }

private fun nodeRows(rows: List<SkillRow>) = rows.map { StatRow(it.key, it.v, nodeText(it.key)) }

private fun sourceText(source: String) = trOr("kami_essentials.admin.skills.src.$source", source.replaceFirstChar { it.uppercase() })

private fun sourceRows(rows: List<SkillRow>) = rows.map { StatRow(it.key, it.v, sourceText(it.key)) }

private fun seconds(v: Long) = Format.duration(v * 1000)

private fun perHour(v: Long) = tr("kami_essentials.admin.skills.per_hour", Format.number(v))

private fun issueText(i: SkillIssue) = when (i.kind) {
    "xph", "lost" -> tr("kami_essentials.admin.skills.issue.${i.kind}", clsLabel(i.a), i.b)
    "fork" -> tr("kami_essentials.admin.skills.issue.fork", nodeText(i.a), i.b)
    else -> tr("kami_essentials.admin.skills.issue.unowned", i.a, nodeText(i.b))
}

private fun levelSeries(rows: List<SkillRow>, label: String, color: Int): Series {
    val by = rows.associate { it.key.toInt() to it.v }
    val from = by.keys.minOrNull() ?: 1
    var last = 0L
    return Series(label, (from..(by.keys.maxOrNull() ?: 0)).map { l -> by[l]?.also { last = it } ?: last }, color, start = from - 1)
}

private fun useText(key: String) = key.split('|').let { p ->
    if (p.size < 2) key else tr("kami_libs.format.paren", trOr("kami_essentials.admin.skills.use.${p[1]}", p[1].replace('_', ' ').replaceFirstChar { it.uppercase() }), clsLabel(p[0]))
}

private fun rate(c: SkillClassStat) = c.sources.filter { it.key != PLAYTIME }.sumOf { it.v } * 3600 / c.active

private fun curveCard(title: String, series: List<Series>, key: String) = StatCard(STAT_CHART_H + 40) { r ->
    val body = card(r, title, Icons.CLOCK)
    val chart = body.dropTop(legend(body.x, body.y, body.w, series.map { it.color to it.label }) + 2)
    lineChart(chart, series, (1..(series.maxOfOrNull { it.start + it.values.size } ?: 0)).map { it.toString() }, ::seconds, key = key)
}

private fun issuesCard(issues: List<SkillIssue>) = StatCard(STAT_TOP_H) { r ->
    val body = card(r, tr("kami_essentials.admin.skills.issues"), Icons.WARNING)
    if (issues.isEmpty()) Draw.textCentered(g, tr("kami_essentials.admin.skills.issues.none"), body, Palette.success)
    else issues.take((body.h / Draw.LINE).coerceAtLeast(1)).forEachIndexed { i, x -> fitText(body.x, body.y + i * Draw.LINE, body.w, issueText(x), Palette.warning, key = "skill-issue:$i") }
}

private fun sourcesCard(classes: List<SkillClassStat>): StatCard {
    val names = classes.flatMap { it.sources }.groupBy({ it.key }, { it.v }).mapValues { it.value.sum() }.entries.sortedByDescending { it.value }.take(SOURCE_LEGEND).map { it.key }.sorted()
    val colors = names.withIndex().associate { (i, n) -> n to Palette.chart[i % Palette.chart.size] }
    val bars = classes.filter { it.sources.isNotEmpty() }.map { c -> c to c.sources.sortedBy { it.key }.map { Slice(sourceText(it.key), it.v, colors[it.key] ?: Palette.textMuted) } }
    return StatCard(STAT_TOP_H + 60) { r ->
        val body = card(r, tr("kami_essentials.admin.skills.src"), Icons.STAR)
        if (bars.isEmpty()) Draw.textCentered(g, tr("kami_libs.chart.no_data"), body, Palette.textMuted)
        else {
            val rows = body.dropTop(legend(body.x, body.y, body.w, names.map { colors.getValue(it) to sourceText(it) }) + 4)
            bars.forEachIndexed { i, (c, slices) ->
                val y = rows.y + i * (BAR_H + 1)
                fitText(rows.x, y + 2, LABEL_W - 2, clsLabel(c.cls), clsColor(c.cls), key = "skill-src-label:$i")
                stackedBar(Rect(rows.x + LABEL_W, y, rows.w - LABEL_W, BAR_H - 2), slices, "skill-src:${c.cls}")
            }
        }
    }
}

private fun splitCard(title: String, icon: Icon, rows: List<SkillRow>, key: String): StatCard {
    val sorted = rows.sortedBy { if (it.v + it.w == 0L) 1.0 else min(it.v, it.w).toDouble() / (it.v + it.w) }
    return StatCard(STAT_TOP_H) { r ->
        val body = card(r, title, icon)
        if (sorted.isEmpty()) Draw.textCentered(g, tr("kami_libs.chart.no_data"), body, Palette.textMuted)
        else sorted.take((body.h / BAR_H).coerceAtLeast(1)).forEachIndexed { i, row ->
            val p = row.key.split('|')
            val bar = Rect(body.x, body.y + i * BAR_H, body.w, BAR_H - 1)
            val a = nameText(p[1])
            val b = nameText(p[2])
            stackedBar(bar, listOf(Slice(a, row.v, Palette.alpha(clsColor(p[0]), 0xB0)), Slice(b, row.w, Palette.alpha(Palette.textMuted, 0x90))), "$key:$i")
            fitText(bar.x + 2, bar.y + 1, bar.w - 4, tr("kami_essentials.admin.skills.versus", a, b), Palette.text, key = "$key-text:$i")
        }
    }
}

internal class SkillStatsPage : Page() {
    override val title get() = tr("kami_essentials.admin.skills")
    private val range = TimeRange("skills:range")
    private val board = StatBoard(range)
    private val xp = SkillClass.entries.map { it to board.counted("skill.xp.cls", obj = it.id) }
    private val members = SkillClass.entries.map { it to board.series("skill.g.class", "max", it.id) }
    private val means = SkillClass.entries.map { it to board.series("skill.g.avg", "avg", it.id) }
    private val remote = Remote("skills.stats", SkillStatsDto.serializer(), REFRESH_MS) { listOf(range.from?.toString().orEmpty()) }
    private val tileMemo = Memo()
    private val cardMemo = Memo()

    override fun draw(ui: Ui, r: Rect) {
        remote.poll()
        val d = remote.data ?: remote.latest
        val chart = board.remote.data ?: board.remote.latest
        ui.statPage("skills", r, range, board, tileMemo.of(d, Format.locale) { tiles(d) }, cardMemo.of(d, chart, Format.locale) { cards(d) })
    }

    private fun tiles(d: SkillStatsDto?): List<KpiTile> {
        d ?: return emptyList()
        val xpTotal = d.classes.sumOf { it.xp }
        val lost = d.classes.sumOf { it.lost }
        val hours = d.classes.sumOf { it.active } / 3600.0
        val xpActive = d.classes.sumOf { c -> c.sources.filter { it.key != PLAYTIME }.sumOf { it.v } }
        return listOf(
            KpiTile(tr("kami_essentials.admin.skills.tile.players"), "${d.classed} / ${d.players}", Icons.PEOPLE),
            KpiTile(tr("kami_essentials.admin.skills.tile.xp"), Format.number(xpTotal), Icons.STAR),
            KpiTile(tr("kami_essentials.admin.skills.tile.xph"), if (hours > 0) perHour((xpActive / hours).toLong()) else "-", Icons.CLOCK),
            KpiTile(tr("kami_essentials.admin.skills.tile.lost"), Format.percent(if (xpTotal + lost > 0) lost.toDouble() / (xpTotal + lost) else 0.0), Icons.PERCENT),
            KpiTile(tr("kami_essentials.admin.skills.tile.unowned"), Format.number(d.unowned.toLong()), Icons.TREE, if (d.unowned > 0) Palette.warning else Palette.text),
            KpiTile(tr("kami_essentials.admin.skills.tile.issues"), Format.number(d.issues.size.toLong()), Icons.WARNING, if (d.issues.isEmpty()) Palette.success else Palette.warning)
        )
    }

    private fun cards(d: SkillStatsDto?): List<StatCard> {
        val lines = xp.map { (c, q) -> StatLine(c.label(), board[q].points.map { StatPoint(it.at, it.v / MILLI) }, Palette.opaque(c.color)) }
        val chart = statChartCard(tr("kami_essentials.admin.skills.xp"), Icons.STAR, "skills:xp", lines)
        val trend = statChartCard(tr("kami_essentials.admin.skills.trend"), Icons.PEOPLE, "skills:trend", members.map { (c, q) -> StatLine(c.label(), board[q].points.map { StatPoint(it.at, it.v) }, Palette.opaque(c.color)) })
        val trendAvg = statChartCard(tr("kami_essentials.admin.skills.trend_avg"), Icons.STAR, "skills:trend-avg", means.map { (c, q) -> StatLine(c.label(), board[q].points.map { StatPoint(it.at, it.v) }, Palette.opaque(c.color)) }) { Format.decimal(it / 100.0) }
        d ?: return listOf(chart, trend, trendAvg)
        val slices = d.classes.filter { it.players > 0 }.map { Slice(clsLabel(it.cls), it.players.toLong(), clsColor(it.cls)) }
        val rates = d.classes.filter { it.active > 0 }.map { StatRow(it.cls, rate(it), clsLabel(it.cls)) }.sortedByDescending { it.v }
        val shares = d.classes.filter { it.xp + it.lost > 0 }.map { StatRow(it.cls, it.lost * 1000 / (it.xp + it.lost), clsLabel(it.cls)) }.sortedByDescending { it.v }
        val levels = d.classes.filter { it.players > 0 }.map { StatRow(it.cls, (it.avgLevel * 10).toLong(), clsLabel(it.cls)) }.sortedByDescending { it.v }
        val curves = d.classes.filter { d.curves[it.cls] != null }.map { c -> d.curves.getValue(c.cls).let { Series(clsLabel(c.cls), it.values, clsColor(c.cls), start = it.from - 1) } } + Series(tr("kami_essentials.admin.skills.gate"), d.gate, Palette.textMuted)
        return listOf(
            issuesCard(d.issues),
            StatCard(STAT_TOP_H) { sliceDonut(it, tr("kami_essentials.admin.skills.classes"), Icons.PEOPLE, slices, "skills:classes") },
            chart,
            trend,
            trendAvg,
            statTopCard(tr("kami_essentials.admin.skills.xph"), Icons.CLOCK, rates, ::perHour),
            sourcesCard(d.classes),
            curveCard(tr("kami_essentials.admin.skills.curve"), curves, "skills:curve"),
            statTopCard(tr("kami_essentials.admin.skills.avg"), Icons.STAR, levels) { Format.decimal(it / 10.0) },
            statTopCard(tr("kami_essentials.admin.skills.lost"), Icons.PERCENT, shares) { Format.percent(it / 1000.0, 1) },
            statTopCard(tr("kami_essentials.admin.skills.lost_by"), Icons.WARNING, sourceRows(d.lostBy)),
            statTopCard(tr("kami_essentials.admin.skills.top"), Icons.CROWN, nodeRows(d.top)),
            statTopCard(tr("kami_essentials.admin.skills.least"), Icons.TREE, nodeRows(d.least)),
            splitCard(tr("kami_essentials.admin.skills.forks"), Icons.CHAIN, d.forks, "skills:forks"),
            splitCard(tr("kami_essentials.admin.skills.keystones"), Icons.CROWN, d.keystones, "skills:keystones"),
            statTopCard(tr("kami_essentials.admin.skills.bonus"), Icons.GEAR, nodeRows(d.bonus)),
            statTopCard(tr("kami_essentials.admin.skills.uses"), Icons.CHECK, d.uses.map { StatRow(it.key, it.v, useText(it.key)) }),
            statTopCard(tr("kami_essentials.admin.skills.choices"), Icons.CHECK, d.choices.map { StatRow(it.key, it.v, clsLabel(it.key)) }),
            statTopCard(tr("kami_essentials.admin.skills.takes"), Icons.ADD, nodeRows(d.takes)),
            statTopCard(tr("kami_essentials.admin.skills.refunds"), Icons.UNDO, nodeRows(d.refunds))
        )
    }
}

internal class PlayerSkills(private val uuid: String) {
    private val range = TimeRange("player-skills:range")
    private val board = StatBoard(range)
    private val xp = board.counted("skill.xp.cls", "p:$uuid")
    private val remote = Remote("skills.player", SkillPlayer.serializer(), REFRESH_MS) { listOf(uuid, range.from?.toString().orEmpty()) }
    private val tileMemo = Memo()
    private val cardMemo = Memo()
    private val nodeTable = TableState<SkillRow>()
    private val recentTable = TableState<SkillRecent>()

    fun draw(ui: Ui, r: Rect) {
        remote.poll()
        val d = remote.data ?: remote.latest
        val chart = board.remote.data ?: board.remote.latest
        ui.statPage("player-skills", r, range, board, tileMemo.of(d, Format.locale) { tiles(d) }, cardMemo.of(d, chart, Format.locale) { cards(d) })
    }

    private fun tiles(d: SkillPlayer?): List<KpiTile> {
        d ?: return emptyList()
        return listOf(
            KpiTile(tr("kami_essentials.skill.class"), d.cls?.let(::clsLabel) ?: tr("kami_libs.common.none"), Icons.TREE, d.cls?.let(::clsColor) ?: Palette.text),
            KpiTile(tr("kami_libs.common.level"), Format.number(d.level.toLong()), Icons.STAR),
            KpiTile(tr("kami_essentials.admin.skills.tile.xp"), Format.number(d.xp), Icons.STAR),
            KpiTile(tr("kami_essentials.admin.skills.tile.active"), seconds(d.active), Icons.CLOCK),
            KpiTile(tr("kami_essentials.admin.skills.tile.points"), "${d.points - d.spent} / ${d.spent}", Icons.GEAR),
            KpiTile(tr("kami_essentials.admin.skills.tile.refunds"), "${d.refunds} / ${d.refundsMax}", Icons.UNDO)
        )
    }

    private fun cards(d: SkillPlayer?): List<StatCard> {
        val gained = statChartCard(tr("kami_essentials.admin.skills.xp_time"), Icons.STAR, "player-skills:xp", listOf(StatLine(tr("kami_essentials.admin.skills.xp_time"), board[xp].points.map { StatPoint(it.at, it.v / MILLI) }, Palette.chart[0], true)))
        d ?: return listOf(gained)
        val history = levelSeries(d.history, tr("kami_essentials.admin.skills.history"), Palette.chart[1])
        val nodeColumns = listOf(
            Column.text<SkillRow>(tr("kami_essentials.admin.skills.col.node"), 110) { nodeText(it.key) },
            Column.text<SkillRow>(tr("kami_essentials.admin.skills.col.kind"), 50) { trOr("kami_essentials.admin.skills.kind.${it.key.substringAfterLast('|')}", it.key.substringAfterLast('|')) },
            Column.number<SkillRow>(tr("kami_essentials.admin.skills.col.rank"), 30) { it.v }
        )
        val recentColumns = listOf(
            Column.number<SkillRecent>(tr("kami_libs.common.time"), 50, format = { Format.ago(it) }) { it.at },
            Column.text<SkillRecent>(tr("kami_libs.common.type"), 40, color = { if (it.take) Palette.success else Palette.danger }) { tr(if (it.take) "kami_essentials.admin.skills.take" else "kami_essentials.admin.skills.refund") },
            Column.text<SkillRecent>(tr("kami_essentials.admin.skills.col.node"), 100) { nodeText(it.key) },
            Column.number<SkillRecent>(tr("kami_essentials.admin.skills.col.rank"), 30) { it.rank }
        )
        return listOf(
            gained,
            curveCard(tr("kami_essentials.admin.skills.history"), listOf(history, Series(tr("kami_essentials.admin.skills.gate"), d.gate, Palette.textMuted)), "player-skills:history"),
            statTopCard(tr("kami_essentials.admin.skills.src"), Icons.STAR, sourceRows(d.sources)),
            statTopCard(tr("kami_essentials.admin.skills.lost_by"), Icons.WARNING, sourceRows(d.lost)),
            StatCard(STAT_TOP_H * 2) { table(card(it, tr("kami_essentials.admin.skills.nodes"), Icons.TREE), nodeColumns, d.nodes, nodeTable, { row -> row.key }, emptyText = tr("kami_essentials.admin.skills.empty"), key = "player-skill-nodes") },
            StatCard(STAT_TOP_H * 2) { table(card(it, tr("kami_essentials.admin.skills.recent"), Icons.SCROLL), recentColumns, d.recent, recentTable, { row -> "${row.at}:${row.key}" }, emptyText = tr("kami_essentials.admin.skills.empty"), key = "player-skill-recent") }
        )
    }
}
