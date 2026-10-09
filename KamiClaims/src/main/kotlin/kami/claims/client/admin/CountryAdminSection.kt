package kami.claims.client.admin

import kami.libs.ui.core.Memo
import kami.claims.admin.CountryDetail
import kami.claims.admin.CountryRequests
import kami.claims.admin.CountryRow
import kami.claims.client.app.pages.WarPage
import kami.libs.ui.app.AdminSection
import kami.libs.ui.app.AdminSections
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.NavItem
import kami.libs.ui.app.Page
import kami.libs.ui.app.Paged
import kami.libs.ui.app.Remote
import kami.libs.ui.app.Route
import kami.libs.ui.app.pagedTable
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.textOrder
import kami.libs.ui.widget.TableState
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.filterBar
import kami.libs.ui.widget.searchField

private val countryColumnsMemo = Memo()

object CountryAdminSection {
    const val LIST_PAGE = "countries.list"
    const val DETAIL_PAGE = "countries.detail"
    const val STATS_PAGE = "countries.stats"
    private const val REFRESH_MS = 15_000L

    var shown: String? = null
    val detail = Remote(CountryRequests.DETAIL, CountryDetail.serializer(), REFRESH_MS) { listOfNotNull(shown) }

    fun register() = AdminSections.register(AdminSection("countries", 20, { "kami_claims.admin.nav.group" }, {
        listOf(
            NavItem(LIST_PAGE, tr("kami_claims.admin.nav.list"), Icons.FLAG),
            NavItem(DETAIL_PAGE, tr("kami_claims.admin.nav.detail"), Icons.CROWN),
            NavItem(STATS_PAGE, tr("kami_libs.common.statistics"), Icons.STATS)
        )
    }, { app, id ->
        when (id) {
            DETAIL_PAGE -> CountryDetailPage(app)
            STATS_PAGE -> CountryStatsPage()
            else -> CountryListPage(app)
        }
    }))

    fun openCountry(app: KamiApp, id: String) = app.navigate(Route(DETAIL_PAGE, mapOf("id" to id)))
}

internal fun countryColumns() = countryColumnsMemo.of(Format.locale) { listOf(
    Column<CountryRow>(tr("kami_libs.common.name"), -1, sort = textOrder { it.name }) { _, r, row ->
        Draw.fill(g, Rect(r.x, r.y + (r.h - 6) / 2, 6, 6), row.color or 0xFF000000.toInt())
        fitText(r.x + 10, r.y + (r.h - 8) / 2, r.w - 10, row.name, if (row.active) Palette.text else Palette.textMuted, key = "cn:${row.name}")
    },
    Column.text<CountryRow>(tr("kami_claims.admin.col.leader"), 80) { it.leaderName },
    Column.number<CountryRow>(tr("kami_libs.common.members"), 56) { it.members.toLong() },
    Column.number<CountryRow>(tr("kami_claims.admin.col.claims"), 50) { it.claims.toLong() },
    Column.number<CountryRow>(tr("kami_libs.flow.treasury"), 70, color = { Palette.money }, format = { Format.money(it) }) { it.treasury },
    Column.number<CountryRow>(tr("kami_libs.common.level"), 40) { it.level.toLong() },
    Column.number<CountryRow>(tr("kami_claims.kpi.power"), 44, format = { WarPage.power(it / 10.0) }) { (it.power * 10).toLong() },
    Column.number<CountryRow>(tr("kami_claims.admin.col.wars"), 40, color = { if (it.wars > 0) Palette.danger else Palette.text }) { it.wars.toLong() }
) }

internal class CountryListPage(private val app: KamiApp) : Page() {
    override val title get() = tr("kami_claims.admin.nav.list")
    private val search = TextState()
    private val paged = Paged(CountryRequests.LIST, CountryRow.serializer()) { listOf(search.text.trim()) }
    private val table = TableState<CountryRow>()

    override fun draw(ui: Ui, r: Rect) {
        val body = ui.filterBar(r, "countries:filters") { bar -> ui.searchField(bar, search, key = "countries:search") }
        val events = ui.pagedTable(body, paged, countryColumns(), table, { it.id }, tr("kami_claims.admin.empty"), "countries", severity = { if (it.active) null else Severity.NEUTRAL })
        if (events.selectionChanged) table.selected.lastOrNull()?.let { CountryAdminSection.detail.prefetch(listOf(it.toString())) }
        events.opened?.let { CountryAdminSection.openCountry(app, it.id) }
    }
}
