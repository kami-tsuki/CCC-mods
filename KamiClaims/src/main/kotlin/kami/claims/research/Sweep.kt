package kami.claims.research

import kami.claims.Country
import kami.claims.service.Fail
import kami.claims.service.Words

class Sweep(val tree: String, val category: String) {
    val whole get() = tree.isEmpty() && category.isEmpty()

    fun keys(country: Country): List<String> {
        val trees = Research.defs.treesFor(country)
        val picked = if (tree.isEmpty()) trees else trees.filter { it.id == tree }
        if (tree.isNotEmpty() && picked.isEmpty()) throw Fail("kami_claims.research.error.unknown", Words.v(tree))
        if (category.isNotEmpty() && (tree.isEmpty() || picked.none { t -> t.categories.any { it.id == category } })) throw Fail("kami_claims.research.error.unknown", Words.v(category))
        return picked.flatMap { t -> t.nodes.filter { category.isEmpty() || it.category == category }.map { it.key } }
    }
}
