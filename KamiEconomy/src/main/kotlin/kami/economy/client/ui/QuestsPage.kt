package kami.economy.client.ui

import kami.economy.LOG
import kami.economy.client.admin.MarketSection
import kami.economy.net.QuestRow
import kami.libs.ui.app.SELECT_W
import kami.libs.ui.app.ReceiptKind
import kami.libs.ui.app.ReceiptLine
import kami.libs.ui.app.Route
import kami.libs.ui.app.receiptDialog
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.UiPrefs
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import net.minecraft.util.Mth
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val MAX_CARDS = 20
private const val MAX_TIER = 5
private const val CARD_GAP = 6
private const val Y_NAME = 5
private const val Y_RARITY = Y_NAME + Draw.LINE + 1
private const val Y_PRICE = Y_RARITY + Draw.LINE + 2
private const val Y_BAR = Y_PRICE + Draw.LINE + 3
private const val Y_CHIPS = Y_BAR + Draw.LINE + 2
private const val Y_INFO = Y_CHIPS + Draw.LINE + 6
private const val CARD_BASE_H = Y_INFO + Draw.LINE + 1
private const val ING_H = 16
private const val QTY_W = 96
private const val GO_W = 70
private const val BANNER_H = 22
private const val RAINBOW_MS = 3000L
private const val RARITIES = 4

internal const val QUEST_BANNER_H = BANNER_H

internal fun questColor(rarity: Int): Int = when {
    rarity >= RARITIES -> if (UiPrefs.prefs.reduceMotion) Palette.accent else 0xFF000000.toInt() or Mth.hsvToRgb((System.currentTimeMillis() % RAINBOW_MS) / RAINBOW_MS.toFloat(), 0.75f, 0.95f)
    rarity == 1 -> Palette.success
    rarity == 2 -> Palette.warning
    rarity == 3 -> Palette.link
    else -> Palette.info
}

internal fun questName(rarity: Int) = tr("kami_economy.quest.rarity.${rarity.coerceIn(0, RARITIES)}")

private fun QuestRow.per() = if (course > 0) ((lotPrice / course - 1) * 100).roundToInt() else 0

private fun QuestRow.total() = amount.toLong() / lot.coerceAtLeast(1) * lotPrice

private fun QuestRow.left() = (amount - filled).coerceAtLeast(0)

private fun percentColor(p: Int) = if (p > 0) Palette.success else if (p < 0) Palette.danger else Palette.textMuted

private fun stateSeverity(state: String) = when (state) { "done" -> Severity.SUCCESS; "open" -> Severity.INFO; "expired" -> Severity.WARNING; else -> Severity.NEUTRAL }

private fun kindText(kind: String) = tr("kami_economy.quest.kind.$kind")

private fun leftColor(q: QuestRow) = if (q.expiresAt - System.currentTimeMillis() < SOON_MS) Palette.danger else Palette.textSecondary

internal fun Ui.questBanner(app: MarketApp, r: Rect, q: QuestRow) {
    val c = questColor(q.rarity)
    panel(r)
    Draw.outline(g, r, c)
    val y = r.y + (r.h - 8) / 2
    val label = tr("kami_economy.quest.ui.banner", questName(q.rarity))
    val x = Draw.text(g, label, r.x + 6, y, c) + 8
    val go = Rect(r.right - GO_W - 4, r.y + 3, GO_W, r.h - 6)
    val left = Format.duration(q.expiresAt - System.currentTimeMillis())
    val info = tr("kami_economy.quest.ui.banner.info", Format.unitMoney(q.lotPrice.toLong(), q.lot), Format.number(q.left()), left)
    fitText(x, y, (go.x - x - 6).coerceAtLeast(0), info, Palette.textSecondary)
    if (button(go, tr("kami_economy.quest.ui.deliver"), style = ButtonStyle.PRIMARY, key = "quest-banner-go")) app.navigate(Route("quests"))
}

internal class QuestsPage(app: MarketApp) : ListPage(app, "quests") {
    private var history = false
    private var state = ""
    private val expanded = HashSet<Long>()
    private val amounts = HashMap<Long, NumberState>()
    private val warned = HashSet<Long>()
    private val table = TableState<QuestRow>().apply { sortColumn = 6; descending = true }
    override val remoteSearch get() = false
    override val title get() = tr("kami_libs.flow.quest")

