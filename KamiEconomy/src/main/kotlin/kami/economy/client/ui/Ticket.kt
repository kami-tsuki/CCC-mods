package kami.economy.client.ui

import kami.economy.net.Detail
import kami.economy.net.Quote
import kami.libs.economy.CleanStep
import kami.libs.ui.app.ReceiptKind
import kami.libs.ui.app.ReceiptLine
import kami.libs.ui.app.receiptDialog
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

private const val QUOTE_DELAY = 200L
private const val FAT_FINGER_PCT = 15L
private val RESET_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

internal fun returnReason(q: Quote, lot: Int) = when (q.reason) {
    "lot" -> tr("kami_economy.action.return.lot", lot)
    "step" -> tr("kami_economy.action.return.step", q.step)
    "slots" -> tr("kami_economy.action.return.slots")
    "exists" -> tr("kami_economy.action.return.exists")
    "crosses" -> tr("kami_economy.action.return.crosses")
    "liquidity" -> tr("kami_economy.ticket.liquidity")
    else -> ""
}

private fun Quote.sig() = listOf(mode, item, qty, price, filled, listed, returned, total, tax, tariff)

internal fun confirmQuote(app: MarketApp, q: Quote, lot: Int, warning: String? = null) {
    val stack = app.stack(q.item)
    val size = stack.maxStackSize
    val buying = q.mode == "buy" || q.mode == "bid"
    val sign = if (buying) "+" else "-"
    val reason = returnReason(q, lot).let { if (it.isEmpty()) "" else " ($it)" }
    val lines = buildList {
        add(ReceiptLine(stack.hoverName.string, unitsText(q.qty, size)))
        if (q.filled > 0) {
            add(ReceiptLine(tr("kami_economy.receipt.avg"), Format.money(q.avg.toLong()), tip = tr("kami_economy.receipt.avg.tip") + " " + tr("kami_economy.receipt.sweep", Format.money(q.worst.toLong()), q.levels)))
            add(ReceiptLine(tr("kami_economy.receipt.subtotal"), Format.money(q.gross), tip = tr("kami_economy.receipt.subtotal.tip")))
            if (q.tax > 0) add(ReceiptLine(tr("kami_economy.receipt.tax", q.taxPct), sign + Format.money(q.tax), ReceiptKind.CHARGE,
                tr("kami_economy.receipt.tax.tip") + q.relation.takeIf { it.isNotEmpty() }?.let { " " + tr("kami_economy.market.rate.$it.tip") }.orEmpty()))
            if (q.tariff > 0) add(ReceiptLine(tr("kami_economy.receipt.tariff", q.tariffPct), sign + Format.money(q.tariff), ReceiptKind.CHARGE, tr("kami_economy.receipt.tariff.tip")))
        }
        add(ReceiptLine(tr(if (buying) "kami_economy.receipt.pay" else "kami_economy.receipt.receive"), Format.money(q.total), ReceiptKind.TOTAL, tr("kami_economy.receipt.total.tip")))
        if (q.listed > 0) add(ReceiptLine("", tr("kami_economy.receipt.listed.${q.mode}", unitsText(q.listed, size), Format.money(q.price.toLong()), Format.money(q.listGross - q.listTax)), ReceiptKind.NOTE, tr("kami_economy.ticket.tip.rest")))
        if (q.returned > 0) add(ReceiptLine("", tr("kami_economy.receipt.returned", unitsText(q.returned, size)) + reason, ReceiptKind.NOTE, tr("kami_economy.ticket.tip.returned")))
        add(ReceiptLine(tr("kami_economy.receipt.balance"), Format.money(app.snap.funds) + " → " + Format.money(q.after.toLong()), tip = tr("kami_economy.ticket.tip.after")))
    }
    val sig = q.sig()
    receiptDialog(
        app::open, tr("kami_economy.receipt.title.${q.mode}"), Icons.COIN, lines, tr("kami_economy.ticket.act.${q.mode}"), warning,
        stale = { app.snap.quote?.takeIf { it.sig() != sig }?.let { tr("kami_economy.receipt.stale") } }
    ) {
        when (q.mode) {
            "buy", "sell_market" -> app.request(q.mode, q.item, q.qty.toString())
            else -> app.request(q.mode, q.item, q.qty.toString(), q.price.toString())
        }
    }
}

