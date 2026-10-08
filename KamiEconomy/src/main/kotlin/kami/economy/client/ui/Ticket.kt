package kami.economy.client.ui

import kami.economy.client.admin.MarketSection
import kami.economy.economy.ClientVariants as Variants
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

private const val QUOTE_DELAY = 200L
private const val FAT_FINGER_PCT = 15L

private fun signedPct(v: Long) = (if (v > 0) "+" else if (v < 0) "−" else "±") + abs(v) + "%"

internal fun returnReason(q: Quote, lot: Int) = when (q.reason) {
    "lot" -> tr("kami_economy.action.return.lot", lot)
    "step" -> tr("kami_economy.action.return.step", q.step)
    "slots" -> tr("kami_economy.action.return.slots")
    "exists" -> tr("kami_economy.action.return.exists")
    "own" -> tr("kami_economy.action.return.own")
    "liquidity" -> tr("kami_economy.ticket.liquidity")
    else -> ""
}

private fun Quote.sig() = listOf(mode, item, qty, price, filled, listed, returned, total, tax, tariff)

private val Quote.buying get() = mode == "buy" || mode == "bid"

internal fun Quote.listValue() = if (mode == "bid") listGross + listTax else listGross - listTax

private fun Quote.sellersKey() = if (mode == "bid") "kami_economy.receipt.tax.sellers.bid" else "kami_economy.receipt.tax.sellers"

private fun Quote.myTaxTip() = tr("kami_economy.receipt.tax.you.tip.$mode") + relation.takeIf { it.isNotEmpty() && mode != "buy" }?.let { " " + tr("kami_economy.market.rate.$it.tip") }.orEmpty()

internal fun confirmQuote(app: MarketApp, q: Quote, lot: Int, warning: String? = null, extra: List<String> = emptyList()) {
    val stack = MarketSection.stack(q.item)
    val size = stack.maxStackSize
    val sign = if (q.buying) "+" else "-"
    val reason = returnReason(q, lot).let { if (it.isEmpty()) "" else " ($it)" }
    val lines = buildList {
        add(ReceiptLine(stack.hoverName.string, unitsText(q.qty, size)))
        if (q.filled > 0 || q.mode == "bid") {
            if (q.filled > 0) add(ReceiptLine(tr("kami_economy.receipt.avg"), Format.money(q.avg.toLong()), tip = tr("kami_economy.receipt.avg.tip") + " " + tr("kami_economy.receipt.sweep", Format.money(q.worst.toLong()), q.levels)))
            add(ReceiptLine(tr("kami_economy.receipt.subtotal"), Format.money(q.gross), tip = tr("kami_economy.receipt.subtotal.tip")))
            if (q.tax > 0) add(ReceiptLine(tr("kami_economy.receipt.tax.you", q.taxPct), sign + Format.money(q.tax), ReceiptKind.CHARGE, q.myTaxTip(), Palette.warning))
            if (q.tariffs.isNotEmpty()) q.tariffs.forEach { t ->
                val label = if (t.country.isEmpty()) tr("kami_economy.receipt.tariff", t.pct) else tr("kami_economy.receipt.tariff.country", t.country, t.pct)
                add(ReceiptLine(label, sign + Format.money(t.amount), ReceiptKind.CHARGE, tr("kami_economy.receipt.tariff.tip"), Palette.warning))
            } else if (q.tariff > 0) add(ReceiptLine(tr("kami_economy.receipt.tariff", q.tariffPct), sign + Format.money(q.tariff), ReceiptKind.CHARGE, tr("kami_economy.receipt.tariff.tip"), Palette.warning))
        }
        add(ReceiptLine(tr(if (q.buying) "kami_economy.receipt.pay" else "kami_economy.receipt.receive"), Format.money(q.total), ReceiptKind.TOTAL, tr("kami_economy.receipt.total.tip"), if (q.buying) Palette.danger else Palette.success))
        if (q.mode == "sell" && q.listed > 0) add(ReceiptLine(tr("kami_economy.receipt.tax.listing", q.taxPct), "-" + Format.money(q.listTax), ReceiptKind.CHARGE, tr("kami_economy.receipt.tax.listing.tip"), Palette.warning))
        if (q.otherTax > 0) add(ReceiptLine(tr(q.sellersKey(), q.otherTaxPct), Format.money(q.otherTax), ReceiptKind.CHARGE, tr("kami_economy.receipt.tax.sellers.tip"), Palette.textMuted))
        if (q.listed > 0) add(ReceiptLine(tr("kami_economy.receipt.listed.${q.mode}", unitsText(q.listed, size), Format.money(q.price.toLong()), Format.money(q.listValue())), "", ReceiptKind.NOTE, tr("kami_economy.ticket.tip.rest")))
        if (q.returned > 0) add(ReceiptLine(tr("kami_economy.receipt.returned", unitsText(q.returned, size)) + reason, "", ReceiptKind.NOTE, tr("kami_economy.ticket.tip.returned")))
        add(ReceiptLine(tr("kami_economy.receipt.balance"), Format.money(app.snap.funds) + " → " + Format.money(q.after.toLong()), tip = tr("kami_economy.ticket.tip.after")))
    }
    val sig = q.sig()
    receiptDialog(
        app::open, tr("kami_economy.receipt.title.${q.mode}"), Icons.COIN, lines, tr("kami_economy.ticket.act.${q.mode}"), warning,
        stale = { app.snap.quote?.takeIf { it.sig() != sig }?.let { tr("kami_economy.receipt.stale") } }
    ) {
        when (q.mode) {
            "buy", "sell_market" -> app.request(q.mode, q.item, q.qty.toString())
            else -> app.request(q.mode, q.item, q.qty.toString(), q.price.toString(), *extra.toTypedArray())
        }
    }
}

