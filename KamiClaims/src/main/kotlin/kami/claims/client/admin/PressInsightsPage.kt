package kami.claims.client.admin

import kami.claims.admin.AdminNews
import kami.claims.admin.NewsCount
import kami.libs.ui.app.Page
import kami.libs.ui.app.remoteFailed
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.KpiTile
import kami.libs.ui.widget.Series
import kami.libs.ui.widget.TableState
import kami.libs.ui.widget.card
import kami.libs.ui.widget.kpiRow
import kami.libs.ui.widget.lineChart
import kami.libs.ui.widget.table
import java.time.LocalDate

private const val TILE_H = 44
private const val GAP = 6

internal class PressInsightsPage : Page() {
    private val remote = PressAdminSection.insights
    private val countries = TableState<NewsCount>()
    private val authors = TableState<NewsCount>()
    private val columnsMemo = Memo()

    override val title get() = tr("kami_claims.admin.press.insights")

    override fun draw(ui: Ui, r: Rect) {
        remote.poll()
        val d = remote.data ?: remote.latest
        if (d == null) {
            if (remote.error != null && !remote.loading) ui.remoteFailed(r, remote, "press-insights")
            return
        }
        val system = d.days.sumOf { it.system }
        val country = d.days.sumOf { it.country }
        val editorial = d.days.sumOf { it.editorial }
        ui.kpiRow(r.top(TILE_H), listOf(
            KpiTile(tr("kami_claims.admin.press.kpi.total", AdminNews.DAYS), Format.number(d.total.toLong()), Icons.SCROLL, sub = tr("kami_claims.admin.press.kpi.split", system, country, editorial)),
            KpiTile(tr("kami_claims.admin.press.kpi.country"), Format.number(country.toLong()), Icons.FLAG),
            KpiTile(tr("kami_claims.admin.press.kpi.system"), Format.number(system.toLong()), Icons.GEAR),
            KpiTile(tr("kami_claims.admin.press.kpi.editorial"), Format.number(editorial.toLong()), Icons.EDIT),
            KpiTile(tr("kami_claims.admin.press.kpi.drafts"), Format.number(d.drafts.toLong()), Icons.SCROLL, if (d.drafts > 0) Palette.warning else Palette.text,
                sub = if (d.oldestDraft > 0) tr("kami_claims.admin.press.kpi.oldest", Format.ago(d.oldestDraft)) else null)
        ), key = "press-kpi")
        val rest = r.dropTop(TILE_H + GAP)
        val chartH = rest.h * 11 / 20
        val chart = ui.card(rest.top(chartH), tr("kami_claims.admin.press.chart", AdminNews.DAYS), Icons.STATS, key = "press-chart")
        ui.lineChart(
            chart,
            listOf(
                Series(tr("kami_claims.admin.press.kpi.country"), d.days.map { it.country.toLong() }, Palette.brass),
                Series(tr("kami_claims.admin.press.kpi.system"), d.days.map { it.system.toLong() }, Palette.textMuted),
                Series(tr("kami_claims.admin.press.kpi.editorial"), d.days.map { it.editorial.toLong() }, Palette.success)
            ),
            d.days.map { LocalDate.ofEpochDay(it.day).toString().substring(5) }, key = "press-line"
        )
        val (left, right) = rest.dropTop(chartH + GAP).columns(2, GAP)
        val columns = columnsMemo.of(Format.locale) { listOf(
            Column.text<NewsCount>(tr("kami_libs.common.name"), -1) { it.name },
            Column.number<NewsCount>(tr("kami_claims.admin.press.articles"), 60) { it.count.toLong() }
        ) }
        ui.table(ui.card(left, tr("kami_claims.admin.press.top_countries"), Icons.FLAG, key = "press-countries"), columns, d.countries, countries, { it.id }, emptyText = tr("kami_libs.table.empty"), key = "press-top-countries")
        ui.table(ui.card(right, tr("kami_claims.admin.press.top_authors"), Icons.PEOPLE, key = "press-authors"), columns, d.authors, authors, { it.id }, emptyText = tr("kami_libs.table.empty"), key = "press-top-authors")
    }
}