internal class Ticket(private val app: MarketApp) {
    private var item = ""
    private var buy = true
    private var order = false
    private val qty = NumberState(1)
    private val price = NumberState(1)
    private var qtyFor = ""
    private var priceFor = ""
    private var lastSig = ""
    private var changedAt = 0L
    private var sent = true

    private val mode get() = when {
        buy && order -> "bid"
        buy -> "buy"
        order -> "sell"
        else -> "sell_market"
    }

    fun open(item: String, buy: Boolean, order: Boolean) {
        this.item = item
        this.buy = buy
        this.order = order
        qtyFor = ""
        priceFor = ""
    }

    private fun seed(d: Detail) {
        if (qtyFor != item) { qtyFor = item; qty.commit(d.lot.toLong()) }
        val key = "$item|$buy"
        if (priceFor != key) {
            priceFor = key
            price.commit(listOf(if (buy) d.sell else d.buy, if (buy) d.buy else d.sell, d.last, 1).first { it > 0 }.toLong())
        }
    }

    private fun track(): Quote? {
        val n = qty.value.toInt()
        val p = if (order) price.value.toInt() else 0
        val sig = "$mode|$item|$n|$p"
        val now = System.currentTimeMillis()
        if (sig != lastSig) { lastSig = sig; changedAt = now; sent = false }
        if (!sent && n > 0 && now - changedAt >= QUOTE_DELAY) {
            sent = true
            app.request("quote", mode, item, n.toString(), p.toString())
        }
        return app.snap.quote?.takeIf { it.mode == mode && it.item == item && it.qty == n && (it.price == p || mode == "sell") }
    }

    private fun blocked(d: Detail, q: Quote?): String? = when {
        !app.snap.citizen -> tr("kami_libs.lock.no_country")
        !buy && d.held <= 0 -> tr("kami_economy.market.sell.none")
        q == null -> tr("kami_economy.market.buy.pending")
        q.filled + q.listed <= 0 -> returnReason(q, d.lot).takeIf { it.isNotEmpty() }?.let { tr("kami_economy.ticket.blocked", it) }
            ?: tr(if (buy) "kami_economy.action.nothing_available" else "kami_economy.action.no_buyers")
        buy && q.total > app.snap.funds -> tr("kami_economy.market.buy.funds")
        else -> null
    }

    private fun fatFinger(d: Detail): String? {
        if (!order) return null
        val ref = (if (buy) d.buy else d.sell).takeIf { it > 0 } ?: d.last
        return tr("kami_economy.ticket.fat").takeIf { ref > 0 && abs(price.value - ref) * 100 > ref * FAT_FINGER_PCT }
    }

    private class Line(val text: String, val color: Int, val tip: String)

    private class Fill(val qty: Long, val why: String)

    private fun fill(d: Detail, lot: Int): Fill {
        val (qty, why) = when {
            !buy -> d.held.toLong() to "held"
            order -> app.snap.funds / price.value.coerceAtLeast(1) * lot to "funds"
            d.maxAffordable < d.available -> d.maxAffordable.toLong() to "funds"
            else -> d.maxAffordable.toLong() to "supply"
        }
        return if (qty <= d.maxAmount) Fill(qty, why) else Fill(d.maxAmount.toLong(), "max")
    }

