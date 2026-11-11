package kami.claims.gov

import kami.claims.research.Capacity
import kami.claims.research.Levels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RankRulesTest : GovTest() {
    @Test
    fun actorsEditOnlyRanksBelowTheirOwn() {
        val c = standard()
        assertSame(Permissions.rank(c, "officer"), RankRules.editable(c, "chan", "officer"))
        assertTrue(failure { RankRules.editable(c, "chan", "chancellor") }.contains("rank_above"))
        assertTrue(failure { RankRules.editable(c, "off", "chancellor") }.contains("rank_above"))
        assertTrue(failure { RankRules.editable(c, "chan", PRESIDENT) }.contains("rank_fixed"))
        assertTrue(failure { RankRules.editable(c, "chan", "ghost") }.contains("rank_unknown"))
    }

    @Test
    fun cannotGrantWhatTheActorLacks() {
        val c = standard()
        RankRules.grantable(c, "chan", setOf(Perm.KICK, Perm.CLAIM))
        assertTrue(failure { RankRules.grantable(c, "chan", setOf(Perm.KICK, Perm.WITHDRAW)) }.contains("rank_grant"))
        RankRules.grantable(c, "pres", Perm.entries.toSet())
    }

    @Test
    fun citizenIsPinnedLastAndUndeletable() {
        val c = standard()
        val citizen = Permissions.rank(c, CITIZEN)!!
        val officer = Permissions.rank(c, "officer")!!
        assertTrue(failure { RankRules.deletable(c, "pres", citizen, officer) }.contains("rank_citizen"))
        assertTrue(failure { RankRules.moved(c, "pres", citizen, up = true) }.contains("rank_move"))
        assertTrue(failure { RankRules.moved(c, "pres", officer, up = false) }.contains("rank_move"))
    }

    @Test
    fun movingStaysBelowTheActorsOwnPosition() {
        val c = standard()
        val officer = Permissions.rank(c, "officer")!!
        assertTrue(failure { RankRules.moved(c, "chan", officer, up = true) }.contains("rank_move"))
        assertEquals(1, RankRules.moved(c, "pres", officer, up = true))
    }

    @Test
    fun deletingNeedsADistinctTargetBelowTheActor() {
        val c = standard()
        val officer = Permissions.rank(c, "officer")!!
        val chancellor = Permissions.rank(c, "chancellor")!!
        assertTrue(failure { RankRules.deletable(c, "pres", officer, officer) }.contains("rank_target"))
        assertTrue(failure { RankRules.deletable(c, "chan", officer, chancellor) }.contains("rank_above"))
        RankRules.deletable(c, "chan", officer, Permissions.rank(c, CITIZEN)!!)
    }

    @Test
    fun rankCountIsCappedByCapacityAndNewRanksSitAboveCitizen() {
        val c = country(mapOf("pres" to PRESIDENT, "cit" to CITIZEN))
        c.level = 50
        val capacity = Levels.capacity(c, Capacity.RANKS)
        assertTrue(capacity > 2)
        assertEquals(1, RankRules.creatable(c, "pres"))
        assertTrue(failure { RankRules.creatable(c, "cit") }.contains("rank_above"))
        while (c.ranks.size < capacity) c.ranks.add(1, rankWith("extra${c.ranks.size}"))
        assertTrue(failure { RankRules.creatable(c, "pres") }.contains("rank_cap"))
        c.ranks.removeAt(1)
        assertEquals(c.ranks.size - 1, RankRules.creatable(c, "pres"))
    }

    @Test
    fun flatStructureStopsNewRanksAtTheFlatCap() {
        val c = country(mapOf("pres" to PRESIDENT, "cit" to CITIZEN))
        c.level = 50
        c.government.levers = c.government.levers.copy(ranks = RankStructure.FLAT)
        assertEquals(FLAT_RANK_CAP, c.government.rankCap(10))
        c.ranks.add(1, rankWith("a"))
        c.ranks.add(1, rankWith("b"))
        assertEquals(FLAT_RANK_CAP, c.ranks.size)
        assertTrue(failure { RankRules.room(c) }.contains("rank_cap"))
    }

    @Test
    fun deletingNeedsTheSuccessorsRiskyPermsEvenBelowTheRank() {
        val c = standard()
        c.ranks.add(3, rankWith("clerk", Perm.WITHDRAW))
        val officer = Permissions.rank(c, "officer")!!
        assertTrue(failure { RankRules.deletable(c, "chan", officer, Permissions.rank(c, "clerk")!!) }.contains("rank_grant"))
    }

    @Test
    fun onlyTwoRanksMayHoldCriticalPerms() {
        val c = country(mapOf("pres" to PRESIDENT, "cit" to CITIZEN))
        val a = rankWith("a", Perm.WITHDRAW)
        val b = rankWith("b", Perm.CAPITAL)
        c.ranks.add(1, a)
        c.ranks.add(1, b)
        val fresh = rankWith("fresh")
        RankRules.criticalCap(c, fresh, setOf(Perm.KICK))
        assertTrue(failure { RankRules.criticalCap(c, fresh, setOf(Perm.WAR)) }.contains("rank_critical_cap"))
        RankRules.criticalCap(c, a, setOf(Perm.WAR))
    }

    @Test
    fun theLastAssignRanksHolderCannotLoseItWhileOccupied() {
        val c = standard()
        val chancellor = Permissions.rank(c, "chancellor")!!
        assertTrue(failure { RankRules.lockout(c, chancellor, chancellor.perms - Perm.ASSIGN_RANKS) }.contains("rank_lockout"))
        RankRules.lockout(c, chancellor, chancellor.perms)
        c.ranks.add(2, rankWith("deputy", Perm.ASSIGN_RANKS))
        RankRules.lockout(c, chancellor, chancellor.perms - Perm.ASSIGN_RANKS)
    }

    @Test
    fun namesAreValidatedForShapeAndUniquenessIgnoringCaseAndAccents() {
        val c = standard()
        RankRules.name(c, "Vice Chair")
        assertEquals("Padded", RankRules.name(c, " Padded "))
        listOf("ab", "x".repeat(25), "Bad!Name", "   ").forEach { assertTrue(failure { RankRules.name(c, it) }.contains("error.name"), it) }
        assertTrue(failure { RankRules.name(c, "OFFICER") }.contains("rank_name_taken"))
        assertTrue(failure { RankRules.name(c, "Cítizen") }.contains("rank_name_taken"))
        RankRules.name(c, "Officer", except = Permissions.rank(c, "officer"))
    }

    @Test
    fun assigningNeedsEveryPermOfTheDestinationRank() {
        val c = standard()
        c.ranks.add(3, rankWith("clerk", Perm.BREAK, Perm.BYPASS_RULES))
        assertTrue(failure { RankRules.assignable(c, "chan", "cit", Permissions.rank(c, "clerk")!!) }.contains("rank_grant"))
    }

    @Test
    fun deletingIntoAHigherSuccessorNeedsItsPerms() {
        val c = standard()
        c.ranks.add(2, rankWith("envoy", Perm.WAR))
        val officer = Permissions.rank(c, "officer")!!
        assertTrue(failure { RankRules.deletable(c, "chan", officer, Permissions.rank(c, "envoy")!!) }.contains("rank_grant"))
        assertTrue(failure { RankRules.deletable(c, "pres", officer, Permissions.rank(c, "envoy")!!) }.isEmpty())
    }

    @Test
    fun promotingHoldersThroughDeletionNeedsAssignRanks() {
        val c = country(mapOf("pres" to PRESIDENT, "boss" to "boss", "low" to "junior"), rankWith("boss", Perm.KICK), rankWith("senior", Perm.KICK), rankWith("junior"))
        val junior = Permissions.rank(c, "junior")!!
        val senior = Permissions.rank(c, "senior")!!
        assertTrue(failure { RankRules.deletable(c, "boss", junior, senior) }.contains("rank_grant"))
        RankRules.deletable(c, "pres", junior, senior)
        c.members.remove("low")
        RankRules.deletable(c, "boss", junior, senior)
    }

    @Test
    fun groupPermsRejectWithdraw() {
        assertTrue(failure { RankRules.groupPerms("WITHDRAW") }.contains("group_perm"))
        assertEquals(setOf(Perm.BREAK), RankRules.groupPerms("BREAK"))
    }

    @Test
    fun assigningNeedsBothTheCurrentAndTheDestinationRankBelowTheActor() {
        val c = standard()
        val citizen = Permissions.rank(c, CITIZEN)!!
        val officer = Permissions.rank(c, "officer")!!
        RankRules.assignable(c, "chan", "off", citizen)
        RankRules.assignable(c, "chan", "cit", officer)
        assertTrue(failure { RankRules.assignable(c, "off", "off", citizen) }.contains("rank_above"))
        assertTrue(failure { RankRules.assignable(c, "chan", "cit", Permissions.rank(c, "chancellor")!!) }.contains("rank_above"))
        assertTrue(failure { RankRules.assignable(c, "chan", "stranger", citizen) }.contains("not_member"))
    }
}