internal class Ticket(private val app: MarketApp) {
    private var item = ""
    private var buy = true
    private var order = false
    private val qty = NumberState(1)
    private val price = NumberState(1)
    private var follow = false
    private val offset = NumberState(0)
    private val pegLimit = NumberState(0)
    private var qtyFor = ""
    private var priceFor = ""
    private var lastSig = ""
    private var changedAt = 0L
    private var sent = true
    private var quotedP = 0

    private val mode get() = when {
        buy && order -> "bid"
        buy -> "buy"
        order -> "sell"
        else -> "sell_market"
    }

    var variant = ""
    private val v get() = if (order) null else Variants.of(variant)?.takeIf { it.core == item }
    val shown get() = if (v != null) variant else item
    fun lotOf(d: Detail) = if (v != null) Variants.lot(variant, d.lot) else d.lot
    fun priceOf(d: Detail, p: Long): Long = if (v != null && p > 0) Variants.scale(variant, p, d.lot) else p
    fun heldOf(d: Detail) = if (v != null) app.carried.get().held[variant] ?: 0 else d.held

    private fun cv(n: Int): Int = v?.takeIf { n > 0 }?.let { (n.toLong() * it.div / it.mul).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } ?: n

    fun open(item: String, buy: Boolean, order: Boolean, variant: String = "") {
        this.item = item
        this.variant = variant
        this.buy = buy
        this.order = order
        follow = false
        qtyFor = ""
        priceFor = ""
    }

    private fun seed(d: Detail) {
        val unit = "$shown|$order"
        if (qtyFor != unit) { qtyFor = unit; qty.commit(lotOf(d).toLong()) }
        val key = "$item|$buy"
        if (priceFor != key) {
            priceFor = key
            price.commit(listOf(if (buy) d.buy - 1 else d.buy, d.sell, d.last, 1).first { it > 0 }.toLong())
        }
    }

    private fun track(): Quote? {
        val n = qty.value.toInt()
        val p = if (order) price.value.toInt() else 0
        val sig = "$mode|$shown|$n|$p"
        val now = System.currentTimeMillis()
        if (sig != lastSig) { lastSig = sig; changedAt = now; sent = false }
        if (!sent && n > 0 && now - changedAt >= QUOTE_DELAY) {
            sent = true
            quotedP = p
            app.request("quote", mode, shown, n.toString(), p.toString())
        }
        return app.snap.quote?.takeIf { it.mode == mode && it.item == shown && it.qty == n && (it.price == p || mode == "sell" && sent && quotedP == p) }
    }

