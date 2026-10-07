package kami.essentials.client

import kami.libs.economy.Account
import kami.libs.economy.DayTotal
import kami.libs.economy.FlowTotal
import kami.libs.economy.Movement
import kami.libs.economy.Statement
import kami.libs.ui.anim.countUp
import kami.libs.ui.app.Callout
import kami.libs.ui.app.NavItem
import kami.libs.ui.app.Page
import kami.libs.ui.app.Personal
import kami.libs.ui.app.PersonalSection
import kami.libs.ui.app.Route
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.label
import kami.libs.ui.text.partyLabel
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import kami.libs.util.Time
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import kami.libs.economy.Flow as MoneyFlow
import kami.libs.ui.text.noteLabel

private const val TILE_H = 44
private const val CHART_DAYS = 30
private const val RECENT_ROWS = 8
private const val DONUT_MAX = 90

val bankSection = PersonalSection(
    "bank", 30, { "kami_essentials.bank.group" },
    {
        if (PersonalApp.instance.noAccount) emptyList()
        else listOf(
            NavItem("bank.movements", tr("kami_essentials.bank.movements"), Icons.LEDGER),
            NavItem("bank.breakdown", tr("kami_essentials.bank.breakdown"), Icons.STATS)
        )
    },
    { _, id ->
        when (id) {
            "bank.movements" -> MovementsPage()
            "bank.breakdown" -> BreakdownPage()
            else -> null
        }
    }
)

private fun flowColor(f: MoneyFlow) = Palette.chart[f.ordinal % Palette.chart.size]

private fun amountColor(v: Long) = if (v > 0) Palette.success else if (v < 0) Palette.danger else Palette.textMuted

private fun flowSeverity(m: Movement) = if (m.amount >= 0) Severity.SUCCESS else Severity.DANGER

private fun movementKey(m: Movement): Any = listOf(m.at, m.amount, m.flow, m.counterparty, m.item, m.qty)

private fun detail(m: Movement): String {
    val party = partyLabel(m.counterparty)
    if (m.item.startsWith(Statement.NOTE)) return listOf(party, noteLabel(m.item)).filter { it.isNotEmpty() }.joinToString(", ")
    val item = ResourceLocation.tryParse(m.item)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }?.description?.string
    val what = item?.let { if (m.qty > 1) "${Format.number(m.qty)}x $it" else it }
    return listOfNotNull(what, party.ifEmpty { null }).joinToString(" · ")
}

private fun movementColumns() = listOf(
    Column.text<Movement>(tr("kami_essentials.bank.col.when"), 56, sortable = false) { Format.ago(it.at) },
    Column.text<Movement>(tr("kami_essentials.bank.col.flow"), 76, sortable = false) { it.flow.label },
    Column.text<Movement>(tr("kami_essentials.bank.col.detail"), -1, sortable = false, value = ::detail),
    Column.number<Movement>(tr("kami_essentials.bank.col.amount"), 70, color = { amountColor(it.amount) }, format = { Format.signedMoney(it) }) { it.amount }
)

private fun Ui.noAccount(r: Rect) {
    emptyState(r, tr("kami_essentials.bank.empty"), tr("kami_essentials.bank.empty.body"))
}

private val stale get() = Personal.loading && Personal.account != null

private fun window(a: Account, count: Int): List<DayTotal> {
    val today = System.currentTimeMillis() / Time.DAY_MS
    val byDay = a.days.associateBy { it.day }
    return (today - count + 1..today).map { byDay[it] ?: DayTotal(it, 0, 0) }
}

class DashboardPage(private val app: PersonalApp) : Page() {
    override val title get() = tr("kami_essentials.personal.nav.dashboard")
    override val help get() = listOf(
        Callout("personal:tiles", tr("kami_essentials.personal.help.tiles"), tr("kami_essentials.personal.help.tiles.desc")),
        Callout("personal:chart", tr("kami_essentials.personal.chart"), tr("kami_essentials.personal.help.chart.desc"))
    )
    private val table = TableState<Movement>()

