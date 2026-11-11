package kami.claims.net

import kami.claims.press.Article
import kami.claims.press.ArticleKind
import kami.claims.press.Author
import kami.claims.press.Draft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PressDtoTest {
    private fun article(country: String? = "a", authors: Int = 40, involved: Int = 12) = Article(
        7, ArticleKind.COUNTRY, country, "Alpha", List(involved) { "c$it" }, "T", "S", "B", List(authors) { Author("u$it", "n$it") }, emptyList(), "", emptyList(), 100, correctedNote = null
    )

    private fun draft(holder: String?, until: Long, updated: Long = 50, since: Long = 10) =
        Draft(3, "a", "T", "S", "body", listOf("u1"), holder, until, since, updated)

    @Test
    fun bodyIsSplitAndJoinedLosslessly() {
        val body = "x".repeat(PressDto.CHUNK * 2 + 5) + "😀"
        val parts = PressDto.split(body)
        assertTrue(parts.all { it.length <= PressDto.CHUNK })
        assertEquals(body, PressDto.join(listOf("1", "t", "s", "0") + parts, 4))
        assertEquals("", PressDto.join(listOf("1", "t", "s", "0"), 4))
        assertTrue(PressDto.split("").isEmpty())
    }

    @Test
    fun filtersAreBoundedAndIgnoreUnknownValues() {
        assertEquals(setOf(ArticleKind.SYSTEM, ArticleKind.COUNTRY), PressDto.kinds("SYSTEM,bogus,COUNTRY"))
        assertEquals(PressDto.MAX_FILTER, PressDto.ids((1..30).joinToString(",")).size)
        assertTrue(PressDto.ids(",, ,${"x".repeat(65)}").isEmpty())
    }

    @Test
    fun feedItemBoundsAuthorsAndFallsBackToTheStoredCountryName() {
        val item = PressDto.item(article()) { null }
        assertEquals(PressDto.MAX_AUTHORS, item.authors.size)
        assertEquals(PressCountry("a", "Alpha"), item.country)
        assertEquals(PressCountry(name = "Alpha"), PressDto.item(article(country = null)) { null }.country)
        assertEquals(PressCountry("a", "Live", 5, 1, 2, 3), PressDto.item(article()) { PressCountry(it, "Live", 5, 1, 2, 3) }.country)
    }

    @Test
    fun articleBoundsInvolvedCountries() {
        val dto = PressDto.article(article()) { null }
        assertEquals(PressDto.MAX_INVOLVED, dto.involved.size)
        assertEquals("", dto.corrected)
    }

    @Test
    fun draftLockStateIsRelativeToTheViewer() {
        val name = { id: String -> "name-$id" }
        val free = PressDto.draft(draft(null, 0), "me", 1000, name, emptyMap(), true)
        assertEquals(PressDto.FREE, free.lock)
        assertNull(free.holder)
        assertEquals("body", free.body)
        val mine = PressDto.draft(draft("me", 5000), "me", 1000, name, emptyMap(), false)
        assertEquals("mine", mine.lock)
        assertEquals(4000, mine.leftMs)
        assertEquals("", mine.body)
        assertEquals(4, mine.chars)
        val other = PressDto.draft(draft("you", 5000), "me", 1000, name, mapOf("minecraft:paper" to 2), false)
        assertEquals("other", other.lock)
        assertEquals(Author("you", "name-you"), other.holder)
        assertTrue(other.takeover)
        assertEquals(listOf(Author("u1", "name-u1")), other.contributors)
        assertEquals(false, PressDto.draft(draft("you", 5000, updated = 5, since = 10), "me", 1000, name, emptyMap(), false).takeover)
        assertEquals(PressDto.FREE, PressDto.draft(draft("you", 500), "me", 1000, name, emptyMap(), false).lock)
    }

    @Test
    fun dtosDecodeWithSafeDefaultsAndGarbageFallsBack() {
        assertEquals(DraftDto(), PressDto.decode(DraftDto.serializer(), "{}", DraftDto(id = 9)))
        assertEquals(FeedDto(), PressDto.decode(FeedDto.serializer(), "{\"unknown\":1}", FeedDto(tag = 9)))
        assertEquals(9, PressDto.decode(FeedDto.serializer(), "not json", FeedDto(tag = 9)).tag)
    }
}