    private fun blocked(d: Detail, q: Quote?, lot: Int, held: Int): String? = frozenFor(mode, d.status, d.statusReason) ?: when {
        !app.snap.citizen -> tr("kami_libs.lock.no_country")
        !buy && held <= 0 -> tr("kami_economy.market.sell.none")
        order && follow && d.followable && pegLimit.value <= 0 -> tr("kami_economy.follow.limit.needed")
        q == null -> tr("kami_economy.market.buy.pending")
        q.filled + q.listed <= 0 -> returnReason(q, lot).takeIf { it.isNotEmpty() }?.let { tr("kami_economy.ticket.blocked", it) }
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

    private fun fill(d: Detail, lot: Int, held: Int): Fill {
        val (qty, why) = when {
            !buy -> held.toLong() to "held"
            order -> app.snap.funds / price.value.coerceAtLeast(1) * lot to "funds"
            d.available < 0 || d.maxAffordable < d.available -> cv(d.maxAffordable).toLong() to "funds"
            else -> cv(d.maxAffordable).toLong() to "supply"
        }
        val max = cv(d.maxAmount)
        return if (qty <= max) Fill(qty, why) else Fill(max.toLong(), "max")
    }

    private fun summary(q: Quote?, lot: Int, size: Int): List<Line> {
        if (q == null) return listOf(Line(tr("kami_economy.market.calculating"), Palette.textMuted, tr("kami_economy.ticket.tip.calculating")))
        return buildList {
            if (q.filled > 0) {
                add(Line(tr("kami_economy.ticket.now.${q.mode}", unitsText(q.filled, size), Format.money(q.total)), Palette.text, tr("kami_economy.ticket.tip.now")))
                if (q.leftover > 0) add(Line(tr("kami_economy.ticket.leftover", q.leftover, MarketSection.stack(item).hoverName.string), Palette.textMuted, tr("kami_economy.ticket.tip.leftover")))
                add(Line(tr("kami_economy.ticket.sweep", Format.money(q.avg.toLong()), Format.money(q.worst.toLong()), q.levels), Palette.textMuted, tr("kami_economy.ticket.tip.sweep")))
            }
            if (q.listed > 0) add(Line(tr("kami_economy.ticket.rest.${q.mode}", unitsText(q.listed, size), Format.money(q.price.toLong()), Format.money(q.listValue())), Palette.textSecondary, tr("kami_economy.ticket.tip.rest")))
            if (q.tax > 0) add(Line(tr("kami_economy.ticket.tax.you", Format.money(q.tax), q.taxPct), Palette.warning, q.myTaxTip()))
            if (q.mode == "sell" && q.listed > 0) add(Line(tr("kami_economy.ticket.tax.listing", Format.money(q.listTax), q.taxPct), Palette.warning, tr("kami_economy.receipt.tax.listing.tip")))
            if (q.otherTax > 0) add(Line(tr(if (q.mode == "bid") "kami_economy.ticket.tax.sellers.bid" else "kami_economy.ticket.tax.sellers", Format.money(q.otherTax), q.otherTaxPct), Palette.textMuted, tr("kami_economy.receipt.tax.sellers.tip")))
            if (q.returned > 0) add(Line(tr("kami_economy.receipt.returned", unitsText(q.returned, size)) + returnReason(q, lot).let { if (it.isEmpty()) "" else " ($it)" }, Palette.warning, tr("kami_economy.ticket.tip.returned")))
            if (q.mode == "sell" && q.price > 0 && q.price != price.value.toInt()) add(Line(tr("kami_economy.ticket.forced", Format.money(q.price.toLong())), Palette.warning, tr("kami_economy.ticket.tip.forced")))
            add(Line(tr("kami_economy.ticket.after", Format.money(q.after.toLong())), Palette.textMuted, tr("kami_economy.ticket.tip.after")))
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
        if (buy || order || d.capLeft < 0) return
        val text = if (d.capLeft == 0) tr("kami_economy.stock.capped") else tr("kami_economy.stock.cap", Format.number(d.capLeft.toLong() * d.lot))
        note(ui, f, "stock", text, tr("kami_economy.stock.tip"))
    }

    private fun pegArgs(): List<String> =
        if (!order || !follow) emptyList() else listOf((offset.value * 10).toString(), pegLimit.value.toString())

    private fun followBlock(ui: Ui, f: Flow) {
        ui.toggle(f.take(SMALL_H), follow, tr("kami_economy.follow.toggle"), tip = tr("kami_economy.follow.tip"), key = "follow")?.let { follow = it }
        if (!follow) return
        ui.fieldLabel(f.take(9), tr("kami_economy.follow.offset"))
        ui.numberField(f.take(CONTROL_H), offset, -50, 50, unit = "%", key = "peg-offset")
        ui.fieldLabel(f.take(9), tr("kami_economy.follow.limit"), if (pegLimit.value == 0L) tr("kami_economy.follow.limit.required") else null)
        val limit = f.take(CONTROL_H)
        ui.numberField(limit, pegLimit, 0, MAX_PRICE, key = "peg-limit")
        ui.tooltip("peg-limit-tip", limit, tr("kami_economy.follow.limit.tip"))
    }

    private fun presetTip(key: String, units: Int, fill: Fill, why: String) =
        tr(key, Format.number(units)) + if (units > fill.qty) " " + tr("kami_economy.ticket.preset.capped", Format.number(fill.qty), why) else ""

    fun draw(ui: Ui, f: Flow, d: Detail?) {
        if (d == null) {
            ui.skeleton(f.take(SMALL_H))
            ui.skeleton(f.take(SMALL_H))
            ui.skeletonLines(f.take(5 * Draw.LINE), 5)
            ui.skeleton(f.take(CONTROL_H))
            ui.skeleton(f.take(SMALL_H))
            ui.skeleton(f.take(CONTROL_H))
            return
        }
        seed(d)
        val lot = lotOf(d).coerceAtLeast(1)
        val size = MarketSection.stack(shown).maxStackSize
        val held = heldOf(d)
        selector(ui, f, d)
        ui.segmented(f.take(SMALL_H), listOf(Option(true, tr("kami_economy.ticket.buy"), description = tr("kami_economy.ticket.buy.tip")), Option(false, tr("kami_economy.ticket.sell"), description = tr("kami_economy.ticket.sell.tip"))), buy, key = "side")?.let { buy = it }
        ui.segmented(f.take(SMALL_H), listOf(Option(false, tr("kami_economy.ticket.instant"), description = tr("kami_economy.ticket.instant.tip")), Option(true, tr("kami_economy.ticket.order"), description = tr("kami_economy.ticket.order.tip"))), order, key = "kind")?.let { order = it }
        frozenFor(mode, d.status, d.statusReason)?.let { text ->
            val area = f.take(Draw.paragraphHeight(text, f.rest.w))
            Draw.paragraph(ui.g, text, area.x, area.y, area.w, Palette.warning)
        }
        val fill = fill(d, lot, held)
        val why = tr("kami_economy.limit.${fill.why}", Format.number(d.maxAmount))
        val limit = (if (buy && order) d.maxAmount.toLong() else fill.qty).coerceAtLeast(1)
        stat(ui, f, "held", tr("kami_economy.col.held"), unitsText(held, size), tr("kami_economy.ticket.tip.held"))
        if (buy) stat(ui, f, "afford", tr("kami_economy.ticket.afford"), unitsText(cv(d.maxAffordable), size), tr("kami_economy.ticket.tip.afford"))
        stat(ui, f, "available", tr("kami_economy.ticket.available"), unitsText(cv(d.available), size), tr("kami_economy.ticket.tip.available"))
        stat(ui, f, "demand", tr("kami_economy.col.demand"), unitsText(cv(d.demand), size), tr("kami_economy.ticket.tip.demand"))
        stat(ui, f, "lot", tr("kami_economy.col.lot"), tr("kami_economy.ticket.lot_size", Format.number(lot)), tr("kami_economy.ticket.tip.lot", Format.number(lot), Format.number(d.maxAmount)))
        stockNote(ui, f, d)
        val hint = stackHint(qty.value.toInt(), size).takeIf { it.isNotEmpty() }?.let { "= $it" }
        ui.fieldLabel(f.take(9), tr("kami_economy.ticket.qty"), hint)
        ui.numberField(f.take(CONTROL_H), qty, 1, limit, key = "qty")
        val presets = f.take(SMALL_H).columns(3, 3)
        if (ui.button(presets[0], tr("kami_economy.ticket.lot"), tip = presetTip("kami_economy.ticket.preset.lot", lot, fill, why), key = "p-lot")) qty.commit(lot.toLong().coerceAtMost(limit))
        if (ui.button(presets[1], tr("kami_economy.ticket.stack"), enabled = size > 1, disabledReason = tr("kami_economy.ticket.preset.nostack"), tip = presetTip("kami_economy.ticket.preset.stack", size, fill, why), key = "p-stack")) qty.commit(size.toLong().coerceAtMost(limit))
        if (ui.button(presets[2], tr("kami_libs.field.max"), enabled = fill.qty > 0, disabledReason = tr("kami_economy.ticket.max.none", why), tip = tr("kami_economy.ticket.max.tip", unitsText(fill.qty.toInt(), size), why), key = "p-max")) qty.commit(fill.qty)
        if (!d.followable) follow = false
        if (order) {
            priceBlock(ui, f, d, lot)
            if (d.followable) followBlock(ui, f)
        }
        val q = track()
        val lines = summary(q, lot, size)
        val w = f.rest.w
        val area = f.take(lines.sumOf { Draw.paragraphHeight(it.text, w) + 2 })
        var y = area.y
        if (q == null) ui.spinner(area.x, y, Palette.textMuted)
        lines.forEachIndexed { i, l ->
            val x = if (q == null) 12 else 0
            val h = Draw.paragraph(ui.g, l.text, area.x + x, y, w - x, l.color)
            ui.tooltip("line:$i", Rect(area.x, y, w, h), l.tip)
            y += h + 2
        }
        val reason = blocked(d, q, lot, held)
        val pending = q == null && reason == tr("kami_economy.market.buy.pending")
        if (ui.button(f.take(CONTROL_H), tr("kami_economy.ticket.act.$mode"), style = ButtonStyle.PRIMARY, enabled = reason == null, disabledReason = reason, pending = pending, key = "act")) {
            q?.let { confirmQuote(app, it, lot, fatFinger(d), pegArgs()) }
        }
        if (d.mine.isNotEmpty()) mine(ui, f, d)
    }

    private fun selector(ui: Ui, f: Flow, d: Detail) {
        if (d.variants.isEmpty()) return
        val options = buildList {
            d.variants.filter { it.mul < it.div }.forEach { add(Option(it.item, tr("kami_economy.variant.small"), description = MarketSection.stack(it.item).hoverName.string)) }
            add(Option("", tr("kami_economy.variant.core"), description = MarketSection.stack(item).hoverName.string))
            d.variants.filter { it.mul > it.div }.forEach { add(Option(it.item, tr("kami_economy.variant.big"), description = MarketSection.stack(it.item).hoverName.string)) }
        }
        val current = if (d.variants.any { it.item == variant }) variant else ""
        ui.segmented(f.take(SMALL_H), options, current, key = "variant")?.let { variant = it }
        if (order && current.isNotEmpty()) note(ui, f, "variant", tr("kami_economy.variant.resting", MarketSection.stack(item).hoverName.string), tr("kami_economy.variant.resting.tip"))
    }

    private fun pegPrice(d: Detail): Long {
        val raw = d.course * (1 + offset.value / 100.0)
        val capped = pegLimit.value.takeIf { it > 0 }?.let { if (buy) minOf(raw, it.toDouble()) else maxOf(raw, it.toDouble()) } ?: raw
        return capped.roundToLong().coerceIn(1L, MAX_PRICE)
    }

    private fun priceBlock(ui: Ui, f: Flow, d: Detail, lot: Int) {
        val following = follow
        if (following) price.commit(pegPrice(d))
        ui.fieldLabel(f.take(9), tr("kami_economy.market.price_lot", lot))
        val field = f.take(CONTROL_H)
        ui.numberField(field, price, 1, MAX_PRICE, enabled = !following, key = "price")
        if (following) ui.tooltip("price-follow", field, tr("kami_economy.follow.price", signedPct(offset.value)))
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
            val text = tr(if (o.bid) "kami_economy.ticket.mine.bid" else "kami_economy.ticket.mine.ask", unitsText(o.amount, MarketSection.stack(o.item).maxStackSize), Format.money(o.price.toLong()))
            Draw.text(ui.g, Draw.fit(text, row.w - 2 * SMALL_H - 6), row.x, row.y + 4, Palette.textSecondary)
            if (ui.iconButton(Rect(row.right - 2 * SMALL_H - 2, row.y, SMALL_H, SMALL_H), Icons.EDIT, tr("kami_economy.reprice.action"), key = "mine-edit:${o.id}")) app.reprice(app, o)
            if (ui.iconButton(Rect(row.right - SMALL_H, row.y, SMALL_H, SMALL_H), Icons.CROSS, tr("kami_economy.cancel.action"), key = "mine-cancel:${o.id}")) app.cancel(app, o)
        }
    }
}
