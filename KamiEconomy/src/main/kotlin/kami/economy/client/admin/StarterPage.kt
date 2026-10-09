package kami.economy.client.admin

import kami.economy.StarterGood
import kami.economy.admin.StarterList
import kami.economy.admin.StarterRow
import kami.economy.client.ui.ROW_H
import kami.economy.client.ui.infiniteText
import kami.economy.client.ui.statusTip
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.Page
import kami.libs.ui.app.Remote
import kami.libs.ui.app.adminAct
import kami.libs.ui.app.dialogButtons
import kami.libs.ui.app.lockUnless
import kami.libs.ui.app.adminManager
import kami.libs.ui.app.adminPending
import kami.libs.ui.app.reasonDialog
import kami.libs.ui.app.remoteTable
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.widget.Align
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.NumberState
import kami.libs.ui.widget.TableState
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.checkbox
import kami.libs.ui.widget.edgeButton
import kami.libs.ui.widget.fieldHelp
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.filterBar
import kami.libs.ui.widget.iconButton
import kami.libs.ui.widget.numberField
import kami.libs.ui.widget.searchField
import kami.libs.ui.widget.textField
import kotlinx.serialization.json.Json

private val OPTIONAL = setOf("floor", "ceiling", "sell_cap")

private fun infinite(v: Int) = if (v < 0) Long.MAX_VALUE else v.toLong()

private fun limit(v: Int?) = v?.takeIf { it > 0 }?.let { Format.number(it.toLong()) } ?: "-"

private fun limitsTip(g: StarterGood) = Tip(tr("kami_economy.admin.col.limits"), listOf(
    "${tr("kami_economy.admin.col.depth")}: ${Format.number(g.depth)}" to Palette.textSecondary,
    "${tr("kami_economy.admin.col.spread")}: ${g.spread?.let { Format.percent(it, 1) } ?: "-"}" to Palette.textSecondary,
    "${tr("kami_economy.admin.col.sell_cap")}: ${limit(g.sellCap)}" to Palette.textSecondary,
    "${tr("kami_economy.admin.col.ceiling")}: ${limit(g.ceiling)}" to Palette.textSecondary,
    "${tr("kami_economy.admin.col.resell")}: ${if (g.resell) "✓" else "–"}" to Palette.textSecondary
))

private fun courseText(v: Double) = java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

internal class StarterPage(private val app: KamiApp) : Page() {
    override val title get() = tr("kami_economy.admin.nav.starter")
    private val remote = Remote("market.starter", StarterList.serializer())
    private val box = MarketSection.searchBox()
    private val table = TableState<StarterRow>()

    private fun num(key: String, width: Int, value: (StarterGood) -> Int?) =
        Column.number<StarterRow>(tr("kami_economy.admin.col.$key"), width, format = { if (it < 0) "-" else Format.number(it) }) { value(it.good)?.toLong() ?: -1L }

    private fun columns(rev: String, lock: String?) = listOf(
        itemColumn<StarterRow>(tr("kami_libs.common.item"), { it.good.item }),
        num("base", 40) { it.base },
        num("lot", 28) { it.lot },
        num("floor", 36) { it.floor },
        tipColumn<StarterRow>(tr("kami_economy.admin.col.limits"), 52, compareBy { it.good.depth }, { limitsTip(it.good) }, Align.RIGHT) {
            "${Format.number(it.good.depth.toLong())}/${limit(it.good.sellCap)}/${limit(it.good.ceiling)}"
        },
        Column.number<StarterRow>(tr("kami_economy.col.supply"), 40, format = ::infiniteText) { infinite(it.supply) },
        Column.number<StarterRow>(tr("kami_economy.col.demand"), 40, format = ::infiniteText) { infinite(it.demand) },
        decimalColumn<StarterRow>(tr("kami_economy.admin.col.course"), 44, 1) { it.course },
        Column<StarterRow>("", 4 * ROW_H + 6) { _, cell, row ->
            val (statusR, liveR, editR, removeR) = cell.columnsFixed(cell.h, cell.h, cell.h, cell.h, gap = 2)
            val tip = if (row.status.isEmpty()) tr("kami_economy.admin.status.edit") else statusTip(row.status, row.statusReason)
            if (iconButton(statusR, Icons.LOCK, tip, lock == null, row.status.isNotEmpty(), key = "market-starter:status:${row.good.item}", disabledReason = lock))
                app.statusDialog(row.good.item, row.status, row.statusRev)
            if (iconButton(liveR, Icons.COIN, tr("kami_economy.admin.starter.live"), lock == null, key = "market-starter:live:${row.good.item}", disabledReason = lock))
                live(row)
            if (iconButton(editR, Icons.EDIT, tr("kami_economy.admin.starter.edit"), lock == null, key = "market-starter:edit:${row.good.item}", disabledReason = lock))
                edit(rev, row.good, emptySet())
            if (iconButton(removeR, Icons.REMOVE, tr("kami_libs.common.remove"), lock == null, key = "market-starter:remove:${row.good.item}", disabledReason = lock, pending = adminPending("market.starter_remove", listOf(row.good.item, rev))))
                remove(rev, row.good.item)
        }
    )

