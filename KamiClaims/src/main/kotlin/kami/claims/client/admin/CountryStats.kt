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
    private val offerCreated = board.series("loan.offer.create", "count", subject)
    private val offerOn = board.total("loan.offer.on", subject)
    private val offerOff = board.total("loan.offer.off", subject)
    private val offerDeleted = board.total("loan.offer.delete", subject)
    private val taken = board.series("loan.take", "count", subject)
    private val takenSum = board.series("loan.take", "sum", subject)
    private val lend = board.total("loan.lend", subject)
    private val paidBack = board.total("loan.paid", subject)
    private val received = board.total("loan.received", subject)
    private val interest = board.total("loan.interest", subject)
    private val missed = board.total("loan.miss", subject)
    private val closed = board.series("loan.close", "count", subject)
    private val missedPayments = board.series("loan.close", "sum", subject)
    private val offersActive = board.series("loan.offers.active", subject = subject)
    private val offersDisabled = board.series("loan.offers.disabled", subject = subject)
    private val offersLent = board.series("loan.offers.lent", subject = subject)
    private val reserved = board.series("loan.reserved", subject = subject)
    private val debt = board.series("loan.debt", subject = subject)
    private val lent = board.series("loan.lent", subject = subject)
    private val block = board.series("loan.block", subject = subject)

    fun draw(ui: Ui, r: Rect) {
        val gain = board[claimed].points.delta(board[unclaimed].points)
        val tiles = listOf(
            KpiTile(tr("kami_claims.admin.col.treasury"), moneyText(board[treasury].points.lastValue()), Icons.TREASURY, Palette.money),
            KpiTile(tr("kami_claims.admin.col.claims"), Format.number(board[chunks].points.lastValue()), Icons.AREA),
            KpiTile(tr("kami_claims.admin.stats.gain"), Format.signed(gain.total().toLong()), Icons.MAP),
            KpiTile(tr("kami_libs.common.members"), Format.number(board[members].points.lastValue()), Icons.PEOPLE),
            KpiTile(tr("kami_claims.admin.stats.declarations"), Format.number(board[declared].points.total().toLong()), Icons.SHIELD),
            KpiTile(tr("kami_claims.admin.stats.war_kills"), Format.number(board[warKills].points.total().toLong()), Icons.DANGER),
            KpiTile(tr("kami_claims.admin.stats.research"), Format.number(board[research].points.total().toLong()), Icons.SCROLL),
            KpiTile(tr("kami_claims.admin.stats.loan.offers_created"), Format.number(board[offerCreated].points.total().toLong()), Icons.DEBT),
            KpiTile(tr("kami_claims.admin.stats.loan.offers_on"), Format.number(board[offerOn].total.toLong()), Icons.CHECK),
            KpiTile(tr("kami_claims.admin.stats.loan.offers_off"), Format.number(board[offerOff].total.toLong()), Icons.LOCK),
            KpiTile(tr("kami_claims.admin.stats.loan.offers_deleted"), Format.number(board[offerDeleted].total.toLong()), Icons.REMOVE),
            KpiTile(tr("kami_claims.admin.stats.loan.taken"), Format.number(board[taken].points.total().toLong()), Icons.DEBT),
            KpiTile(tr("kami_claims.admin.stats.loan.taken_sum"), moneyText(board[takenSum].points.total().toLong()), Icons.INCOME, Palette.money),
            KpiTile(tr("kami_claims.admin.stats.loan.lend"), moneyText(board[lend].total.toLong()), Icons.EXPENSE, Palette.money),
            KpiTile(tr("kami_claims.admin.stats.loan.paid"), moneyText(board[paidBack].total.toLong()), Icons.EXPENSE, Palette.money),
            KpiTile(tr("kami_claims.admin.stats.loan.received"), moneyText(board[received].total.toLong()), Icons.INCOME, Palette.money),
            KpiTile(tr("kami_claims.admin.stats.loan.interest"), moneyText(board[interest].total.toLong()), Icons.PERCENT, Palette.money),
            KpiTile(tr("kami_claims.admin.stats.loan.missed"), moneyText(board[missed].total.toLong()), Icons.WARNING, Palette.danger),
            KpiTile(tr("kami_claims.admin.stats.loan.closed"), Format.number(board[closed].points.total().toLong()), Icons.CHECK),
            KpiTile(tr("kami_claims.admin.stats.loan.missed_payments"), Format.number(board[missedPayments].points.total().toLong()), Icons.WARNING, Palette.danger),
            KpiTile(tr("kami_claims.admin.stats.loan.active"), Format.number(board[offersActive].points.lastValue()), Icons.DEBT),
            KpiTile(tr("kami_claims.admin.stats.loan.disabled"), Format.number(board[offersDisabled].points.lastValue()), Icons.LOCK),
            KpiTile(tr("kami_claims.admin.stats.loan.lent_offers"), Format.number(board[offersLent].points.lastValue()), Icons.HANDSHAKE),
            KpiTile(tr("kami_claims.admin.stats.loan.reserved"), moneyText(board[reserved].points.lastValue()), Icons.TREASURY, Palette.money),
            KpiTile(tr("kami_claims.admin.stats.loan.debt"), moneyText(board[debt].points.lastValue()), Icons.DEBT, Palette.danger),
            KpiTile(tr("kami_claims.admin.stats.loan.lent"), moneyText(board[lent].points.lastValue()), Icons.EXPENSE, Palette.money),
            KpiTile(tr("kami_claims.admin.stats.loan.block"), Format.number(board[block].points.lastValue()), Icons.CLOCK)
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
            board.chartCard(tr("kami_claims.admin.stats.research"), Icons.SCROLL, "country-detail-stats:research", research, Palette.chart[6]),
            board.chartCard(tr("kami_claims.admin.stats.loan.debt"), Icons.DEBT, "country-detail-stats:loan-debt", debt, Palette.danger, false, moneyText),
            board.chartCard(tr("kami_claims.admin.stats.loan.taken_sum"), Icons.INCOME, "country-detail-stats:loan-taken", takenSum, Palette.money, format = moneyText)
        )
        ui.statPage("country-detail-stats", r, range, board, tiles, cards)
    }
}
