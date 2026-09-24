package kami.geology.config

import kami.geology.KamiGeology
import kami.geology.util.Hash
import kami.libs.mc.Registry
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

object Blocks {
    private val registry = Registry(KamiGeology.LOG, Compat::loaded)

    fun find(id: String): Block? = registry.findBlock(id)
    fun require(id: String, context: String): Block = registry.requireBlock(id, context)
    fun missing(id: String, context: String) = registry.missing(id, context)
}

class BlockRule(entries: List<String>, context: String) {
    private val blocks: Set<Block>
    private val tags: List<TagKey<Block>>

    init {
        val (tagEntries, blockEntries) = entries.partition { it.startsWith("#") }
        tags = tagEntries.mapNotNull { entry ->
            ResourceLocation.tryParse(entry.substring(1))?.let { TagKey.create(Registries.BLOCK, it) }
                ?: null.also { KamiGeology.LOG.warn("Invalid tag '{}' in {}", entry, context) }
        }
        blocks = blockEntries.mapNotNull { entry ->
            Blocks.find(entry) ?: null.also { Blocks.missing(entry, context) }
        }.toSet()
    }

    fun test(state: BlockState): Boolean = state.block in blocks || tags.any { state.`is`(it) }
}

class Palette(entries: List<Weighted>, context: String) {
    private val states: List<BlockState>
    private val cumulative: DoubleArray

    init {
        val resolved = entries.mapNotNull { entry ->
            val block = Blocks.find(entry.block)
            if (block == null) Blocks.missing(entry.block, context)
            block?.let { it.defaultBlockState() to entry.weight.coerceAtLeast(0.0) }
        }
        states = resolved.map { it.first }
        var sum = 0.0
        cumulative = DoubleArray(resolved.size) { sum += resolved[it].second; sum }
    }

    val isEmpty get() = states.isEmpty() || cumulative.last() <= 0.0

    fun pick(unit: Double): BlockState {
        val target = unit * cumulative.last()
        return states[cumulative.indexOfFirst { it > target }.let { if (it < 0) states.lastIndex else it }]
    }
}

class Provinces(map: Map<String, List<String>>, private val fallback: String? = null) {
    private class Rule(val name: String, val ids: List<ResourceLocation>, val tags: List<TagKey<Biome>>)

    private val rules = map.map { (name, entries) ->
        val (tagEntries, idEntries) = entries.partition { it.startsWith("#") }
        Rule(
            name,
            idEntries.mapNotNull { ResourceLocation.tryParse(it) },
            tagEntries.mapNotNull { ResourceLocation.tryParse(it.substring(1)) }.map { TagKey.create(Registries.BIOME, it) }
        )
    }
    private val cache = ConcurrentHashMap<ResourceLocation, String>()

    val names: Set<String> = map.keys

    fun of(biome: Holder<Biome>): String? {
        val key = biome.unwrapKey().map { it.location() }.orElse(null) ?: return find(biome)
        return cache.getOrPut(key) { find(biome) ?: "" }.ifEmpty { null }
    }

    private fun find(biome: Holder<Biome>): String? =
        rules.firstOrNull { rule -> rule.ids.any { biome.`is`(it) } || rule.tags.any { biome.`is`(it) } }?.name
            ?: fallback?.takeUnless { biome.`is`(Compat.cave) }
}

class Ore(
    val id: String,
    val index: Int,
    val config: OreConfig,
    val stone: BlockState,
    val deepslate: BlockState,
    val hosts: Map<Block, BlockState>,
    val core: BlockState?,
    val halo: Palette?,
    val outcrop: Palette?,
    val replaceable: BlockRule,
    val haloRule: BlockRule,
    val outcropRule: BlockRule,
    val deepslateRule: BlockRule,
    val strip: Set<Block>,
    val scatterProvinces: Set<String>?
) {
    val salt = Hash.mix(id.hashCode().toLong())
    val deposit = config.deposit
    val scatter = config.scatter
    val haloScale = if (halo != null) config.halo!!.scale.coerceAtLeast(1.0) else 1.0
    val maxWeight = config.provinces.values.maxOrNull() ?: 0.0
    val tiers = deposit?.tiers.orEmpty()
    var anchor: Ore? = null

    val reach: Double = deposit?.let { dep ->
        val half = tiers.maxOf { max(it.length[1], max(it.width[1], it.thickness[1])) } / 2.0
        val shape = dep.shape
        half * haloScale * (1.0 + shape.warp + shape.lean + if (shape.kind == Kind.PODS) 0.6 else 0.0) + 6.0
    } ?: 0.0

    fun stateFor(host: BlockState): BlockState = hosts[host.block] ?: if (deepslateRule.test(host)) deepslate else stone

    fun weightIn(province: String?): Double = province?.let { config.provinces[it] } ?: 0.0
}

class Settings(
    val general: GeneralConfig,
    val provinces: Provinces,
    val ores: List<Ore>,
    val version: Int
) {
    val dimensions: Set<ResourceLocation> = general.dimensions.mapNotNull { ResourceLocation.tryParse(it) }.toSet()
    val removeIds: Set<ResourceLocation> = general.removeFeatures.mapNotNull { ResourceLocation.tryParse(it) }.toSet()
    val keepIds: Set<ResourceLocation> = general.keepFeatures.mapNotNull { ResourceLocation.tryParse(it) }.toSet()
    val grades: List<Grade> = general.richness.grades.filter { it.weight > 0.0 }
    private val gradeTotal = grades.sumOf { it.weight }
    private val byId = ores.associateBy { it.id }
    private val byBlock: Map<Block, Ore> = buildMap {
        ores.forEach { ore ->
            (listOf(ore.stone.block, ore.deepslate.block) + ore.hosts.values.map { it.block }).forEach { putIfAbsent(it, ore) }
        }
    }

    val managed: Set<Block> = ores.flatMapTo(HashSet()) { ore ->
        listOf(ore.stone.block, ore.deepslate.block) + ore.hosts.values.map { it.block } + listOfNotNull(ore.core?.block) + ore.strip
    }

    fun ore(id: String): Ore? = byId[id]

    fun oreOf(block: Block): Ore? = byBlock[block]

    fun grade(unit: Double): Grade {
        var target = unit * gradeTotal
        return grades.firstOrNull { target -= it.weight; target < 0.0 } ?: grades.last()
    }
}