    private fun summary(q: Quote?, d: Detail, size: Int): List<Line> {
        if (q == null) return listOf(Line(tr("kami_economy.market.calculating"), Palette.textMuted, tr("kami_economy.ticket.tip.calculating")))
        return buildList {
            if (q.filled > 0) {
                add(Line(tr("kami_economy.ticket.now.${q.mode}", unitsText(q.filled, size), Format.money(q.total)), Palette.text, tr("kami_economy.ticket.tip.now")))
                add(Line(tr("kami_economy.ticket.sweep", Format.money(q.avg.toLong()), Format.money(q.worst.toLong()), q.levels), Palette.textMuted, tr("kami_economy.ticket.tip.sweep")))
            }
            if (q.listed > 0) add(Line(tr("kami_economy.ticket.rest.${q.mode}", unitsText(q.listed, size), Format.money(q.price.toLong()), Format.money(q.listGross - q.listTax)), Palette.textSecondary, tr("kami_economy.ticket.tip.rest")))
            if (q.returned > 0) add(Line(tr("kami_economy.receipt.returned", unitsText(q.returned, size)) + returnReason(q, d.lot).let { if (it.isEmpty()) "" else " ($it)" }, Palette.warning, tr("kami_economy.ticket.tip.returned")))
            if (q.mode == "sell" && q.price > 0 && q.price != price.value.toInt()) add(Line(tr("kami_economy.ticket.forced", Format.money(q.price.toLong())), Palette.warning, tr("kami_economy.ticket.tip.forced")))
            add(Line(tr("kami_economy.ticket.after", Format.money(q.after.toLong())), Palette.textMuted, tr("kami_economy.ticket.tip.after")))
            if (q.guaranteed > 0) add(Line(tr("kami_economy.ticket.guaranteed", q.guaranteed), Palette.success, tr("kami_economy.stock.tip")))
        }
    }

    private fun stat(ui: Ui, f: Flow, key: String, label: String, value: String, tip: String) {
        val r = f.take(10)
        Draw.text(ui.g, label, r.x, r.y, Palette.textMuted)
        Draw.textRight(ui.g, Draw.fit(value, r.w - Draw.width(label) - 6), r.right, r.y)
        ui.tooltip("stat:$key", r, tip)
    }

    private fun note(ui: Ui, f: Flow, key: String, text: String, tip: String) {
        val area = f.take(Draw.paragraphHeight(text, f.rest.w))
        Draw.paragraph(ui.g, text, area.x, area.y, area.w, Palette.textSecondary)
        ui.tooltip("note:$key", area, tip)
    }

    private fun stockNote(ui: Ui, f: Flow, d: Detail) {
        val s = d.stock ?: return
        if (buy) {
            if (d.infinite) note(ui, f, "never", tr("kami_economy.stock.never"), tr("kami_economy.stock.tip.infinite"))
            return
        }
        if (order) return
        val reset = LocalTime.ofInstant(Instant.ofEpochMilli(d.resetAt), ZoneId.systemDefault()).format(RESET_FORMAT)
        val text = when {
            d.stockBid > 0 && s.capLeft > 0 -> tr("kami_economy.stock.guaranteed", Format.number(minOf(s.room, s.capLeft) * d.lot), Format.money(d.stockBid.toLong()), reset)
            d.stockBid > 0 -> tr("kami_economy.stock.curve", Format.money(d.stockBid.toLong()))
            d.infinite && s.capLeft > 0 -> tr("kami_economy.stock.orders")
            d.infinite -> tr("kami_economy.stock.capped", reset)
            else -> tr("kami_economy.stock.full")
        }
        note(ui, f, "stock", text, tr("kami_economy.stock.tip") + if (d.infinite) " " + tr("kami_economy.stock.tip.infinite") else "")
    }

    private fun presetTip(key: String, units: Int, fill: Fill, why: String) =
        tr(key, Format.number(units)) + if (units > fill.qty) " " + tr("kami_economy.ticket.preset.capped", Format.number(fill.qty), why) else ""

