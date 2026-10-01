package kami.claims

import kami.claims.research.*
import kami.libs.config.Configs
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskRoutingTest {
    private fun node(id: String, task: String) = """{"id":"$id","category":"c","time":"10m","cost":100,"tasks":[$task]}"""

    @BeforeTest
    fun setup() {
        Realm.reset(Data())
        val tree = Configs.json().decodeFromString(
            TreeFile.serializer(),
            """{"categories":[{"id":"c"}],"nodes":[
                ${node("village", """{"type":"structure","structure":"#minecraft:village","count":2}""")},
                ${node("fortress", """{"type":"structure","structure":"minecraft:fortress"}""")},
                ${node("crush", """{"type":"process","recipeType":"create:crushing","output":"create:crushed_raw_iron","count":2}""")},
                ${node("smelt", """{"type":"process","recipeType":"minecraft:*","count":3}""")}
            ]}"""
        )
        Research.defs = Validator.build(ResearchSettings(baseline = emptyList()), LevelsConfig(capacities = mapOf(Capacity.QUEUE_SLOTS to 4, Capacity.RESEARCH_SLOTS to 1)), emptyMap(), mapOf("t" to tree)).defs
        Levels.day = { 1 }
        Levels.announce = {}
    }

    @AfterTest
    fun restore() {
        Research.defs = ResearchDefs.EMPTY
    }

    private fun country(): Country {
        val c = Country("alpha", treasury = 1000, level = 10)
        Realm.data.countries[c.id] = c
        Realm.join(c, "alpha_p", Rank.PRESIDENT)
        return c
    }

    private fun queue(c: Country, vararg nodes: String) = nodes.forEach { Queue.enqueue(c, "t:$it", null) }

    private fun progress(c: Country, node: String) = c.research.queue.first { it.node == "t:$node" }.tasks[0] ?: 0L

    @Test
    fun structureSelectorsMatchIdAndTag() {
        val c = country()
        queue(c, "village", "fortress")
        Progress.structureEntered(c, "minecraft:village_plains", listOf("minecraft:village"))
        assertEquals(1L, progress(c, "village"))
        assertEquals(0L, progress(c, "fortress"))
        Progress.structureEntered(c, "minecraft:fortress", emptyList())
        assertEquals(1L, c.research.queue.count { it.state == NodeState.QUEUED }.toLong())
        assertTrue(c.research.queue.any { it.node == "t:fortress" && it.state == NodeState.READY })
    }

    @Test
    fun firstSightingOfAStructureGivesDiscoveryXpOnce() {
        val c = country()
        Progress.structureEntered(c, "minecraft:fortress", emptyList())
        val xp = c.xp
        assertTrue(xp > 0)
        assertTrue("structure:minecraft:fortress" in c.research.visited)
        Progress.structureEntered(c, "minecraft:fortress", emptyList())
        assertEquals(xp, c.xp)
    }

    @Test
    fun watchReportsOnlyOnEntry() {
        val watch = StructureWatch()
        val player = UUID.randomUUID()
        val village = Sighting("minecraft:village_plains", listOf("minecraft:village"))
        assertNull(watch.observe(player, null))
        assertNotNull(watch.observe(player, village))
        assertNull(watch.observe(player, village))
        assertNull(watch.observe(player, null))
        assertNotNull(watch.observe(player, village))
        assertNotNull(watch.observe(player, Sighting("minecraft:fortress", emptyList())))
    }

    @Test
    fun processMatchesTypeAndOutput() {
        val c = country()
        queue(c, "crush", "smelt")
        Progress.processed(c, "create:crushing", "create:crushed_raw_gold")
        assertEquals(0L, progress(c, "crush"))
        Progress.processed(c, "create:crushing", "create:crushed_raw_iron")
        assertEquals(1L, progress(c, "crush"))
        assertEquals(0L, progress(c, "smelt"))
        Progress.processed(c, "minecraft:blasting", "minecraft:iron_ingot")
        assertEquals(1L, progress(c, "smelt"))
        assertEquals(1L, progress(c, "crush"))
    }

    @Test
    fun processTaskAcceptsBlankOutputAndRejectsOtherTypes() {
        val task = ProcessTask(recipeType = "minecraft:*")
        assertTrue(task.accepts("minecraft:smelting|minecraft:glass"))
        assertFalse(task.accepts("create:milling|minecraft:flour"))
        assertTrue(ProcessTask(output = "minecraft:flour").accepts("create:milling|minecraft:flour"))
    }
}
