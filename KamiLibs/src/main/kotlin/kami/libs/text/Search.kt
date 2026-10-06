package kami.libs.text

class SearchTerm(val prefix: Char?, val text: String, val negate: Boolean)

class SearchQuery(val clauses: List<List<SearchTerm>>) {
    val isEmpty: Boolean get() = clauses.isEmpty()
    val hasWords: Boolean = clauses.any { c -> c.any { it.prefix == null && !it.negate } }

    fun matches(test: (SearchTerm) -> Boolean): Boolean = clauses.all { c -> c.any { test(it) != it.negate } }

    fun matches(vararg fields: String): Boolean = matches { t -> t.prefix == null && fields.any { it.contains(t.text, true) } }

    companion object {
        val EMPTY = SearchQuery(emptyList())

        fun parse(raw: String): SearchQuery {
            val clauses = tokenize(raw).mapNotNull { token ->
                token.split('|').mapNotNull(::term).takeIf { it.isNotEmpty() }
            }
            return SearchQuery(clauses.sortedBy { c -> c.any { it.prefix == '$' } })
        }

        private fun tokenize(raw: String): List<String> {
            val tokens = ArrayList<String>()
            val cur = StringBuilder()
            var quoted = false
            for (ch in raw) {
                if (ch == '"') quoted = !quoted
                if (ch.isWhitespace() && !quoted) {
                    if (cur.isNotEmpty()) tokens += cur.toString()
                    cur.clear()
                } else cur.append(ch)
            }
            if (cur.isNotEmpty()) tokens += cur.toString()
            return tokens
        }

        private fun term(alt: String): SearchTerm? {
            val negate = alt.startsWith('-') && !(alt.length > 1 && alt.drop(1).all { it.isDigit() })
            val body = if (negate) alt.substring(1) else alt
            val prefix = body.firstOrNull()?.takeIf { it == '@' || it == '#' || it == '$' }
            val text = (if (prefix != null) body.substring(1) else body).replace("\"", "").lowercase()
            return if (text.isEmpty()) null else SearchTerm(prefix, text, negate)
        }
    }
}