    fun draw(ui: Ui, f: Flow, d: Detail?) {
        if (d == null) {
            Draw.text(ui.g, tr("kami_economy.market.calculating"), f.rest.x, f.rest.y, Palette.textMuted)
            return
        }
        seed(d)
        val lot = d.lot.coerceAtLeast(1)
        val size = app.stack(item).maxStackSize
        ui.segmented(f.take(SMALL_H), listOf(Option(true, tr("kami_economy.ticket.buy"), description = tr("kami_economy.ticket.buy.tip")), Option(false, tr("kami_economy.ticket.sell"), description = tr("kami_economy.ticket.sell.tip"))), buy, key = "side")?.let { buy = it }
        ui.segmented(f.take(SMALL_H), listOf(Option(false, tr("kami_economy.ticket.instant"), description = tr("kami_economy.ticket.instant.tip")), Option(true, tr("kami_economy.ticket.order"), description = tr("kami_economy.ticket.order.tip"))), order, key = "kind")?.let { order = it }
        val fill = fill(d, lot)
        val why = tr("kami_economy.limit.${fill.why}", Format.number(d.maxAmount))
        val limit = (if (buy && order) d.maxAmount.toLong() else fill.qty).coerceAtLeast(1)
        stat(ui, f, "held", tr("kami_economy.col.held"), unitsText(d.held, size), tr("kami_economy.ticket.tip.held"))
        if (buy) stat(ui, f, "afford", tr("kami_economy.ticket.afford"), unitsText(d.maxAffordable, size), tr("kami_economy.ticket.tip.afford"))
        stat(ui, f, "available", tr("kami_economy.ticket.available"), unitsText(d.available, size), tr("kami_economy.ticket.tip.available"))
        stat(ui, f, "demand", tr("kami_economy.col.demand"), unitsText(d.demand, size), tr("kami_economy.ticket.tip.demand"))
        stat(ui, f, "lot", tr("kami_economy.col.lot"), tr("kami_economy.ticket.lot_size", Format.number(lot)), tr("kami_economy.ticket.tip.lot", Format.number(lot), Format.number(d.maxAmount)))
        stockNote(ui, f, d)
        val hint = stackHint(qty.value.toInt(), size).takeIf { it.isNotEmpty() }?.let { "= $it" }
        ui.fieldLabel(f.take(9), tr("kami_economy.ticket.qty"), hint)
        ui.numberField(f.take(CONTROL_H), qty, 1, limit, key = "qty")
        val presets = f.take(SMALL_H).columns(3, 3)
        if (ui.button(presets[0], tr("kami_economy.ticket.lot"), tip = presetTip("kami_economy.ticket.preset.lot", lot, fill, why), key = "p-lot")) qty.commit(lot.toLong().coerceAtMost(limit))
        if (ui.button(presets[1], tr("kami_economy.ticket.stack"), enabled = size > 1, disabledReason = tr("kami_economy.ticket.preset.nostack"), tip = presetTip("kami_economy.ticket.preset.stack", size, fill, why), key = "p-stack")) qty.commit(size.toLong().coerceAtMost(limit))
        if (ui.button(presets[2], tr("kami_libs.field.max"), enabled = fill.qty > 0, disabledReason = tr("kami_economy.ticket.max.none", why), tip = tr("kami_economy.ticket.max.tip", unitsText(fill.qty.toInt(), size), why), key = "p-max")) qty.commit(fill.qty)
        if (order) priceBlock(ui, f, d, lot)
        val q = track()
        val lines = summary(q, d, size)
        val w = f.rest.w
        val area = f.take(lines.sumOf { Draw.paragraphHeight(it.text, w) + 2 })
        var y = area.y
        lines.forEachIndexed { i, l ->
            val h = Draw.paragraph(ui.g, l.text, area.x, y, w, l.color)
            ui.tooltip("line:$i", Rect(area.x, y, w, h), l.tip)
            y += h + 2
        }
        val reason = blocked(d, q)
        if (ui.button(f.take(CONTROL_H), tr("kami_economy.ticket.act.$mode"), style = ButtonStyle.PRIMARY, enabled = reason == null, disabledReason = reason, key = "act")) {
            q?.let { confirmQuote(app, it, lot, fatFinger(d)) }
        }
        if (d.mine.isNotEmpty()) mine(ui, f, d)
    }

