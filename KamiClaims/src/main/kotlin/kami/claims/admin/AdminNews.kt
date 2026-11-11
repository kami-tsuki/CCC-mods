package kami.claims.admin

import kami.claims.Config
import kami.claims.Realm
import kami.claims.now
import kami.claims.press.Article
import kami.claims.press.ArticleKind
import kami.claims.press.Corrections
import kami.claims.press.News
import kami.claims.press.NewsDelivery
import kami.claims.press.NewsFilter
import kami.claims.press.NewsReports
import kami.claims.press.NewsStore
import kami.claims.press.PressSettings
import kami.claims.service.Fail
import kami.libs.admin.Admin
import kami.libs.admin.AdminCtx
import kami.libs.admin.Page
import kami.libs.admin.long
import kami.libs.admin.page
import kami.libs.admin.text
import kami.libs.db.Db
import kami.libs.discord.DiscordText
import kami.libs.press.Markup
import kami.libs.util.Time
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

object NewsRequests {
    const val INSIGHTS = "press.insights"
    const val LIST = "press.list"
    const val GET = "press.get"
    const val DELETE = "press.delete"
    const val MODIFY = "press.modify"
    const val ADD = "press.add"
    const val SETTINGS = "press.settings"
    const val SETTINGS_SAVE = "press.settings_save"
    const val REPORT = "press.report"
}

@Serializable
class NewsDay(val day: Long = 0, val system: Int = 0, val country: Int = 0, val editorial: Int = 0)

@Serializable
class NewsCount(val id: String = "", val name: String = "", val count: Int = 0)

@Serializable
class NewsInsights(
    val days: List<NewsDay> = emptyList(), val countries: List<NewsCount> = emptyList(), val authors: List<NewsCount> = emptyList(),
    val total: Int = 0, val drafts: Int = 0, val oldestDraft: Long = 0
)

@Serializable
class NewsRow(
    val id: Long = 0, val kind: String = "", val title: String = "", val country: String = "", val authors: String = "", val created: Long = 0,
    val deleted: Boolean = false, val corrected: Boolean = false
)

@Serializable
class NewsFull(
    val id: Long = 0, val kind: String = "", val title: String = "", val subtitle: String = "", val body: String = "", val country: String = "",
    val authors: List<String> = emptyList(), val created: Long = 0, val system: Boolean = false, val deletedBy: String = "", val deletedAt: Long = 0,
    val deletedReason: String = "", val corrected: String = ""
)

object AdminNews {
    const val DAYS = 30
    const val TOP = 10
    const val DEFAULT_AUTHOR = "Editorial Board"
    const val AUTHOR_MAX = 40

    fun register() {
        Admin.view(NewsRequests.INSIGHTS) { _, _ -> Db.encode(NewsInsights.serializer(), insights()) }
        Admin.view(NewsRequests.LIST) { _, a ->
            val size = Page.SIZE
            val page = a.page(5).coerceAtMost(Page.MAX_PAGE)
            val (rows, total) = NewsStore.search(NewsFilter.parse(a.text(0), a.text(1), a.text(2), a.text(3), a.text(4)), now(), page, size)
            Db.encode(Page.serializer(NewsRow.serializer()), Page(rows.map(::row), total, page, size))
        }
        Admin.view(NewsRequests.GET) { ctx, a -> Db.encode(NewsFull.serializer(), full(article(ctx, a))) }
        Admin.view(NewsRequests.SETTINGS) { _, _ -> Db.encode(PressSettings.serializer(), Config.s.press) }
        Admin.act(NewsRequests.DELETE) { ctx, a ->
            val article = article(ctx, a)
            if (!News.retract(article.id, ctx.player.scoreboardName, ctx.reason)) ctx.fail("kami_claims.admin.press.unknown", article.id)
            runCatching { NewsDelivery.retract(article) }
            Admin.done(label(article), audit = mapOf("kind" to article.kind.name))
        }
        Admin.act(NewsRequests.MODIFY) { ctx, a -> modify(ctx, a) }
        Admin.act(NewsRequests.ADD) { ctx, a -> add(ctx, a) }
        Admin.act(NewsRequests.SETTINGS_SAVE) { ctx, a -> saveSettings(ctx, a) }
        Admin.act(NewsRequests.REPORT) { ctx, a ->
            val name = a.text(0)?.takeIf { it in NewsReports.NAMES } ?: ctx.fail("kami_claims.admin.press.report_unknown", a.text(0).orEmpty())
            if (!NewsReports.runNow(name)) ctx.fail("kami_claims.admin.press.report_empty", name)
            Admin.done(name)
        }
    }

    private fun article(ctx: AdminCtx, a: List<String>): Article =
        a.long(0)?.let(News::article) ?: ctx.fail("kami_claims.admin.press.unknown", a.text(0).orEmpty())

    private fun label(a: Article) = "#${a.id} ${title(a).take(LABEL)}"

    private fun title(a: Article) = if (a.templateKey.isEmpty()) a.title else DiscordText.phrase(a.headline())

    private fun authors(a: Article) = a.authors.map { it.name }

    private fun row(a: Article) = NewsRow(a.id, a.kind.name, title(a), a.country, authors(a).joinToString(", "), a.created, a.deleted, a.correctedNote != null)

