package kami.economy.client.admin

import kami.economy.admin.VillagerAdmin
import kami.economy.admin.VillagerOffer
import kami.economy.admin.VillagerOverview
import kami.economy.admin.VillagerRow
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.Page
import kami.libs.ui.app.Remote
import kami.libs.ui.app.adminConfirmAct
import kami.libs.ui.app.adminManager
import kami.libs.ui.app.adminPending
import kami.libs.ui.app.lockUnless
import kami.libs.ui.app.remoteTable
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

private const val REFRESH_MS = 5_000L
private const val TILE_H = 44
private const val GAP = 6
private const val PROP_H = 12

internal class VillagerPage(private val app: KamiApp) : Page() {
    private val remote = Remote(VillagerAdmin.VIEW, VillagerOverview.serializer(), REFRESH_MS)
    private val goods = TableState<VillagerRow>()
    private val offers = TableState<VillagerOffer>()
    private val goodsMemo = Memo()
    private val offersMemo = Memo()
    private val course = TextState()
    private var lock = true
    private var selected: String? = null

    override val title get() = tr("kami_economy.admin.villager.title")
    override val subtitle get() = tr("kami_economy.admin.villager.help")

    override fun draw(ui: Ui, r: Rect) {
        remote.poll()
        val d = remote.data
        tiles(ui, r.top(TILE_H), d)
        val (left, right) = r.dropTop(TILE_H + GAP).columns(listOf(1.2f, 1f), 8)
        val rows = d?.rows.orEmpty()
        ui.remoteTable(left, remote, goodsColumns(), rows, goods, { it.spec }, tr("kami_economy.admin.villager.empty"), "villager-goods", severity = { severity(it.state) })
        goods.selected.lastOrNull()?.let { selected = it.toString() }
        val (top, bottom) = right.rows(listOf(0.42f, 0.58f), 8)
        detail(ui, top, rows.firstOrNull { it.spec == selected })
        near(ui, bottom, d)
    }

    private fun tiles(ui: Ui, r: Rect, d: VillagerOverview?) {
        val rows = d?.rows.orEmpty()
        val seeds = rows.count { it.state == "seed" }
        val on = d?.enabled == true
        val emerald = d?.emerald ?: 0.0
        ui.kpiRow(r, listOf(
            KpiTile(tr("kami_libs.common.status"), tr(if (on) "kami_libs.common.on" else "kami_libs.common.off"), Icons.CHECK, if (on) Palette.success else Palette.danger),
            KpiTile(tr("kami_economy.admin.villager.emerald"), if (emerald > 0) Format.rateMoney(emerald) else "-", Icons.COIN, if (emerald > 0) Palette.money else Palette.danger,
                sub = tr("kami_economy.admin.villager.no_emerald").takeIf { d != null && emerald <= 0 }),
            KpiTile(tr("kami_economy.admin.villager.tracked"), Format.number(rows.size.toLong()), Icons.LAYERS),
            KpiTile(tr("kami_economy.admin.villager.discovery"), Format.number(seeds.toLong()), Icons.SEARCH, if (seeds > 0) Palette.warning else Palette.text),
            KpiTile(tr("kami_economy.admin.villager.volume"), Format.money(d?.volume ?: 0), Icons.STATS, Palette.money)
        ), key = "villager-kpi")
    }