    private fun priceBlock(ui: Ui, f: Flow, d: Detail, lot: Int) {
        ui.fieldLabel(f.take(9), tr("kami_economy.market.price_lot", lot))
        ui.numberField(f.take(CONTROL_H), price, 1, MAX_PRICE, key = "price")
        val best = (if (buy) d.bids else d.asks).firstOrNull { !it.market }?.price ?: 0
        val none = tr("kami_economy.market.book.empty")
        val p = price.value
        val top = f.take(SMALL_H).columns(2, 3)
        val bottom = f.take(SMALL_H).columns(2, 3)
        val side = if (buy) "bid" else "ask"
        if (ui.button(top[0], tr("kami_economy.price.match"), enabled = best > 0, disabledReason = none, tip = tr("kami_economy.price.match.tip.$side", Format.money(best.toLong())), key = "p-match")) price.commit(best.toLong())
        if (ui.button(top[1], tr("kami_economy.price.beat"), enabled = best > 0, disabledReason = none, tip = tr("kami_economy.price.beat.tip.$side"), key = "p-beat")) price.commit(if (buy) best + 1L else max(1L, best - 1L))
        if (ui.button(bottom[0], tr("kami_economy.price.minus"), tip = tr("kami_economy.price.minus.tip"), key = "p-minus")) price.commit(max(1L, p - max(1L, p * 5 / 100)))
        if (ui.button(bottom[1], tr("kami_economy.price.plus"), tip = tr("kami_economy.price.plus.tip"), key = "p-plus")) price.commit(p + max(1L, p * 5 / 100))
        val tax = app.snap.taxPct
        val stepLots = CleanStep.step(p, listOf(tax))
        if (stepLots <= 1) return
        val text = tr("kami_economy.ticket.step", Format.number(stepLots.toLong() * lot), stepLots, p, tax)
        val q = CleanStep.q(tax)
        val near = max(q, (p + q / 2) / q * q)
        val area = f.take(Draw.paragraphHeight(text, f.rest.w) + if (near != p) 12 else 0)
        ui.anchor("item:step", area)
        Draw.paragraph(ui.g, text, area.x, area.y, area.w, Palette.textMuted)
        ui.tooltip("tip:step", area, tr("kami_economy.ticket.step.tip", Format.number(stepLots.toLong() * lot), stepLots, Format.number(p), tax))
        if (near != p && ui.link(area.x, area.bottom - 10, tr("kami_economy.ticket.step.fix", Format.number(near)))) price.commit(near)
    }

    private fun mine(ui: Ui, f: Flow, d: Detail) {
        ui.section(f.take(13), tr("kami_economy.ticket.mine"))
        d.mine.take(4).forEach { o ->
            val row = f.take(SMALL_H)
            val text = tr(if (o.bid) "kami_economy.ticket.mine.bid" else "kami_economy.ticket.mine.ask", unitsText(o.amount, app.stack(o.item).maxStackSize), Format.money(o.price.toLong()))
            Draw.text(ui.g, Draw.fit(text, row.w - 2 * SMALL_H - 6), row.x, row.y + 4, Palette.textSecondary)
            if (ui.iconButton(Rect(row.right - 2 * SMALL_H - 2, row.y, SMALL_H, SMALL_H), Icons.EDIT, tr("kami_economy.reprice.action"), key = "mine-edit:${o.id}")) app.reprice(o)
            if (ui.iconButton(Rect(row.right - SMALL_H, row.y, SMALL_H, SMALL_H), Icons.CROSS, tr("kami_economy.cancel.action"), key = "mine-cancel:${o.id}")) app.cancel(o)
        }
    }
}
