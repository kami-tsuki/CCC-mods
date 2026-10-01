package kami.claims

import kami.claims.research.Capacity
import kami.claims.research.Levels
import kami.claims.service.Fail
import kami.claims.service.Housing
import kami.claims.service.PlotBlock
import kami.claims.service.Provinces
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HousingTest {
    private val dim = "minecraft:overworld"

    @AfterTest
    fun restore() {
        Housing.pay = { _, _ -> true }
    }

    private fun country(name: String, treasury: Long = 0): Country {
        val c = Country(name, treasury = treasury)
        Realm.data.countries[c.id] = c
        Realm.join(c, "${name}_p", Rank.PRESIDENT)
        return c
    }

    private fun plot(c: Country, x: Int, z: Int = 0, type: String = "residential") = Claim(c.id, dim, x, z, type).also { Realm.add(it) }

    private fun failure(block: () -> Unit): String = try {
        block()
        ""
    } catch (e: Fail) {
        e.phrase.json()
    }

    @Test
    fun categoriesFollowRelationsAndLimitsAndAdjacencyApply() {
        Realm.reset(Data())
        val land = country("land").also { it.level = 30 }
        val parent = country("parentland")
        val child = country("childland")
        val sibling = country("siblingland")
        Realm.join(land, "cit", Rank.CITIZEN)
        Provinces.finalize(child, parent, TaxMode.FLAT, 0.0)
        Provinces.finalize(sibling, parent, TaxMode.FLAT, 0.0)
        land.alliances += child.id
        child.alliances += land.id
        land.outsiders["friend"] = Rank.ALLIED
        land.outsiders["exile"] = Rank.BANISHED
        land.autoAllies += "auto"
        land.outsiders["auto"] = Rank.ALLIED
        assertEquals(Claimant.CITIZEN, Housing.category(land, "land_p"))
        assertEquals(Claimant.ALLIED, Housing.category(land, "childland_p"))
        assertEquals(Claimant.ALLIED, Housing.category(land, "friend"))
        assertEquals(Claimant.RANDOM, Housing.category(land, "auto"))
        assertEquals(Claimant.RANDOM, Housing.category(land, "stranger"))
        assertNull(Housing.category(land, "exile"))
        assertEquals(Claimant.PARENT, Housing.category(child, "parentland_p"))
        assertEquals(Claimant.PROVINCE, Housing.category(parent, "childland_p"))
        assertEquals(Claimant.PROVINCE, Housing.category(child, "siblingland_p"))
        child.alliances += parent.id
        assertEquals(Claimant.PARENT, Housing.category(child, "parentland_p"))

        assertEquals(2, Housing.limit(land, "cit"))
        assertEquals(25, Housing.limit(land, "land_p"))
        assertEquals(1, Housing.limit(land, "stranger"))
        assertEquals(2, Housing.limit(land, "childland_p"))
        land.playerPlots["stranger"] = 100
        assertEquals(Levels.capacity(land, Capacity.PLOTS), Housing.limit(land, "stranger"))
        land.playerPlots["stranger"] = 3

        val a = plot(land, 0)
        val b = plot(land, 1)
        val far = plot(land, 5)
        val c = plot(land, 2)
        val civic = plot(land, 3, 1, "civic")
        assertTrue(failure { Housing.claim(land, civic, "cit") }.contains("plot_type"))
        Housing.claim(land, a, "cit")
        assertTrue(failure { Housing.claim(land, far, "cit") }.contains("plot_adjacent"))
        Housing.claim(land, b, "cit")
        assertTrue(failure { Housing.claim(land, c, "cit") }.contains("plot_limit"))
        assertEquals(PlotBlock.LIMIT, Housing.blocker(land, c, "cit"))

        assertTrue(failure { Housing.claim(land, far, "stranger") }.contains("plot_not_offered"))
        land.offer.open += Claimant.RANDOM
        Housing.claim(land, far, "stranger")
        assertEquals(Claimant.RANDOM, far.category)
        assertTrue(failure { Housing.claim(land, c, "stranger") }.contains("plot_adjacent"))
        assertTrue(failure { Housing.claim(land, c, "exile") }.isNotEmpty())
        assertTrue(failure { Housing.claim(land, far, "friend") }.contains("plot_taken"))
    }

    @Test
    fun rentDebtLeadsToMoveOutAndExpiryAndFullTreasuryPartiallyPays() {
        Realm.reset(Data())
        val land = country("land", 100).also { it.level = 30 }
        Realm.join(land, "tenant", Rank.CITIZEN)
        land.rentDebtLimit = 12
        land.moveOutDays = 1
        val cl = plot(land, 0)
        Housing.claim(land, cl, "tenant")
        Housing.pay = { _, _ -> false }
        Housing.collect(land)
        Housing.collect(land)
        assertEquals(10L, cl.rentDebt)
        assertEquals(Tenancy.ACTIVE, cl.state)
        Housing.collect(land)
        assertEquals(Tenancy.MOVING_OUT, cl.state)
        assertTrue(cl.until > now())
        assertTrue(Housing.tenanted(cl))
        Housing.collect(land)
        assertEquals(15L, cl.rentDebt)
        assertFalse(Housing.sweep())
        cl.until = now() - 1
        assertFalse(Housing.tenanted(cl))
        assertTrue(Housing.sweep())
        assertNull(cl.owner)
        assertEquals(0L, cl.rentDebt)
        assertEquals(Tenancy.ACTIVE, cl.state)

        val paid = ArrayList<Int>()
        Housing.pay = { _, n -> paid += n; true }
        land.reclaimLocks.clear()
        Housing.claim(land, cl, "tenant")
        land.treasury = Levels.capacity(land, Capacity.TREASURY) - 3L
        assertEquals(3L, Housing.collect(land))
        assertEquals(listOf(3), paid)
        assertEquals(0L, cl.rentDebt)
        assertEquals(Tenancy.ACTIVE, cl.state)
        assertEquals(0L, Housing.collect(land))

        cl.rentDebt = 4
        land.treasury = Levels.capacity(land, Capacity.TREASURY) - 8L
        paid.clear()
        assertEquals(8L, Housing.collect(land))
        assertEquals(listOf(8), paid)
        assertEquals(1L, cl.rentDebt)

        Realm.leave(land, "tenant", Leave.LEAVE)
        assertEquals(Tenancy.REMOVED, cl.state)
        assertEquals(Claimant.RANDOM, cl.category)
        assertEquals(Housing.rent(land, Claimant.RANDOM), Housing.rate(land, cl))
        land.offer.open += Claimant.RANDOM
        Housing.collect(land)
        assertEquals(Tenancy.ACTIVE, cl.state)
        Realm.join(land, "tenant", Rank.CITIZEN)
        Realm.leave(land, "tenant", Leave.KICK)
        assertEquals(Tenancy.MOVING_OUT, cl.state)
    }

    @Test
    fun leavingWhileMovingOutKeepsTheMoveOut() {
        Realm.reset(Data())
        val land = country("land", 100).also { it.level = 30 }
        Realm.join(land, "tenant", Rank.CITIZEN)
        val cl = plot(land, 0)
        Housing.claim(land, cl, "tenant")
        Housing.moveOut(land, cl, "debt")
        val until = cl.until
        Realm.leave(land, "tenant", Leave.LEAVE)
        assertEquals(Tenancy.MOVING_OUT, cl.state)
        assertEquals(until, cl.until)
    }

    @Test
    fun releaseNeedsNoDebtAndMoveOutsLockReclaiming() {
        Realm.reset(Data())
        val land = country("land", 100).also { it.level = 30 }
        Realm.join(land, "tenant", Rank.CITIZEN)
        val cl = plot(land, 0)
        Housing.claim(land, cl, "tenant")
        cl.rentDebt = 5
        assertTrue(failure { Housing.release(land, cl) }.contains("plot_debt"))
        assertEquals("tenant", cl.owner)
        Housing.moveOut(land, cl, "debt")
        assertTrue(land.reclaimLocks.getValue("tenant") > cl.until)
        Housing.release(land, cl)
        assertNull(cl.owner)
        assertEquals(PlotBlock.LOCKED, Housing.blocker(land, cl, "tenant"))
        assertTrue(failure { Housing.claim(land, cl, "tenant") }.contains("plot_locked"))
        land.reclaimLocks["tenant"] = now() - 1
        assertNull(Housing.blocker(land, cl, "tenant"))
        Housing.claim(land, cl, "tenant")
        assertEquals("tenant", cl.owner)
    }

    @Test
    fun disbandingDropsInvitesAndRequests() {
        Realm.reset(Data())
        val land = country("land")
        land.invites["a"] = now() + 1000
        land.requests["b"] = now() + 1000
        Realm.disband(land)
        assertTrue(land.invites.isEmpty() && land.requests.isEmpty())
        assertNull(Realm.live(land.id))
    }

    @Test
    fun disbandedCountryWithTenantsIsInactiveUntilTheLastMoveOutEnds() {
        Realm.reset(Data())
        val land = country("land", 50)
        Realm.join(land, "tenant", Rank.CITIZEN)
        val home = plot(land, 0)
        plot(land, 1)
        Housing.claim(land, home, "tenant")
        Realm.disband(land)
        assertFalse(land.active)
        assertNull(Realm.of("land_p"))
        assertNull(Realm.live(land.id))
        assertEquals(listOf(home), Realm.claims(land.id))
        assertEquals(Tenancy.MOVING_OUT, home.state)
        assertEquals(0L, Housing.collect(land))
        assertFalse(Housing.sweep())
        home.until = now() - 1
        assertTrue(Housing.sweep())
        assertFalse(Realm.data.countries.containsKey(land.id))
        assertTrue(Realm.data.claims.isEmpty())
    }

    @Test
    fun accessTableAndFluidBorders() {
        Realm.reset(Data())
        val land = country("land").also { it.level = 30 }
        val other = country("other")
        val a = plot(land, 0).also { it.owner = "owner"; it.roles["house"] = Role.HOUSEHOLD; it.roles["ally"] = Role.ALLIED }
        val a2 = plot(land, 1).also { it.owner = "owner" }
        val b = plot(land, 2).also { it.owner = "neighbour" }
        val free = plot(land, 3)
        val civic = plot(land, 4, 0, "civic")
        val foreign = plot(other, 9)
        Action.entries.forEach { assertTrue(Housing.plotAccess(a, "owner", it)) }
        Action.entries.forEach { assertTrue(Housing.plotAccess(a, "house", it)) }
        assertTrue(Housing.plotAccess(a, "ally", Action.INTERACT))
        assertFalse(Housing.plotAccess(a, "ally", Action.BREAK))
        assertFalse(Housing.plotAccess(a, "land_p", Action.BREAK))
        assertFalse(Housing.plotAccess(a, "house", Action.BREAK, banned = true))

        a.state = Tenancy.MOVING_OUT
        a.until = now() + 1000
        assertFalse(Housing.plotAccess(a, "owner", Action.PLACE))
        listOf(Action.BREAK, Action.INTERACT, Action.CONTAINER).forEach {
            assertTrue(Housing.plotAccess(a, "owner", it))
            assertTrue(Housing.plotAccess(a, "owner", it, banned = true))
            assertTrue(Housing.plotAccess(a, "house", it))
            assertFalse(Housing.plotAccess(a, "land_p", it))
            assertFalse(Housing.plotAccess(a, "ally", it))
        }
        assertFalse(Housing.plotAccess(a, "land_p", Action.PLACE))
        assertEquals("owner", a.tenant)
        a.until = now() - 1
        assertNull(a.tenant)

        assertTrue(Housing.fluidFlows(a2, a2, false))
        assertTrue(Housing.fluidFlows(a2, plot(land, 5).also { it.owner = "owner" }, false))
        assertFalse(Housing.fluidFlows(a2, b, false))
        assertFalse(Housing.fluidFlows(b, a2, false))
        assertFalse(Housing.fluidFlows(a2, civic, false))
        assertTrue(Housing.fluidFlows(free, plot(land, 6), false))
        assertTrue(Housing.fluidFlows(civic, plot(land, 7, 0, "civic"), false))
        assertFalse(Housing.fluidFlows(civic, foreign, true))
        assertFalse(Housing.fluidFlows(null, civic, true))
        assertFalse(Housing.fluidFlows(civic, null, true))
        assertTrue(Housing.fluidFlows(null, null, true))
        assertFalse(Housing.fluidFlows(null, null, false))
    }
}
