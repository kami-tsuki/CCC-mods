package kami.economy.client.admin

import kami.economy.admin.StatusRow
import kami.economy.client.PriceCache
import kami.economy.client.ui.ROW_H
import kami.economy.client.ui.flagsText
import kami.economy.economy.TradeFlag
import kami.libs.admin.Admin
import kami.libs.admin.Page as DataPage
import kami.libs.mc.ItemSpec
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.Page
import kami.libs.ui.app.Paged
import kami.libs.ui.app.adminAct
import kami.libs.ui.app.adminManager
import kami.libs.ui.app.decode
import kami.libs.ui.app.dialogButtons
import kami.libs.ui.app.lockUnless
import kami.libs.ui.app.message
import kami.libs.ui.app.pagedTable
import kami.libs.ui.app.reasonDialog
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.ItemSearch
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.ChipSpec
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.TableState
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.chipFlow
import kami.libs.ui.widget.edgeButton
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.filterBar
import kami.libs.ui.widget.iconButton
import kami.libs.ui.widget.itemSlot
import kami.libs.ui.widget.searchField
import kami.libs.ui.widget.tagMembers
import kami.libs.ui.widget.textField

private const val PICK_ROWS = 3
private const val PICK_SLOT = 20
private val BOTH = setOf(TradeFlag.BUY, TradeFlag.SELL)
private val PRESETS = listOf("none" to emptySet<TradeFlag>(), "both" to BOTH) + TradeFlag.entries.map { it.name.lowercase() to setOf(it) }

internal fun KamiApp.statusDialog(item: String, csv: String, rev: String) {
    val before = TradeFlag.parse(csv)
    var flags = before
    open(Dialog(tr("kami_economy.admin.status.title"), MarketSection.itemName(item), Icons.LOCK, DialogKind.CONFIRM, 300) { s ->
        val st = Stack(s.body.x, s.body.y, s.body.w)
        val help = tr("kami_economy.admin.status.dialog_help")
        val area = st.take(Draw.paragraphHeight(help, s.body.w))
        Draw.paragraph(g, help, area.x, area.y, area.w, Palette.textSecondary)
        val chips = PRESETS.map { (k, set) -> ChipSpec(tr("kami_economy.flag.$k"), Palette.warning, selected = if (set.size == 1) set.first() in flags else flags == set) }
        chipFlow(st, chips, "status-flags")?.let { i ->
            val set = PRESETS[i].second
            flags = when {
                set.size == 1 -> if (set.first() in flags) flags - set else flags + set
                flags == set -> emptySet()
                else -> set
            }
        }
        s.used = st.bottom - s.body.y
        dialogButtons(s, tr("kami_libs.common.confirm"), flags != before, tr("kami_economy.admin.quest.ui.unchanged")) {
            s.close()
            reasonDialog({ this@statusDialog.open(it) }, tr("kami_economy.admin.status.title"), danger = flags.isNotEmpty()) { reason, _, _ ->
                this@statusDialog.adminAct("market.status_set", listOf(item, TradeFlag.csv(flags), rev), reason)
            }
        }
    })
}

internal fun KamiApp.openStatus(item: String) {
    val ids = if (item.startsWith("#")) tagMembers(item).map(ItemSpec::id) + item else listOf(item)
    Admin.request("market.status", listOf(item, ids.joinToString(","), "0")) { r ->
        if (!r.ok) {
            toast(Severity.DANGER, r.message())
            return@request
        }
        val row = r.decode(DataPage.serializer(StatusRow.serializer()))?.rows?.firstOrNull { it.item == item }
        statusDialog(item, row?.flags.orEmpty(), row?.rev ?: "0")
    }
}

internal fun KamiApp.pickItem(onPick: (String) -> Unit) {
    val box = MarketSection.searchBox()
    val id = TextState()
    var hitsFor: String? = null
    var hits = emptyList<String>()
    open(Dialog(tr("kami_economy.admin.status.pick"), null, Icons.SEARCH, DialogKind.CONFIRM, 320) { s ->
        val st = Stack(s.body.x, s.body.y, s.body.w)
        searchField(st.take(CONTROL_H), box.text, key = "status-pick:search")
        if (hitsFor != box.text.text) {
            hitsFor = box.text.text
            hits = if (box.query.isEmpty) emptyList() else ItemSearch.ids(box.query, PriceCache.items).sorted()
        }
        val grid = st.take(PICK_ROWS * PICK_SLOT)
        val cols = (grid.w / PICK_SLOT).coerceAtLeast(1)
        hits.take(cols * PICK_ROWS).forEachIndexed { i, spec ->
            val r = Rect(grid.x + i % cols * PICK_SLOT, grid.y + i / cols * PICK_SLOT, PICK_SLOT - 2, PICK_SLOT - 2)
            if (itemSlot(r, MarketSection.stack(spec), selected = spec == id.text, key = "status-pick:$spec")) id.set(spec)
        }
        fieldLabel(st.take(9), tr("kami_libs.common.item"))
        textField(st.take(CONTROL_H), id, "minecraft:wheat", maxLength = 128, key = "status-pick:id")
        val item = id.text.trim()
        s.used = st.bottom - s.body.y
        dialogButtons(s, tr("kami_libs.common.confirm"), MarketSection.valid(item), tr("kami_economy.admin.starter.item.invalid")) {
            s.close()
            onPick(item)
        }
    })
}

internal class StatusPage(private val app: KamiApp) : Page() {
    override val title get() = tr("kami_economy.admin.nav.status")
    override val subtitle get() = tr("kami_economy.admin.status.help")
    private val box = MarketSection.searchBox()
    private val paged = Paged("market.status", StatusRow.serializer()) { listOf(box.text.text.trim(), MarketSection.ids(box)) }
    private val table = TableState<StatusRow>()

    private fun columns(lock: String?) = listOf(
        itemColumn<StatusRow>(tr("kami_libs.common.item"), { it.item }),
        Column.text<StatusRow>(tr("kami_economy.admin.col.flags"), 110, color = { Palette.warning }) { flagsText(it.flags) },
        Column.text<StatusRow>(tr("kami_libs.admin.reason"), 120) { it.reason },
        Column.text<StatusRow>(tr("kami_libs.common.by"), 70) { it.byName },
        Column.number<StatusRow>(tr("kami_economy.admin.col.placed"), 56, format = { Format.ago(it) }) { it.at },
        Column<StatusRow>("", 18) { _, cell, row ->
            if (iconButton(Rect(cell.x, cell.y, cell.h, cell.h), Icons.EDIT, tr("kami_economy.admin.status.edit"), lock == null, key = "market-status:edit:${row.item}", disabledReason = lock))
                app.statusDialog(row.item, row.flags, row.rev)
        }
    )

    override fun draw(ui: Ui, r: Rect) {
        val lock = lockUnless(adminManager)
        val body = ui.filterBar(r, "market-status:filters") { bar ->
            val row = Row(bar, 6)
            if (ui.edgeButton(row, tr("kami_economy.admin.status.set"), Icons.LOCK, ButtonStyle.PRIMARY, lock == null, lock, key = "market-status:set"))
                app.pickItem { app.openStatus(it) }
            ui.searchField(row.remaining(), box.text, key = "market-status:search")
        }
        val empty = if (box.text.text.isBlank()) tr("kami_economy.admin.status.empty") else tr("kami_libs.common.nothing_found")
        ui.pagedTable(body, paged, columns(lock), table, { it.item }, empty, "market-status", rowHeight = ROW_H)
            .opened?.let { if (lock == null) app.statusDialog(it.item, it.flags, it.rev) }
    }
}
