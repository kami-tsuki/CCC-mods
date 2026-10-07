package kami.claims.client.admin

import kami.claims.client.app.pages.WarPage
import kami.libs.admin.StatPoint
import kami.libs.ui.app.Page
import kami.libs.ui.app.StatBoard
import kami.libs.ui.app.StatLine
import kami.libs.ui.app.StatQuery
import kami.libs.ui.app.TimeRange
import kami.libs.ui.app.delta
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

private fun List<StatPoint>.lastValue() = lastOrNull()?.v?.toLong() ?: 0L

private val moneyText = { v: Long -> Format.money(v, compact = true) }
private val powerText = { v: Long -> WarPage.power(v / 100.0) }
private val fameText = { v: Long -> WarPage.stars(v / 100.0) }

private fun average(label: String) = tr("kami_claims.admin.stats.average", label)

private fun perCountry(label: String) = tr("kami_claims.admin.stats.per_country", label)

internal class CountryStatsPage : Page() {
    override val title get() = tr("kami_claims.admin.nav.stats")
    private val range = TimeRange("country-stats:range")
    private val board = StatBoard(range)
    private val countries = board.series("country.count")
    private val active = board.series("country.active")
    private val treasury = board.series("country.treasury")
    private val treasuryBy = board.latest("country.treasury")
    private val chunks = board.series("country.chunks")
    private val chunksBy = board.latest("country.chunks")
    private val claimed = board.counted("country.claim")
    private val unclaimed = board.counted("country.unclaim")
    private val provinces = board.series("country.provinces")
    private val provincesBy = board.latest("country.provinces")
    private val members = board.series("country.members")
    private val membersBy = board.latest("country.members")
    private val online = board.series("country.online")
    private val onlineBy = board.latest("country.online")
    private val levelBy = board.latest("country.level")
    private val fameBy = board.latest("country.fame")
    private val powerBy = board.latest("country.power")
    private val declared = board.series("war.declare", "count")
    private val warKills = board.counted("war.kill")
    private val warKillsBy = board.top("war.kill")
    private val research = board.series("research.done", "count")

    override fun draw(ui: Ui, r: Rect) {
        val gain = board[claimed].points.delta(board[unclaimed].points)
        val tiles = listOf(
            KpiTile(tr("kami_claims.metric.countries"), Format.number(board[countries].points.lastValue()), Icons.FLAG),
            KpiTile(tr("kami_claims.admin.stats.active"), Format.number(board[active].points.lastValue()), Icons.PEOPLE),
            KpiTile(average(tr("kami_claims.admin.col.treasury")), moneyText(board[treasury].points.lastValue()), Icons.TREASURY, Palette.money),
            KpiTile(tr("kami_claims.admin.stats.gain"), Format.signed(gain.total().toLong()), Icons.AREA),
            KpiTile(tr("kami_claims.admin.stats.declarations"), Format.number(board[declared].points.total().toLong()), Icons.SHIELD),
            KpiTile(tr("kami_claims.admin.stats.war_kills"), Format.number(board[warKills].points.total().toLong()), Icons.DANGER),
            KpiTile(tr("kami_claims.admin.stats.research"), Format.number(board[research].points.total().toLong()), Icons.SCROLL)
        )
        val cards = listOf(
            chart(average(tr("kami_claims.admin.col.treasury")), Icons.TREASURY, "treasury", listOf(StatLine(tr("kami_claims.admin.col.treasury"), board[treasury].points, Palette.money, true)), moneyText),
            top(perCountry(tr("kami_claims.admin.col.treasury")), Icons.TREASURY, treasuryBy, moneyText),
            chart(average(tr("kami_claims.admin.col.claims")), Icons.AREA, "chunks", listOf(StatLine(tr("kami_claims.admin.col.claims"), board[chunks].points, Palette.chart[2], true))),
            chart(tr("kami_claims.admin.stats.gain"), Icons.MAP, "gain", listOf(StatLine(tr("kami_claims.admin.stats.gain"), gain, Palette.chart[1], true))),
            top(perCountry(tr("kami_claims.admin.col.claims")), Icons.AREA, chunksBy),
            chart(average(tr("kami_claims.admin.stats.provinces")), Icons.CHAIN, "provinces", listOf(StatLine(tr("kami_claims.admin.stats.provinces"), board[provinces].points, Palette.chart[3]))),
            top(perCountry(tr("kami_claims.admin.stats.provinces")), Icons.CHAIN, provincesBy),
            chart(
                average(tr("kami_libs.common.members")), Icons.PEOPLE, "members",
                listOf(StatLine(tr("kami_libs.common.members"), board[members].points, Palette.chart[1]), StatLine(tr("kami_libs.common.online"), board[online].points, Palette.chart[2], true))
            ),
            top(perCountry(tr("kami_libs.common.members")), Icons.PEOPLE, membersBy),
            top(perCountry(tr("kami_libs.common.online")), Icons.PERSON, onlineBy),
            top(perCountry(tr("kami_claims.admin.col.level")), Icons.STAR, levelBy),
            top(perCountry(tr("kami_claims.warpage.fame")), Icons.CROWN, fameBy, fameText),
            top(perCountry(tr("kami_claims.kpi.power")), Icons.SHIELD, powerBy, powerText),
            chart(tr("kami_claims.admin.stats.declarations"), Icons.SHIELD, "declared", listOf(StatLine(tr("kami_claims.admin.stats.declarations"), board[declared].points, Palette.danger, true))),
            chart(tr("kami_claims.admin.stats.war_kills"), Icons.DANGER, "war-kills", listOf(StatLine(tr("kami_claims.admin.stats.war_kills"), board[warKills].points, Palette.danger, true))),
            top(perCountry(tr("kami_claims.admin.stats.war_kills")), Icons.DANGER, warKillsBy),
            chart(tr("kami_claims.admin.stats.research"), Icons.SCROLL, "research", listOf(StatLine(tr("kami_claims.admin.stats.research"), board[research].points, Palette.chart[6], true))),
            chart(
                tr("kami_claims.metric.countries"), Icons.FLAG, "countries",
                listOf(StatLine(tr("kami_claims.metric.countries"), board[countries].points, Palette.chart[2]), StatLine(tr("kami_claims.admin.stats.active"), board[active].points, Palette.chart[1], true))
            )
        )
        ui.statPage("country-stats", r, range, board, tiles, cards)
    }

