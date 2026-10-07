package kami.claims.client.admin

import kami.claims.client.app.pages.WarPage
import kami.libs.admin.StatPoint
import kami.libs.ui.app.Page
import kami.libs.ui.app.StatBoard
import kami.libs.ui.app.StatLine
import kami.libs.ui.app.TimeRange
import kami.libs.ui.app.delta
import kami.libs.ui.app.chartCard
import kami.libs.ui.app.statChartCard
import kami.libs.ui.app.statPage
import kami.libs.ui.app.topCard
import kami.libs.ui.app.total
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.KpiTile

private fun List<StatPoint>.lastValue() = lastOrNull()?.v?.toLong() ?: 0L

private val moneyText = { v: Long -> Format.money(v) }
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
            board.chartCard(average(tr("kami_claims.admin.col.treasury")), Icons.TREASURY, "country-stats:treasury", treasury, Palette.money, format = moneyText),
            board.topCard(perCountry(tr("kami_claims.admin.col.treasury")), Icons.TREASURY, treasuryBy, moneyText),
            board.chartCard(average(tr("kami_claims.admin.col.claims")), Icons.AREA, "country-stats:chunks", chunks, Palette.chart[2]),
            statChartCard(tr("kami_claims.admin.stats.gain"), Icons.MAP, "country-stats:gain", listOf(StatLine(tr("kami_claims.admin.stats.gain"), gain, Palette.chart[1], true))),
            board.topCard(perCountry(tr("kami_claims.admin.col.claims")), Icons.AREA, chunksBy),
            board.chartCard(average(tr("kami_claims.admin.stats.provinces")), Icons.CHAIN, "country-stats:provinces", provinces, Palette.chart[3], false),
            board.topCard(perCountry(tr("kami_claims.admin.stats.provinces")), Icons.CHAIN, provincesBy),
            statChartCard(
                average(tr("kami_libs.common.members")), Icons.PEOPLE, "country-stats:members",
                listOf(StatLine(tr("kami_libs.common.members"), board[members].points, Palette.chart[1]), StatLine(tr("kami_libs.common.online"), board[online].points, Palette.chart[2], true))
            ),
            board.topCard(perCountry(tr("kami_libs.common.members")), Icons.PEOPLE, membersBy),
            board.topCard(perCountry(tr("kami_libs.common.online")), Icons.PERSON, onlineBy),
            board.topCard(perCountry(tr("kami_claims.admin.col.level")), Icons.STAR, levelBy),
            board.topCard(perCountry(tr("kami_claims.warpage.fame")), Icons.CROWN, fameBy, fameText),
            board.topCard(perCountry(tr("kami_claims.kpi.power")), Icons.SHIELD, powerBy, powerText),
            board.chartCard(tr("kami_claims.admin.stats.declarations"), Icons.SHIELD, "country-stats:declared", declared, Palette.danger),
            board.chartCard(tr("kami_claims.admin.stats.war_kills"), Icons.DANGER, "country-stats:war-kills", warKills, Palette.danger),
            board.topCard(perCountry(tr("kami_claims.admin.stats.war_kills")), Icons.DANGER, warKillsBy),
            board.chartCard(tr("kami_claims.admin.stats.research"), Icons.SCROLL, "country-stats:research", research, Palette.chart[6]),
            statChartCard(
                tr("kami_claims.metric.countries"), Icons.FLAG, "country-stats:countries",
                listOf(StatLine(tr("kami_claims.metric.countries"), board[countries].points, Palette.chart[2]), StatLine(tr("kami_claims.admin.stats.active"), board[active].points, Palette.chart[1], true))
            )
        )
        ui.statPage("country-stats", r, range, board, tiles, cards)
    }
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
            board.chartCard(tr("kami_claims.admin.col.treasury"), Icons.TREASURY, "country-detail-stats:treasury", treasury, Palette.money, format = moneyText),
            board.chartCard(tr("kami_claims.admin.col.claims"), Icons.AREA, "country-detail-stats:chunks", chunks, Palette.chart[2]),
            statChartCard(tr("kami_claims.admin.stats.gain"), Icons.MAP, "country-detail-stats:gain", listOf(StatLine(tr("kami_claims.admin.stats.gain"), gain, Palette.chart[1], true))),
            statChartCard(
                tr("kami_libs.common.members"), Icons.PEOPLE, "country-detail-stats:members",
                listOf(StatLine(tr("kami_libs.common.members"), board[members].points, Palette.chart[1]), StatLine(tr("kami_libs.common.online"), board[online].points, Palette.chart[2], true))
            ),
            board.chartCard(tr("kami_claims.admin.stats.provinces"), Icons.CHAIN, "country-detail-stats:provinces", provinces, Palette.chart[3], false),
            board.chartCard(tr("kami_claims.admin.col.level"), Icons.STAR, "country-detail-stats:level", level, Palette.chart[0], false),
            board.chartCard(tr("kami_claims.warpage.fame"), Icons.CROWN, "country-detail-stats:fame", fame, Palette.chart[4], false, fameText),
            board.chartCard(tr("kami_claims.kpi.power"), Icons.SHIELD, "country-detail-stats:power", power, Palette.chart[5], false, powerText),
            board.chartCard(tr("kami_claims.admin.stats.war_kills"), Icons.DANGER, "country-detail-stats:war-kills", warKills, Palette.danger),
            board.chartCard(tr("kami_claims.admin.stats.research"), Icons.SCROLL, "country-detail-stats:research", research, Palette.chart[6])
        )
        ui.statPage("country-detail-stats", r, range, board, tiles, cards)
    }
}
