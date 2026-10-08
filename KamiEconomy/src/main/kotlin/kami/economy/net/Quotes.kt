package kami.economy.net

import kami.economy.Config
import kami.economy.economy.BuyPlan
import kami.economy.economy.FillLinePlan
import kami.economy.economy.Limits
import kami.economy.economy.Matching
import kami.economy.economy.SellPlan
import kami.economy.economy.Terms
import kami.economy.economy.Trade
import kami.economy.economy.Variants
import kami.libs.claims.ClaimsApi

internal class QuoteReq(val mode: String, val item: String, val qty: Int, val price: Int)

internal fun clamp(v: Long): Int = v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

internal object Quotes {
    private fun tariffs(fills: List<FillLinePlan>, terms: (String) -> Terms): List<TariffLine> =
        fills.filter { !it.market && it.tariff > 0 }.groupBy { Trade.country(it.owner) }.map { (country, list) ->
            TariffLine(country?.let { ClaimsApi.country(it)?.name } ?: "", terms(list.first().owner).tariffPct, list.sumOf { it.tariff })
        }.sortedByDescending { it.amount }

    private fun avg(gross: Long, filled: Int, lot: Int): Int = if (filled > 0) (gross * lot / filled).toInt() else 0

    private fun relation(terms: List<Terms>): String = terms.map { it.relation.name.lowercase() }.distinct().let { if (it.size > 1) "mixed" else it.firstOrNull() ?: "" }

    fun of(me: String, funds: Long, r: QuoteReq): Quote? {
        val v = Variants.of(r.item) ?: return quote(me, funds, r)
        val coreLot = Config.s.lotOf(v.core)
        val lot = Variants.lot(r.item, coreLot)
        val whole = r.qty - r.qty % lot
        val coreQty = if (whole <= 0) 0 else Variants.coreQty(r.item, whole)?.takeIf { it > 0 } ?: return null
        val q = quote(me, funds, QuoteReq(r.mode, v.core, coreQty, r.price)) ?: return null
        fun units(core: Int) = clamp(core.toLong() * v.div / v.mul)
        fun price(p: Int) = if (p <= 0) 0 else clamp(Variants.scale(r.item, p.toLong(), coreLot))
        return q.copy(
            item = r.item, qty = r.qty, filled = units(q.filled), listed = units(q.listed), returned = r.qty - units(q.filled) - units(q.listed),
            avg = price(q.avg), worst = price(q.worst), step = lot, leftover = (q.filled.toLong() * v.div % v.mul / v.div).toInt(), reason = if ((q.reason.isEmpty() || whole <= 0) && whole < r.qty) "lot" else q.reason
        )
    }

    private fun quote(me: String, funds: Long, r: QuoteReq): Quote? {
        val lot = Config.s.lotOf(r.item)
        val whole = r.qty - r.qty % lot
        fun lotReason(filled: Int) = when {
            filled < whole -> "liquidity"
            whole < r.qty -> "lot"
            else -> ""
        }
        return when (r.mode) {
            "buy" -> {
                val plan = Matching.plan(r.item, whole, me)
                buyQuote(me, funds, r, plan, 0, 0, lot, lotReason(plan.filled), 0)
            }
            "sell_market" -> {
                val plan = Matching.sellPlan(r.item, whole, me).takeIf { it.filled > 0 && it.net > 0 }
                sellQuote(me, funds, r, plan, 0, 0, lot, lotReason(plan?.filled ?: 0), 0, 0)
            }
            "sell" -> {
                val price = Matching.listPrice(me, r.item, r.price)
                if (Matching.ownCross(me, r.item, price, false)) return sellQuote(me, funds, r, null, price, 0, Matching.step(price) * lot, "own", 0, 0)
                val q = Matching.sellQuote(me, r.item, r.qty, price)
                val rest = whole - q.filled
                val reason = when {
                    rest > 0 && q.limited -> "slots"
                    rest > q.listed -> "step"
                    whole < r.qty -> "lot"
                    else -> ""
                }
                val listGross = q.listed.toLong() / lot * price
                sellQuote(me, funds, r, q.plan, price, q.listed, q.step, reason, listGross, Matching.tax(listGross))
            }
            "bid" -> {
                val step = Matching.step(r.price.coerceAtLeast(1)) * lot
                val refusal = when {
                    Matching.bookFor(r.item).buys.any { it.owner == me } -> "exists"
                    Limits.marketFull(me) -> "slots"
                    Matching.ownCross(me, r.item, r.price, true) -> "own"
                    else -> null
                }
                if (refusal != null) return buyQuote(me, funds, r, null, r.price, 0, step, refusal, 0)
                val amount = r.qty - r.qty % step
                if (amount <= 0) return buyQuote(me, funds, r, null, r.price, 0, step, "step", 0)
                val plan = Matching.plan(r.item, amount, me, r.price)
                val rest = amount - plan.filled
                val listed = rest - rest % step
                val reason = when {
                    rest > listed -> "step"
                    amount < r.qty -> "lot"
                    else -> ""
                }
                buyQuote(me, funds, r, plan, r.price, listed, step, reason, listed.toLong() / lot * r.price)
            }
            else -> null
        }
    }

