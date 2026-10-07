package kami.essentials.client.admin

import kami.libs.ui.app.Remote
import kami.libs.ui.app.Paged
import kami.libs.ui.app.pagedTable
import kami.libs.ui.app.remoteFailed
import kami.essentials.admin.Dashboard
import kami.essentials.admin.NameRow
import kami.libs.admin.Audit
import kami.libs.economy.Journal
import kami.libs.economy.ServerBank
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.label
import kami.libs.ui.text.tr
import kami.libs.ui.text.trOr
import kami.libs.ui.widget.*
import kami.libs.util.Time
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID
import kami.libs.economy.Flow as MoneyFlow
import kami.libs.ui.text.noteLabel

private const val TILE_H = 44
private const val BANNER_H = 30
private const val CHART_H = 96
private const val CHART_DAYS = 30
private const val SAMPLE_MS = 300_000L
private const val REFRESH_MS = 10_000L
private const val METRIC_H = 30
private const val METRIC_W = 90
private const val MB = 1_048_576L
private const val AMOUNT_W = 52
private const val COIN_ALERT = "kami_libs.metric.coin_alert"

private fun metricLabel(key: String) = trOr(key, key.substringAfterLast('.').replace('_', ' ').replaceFirstChar { it.uppercase() })

internal fun amountColor(v: Long) = if (v > 0) Palette.success else if (v < 0) Palette.danger else Palette.textMuted

private fun flowColor(f: MoneyFlow) = Palette.chart[f.ordinal % Palette.chart.size]

private fun amountArg(s: TextState) = Format.parseAmount(s.text)?.toString().orEmpty()

internal class JournalFilter(private val key: String, private val withParty: Boolean) {
    private val party = TextState()
    private var partyKey: String? = null
    private val text = TextState()
    private val min = TextState()
    private val max = TextState()
    private val range = TimeRange("$key:range")
    private val flows = LinkedHashSet<MoneyFlow>()
    private var showFlows = false
    private val countries = Remote("countries.names", MapSerializer(String.serializer(), String.serializer()))
    private var prefix = ""
    private val names = Remote("players.names", ListSerializer(NameRow.serializer())) { listOf(prefix) }

    fun args(): List<String> =
        (if (withParty) listOf(partyKey ?: party.text.trim()) else emptyList()) +
            listOf(flows.joinToString(",") { it.name }, amountArg(min), amountArg(max), range.from?.toString().orEmpty(), "", text.text.trim())

    fun draw(ui: Ui, r: Rect): Rect {
        val rest = ui.filterBar(r, "$key:filters") { bar ->
            val row = Row(bar, 6)
            if (ui.iconButton(row.takeFromRight(CONTROL_H), Icons.FILTER, tr("kami_essentials.admin.filter.flows"), selected = showFlows || flows.isNotEmpty(), key = "$key:flows")) showFlows = !showFlows
            range.draw(ui, row.takeFromRight(range.width(bar.w < NARROW_W)))
            val amount = { c: Char -> c.isDigit() || c in "kmbKMB.," }
            val maxBox = row.takeFromRight(AMOUNT_W)
            ui.textField(row.takeFromRight(AMOUNT_W), min, tr("kami_essentials.admin.filter.min"), allow = amount, key = "$key:min")
            ui.textField(maxBox, max, tr("kami_essentials.admin.filter.max"), allow = amount, key = "$key:max")
            if (withParty) {
                countries.poll()
                names.poll()
                val players = names.data.orEmpty().onEach { AdminData.remember(it.uuid, it.name) }.mapNotNull { n -> runCatching { UUID.fromString(n.uuid) }.getOrNull()?.let { n.name to it } }.toMap()
                ui.partyField(row.take(minOf(110, (row.rest.w - 6) / 2)), party, { countries.data.orEmpty().entries.associate { (id, name) -> name to id } }, { players }, { prefix = it }, key = "$key:party") { partyKey = it }
            }
            ui.textField(row.remaining(), text, tr("kami_essentials.admin.filter.text"), Icons.SEARCH, key = "$key:text", clearable = true)
        }
        if (!showFlows) return rest
        val stack = Stack(rest.x, rest.y, rest.w, 3)
        ui.chipFlow(stack, MoneyFlow.entries.map { ChipSpec(it.label, flowColor(it), selected = it in flows) }, "$key:chips")
            ?.let { i -> MoneyFlow.entries[i].let { if (!flows.remove(it)) flows += it } }
        return rest.dropTop(stack.bottom - rest.y + 3)
    }
}

