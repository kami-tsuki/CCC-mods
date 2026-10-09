package kami.essentials.client.admin

import kami.libs.ui.app.Page
import kami.libs.ui.app.StatBoard
import kami.libs.ui.app.StatLine
import kami.libs.ui.app.TimeRange
import kami.libs.ui.app.statChartCard
import kami.libs.ui.app.statDonutCard
import kami.libs.ui.app.statPage
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.KpiTile

internal class ActivityPage : Page() {
    override val title get() = tr("kami_essentials.admin.activity")
    private val range = TimeRange("activity:range")
    private val board = StatBoard(range)
    private val online = board.series("server.online")
    private val unique = board.series("server.ping", "distinct")
    private val ping = board.series("server.ping")
    private val tps = board.series("server.tps")
    private val players = board.distinct("server.ping")
    private val activeCountries = board.series("country.active")
    private val countries = board.series("country.count")
    private val members = board.latest("country.members")
    private val onlineBy = board.latest("country.online")

    override fun draw(ui: Ui, r: Rect) {
        val tiles = listOf(
            KpiTile(tr("kami_essentials.admin.stats.unique"), Format.number(board[players].total.toLong()), Icons.PEOPLE)
        )
        val cards = listOf(
            chart(tr("kami_essentials.admin.online_chart"), Icons.PEOPLE, "online", listOf(StatLine(tr("kami_libs.common.online"), board[online].points, Palette.chart[1], true))),
            chart(tr("kami_essentials.admin.stats.unique"), Icons.PERSON, "unique", listOf(StatLine(tr("kami_essentials.admin.stats.unique"), board[unique].points, Palette.chart[2], true))),
            chart(tr("kami_essentials.admin.stats.ping"), Icons.GLOBE, "ping", listOf(StatLine(tr("kami_essentials.admin.stats.ping"), board[ping].points, Palette.chart[3]))),
            chart(tr("kami_essentials.admin.tps"), Icons.CLOCK, "tps", listOf(StatLine(tr("kami_essentials.admin.tps"), board[tps].points, Palette.chart[0])), { Format.decimal(it / 100.0) }),
            chart(
                tr("kami_essentials.admin.economy.countries"), Icons.FLAG, "countries",
                listOf(StatLine(tr("kami_essentials.admin.economy.countries"), board[countries].points, Palette.chart[2]), StatLine(tr("kami_essentials.admin.filter.active"), board[activeCountries].points, Palette.chart[1], true))
            ),
            statDonutCard(tr("kami_essentials.admin.stats.members_by"), Icons.PEOPLE, "activity:members", board[members].rows),
            statDonutCard(tr("kami_essentials.admin.stats.online_by"), Icons.FLAG, "activity:online-by", board[onlineBy].rows)
        )
        ui.statPage("activity", r, range, board, tiles, cards)
    }

    private fun chart(title: String, icon: Icon, key: String, lines: List<StatLine>, format: (Long) -> String = { Format.number(it) }) =
        statChartCard(title, icon, "activity:$key", lines, format)
}

