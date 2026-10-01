package kami.claims

import kami.claims.net.Sync
import kami.claims.service.View
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViewTest {
    private val dim = "minecraft:overworld"

    private fun land(): Pair<Country, Claim> {
        Realm.reset(Data())
        val c = Country("Land").also { it.level = 30; it.treasury = 999 }
        Realm.data.countries[c.id] = c
        Realm.join(c, "boss", Rank.PRESIDENT)
        Realm.join(c, "cit", Rank.CITIZEN)
        return c to Claim(c.id, dim, 0, 0, "residential").also { Realm.add(it) }
    }

    private fun has(flags: Int, bit: Int) = flags and bit != 0

    @Test
    fun strangerSeesOfferWithoutPrivateData() {
        val (c, cl) = land()
        c.offer.open += Claimant.RANDOM
        cl.debt = 3
        assertTrue(has(View.flags(cl, c, "stranger"), View.OFFER))
        val d = Sync.stranger(c, cl, "stranger")
        assertEquals("residential", d.type)
        assertEquals("", d.owner)
        assertTrue(d.roles.isEmpty())
        assertEquals(0, d.debt)
        assertFalse(d.free)
        assertNull(d.mine)
        assertEquals(0, d.price)
        val offer = assertNotNull(d.offer)
        assertEquals("random", offer.category)
        assertEquals("", offer.lock)
        assertEquals(c.offer.rent[Claimant.RANDOM] ?: 0, offer.price)
        c.offer.open -= Claimant.RANDOM
        assertEquals("offer", Sync.stranger(c, cl, "stranger").offer?.lock)
        assertFalse(has(View.flags(cl, c, "stranger"), View.OFFER))
        cl.owner = "cit"
        cl.category = Claimant.CITIZEN
        assertNull(Sync.stranger(c, cl, "stranger").offer)
        assertTrue(Sync.stranger(c, cl, "stranger").taken)
        assertEquals(View.TAKEN, View.flags(cl, c, "stranger"))
    }

    @Test
    fun strangerSeesNothingOnOtherTypes() {
        val (c, _) = land()
        val farm = Claim(c.id, dim, 1, 0, "farm").also { Realm.add(it) }
        val d = Sync.stranger(c, farm, "stranger")
        assertEquals("", d.type)
        assertNull(d.offer)
    }

    @Test
    fun foreignTenantGetsMineAndMoving() {
        val (c, cl) = land()
        c.offer.open += Claimant.RANDOM
        cl.owner = "tenant"
        cl.category = Claimant.RANDOM
        val f = View.flags(cl, c, "tenant")
        assertTrue(has(f, View.MINE))
        assertFalse(has(f, View.MOVING))
        assertFalse(has(f, View.OFFER))
        cl.state = Tenancy.MOVING_OUT
        cl.until = now() + 100_000
        assertTrue(has(View.flags(cl, c, "tenant"), View.MOVING))
        assertFalse(has(View.flags(cl, c, "other"), View.MINE))
    }

    @Test
    fun assignedNeedsTheMatchingJob() {
        val (c, _) = land()
        val pit = Claim(c.id, dim, 1, 0, "mining").also { Realm.add(it) }
        pit.workers += "cit"
        assertFalse(has(View.flags(pit, c, "cit"), View.ASSIGNED))
        c.members.getValue("cit").jobs["miner"] = Job()
        assertTrue(has(View.flags(pit, c, "cit"), View.ASSIGNED))
        assertFalse(has(View.flags(pit, c, "boss"), View.ASSIGNED))
        c.members.getValue("cit").jobs.clear()
        assertFalse(has(View.flags(pit, c, "cit"), View.ASSIGNED))
    }
}