internal fun journalColumns(withParty: Boolean) = listOfNotNull(
    Column.number<Journal.Entry>(tr("kami_essentials.admin.col.when"), 56, format = { Format.ago(it) }) { it.at },
    if (withParty) whoColumn<Journal.Entry>(tr("kami_essentials.admin.col.party"), 90) { it.party } else null,
    Column.text<Journal.Entry>(tr("kami_essentials.admin.col.flow"), 76, color = { flowColor(it.flow) }) { it.flow.label },
    whoColumn<Journal.Entry>(tr("kami_essentials.admin.col.counterparty"), 90) { it.counterparty },
    Column.text<Journal.Entry>(tr("kami_essentials.admin.col.note"), -1) { noteLabel(it.note) },
    Column.number<Journal.Entry>(tr("kami_essentials.admin.col.amount"), 70, color = { amountColor(it.delta) }, format = { Format.signedMoney(it) }) { it.delta }
)

private fun journalSeverity(e: Journal.Entry) = if (e.delta >= 0) Severity.SUCCESS else Severity.DANGER

internal class DashboardPage(private val app: AdminApp) : Page() {
    override val title get() = tr("kami_essentials.admin.nav.dashboard")
    private val remote = Remote("server.dashboard", Dashboard.serializer(), REFRESH_MS)
    private val table = TableState<Audit.Entry>()

    override fun draw(ui: Ui, r: Rect) {
        remote.poll()
        val d = remote.data ?: run {
            if (remote.error != null && !remote.loading) ui.remoteFailed(r, remote, "dashboard") else placeholder(ui, r)
            return
        }
        val coinAlert = d.metrics[COIN_ALERT] ?: 0L
        val metrics = d.metrics.entries.filter { it.key != COIN_ALERT }
        val main = if (coinAlert < 1) r else r.dropTop(BANNER_H + 6).also {
            val critical = coinAlert >= 2
            if (ui.banner(r.top(BANNER_H), if (critical) Severity.DANGER else Severity.WARNING, tr(if (critical) "kami_libs.coins.alert.critical" else "kami_libs.coins.alert.warning"),
                    tr("kami_essentials.admin.coins.banner"), tr("kami_essentials.admin.coins.open"), key = "coin-alert")) app.navigate(Route("coins"))
        }
        val tpsColor = if (d.tps >= 19.5) Palette.success else if (d.tps >= 15) Palette.warning else Palette.danger
        ui.kpiRow(main.top(TILE_H), listOf(
            KpiTile(tr("kami_essentials.admin.tps"), Format.decimal(d.tps), Icons.CLOCK, tpsColor, sub = tr("kami_essentials.admin.mspt", Format.decimal(d.mspt))),
            KpiTile(tr("kami_libs.common.online"), tr("kami_libs.format.ratio", d.online, d.max), Icons.PEOPLE,
                sub = tr("kami_essentials.admin.today", Format.number(d.uniqueToday), Format.number(d.newToday)), spark = d.onlineSeries.map { it.toLong() },
                onClick = { app.navigate(Route("players")) }),
            KpiTile(tr("kami_essentials.admin.uptime"), Format.duration(d.uptimeMs), Icons.GEAR,
                sub = tr("kami_essentials.admin.memory", Format.number(d.memUsed / MB), Format.number(d.memMax / MB))),
            KpiTile(tr("kami_essentials.admin.nav.bank"), Format.money(d.bank), Icons.TREASURY, Palette.money,
                trend = Trend(d.bankNet24h, tr("kami_essentials.admin.net24h", Format.signed(d.bankNet24h))), onClick = { app.navigate(Route("bank")) })
        ), key = "admin-kpi")
        val (left, right) = main.dropTop(TILE_H + 6).columns(listOf(1.4f, 1f), 6)
        val cols = cardColumns(left.w - 10, METRIC_W, 4)
        val metricsH = if (metrics.isEmpty()) 0 else 24 + (metrics.size + cols - 1) / cols * (METRIC_H + 4)
        val chart = ui.card(left.dropBottom(metricsH, if (metricsH > 0) 6 else 0), tr("kami_essentials.admin.online_chart"), Icons.STATS)
        if (d.onlineSeries.size < 2) Draw.textCentered(ui.g, tr("kami_libs.chart.no_data"), chart.top(20), Palette.textMuted)
        else {
            val now = System.currentTimeMillis()
            val n = d.onlineSeries.size
            ui.lineChart(chart, listOf(Series(tr("kami_libs.common.online"), d.onlineSeries.map { it.toLong() }, Palette.chart[1], area = true)),
                List(n) { Format.ago(now - (n - 1 - it) * SAMPLE_MS) }, format = { Format.number(it) }, key = "admin-online")
        }
        if (metricsH > 0) {
            val body = ui.card(left.bottom(metricsH), tr("kami_essentials.admin.metrics"), Icons.DASHBOARD)
            body.grid(cols, METRIC_H, metrics.size, 4).forEachIndexed { i, cell ->
                ui.statTile(cell, metricLabel(metrics[i].key), Format.compact(metrics[i].value), key = "metric:${metrics[i].key}")
            }
        }
        val audit = ui.card(right, tr("kami_essentials.admin.latest_audit"), Icons.SCROLL)
        ui.table(audit, compactAuditColumns(), d.audit, table, { it.id }, severity = ::auditSeverity, emptyText = tr("kami_essentials.admin.audit.empty"), key = "admin-audit")
            .opened?.let { app.auditDialog(it) }
    }

