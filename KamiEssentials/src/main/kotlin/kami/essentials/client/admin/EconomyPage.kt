package kami.essentials.client.admin

import kami.libs.economy.WealthDay
import kami.libs.ui.app.Page
import kami.libs.ui.app.STAT_TILE_H
import kami.libs.ui.app.Remote
import kami.libs.ui.app.remoteFailed
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.label
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import kotlinx.serialization.builtins.ListSerializer
import kami.libs.economy.Flow as MoneyFlow

private const val CARD_H = 130
private const val FLOW_H = 220
private const val FLOW_CHART_H = 56
private const val GAP = 6
private const val SCROLLBAR_W = 4
private const val REFRESH_MS = 10_000L
private const val GINI_SCALE = 1000.0
private val RANGES = listOf(30, 90, 365)

private class FlowRow(val flow: MoneyFlow, val minted: Long, val voided: Long)

private fun supply(d: WealthDay) = d.players + d.countries + d.countriesNegative + d.escrow + d.coins

internal class EconomyPage : Page() {
    override val title get() = tr("kami_libs.common.economy")
    private var range = RANGES.first()
    private var picked: Int? = null
    private val history = Remote("wealth.history", ListSerializer(WealthDay.serializer())) { listOf(range.toString()) }
    private val live = Remote("wealth.live", WealthDay.serializer(), REFRESH_MS)
    private val table = TableState<FlowRow>()

    override fun draw(ui: Ui, r: Rect) {
        history.poll()
        live.poll()
        val now = live.data
        if (now == null && live.error != null && !live.loading) {
            ui.remoteFailed(r, live, "economy")
            return
        }
        val past = history.data ?: history.latest
        val days = if (now == null || past == null) null else past.filter { it.day < now.day } + now
        val stale = history.data == null
        val foot = tr("kami_essentials.admin.economy.footnote")
        val footH = Draw.paragraphHeight(foot, r.w - SCROLLBAR_W) + 2
        val height = CONTROL_H + STAT_TILE_H + CARD_H * 2 + FLOW_H + footH + GAP * 5
        ui.scroll("economy", r, height) { c ->
            val s = Stack(c.x, c.y, c.w, GAP)
            ui.choice(s.take(CONTROL_H), RANGES.map { Option(it, tr("kami_libs.range.${it}d")) }, range, "eco-range")?.let {
                range = it
                picked = null
            }
            kpis(ui, s.take(STAT_TILE_H), now)
            val (supplyBox, shareBox) = s.take(CARD_H).columns(listOf(1.4f, 1f), GAP)
            supplyChart(ui, supplyBox, days, stale)
            share(ui, shareBox, now)
            val (topBox, bracketBox) = s.take(CARD_H).columns(2, GAP)
            val top = ui.card(topBox, tr("kami_essentials.admin.economy.top"), Icons.CROWN)
            if (now == null) ui.skeleton(top)
            else ui.barChart(top, now.top.map { it.amount.coerceAtLeast(0) }, emptyList(), now.top.map { it.name }, format = { Format.money(it) }, key = "eco-top")
            val brackets = ui.card(bracketBox, tr("kami_essentials.admin.economy.brackets"), Icons.STATS)
            if (now == null) ui.skeleton(brackets)
            else ui.barChart(brackets, now.brackets.map { it.count.toLong() }, emptyList(), now.brackets.map {
                if (it.max <= 0) Format.money(0) else tr("kami_essentials.admin.economy.bracket", Format.number(it.min), Format.number(it.max))
            }, key = "eco-brackets")
            val (giniBox, flowBox) = s.take(FLOW_H).columns(2, GAP)
            giniChart(ui, giniBox, days, stale)
            flows(ui, flowBox, days ?: now?.let(::listOf))
            Draw.paragraph(ui.g, foot, c.x, s.take(footH).y + 2, c.w, Palette.textMuted)
        }
    }

    private fun kpis(ui: Ui, r: Rect, d: WealthDay?) = ui.kpiRow(r, listOf(
        KpiTile(tr("kami_essentials.admin.economy.supply"), d?.let { Format.money(supply(it)) }, Icons.COIN, Palette.money, flashValue = d?.let(::supply)),
        KpiTile(tr("kami_libs.common.players"), d?.let { Format.money(it.players) }, Icons.PEOPLE,
            sub = d?.let { tr("kami_essentials.admin.economy.players.sub", Format.number(it.playerCount.toLong()), Format.money(it.median)) }),
        KpiTile(tr("kami_essentials.admin.economy.countries"), d?.let { Format.money(it.countries) }, Icons.FLAG,
            sub = d?.let { tr("kami_essentials.admin.economy.negative", Format.money(it.countriesNegative)) }),
        KpiTile(tr("kami_essentials.admin.economy.escrow"), d?.let { Format.money(it.escrow) }, Icons.LOCK),
        KpiTile(tr("kami_libs.metric.coins"), d?.let { Format.money(it.coins) }, Icons.COIN),
        KpiTile(tr("kami_essentials.bank.group"), d?.let { Format.money(it.bank) }, Icons.TREASURY, amountColor(d?.bank ?: 0L))
    ), key = "eco-kpi")

