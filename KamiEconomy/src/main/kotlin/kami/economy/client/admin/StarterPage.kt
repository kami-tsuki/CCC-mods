package kami.economy.client.admin

import kami.economy.StarterGood
import kami.economy.admin.StarterList
import kami.economy.admin.StarterRow
import kami.economy.client.ui.ROW_H
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
import kami.libs.ui.widget.toggle
import kotlinx.serialization.json.Json

private val OPTIONAL = setOf("target", "cap", "max")

private fun limit(v: Int?) = v?.takeIf { it > 0 }?.let { Format.compact(it.toLong()) } ?: "-"

private fun limitsTip(g: StarterGood) = Tip(tr("kami_economy.admin.col.limits"), listOf(
    "${tr("kami_economy.admin.col.depth")}: ${Format.number(g.depth)}" to Palette.textSecondary,
    "${tr("kami_economy.admin.col.cap")}: ${limit(g.dailyCap)}" to Palette.textSecondary,
    "${tr("kami_economy.admin.col.max")}: ${limit(g.max)}" to Palette.textSecondary
))

internal class StarterPage(private val app: KamiApp) : Page() {
    override val title get() = tr("kami_economy.admin.nav.starter")
    private val remote = Remote("market.starter", StarterList.serializer())
    private val box = MarketSection.searchBox()
    private val table = TableState<StarterRow>()

    private fun num(key: String, width: Int, value: (StarterGood) -> Int?) =
        Column.text<StarterRow>(tr("kami_economy.admin.col.$key"), width, Align.RIGHT) { value(it.good)?.let { v -> Format.number(v) } ?: "-" }

    private fun columns(rev: String, lock: String?) = listOf(
        itemColumn<StarterRow>(tr("kami_libs.common.item"), { it.good.item }),
        num("base", 40) { it.base },
        num("lot", 28) { it.lot },
        num("target", 36) { it.target },
        tipColumn<StarterRow>(tr("kami_economy.admin.col.limits"), 52, compareBy { it.good.depth }, { limitsTip(it.good) }, Align.RIGHT) {
            "${Format.compact(it.good.depth.toLong())}/${limit(it.good.dailyCap)}/${limit(it.good.max)}"
        },
        Column<StarterRow>("∞", 22, tip = tr("kami_economy.admin.col.infinite")) { _, cell, row ->
            toggle(cell.inset(0, 1), row.good.infinite, enabled = lock == null, disabledReason = lock, tip = tr("kami_economy.admin.col.infinite"), key = "market-starter:inf:${row.good.item}")
                ?.let { upsert(row.good.copy(infinite = it), rev, tr("kami_economy.admin.starter.edit")) }
        },
        Column.text<StarterRow>(tr("kami_economy.admin.col.stock"), 44, Align.RIGHT) { if (it.good.infinite) "∞" else it.stock?.let { v -> Format.number(v) } ?: "-" },
        Column<StarterRow>("", 3 * ROW_H + 4) { _, cell, row ->
            val (statusR, editR, removeR) = cell.columnsFixed(cell.h, cell.h, cell.h, gap = 2)
            val tip = if (row.status.isEmpty()) tr("kami_economy.admin.status.edit") else statusTip(row.status, row.statusReason)
            if (iconButton(statusR, Icons.LOCK, tip, lock == null, row.status.isNotEmpty(), key = "market-starter:status:${row.good.item}", disabledReason = lock))
                app.statusDialog(row.good.item, row.status, row.statusRev)
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

    private fun edit(rev: String, good: StarterGood?, existing: Set<String>) {
        val g = good ?: StarterGood("", 1)
        val item = TextState(g.item)
        val fields = linkedMapOf(
            "base" to NumberState(g.base.toLong()), "lot" to NumberState(g.lot.toLong()), "target" to NumberState(g.target.toLong()),
            "depth" to NumberState(g.depth.toLong()), "cap" to NumberState((g.dailyCap ?: 0).toLong()), "max" to NumberState((g.max ?: 0).toLong())
        )
        var infinite = g.infinite
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
            checkbox(Rect(b.x, y, b.w, 12), tr("kami_economy.admin.col.infinite"), infinite, key = "starter-infinite")?.let { infinite = it }
            y += 16
            s.used = y - b.y
            val error = item.error ?: fields.entries.firstNotNullOfOrNull { (k, st) -> st.text.error?.let { "${tr("kami_economy.admin.col.$k")}: $it" } }
            dialogButtons(s, tr("kami_libs.common.confirm"), error == null, error) {
                val v = fields.mapValues { (Format.parseAmount(it.value.text.text) ?: it.value.value).toInt() }
                upsert(StarterGood(spec, v.getValue("base"), v.getValue("lot"), v.getValue("target"), v.getValue("depth"), v.getValue("cap").takeIf { it > 0 }, infinite, v.getValue("max").takeIf { it > 0 }), rev, title)
                s.close()
            }
        })
    }
}
