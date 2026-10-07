package kami.essentials.client.admin

import kami.libs.ui.app.Page
import kami.libs.ui.app.StatBoard
import kami.libs.ui.app.StatQuery
import kami.libs.ui.app.TimeRange
import kami.libs.ui.app.statPage
import kami.libs.ui.app.statTopCard
import kami.libs.ui.app.total
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.KpiTile

internal fun average(total: Double, count: Double) = if (count > 0) total / count else 0.0

internal class StatisticsPage : Page() {
    override val title get() = tr("kami_essentials.admin.nav.statistics")
    private val range = TimeRange("statistics:range")
    private val board = StatBoard(range)
    private val metrics = StatMetrics(board, "", "statistics")
    private val players = board.distinct("server.ping")
    private val playtime = board.total("session.time")
    private val playtimers = board.distinct("session.time")
    private val joined = board.series("session.login", "count", obj = "new")
    private val tradeTotal = board.total("market.trade")
    private val traders = board.distinct("market.trade")
    private val taxTotal = board.total("market.tax")
    private val payers = board.distinct("market.tax")
    private val topPlaytime = board.top("session.time")
    private val topKillers = board.top("combat.kill")
    private val topDeaths = board.top("combat.death")
    private val topMobs = board.top("mob.kill")
    private val topMiners = board.top("block.mine")
    private val walkers = board.top("move.dist")
    private val topTraders = board.top("market.trade")

    override fun draw(ui: Ui, r: Rect) {
        val head = listOf(
            KpiTile(tr("kami_essentials.admin.stats.unique"), Format.number(board[players].total.toLong()), Icons.PEOPLE),
            KpiTile(tr("kami_essentials.admin.stats.new"), Format.number(board[joined].points.total().toLong()), Icons.INVITE),
            KpiTile(tr("kami_essentials.admin.stats.playtime"), Format.duration(average(board[playtime].total, board[playtimers].total).toLong()), Icons.CLOCK),
            KpiTile(tr("kami_essentials.admin.stats.trades"), Format.decimal(average(board[tradeTotal].total, board[traders].total)), Icons.HANDSHAKE),
            KpiTile(tr("kami_essentials.admin.stats.taxes"), Format.money(average(board[taxTotal].total, board[payers].total).toLong()), Icons.TAX, Palette.money)
        )
        val tops = listOf(
            top(tr("kami_essentials.admin.stats.top_playtime"), Icons.CLOCK, topPlaytime) { Format.duration(it) },
            top(tr("kami_essentials.admin.stats.top_killers"), Icons.CROWN, topKillers),
            top(tr("kami_essentials.admin.stats.top_deaths"), Icons.DANGER, topDeaths),
            top(tr("kami_essentials.admin.stats.top_mob_killers"), Icons.FIRE, topMobs),
            top(tr("kami_essentials.admin.stats.top_miners"), Icons.PICKAXE, topMiners),
            top(tr("kami_essentials.admin.stats.walkers"), Icons.RULER, walkers) { Format.number(it) },
            top(tr("kami_essentials.admin.stats.top_traders"), Icons.HANDSHAKE, topTraders)
        )
        ui.statPage("statistics", r, range, board, head + metrics.tiles(), metrics.charts() + tops + metrics.objects())
    }

    private fun top(title: String, icon: Icon, q: StatQuery, format: (Long) -> String = { Format.number(it) }) = statTopCard(title, icon, board[q].rows, format)
}
