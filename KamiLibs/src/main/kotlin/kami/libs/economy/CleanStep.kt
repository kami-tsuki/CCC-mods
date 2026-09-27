package kami.libs.economy

object CleanStep {
    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    private fun lcm(a: Long, b: Long): Long = a / gcd(a, b) * b

    fun q(ratePct: Int): Long = 100L / gcd(100L, ratePct.coerceIn(0, 100).toLong())

    fun q(rates: List<Int>): Long = rates.fold(1L) { acc, r -> lcm(acc, q(r)) }

    fun step(lotPrice: Long, rates: List<Int>): Int {
        val q = q(rates)
        return (q / gcd(q, lotPrice.coerceAtLeast(1))).toInt()
    }

    fun isClean(total: Long, rates: List<Int>): Boolean = total % q(rates) == 0L

    fun charge(total: Long, ratePct: Int): Long = total * ratePct.coerceIn(0, 100) / 100
}
