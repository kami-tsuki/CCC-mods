package kami.claims.press

import kami.libs.db.Db
import kami.libs.db.Row
import kami.libs.db.Session
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

internal object NewsStore {
    private const val RECENT = 64
    private val strings = ListSerializer(String.serializer())
    private val authors = ListSerializer(Author.serializer())
    private val refs = ListSerializer(RefSnap.serializer())
    private val recent = object : LinkedHashMap<Long, Article>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Article>) = size > RECENT
    }
    private val states = HashMap<String, String>()
    private var drafts: MutableMap<Long, Draft>? = null

    private fun <T> read(block: Session.() -> T) = Db.read(block = block)

    private fun <T> cached(block: MutableMap<Long, Draft>.() -> T): T = synchronized(this) {
        block(drafts ?: read { query("SELECT * FROM news_draft", row = ::toDraft) }.associateBy { it.id }.toMutableMap().also { drafts = it })
    }

    private fun remember(a: Article) = synchronized(recent) {
        recent.remove(a.id)
        recent[a.id] = a
    }

    private fun touch(id: Long, change: (Article) -> Article) = article(id)?.let { remember(change(it)) }

    private fun recentIds(): Set<Long> = synchronized(recent) { recent.keys.toSet() }

    private fun recentCount(match: (Article) -> Boolean) = synchronized(recent) { recent.values.count(match) }

    private fun skip(ids: Set<Long>) = if (ids.isEmpty()) "" else ids.joinToString(",", " AND id NOT IN (", ")")

    internal fun reset() {
        synchronized(this) { drafts = null }
        synchronized(recent) { recent.clear() }
        synchronized(states) { states.clear() }
    }

    private fun toArticle(r: Row) = Article(
        r.long("id"), ArticleKind.valueOf(r.string("kind")), r.stringOrNull("country_id"), r.string("country_name"),
        Db.json.decodeFromString(strings, r.string("involved")), r.string("title"), r.string("subtitle"), r.string("body"),
        Db.json.decodeFromString(authors, r.string("authors")), Db.json.decodeFromString(refs, r.string("refs")),
        r.string("template_key"), Db.json.decodeFromString(strings, r.string("template_args")), r.long("created"), r.stringOrNull("dedupe"),
        r.stringOrNull("deleted_by"), r.longOrNull("deleted_at"), r.stringOrNull("deleted_reason"), r.stringOrNull("corrected_note"),
        Db.json.decodeFromString(strings, r.string("discord"))
    )

    private fun toDraft(r: Row) = Draft(
        r.long("id"), r.string("country_id"), r.string("title"), r.string("subtitle"), r.string("body"),
        Db.json.decodeFromString(strings, r.string("contributors")), r.stringOrNull("lock_holder"), r.long("lock_until"), r.long("lock_since"), r.long("updated")
    )

    fun maxArticle(): Long =
        maxOf(read { one("SELECT COALESCE(MAX(id), 0) AS n FROM news_article") { it.long("n") } } ?: 0, synchronized(recent) { recent.keys.maxOrNull() } ?: 0)

    fun maxDraft(): Long = cached { keys.maxOrNull() ?: 0 }

    fun insert(a: Article) {
        remember(a)
        Db.write {
            exec(
                "INSERT INTO news_article (id, kind, country_id, country_name, involved, title, subtitle, body, authors, refs, template_key, template_args, created, dedupe, deleted_by, deleted_at, deleted_reason, corrected_note, discord) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                a.id, a.kind.name, a.countryId, a.country, Db.json.encodeToString(strings, a.involved), a.title, a.subtitle, a.body,
                Db.json.encodeToString(authors, a.authors), Db.json.encodeToString(refs, a.refs), a.templateKey, Db.json.encodeToString(strings, a.templateArgs),
                a.created, a.dedupe, a.deletedBy, a.deletedAt, a.deletedReason, a.correctedNote, Db.json.encodeToString(strings, a.discord)
            )
        }
    }

    fun update(a: Article) {
        remember(a)
        Db.write {
            exec("UPDATE news_article SET title = ?, subtitle = ?, body = ?, refs = ?, corrected_note = ? WHERE id = ?", a.title, a.subtitle, a.body, Db.json.encodeToString(refs, a.refs), a.correctedNote, a.id)
        }
    }

    fun setDiscord(id: Long, ids: List<String>) {
        touch(id) { it.copy(discord = ids) }
        Db.write { exec("UPDATE news_article SET discord = ? WHERE id = ?", Db.json.encodeToString(strings, ids), id) }
    }

    fun markDeleted(id: Long, by: String, at: Long, reason: String) {
        touch(id) { it.copy(deletedBy = by, deletedAt = at, deletedReason = reason) }
        Db.write { exec("UPDATE news_article SET deleted_by = ?, deleted_at = ?, deleted_reason = ? WHERE id = ?", by, at, reason, id) }
    }

    fun search(f: NewsFilter, now: Long, page: Int, size: Int): Pair<List<Article>, Long> {
        val (where, params) = f.clause(now)
        return read {
            val total = one("SELECT COUNT(*) AS n FROM news_article" + where, *params.toTypedArray()) { it.long("n") } ?: 0L
            query("SELECT * FROM news_article" + where + " ORDER BY id DESC LIMIT ? OFFSET ?", *params.toTypedArray(), size, page.toLong() * size, row = ::toArticle) to total
        }
    }

    class DayCount(val day: Long, val kind: String, val count: Int)

    class CountryCount(val id: String, val name: String, val count: Int)

    fun perDay(since: Long): List<DayCount> = read {
        query("SELECT created / 86400000 AS d, kind, COUNT(*) AS n FROM news_article WHERE created >= ? AND deleted_at IS NULL GROUP BY d, kind", since) { DayCount(it.long("d"), it.string("kind"), it.int("n")) }
    }

    fun perCountry(since: Long, limit: Int): List<CountryCount> = read {
        query(
            "SELECT country_id, MAX(country_name) AS name, COUNT(*) AS n FROM news_article WHERE kind = 'COUNTRY' AND created >= ? AND deleted_at IS NULL GROUP BY country_id ORDER BY n DESC LIMIT ?",
            since, limit
        ) { CountryCount(it.string("country_id"), it.string("name"), it.int("n")) }
    }

    fun authorLists(since: Long): List<List<Author>> =
        read { query("SELECT authors FROM news_article WHERE kind = 'COUNTRY' AND created >= ? AND deleted_at IS NULL", since) { Db.json.decodeFromString(authors, it.string("authors")) } }

    fun openDrafts(): Pair<Int, Long> = cached { size to (values.minOfOrNull { it.updated } ?: 0L) }

    fun remove(id: Long) {
        synchronized(recent) { recent.remove(id) }
        Db.write { exec("DELETE FROM news_article WHERE id = ?", id) }
    }

    fun article(id: Long): Article? = synchronized(recent) { recent[id] } ?: read { one("SELECT * FROM news_article WHERE id = ?", id, row = ::toArticle) }

    fun list(before: Long, limit: Int, kinds: Set<ArticleKind>, countries: Set<String>, since: Long): List<Article> {
        val where = mutableListOf("id < ?", "created >= ?", "deleted_at IS NULL")
        val params = mutableListOf<Any?>(before, since)
        if (kinds.isNotEmpty()) {
            where += kinds.joinToString(",", "kind IN (", ")") { "?" }
            kinds.forEach { params += it.name }
        }
        if (countries.isNotEmpty()) {
            where += countries.joinToString(" OR ", "(", ")") { "instr(involved, ?) > 0" }
            countries.forEach { params += "\"$it\"" }
        }
        val sql = "SELECT * FROM news_article WHERE " + where.joinToString(" AND ") + " ORDER BY id DESC LIMIT ?"
        return read { query(sql, *params.toTypedArray(), limit, row = ::toArticle) }
    }

    fun published(country: String, since: Long): Int {
        val ids = recentIds()
        val stored = read { one("SELECT COUNT(*) AS n FROM news_article WHERE kind = 'COUNTRY' AND country_id = ? AND created >= ?" + skip(ids), country, since) { it.int("n") } } ?: 0
        return stored + recentCount { it.kind == ArticleKind.COUNTRY && it.countryId == country && it.created >= since }
    }

    fun limited(since: Long, bulk: Collection<String>): Int {
        val ids = recentIds()
        val sql = "SELECT COUNT(*) AS n FROM news_article WHERE kind = 'SYSTEM' AND created >= ? AND template_key NOT IN (" + bulk.joinToString(",") { "?" } + ")" + skip(ids)
        val stored = read { one(sql, since, *bulk.toTypedArray()) { it.int("n") } } ?: 0
        return stored + recentCount { it.kind == ArticleKind.SYSTEM && it.created >= since && it.templateKey !in bulk }
    }

    fun dedupeExists(key: String): Boolean =
        recentCount { it.dedupe == key } > 0 || read { one("SELECT 1 AS n FROM news_article WHERE dedupe = ?", key) { it.int("n") } } != null

    fun dedupeCount(prefix: String, since: Long = 0): Int {
        val ids = recentIds()
        val stored = read {
            one("SELECT COUNT(*) AS n FROM news_article WHERE substr(dedupe, 1, ?) = ? AND created >= ?" + skip(ids), prefix.length, prefix, since) { it.int("n") }
        } ?: 0
        return stored + recentCount { it.dedupe?.startsWith(prefix) == true && it.created >= since }
    }

    fun prune(before: Long) {
        synchronized(recent) { recent.values.removeIf { it.created < before } }
        Db.write { exec("DELETE FROM news_article WHERE created < ?", before) }
    }

    fun draft(id: Long): Draft? = cached { get(id) }

    fun drafts(country: String): List<Draft> = cached { values.filter { it.countryId == country }.sortedBy { it.id } }

    fun draftCount(country: String): Int = cached { values.count { it.countryId == country } }

    fun draftCountries(): List<String> = cached { values.map { it.countryId }.distinct() }

    fun save(d: Draft) {
        cached { put(d.id, d) }
        Db.write {
            exec(
                "INSERT INTO news_draft (id, country_id, title, subtitle, body, contributors, lock_holder, lock_until, lock_since, updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(id) DO UPDATE SET title = excluded.title, subtitle = excluded.subtitle, body = excluded.body, contributors = excluded.contributors, " +
                    "lock_holder = excluded.lock_holder, lock_until = excluded.lock_until, lock_since = excluded.lock_since, updated = excluded.updated",
                d.id, d.countryId, d.title, d.subtitle, d.body, Db.json.encodeToString(strings, d.contributors), d.lockHolder, d.lockUntil, d.lockSince, d.updated
            )
        }
    }

    fun deleteDraft(id: Long) {
        cached { remove(id) }
        Db.write { exec("DELETE FROM news_draft WHERE id = ?", id) }
    }

    fun deleteDraftsOf(countries: Collection<String>) {
        cached { values.removeIf { it.countryId in countries } }
        Db.write { countries.forEach { exec("DELETE FROM news_draft WHERE country_id = ?", it) } }
    }

    fun state(key: String): String? = synchronized(states) { states[key] } ?: read { one("SELECT value FROM news_state WHERE key = ?", key) { it.string("value") } }

    fun putState(key: String, value: String) {
        synchronized(states) { states[key] = value }
        Db.write { exec("INSERT INTO news_state (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value) }
    }
}
