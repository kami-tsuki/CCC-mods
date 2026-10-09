package kami.essentials.client.admin

import kami.libs.economy.CoinAlert
import kami.libs.economy.CoinPlayer
import kami.libs.economy.CoinSourceTotal
import kami.libs.economy.CoinSummary
import kami.libs.economy.UnexplainedAccount
import kami.libs.ui.app.Page
import kami.libs.ui.app.STAT_TILE_H
import kami.libs.ui.app.Paged
import kami.libs.ui.app.Remote
import kami.libs.ui.app.pagedTable
import kami.libs.ui.app.remoteFailed
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

private const val BANNER_H = 30
private const val CHART_H = 140
private const val TABLE_H = 260
private const val UNEXPLAINED_H = 220
private const val GAP = 6
private const val REFRESH_MS = 30_000L
private val RANGES = listOf(7, 30, 90)
private val SORTS = listOf("net", "deposited", "withdrawn", "last")

private fun coinSeverity(alert: CoinAlert) = when (alert) {
    CoinAlert.CRITICAL -> Severity.DANGER
    CoinAlert.WARNING -> Severity.WARNING
    CoinAlert.OK -> null
}

internal class CoinsPage(private val app: AdminApp) : Page() {
    override val title get() = tr("kami_essentials.admin.nav.coins")
    private var range = RANGES.first()
    private var sort = SORTS.first()
    private val summary = Remote("coins.summary", CoinSummary.serializer(), REFRESH_MS) { listOf(range.toString()) }
    private val players = Paged("coins.players", CoinPlayer.serializer()) { listOf(sort) }
    private val unexplained = Paged("money.unexplained", UnexplainedAccount.serializer()) { emptyList() }
    private val unexplainedTable = TableState<UnexplainedAccount>()
    private val sourceTable = TableState<CoinSourceTotal>()
    private val table = TableState<CoinPlayer>()

    override fun draw(ui: Ui, r: Rect) {
        summary.poll()
        val s = summary.data ?: summary.latest
        if (s == null && summary.error != null && !summary.loading) return ui.remoteFailed(r, summary, "coins")
        val severity = s?.let { coinSeverity(it.alert) }
        val height = (if (severity != null) BANNER_H + GAP else 0) + CONTROL_H + STAT_TILE_H + CHART_H + TABLE_H + UNEXPLAINED_H + GAP * 5
        ui.scroll("coins", r, height) { c ->
            val st = Stack(c.x, c.y, c.w, GAP)
            if (s != null && severity != null) ui.banner(st.take(BANNER_H), severity, tr("kami_libs.coins.alert.${s.alert.name.lowercase()}"), tr(s.reason), key = "coins-banner")
            ui.choice(st.take(CONTROL_H), RANGES.map { Option(it, tr("kami_libs.range.${it}d")) }, range, "coins-range")?.let { range = it }
            val kpiBox = st.take(STAT_TILE_H)
            kpis(ui, kpiBox, s)
            val charts = st.take(CHART_H)
            val (chartBox, sourceBox) = charts.columns(listOf(1.6f, 1f), GAP)
            chart(ui, chartBox, s)
            sources(ui, sourceBox, s)
            if (s != null && summary.data == null) ui.staleVeil(Rect(kpiBox.x, kpiBox.y, kpiBox.w, charts.bottom - kpiBox.y), summary.loading)
            playerTable(ui, st.take(TABLE_H))
            unexplainedCard(ui, st.take(UNEXPLAINED_H))
        }
    }

    private fun kpis(ui: Ui, r: Rect, s: CoinSummary?) {
        val rangeIn = s?.days?.sumOf { it.terminalIn }
        val rangeOut = s?.days?.sumOf { it.terminalOut }
        val net = s?.let { it.terminalIn - it.terminalOut }
        val todayNet = s?.let { it.today.terminalIn - it.today.terminalOut }
        val inRange = { v: Long? -> v?.let { tr("kami_essentials.admin.coins.in_range", Format.money(it)) } }
        ui.kpiRow(r, listOf(
            KpiTile(tr("kami_essentials.admin.coins.world"), s?.let { Format.money(it.supply) }, Icons.COIN, if ((s?.supply ?: 0) < 0) Palette.danger else Palette.money,
                sub = s?.let { tr("kami_essentials.admin.in_out", Format.number(it.minted), Format.number(it.burned)) }, flashValue = s?.supply),
            KpiTile(tr("kami_essentials.admin.coins.terminal_in"), s?.let { Format.money(it.terminalIn) }, Icons.DEPOSIT, sub = inRange(rangeIn)),
            KpiTile(tr("kami_essentials.admin.coins.terminal_out"), s?.let { Format.money(it.terminalOut) }, Icons.WITHDRAW, sub = inRange(rangeOut)),
            KpiTile(tr("kami_libs.common.net"), net?.let { Format.signedMoney(it) }, Icons.SCALES, net?.let { if (it > 0) Palette.warning else Palette.text } ?: Palette.text),
            KpiTile(tr("kami_libs.common.today"), todayNet?.let { Format.signedMoney(it) }, Icons.CLOCK,
                sub = s?.let { tr("kami_essentials.admin.in_out", Format.number(it.today.terminalIn), Format.number(it.today.terminalOut)) })
        ), key = "coins-kpi")
    }