    private fun chart(title: String, icon: Icon, key: String, lines: List<StatLine>, format: (Long) -> String = { Format.compact(it) }) =
        statChartCard(title, icon, "country-stats:$key", lines, format)

    private fun top(title: String, icon: Icon, q: StatQuery, format: (Long) -> String = { Format.number(it) }) = statTopCard(title, icon, board[q].rows, format)
}

internal class CountryStats(id: String) {
    private val subject = "c:$id"
    private val range = TimeRange("country-detail-stats:range")
    private val board = StatBoard(range)
    private val treasury = board.series("country.treasury", subject = subject)
    private val chunks = board.series("country.chunks", subject = subject)
    private val claimed = board.counted("country.claim", subject)
    private val unclaimed = board.counted("country.unclaim", subject)
    private val members = board.series("country.members", subject = subject)
    private val online = board.series("country.online", subject = subject)
    private val provinces = board.series("country.provinces", subject = subject)
    private val level = board.series("country.level", subject = subject)
    private val fame = board.series("country.fame", subject = subject)
    private val power = board.series("country.power", subject = subject)
    private val declared = board.series("war.declare", "count", subject)
    private val warKills = board.counted("war.kill", subject)
    private val research = board.series("research.done", "count", subject)

    fun draw(ui: Ui, r: Rect) {
        val gain = board[claimed].points.delta(board[unclaimed].points)
        val tiles = listOf(
            KpiTile(tr("kami_claims.admin.col.treasury"), moneyText(board[treasury].points.lastValue()), Icons.TREASURY, Palette.money),
            KpiTile(tr("kami_claims.admin.col.claims"), Format.number(board[chunks].points.lastValue()), Icons.AREA),
            KpiTile(tr("kami_claims.admin.stats.gain"), Format.signed(gain.total().toLong()), Icons.MAP),
            KpiTile(tr("kami_libs.common.members"), Format.number(board[members].points.lastValue()), Icons.PEOPLE),
            KpiTile(tr("kami_claims.admin.stats.declarations"), Format.number(board[declared].points.total().toLong()), Icons.SHIELD),
            KpiTile(tr("kami_claims.admin.stats.war_kills"), Format.number(board[warKills].points.total().toLong()), Icons.DANGER),
            KpiTile(tr("kami_claims.admin.stats.research"), Format.number(board[research].points.total().toLong()), Icons.SCROLL)
        )
        val cards = listOf(
            chart(tr("kami_claims.admin.col.treasury"), Icons.TREASURY, "treasury", listOf(StatLine(tr("kami_claims.admin.col.treasury"), board[treasury].points, Palette.money, true)), moneyText),
            chart(tr("kami_claims.admin.col.claims"), Icons.AREA, "chunks", listOf(StatLine(tr("kami_claims.admin.col.claims"), board[chunks].points, Palette.chart[2], true))),
            chart(tr("kami_claims.admin.stats.gain"), Icons.MAP, "gain", listOf(StatLine(tr("kami_claims.admin.stats.gain"), gain, Palette.chart[1], true))),
            chart(
                tr("kami_libs.common.members"), Icons.PEOPLE, "members",
                listOf(StatLine(tr("kami_libs.common.members"), board[members].points, Palette.chart[1]), StatLine(tr("kami_libs.common.online"), board[online].points, Palette.chart[2], true))
            ),
            chart(tr("kami_claims.admin.stats.provinces"), Icons.CHAIN, "provinces", listOf(StatLine(tr("kami_claims.admin.stats.provinces"), board[provinces].points, Palette.chart[3]))),
            chart(tr("kami_claims.admin.col.level"), Icons.STAR, "level", listOf(StatLine(tr("kami_claims.admin.col.level"), board[level].points, Palette.chart[0]))),
            chart(tr("kami_claims.warpage.fame"), Icons.CROWN, "fame", listOf(StatLine(tr("kami_claims.warpage.fame"), board[fame].points, Palette.chart[4])), fameText),
            chart(tr("kami_claims.kpi.power"), Icons.SHIELD, "power", listOf(StatLine(tr("kami_claims.kpi.power"), board[power].points, Palette.chart[5])), powerText),
            chart(tr("kami_claims.admin.stats.war_kills"), Icons.DANGER, "war-kills", listOf(StatLine(tr("kami_claims.admin.stats.war_kills"), board[warKills].points, Palette.danger, true))),
            chart(tr("kami_claims.admin.stats.research"), Icons.SCROLL, "research", listOf(StatLine(tr("kami_claims.admin.stats.research"), board[research].points, Palette.chart[6], true)))
        )
        ui.statPage("country-detail-stats", r, range, board, tiles, cards)
    }

    private fun chart(title: String, icon: Icon, key: String, lines: List<StatLine>, format: (Long) -> String = { Format.compact(it) }) =
        statChartCard(title, icon, "country-detail-stats:$key", lines, format)
}
