package kami.claims.press

import kami.claims.WithDb
import kami.claims.now
import kami.libs.db.Db
import kami.libs.db.SavePoint
import kami.libs.util.Time
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsStoreTest : WithDb() {
    @BeforeTest
    fun resetStore() = NewsStore.reset()

    private fun article(id: Long, kind: ArticleKind, country: String?, created: Long, template: String = "", dedupe: String? = null) =
        Article(id, kind, country, country.orEmpty(), listOfNotNull(country), "T$id", "", "body", listOf(Author("u", "U")), listOf(RefSnap("price", "minecraft:iron_ingot", 5, created)), template, listOf("a"), created, dedupe)

    @Test
    fun articlesRoundTripAndFilter() {
        val t = now()
        NewsStore.insert(article(1, ArticleKind.COUNTRY, "alpha", t))
        NewsStore.insert(article(2, ArticleKind.SYSTEM, "beta", t + 1, "founded", "founded:beta:1"))
        Db.flush()
        val got = NewsStore.article(1)!!
        assertEquals(listOf(Author("u", "U")), got.authors)
        assertEquals(5L, got.refs.single().value)
        assertEquals(listOf(2L, 1L), NewsStore.list(Long.MAX_VALUE, 10, emptySet(), emptySet(), 0).map { it.id })
        assertEquals(listOf(1L), NewsStore.list(Long.MAX_VALUE, 10, setOf(ArticleKind.COUNTRY), emptySet(), 0).map { it.id })
        assertEquals(listOf(2L), NewsStore.list(Long.MAX_VALUE, 10, emptySet(), setOf("beta"), 0).map { it.id })
        assertEquals(listOf(1L), NewsStore.list(2, 10, emptySet(), emptySet(), 0).map { it.id })
    }

    @Test
    fun publishedCountsCountryArticlesPerDay() {
        val day = now() - now() % Time.DAY_MS
        NewsStore.insert(article(1, ArticleKind.COUNTRY, "alpha", day + 5))
        NewsStore.insert(article(2, ArticleKind.COUNTRY, "alpha", day - 5))
        NewsStore.insert(article(3, ArticleKind.COUNTRY, "beta", day + 6))
        assertEquals(1, NewsStore.published("alpha", day))
        assertEquals(2, NewsStore.published("alpha", 0))
    }

    @Test
    fun dedupeAndLimitedCounts() {
        val t = now()
        NewsStore.insert(article(1, ArticleKind.SYSTEM, "a", t, "warPhase.war", "war:a+b:warPhase:$t"))
        NewsStore.insert(article(2, ArticleKind.SYSTEM, "a", t, "marketReport", "market:1"))
        NewsStore.insert(article(3, ArticleKind.SYSTEM, "a", t - 2 * Time.HOUR_MS, "founded"))
        assertTrue(NewsStore.dedupeExists("market:1"))
        assertFalse(NewsStore.dedupeExists("market:2"))
        assertEquals(1, NewsStore.dedupeCount("war:a+b:"))
        assertEquals(0, NewsStore.dedupeCount("war:a+c:"))
        assertEquals(1, NewsStore.limited(t - Time.HOUR_MS, News.BULK))
        assertEquals(2, NewsStore.limited(0, News.BULK))
    }

    @Test
    fun draftsSaveUpdateAndDelete() {
        val d = Draft(1, "alpha", "t", "s", "b", listOf("u"), "u", 100, 50, 60)
        NewsStore.save(d)
        NewsStore.save(d.copy(id = 2, countryId = "beta"))
        NewsStore.save(d.copy(title = "t2", lockHolder = null))
        assertEquals("t2", NewsStore.draft(1)!!.title)
        assertNull(NewsStore.draft(1)!!.lockHolder)
        assertEquals(1, NewsStore.draftCount("alpha"))
        assertEquals(setOf("alpha", "beta"), NewsStore.draftCountries().toSet())
        NewsStore.deleteDraftsOf(listOf("beta"))
        NewsStore.deleteDraft(1)
        assertEquals(0, NewsStore.draftCount("alpha") + NewsStore.draftCount("beta"))
    }

    @Test
    fun pruneRemovesOldArticlesAndStateUpserts() {
        val t = now()
        NewsStore.insert(article(1, ArticleKind.COUNTRY, "alpha", t - 100 * Time.DAY_MS))
        NewsStore.insert(article(2, ArticleKind.COUNTRY, "alpha", t))
        NewsStore.prune(t - 90 * Time.DAY_MS)
        Db.flush()
        assertNull(NewsStore.article(1))
        assertEquals(2L, NewsStore.article(2)!!.id)
        NewsStore.putState("k", "1")
        NewsStore.putState("k", "2")
        assertEquals("2", NewsStore.state("k"))
        assertNull(NewsStore.state("missing"))
    }

    @Test
    fun warUpdatesCountPerWarAndPerKind() {
        val t = now()
        NewsStore.insert(article(1, ArticleKind.SYSTEM, "a", t, "warPhase.war", "war:w1:warPhase.war:$t"))
        NewsStore.insert(article(2, ArticleKind.SYSTEM, "a", t, "warProvince", "war:w2:warProvince:$t"))
        assertEquals(1, NewsStore.dedupeCount("war:w1:"))
        assertEquals(1, NewsStore.dedupeCount("war:w1:warPhase.war:", t - t % Time.DAY_MS))
        assertEquals(0, NewsStore.dedupeCount("war:w1:warPhase.ceasefire:", t - t % Time.DAY_MS))
        assertEquals(0, NewsStore.dedupeCount("war:w1:warProvince:"))
        assertEquals(1, NewsStore.dedupeCount("war:w2:warProvince:"))
    }

    @Test
    fun savePointKeepsArticlesWrittenBeforeIt() {
        NewsStore.insert(article(1, ArticleKind.COUNTRY, "alpha", now()))
        SavePoint.run()
        SavePoint.discardUnsaved()
        assertEquals(1L, NewsStore.article(1)?.id)
    }

    @Test
    fun draftsReadBackWithoutFlush() {
        val d = Draft(1, "alpha", "t", "s", "b", listOf("u"), "u", 100, 50, 60)
        NewsStore.save(d)
        NewsStore.save(d.copy(id = 2, updated = 40))
        NewsStore.save(d.copy(id = 3, countryId = "beta", updated = 70))
        assertEquals(listOf(1L, 2L), NewsStore.drafts("alpha").map { it.id })
        assertEquals(2, NewsStore.draftCount("alpha"))
        assertEquals(3L, NewsStore.maxDraft())
        assertEquals(3 to 40L, NewsStore.openDrafts())
        NewsStore.save(d.copy(lockHolder = null, lockUntil = 0))
        assertNull(NewsStore.draft(1)!!.lockHolder)
        NewsStore.deleteDraft(2)
        assertNull(NewsStore.draft(2))
        assertEquals(1, NewsStore.draftCount("alpha"))
    }

    @Test
    fun draftsSurviveAColdCacheFromTheDatabase() {
        NewsStore.save(Draft(1, "alpha", "t", "s", "b", listOf("u"), "u", 100, 50, 60))
        Db.flush()
        NewsStore.reset()
        assertEquals("t", NewsStore.draft(1)!!.title)
        assertEquals(1L, NewsStore.maxDraft())
    }

    @Test
    fun articleChecksSeeUnflushedWrites() {
        val day = now() - now() % Time.DAY_MS
        NewsStore.insert(article(1, ArticleKind.COUNTRY, "alpha", day + 5, dedupe = "k"))
        NewsStore.insert(article(2, ArticleKind.SYSTEM, "a", day + 5, "founded", "founded:1"))
        assertEquals(1, NewsStore.published("alpha", day))
        assertTrue(NewsStore.dedupeExists("founded:1"))
        assertEquals(1, NewsStore.limited(day, News.BULK))
        assertEquals(2L, NewsStore.maxArticle())
        NewsStore.markDeleted(1, "x", day + 9, "r")
        assertTrue(NewsStore.article(1)!!.deleted)
        assertEquals(1, NewsStore.published("alpha", day))
        Db.flush()
        assertEquals(1, NewsStore.published("alpha", day))
        assertEquals(1, NewsStore.limited(day, News.BULK))
        NewsStore.putState("s", "1")
        assertEquals("1", NewsStore.state("s"))
    }

    @Test
    fun digestCollectsOverflowAndFlushesOnALaterDay() {
        NewsDigest.add(listOf("founded", "A", "B"))
        NewsDigest.add(listOf("disbanded", "C"))
        NewsDigest.flush()
        Db.flush()
        assertEquals(0, NewsStore.list(Long.MAX_VALUE, 10, emptySet(), emptySet(), 0).size)
        NewsStore.putState("digest", """{"day":1,"items":[["founded","A","B"],["disbanded","C"]]}""")
        NewsDigest.flush()
        Db.flush()
        val digest = NewsStore.list(Long.MAX_VALUE, 10, emptySet(), emptySet(), 0).single()
        assertEquals("digest", digest.templateKey)
        assertEquals(listOf("founded${PressRules.UNIT}A${PressRules.UNIT}B", "disbanded${PressRules.UNIT}C"), digest.templateArgs)
    }
}
