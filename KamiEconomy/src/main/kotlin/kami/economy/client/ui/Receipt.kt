package kami.economy.client.ui

import kami.economy.client.admin.MarketSection
import kami.economy.net.Quote
import kami.libs.ui.app.ReceiptKind
import kami.libs.ui.app.ReceiptLine
import kami.libs.ui.app.receiptDialog
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

internal fun returnReason(q: Quote, lot: Int) = when (q.reason) {
    "lot" -> tr("kami_economy.action.return.lot", lot)
    "step" -> tr("kami_economy.action.return.step", q.step)
    "slots" -> tr("kami_economy.action.return.slots")
    "exists" -> tr("kami_economy.action.return.exists")
    "own" -> tr("kami_economy.action.return.own")
    "liquidity" -> tr("kami_economy.ticket.liquidity")
    else -> ""
}

internal fun Quote.sig() = listOf(mode, item, qty, price, filled, listed, returned, total, tax, tariff)

internal val Quote.buying get() = mode == "buy" || mode == "bid"

internal fun Quote.listValue() = if (mode == "bid") listGross + listTax else listGross - listTax

internal fun Quote.sellersKey() = if (mode == "bid") "kami_economy.receipt.tax.sellers.bid" else "kami_economy.receipt.tax.sellers"

internal fun Quote.myTaxTip() = tr("kami_economy.receipt.tax.you.tip.$mode") + relation.takeIf { it.isNotEmpty() && mode != "buy" }?.let { " " + tr("kami_economy.market.rate.$it.tip") }.orEmpty()

internal fun confirmQuote(app: MarketApp, q: Quote, lot: Int, warning: String? = null, extra: List<String> = emptyList()) {
    val stack = MarketSection.stack(q.item)
    val size = stack.maxStackSize
    val sign = if (q.buying) "+" else "-"
    val reason = returnReason(q, lot).let { if (it.isEmpty()) "" else " ($it)" }
    val lines = buildList {
        add(ReceiptLine(stack.hoverName.string, unitsText(q.qty, size)))
        if (q.filled > 0 || q.mode == "bid") {
            if (q.filled > 0) add(ReceiptLine(tr("kami_economy.receipt.avg"), Format.unitMoney(q.avg.toLong(), lot), tip = tr("kami_economy.receipt.sweep", Format.unitMoney(q.worst.toLong(), lot), q.levels)))
            add(ReceiptLine(tr("kami_economy.receipt.subtotal"), Format.money(q.gross)))
            if (q.tax > 0) add(ReceiptLine(tr("kami_economy.receipt.tax.you", q.taxPct), sign + Format.money(q.tax), ReceiptKind.CHARGE, q.myTaxTip(), Palette.warning))
            if (q.tariffs.isNotEmpty()) q.tariffs.forEach { t ->
                val label = if (t.country.isEmpty()) tr("kami_economy.receipt.tariff", t.pct) else tr("kami_economy.receipt.tariff.country", t.country, t.pct)
                add(ReceiptLine(label, sign + Format.money(t.amount), ReceiptKind.CHARGE, tr("kami_economy.receipt.tariff.tip"), Palette.warning))
            } else if (q.tariff > 0) add(ReceiptLine(tr("kami_economy.receipt.tariff", q.tariffPct), sign + Format.money(q.tariff), ReceiptKind.CHARGE, tr("kami_economy.receipt.tariff.tip"), Palette.warning))
        }
        add(ReceiptLine(tr(if (q.buying) "kami_economy.receipt.pay" else "kami_economy.col.receive"), Format.money(q.total), ReceiptKind.TOTAL, color = if (q.buying) Palette.danger else Palette.success))
        if (q.mode == "sell" && q.listed > 0) add(ReceiptLine(tr("kami_economy.receipt.tax.listing", q.taxPct), "-" + Format.money(q.listTax), ReceiptKind.CHARGE, tr("kami_economy.receipt.tax.listing.tip"), Palette.warning))
        if (q.otherTax > 0) add(ReceiptLine(tr(q.sellersKey(), q.otherTaxPct), Format.money(q.otherTax), ReceiptKind.CHARGE, tr("kami_economy.receipt.tax.sellers.tip"), Palette.textMuted))
        if (q.listed > 0) add(ReceiptLine(tr("kami_economy.ticket.rest.${q.mode}", unitsText(q.listed, size), Format.unitMoney(q.price.toLong(), lot), Format.money(q.listValue())), "", ReceiptKind.NOTE, tr("kami_economy.ticket.tip.rest")))
        if (q.returned > 0) add(ReceiptLine(tr("kami_economy.receipt.returned", unitsText(q.returned, size)) + reason, "", ReceiptKind.NOTE, tr("kami_economy.ticket.tip.returned")))
        add(ReceiptLine(tr("kami_libs.common.balance"), Format.money(app.snap.funds) + " → " + Format.money(q.after.toLong())))
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