    private fun chart(ui: Ui, r: Rect, s: CoinSummary?) {
        val body = ui.card(r, tr("kami_essentials.admin.coins.daily"), Icons.STATS)
        if (s == null) return ui.skeleton(body)
        ui.barChart(body, s.days.map { it.terminalIn }, s.days.map { it.terminalOut }, s.days.map { Format.dayLabel(it.day) },
            format = { Format.money(it) }, key = "coins-daily")
    }

    private fun sources(ui: Ui, r: Rect, s: CoinSummary?) {
        val body = ui.card(r, tr("kami_essentials.admin.coins.sources"), Icons.LEDGER)
        if (s == null) return ui.skeletonTable(body, 3)
        ui.table(body, sourceColumns(), s.sources, sourceTable, { it.source }, emptyText = tr("kami_essentials.admin.coins.sources.empty"), key = "coins-sources")
    }

    private fun playerTable(ui: Ui, r: Rect) {
        val body = ui.card(r, tr("kami_essentials.admin.coins.players"), Icons.PEOPLE)
        ui.choice(body.top(CONTROL_H), SORTS.map { Option(it, if (it == "net") tr("kami_libs.common.net") else tr("kami_essentials.admin.coins.sort.$it")) }, sort, "coins-sort")?.let { sort = it }
        ui.pagedTable(body.dropTop(CONTROL_H, 4), players, playerColumns(), table, { it.uuid }, tr("kami_essentials.admin.coins.players.empty"), "coins-players", { if (it.flagged) Severity.WARNING else null })
            .opened?.let { app.openPlayer(it.uuid) }
    }

    private fun unexplainedCard(ui: Ui, r: Rect) {
        val body = ui.card(r, tr("kami_essentials.admin.coins.unexplained"), Icons.SCALES)
        ui.pagedTable(body, unexplained, unexplainedColumns(), unexplainedTable, { it.uuid }, tr("kami_essentials.admin.coins.unexplained.empty"), "coins-unexplained", { if (it.flagged) Severity.DANGER else null })
            .opened?.takeIf { it.player }?.let { app.openPlayer(it.uuid) }
    }
}

private val sourceMemo = Memo()
private val playerMemo = Memo()
private val unexplainedMemo = Memo()

private fun sourceColumns() = sourceMemo.of(Format.locale) {
    listOf(
        Column.text<CoinSourceTotal>(tr("kami_libs.common.source"), -1) { tr("kami_essentials.admin.coins.source.${it.source.name.lowercase()}") },
        Column.number<CoinSourceTotal>(tr("kami_essentials.admin.coins.minted"), 56, color = { Palette.success }, format = { Format.money(it) }) { it.minted },
        Column.number<CoinSourceTotal>(tr("kami_essentials.admin.coins.burned"), 56, color = { Palette.danger }, format = { Format.money(it) }) { it.burned }
    )
}

private fun playerColumns() = playerMemo.of(Format.locale) {
    listOf(
        playerColumn<CoinPlayer>(tr("kami_libs.common.name"), { it.uuid }, { it.name }),
        Column.secret<CoinPlayer>(tr("kami_essentials.admin.uuid"), 80) { it.uuid },
        Column.number<CoinPlayer>(tr("kami_essentials.admin.coins.deposited"), 64, format = { Format.money(it) }) { it.deposited },
        Column.number<CoinPlayer>(tr("kami_essentials.admin.coins.withdrawn"), 64, format = { Format.money(it) }) { it.withdrawn },
        Column.number<CoinPlayer>(tr("kami_libs.common.net"), 64, color = { if (it.flagged) Palette.warning else Palette.text }, format = { Format.signedMoney(it) }) { it.net },
        Column.number<CoinPlayer>(tr("kami_essentials.admin.coins.last"), 60, format = { Format.ago(it) }) { it.last }
    )
}

private fun unexplainedColumns() = unexplainedMemo.of(Format.locale) {
    listOf(
        playerColumn<UnexplainedAccount>(tr("kami_libs.common.name"), { it.uuid }, { if (it.player) it.name else tr("kami_essentials.admin.coins.source.blaze_banker") }),
        Column.secret<UnexplainedAccount>(tr("kami_essentials.admin.uuid"), 80) { it.uuid },
        Column.number<UnexplainedAccount>(tr("kami_libs.common.total"), 72, color = { if (it.flagged) Palette.danger else Palette.text }, format = { Format.signedMoney(it) }) { it.total },
        Column.number<UnexplainedAccount>(tr("kami_essentials.admin.coins.unexplained.count"), 48) { it.count },
        Column.number<UnexplainedAccount>(tr("kami_essentials.admin.coins.last"), 60, format = { Format.ago(it) }) { it.last }
    )
}
