package kami.claims.press

import kami.libs.util.Time

class NewsFilter(val kind: ArticleKind? = null, val country: String? = null, val days: Int = 0, val text: String = "", val deleted: Boolean = false) {
    fun clause(now: Long): Pair<String, List<Any?>> {
        val where = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        if (!deleted) where += "deleted_at IS NULL"
        kind?.let { where += "kind = ?"; params += it.name }
        country?.let { where += "country_id = ?"; params += it }
        if (days > 0) { where += "created >= ?"; params += now - days * Time.DAY_MS }
        text.trim().takeIf { it.isNotEmpty() }?.let { where += "title LIKE ? ESCAPE '!'"; params += "%" + like(it) + "%" }
        return (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) to params
    }

    companion object {
        const val MAX_DAYS = 3650
        const val MAX_TEXT = 64

        fun like(s: String) = s.replace("!", "!!").replace("%", "!%").replace("_", "!_")

        fun parse(kind: String?, country: String?, days: String?, text: String?, deleted: String?) = NewsFilter(
            ArticleKind.entries.firstOrNull { it.name == kind?.trim()?.uppercase() },
            country?.trim()?.takeIf { it.isNotEmpty() },
            days?.trim()?.toIntOrNull()?.coerceIn(0, MAX_DAYS) ?: 0,
            text?.trim().orEmpty().take(MAX_TEXT),
            deleted?.trim()?.toBooleanStrictOrNull() == true
        )
    }
}
