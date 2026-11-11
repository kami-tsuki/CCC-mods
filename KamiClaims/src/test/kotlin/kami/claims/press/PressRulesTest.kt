package kami.claims.press

import kami.libs.press.Cost
import kami.libs.press.Dye
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PressRulesTest {
    private val cfg = PressSettings(dailyCap = 1, perCountryCap = mapOf("big" to 3), warUpdatesPerWar = 3, warUpdatesPerDay = 1)

    @Test
    fun capUsesTheCountryOverrideAndStopsAtTheLimit() {
        assertEquals(1, PressRules.capFor(cfg, "small"))
        assertEquals(3, PressRules.capFor(cfg, "big"))
        assertFalse(PressRules.capReached(0, 1))
        assertTrue(PressRules.capReached(1, 1))
        assertTrue(PressRules.capReached(0, 0))
    }

    @Test
    fun draftSlotsAreFullAtTheLimit() {
        assertFalse(PressRules.slotsFull(4, 5))
        assertTrue(PressRules.slotsFull(5, 5))
    }

    @Test
    fun lockExpiresAndBelongsToOneHolder() {
        val until = PressRules.lockExpiry(1_000, 5)
        assertEquals(1_000 + 300_000L, until)
        assertEquals(PressRules.Lock.MINE, PressRules.lockState("a", until, "a", 2_000))
        assertEquals(PressRules.Lock.OTHER, PressRules.lockState("a", until, "b", 2_000))
        assertEquals(PressRules.Lock.FREE, PressRules.lockState("a", until, "b", until))
        assertEquals(PressRules.Lock.FREE, PressRules.lockState(null, until, "b", 2_000))
    }

    @Test
    fun takeoverNeedsAnExpiredLockOrAForceAfterASave() {
        assertTrue(PressRules.mayTakeOver(PressRules.Lock.FREE, false, false))
        assertFalse(PressRules.mayTakeOver(PressRules.Lock.OTHER, true, false))
        assertFalse(PressRules.mayTakeOver(PressRules.Lock.OTHER, false, true))
        assertTrue(PressRules.mayTakeOver(PressRules.Lock.OTHER, true, true))
        assertTrue(PressRules.savedSinceLock(500, 500))
        assertFalse(PressRules.savedSinceLock(499, 500))
    }

    @Test
    fun autosaveIsRateLimitedButManualSaveIsNot() {
        assertFalse(PressRules.autosaveAllowed(1_000, 5_000, 10, true))
        assertTrue(PressRules.autosaveAllowed(1_000, 11_000, 10, true))
        assertTrue(PressRules.autosaveAllowed(1_000, 1_001, 10, false))
    }

    @Test
    fun contributorsOnlyGrowOnChangeAndNeverDuplicate() {
        assertEquals(emptyList(), PressRules.contributors(emptyList(), "a", false))
        assertEquals(listOf("a"), PressRules.contributors(emptyList(), "a", true))
        assertEquals(listOf("a"), PressRules.contributors(listOf("a"), "a", true))
        assertEquals(listOf("a", "b"), PressRules.contributors(listOf("a"), "b", true))
        assertEquals(listOf("a", "b"), PressRules.authors(listOf("a", "b"), "b"))
        assertEquals(listOf("a", "c"), PressRules.authors(listOf("a"), "c"))
    }

    @Test
    fun hourlyLimitAndWarLimits() {
        assertFalse(PressRules.hourlyFull(5, 6))
        assertTrue(PressRules.hourlyFull(6, 6))
        assertTrue(PressRules.warAllowed(0, 0, cfg))
        assertFalse(PressRules.warAllowed(0, 1, cfg))
        assertFalse(PressRules.warAllowed(3, 0, cfg))
        assertEquals(PressRules.warKey("a", "b"), PressRules.warKey("b", "a"))
    }

    @Test
    fun costBecomesItemsAndMissingIsWhatIsShort() {
        val need = PressRules.need(Cost(2, 3, mapOf(Dye.RED to 2, Dye.BLUE to 1)))
        assertEquals(mapOf("minecraft:paper" to 2, "minecraft:ink_sac" to 3, "minecraft:red_dye" to 2, "minecraft:blue_dye" to 1), need)
        assertEquals(emptyMap(), PressRules.need(Cost(0, 0, emptyMap())))
        val have = mapOf("minecraft:paper" to 5, "minecraft:ink_sac" to 1, "minecraft:red_dye" to 2)
        assertEquals(mapOf("minecraft:ink_sac" to 2, "minecraft:blue_dye" to 1), PressRules.missing(need, have))
    }

    @Test
    fun settingsClampBadValues() {
        val s = PressSettings(draftSlots = 0, lockMinutes = 0, feeSpur = -5, bodyMax = 1, marketReport = ReportSchedule(times = listOf("12:00", "25:99", "Mon 8:30", "Funday 1:00"))).sane()
        assertEquals(1, s.draftSlots)
        assertEquals(1, s.lockMinutes)
        assertEquals(0, s.feeSpur)
        assertEquals(100, s.bodyMax)
        assertEquals(listOf("12:00", "Mon 8:30"), s.marketReport.times)
        assertTrue(s.events.getValue("warDeclared"))
    }
}
