package kami.economy.admin

import kami.libs.mc.ItemSpec
import kami.libs.text.SearchQuery

internal class AdminSearch(args: List<String>, at: Int) {
    private val query = SearchQuery.parse(args.getOrNull(at).orEmpty())
    private val ids: Set<String>? = args.getOrNull(at + 1).orEmpty().takeUnless { query.isEmpty || it == "*" }?.split(',')?.filter(String::isNotBlank)?.toSet()

    fun hit(item: String, vararg extra: String): Boolean {
        val set = ids ?: return true
        val members = if (item.startsWith("#")) ItemSpec.stacks(item).map(ItemSpec::id) else listOf(item)
        return members.any { it in set } || query.hasWords && query.matches(item, *extra)
    }
}
