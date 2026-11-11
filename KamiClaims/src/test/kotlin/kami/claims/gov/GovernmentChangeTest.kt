package kami.claims.gov

import kami.claims.Config
import kami.claims.Country
import kami.claims.Member
import kami.claims.Realm
import kami.claims.now
import kami.claims.service.Upkeep
import kami.claims.war.War
import kami.claims.war.WarKind
import kami.claims.war.WarSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GovernmentChangeTest : GovTest() {
    private val day get() = Config.s.dayMillis

    private fun change(c: Country, levers: Levers = c.government.levers, reset: Boolean = false) = failure { GovernmentActions.requireChangeable(c, levers, reset) }

    @Test
    fun aQuietCountryMayChange() {
        assertEquals("", change(standard()))
    }

    @Test
    fun warBlocksChanges() {
        val c = standard()
        Realm.data.wars += War("w", WarKind.REGULAR, WarSide(c.id), WarSide("foe"), 0L)
        assertTrue(change(c).contains("gov_war"))
    }

    @Test
    fun provinceDebtBlocksChanges() {
        val c = standard()
        c.provinceDebt = 1
        assertTrue(change(c).contains("gov_default"))
    }

    @Test
    fun changesWaitSevenDays() {
        val c = standard()
        c.government.changedAt = now() - 6 * day
        assertTrue(change(c).contains("gov_cooldown"))
        c.government.changedAt = now() - 8 * day
        assertEquals("", change(c))
    }

    @Test
    fun flatRanksNeedAtMostFourRanksUnlessTheyAreReset() {
        val c = standard()
        c.ranks.add(1, rankWith("extra"))
        val flat = c.government.levers.copy(ranks = RankStructure.FLAT)
        assertTrue(change(c, flat).contains("gov_flat_ranks"))
        assertEquals("", change(c, flat, reset = true))
    }

    @Test
    fun councilTreasuryNeedsTwoMembers() {
        val c = country(mapOf("pres" to PRESIDENT))
        val council = c.government.levers.copy(treasury = TreasuryControl.COUNCIL)
        assertTrue(change(c, council).contains("gov_council_members"))
        c.members["cit"] = Member(CITIZEN)
        assertEquals("", change(c, council))
    }

    @Test
    fun leaderChangeClearsHeirVotesAndCouncil() {
        val c = standard()
        c.treasury = 100_000
        c.government.heir = "chan"
        c.government.votes["cit"] = "off"
        Council.request(c, "pres", 50_000)
        c.leaderChanged()
        assertNull(c.government.heir)
        assertTrue(c.government.votes.isEmpty())
        assertTrue(Council.pending(c).isEmpty())
    }

    @Test
    fun auditKeepsTheNewestEntries() {
        val c = standard()
        repeat(Audit.LIMIT + 5) { Audit.record(c, "pres", "action$it") }
        assertEquals(Audit.LIMIT, c.audit.size)
        assertEquals("action5", c.audit.first().action)
    }

    private fun electorate(leadership: Leadership): Country {
        val c = country(mapOf("boss" to PRESIDENT))
        c.government.levers = c.government.levers.copy(leadership = leadership)
        listOf("a", "b", "c").forEachIndexed { i, id -> c.members[id] = Member(CITIZEN, since = now() - (10 - i) * day) }
        return c
    }

    @Test
    fun aMajorityOfTenuredVotesWinsTheElection() {
        val c = electorate(Leadership.ELECTED)
        c.government.votes += mapOf("a" to "c", "b" to "c", "c" to "c")
        assertEquals("c", Upkeep.heir(c, "boss"))
    }

    @Test
    fun withoutAMajoritySeniorityDecides() {
        val c = electorate(Leadership.ELECTED)
        c.government.votes += mapOf("b" to "c")
        assertEquals("a", Upkeep.heir(c, "boss"))
    }

    @Test
    fun votesFromMembersUnderThreeDaysAreIgnored() {
        val c = electorate(Leadership.ELECTED)
        c.members["new"] = Member(CITIZEN, since = now() - day)
        c.government.votes += mapOf("a" to "c", "new" to "c")
        assertEquals("a", Upkeep.heir(c, "boss"))
    }

    @Test
    fun aStaleHereditaryHeirFallsBackToSeniority() {
        val c = electorate(Leadership.HEREDITARY)
        c.government.heir = "c"
        assertEquals("c", Upkeep.heir(c, "boss"))
        c.government.heir = "ghost"
        assertEquals("a", Upkeep.heir(c, "boss"))
    }
}
