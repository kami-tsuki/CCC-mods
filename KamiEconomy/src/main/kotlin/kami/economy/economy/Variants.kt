package kami.economy.economy

import kotlinx.serialization.Serializable

@Serializable
data class Variant(val core: String, val mul: Int, val div: Int)

open class VariantIndex {
    @Volatile private var families: Map<String, List<Pair<String, Variant>>> = emptyMap()

    @Volatile var map: Map<String, Variant> = emptyMap()
        set(value) {
            families = value.entries.groupBy({ it.value.core }, { it.key to it.value }).mapValues { e -> e.value.sortedBy { it.second.mul.toDouble() / it.second.div } }
            field = value
        }

    fun of(id: String): Variant? = map[id]

    fun core(id: String): String = map[id]?.core ?: id

    fun family(core: String): List<Pair<String, Variant>> = families[core].orEmpty()

    fun coreQty(id: String, qty: Int): Int? {
        val v = map[id] ?: return qty
        val units = qty.toLong() * v.mul
        return if (units % v.div == 0L) (units / v.div).takeIf { it <= Int.MAX_VALUE }?.toInt() else null
    }

    fun lot(id: String, coreLot: Int): Int {
        val v = map[id] ?: return coreLot
        val n = coreLot.toLong() * v.div
        return (n / gcd(n, v.mul.toLong())).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun scale(id: String, coreLotPrice: Long, coreLot: Int): Long {
        val v = map[id] ?: return coreLotPrice
        val d = v.div.toLong() * coreLot
        return (coreLotPrice * lot(id, coreLot) * v.mul + d / 2) / d
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
}

object Variants : VariantIndex() {
    private const val MAX_STEP = 3

    private fun pow9(k: Int): Int = generateSequence(1) { it * 9 }.elementAt(k)

    private fun coreOf(chain: List<String>, starter: (String) -> Boolean): Int {
        if (chain.size == 2) {
            val starters = chain.filter(starter)
            return if (starters.size == 1) chain.indexOf(starters[0]) else if ("nugget" in chain[0]) 1 else 0
        }
        val mid = chain.size / 2
        val candidates = if (chain.size % 2 == 1) listOf(mid) else listOf(mid - 1, mid)
        return candidates.sortedWith(compareBy<Int>({ !starter(chain[it]) }, { "nugget" in chain[it] || "block" in chain[it] })).first()
    }

    fun derive(pairs: List<Pair<String, String>>, starter: (String) -> Boolean): Map<String, Variant> {
        val bigOf = pairs.groupBy({ it.first }, { it.second }).filterValues { it.size == 1 }.mapValues { it.value[0] }
        val smallOf = pairs.groupBy({ it.second }, { it.first }).filterValues { it.size == 1 }.mapValues { it.value[0] }
        val ok = pairs.filter { bigOf[it.first] == it.second && smallOf[it.second] == it.first && it.first != it.second }
        val up = ok.toMap()
        val down = ok.map { it.second }.toSet()
        return up.keys.filter { it !in down }.flatMap { start ->
            val chain = generateSequence(start) { up[it] }.take(up.size + 1).toList()
            val ci = coreOf(chain, starter)
            chain.indices.filter { it != ci && kotlin.math.abs(it - ci) <= MAX_STEP }.map { i ->
                val k = i - ci
                chain[i] to Variant(chain[ci], if (k > 0) pow9(k) else 1, if (k < 0) pow9(-k) else 1)
            }
        }.toMap()
    }
}

object ClientVariants : VariantIndex()
