package kami.claims.press

import kami.claims.admin.AdminNews
import kami.claims.client.admin.PressForm
import kami.claims.client.admin.PressTimes
import kami.libs.press.CostTable
import kami.libs.util.Time
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PressAdminTest {
    @Test
    fun settingsClampCapsCostAndEvents() {
        val s = PressSettings(
            dailyCap = 1000, perCountryCap = mapOf("a" to 500, "b" to 2), feeSpur = Long.MAX_VALUE, warUpdatesPerWar = -4,
            cost = CostTable(extraChars = 0, dyeChars = -3, basePaper = 9999), events = mapOf("bogus" to false, "founded" to false)
        ).sane()
        assertEquals(PressSettings.MAX_CAP, s.dailyCap)
        assertEquals(mapOf("b" to 2), s.perCountryCap)
        assertEquals(PressSettings.MAX_FEE, s.feeSpur)
        assertEquals(0, s.warUpdatesPerWar)
        assertEquals(1, s.cost.extraChars)
        assertEquals(1, s.cost.dyeChars)
        assertEquals(100, s.cost.basePaper)
        assertEquals(false, s.events["founded"])
        assertTrue("bogus" !in s.events)
    }

    @Test
    fun reportTimesAreTrimmedDistinctAndCapped() {
        val many = List(20) { "%02d:00".format(it) }
        val s = PressSettings(marketReport = ReportSchedule(times = listOf(" 12:00 ", "12:00", "Mon 08:00") + many)).sane()
        assertEquals(PressSettings.MAX_TIMES, s.marketReport.times.size)
        assertEquals(listOf("12:00", "Mon 08:00"), s.marketReport.times.take(2))
    }

    @Test
    fun badTimeNamesTheFirstInvalidEntry() {
        assertNull(PressSettings.badTime(listOf("12:00", "Mon 20:30")))
        assertEquals("25:00", PressSettings.badTime(listOf("12:00", "25:00")))
        assertNotNull(PressSettings.badTime(List(PressSettings.MAX_TIMES + 1) { "12:00" }))
        assertNull(PressTimes.problem(""))
        assertEquals("Funday 1:00", PressTimes.problem("12:00, Funday 1:00"))
        assertEquals(listOf("12:00", "Mon 8:30"), PressTimes.parse(" 12:00 , Mon 8:30,, "))
    }

    @Test
    fun filterBuildsOnlyTheRequestedClauses() {
        val (none, noParams) = NewsFilter().clause(1000)
        assertEquals(" WHERE deleted_at IS NULL", none)
        assertTrue(noParams.isEmpty())
        val now = 10 * Time.DAY_MS
        val (sql, params) = NewsFilter(ArticleKind.COUNTRY, "c1", 2, " 50%_off ", true).clause(now)
        assertEquals(" WHERE kind = ? AND country_id = ? AND created >= ? AND title LIKE ? ESCAPE '!'", sql)
        assertEquals(listOf("COUNTRY", "c1", now - 2 * Time.DAY_MS, "%50!%!_off%"), params)
    }

    @Test
    fun filterParsingIgnoresJunk() {
        val f = NewsFilter.parse("editorial", " c2 ", "99999", "x".repeat(200), "true")
        assertEquals(ArticleKind.EDITORIAL, f.kind)
        assertEquals("c2", f.country)
        assertEquals(NewsFilter.MAX_DAYS, f.days)
        assertEquals(NewsFilter.MAX_TEXT, f.text.length)
        assertTrue(f.deleted)
        val none = NewsFilter.parse("nope", "", "-4", null, "maybe")
        assertNull(none.kind)
        assertNull(none.country)
        assertEquals(0, none.days)
        assertEquals(false, none.deleted)
    }

    @Test
    fun settingsDiffListsOnlyChangedFields() {
        val before = PressSettings()
        val diff = AdminNews.diff(before, before.copy(dailyCap = 3, digest = false))
        assertEquals(setOf("dailyCap", "digest"), diff.keys)
        assertEquals("1 -> 3", diff["dailyCap"])
        assertTrue(AdminNews.diff(before, before).isEmpty())
    }

    @Test
    fun formRejectsEmptyAndOversizedText() {
        val cfg = PressSettings(titleMax = 10, subtitleMax = 5, bodyMax = 100)
        assertNull(PressForm.problem("Title", "", "Body", cfg))
        assertEquals("kami_claims.news.error.empty", PressForm.problem(" ", "", "Body", cfg))
        assertEquals("kami_claims.news.error.empty", PressForm.problem("Title", "", "  ", cfg))
        assertEquals("kami_claims.admin.press.too_long", PressForm.problem("x".repeat(11), "", "Body", cfg))
        assertEquals("kami_claims.admin.press.too_long", PressForm.problem("Title", "123456", "Body", cfg))
        assertEquals("kami_claims.admin.press.too_long", PressForm.problem("Title", "", "b".repeat(101), cfg))
    }
}