    private fun detail(ui: Ui, r: Rect, row: VillagerRow?) {
        val body = ui.card(r, row?.spec ?: tr("kami_economy.admin.villager.pick"), Icons.COIN, trailing = row?.let { state(it.state) }, key = "villager-detail")
        if (row == null) return
        val down = lockUnless(adminManager)
        fun prop(i: Int) = body.dropTop(PROP_H * i).top(PROP_H)
        ui.property(prop(0), tr("kami_economy.admin.villager.course"), Format.rateMoney(row.course), Palette.money)
        ui.property(prop(1), tr("kami_economy.admin.villager.floor"), Format.rateMoney(row.floor))
        ui.property(prop(2), tr("kami_economy.admin.villager.fills"), Format.number(row.fills))
        val input = Row(body.dropTop(PROP_H * 3 + GAP).top(CONTROL_H), GAP)
        ui.textField(input.take(80), course, Format.rate(row.course), allow = { it.isDigit() || it == '.' }, key = "villager-course:${row.spec}")
        ui.checkbox(input.take(60), tr("kami_economy.admin.villager.lock"), lock, key = "villager-lock")?.let { lock = it }
        val value = course.text.toDoubleOrNull()?.takeIf { it > 0 }
        action(ui, input, tr("kami_economy.admin.villager.set"), Icons.CHECK, down ?: tr("kami_economy.admin.villager.bad_course").takeIf { value == null }, false,
            VillagerAdmin.SET, listOf(row.spec, value.toString(), if (lock) "1" else "0"), tr("kami_economy.admin.villager.set.title", row.spec, value?.let(Format::rateMoney).orEmpty()))
        val bar = Row(body.dropTop(PROP_H * 3 + GAP * 2 + CONTROL_H).top(CONTROL_H), GAP)
        action(ui, bar, tr("kami_economy.admin.villager.unlock"), Icons.LOCK, down ?: tr("kami_economy.admin.villager.not_locked").takeIf { row.state != "locked" }, false,
            VillagerAdmin.UNLOCK, listOf(row.spec), tr("kami_economy.admin.villager.unlock.title", row.spec))
        action(ui, bar, tr("kami_economy.admin.villager.reset"), Icons.REMOVE, down, true, VillagerAdmin.RESET, listOf(row.spec), tr("kami_economy.admin.villager.reset.title", row.spec))
    }

    private fun near(ui: Ui, r: Rect, d: VillagerOverview?) {
        val v = d?.near
        val body = ui.card(r, v?.name ?: tr("kami_economy.admin.villager.near"), Icons.PEOPLE, trailing = v?.takeIf { it.epoch >= 0 }?.let { tr("kami_economy.admin.villager.epoch", it.epoch) }, key = "villager-near")
        val empty = tr(if (v == null) "kami_economy.admin.villager.none_near" else "kami_economy.admin.villager.no_quote")
        ui.table(body, offerColumns(), v?.offers.orEmpty(), offers, { it.spec + it.side + it.price }, emptyText = empty, key = "villager-offers")
    }

    private fun action(ui: Ui, row: Row, label: String, icon: Icon, why: String?, danger: Boolean, act: String, args: List<String>, title: String) {
        if (ui.edgeButton(row, label, icon, if (danger) ButtonStyle.DANGER else ButtonStyle.SECONDARY, why == null, why, pending = adminPending(act, args), left = true, key = "villager:$act"))
            app.adminConfirmAct(title, act, args, danger)
    }

    private fun state(s: String) = tr("kami_economy.admin.villager.state.$s")

    private fun severity(s: String) = when (s) {
        "seed" -> Severity.WARNING
        "locked" -> Severity.NEUTRAL
        else -> null
    }

    private fun side(s: Int) = tr("kami_economy.admin.villager.side.$s")

    private fun goodsColumns() = goodsMemo.of(Format.locale) { listOf(
        Column.text<VillagerRow>(tr("kami_libs.common.item"), -1) { it.spec },
        Column.text<VillagerRow>(tr("kami_libs.common.status"), 60) { state(it.state) },
        Column.text<VillagerRow>(tr("kami_economy.admin.villager.course"), 60) { Format.rateMoney(it.course) },
        Column.number<VillagerRow>(tr("kami_economy.admin.villager.fills"), 40) { it.fills }
    ) }

    private fun offerColumns() = offersMemo.of(Format.locale) { listOf(
        Column.text<VillagerOffer>(tr("kami_libs.common.item"), -1) { it.spec },
        Column.text<VillagerOffer>(tr("kami_libs.common.type"), 44) { side(it.side) },
        Column.text<VillagerOffer>(tr("kami_libs.common.price"), 54) { if (it.price < 0) "-" else Format.money(it.price) },
        Column.text<VillagerOffer>(tr("kami_economy.admin.villager.spread"), 40) { Format.percent(it.spread, 1) },
        Column.text<VillagerOffer>(tr("kami_economy.admin.villager.demand"), 40) { Format.percent(it.demand - 1) },
        Column.text<VillagerOffer>(tr("kami_economy.admin.villager.uses"), 40) { "${it.uses}/${it.max}" }
    ) }
}
