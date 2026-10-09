package kami.geology.config

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class ConfigValidationTest {
    private val tier = Tier("t", 1.0, listOf(4, 8), listOf(4, 8), listOf(2, 4))

    private fun ore(deposit: DepositConfig? = null, scatter: ScatterConfig? = null, core: CoreConfig? = null, provinces: Map<String, Double> = emptyMap()) =
        OreConfig(blocks = OreBlocks("minecraft:stone"), provinces = provinces, deposit = deposit, scatter = scatter, core = core)

    private fun deposit(
        tiers: List<Tier> = listOf(tier),
        spacing: Int = 128,
        chance: Double = 0.5,
        bodyRadius: Double = 0.6,
        height: List<Int> = listOf(-60, 60)
    ) = DepositConfig(spacing = spacing, chance = chance, bodyRadius = bodyRadius, tiers = tiers, height = height)

    private fun rejects(config: OreConfig, text: String) {
        val e = assertFailsWith<IllegalArgumentException> { ConfigStore.validate(config, emptySet()) }
        assertContains(e.message.orEmpty(), text)
    }

    @Test
    fun aValidDepositAndScatterPass() {
        ConfigStore.validate(ore(deposit(), ScatterConfig(perChunk = 1.0), CoreConfig(0.5, "minecraft:diamond_block")), emptySet())
    }

    @Test
    fun depositRulesRejectBadValues() {
        rejects(ore(deposit(tiers = emptyList())), "tiers must not be empty")
        rejects(ore(deposit(spacing = 15)), "spacing")
        rejects(ore(deposit(chance = 1.1)), "chance")
        rejects(ore(deposit(bodyRadius = 1.0)), "bodyRadius")
        rejects(ore(deposit(height = listOf(5, 2))), "deposit.height")
        rejects(ore(deposit(tiers = listOf(tier.copy(weight = 0.0)))), "weight must be positive")
        rejects(ore(deposit(tiers = listOf(tier.copy(length = listOf(1, 4))))), "length")
    }

    @Test
    fun scatterCoreAndProvinceRulesRejectBadValues() {
        rejects(ore(scatter = ScatterConfig(perChunk = 1.0, size = listOf(0, 3))), "scatter.size")
        rejects(ore(scatter = ScatterConfig(perChunk = -1.0)), "perChunk")
        rejects(ore(core = CoreConfig(1.5, "minecraft:stone")), "core.chance")
        rejects(ore(provinces = mapOf("p" to -1.0)), "province weight")
    }

    @Test
    fun rangesNeedTwoOrderedEntries() {
        ConfigStore.ints(listOf(2, 5), "r")
        ConfigStore.doubles(listOf(0.0, 1.0), "d")
        assertFailsWith<IllegalArgumentException> { ConfigStore.ints(listOf(5, 2), "r") }
        assertFailsWith<IllegalArgumentException> { ConfigStore.ints(listOf(1), "r") }
        assertFailsWith<IllegalArgumentException> { ConfigStore.ints(listOf(0, 3), "r", 1) }
        assertFailsWith<IllegalArgumentException> { ConfigStore.doubles(listOf(2.0, 1.0), "d") }
    }

    @Test
    fun newBoundsRejectOutOfRangeValues() {
        rejects(ore(scatter = ScatterConfig(perChunk = 257.0)), "perChunk")
        rejects(ore(scatter = ScatterConfig(perChunk = -1.0)), "perChunk")
        ConfigStore.validate(ore(scatter = ScatterConfig(perChunk = 256.0)), emptySet())
        rejects(ore(core = CoreConfig(0.5, "minecraft:stone", radius = 1.5)), "core.radius")
        rejects(ore().copy(halo = HaloConfig(listOf(Weighted("minecraft:stone")), density = 2.0)), "halo.density")
        rejects(ore(deposit().copy(anchor = Anchor("minecraft:coal_ore", chance = -0.1))), "anchor.chance")
    }
}
