package kami.claims

import kami.claims.net.ResearchSync
import kami.claims.net.ResearchWire
import kami.claims.research.*
import kami.libs.config.Configs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossTreeTest {
    @AfterTest
    fun reset() {
        Research.defs = ResearchDefs.EMPTY
    }

    private fun tree(vararg nodes: String) =
        Configs.json().decodeFromString(TreeFile.serializer(), """{"categories":[{"id":"c"}],"nodes":[${nodes.joinToString(",")}]}""")

    @Test
    fun externalRequirementNeedsOtherTreeAndSyncsToClient() {
        val a = tree("""{"id":"x","category":"c","time":"1m"}""")
        val b = tree("""{"id":"y","category":"c","time":"1m","requires":[{"type":"node","id":"a:x","hidden":true}]}""")
        Research.defs = Validator.build(ResearchSettings(baseline = emptyList()), LevelsConfig(), emptyMap(), mapOf("a" to a, "b" to b)).defs
        val y = Research.defs.node("b:y")!!
        val country = Country("c")
        assertFalse(y.requires.all { it.met(country) })
        country.research.done["a:x"] = 0
        assertTrue(y.requires.all { it.met(country) })
        val view = ResearchWire.decodeDefs(ResearchWire.encodeDefs(ResearchSync.defs())).trees.first { it.id == "b" }.nodes.single()
        assertEquals(listOf("a:x"), view.external)
        assertEquals(listOf("a:x"), view.hiddenExternal)
        assertEquals(emptyList(), view.requires)
    }
}