    private val tabsMemo = Memo()
    private val statesMemo = Memo()

    private val columns by localized { listOf(
        itemColumn<QuestRow>({ it.item }, { "quest-h:${it.id}" }, { questColor(it.rarity) }) { app.openItem(it.item) },
        Column.text<QuestRow>(tr("kami_economy.quest.ui.col.rarity"), 64, color = { questColor(it.rarity) }) { questName(it.rarity) },
        Column<QuestRow>(tr("kami_libs.common.status"), 70, sort = textOrder { tr("kami_economy.quest.ui.state.${it.state}") }) { _, r, row ->
            statusPill(r.x, r.y + (r.h - 13) / 2, tr("kami_economy.quest.ui.state.${row.state}"), stateSeverity(row.state), key = "qs:${row.id}", maxWidth = r.w)
        },
        Column.number<QuestRow>(tr("kami_economy.quest.ui.col.fill"), 46, format = { Format.percent(it / 100.0) }) { if (it.amount > 0) it.filled * 100L / it.amount else 0L },
        Column.text<QuestRow>(tr("kami_economy.quest.ui.col.winner"), 90) { it.winner ?: "–" },
        Column.text<QuestRow>(tr("kami_economy.quest.ui.col.top"), -1, color = { Palette.textSecondary }) { q -> q.top.take(3).joinToString(" · ") { "${it.name} ${Format.number(it.value)}" }.ifEmpty { "–" } },
        Column.number<QuestRow>(tr("kami_economy.quest.ui.col.ended"), 60, format = { if (it > 0) Format.ago(it) else "–" }) { it.endedAt }
    ) }

    override fun draw(ui: Ui, r: Rect) {
        val source = data?.quests.orEmpty()
        val all = filtered(ui, "quests:rows", source) { hit(it.item) }
        val open = ui.filtered("quests:open", listOf(all)) { all.filter { it.state == "open" }.take(MAX_CARDS) }
        val done = ui.filtered("quests:done", listOf(all, state)) { all.filter { it.state != "open" && (state.isEmpty() || it.state == state) } }
        val openCount = source.count { it.state == "open" }.coerceAtMost(MAX_CARDS)
        val tabs = tabsMemo.of(openCount, Format.locale) { listOf(TabItem(tr("kami_economy.quest.ui.tab.open"), badge = openCount), TabItem(tr("kami_economy.quest.ui.tab.history"))) }
        ui.subTabs(r.top(18), tabs, if (history) 1 else 0, "quest-tabs")?.let { history = it == 1 }
        val bar = r.dropTop(22).top(SMALL_H)
        val area = r.dropTop(22 + SMALL_H + 6)
        if (history) {
            val states = statesMemo.of(Format.locale) { listOf("", "done", "expired", "cancelled").map { Option(it, if (it.isEmpty()) tr("kami_libs.common.all") else tr("kami_economy.quest.ui.state.$it")) } }
            searchBar(ui, bar.dropRight(SELECT_W, BAR_GAP))
            ui.select(bar.right(SELECT_W), states, state, key = "quest-state")?.let { state = it }
            ui.table(area, columns, done, table, { it.id }, rowHeight = ROW_H, emptyText = tr("kami_economy.quest.ui.history.empty"), key = "quest-history", loading = loading).opened?.let { app.openItem(it.item) }
            ui.staleRows(area, stale && done.isNotEmpty())
        } else {
            searchBar(ui, bar)
            cards(ui, area, open)
        }
    }

    private fun height(q: QuestRow) = CARD_BASE_H + CONTROL_H + 6 + if (q.id in expanded) q.ingredients.size * ING_H + 4 else 0

    private fun cards(ui: Ui, area: Rect, open: List<QuestRow>) {
        if (data?.view != "quests") return ui.skeleton(area.top(CARD_BASE_H))
        if (open.isEmpty()) {
            ui.emptyState(area, tr("kami_economy.quest.ui.empty"), "", key = "quest-empty")
            return
        }
        val held = app.carried.get().held
        val ids = open.map { it.id }.toSet()
        amounts.keys.retainAll(ids)
        expanded.retainAll(ids)
        ui.scroll("quest-cards", area, open.sumOf { height(it) + CARD_GAP }) { c ->
            var y = c.y
            open.forEach { q ->
                val h = height(q)
                runCatching { card(ui, Rect(c.x, y, c.w, h), q, held[q.item] ?: 0) }.onFailure {
                    if (warned.add(q.id)) LOG.warn("Quest card {} failed", q.id, it)
                }
                y += h + CARD_GAP
            }
        }
    }

