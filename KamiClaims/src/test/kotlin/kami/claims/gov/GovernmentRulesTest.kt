package kami.claims.gov

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GovernmentRulesTest : GovTest() {
    private val values = listOf("market", "private", "flat", "ruler", "appointed", "ladder", "direct", "open")

    @Test
    fun leversParseFromTheGivenOffsetIgnoringCase() {
        val levers = Levers.parse(listOf("ignored") + values.map { it.uppercase() }, 1)
        assertEquals(Levers(Labour.MARKET, Land.PRIVATE, TaxStyle.FLAT, TreasuryControl.RULER, Leadership.APPOINTED, RankStructure.LADDER, ProvinceRule.DIRECT, Membership.OPEN), levers)
    }

    @Test
    fun anUnknownLeverValueIsOneError() {
        assertTrue(failure { Levers.parse(values.dropLast(1) + "nope", 0) }.contains("gov_value"))
        assertTrue(failure { Levers.parse(values.take(5), 0) }.contains("gov_value"))
    }

    @Test
    fun flatStructureCapsRanksAtFour() {
        val c = standard()
        assertEquals(10, c.government.rankCap(10))
        c.government.levers = c.government.levers.copy(ranks = RankStructure.FLAT)
        assertEquals(FLAT_RANK_CAP, c.government.rankCap(10))
    }

    @Test
    fun switchingLeadershipClearsVotesAndAHeirThatNoLongerFits() {
        val c = standard()
        c.government.levers = c.government.levers.copy(leadership = Leadership.HEREDITARY)
        c.government.heir = "chan"
        c.government.votes["cit"] = "off"
        c.government.switch(c, PresetId.MIXED, c.government.levers.copy(leadership = Leadership.ELECTED), reset = false)
        assertEquals(Leadership.ELECTED, c.government.levers.leadership)
        assertTrue(c.government.votes.isEmpty())
        assertNull(c.government.heir)
    }

    @Test
    fun switchingOtherLeversKeepsVotesAndResetRestartsTheRanks() {
        val c = standard()
        c.government.votes["cit"] = "off"
        c.government.switch(c, PresetId.COMMUNE, c.government.levers.copy(labour = Labour.QUOTA), reset = true)
        assertEquals(1, c.government.votes.size)
        assertEquals(listOf(PRESIDENT, CITIZEN), c.ranks.map { it.id })
        assertEquals(CITIZEN, c.members.getValue("off").rank)
    }
}