    private fun placeholder(ui: Ui, r: Rect) {
        ui.kpiRow(r.top(TILE_H), listOf(
            KpiTile(tr("kami_essentials.admin.tps"), null, Icons.CLOCK),
            KpiTile(tr("kami_libs.common.online"), null, Icons.PEOPLE),
            KpiTile(tr("kami_essentials.admin.uptime"), null, Icons.GEAR),
            KpiTile(tr("kami_essentials.admin.nav.bank"), null, Icons.TREASURY, Palette.money)
        ), key = "admin-kpi")
        val (left, right) = r.dropTop(TILE_H + 6).columns(listOf(1.4f, 1f), 6)
        ui.skeleton(ui.card(left, tr("kami_essentials.admin.online_chart"), Icons.STATS))
        ui.table(ui.card(right, tr("kami_essentials.admin.latest_audit"), Icons.SCROLL), compactAuditColumns(), emptyList(), table, { it.id }, key = "admin-audit", loading = true)
    }
}

internal class BankPage : Page() {
    override val title get() = tr("kami_essentials.admin.nav.bank")
    private val summary = Remote("bank.summary", ServerBank.Summary.serializer())
    private val filter = JournalFilter("bank", false)
    private val paged = Paged("bank.movements", Journal.Entry.serializer()) { filter.args() }
    private val table = TableState<Journal.Entry>()

    override fun draw(ui: Ui, r: Rect) {
        summary.poll()
        val s = summary.data ?: summary.latest
        if (s == null && summary.error != null && !summary.loading) return ui.remoteFailed(r, summary, "bank")
        val today = System.currentTimeMillis() / Time.DAY_MS
        val days = (today - CHART_DAYS + 1..today).map { d -> s?.days.orEmpty().filter { it.day == d } }
        val inflow = days.map { l -> l.sumOf { it.inflow } }
        val outflow = days.map { l -> l.sumOf { it.outflow } }
        fun net(n: Int) = inflow.takeLast(n).sum() - outflow.takeLast(n).sum()
        fun netTile(label: String, n: Int) = KpiTile(label, s?.let { Format.signedMoney(net(n)) }, Icons.STATS, amountColor(net(n)),
            sub = s?.let { tr("kami_essentials.admin.in_out", Format.compact(inflow.takeLast(n).sum()), Format.compact(outflow.takeLast(n).sum())) })
        ui.kpiRow(r.top(TILE_H), listOf(
            KpiTile(tr("kami_essentials.admin.balance"), s?.let { Format.money(it.balance) }, Icons.TREASURY, Palette.money, flashValue = s?.balance),
            netTile(tr("kami_essentials.admin.range.1d"), 1),
            netTile(tr("kami_essentials.admin.range.7d"), 7),
            netTile(tr("kami_essentials.admin.range.30d"), CHART_DAYS)
        ), key = "bank-kpi")
        val chartBox = r.dropTop(TILE_H + 6).top(CHART_H)
        val chart = ui.card(chartBox, tr("kami_essentials.admin.bank.daily"), Icons.STATS)
        if (s == null) ui.skeleton(chart)
        else ui.barChart(chart, inflow, outflow, List(days.size) { Format.dayLabel(today - CHART_DAYS + 1 + it) }, format = { Format.money(it, compact = true) }, key = "bank-daily")
        val body = filter.draw(ui, r.dropTop(TILE_H + CHART_H + 12))
        ui.pagedTable(body, paged, journalColumns(false), table, { it.id }, tr("kami_essentials.admin.journal.empty"), "bank-moves", ::journalSeverity)
    }
}

internal class JournalPage(private val app: AdminApp) : Page() {
    override val title get() = tr("kami_essentials.admin.nav.journal")
    private val filter = JournalFilter("journal", true)
    private val paged = Paged("journal.query", Journal.Entry.serializer()) { filter.args() }
    private val table = TableState<Journal.Entry>()

    override fun draw(ui: Ui, r: Rect) {
        val body = filter.draw(ui, r)
        ui.pagedTable(body, paged, journalColumns(true), table, { it.id }, tr("kami_essentials.admin.journal.empty"), "journal", ::journalSeverity)
            .opened?.party?.takeIf { it.startsWith("p:") }?.let { app.openPlayer(it.drop(2)) }
    }
}
