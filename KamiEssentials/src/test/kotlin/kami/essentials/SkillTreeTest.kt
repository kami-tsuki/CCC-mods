package kami.essentials

import kami.essentials.skills.NodeKind
import kami.essentials.skills.SkillTree
import kami.libs.skills.SkillClass
import kami.libs.skills.Skills
import kami.libs.text.LangAudit
import java.nio.file.Path
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillTreeTest {
    @Test
    fun everyNodeIsReachableFromAnyStart() {
        val all = SkillTree.nodes.map { it.id }.toSet()
        assertEquals(SkillClass.entries.size, SkillTree.starts.size)
        SkillTree.starts.values.forEach { assertTrue(SkillTree.connected(all, it)) }
        assertEquals(6 * SkillClass.entries.size, SkillTree.nodes.count { it.kind == NodeKind.CORE })
        assertEquals(5 * SkillClass.entries.size, SkillTree.constellations.size)
    }

    @Test
    fun bonusNodesAreLeavesAndNotSectorPoints() {
        val bonus = SkillTree.nodes.filter { it.kind == NodeKind.BONUS }
        assertEquals(2 * SkillClass.entries.size, bonus.size)
        bonus.forEach { assertEquals(1, it.links.size); assertEquals(1, it.ranks); assertEquals(-1, it.alt); assertTrue(it.gate == null) }
        SkillClass.entries.forEach { cls ->
            val own = bonus.filter { it.cls == cls }
            assertEquals(2, own.size)
            assertEquals(0, SkillTree.sector(own.associate { it.id to 1 }, cls))
            assertEquals(2, SkillTree.spent(own.associate { it.id to 1 }, cls))
        }
    }

    @Test
    fun nodesDoNotOverlap() {
        val n = SkillTree.nodes
        for (i in n.indices) for (j in i + 1 until n.size) assertTrue(hypot(n[i].x - n[j].x, n[i].y - n[j].y) > 14.0, "${n[i].name} ${n[j].name}")
    }

    @Test
    fun alternativesArePairedAndKeystonesAreGated() {
        val alts = SkillTree.nodes.filter { it.alt >= 0 }
        assertEquals(8 * SkillClass.entries.size, alts.size)
        alts.forEach { assertEquals(it.id, SkillTree.nodes[it.alt].alt) }
        SkillClass.entries.forEach { cls ->
            val keys = SkillTree.nodes.filter { it.cls == cls && it.kind == NodeKind.KEYSTONE }
            assertEquals(2, keys.size)
            assertEquals(keys[1].id, keys[0].alt)
            keys.forEach { assertTrue(it.gate!!.sector(1) >= 40) }
        }
        assertTrue(SkillTree.blocked(mapOf(alts.first().id to 1), SkillTree.nodes[alts.first().alt]))
    }

    @Test
    fun constellationBonusNeedsEveryNode() {
        val c = SkillTree.constellations.first()
        val owned = c.nodes.associateWith { 1 }
        val key = c.bonus.keys.first()
        val full = SkillTree.values(owned)[key] ?: 0.0
        val part = SkillTree.values(owned - c.nodes.first())[key] ?: 0.0
        assertTrue(full - part >= c.bonus.getValue(key) - 1e-9)
    }

    @Test
    fun cheapestKeystonePathCostsAboutFortyFourPoints() {
        val need = 40
        SkillClass.entries.forEach { cls ->
            val start = SkillTree.starts.getValue(cls)
            val seen = hashMapOf((start to 0) to 0)
            val queue = ArrayDeque(listOf(start to 0))
            var cost = Int.MAX_VALUE
            while (queue.isNotEmpty()) {
                val (id, sec) = queue.removeFirst()
                val d = seen.getValue(id to sec)
                val n = SkillTree.nodes[id]
                if (n.kind == NodeKind.KEYSTONE && n.cls == cls) { cost = minOf(cost, d + need - sec); continue }
                n.links.forEach { next ->
                    val m = SkillTree.nodes[next]
                    val state = next to minOf(need, sec + if (m.cls == cls && m.kind in listOf(NodeKind.MINOR, NodeKind.NOTABLE, NodeKind.CORE)) 1 else 0)
                    if (state !in seen) { seen[state] = d + 1; queue += state }
                }
            }
            assertTrue(cost in 42..46, "$cls $cost")
        }
    }

    @Test
    fun furtherKeystonesNeedHigherLevels() {
        val key = SkillTree.nodes.first { it.kind == NodeKind.KEYSTONE }
        val others = SkillTree.nodes.filter { it.kind == NodeKind.KEYSTONE && it.cls != key.cls }.map { it.id to 1 }
        assertEquals(40, SkillTree.levelNeed(emptyMap(), key, 1))
        assertTrue(SkillTree.levelNeed(others.take(1).toMap(), key, 1) > 40)
        assertTrue(SkillTree.levelNeed(others.take(2).toMap(), key, 1) > SkillTree.levelNeed(others.take(1).toMap(), key, 1))
    }

    @Test
    fun traderHasEconomyEffectsInsteadOfLuck() {
        val trader = SkillTree.nodes.filter { it.cls == SkillClass.TRADER }
        assertTrue(trader.none { "luck" in it.effects })
        assertTrue(SkillTree.constellations.filter { it.cls == SkillClass.TRADER }.none { "luck" in it.bonus })
        listOf(Skills.ORDER_SLOTS, Skills.AUCTION_SLOTS, Skills.INFINITE_LOTS).forEach { k -> assertTrue(trader.any { k in it.effects } || SkillTree.constellations.any { k in it.bonus }, k) }
    }

    @Test
    fun everyNodeAndEffectHasText() {
        val dir = Path.of("src/main/resources/assets/kami_essentials/lang")
        listOf("en_us", "de_de").forEach { lang ->
            val text = LangAudit.load(dir.resolve("$lang.json"))
            val effects = (SkillTree.nodes.flatMap { it.effects.keys } + SkillTree.constellations.flatMap { it.bonus.keys }).toSet()
            val names = SkillTree.nodes.filter { it.kind != NodeKind.START }.map { it.name } + SkillTree.constellations.map { it.name } + effects
            names.forEach { assertTrue("kami_essentials.skill.name.$it" in text, "$lang name $it") }
            effects.forEach { assertTrue("kami_essentials.skill.effect.$it" in text, "$lang effect $it") }
        }
    }
}
