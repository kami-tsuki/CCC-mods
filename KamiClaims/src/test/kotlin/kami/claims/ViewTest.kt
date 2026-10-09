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
        assertEquals("", d.type)
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
        assertFalse(Sync.stranger(c, cl, "stranger").taken)
        assertEquals(0, View.flags(cl, c, "stranger"))
        assertTrue(has(View.flags(cl, c, "boss"), View.TAKEN))
    }

    @Test
    fun enemyGetsOnlyCountryName() {
        val (c, cl) = land()
        c.offer.open += Claimant.RANDOM
        val d = Sync.enemy(c, cl)
        assertEquals(c.name, d.country)
        assertEquals("", d.type)
        assertFalse(d.taken)
        assertNull(d.offer)
        assertTrue(d.access.isEmpty())
    }

    @Test
    fun powerOnlyForMembersAndAdmins() {
        val (c, _) = land()
        assertNull(Sync.power(c, "stranger", false))
        assertNotNull(Sync.power(c, "cit", false))
        assertNotNull(Sync.power(c, "stranger", true))
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
    fun signatureDiffersPerViewerAndSurvivesRevBumpWithoutChange() {
        val (_, cl) = land()
        cl.owner = "cit"
        cl.category = Claimant.CITIZEN
        val tenant = View.signature(View.build("cit"))
        val stranger = View.signature(View.build("stranger"))
        assertTrue(tenant != stranger)
        assertEquals(tenant, View.signature(View.build("cit")))
        Realm.changed()
        val rebuilt = View.build("cit")
        assertEquals(tenant, View.signature(rebuilt))
        assertEquals(Realm.claimRev, rebuilt.rev)
    }

    @Test
    fun signatureChangesWhenAClaimIsAdded() {
        val (c, _) = land()
        val before = View.signature(View.build("boss"))
        Realm.add(Claim(c.id, dim, 1, 0, "residential"))
        assertTrue(before != View.signature(View.build("boss")))
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

    private fun two(): Triple<Country, Country, Claim> {
        val (a, cl) = land()
        val b = Country("Other").also { it.level = 30; Realm.data.countries[it.id] = it }
        Realm.join(b, "bob", Rank.PRESIDENT)
        val far = Claim(b.id, dim, 5, 5, "residential").also { Realm.add(it) }
        return Triple(a, b, far).also { cl.capital = true }
    }

    private fun entry(p: View.Payload, name: String, x: Int, z: Int) = p.entries.first { p.countries[it.country].name == name && it.x == x && it.z == z }

    @Test
    fun alliedOutsiderGetsNoTypeOrCapital() {
        val (a, _, _) = two()
        a.outsiders["bob"] = Rank.ALLIED
        val e = entry(View.build("bob"), a.name, 0, 0)
        assertTrue(has(e.flags, View.ALLY))
        assertFalse(has(e.flags, View.CAPITAL))
        assertEquals(-1, e.type)
    }

    @Test
    fun overlordSeesProvinceCapitalAndType() {
        val (a, b, far) = two()
        far.capital = true
        b.parent = a.id
        a.provinces += b.id
        val e = entry(View.build("boss"), b.name, 5, 5)
        assertTrue(has(e.flags, View.CAPITAL))
        assertTrue(e.type >= 0)
        val back = entry(View.build("bob"), a.name, 0, 0)
        assertFalse(has(back.flags, View.CAPITAL))
        assertEquals(-1, back.type)
    }

    @Test
    fun enemyEntryCarriesOnlyEnemyBit() {
        val (a, b, _) = two()
        Realm.data.wars += kami.claims.war.War("w", kami.claims.war.WarKind.REGULAR, kami.claims.war.WarSide(a.id), kami.claims.war.WarSide(b.id), 0L)
        val p = View.build("boss")
        val e = entry(p, b.name, 5, 5)
        assertEquals(View.ENEMY, e.flags)
        assertEquals(-1, e.type)
        assertEquals(View.REL_ENEMY, p.countries[e.country].relation)
        val op = entry(View.build("boss", true), b.name, 5, 5)
        assertFalse(has(op.flags, View.ENEMY))
    }

    @Test
    fun reservedListIsScoped() {
        val (a, b, _) = two()
        Realm.data.reserves += Reserve(dim, 9, 9, a.id, now() + 100_000)
        Realm.data.reserves += Reserve(dim, 8, 8, b.id, now() + 100_000)
        Realm.changed()
        val p = View.build("boss")
        assertEquals(listOf(9), p.reserved.map { it.x })
        assertEquals(2, View.build("nobody", true).reserved.size)
        assertTrue(View.build("nobody").reserved.isEmpty())
    }

    @Test
    fun safeOwnAndStakedParticipantsOnly() {
        val (a, b, far) = two()
        val cl = Realm.index[Key(dim, 0, 0)]!!
        a.safeChunks += kami.claims.war.ChunkRef(dim, 0, 0)
        val stakes = kami.claims.war.Stakes(chunks = listOf(kami.claims.war.ChunkRef(dim, 5, 5)))
        val third = Country("Third").also { Realm.data.countries[it.id] = it }
        Realm.join(third, "tom", Rank.PRESIDENT)
        Realm.data.wars += kami.claims.war.War("w", kami.claims.war.WarKind.REGULAR, kami.claims.war.WarSide(a.id), kami.claims.war.WarSide(b.id), 0L, stakes = stakes)
        Realm.changed()
        assertTrue(has(entry(View.build("boss"), a.name, cl.x, cl.z).flags, View.SAFE))
        assertFalse(has(entry(View.build("bob"), a.name, cl.x, cl.z).flags, View.SAFE))
        assertTrue(has(entry(View.build("bob"), b.name, far.x, far.z).flags, View.STAKED))
        assertTrue(has(entry(View.build("boss"), b.name, far.x, far.z).flags, View.STAKED))
        assertFalse(has(entry(View.build("tom"), b.name, far.x, far.z).flags, View.STAKED))
    }

    @Test
    fun warBitsExemptOpsAndNonParticipants() {
        val (a, b, _) = two()
        Realm.data.wars += kami.claims.war.War("w", kami.claims.war.WarKind.REGULAR, kami.claims.war.WarSide(a.id), kami.claims.war.WarSide(b.id), 0L)
        assertEquals(View.WAR_ACTIVE or View.WAR_FAIRPLAY, View.build("boss").war)
        assertEquals(0, View.build("nobody").war)
        val noFair = Config.s
        Config.s = noFair.copy(visibility = noFair.visibility.copy(fairPlay = false))
        try {
            assertEquals(View.WAR_ACTIVE, View.build("boss").war)
        } finally {
            Config.s = noFair
        }
        Realm.data.wars.clear()
        assertEquals(0, View.build("boss").war)
    }
}
