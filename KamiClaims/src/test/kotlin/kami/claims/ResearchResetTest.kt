package kami.claims

import kami.claims.gov.PRESIDENT
import kami.claims.research.*
import kami.libs.config.Configs
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResearchResetTest : WithDb() {
    private fun node(id: String, extra: String = "") = """{"id":"$id","category":"c","time":"10m","cost":100$extra}"""

    @BeforeTest
    fun setup() {
        val tree = Configs.json().decodeFromString(
            TreeFile.serializer(),
            """{"scope":"country","categories":[{"id":"c"}],"nodes":[${node("a", ""","unlocks":[{"type":"capacity","key":"chunks","add":5}]""")},${node("b")},${node("hi", ""","level":60""")}]}"""
        )
        Research.defs = Validator.build(ResearchSettings(baseline = emptyList()), LevelsConfig(xpFromLevel = 0), mapOf("g" to Group()), mapOf("t" to tree)).defs
        Realm.reset(Data())
    }

    @AfterTest
    fun cleanup() {
        Research.defs = ResearchDefs.EMPTY
    }

    private fun country(): Country {
        val c = Country("alpha", treasury = 500, xp = 40, level = 3, rewardedLevel = 3)
        c.tokens["rename"] = 2
        c.research.done["t:a"] = 1
        c.research.queue += QueueEntry("t:b", paid = true, paidCost = 100)
        c.research.parked += QueueEntry("gone")
        c.buffs.enabled += "t:a"
        Realm.data.countries[c.id] = c
        c.mixed()
        Realm.join(c, "alpha_p", PRESIDENT)
        return c
    }

    @Test
    fun resetClearsResearchAndKeepsProgress() {
        val c = country()
        val before = Levels.capacity(c, Capacity.CHUNKS)
        assertEquals(1, ResearchReset.run())
        assertTrue(c.research.done.isEmpty() && c.research.queue.isEmpty() && c.research.parked.isEmpty() && c.buffs.enabled.isEmpty())
        assertEquals(ResearchReset.VERSION, c.research.treeVersion)
        assertEquals(40, c.xp)
        assertEquals(3, c.level)
        assertEquals(3, c.rewardedLevel)
        assertEquals(2, c.tokens["rename"])
        assertEquals(600, c.treasury)
        assertEquals(before - 5, Levels.capacity(c, Capacity.CHUNKS))
    }

    @Test
    fun resetRunsOnlyOncePerVersion() {
        val c = country()
        ResearchReset.run()
        c.research.done["t:a"] = 1
        assertEquals(0, ResearchReset.run())
        assertTrue("t:a" in c.research.done)
        c.research.treeVersion = ResearchReset.VERSION - 1
        assertEquals(1, ResearchReset.run())
        assertFalse("t:a" in c.research.done)
    }

    @Test
    fun foundedCountryIsNotReset() {
        val c = ResearchReset.founded(Country("gamma"))
        Realm.data.countries[c.id] = c
        c.research.done["t:a"] = 1
        assertEquals(0, ResearchReset.run())
        assertTrue("t:a" in c.research.done)
    }

    @Test
    fun inactiveCountryIsLeftUntouched() {
        val c = country()
        c.state = CountryState.DISBANDED
        assertEquals(0, ResearchReset.run())
        assertTrue(c.research.done.isNotEmpty())
    }

    @Test
    fun unrefundablePaidEntryIsParkedNotLost() {
        val c = country()
        c.research.queue += QueueEntry("t:b", paid = true, paidCost = -1)
        ResearchReset.run()
        assertTrue(c.research.queue.isEmpty())
        assertEquals(listOf("t:b"), c.research.parked.map { it.node })
    }

    @Test
    fun resetIsRefusedWhileTreesAreStale() {
        val c = country()
        Research.treesCurrent = false
        try {
            assertEquals(0, ResearchReset.run())
        } finally {
            Research.treesCurrent = true
        }
        assertTrue("t:a" in c.research.done)
        assertEquals(0, c.research.treeVersion)
    }

    @Test
    fun staleTreeFileIsReplacedByTheBundledOne() {
        val dir = Files.createTempDirectory("trees")
        val bundled = mapOf("old" to TreeFile(version = 2), "cur" to TreeFile(version = 2), "free" to TreeFile())
        Files.writeString(dir.resolve("old.json"), """{"version":1}""")
        Files.writeString(dir.resolve("cur.json"), """{"version":2}""")
        Files.writeString(dir.resolve("free.json"), "{}")
        assertTrue(Research.replaceStale(dir, bundled))
        assertFalse(Files.exists(dir.resolve("old.json")))
        assertTrue(Files.exists(dir.resolve("old.json.v1.bak")))
        assertTrue(Files.exists(dir.resolve("cur.json")) && Files.exists(dir.resolve("free.json")))
    }

    @Test
    fun emptyCountryIsStampedWithoutNotice() {
        val c = Country("beta")
        Realm.data.countries[c.id] = c
        assertEquals(0, ResearchReset.run())
        assertEquals(ResearchReset.VERSION, c.research.treeVersion)
    }
}
