package kami.claims.net

object LoanTerms {
    const val MIN_AMOUNT = 100L
    const val MAX_TERM = 60
    const val MAX_INTEREST = 100

    fun total(amount: Long, pct: Int) = amount * (100 + pct) / 100

    fun perDay(total: Long, days: Int) = (total + days - 1) / days
}
