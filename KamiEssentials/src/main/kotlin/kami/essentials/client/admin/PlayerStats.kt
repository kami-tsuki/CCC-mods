package kami.essentials.client.admin

import kami.libs.admin.StatRow
import kami.libs.ui.app.StatBoard
import kami.libs.ui.app.StatLine
import kami.libs.ui.app.StatQuery
import kami.libs.ui.app.TimeRange
import kami.libs.ui.app.statChartCard
import kami.libs.ui.app.statDonutCard
import kami.libs.ui.app.statPage
import kami.libs.ui.app.statTopCard
import kami.libs.ui.app.total
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.text.trOr
import kami.libs.ui.widget.KpiTile

internal class PlayerStats(uuid: String) {
    private val subject = "p:$uuid"
    private val range = TimeRange("player-stats:range")
    private val board = StatBoard(range)
    private val playtime = board.total("session.time", subject)
    private val playtimeSeries = board.counted("session.time", subject)
    private val sessions = board.series("session.login", "count", subject)
    private val kills = board.total("combat.kill", subject)
    private val deaths = board.total("combat.death", subject)
    private val mobKills = board.total("mob.kill", subject)
    private val minedTotal = board.total("block.mine", subject)
    private val placedTotal = board.total("block.place", subject)
    private val craftedTotal = board.total("craft.item", subject)
    private val distance = board.total("move.dist", subject)
    private val trades = board.total("market.trade", subject)
    private val volume = board.total("market.volume", subject)
    private val tax = board.total("money.tax", subject, "out")
    private val mobs = board.top("mob.kill", "o", subject)
    private val mined = board.top("block.mine", "o", subject)
    private val placed = board.top("block.place", "o", subject)
    private val crafted = board.top("craft.item", "o", subject)
    private val dimensions = board.top("session.time", "o", subject)

    fun draw(ui: Ui, r: Rect) {
        fun number(q: StatQuery) = Format.compact(board[q].total.toLong())
        val tiles = listOf(
            KpiTile(tr("kami_essentials.admin.col.playtime"), Format.duration(board[playtime].total.toLong()), Icons.CLOCK),
            KpiTile(tr("kami_essentials.admin.stats.sessions"), Format.number(board[sessions].points.total().toLong()), Icons.PERSON),
            KpiTile(tr("kami_essentials.admin.stats.kills"), number(kills), Icons.SHIELD),
            KpiTile(tr("kami_essentials.admin.stats.deaths"), number(deaths), Icons.DANGER),
            KpiTile(tr("kami_essentials.admin.stats.mob_kills"), number(mobKills), Icons.FIRE),
            KpiTile(tr("kami_essentials.admin.stats.mined"), number(minedTotal), Icons.PICKAXE),
            KpiTile(tr("kami_essentials.admin.stats.placed"), number(placedTotal), Icons.BLOCK),
            KpiTile(tr("kami_essentials.admin.stats.crafted"), number(craftedTotal), Icons.TOOL),
            KpiTile(tr("kami_essentials.admin.stats.distance"), tr("kami_essentials.admin.stats.blocks", number(distance)), Icons.RULER),
            KpiTile(tr("kami_essentials.admin.stats.trade_count"), number(trades), Icons.HANDSHAKE),
            KpiTile(tr("kami_essentials.admin.stats.volume"), Format.money(board[volume].total.toLong(), compact = true), Icons.COIN, Palette.money),
            KpiTile(tr("kami_essentials.admin.stats.taxes_paid"), Format.money(board[tax].total.toLong(), compact = true), Icons.TAX, Palette.money)
        )
        val dims = board[dimensions].rows.map { StatRow(it.key, it.v, trOr("kami_essentials.admin.dim.${it.key.replace(':', '.')}", "")) }
        val cards = listOf(
            statChartCard(
                tr("kami_essentials.admin.col.playtime"), Icons.CLOCK, "player-stats:time",
                listOf(StatLine(tr("kami_essentials.admin.col.playtime"), board[playtimeSeries].points, Palette.chart[1], true)), { ms -> Format.duration(ms) }
            ),
            statDonutCard(tr("kami_essentials.admin.stats.dimensions"), Icons.GLOBE, "player-stats:dims", dims, { ms -> Format.duration(ms) }, false),
            statTopCard(tr("kami_essentials.admin.stats.mobs"), Icons.FIRE, board[mobs].rows),
            statTopCard(tr("kami_essentials.admin.stats.mined"), Icons.PICKAXE, board[mined].rows),
            statTopCard(tr("kami_essentials.admin.stats.placed"), Icons.BLOCK, board[placed].rows),
            statTopCard(tr("kami_essentials.admin.stats.crafted"), Icons.TOOL, board[crafted].rows)
        )
        ui.statPage("player-stats", r, range, board, tiles, cards)
    }
}