    override fun draw(ui: Ui, r: Rect) {
        if (PersonalApp.instance.noAccount) return ui.noAccount(r)
        val a = Personal.account ?: return placeholder(ui, r)
        val days = window(a, CHART_DAYS)
        val week = days.takeLast(7)
        val net7 = week.sumOf { it.income - it.expense }
        val net30 = days.sumOf { it.income - it.expense }
        var balance = a.balance
        val trail = days.asReversed().map { d -> balance.also { balance -= d.income - d.expense } }.asReversed()
        ui.anchor("personal:tiles", r.top(TILE_H))
        ui.kpiRow(r.top(TILE_H), listOf(
            KpiTile(tr("kami_essentials.personal.balance"), Format.money(ui.countUp("personal-balance", a.balance)), Icons.COIN, Palette.money,
                trend = Trend(net30, tr("kami_essentials.personal.balance.month", Format.signed(net30))), spark = trail, flashValue = a.balance,
                onClick = { app.navigate(Route("bank.movements")) }),
            KpiTile(tr("kami_essentials.personal.net7"), Format.signedMoney(net7), Icons.STATS, amountColor(net7),
                sub = tr("kami_essentials.personal.net7.sub", Format.number(week.sumOf { it.income }), Format.number(week.sumOf { it.expense })),
                onClick = { app.navigate(Route("bank.breakdown")) }),
            KpiTile(tr("kami_essentials.personal.playtime"), Format.duration(a.playtime), Icons.CLOCK),
            KpiTile(tr("kami_essentials.personal.kd"), "${Format.number(a.kills)} / ${Format.number(a.deaths)}", Icons.FIRE,
                sub = tr("kami_essentials.personal.kd.sub", Format.decimal(a.kills.toDouble() / a.deaths.coerceAtLeast(1), 2)))
        ), key = "personal-kpi")
        val (left, right) = r.dropTop(TILE_H + 6).columns(listOf(1.35f, 1f), 6)
        ui.anchor("personal:chart", left)
        val chart = ui.card(left, tr("kami_essentials.personal.chart"), Icons.STATS, help = tr("kami_essentials.personal.help.chart.desc"))
        ui.barChart(chart, days.map { it.income }, days.map { it.expense }, days.map { Format.dayLabel(it.day) }, format = { Format.money(it) }, key = "personal-chart")
        val f = Flow(right, 6)
        val you = Flow(ui.card(f.take(48), tr("kami_libs.common.you"), Icons.PERSON), 1)
        ui.property(you.take(11), tr("kami_libs.common.country"), a.country ?: tr("kami_libs.common.none"), if (a.country == null) Palette.textMuted else Palette.text)
        ui.property(you.take(11), tr("kami_libs.common.rank"), a.rank ?: tr("kami_libs.common.none"), if (a.rank == null) Palette.textMuted else Palette.text)
        val recent = ui.card(f.remaining(), tr("kami_essentials.bank.recent"), Icons.LEDGER)
        ui.table(recent, movementColumns(), a.movements.take(RECENT_ROWS), table, ::movementKey, severity = ::flowSeverity, emptyText = tr("kami_essentials.bank.movements.empty"), key = "personal-recent")
        if (stale) ui.staleVeil(r)
    }

    private fun placeholder(ui: Ui, r: Rect) {
        ui.skeletonKpis(r.top(TILE_H), 4)
        val (left, right) = r.dropTop(TILE_H + 6).columns(listOf(1.35f, 1f), 6)
        ui.skeleton(ui.card(left, tr("kami_essentials.personal.chart"), Icons.STATS))
        val f = Flow(right, 6)
        ui.skeletonLines(ui.card(f.take(48), tr("kami_libs.common.you"), Icons.PERSON), 2)
        ui.table(ui.card(f.remaining(), tr("kami_essentials.bank.recent"), Icons.LEDGER), movementColumns(), emptyList(), table, ::movementKey, key = "personal-recent", loading = true)
    }
}

private class MovementsPage : Page() {
    override val title get() = tr("kami_essentials.bank.movements")
    override val subtitle get() = Personal.account?.let { tr("kami_essentials.bank.movements.sub", Format.number(it.movements.size)) }
    override val help get() = listOf(Callout("bank:movements", tr("kami_essentials.bank.movements"), tr("kami_essentials.bank.help.movements")))
    private val table = TableState<Movement>()