    private fun supplyChart(ui: Ui, r: Rect, days: List<WealthDay>?, stale: Boolean) {
        val body = ui.card(r, tr("kami_essentials.admin.economy.supply_chart"), Icons.STATS)
        if (days == null) return ui.skeleton(body)
        ui.lineChart(body, listOf(
            Series(tr("kami_essentials.admin.economy.supply"), days.map(::supply), Palette.chart[0], area = true),
            Series(tr("kami_libs.common.players"), days.map { it.players }, Palette.chart[1]),
            Series(tr("kami_essentials.admin.economy.countries"), days.map { it.countries + it.countriesNegative }, Palette.chart[2])
        ), days.map { Format.dayLabel(it.day) }, format = { Format.money(it) }, key = "eco-supply")
        if (stale) ui.staleVeil(body)
    }

    private fun share(ui: Ui, r: Rect, d: WealthDay?) {
        val body = ui.card(r, tr("kami_libs.common.share"), Icons.PERCENT)
        if (d == null) {
            ui.skeleton(body.left(body.h))
            ui.skeletonLines(body.dropLeft(body.h, GAP), 5)
            return
        }
        val slices = listOf(
            Slice(tr("kami_libs.common.players"), d.players.coerceAtLeast(0), Palette.chart[1]),
            Slice(tr("kami_essentials.admin.economy.countries"), d.countries, Palette.chart[2]),
            Slice(tr("kami_essentials.admin.economy.escrow"), d.escrow, Palette.chart[3]),
            Slice(tr("kami_libs.metric.coins"), d.coins.coerceAtLeast(0), Palette.chart[4]),
            Slice(tr("kami_essentials.bank.group"), d.bank.coerceAtLeast(0), Palette.chart[0])
        )
        ui.donut(body.left(body.h), slices, Format.number(slices.sumOf { it.value }), key = "eco-donut")
        val side = body.dropLeft(body.h, GAP)
        val legend = ui.legend(side.x, side.y, side.w, slices.map { it.color to it.label })
        ui.stackedBar(Rect(side.x, side.y + legend + 4, side.w, 10), slices, key = "eco-stack")
        val props = Stack(side.x, side.y + legend + 20, side.w, 0)
        ui.property(props, tr("kami_essentials.admin.economy.gini_players"), Format.decimal(d.giniPlayers, 3))
        ui.property(props, tr("kami_essentials.admin.economy.gini_countries"), Format.decimal(d.giniCountries, 3))
        ui.property(props, tr("kami_essentials.admin.economy.top10_share"), Format.percent(d.top10Share, 1))
    }

    private fun giniChart(ui: Ui, r: Rect, days: List<WealthDay>?, stale: Boolean) {
        val body = ui.card(r, tr("kami_essentials.admin.economy.gini"), Icons.SCALES)
        if (days == null) return ui.skeleton(body)
        ui.lineChart(body, listOf(
            Series(tr("kami_libs.common.players"), days.map { (it.giniPlayers * GINI_SCALE).toLong() }, Palette.chart[1]),
            Series(tr("kami_essentials.admin.economy.countries"), days.map { (it.giniCountries * GINI_SCALE).toLong() }, Palette.chart[2])
        ), days.map { Format.dayLabel(it.day) }, format = { Format.decimal(it / GINI_SCALE, 3) }, key = "eco-gini")
        if (stale) ui.staleVeil(body)
    }

    private fun flows(ui: Ui, r: Rect, days: List<WealthDay>?) {
        if (days == null) {
            val body = ui.card(r, tr("kami_essentials.admin.economy.flows"), Icons.LEDGER)
            ui.skeleton(body.dropTop(CONTROL_H, 4).top(FLOW_CHART_H))
            ui.skeletonTable(body.dropTop(CONTROL_H + FLOW_CHART_H + 8), 4)
            return
        }
        val index = (picked ?: days.lastIndex).coerceIn(0, days.lastIndex)
        val d = days[index]
        val body = ui.card(r, tr("kami_essentials.admin.economy.flows"), Icons.LEDGER, trailing = Format.dayLabel(d.day))
        ui.pager(body.top(CONTROL_H).right(110), index, days.size, key = "eco-day")?.let { picked = it }
        val rows = (d.minted.keys + d.voided.keys).sortedBy { it.ordinal }.map { FlowRow(it, d.minted[it] ?: 0L, d.voided[it] ?: 0L) }
        val chart = body.dropTop(CONTROL_H, 4).top(FLOW_CHART_H)
        ui.barChart(chart, rows.map { it.minted }, rows.map { it.voided }, rows.map { it.flow.label }, format = { Format.money(it) }, key = "eco-flows")
        ui.table(body.dropTop(CONTROL_H + FLOW_CHART_H + 8), flowColumns(), rows, table, { it.flow }, emptyText = tr("kami_essentials.admin.economy.flows.empty"), key = "eco-flow-table")
    }
}

private val flowMemo = Memo()

private fun flowColumns() = flowMemo.of(Format.locale) {
    listOf(
        Column.text<FlowRow>(tr("kami_libs.common.category"), -1) { it.flow.label },
        Column.number<FlowRow>(tr("kami_essentials.admin.coins.minted"), 64, color = { Palette.success }, format = { Format.money(it) }) { it.minted },
        Column.number<FlowRow>(tr("kami_essentials.admin.coins.burned"), 64, color = { Palette.danger }, format = { Format.money(it) }) { it.voided },
        Column.number<FlowRow>(tr("kami_libs.common.net"), 64, color = { amountColor(it.minted - it.voided) }, format = { Format.signedMoney(it) }) { it.minted - it.voided }
    )
}