    private fun buyQuote(me: String, funds: Long, r: QuoteReq, plan: BuyPlan?, price: Int, listed: Int, step: Int, reason: String, listGross: Long): Quote {
        val lot = Config.s.lotOf(r.item)
        val fills = plan?.fills.orEmpty()
        val filled = plan?.filled ?: 0
        val stock = fills.filter { it.market }
        val terms = fills.filterNot { it.market }.map { Trade.terms(me, it.owner) }
        val gross = fills.sumOf { it.gross }
        val total = (plan?.totalSpurs ?: 0) + listGross
        val listTaxPct = if (listed > 0) Config.s.taxPct else 0
        return Quote(
            r.mode, r.item, r.qty, price, filled, listed, r.qty - filled - listed, reason,
            gross, stock.sumOf { it.tax }, fills.sumOf { it.tariff }, total, avg(gross, filled, lot), fills.maxOfOrNull { it.unitPrice } ?: 0, fills.size,
            listGross, 0, step, if (stock.isEmpty()) 0 else Config.s.taxPct, terms.maxOfOrNull { it.tariffPct } ?: 0, relation(terms), clamp(funds - total),
            fills.filterNot { it.market }.sumOf { it.tax } + if (listed > 0) Matching.tax(listGross) else 0, maxOf(terms.maxOfOrNull { it.taxPct } ?: 0, listTaxPct),
            tariffs(fills) { Trade.terms(me, it) }
        )
    }

    private fun sellQuote(me: String, funds: Long, r: QuoteReq, plan: SellPlan?, price: Int, listed: Int, step: Int, reason: String, listGross: Long, listTax: Long): Quote {
        val lot = Config.s.lotOf(r.item)
        val fills = plan?.fills.orEmpty()
        val terms = fills.filterNot { it.market }.map { Trade.terms(it.owner, me) }
        val filled = plan?.filled ?: 0
        val total = plan?.net ?: 0L
        val taxPct = (terms.map { it.taxPct } + if (fills.any { it.market }) listOf(Config.s.taxPct) else emptyList()).maxOrNull() ?: Config.s.taxPct
        return Quote(
            r.mode, r.item, r.qty, price, filled, listed, r.qty - filled - listed, reason,
            plan?.gross ?: 0, plan?.tax ?: 0, plan?.tariff ?: 0, total, avg(plan?.gross ?: 0, filled, lot), fills.minOfOrNull { it.unitPrice } ?: 0, fills.size,
            listGross, listTax, step, taxPct, terms.maxOfOrNull { it.tariffPct } ?: 0, relation(terms), clamp(funds + total),
            tariffs = tariffs(fills) { Trade.terms(it, me) }
        )
    }
}
