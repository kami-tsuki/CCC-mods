package kami.economy.client.admin

import kami.libs.ui.core.Memo
import kami.economy.QuestSettings
import kami.economy.admin.DishRow
import kami.economy.admin.QuestAdminRow
import kami.economy.admin.QuestStatsRes
import kami.economy.admin.BoostStat
import kami.economy.admin.RarityStat
import kami.economy.client.ui.ROW_H
import kami.economy.client.ui.questColor
import kami.economy.client.ui.questName
import kami.economy.quest.QuestBoost
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.Page
import kami.libs.ui.app.Paged
import kami.libs.ui.app.Remote
import kami.libs.ui.app.adminAct
import kami.libs.ui.app.adminManager
import kami.libs.ui.app.dialogButtons
import kami.libs.ui.app.lockUnless
import kami.libs.ui.app.pagedTable
import kami.libs.ui.app.reasonDialog
import kami.libs.ui.app.remoteFailed
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.Align
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.NumberState
import kami.libs.ui.widget.TableState
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.checkbox
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.filterBar
import kami.libs.ui.widget.iconButton
import kami.libs.ui.widget.numberField
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.searchField
import kami.libs.ui.widget.skeletonTable
import kami.libs.ui.widget.table
import kami.libs.ui.widget.textField
import kami.libs.util.Time
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

internal fun <T> keyedColumn(title: String, width: Int, key: (T) -> Long, text: (T) -> String) =
    Column<T>(title, width, Align.RIGHT, compareBy(key)) { _, r, row -> fitText(r.x, r.y + (r.h - 8) / 2, r.w, text(row), align = Align.RIGHT, key = "cell:${r.x},${r.y}") }

internal fun <T> decimalColumn(title: String, width: Int, digits: Int, value: (T) -> Double): Column<T> {
    val scale = Math.pow(10.0, digits.toDouble())
    return Column.number(title, width, format = { Format.decimal(it / scale, digits) }) { Math.round(value(it) * scale) }
}

internal class QuestAdminListPage(private val app: KamiApp) : Page() {
    private val listColumns = Memo()
    override val title get() = tr("kami_libs.flow.quest")
    private val box = MarketSection.searchBox()
    private val paged = Paged("market.quest_list", QuestAdminRow.serializer()) { listOf(box.text.text.trim(), MarketSection.ids(box)) }
    private val table = TableState<QuestAdminRow>()

    private fun columns(lock: String?) = listColumns.of(lock, Format.locale) { listOf(
        itemColumn<QuestAdminRow>(tr("kami_libs.common.item"), { it.item }),
        Column.text<QuestAdminRow>(tr("kami_economy.quest.ui.col.rarity"), 54, color = { questColor(it.rarity) }) { questName(it.rarity) },
        Column.number<QuestAdminRow>(tr("kami_libs.common.price"), 52, color = { Palette.money }, format = { Format.money(it) }) { it.lotPrice.toLong() },
        keyedColumn<QuestAdminRow>(tr("kami_economy.quest.ui.col.fill"), 70, { if (it.amount > 0) it.filled * 1000L / it.amount else 0L }) { "${Format.number(it.filled)}/${Format.number(it.amount)}" },
        keyedColumn<QuestAdminRow>(tr("kami_libs.common.time_left"), 56, { if (it.state == "open") it.expiresAt else Long.MAX_VALUE }) { if (it.state == "open") Format.duration(it.expiresAt - System.currentTimeMillis()) else q("state.${it.state}") },
        keyedColumn<QuestAdminRow>(q("col.reach"), 50, { it.deliveries.toLong() }) { "${it.deliveries}/${it.players}/${it.countries}" },
        Column.number<QuestAdminRow>(tr("kami_libs.common.paid"), 52, color = { Palette.money }, format = { Format.money(it) }) { it.paid },
        Column<QuestAdminRow>("", 3 * ROW_H + 4) { _, cell, row ->
            if (row.state != "open") return@Column
            val (cancelR, endR, editR) = cell.columnsFixed(cell.h, cell.h, cell.h, gap = 2)
            if (iconButton(cancelR, Icons.CROSS, q("cancel.tip"), lock == null, key = "quest:cancel:${row.id}", disabledReason = lock)) act("cancel", row, true)
            if (iconButton(endR, Icons.CHECK, q("end.tip"), lock == null, key = "quest:end:${row.id}", disabledReason = lock)) act("end", row, false)
            if (iconButton(editR, Icons.EDIT, q("edit.title"), lock == null, key = "quest:edit:${row.id}", disabledReason = lock)) edit(row)
        }
    ) }

