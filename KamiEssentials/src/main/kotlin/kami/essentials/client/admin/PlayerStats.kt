package kami.essentials.client.admin

import kami.libs.admin.StatRow
import kami.libs.stats.Staff
import kami.libs.ui.app.StatBoard
import kami.libs.ui.app.StatCard
import kami.libs.ui.app.chartCard
import kami.libs.ui.app.StatQuery
import kami.libs.ui.app.TimeRange
import kami.libs.ui.app.topCard
import kami.libs.ui.app.statDonutCard
import kami.libs.ui.app.statPage
import kami.libs.ui.app.total
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.text.trOr
import kami.libs.ui.widget.KpiTile

internal class StatMetrics(private val board: StatBoard, private val subject: String, private val id: String, private val prefix: String = "") {
    private class Metric(val title: String, val icon: Icon, val query: StatQuery, val format: (Long) -> String, val color: Int)

    private fun metric(title: String, icon: Icon, key: String, obj: String = "", count: Boolean = false, color: Int = Palette.text, format: (Long) -> String = { Format.number(it) }) =
        Metric(tr(title), icon, if (count) board.series(prefix + key, "count", subject, obj) else board.counted(prefix + key, subject, obj), format, color)

    private val metrics = listOf(
        metric("kami_essentials.admin.col.playtime", Icons.CLOCK, "session.time", format = { Format.duration(it) }),
        metric("kami_essentials.admin.stats.sessions", Icons.PERSON, "session.login", count = true, format = { Format.number(it) }),
        metric("kami_essentials.admin.stats.kills", Icons.SHIELD, "combat.kill"),
        metric("kami_essentials.admin.stats.deaths", Icons.DANGER, "combat.death"),
        metric("kami_essentials.admin.stats.mob_kills", Icons.FIRE, "mob.kill"),
        metric("kami_essentials.admin.stats.dealt", Icons.SHIELD, "combat.dealt", format = { Format.number(it / 10) }),
        metric("kami_essentials.admin.stats.taken", Icons.DANGER, "combat.taken", format = { Format.number(it / 10) }),
        metric("kami_essentials.admin.stats.mined", Icons.PICKAXE, "block.mine"),
        metric("kami_essentials.admin.stats.placed", Icons.BLOCK, "block.place"),
        metric("kami_essentials.admin.stats.crafted", Icons.TOOL, "craft.item"),
        metric("kami_essentials.admin.stats.smelted", Icons.FIRE, "craft.smelt"),
        metric("kami_essentials.admin.stats.distance", Icons.RULER, "move.dist", format = { tr("kami_essentials.admin.stats.blocks", Format.number(it)) }),
        metric("kami_essentials.admin.stats.trade_count", Icons.HANDSHAKE, "market.trade"),
        metric("kami_essentials.admin.stats.volume", Icons.COIN, "market.volume", color = Palette.money, format = { Format.money(it) }),
        metric("kami_essentials.admin.stats.taxes_paid", Icons.TAX, "market.tax", color = Palette.money, format = { Format.money(it) })
    )
    private val mobs = board.top(prefix + "mob.kill", "o", subject)
    private val mined = board.top(prefix + "block.mine", "o", subject)
    private val placed = board.top(prefix + "block.place", "o", subject)
    private val crafted = board.top(prefix + "craft.item", "o", subject)
    private val smelted = board.top(prefix + "craft.smelt", "o", subject)
    private val causes = board.top(prefix + "combat.death", "o", subject)
    private val dimensions = board.top(prefix + "session.time", "o", subject)

    fun tiles() = metrics.map { KpiTile(it.title, it.format(board[it.query].points.total().toLong()), it.icon, it.color) }

    fun charts() = metrics.mapIndexed { i, m ->
        board.chartCard(m.title, m.icon, "$id:$i", m.query, if (m.color == Palette.text) Palette.chart[1] else m.color, format = m.format)
    }

    fun objects(): List<StatCard> {
        val dims = board[dimensions].rows.map { StatRow(it.key, it.v, trOr("kami_essentials.admin.dim.${it.key.replace(':', '.')}", "")) }
        return listOf(
            statDonutCard(tr("kami_essentials.admin.stats.dimensions"), Icons.GLOBE, "$id:dims", dims, { Format.duration(it) }, false),
            board.topCard(tr("kami_essentials.admin.stats.mobs"), Icons.FIRE, mobs),
            board.topCard(tr("kami_essentials.admin.stats.death_causes"), Icons.DANGER, causes),
            board.topCard(tr("kami_essentials.admin.stats.mined"), Icons.PICKAXE, mined),
            board.topCard(tr("kami_essentials.admin.stats.placed"), Icons.BLOCK, placed),
            board.topCard(tr("kami_essentials.admin.stats.crafted"), Icons.TOOL, crafted),
            board.topCard(tr("kami_essentials.admin.stats.smelted"), Icons.FIRE, smelted)
        )
    }
}

internal class PlayerStats(uuid: String, prefix: String = "") {
    private val id = if (prefix.isEmpty()) "player-stats" else "player-staff"
    private val range = TimeRange("$id:range")
    private val board = StatBoard(range)
    private val metrics = StatMetrics(board, "p:$uuid", id, prefix)
    private val commands = board.top(Staff.PREFIX + "command", "o", "p:$uuid")

    fun draw(ui: Ui, r: Rect) {
        val extra = if (id == "player-staff") listOf(board.topCard(tr("kami_essentials.admin.staff.commands"), Icons.SCROLL, commands)) else emptyList()
        ui.statPage(id, r, range, board, metrics.tiles(), extra + metrics.charts() + metrics.objects())
    }
}
