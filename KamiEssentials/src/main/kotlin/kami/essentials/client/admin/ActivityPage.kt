package kami.essentials.client.admin

import kami.libs.ui.app.Page
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
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.KpiTile

internal fun average(total: Double, count: Double) = if (count > 0) total / count else 0.0

internal class ActivityPage : Page() {
    override val title get() = tr("kami_essentials.admin.nav.activity")
    private val range = TimeRange("activity:range")
    private val board = StatBoard(range)
    private val online = board.series("server.online")
    private val unique = board.series("server.ping", "distinct")
    private val ping = board.series("server.ping")
    private val tps = board.series("server.tps")
    private val joined = board.series("session.login", "count", obj = "new")
    private val players = board.distinct("server.ping")
    private val playtime = board.total("session.time")
    private val playtimers = board.distinct("session.time")
    private val mobKills = board.total("mob.kill")
    private val deaths = board.total("combat.death")
    private val pvp = board.series("combat.pvp", "count")
    private val distance = board.total("move.dist")
    private val trades = board.total("market.trade")
    private val traders = board.distinct("market.trade")
    private val tax = board.total("money.tax", obj = "out")
    private val payers = board.distinct("money.tax", obj = "out")
    private val volume = board.counted("market.volume")
    private val topKillers = board.top("combat.kill")
    private val topDeaths = board.top("combat.death")
    private val mobs = board.top("mob.kill", "o")
    private val mined = board.top("block.mine", "o")
    private val placed = board.top("block.place", "o")
    private val crafted = board.top("craft.item", "o")
    private val walkers = board.top("move.dist")
    private val topTraders = board.top("market.trade")
    private val activeCountries = board.series("country.active")
    private val countries = board.series("country.count")
    private val members = board.latest("country.members")
    private val onlineBy = board.latest("country.online")

    override fun draw(ui: Ui, r: Rect) {
        val tiles = listOf(
            KpiTile(tr("kami_essentials.admin.stats.unique"), Format.number(board[players].total.toLong()), Icons.PEOPLE),
            KpiTile(tr("kami_essentials.admin.stats.new"), Format.number(board[joined].points.total().toLong()), Icons.INVITE),
            KpiTile(tr("kami_essentials.admin.stats.playtime"), Format.duration(average(board[playtime].total, board[playtimers].total).toLong()), Icons.CLOCK),
            KpiTile(tr("kami_essentials.admin.stats.mob_kills"), Format.compact(board[mobKills].total.toLong()), Icons.FIRE),
            KpiTile(tr("kami_essentials.admin.stats.deaths"), Format.compact(board[deaths].total.toLong()), Icons.DANGER),
            KpiTile(tr("kami_essentials.admin.stats.pvp"), Format.number(board[pvp].points.total().toLong()), Icons.SHIELD),
            KpiTile(tr("kami_essentials.admin.stats.distance"), tr("kami_essentials.admin.stats.blocks", Format.compact(board[distance].total.toLong())), Icons.RULER),
            KpiTile(tr("kami_essentials.admin.stats.trades"), Format.decimal(average(board[trades].total, board[traders].total)), Icons.HANDSHAKE),
            KpiTile(tr("kami_essentials.admin.stats.taxes"), Format.money(average(board[tax].total, board[payers].total).toLong(), compact = true), Icons.TAX, Palette.money),
            KpiTile(tr("kami_essentials.admin.stats.volume"), Format.money(board[volume].points.total().toLong(), compact = true), Icons.COIN, Palette.money)
        )
        val cards = listOf(
            chart(tr("kami_essentials.admin.stats.online"), Icons.PEOPLE, "online", listOf(StatLine(tr("kami_libs.common.online"), board[online].points, Palette.chart[1], true))),
            chart(tr("kami_essentials.admin.stats.unique"), Icons.PERSON, "unique", listOf(StatLine(tr("kami_essentials.admin.stats.unique"), board[unique].points, Palette.chart[2], true))),
            chart(tr("kami_essentials.admin.stats.ping"), Icons.GLOBE, "ping", listOf(StatLine(tr("kami_essentials.admin.stats.ping"), board[ping].points, Palette.chart[3]))),
            chart(tr("kami_essentials.admin.tps"), Icons.CLOCK, "tps", listOf(StatLine(tr("kami_essentials.admin.tps"), board[tps].points, Palette.chart[0])), { Format.decimal(it / 100.0) }),
            chart(tr("kami_essentials.admin.stats.new"), Icons.INVITE, "new", listOf(StatLine(tr("kami_essentials.admin.stats.new"), board[joined].points, Palette.chart[6], true))),
            chart(tr("kami_essentials.admin.stats.volume"), Icons.COIN, "volume", listOf(StatLine(tr("kami_essentials.admin.stats.volume"), board[volume].points, Palette.money, true))),
            chart(
                tr("kami_essentials.admin.economy.countries"), Icons.FLAG, "countries",
                listOf(StatLine(tr("kami_essentials.admin.economy.countries"), board[countries].points, Palette.chart[2]), StatLine(tr("kami_essentials.admin.stats.active"), board[activeCountries].points, Palette.chart[1], true))
            ),
            top(tr("kami_essentials.admin.stats.top_killers"), Icons.CROWN, topKillers),
            top(tr("kami_essentials.admin.stats.top_deaths"), Icons.DANGER, topDeaths),
            top(tr("kami_essentials.admin.stats.mobs"), Icons.FIRE, mobs),
            top(tr("kami_essentials.admin.stats.mined"), Icons.PICKAXE, mined),
            top(tr("kami_essentials.admin.stats.placed"), Icons.BLOCK, placed),
            top(tr("kami_essentials.admin.stats.crafted"), Icons.TOOL, crafted),
            top(tr("kami_essentials.admin.stats.walkers"), Icons.RULER, walkers) { Format.compact(it) },
            top(tr("kami_essentials.admin.stats.top_traders"), Icons.HANDSHAKE, topTraders),
            statDonutCard(tr("kami_essentials.admin.stats.members_by"), Icons.PEOPLE, "activity:members", board[members].rows),
            statDonutCard(tr("kami_essentials.admin.stats.online_by"), Icons.FLAG, "activity:online-by", board[onlineBy].rows)
        )
        ui.statPage("activity", r, range, board, tiles, cards)
    }

    private fun chart(title: String, icon: Icon, key: String, lines: List<StatLine>, format: (Long) -> String = { Format.compact(it) }) =
        statChartCard(title, icon, "activity:$key", lines, format)

    private fun top(title: String, icon: Icon, q: StatQuery, format: (Long) -> String = { Format.number(it) }) = statTopCard(title, icon, board[q].rows, format)
}