    override fun draw(ui: Ui, r: Rect) {
        val lock = lockUnless(adminManager)
        val body = ui.filterBar(r, "quest-list:filters") { bar -> ui.searchField(Row(bar, 6).remaining(), box.text, key = "quest-list:search") }
        ui.pagedTable(body, paged, columns(lock), table, { it.id }, tr("kami_libs.table.empty"), "quest-list", rowHeight = ROW_H)
            .opened?.let { if (lock == null && it.state == "open") edit(it) }
    }

    private fun act(name: String, row: QuestAdminRow, danger: Boolean) =
        reasonDialog({ app.open(it) }, q("$name.title", MarketSection.itemName(row.item)), danger) { reason, _, _ ->
            app.adminAct("market.quest_$name", listOf(row.id.toString()), reason)
        }

    private fun edit(row: QuestAdminRow) {
        val price = NumberState(row.lotPrice.toLong())
        val amount = NumberState(row.amount.toLong())
        val hours = TextState()
        val boosts = row.boosts.mapTo(ArrayList()) { BoostEdit(it.kind, it.pct.toLong(), it.hours.toLong()) }
        app.open(Dialog(q("edit.title"), MarketSection.itemName(row.item), Icons.EDIT, DialogKind.CONFIRM, 320) { s ->
            val b = s.body
            var y = b.y
            val cols = Rect(b.x, y, b.w, 11 + CONTROL_H).columns(2, 8)
            fieldLabel(cols[0].top(9), tr("kami_libs.common.price"))
            numberField(Rect(cols[0].x, y + 11, cols[0].w, CONTROL_H), price, min = 1, max = 1_000_000_000, key = "quest-edit:price")
            fieldLabel(cols[1].top(9), tr("kami_libs.common.amount"))
            numberField(Rect(cols[1].x, y + 11, cols[1].w, CONTROL_H), amount, min = 1, max = 100_000_000, key = "quest-edit:amount")
            y += 11 + CONTROL_H + 6
            val h = hours.text.trim().toLongOrNull()?.takeIf { it in 1..QuestSettings.MAX_HOURS }
            hours.error = if (hours.text.isNotBlank() && h == null) q("invalid") else null
            fieldLabel(Rect(b.x, y, b.w, 9), q("edit.hours"))
            textField(Rect(b.x, y + 11, b.w, CONTROL_H), hours, "", maxLength = 3, allow = { it.isDigit() }, key = "quest-edit:hours")
            y += 11 + CONTROL_H + 6
            fieldLabel(Rect(b.x, y, b.w, 9), q("boosts"))
            y = boostEditor(b, y + 11, boosts, "quest-edit:boost")
            s.used = y - b.y
            val p = price.int().takeIf { it != row.lotPrice }
            val a = amount.int().takeIf { it != row.amount }
            val bs = boosts.map { it.value() }.takeIf { it != row.boosts }
            val boostError = boosts.any { it.pct.text.error != null || it.hours.text.error != null }
            val error = hours.error ?: price.text.error ?: amount.text.error ?: q("invalid").takeIf { boostError } ?: q("unchanged").takeIf { p == null && a == null && h == null && bs == null }
            dialogButtons(s, tr("kami_libs.common.confirm"), error == null, error) {
                val args = listOf(
                    row.id.toString(), p?.toString().orEmpty(), a?.toString().orEmpty(), h?.let { (System.currentTimeMillis() + it * Time.HOUR_MS).toString() }.orEmpty(),
                    bs?.let { Json.encodeToString(ListSerializer(QuestBoost.serializer()), it) }.orEmpty()
                )
                reasonDialog({ app.open(it) }, q("edit.title")) { reason, _, _ -> app.adminAct("market.quest_edit", args, reason) }
                s.close()
            }
        })
    }
}

internal class QuestDishesPage : Page() {
    private val dishColumns = Memo()
    override val title get() = q("nav.dishes")
    private val box = MarketSection.searchBox()
    private val table = TableState<DishRow>()
    private var onlyQuest = false
    private val paged = Paged("market.quest_dishes", DishRow.serializer()) { listOf(box.text.text.trim(), MarketSection.ids(box), onlyQuest.toString()) }