    override fun draw(ui: Ui, r: Rect) {
        remote.poll()
        val data = remote.data
        val lock = lockUnless(adminManager)
        val body = ui.filterBar(r, "market-starter:filters") { bar ->
            val row = Row(bar, 6)
            if (ui.edgeButton(row, tr("kami_economy.admin.starter.add"), Icons.ADD, ButtonStyle.PRIMARY, lock == null && data != null, lock, key = "market-starter:add"))
                data?.let { d -> edit(d.rev, null, d.rows.mapTo(HashSet()) { it.good.item }) }
            ui.searchField(row.remaining(), box.text, key = "market-starter:search")
        }
        val rows = box.filter(ui, "market-starter:rows", data?.rows.orEmpty()) { box.hit(it.good.item) }
        val empty = if (box.text.text.isBlank()) tr("kami_economy.admin.starter.empty") else tr("kami_libs.common.nothing_found")
        ui.remoteTable(body, remote, columns(data?.rev.orEmpty(), lock), rows, table, { it.good.item }, empty, "market-starter", { if (it.status.isEmpty()) null else Severity.WARNING }, ROW_H)
            .opened?.let { if (lock == null && data != null) edit(data.rev, it.good, emptySet()) }
    }

    private fun upsert(good: StarterGood, rev: String, title: String) = reasonDialog({ app.open(it) }, title) { reason, _, _ ->
        app.adminAct("market.starter_upsert", listOf(Json.encodeToString(StarterGood.serializer(), good), rev), reason)
    }

    private fun remove(rev: String, key: String) = reasonDialog({ app.open(it) }, tr("kami_economy.admin.starter.remove.title", MarketSection.itemName(key)), danger = true) { reason, _, _ ->
        app.adminAct("market.starter_remove", listOf(key, rev), reason)
    }

    private fun Ui.amountRow(b: Rect, y: Int, label: String, st: NumberState, inf: Boolean, key: String): Boolean? {
        fieldLabel(Rect(b.x, y, b.w, 9), label)
        val cols = Rect(b.x, y + 11, b.w, CONTROL_H).columns(2, 8)
        numberField(cols[0], st, min = 0, max = 1_000_000, enabled = !inf, key = "$key-n")
        return checkbox(cols[1], tr("kami_economy.admin.col.infinite"), inf, key = "$key-inf")
    }

    private fun amount(inf: Boolean, st: NumberState) = if (inf) -1 else (Format.parseAmount(st.text.text) ?: st.value).toInt()

    private fun live(row: StarterRow) {
        val supply = NumberState(row.supply.coerceAtLeast(0).toLong())
        val demand = NumberState(row.demand.coerceAtLeast(0).toLong())
        val course = TextState(courseText(row.course))
        var supplyInf = row.supply < 0
        var demandInf = row.demand < 0
        app.open(Dialog(tr("kami_economy.admin.starter.live"), MarketSection.itemName(row.good.item), Icons.COIN, DialogKind.CONFIRM, 300) { s ->
            val b = s.body
            var y = b.y
            amountRow(b, y, tr("kami_economy.col.supply"), supply, supplyInf, "live-supply")?.let { supplyInf = it }
            y += 11 + CONTROL_H + 6
            amountRow(b, y, tr("kami_economy.col.demand"), demand, demandInf, "live-demand")?.let { demandInf = it }
            y += 11 + CONTROL_H + 6
            val parsed = course.text.trim().toDoubleOrNull()?.takeIf { it > 0 }
            course.error = if (parsed == null) tr("kami_economy.admin.starter.course.invalid") else null
            fieldLabel(Rect(b.x, y, b.w, 9), tr("kami_economy.admin.col.course"))
            textField(Rect(b.x, y + 11, b.w, CONTROL_H), course, "1.0", maxLength = 12, allow = { it.isDigit() || it == '.' }, key = "live-course")
            y += 11 + CONTROL_H + 4
            fieldHelp(Rect(b.x, y, b.w, 9), course, tr("kami_economy.admin.starter.live.help"))
            y += 12
            s.used = y - b.y
            fun send(inf: Boolean, st: NumberState, old: Int) = amount(inf, st).let { if (it == old) "" else if (it < 0) "inf" else it.toString() }
            val next = if (parsed != null && course.text.trim() != courseText(row.course)) course.text.trim() else ""
            val args = listOf(row.good.item, send(supplyInf, supply, row.supply), send(demandInf, demand, row.demand), next)
            val error = course.error ?: supply.text.error.takeUnless { supplyInf } ?: demand.text.error.takeUnless { demandInf }
                ?: tr("kami_economy.admin.quest.ui.unchanged").takeIf { args.drop(1).all { it.isEmpty() } }
            dialogButtons(s, tr("kami_libs.common.confirm"), error == null, error) {
                reasonDialog({ app.open(it) }, tr("kami_economy.admin.starter.live")) { reason, _, _ ->
                    app.adminAct("market.server_set", args, reason)
                }
                s.close()
            }
        })
    }