    private fun card(ui: Ui, r: Rect, q: QuestRow, held: Int) {
        val g = ui.g
        val c = questColor(q.rarity)
        val lot = q.lot.coerceAtLeast(1)
        val stack = MarketSection.stack(q.item)
        val name = stack.hoverName.string
        val x = r.x + 6
        val w = r.w - 12
        ui.panel(r)
        Draw.outline(g, r, c)
        Draw.outline(g, r.inset(1), Palette.alpha(c, 0xA0))
        if (ui.itemSlot(Rect(x, r.y + 5, 18, 18), stack, key = "quest-item:${q.id}")) app.openItem(q.item)
        val time = Format.duration(q.expiresAt - System.currentTimeMillis())
        val timeW = Draw.width(time) + Draw.ICON + 2
        Draw.leadIcon(g, Icons.CLOCK, r.right - 6 - timeW, r.y + 9, leftColor(q))
        Draw.textRight(g, time, r.right - 6, r.y + 5, leftColor(q))
        val nameW = Draw.width(name, TextStyle.HEADING)
        ui.fitText(x + 24, r.y + 5, w - timeW - 28, name, c, TextStyle.HEADING)
        if (ui.clickable("quest-name:${q.id}", Rect(x + 24, r.y + 4, nameW.coerceAtMost(w - timeW - 28), 11))) app.openItem(q.item)
        var tx = Draw.text(g, questName(q.rarity), x + 24, r.y + Y_RARITY, c) + 6
        repeat(MAX_TIER) { i ->
            Draw.fill(g, Rect(tx + i * 6, r.y + Y_RARITY + 1, 4, 4), if (i < q.tier) c else Palette.alpha(Palette.border, 0xC0))
        }
        ui.tooltip("quest-tier:${q.id}", Rect(tx, r.y + Y_RARITY - 1, MAX_TIER * 6, 8), tr("kami_economy.quest.ui.tier.tip", q.tier, q.score))
        val p = q.per()
        val priceY = r.y + Y_PRICE
        val price = tr("kami_economy.quest.ui.price", Format.unitMoney(q.lotPrice.toLong(), lot))
        tx = Draw.text(g, price, x, priceY, Palette.money) + 4
        Draw.text(g, signedPercent(p.toDouble()), tx, priceY, percentColor(p))
        ui.tooltip("quest-course:${q.id}", Rect(x, priceY - 1, Draw.width(price) + 36, 10), tr("kami_economy.quest.ui.course.tip", Format.unitMoney(q.course.let { if (it.isFinite()) it.roundToLong() else 0L }, lot)))
        Draw.textRight(g, tr("kami_economy.quest.ui.total", Format.money(q.total())), r.right - 6, priceY, Palette.money)
        val count = "${Format.number(q.filled)} / ${Format.number(q.amount)}"
        val countW = Draw.width(count)
        ui.progressBar(Rect(x, r.y + Y_BAR, w - countW - 6, 8), q.filled.toLong(), q.amount.toLong(), color = c, key = "quest-bar:${q.id}")
        Draw.textRight(g, count, r.right - 6, r.y + Y_BAR, Palette.textSecondary)
        var cx = x
        q.boosts.forEachIndexed { i, b ->
            val label = tr("kami_economy.quest.ui.boost", kindText(b.kind), b.pct, b.hours)
            if (cx + Draw.width(label) + 8 > r.right - 6) return@forEachIndexed
            cx += ui.chip(cx, r.y + Y_CHIPS, label, Palette.info, tip = tr("kami_economy.quest.ui.boost.tip", kindText(b.kind), b.pct, b.hours), key = "quest-boost:${q.id}:$i:${b.kind}") + 4
        }
        val free = tr("kami_economy.quest.ui.taxfree")
        if (cx + Draw.width(free) + 8 <= r.right - 6) ui.chip(cx, r.y + Y_CHIPS, free, Palette.success, key = "quest-free:${q.id}")
        val infoY = r.y + Y_INFO
        val ix = Draw.text(g, tr("kami_economy.quest.ui.you", Format.number(q.mine)), x, infoY, Palette.brass) + 8
        val lead = q.leader?.let { tr("kami_economy.quest.ui.leading", it, Format.number(q.leaderQty)) }
        val own = q.myCountry?.let { tr("kami_economy.quest.ui.country", it, Format.number(q.myCountryQty)) }
        val rest = listOfNotNull(lead, own).joinToString("  ·  ")
        ui.fitText(ix, infoY, (r.right - 6 - ix).coerceAtLeast(0), rest, Palette.textSecondary)
        actions(ui, Rect(x, r.y + CARD_BASE_H - 2, w, CONTROL_H), q, lot, held)
        if (q.id in expanded) {
            var y = r.y + CARD_BASE_H + CONTROL_H + 2
            q.ingredients.forEach { ing ->
                val row = Rect(x + 4, y, w - 4, ING_H)
                val st = MarketSection.stack(ing.item)
                val price = Format.unitMoney((ing.price * 1000).let { if (it.isFinite()) it.roundToLong() else 0L }, 1000)
                val pw = Draw.width(price) + 4
                if (ui.itemCell(row.dropRight(pw), st, st.hoverName.string, "quest-ing:${q.id}:${ing.item}", Palette.textSecondary)) app.openItem(ing.item)
                Draw.textRight(g, price, row.right, row.y + (row.h - 8) / 2, Palette.textMuted)
                y += ING_H
            }
        }
    }

