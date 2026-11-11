package kami.claims.gov

import kami.claims.Claimant
import kami.claims.Country
import kami.claims.Realm
import kami.claims.mixed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionsTest : GovTest() {
    @Test
    fun presidentHoldsEveryPermWithoutListingThem() {
        val c = standard()
        assertTrue(Perm.entries.all { Permissions.holds(c, "pres", it) })
        assertEquals("pres", Permissions.president(c))
    }

    @Test
    fun membersHoldOnlyWhatTheirRankLists() {
        val c = standard()
        assertTrue(Permissions.holds(c, "off", Perm.KICK))
        assertFalse(Permissions.holds(c, "off", Perm.CLAIM))
        assertFalse(Permissions.holds(c, "cit", Perm.KICK))
        assertTrue(Permissions.holds(c, "cit", Perm.DEPOSIT))
        assertEquals(listOf(Perm.WITHDRAW, Perm.WAR), Permissions.missing(c, "chan", listOf(Perm.KICK, Perm.WITHDRAW, Perm.WAR)))
    }

    @Test
    fun outranksIsStrictlyByLadderPosition() {
        val c = standard()
        assertTrue(Permissions.outranks(c, "pres", "chan"))
        assertTrue(Permissions.outranks(c, "chan", "cit"))
        assertFalse(Permissions.outranks(c, "off", "off"))
        assertFalse(Permissions.outranks(c, "cit", "off"))
        assertTrue(Permissions.outranks(c, "cit", "stranger"))
        assertFalse(Permissions.outranks(c, "stranger", "cit"))
    }

    @Test
    fun holdersAndPresidentHelpersReadTheMemberRanks() {
        val c = standard()
        assertEquals(1, Permissions.holders(c, "officer"))
        assertTrue(Permissions.isPresident(c, "pres"))
        assertFalse(Permissions.isPresident(c, "chan"))
        assertFalse(Permissions.isPresident(c, "stranger"))
    }

    @Test
    fun nonCitizensAreEveryoneButCitizens() {
        val c = standard()
        assertEquals(setOf("pres", "chan", "off"), Permissions.nonCitizens(c).toSet())
    }

    @Test
    fun outsidersFollowTheirGroupAndBanishedGetNothing() {
        val c = standard()
        c.groups[Claimant.ALLIED] = mutableSetOf(Perm.BREAK, Perm.DEPOSIT)
        c.groups[Claimant.RANDOM] = mutableSetOf(Perm.INTERACT)
        c.outsiders["friend"] = Standing.ALLIED
        c.outsiders["exile"] = Standing.BANISHED
        assertTrue(Permissions.holds(c, "friend", Perm.BREAK))
        assertFalse(Permissions.holds(c, "friend", Perm.INTERACT))
        assertTrue(Permissions.holds(c, "stranger", Perm.INTERACT))
        assertFalse(Permissions.holds(c, "stranger", Perm.BREAK))
        assertTrue(Perm.entries.none { Permissions.holds(c, "exile", it) })
    }

    @Test
    fun banishedStaysBlockedEvenWhenTheGroupGrantsEverything() {
        val c = standard()
        Claimant.entries.forEach { c.groups[it] = Perm.entries.toMutableSet() }
        c.outsiders["exile"] = Standing.BANISHED
        assertTrue(Permissions.outsiderPerms(c, "exile").isEmpty())
        assertFalse(Permissions.holds(c, "exile", Perm.DEPOSIT))
    }

    @Test
    fun autoAlliesUseTheFamilyGroupInsteadOfTheAlliedGroup() {
        val parent = standard()
        val child = Country("Kidland").mixed()
        Realm.data.countries[child.id] = child
        Realm.join(child, "kid", CITIZEN)
        child.parent = parent.id
        parent.provinces += child.id
        parent.outsiders["kid"] = Standing.ALLIED
        parent.autoAllies += "kid"
        parent.groups[Claimant.ALLIED] = mutableSetOf(Perm.DEPOSIT)
        parent.groups[Claimant.PROVINCE] = mutableSetOf(Perm.BREAK)
        assertEquals(setOf(Perm.BREAK), Permissions.outsiderPerms(parent, "kid"))
    }

    @Test
    fun unknownRankIdIsTheLowestPosition() {
        val c = standard()
        assertEquals(Int.MAX_VALUE, Permissions.position(c, "ghost"))
        assertEquals(0, Permissions.position(c, PRESIDENT))
        assertEquals(c.ranks.size - 1, Permissions.position(c, CITIZEN))
    }
}
