package kami.claims.client.app.pages

import kami.claims.net.MarketLoanView
import kami.claims.net.LoanTerms
import kami.claims.service.Diplomacy

enum class LoanSort {
    INTEREST, AMOUNT, TERM
}

enum class LoanBlock { LEND_LOCKED, OFFER_LIMIT, FUNDS, RANGE }

class LoanQuote(val total: Long, val perDay: Long, val worstEffective: Int?, val worstTotal: Long, val worstPerDay: Long)

object LoansModel {
    fun quote(amount: Long, term: Long, interest: Long, tariff: Boolean): LoanQuote {
        val total = LoanTerms.total(amount, interest.toInt())
        val effective = if (tariff) (interest * (100 + Diplomacy.MAX_TARIFF) / 100).toInt() else null
        val worst = effective?.let { LoanTerms.total(amount, it) } ?: 0L
        return LoanQuote(total, LoanTerms.perDay(total, term.toInt()), effective, worst, effective?.let { LoanTerms.perDay(worst, term.toInt()) } ?: 0L)
    }

    fun block(lendLocked: Boolean, ownOffers: Int, maxOffers: Int, treasury: Long, amount: Long, minOn: Boolean, maxOn: Boolean, minRep: Int, maxRep: Int): LoanBlock? = when {
        lendLocked -> LoanBlock.LEND_LOCKED
        ownOffers >= maxOffers -> LoanBlock.OFFER_LIMIT
        treasury < amount -> LoanBlock.FUNDS
        minOn && maxOn && minRep > maxRep -> LoanBlock.RANGE
        else -> null
    }

    fun <T> arrange(items: List<T>, sort: LoanSort, affordableOnly: Boolean, affordable: (T) -> Boolean, offer: (T) -> MarketLoanView): List<T> =
        items.filter { !affordableOnly || affordable(it) }.sortedWith(when (sort) {
            LoanSort.AMOUNT -> compareBy { offer(it).amount }
            LoanSort.TERM -> compareBy { offer(it).termDays }
            LoanSort.INTEREST -> compareBy { offer(it).effectivePct }
        })
}