    private fun actions(ui: Ui, r: Rect, q: QuestRow, lot: Int, held: Int) {
        val cap = minOf(held, q.left()) / lot * lot
        val can = cap >= lot
        val state = amounts.getOrPut(q.id) { NumberState(cap.coerceAtLeast(lot).toLong()) }
        if (can && state.value > cap) state.commit(cap.toLong())
        ui.numberField(r.left(QTY_W), state, lot.toLong(), cap.coerceAtLeast(lot).toLong(), step = lot.toLong(), enabled = can, key = "quest-qty:${q.id}")
        val reason = if (!app.snap.citizen) tr("kami_libs.lock.no_country") else tr("kami_economy.quest.ui.none")
        val go = Rect(r.x + QTY_W + 6, r.y, GO_W, r.h)
        if (ui.button(go, tr("kami_economy.quest.ui.deliver"), style = ButtonStyle.PRIMARY, enabled = can && app.snap.citizen, disabledReason = reason, key = "quest-go:${q.id}")) confirm(q, state.value.toInt(), lot)
        if (q.ingredients.isEmpty()) return
        val open = q.id in expanded
        val label = tr("kami_economy.quest.ui.ingredients", q.ingredients.size)
        val toggle = Rect(r.right - Draw.width(label) - 18, r.y, Draw.width(label) + 18, r.h)
        if (ui.disclosure(toggle, label, open, key = "quest-ing-toggle:${q.id}")) if (open) expanded.remove(q.id) else expanded.add(q.id)
    }

    private fun confirm(q: QuestRow, qty: Int, lot: Int) {
        val stack = MarketSection.stack(q.item)
        val pay = qty.toLong() / lot * q.lotPrice
        val lines = listOf(
            ReceiptLine(stack.hoverName.string, unitsText(qty, stack.maxStackSize)),
            ReceiptLine(tr("kami_economy.ticket.price_item"), Format.unitMoney(q.lotPrice.toLong(), lot)),
            ReceiptLine(tr("kami_economy.quest.ui.receipt.pay"), Format.money(pay), ReceiptKind.TOTAL),
            ReceiptLine(tr("kami_economy.quest.ui.taxfree"), "", ReceiptKind.NOTE)
        )
        receiptDialog(app::open, tr("kami_economy.quest.ui.receipt.title"), Icons.CROWN, lines, tr("kami_economy.quest.ui.deliver"),
            stale = { if (app.cached("quests")?.quests.orEmpty().any { it.id == q.id && it.state == "open" }) null else tr("kami_economy.quest.closed") }
        ) { app.request("quest_deliver", q.id.toString(), qty.toString()) }
    }
}