    private fun columns() = dishColumns.of(Format.locale) { listOf(
        itemColumn<DishRow>(tr("kami_libs.common.item"), { it.item }),
        Column.text<DishRow>(q("col.mod"), 56) { it.mod },
        Column.number<DishRow>(q("col.score"), 34) { it.score.toLong() },
        Column.number<DishRow>(q("col.tier"), 28) { it.tier.toLong() },
        Column.number<DishRow>(tr("kami_libs.common.price"), 48, color = { Palette.money }, format = { Format.money(it) }) { it.price.roundToLong() },
        Column.number<DishRow>(tr("kami_economy.admin.col.lot"), 28) { it.lot.toLong() },
        decimalColumn<DishRow>(tr("kami_economy.admin.col.course"), 40, 1) { it.course },
        Column.text<DishRow>(q("col.inputs"), 90) { it.inputs.joinToString(", ") { i -> MarketSection.itemName(i) } },
        Column.text<DishRow>(q("col.stations"), 60) { it.stations.joinToString(", ") },
        Column.text<DishRow>(q("col.guessed"), 30, Align.CENTER) { if (it.guessed) "✓" else "" },
        Column.text<DishRow>(q("col.quest"), 30, Align.CENTER) { if (it.quest) "✓" else "" }
    ) }

    override fun draw(ui: Ui, r: Rect) {
        val body = ui.filterBar(r, "quest-dishes:filters") { bar ->
            val row = Row(bar, 6)
            ui.checkbox(row.takeFromRight(110), q("only_quest"), onlyQuest, key = "quest-dishes:only")?.let { onlyQuest = it }
            ui.searchField(row.remaining(), box.text, key = "quest-dishes:search")
        }
        ui.pagedTable(body, paged, columns(), table, { it.item }, tr("kami_libs.common.nothing_found"), "quest-dishes", rowHeight = ROW_H)
    }
}

internal class QuestStatsPage : Page() {
    override val title get() = q("nav.stats")
    private val remote = Remote("market.quest_stats", QuestStatsRes.serializer(), 15_000L)
    private val rarities = TableState<RarityStat>()
    private val boosts = TableState<BoostStat>()

    override fun draw(ui: Ui, r: Rect) {
        remote.poll()
        val d = remote.data ?: remote.latest
        if (d == null) {
            if (remote.error != null && !remote.loading) ui.remoteFailed(r, remote, "quest-stats") else ui.skeletonTable(r, 4)
            return
        }
        val line1 = q("stats.sum", Format.number(d.total), Format.number(d.open), Format.money(d.payout), Format.number(d.deliveries))
        val line2 = q("stats.next", if (d.nextSpawn > 0) Format.duration(d.nextSpawn - System.currentTimeMillis()) else "-") + "  " +
            (2..4).joinToString("  ") { "${questName(it)}: ${d.lastSpawn.getOrNull(it - 2)?.let { t -> Format.ago(t) } ?: "-"}" }
        Draw.text(ui.g, line1, r.x, r.y, Palette.text)
        Draw.text(ui.g, line2, r.x, r.y + 12, Palette.textSecondary)
        val rest = r.dropTop(28)
        val parts = rest.rows(listOf(1.3f, 1f), 6)
        ui.table(
            parts[0], listOf(
                Column.text<RarityStat>(tr("kami_economy.quest.ui.col.rarity"), -1, color = { questColor(it.rarity) }) { questName(it.rarity) },
                Column.number<RarityStat>(tr("kami_libs.common.total"), 40) { it.total.toLong() },
                Column.number<RarityStat>(q("state.open"), 40) { it.open.toLong() },
                Column.number<RarityStat>(q("state.done"), 40) { it.done.toLong() },
                Column.number<RarityStat>(q("state.expired"), 46) { it.expired.toLong() },
                Column.number<RarityStat>(q("state.cancelled"), 46) { it.cancelled.toLong() },
                Column.number<RarityStat>(tr("kami_libs.common.paid"), 60, color = { Palette.money }, format = { Format.money(it) }) { it.payout },
                Column.text<RarityStat>(q("stats.fill"), 50, Align.RIGHT) { if (it.amount > 0) Format.percent(it.filled.toDouble() / it.amount, 0) else "-" }
            ), d.rarities, rarities, { it.rarity }, emptyText = tr("kami_libs.chart.no_data"), key = "quest-stats:rarities"
        )
        ui.table(
            parts[1], listOf(
                Column.text<BoostStat>(q("stats.boost"), -1) { tr("kami_economy.quest.kind.${it.kind}") },
                Column.number<BoostStat>(q("stats.count"), 50) { it.count.toLong() },
                Column.text<BoostStat>(q("stats.avg_pct"), 60, Align.RIGHT) { if (it.count > 0) Format.percent(it.pctSum.toDouble() / it.count / 100) else "-" },
                Column.number<BoostStat>(q("stats.hours"), 60) { it.hoursSum }
            ), d.boosts, boosts, { it.kind }, emptyText = tr("kami_libs.chart.no_data"), key = "quest-stats:boosts"
        )
    }
}
