package kami.economy.client.admin

import kami.economy.admin.WatchStats
import kami.libs.admin.StatRow
import kami.libs.ui.app.Page
import kami.libs.ui.app.Remote
import kami.libs.ui.app.StatBoard
import kami.libs.ui.app.StatLine
import kami.libs.ui.app.StatQuery
import kami.libs.ui.app.TimeRange
import kami.libs.ui.app.statChartCard
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

private const val REFRESH_MS = 15_000L

internal class MarketStatsPage : Page() {
    override val title get() = tr("kami_economy.admin.nav.stats")
    private val range = TimeRange("market-stats:range")
    private val board = StatBoard(range)
    private val watch = Remote("market.watch", WatchStats.serializer(), REFRESH_MS)
    private val watched = board.counted("market.watch")
    private val trades = board.counted("market.trade")
    private val volume = board.counted("market.volume")
    private val taxes = board.counted("market.tax")
    private val tariffs = board.counted("market.tariff")
    private val bought = board.top("market.buy", "o")
    private val sold = board.top("market.sell", "o")
    private val traders = board.top("market.trade")

    private fun money(v: Long) = Format.money(v)

    private fun chart(title: String, icon: Icon, key: String, q: StatQuery, color: Int = Palette.chart[1], format: (Long) -> String = { Format.number(it) }) =
        statChartCard(title, icon, "market-stats:$key", listOf(StatLine(title, board[q].points, color, true)), format)

    override fun draw(ui: Ui, r: Rect) {
        watch.poll()
        val w = watch.data ?: watch.latest
        val tiles = listOf(
            KpiTile(tr("kami_economy.admin.stats.watch_total"), w?.total?.let { Format.number(it.toLong()) }, Icons.STAR),
            KpiTile(tr("kami_economy.admin.stats.watch_players"), w?.players?.let { Format.number(it.toLong()) }, Icons.PEOPLE),
            KpiTile(tr("kami_economy.admin.stats.watch_items"), w?.items?.let { Format.number(it.toLong()) }, Icons.LAYERS),
            KpiTile(tr("kami_economy.admin.stats.trades"), Format.number(board[trades].points.total().toLong()), Icons.HANDSHAKE),
            KpiTile(tr("kami_economy.admin.stats.volume"), money(board[volume].points.total().toLong()), Icons.COIN, Palette.money),
            KpiTile(tr("kami_economy.admin.stats.taxes"), money(board[taxes].points.total().toLong()), Icons.TAX, Palette.money),
            KpiTile(tr("kami_economy.admin.stats.tariffs"), money(board[tariffs].points.total().toLong()), Icons.PERCENT, Palette.money)
        )
        val cards = listOf(
            statTopCard(tr("kami_economy.admin.stats.top_watched"), Icons.STAR, w?.top.orEmpty().map { StatRow(it.item, it.count.toLong(), MarketSection.itemName(it.item)) }),
            chart(tr("kami_economy.admin.stats.watch_change"), Icons.STAR, "watch", watched, format = { Format.number(it) }),
            chart(tr("kami_economy.admin.stats.trades"), Icons.HANDSHAKE, "trades", trades),
            chart(tr("kami_economy.admin.stats.volume"), Icons.COIN, "volume", volume, Palette.money) { money(it) },
            chart(tr("kami_economy.admin.stats.taxes"), Icons.TAX, "taxes", taxes, Palette.money) { money(it) },
            chart(tr("kami_economy.admin.stats.tariffs"), Icons.PERCENT, "tariffs", tariffs, Palette.money) { money(it) },
            statTopCard(tr("kami_economy.admin.stats.top_bought"), Icons.INCOME, board[bought].rows),
            statTopCard(tr("kami_economy.admin.stats.top_sold"), Icons.EXPENSE, board[sold].rows),
            statTopCard(tr("kami_economy.admin.stats.top_traders"), Icons.HANDSHAKE, board[traders].rows)
        )
        ui.statPage("market-stats", r, range, board, tiles, cards)
    }
}