    private fun full(a: Article) = NewsFull(
        a.id, a.kind.name, title(a), a.subtitle, a.body, a.country, authors(a), a.created, a.templateKey.isNotEmpty(), a.deletedBy.orEmpty(), a.deletedAt ?: 0,
        a.deletedReason.orEmpty(), a.correctedNote.orEmpty()
    )

    private fun insights(): NewsInsights {
        val today = now() / Time.DAY_MS
        val first = today - DAYS + 1
        val since = first * Time.DAY_MS
        val counts = NewsStore.perDay(since).groupBy { it.day }
        val days = (first..today).map { d ->
            fun of(kind: ArticleKind) = counts[d].orEmpty().filter { it.kind == kind.name }.sumOf { it.count }
            NewsDay(d, of(ArticleKind.SYSTEM), of(ArticleKind.COUNTRY), of(ArticleKind.EDITORIAL))
        }
        val authors = NewsStore.authorLists(since).flatten().groupBy { it.id }.map { (id, l) -> NewsCount(id, l.last().name, l.size) }.sortedByDescending { it.count }.take(TOP)
        val (drafts, oldest) = NewsStore.openDrafts()
        return NewsInsights(days, NewsStore.perCountry(since, TOP).map { NewsCount(it.id, it.name, it.count) }, authors, days.sumOf { it.system + it.country + it.editorial }, drafts, oldest)
    }

    private fun clean(ctx: AdminCtx, a: List<String>, first: Int): Triple<String, String, String> {
        val cfg = Config.s.press
        val title = Markup.clean(a.getOrNull(first).orEmpty(), cfg.titleMax)
        val subtitle = Markup.clean(a.getOrNull(first + 1).orEmpty(), cfg.subtitleMax)
        val body = Markup.clean(a.getOrNull(first + 2).orEmpty(), cfg.bodyMax)
        if (title.isEmpty() || body.isEmpty()) ctx.fail("kami_claims.news.error.empty")
        return Triple(title, subtitle, body)
    }

    private fun modify(ctx: AdminCtx, a: List<String>): JsonObject {
        val old = article(ctx, a)
        if (old.deleted || old.templateKey.isNotEmpty() || old.kind == ArticleKind.SYSTEM) ctx.fail("kami_claims.admin.press.locked", old.id)
        val (title, subtitle, body) = clean(ctx, a, 1)
        if (title == old.title && subtitle == old.subtitle && body == old.body) ctx.fail("kami_claims.admin.press.unchanged")
        val saved = guarded(ctx) { News.amend(old.id, title, subtitle, body, correction()) } ?: ctx.fail("kami_claims.admin.press.locked", old.id)
        runCatching { NewsDelivery.update(saved) }
        return Admin.done(label(saved), audit = changes(old, saved))
    }

    private fun <T> guarded(ctx: AdminCtx, run: () -> T): T = try {
        run()
    } catch (e: Fail) {
        ctx.fail(e.phrase.key, *e.phrase.args.map { it.json() }.toTypedArray())
    }

    private fun add(ctx: AdminCtx, a: List<String>): JsonObject {
        val (title, subtitle, body) = clean(ctx, a, 0)
        val author = Markup.clean(a.getOrNull(3).orEmpty(), AUTHOR_MAX).ifEmpty { DEFAULT_AUTHOR }
        val created = guarded(ctx) { News.editorial(title, subtitle, body, author) }
        return Admin.done(label(created), audit = mapOf("author" to author))
    }

    private fun correction() = Corrections.mark(now())

    fun changes(old: Article, next: Article): Map<String, String> = buildMap {
        if (old.title != next.title) put("title", "${old.title} -> ${next.title}")
        if (old.subtitle != next.subtitle) put("subtitle", "${old.subtitle} -> ${next.subtitle}")
        if (old.body != next.body) put("body", "${old.body.length} -> ${next.body.length} chars")
    }

    private fun saveSettings(ctx: AdminCtx, a: List<String>): JsonObject {
        val parsed = a.text(0)?.let { runCatching { Db.json.decodeFromString(PressSettings.serializer(), it) }.getOrNull() } ?: ctx.fail("kami_claims.admin.press.invalid")
        val before = Config.s.press
        listOf("market" to parsed.marketReport, "ranking" to parsed.rankingReport).forEach { (name, schedule) ->
            PressSettings.badTime(schedule.times)?.let { ctx.fail("kami_claims.admin.press.bad_time", name, it) }
        }
        parsed.perCountryCap.keys.firstOrNull { it !in before.perCountryCap && Realm.country(it) == null }?.let { ctx.fail("kami_claims.error.unknown_country") }
        if (parsed.rev != before.rev) ctx.fail("kami_claims.admin.press.stale")
        val after = parsed.sane()
        val diff = diff(before, after)
        if (diff.isEmpty()) ctx.fail("kami_claims.admin.press.unchanged")
        Config.save(Config.s.copy(press = after.copy(rev = before.rev + 1)))
        return Admin.done("press", audit = diff)
    }

    fun diff(before: PressSettings, after: PressSettings): Map<String, String> {
        val a = Db.json.encodeToJsonElement(PressSettings.serializer(), before) as JsonObject
        val b = Db.json.encodeToJsonElement(PressSettings.serializer(), after) as JsonObject
        return b.filter { (k, v) -> a[k] != v }.mapValues { (k, v) -> "${a[k]} -> $v" }
    }

    private const val LABEL = 60
}
