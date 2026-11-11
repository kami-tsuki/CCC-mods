package kami.claims.gov

import kami.claims.Claimant
import kami.claims.Country
import kami.claims.Member
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WarningsTest : GovTest() {
    private fun rank(c: Country, id: String) = Permissions.rank(c, id)!!

    private fun severities(list: List<Warning>) = list.associate { it.key to it.severity }

    @Test
    fun eachRiskyGrantRaisesItsOwnTierOfWarning() {
        val c = standard()
        val officer = rank(c, "officer")
        val expected = mapOf(
            Perm.EDIT_RANKS to ("manage_ranks" to WarnTier.CRITICAL),
            Perm.CREATE_RANKS to ("manage_ranks" to WarnTier.CRITICAL),
            Perm.WITHDRAW to ("withdraw" to WarnTier.CRITICAL),
            Perm.CAPITAL to ("territory" to WarnTier.CRITICAL),
            Perm.WAR to ("territory" to WarnTier.CRITICAL),
            Perm.BYPASS_RULES to ("bypass" to WarnTier.CRITICAL),
            Perm.BANISH to ("remove_citizens" to WarnTier.DANGER),
            Perm.UNCLAIM to ("give_up_land" to WarnTier.DANGER)
        )
        expected.forEach { (perm, warning) ->
            assertEquals(warning.second, severities(Warnings.of(c, officer, setOf(perm), emptySet()))[warning.first], perm.name)
        }
    }

    @Test
    fun harmlessGrantsAndUnchangedStateStaySilent() {
        val c = standard()
        val officer = rank(c, "officer")
        assertTrue(Warnings.of(c, officer, setOf(Perm.TREASURY_VIEW, Perm.VIEW_JOBS), emptySet()).isEmpty())
        assertTrue(Warnings.of(c, officer, emptySet(), emptySet()).isEmpty())
        assertTrue(Warnings.of(c, rank(c, "chancellor"), emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun withdrawCrowdCountsHoldersAndWarningsComeWorstFirst() {
        val c = standard()
        repeat(4) { c.members["extra$it"] = Member("officer") }
        val warnings = Warnings.of(c, rank(c, "officer"), setOf(Perm.WITHDRAW, Perm.KICK), emptySet())
        assertTrue(warnings.any { it.key == "withdraw_crowd" })
        assertEquals(warnings.sortedByDescending { it.severity }.map { it.key }, warnings.map { it.key })
        assertEquals(WarnTier.CRITICAL, warnings.first().severity)
    }

    @Test
    fun selfEscalationNeedsBothRankPowersAfterTheChange() {
        val c = standard()
        val officer = rank(c, "officer")
        assertNull(Warnings.of(c, officer, setOf(Perm.EDIT_RANKS), emptySet()).firstOrNull { it.key == "self_escalation" })
        officer.perms += Perm.EDIT_RANKS
        assertNotNull(Warnings.of(c, officer, setOf(Perm.ASSIGN_RANKS), emptySet()).firstOrNull { it.key == "self_escalation" })
        assertNull(Warnings.of(c, officer, setOf(Perm.INVITE), emptySet()).firstOrNull { it.key == "self_escalation" })
    }

    @Test
    fun outsiderGroupsWarnAboutHarmfulUseAndAlliesAboutTreasuryAndPeople() {
        val c = standard()
        val harm = Warnings.of(c, null, setOf(Perm.BREAK), emptySet(), Claimant.RANDOM)
        assertEquals(WarnTier.DANGER, severities(harm)["outsider_harm"])
        assertNull(severities(harm)["allied_access"])
        val allied = severities(Warnings.of(c, null, setOf(Perm.DEPOSIT), emptySet(), Claimant.ALLIED))
        assertEquals(WarnTier.DANGER, allied["allied_access"])
        assertTrue(Warnings.of(c, null, setOf(Perm.BREAK), emptySet()).none { it.key == "outsider_harm" })
    }

    @Test
    fun defaultRankWarnsByTheRiskOfWhatItGains() {
        val c = standard()
        val citizen = rank(c, CITIZEN)
        assertEquals(WarnTier.DANGER, severities(Warnings.of(c, citizen, setOf(Perm.KICK), emptySet()))["default_rank"])
        assertEquals(WarnTier.CRITICAL, severities(Warnings.of(c, citizen, setOf(Perm.WITHDRAW), emptySet()))["default_rank"])
        assertNull(severities(Warnings.of(c, citizen, setOf(Perm.INVITE), emptySet()))["default_rank"])
        assertNull(severities(Warnings.of(c, rank(c, "officer"), setOf(Perm.KICK), emptySet()))["default_rank"])
    }

    @Test
    fun removalWarnsAboutLostPowerAndTheLastPromoter() {
        val c = standard()
        val chancellor = rank(c, "chancellor")
        val lost = Warnings.of(c, chancellor, emptySet(), setOf(Perm.ASSIGN_RANKS))
        assertEquals(WarnTier.CAUTION, severities(lost)["only_president_promotes"])
        c.ranks.add(2, rankWith("deputy", Perm.ASSIGN_RANKS))
        assertNull(severities(Warnings.of(c, chancellor, emptySet(), setOf(Perm.ASSIGN_RANKS)))["only_president_promotes"])
        assertNull(severities(Warnings.of(c, rank(c, "deputy"), emptySet(), setOf(Perm.ASSIGN_RANKS)))["lost_power"])
    }
}
