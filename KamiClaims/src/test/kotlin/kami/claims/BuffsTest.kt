package kami.claims

import kami.claims.research.*
import kami.claims.service.Fail
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuffsTest {
    private val dim = "minecraft:overworld"

    @AfterTest
    fun reset() {
        Research.defs = ResearchDefs.EMPTY
    }

    private fun country(vararg done: String): Country {
        val built = Validator.build(ResearchSettings(), LevelsConfig(), Defaults.groups, Defaults.trees)
        assertEquals(emptyList(), built.problems.filter { it.contains("buffs") })
        Research.defs = built.defs
        Realm.reset(Data())
        val c = Country("buffy")
        Realm.data.countries[c.id] = c
        done.forEach { c.research.done["buffs:$it"] = 0 }
        return c
    }

    private fun grid(c: Country, size: Int) = (0 until size * size).forEach { i ->
        Realm.add(Claim(c.id, dim, i % size, i / size, "civic", at = i.toLong(), since = 0))
    }

    @Test
    fun pointsAreSpentAndReturned() {
        val c = country("buffs_view", "buff_points_1", "instant_health_1", "speed_1", "speed_2")
        assertEquals(2, Buffs.points(c))
        Buffs.toggle(c, "buffs:speed_2")
        assertEquals(2, Buffs.used(c))
        assertFailsWith<Fail> { Buffs.toggle(c, "buffs:instant_health_1") }
        Buffs.toggle(c, "buffs:speed_2")
        Buffs.toggle(c, "buffs:instant_health_1")
        Buffs.toggle(c, "buffs:speed_1")
        assertEquals(setOf("buffs:instant_health_1", "buffs:speed_1"), c.buffs.enabled)
        c.research.done.remove("buffs:speed_1")
        Buffs.prune(c)
        assertEquals(setOf("buffs:instant_health_1"), c.buffs.enabled)
    }

    @Test
    fun borderChunksTouchForeignOrEmptyLand() {
        val c = country("buffs_view")
        grid(c, 3)
        val border = Buffs.borderChunks(c)
        assertEquals(8, border.size)
        assertFalse(Key(dim, 1, 1) in border)
        Realm.add(Claim(c.id, dim, 3, 1, "civic", at = 9, since = 0))
        assertFalse(Key(dim, 2, 1) in Buffs.borderChunks(c))
        assertTrue(Key(dim, 3, 1) in Buffs.borderChunks(c))
    }

    @Test
    fun surchargeAddsEnabledTaxToBorderPrice() {
        val c = country("buffs_view", "buff_points_1", "buff_points_2", "buff_points_3", "instant_health_1", "speed_2")
        grid(c, 3)
        Buffs.toggle(c, "buffs:instant_health_1")
        Buffs.toggle(c, "buffs:speed_2")
        val base = Realm.price(Realm.at(dim, 1, 1)!!)
        assertEquals(3, Buffs.tax(c))
        assertEquals(base, Buffs.price(c, Realm.at(dim, 1, 1)!!))
        assertEquals(base + 3, Buffs.price(c, Realm.at(dim, 0, 0)!!))
    }

    @Test
    fun pulseFiresOnEntryOnceInCooldown() {
        val tracker = PulseTracker()
        val player = UUID.randomUUID()
        assertFalse(tracker.entered(player, true))
        assertFalse(tracker.entered(player, true))
        assertFalse(tracker.entered(player, false))
        assertTrue(tracker.entered(player, true))
        assertTrue(tracker.ready(player, "buffs:instant_health_1", 1_000, 300_000))
        assertFalse(tracker.ready(player, "buffs:instant_health_1", 200_000, 300_000))
        assertTrue(tracker.ready(player, "buffs:instant_health_1", 301_000, 300_000))
        tracker.forget(player)
        assertFalse(tracker.ready(player, "buffs:instant_health_1", 302_000, 300_000))
        tracker.prune(700_000)
        assertTrue(tracker.ready(player, "buffs:instant_health_1", 700_001, 300_000))
    }
}
