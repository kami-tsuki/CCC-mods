package kami.economy.client.admin

import kami.economy.admin.WatchStats
import kami.libs.admin.StatRow
import kami.libs.ui.app.Page
import kami.libs.ui.app.Remote
import kami.libs.ui.app.StatBoard
import kami.libs.ui.app.chartCard
import kami.libs.ui.app.TimeRange
import kami.libs.ui.app.statPage
import kami.libs.ui.app.statTopCard
import kami.libs.ui.app.topCard
import kami.libs.ui.app.total
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.KpiTile

internal class MarketStatsPage : Page() {
    override val title get() = tr("kami_libs.common.statistics")
    private val range = TimeRange("market-stats:range")
    private val board = StatBoard(range)
    private val watch = Remote("market.watch", WatchStats.serializer(), 15_000L)
    private val watched = board.counted("market.watch")
    private val trades = board.counted("market.trade")
    private val volume = board.counted("market.volume")
    private val taxes = board.counted("market.tax")
    private val tariffs = board.counted("market.tariff")
    private val bought = board.top("market.buy", "o")
    private val sold = board.top("market.sell", "o")
    private val traders = board.top("market.trade")

    override fun draw(ui: Ui, r: Rect) {
        watch.poll()
        val w = watch.data ?: watch.latest
        val tiles = listOf(
            KpiTile(tr("kami_economy.admin.stats.watch_total"), w?.total?.let { Format.number(it.toLong()) }, Icons.STAR),
            KpiTile(tr("kami_economy.admin.stats.watch_players"), w?.players?.let { Format.number(it.toLong()) }, Icons.PEOPLE),
            KpiTile(tr("kami_economy.admin.stats.watch_items"), w?.items?.let { Format.number(it.toLong()) }, Icons.LAYERS),
            KpiTile(tr("kami_economy.personal.trades"), Format.number(board[trades].points.total().toLong()), Icons.HANDSHAKE),
            KpiTile(tr("kami_economy.admin.stats.volume"), Format.money(board[volume].points.total().toLong()), Icons.COIN, Palette.money),
            KpiTile(tr("kami_libs.flow.tax"), Format.money(board[taxes].points.total().toLong()), Icons.TAX, Palette.money),
            KpiTile(tr("kami_economy.admin.stats.tariffs"), Format.money(board[tariffs].points.total().toLong()), Icons.PERCENT, Palette.money)
        )
        val cards = listOf(
            statTopCard(tr("kami_economy.admin.stats.top_watched"), Icons.STAR, w?.top.orEmpty().map { StatRow(it.item, it.count.toLong(), MarketSection.itemName(it.item)) }),
            board.chartCard(tr("kami_economy.admin.stats.watch_change"), Icons.STAR, "market-stats:watch", watched),
            board.chartCard(tr("kami_economy.personal.trades"), Icons.HANDSHAKE, "market-stats:trades", trades),
            board.chartCard(tr("kami_economy.admin.stats.volume"), Icons.COIN, "market-stats:volume", volume, Palette.money, format = Format::money),
            board.chartCard(tr("kami_libs.flow.tax"), Icons.TAX, "market-stats:taxes", taxes, Palette.money, format = Format::money),
            board.chartCard(tr("kami_economy.admin.stats.tariffs"), Icons.PERCENT, "market-stats:tariffs", tariffs, Palette.money, format = Format::money),
            board.topCard(tr("kami_economy.dash.bought"), Icons.INCOME, bought),
            board.topCard(tr("kami_economy.dash.sold"), Icons.EXPENSE, sold),
            board.topCard(tr("kami_libs.common.top_traders"), Icons.HANDSHAKE, traders)
        )
        ui.statPage("market-stats", r, range, board, tiles, cards)
    }
}