    private fun edit(rev: String, good: StarterGood?, existing: Set<String>) {
        val g = good ?: StarterGood("", 1)
        val item = TextState(g.item)
        val fields = linkedMapOf(
            "base" to NumberState(g.base.toLong()), "lot" to NumberState(g.lot.toLong()), "depth" to NumberState(g.depth.toLong()),
            "floor" to NumberState((g.floor ?: 0).toLong()),
            "ceiling" to NumberState((g.ceiling ?: 0).toLong()), "sell_cap" to NumberState(g.sellCap.toLong())
        )
        val spread = TextState(g.spread?.let { java.math.BigDecimal(it * 100).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString() } ?: "")
        val supply = NumberState(g.supply.coerceAtLeast(0).toLong())
        val demand = NumberState(g.demand.coerceAtLeast(0).toLong())
        var supplyInf = g.supply < 0
        var demandInf = g.demand < 0
        var resell = g.resell
        val title = tr(if (good == null) "kami_economy.admin.starter.add" else "kami_economy.admin.starter.edit")
        app.open(Dialog(title, good?.let { MarketSection.itemName(it.item) }, Icons.EDIT, DialogKind.CONFIRM, 300) { s ->
            val b = s.body
            var y = b.y
            val spec = item.text.trim()
            item.error = when {
                !MarketSection.valid(spec) -> tr("kami_economy.admin.starter.item.invalid")
                spec in existing -> tr("kami_economy.admin.starter.exists")
                else -> null
            }
            fieldLabel(Rect(b.x, y, b.w, 9), tr("kami_libs.common.item"))
            y += 11
            textField(Rect(b.x, y, b.w, CONTROL_H), item, "minecraft:wheat", enabled = good == null, maxLength = 128, key = "starter-item", autoFocus = good == null)
            y += CONTROL_H + 2
            fieldHelp(Rect(b.x, y, b.w, 9), item, tr("kami_economy.admin.starter.item.help"))
            y += 12
            fields.entries.chunked(2).forEach { pair ->
                val cols = Rect(b.x, y, b.w, 11 + CONTROL_H).columns(2, 8)
                pair.forEachIndexed { i, (k, st) ->
                    fieldLabel(cols[i].top(9), tr("kami_economy.admin.col.$k"))
                    numberField(Rect(cols[i].x, cols[i].y + 11, cols[i].w, CONTROL_H), st, min = if (k in OPTIONAL) 0 else 1, max = 1_000_000, key = "starter-$k")
                }
                y += 11 + CONTROL_H + 6
            }
            val spreadText = spread.text.trim()
            val spreadPct = spreadText.toDoubleOrNull()
            spread.error = if (spreadText.isEmpty() || spreadPct == -1.0 || spreadPct != null && spreadPct in 0.0..50.0) null else tr("kami_economy.admin.starter.spread.invalid")
            fieldLabel(Rect(b.x, y, b.w, 9), tr("kami_economy.admin.starter.spread"))
            textField(Rect(b.x, y + 11, b.w, CONTROL_H), spread, "", maxLength = 6, allow = { it.isDigit() || it == '.' || it == '-' }, key = "starter-spread", suffix = "%")
            y += 11 + CONTROL_H + 4
            fieldHelp(Rect(b.x, y, b.w, 9), spread, null)
            y += 12
            amountRow(b, y, tr("kami_economy.col.supply"), supply, supplyInf, "starter-supply")?.let { supplyInf = it }
            y += 11 + CONTROL_H + 6
            amountRow(b, y, tr("kami_economy.col.demand"), demand, demandInf, "starter-demand")?.let { demandInf = it }
            y += 11 + CONTROL_H + 6
            checkbox(Rect(b.x, y, b.w, 12), tr("kami_economy.admin.col.resell"), resell, key = "starter-resell")?.let { resell = it }
            y += 16
            if (amount(supplyInf, supply) == 0 && amount(demandInf, demand) == 0) {
                val idle = tr("kami_economy.admin.starter.idle")
                y += Draw.paragraph(this.g, idle, b.x, y, b.w, Palette.warning) + 4
            }
            s.used = y - b.y
            val error = item.error ?: spread.error ?: fields.entries.firstNotNullOfOrNull { (k, st) -> st.text.error?.let { "${tr("kami_economy.admin.col.$k")}: $it" } } ?: supply.text.error.takeUnless { supplyInf } ?: demand.text.error.takeUnless { demandInf }
            dialogButtons(s, tr("kami_libs.common.confirm"), error == null, error) {
                val v = fields.mapValues { (Format.parseAmount(it.value.text.text) ?: it.value.value).toInt() }
                upsert(
                    StarterGood(
                        spec, v.getValue("base"), v.getValue("lot"), v.getValue("depth"), amount(supplyInf, supply), amount(demandInf, demand),
                        v.getValue("floor").takeIf { it > 0 }, v.getValue("ceiling").takeIf { it > 0 }, spreadPct?.takeIf { it >= 0 }?.let { it / 100.0 },
                        v.getValue("sell_cap"), resell
                    ), rev, title
                )
                s.close()
            }
        })
    }
}
