package kami.claims.research

import kami.claims.Country

class Penalty(private val country: Country) {
    private val memo = HashMap<String, Set<String>>()

    fun ms(node: Node): Long = missing(node).sumOf { time(it) }

    private fun time(key: String) = Research.defs.node(key)?.time?.inWholeMilliseconds ?: 0L

    private fun missing(node: Node): Set<String> = memo[node.key] ?: run {
        memo[node.key] = emptySet()
        if (strict(node)) return@run emptySet()
        node.requires.filter { optional(it) }.fold(emptySet<String>()) { acc, c -> acc + dep(c) }.also { memo[node.key] = it }
    }

    private fun dep(c: Condition): Set<String> = when {
        c.met(country) -> emptySet()
        c is NodeDone -> chain(c.id)
        else -> (c as AnyOf).of.map { chain((it as NodeDone).id) }.minBy { set -> set.sumOf { time(it) } }
    }

    private fun chain(key: String) = setOf(key) + (Research.defs.node(key)?.let(::missing) ?: emptySet())

    companion object {
        fun optional(c: Condition) = c is NodeDone || c is AnyOf && c.of.isNotEmpty() && c.of.all { it is NodeDone }

        fun hard(node: Node) = if (strict(node)) node.conditions() else node.conditions().filterNot(::optional)

        private fun strict(node: Node) = Research.defs.trees[node.tree]?.strict == true
    }
}