    override fun draw(ui: Ui, r: Rect) {
        if (PersonalApp.instance.noAccount) return ui.noAccount(r)
        ui.anchor("bank:movements", r)
        ui.table(r, movementColumns(), Personal.account?.movements.orEmpty(), table, ::movementKey, severity = ::flowSeverity, emptyText = tr("kami_essentials.bank.movements.empty"), key = "bank-movements", loading = Personal.loading)
        ui.staleRows(r, stale, loading = true)
    }
}

private class BreakdownPage : Page() {
    override val title get() = tr("kami_essentials.bank.breakdown")
    override val help get() = listOf(
        Callout("bank:range", tr("kami_essentials.bank.range"), tr("kami_essentials.bank.help.range")),
        Callout("bank:flows", tr("kami_essentials.bank.flows"), tr("kami_essentials.bank.help.flows"))
    )
    private val table = TableState<FlowTotal>()

    override fun draw(ui: Ui, r: Rect) {
        if (PersonalApp.instance.noAccount) return ui.noAccount(r)
        val a = Personal.account
        val prefs = PersonalPrefs.prefs
        val head = r.top(CONTROL_H)
        ui.anchor("bank:range", head)
        ui.segmented(Rect(head.x, head.y, 160, CONTROL_H), listOf(Option("week", tr("kami_essentials.bank.range.week")), Option("month", tr("kami_essentials.bank.range.month"))), prefs.bankRange, key = "bank-range")
            ?.let { prefs.bankRange = it; PersonalPrefs.save() }
        val rows = a?.let { if (prefs.bankRange == "month") it.month else it.week }.orEmpty().filter { it.income != 0L || it.expense != 0L }
        val (donuts, flows) = r.dropTop(CONTROL_H + 6).columns(listOf(1f, 1.1f), 6)
        val (inRect, outRect) = donuts.columns(2, 6)
        donutCard(ui, inRect, tr("kami_essentials.bank.income"), Icons.INCOME, rows.filter { it.income > 0 }.map { Slice(it.flow.label, it.income, flowColor(it.flow)) }, "bank-in", a == null)
        donutCard(ui, outRect, tr("kami_essentials.bank.expense"), Icons.EXPENSE, rows.filter { it.expense > 0 }.map { Slice(it.flow.label, it.expense, flowColor(it.flow)) }, "bank-out", a == null)
        ui.anchor("bank:flows", flows)
        val box = ui.card(flows, tr("kami_essentials.bank.flows"), Icons.LEDGER)
        val columns = listOf(
            Column.text<FlowTotal>(tr("kami_essentials.bank.col.flow"), -1, color = { flowColor(it.flow) }) { it.flow.label },
            Column.number<FlowTotal>(tr("kami_essentials.bank.income"), 60, color = { Palette.success }, format = { Format.money(it) }) { it.income },
            Column.number<FlowTotal>(tr("kami_essentials.bank.expense"), 60, color = { Palette.danger }, format = { Format.money(it) }) { it.expense },
            Column.number<FlowTotal>(tr("kami_essentials.bank.col.net"), 72, color = { amountColor(it.income - it.expense) }, format = { Format.signedMoney(it) }) { it.income - it.expense }
        )
        ui.table(box, columns, rows, table, { it.flow }, emptyText = tr("kami_essentials.bank.breakdown.empty"), key = "bank-flows", loading = Personal.loading)
        if (stale) ui.staleVeil(r.dropTop(CONTROL_H + 6))
    }

    private fun donutCard(ui: Ui, r: Rect, title: String, icon: Icon, slices: List<Slice>, key: String, loading: Boolean) {
        val body = ui.card(r, title, icon)
        val size = minOf(body.w, DONUT_MAX, (body.h / 2).coerceAtLeast(20))
        if (loading) {
            ui.skeleton(Rect(body.x + (body.w - size) / 2, body.y, size, size))
            ui.skeletonLines(Rect(body.x, body.y + size + 6, body.w, body.bottom - body.y - size - 6), 2)
            return
        }
        ui.donut(Rect(body.x + (body.w - size) / 2, body.y, size, size), slices, Format.number(slices.sumOf { it.value }), key)
        if (slices.isEmpty()) Draw.textCentered(ui.g, tr("kami_libs.chart.no_data"), Rect(body.x, body.y + size + 2, body.w, 12), Palette.textMuted)
        else ui.legend(body.x, body.y + size + 6, body.w, slices.map { it.color to it.label })
    }
}
