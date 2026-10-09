package kami.economy.quest

data class Dish(
    val item: String, val score: Int, val tier: Int, val inputs: List<String>, val stations: List<String>,
    val price: Double, val guessed: Boolean, val quest: Boolean
)

data class In(val options: List<String>, val qty: Double = 1.0, val fluid: Boolean = false)

data class Rec(val out: String, val count: Int, val inputs: List<In>, val stations: List<String> = emptyList())

object DishGraph {
    const val BUCKET = "minecraft:bucket"
    private const val WORK_BUDGET = 200_000
    val containers = setOf(BUCKET, "minecraft:bowl", "minecraft:glass_bottle")

    private class Node(val score: Int, val price: Double, val guessed: Boolean, val inputs: List<String>, val stations: List<String>, val partial: Boolean = false)

    private class Pick(val id: String, val node: Node, val cost: Double)

    fun tier(score: Int): Int = when {
        score >= 13 -> 5
        score >= 9 -> 4
        score >= 6 -> 3
        score >= 4 -> 2
        score >= 2 -> 1
        else -> 0
    }

    fun lot(price: Double): Int = listOf(1, 4, 16).firstOrNull { price * it >= 20 } ?: 64

    fun solve(
        recipes: Collection<Rec>, targets: Collection<String>, course: (String) -> Double?, guess: (String) -> Double, scope: (String) -> Boolean
    ): Map<String, Dish> {
        val byOut = recipes.groupBy { it.out }
        val memo = HashMap<String, Node>()
        val busy = HashSet<String>()
        var cut = false
        var work = 0

        lateinit var node: (String) -> Node?

        fun build(rec: Rec): Node? {
            if (rec.inputs.isEmpty()) return null
            val picks = rec.inputs.map { i ->
                i.options.mapNotNull { o ->
                    node(o)?.takeUnless { it.partial }?.let { n ->
                        Pick(o, n, if (i.fluid) (n.price - (node(BUCKET)?.price ?: 0.0)).coerceAtLeast(0.0) * i.qty else n.price * i.qty)
                    }
                }.minByOrNull { it.cost } ?: return null
            }
            if (picks.any { rec.out in it.node.inputs }) return null
            val distinct = picks.distinctBy { it.id }.filter { it.id !in containers }.ifEmpty { picks.take(1) }
            val children = distinct.map { it.node.score }.sortedDescending()
            val score = 2 + (distinct.size - 1) + rec.stations.size + children.first() + children.drop(1).sum() / 4
            val price = picks.sumOf { it.cost } / rec.count.coerceAtLeast(1) * (1.08 + 0.03 * score)
            return Node(score, price, distinct.any { it.node.guessed }, picks.filterIndexed { k, _ -> !rec.inputs[k].fluid }.map { it.id }.distinct(), rec.stations)
        }

        node = fun(item: String): Node? {
            memo[item]?.let { return it }
            if (!busy.add(item)) {
                cut = true
                return null
            }
            val outer = cut
            var tainted = false
            try {
                val known = course(item)
                val best = byOut[item].orEmpty().mapNotNull { r ->
                    cut = false
                    build(r).also { if (cut) tainted = true }
                }.minByOrNull { it.price }
                val n = when {
                    best != null && known != null -> Node(best.score, known, false, best.inputs, best.stations)
                    best != null -> best
                    known != null -> Node(0, known, false, emptyList(), emptyList())
                    else -> Node(0, guess(item), true, emptyList(), emptyList(), tainted)
                }
                if (!tainted || busy.size == 1 || ++work > WORK_BUDGET) memo[item] = n
                return n
            } finally {
                busy.remove(item)
                cut = tainted || outer
            }
        }


        val wanted = targets.toHashSet()
        val out = LinkedHashMap<String, Dish>()
        val queue = ArrayDeque(targets.distinct())
        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            if (item in out) continue
            val n = node(item) ?: continue
            out[item] = Dish(item, n.score, tier(n.score), n.inputs, n.stations, n.price, n.guessed, n.score >= 2 && item in wanted && scope(item))
            queue += n.inputs
        }
        return out
    }
}
