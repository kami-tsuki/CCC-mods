package kami.claims.gov

import kami.claims.Claimant
import kami.claims.Config
import kami.claims.Country
import kami.claims.Member
import kami.claims.Realm
import kami.claims.now
import kami.claims.service.Housing
import kami.claims.service.Upkeep
import kami.claims.war.Joining
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeverTest : GovTest() {
    private val day get() = Config.s.dayMillis

    private fun withLeadership(leadership: Leadership): Country {
        val c = country(mapOf("boss" to PRESIDENT))
        c.government.levers = c.government.levers.copy(leadership = leadership)
        listOf("a", "b", "c").forEachIndexed { i, id -> c.members[id] = Member(CITIZEN, since = now() - (10 - i) * day) }
        return c
    }

    @Test
    fun wageFloorExistsOnlyForWageSocialismAndMixed() {
        fun floor(preset: PresetId, labour: Labour = Presets.levers(preset).labour) = Government(preset, Presets.levers(preset).copy(labour = labour)).wageFloor
        assertEquals(0.5, floor(PresetId.SOCIALISM))
        assertEquals(0.25, floor(PresetId.MIXED))
        assertEquals(0.0, floor(PresetId.SOCIALISM, Labour.QUOTA))
        assertEquals(0.0, floor(PresetId.CAPITALISM))
    }

    @Test
    fun aRankChangeBillsTheOldTierForThreeDays() {
        val c = standard()
        val member = c.members.getValue("chan")
        assertEquals(1.2, Housing.levy(c, Claimant.CITIZEN, "chan"), 1e-9)
        member.changeRank(CITIZEN)
        assertEquals(1.2, Housing.levy(c, Claimant.CITIZEN, "chan"), 1e-9)
        assertEquals("chancellor", Housing.taxedRank(c, member, now() + 2 * day))
        assertEquals(CITIZEN, Housing.taxedRank(c, member, now() + 4 * day))
    }

    @Test
    fun theDelayEndsExactlyAfterThreeDays() {
        val c = standard()
        val member = c.members.getValue("chan")
        member.changeRank(CITIZEN)
        val edge = member.rankAt + 3 * Config.s.dayMillis
        assertEquals("chancellor", Housing.taxedRank(c, member, edge - 1))
        assertEquals(CITIZEN, Housing.taxedRank(c, member, edge))
    }

    @Test
    fun demotingAndRepromotingKeepsTheHigherTier() {
        val c = standard()
        val member = c.members.getValue("chan")
        member.changeRank(CITIZEN, c)
        member.changeRank("chancellor", c)
        assertEquals("chancellor", member.rankPrev)
    }

    @Test
    fun aDeletedPreviousRankIsNotBilled() {
        val c = standard()
        val member = c.members.getValue("off")
        member.changeRank(CITIZEN)
        c.ranks.removeIf { it.id == "officer" }
        assertEquals(CITIZEN, Housing.taxedRank(c, member))
    }

    @Test
    fun unchangedRanksKeepNoDelay() {
        val member = Member(CITIZEN)
        member.changeRank(CITIZEN)
        assertEquals(null, member.rankPrev)
    }

    @Test
    fun hereditaryHeirsNeedSevenDaysOfMembership() {
        val c = withLeadership(Leadership.HEREDITARY)
        c.members["young"] = Member(CITIZEN, since = now() - 5 * day)
        assertFalse(c.government.heirEligible(c.members.getValue("young")))
        assertTrue(c.government.heirEligible(c.members.getValue("a")))
        c.government.heir = "young"
        assertEquals("a", Upkeep.heir(c, "boss"))
    }

    @Test
    fun appointedHeirsMayBeNewcomers() {
        val c = withLeadership(Leadership.APPOINTED)
        c.members["young"] = Member(CITIZEN, since = now() - 5 * day)
        c.government.heir = "young"
        assertEquals("young", Upkeep.heir(c, "boss"))
    }

    @Test
    fun appointedSuccessionFallsBackToVotesThenSeniority() {
        val c = withLeadership(Leadership.APPOINTED)
        assertEquals("a", Upkeep.heir(c, "boss"))
        c.government.votes += mapOf("a" to "c", "b" to "c")
        assertEquals("c", Upkeep.heir(c, "boss"))
        c.government.heir = "b"
        assertEquals("b", Upkeep.heir(c, "boss"))
    }

    @Test
    fun payTiersScaleTheBasePayWithinTheJobPayCap() {
        assertEquals(60, JobRules.tierPay(80, PayTier.LOW))
        assertEquals(80, JobRules.tierPay(80, PayTier.STANDARD))
        assertEquals(100, JobRules.tierPay(80, PayTier.HIGH))
        assertEquals(Config.s.maxJobPay, JobRules.tierPay(Config.s.maxJobPay, PayTier.HIGH))
    }

    @Test
    fun onlyVassalOverlordsLevyTheirProvinces() {
        val parent = standard()
        val child = Country("Child")
        Realm.data.countries[child.id] = child
        parent.provinces += child.id
        parent.government.levers = parent.government.levers.copy(provinces = ProvinceRule.VASSAL)
        assertEquals(listOf(child), Joining.vassals(parent))
        parent.government.levers = parent.government.levers.copy(provinces = ProvinceRule.AUTONOMOUS)
        assertTrue(Joining.vassals(parent).isEmpty())
    }
}
