package kami.geology.config

import kami.geology.util.Rng
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.neoforged.fml.ModList
import net.minecraft.world.level.block.Blocks as Vanilla

object Compat {
    private val order = compareBy<Block>({ key(it).namespace != "minecraft" }, { key(it).toString() })
    private val deepslate = blockTag("ores_in_ground/deepslate")
    private val ores = blockTag("ores")
    val cave: TagKey<Biome> = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("c", "is_cave"))

    private fun blockTag(path: String): TagKey<Block> = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", path))

    private fun key(block: Block) = BuiltInRegistries.BLOCK.getKey(block)

    private fun name(block: Block) = key(block).toString()

    private fun present(id: String) = Blocks.find(id) != null

    fun ready() = Vanilla.STONE.defaultBlockState().`is`(BlockTags.BASE_STONE_OVERWORLD)

    fun loaded(id: String) = ResourceLocation.tryParse(id)?.let { ModList.get().isLoaded(it.namespace) } ?: false

    private fun members(tag: TagKey<Block>): List<Block> =
        BuiltInRegistries.BLOCK.getTag(tag).map { set -> set.map { it.value() } }.orElse(emptyList())

    private fun tagsOf(block: Block, prefix: String) = BuiltInRegistries.BLOCK.wrapAsHolder(block).tags().map { it.location() }
        .filter { it.namespace == "c" && it.path.startsWith(prefix) }.map { it.path }.toList()

    private fun overworld(block: Block): Boolean {
        val hosts = tagsOf(block, "ores_in_ground/")
        return if (hosts.isEmpty()) key(block).namespace != "minecraft" else hosts.any { it.endsWith("/stone") || it.endsWith("/deepslate") }
    }

    fun material(name: String): List<Block> = members(blockTag("ores/$name")).filter(::overworld).sortedWith(order)

    private fun raw(name: String) = members(blockTag("storage_blocks/raw_$name")).minWithOrNull(order)

    private fun materialOf(block: Block) =
        tagsOf(block, "ores/").minOrNull()?.removePrefix("ores/") ?: key(block).path.removeSuffix("_ore").removePrefix("deepslate_")

    private fun blocksOf(group: List<Block>): OreBlocks? {
        val deep = group.firstOrNull { it.defaultBlockState().`is`(deepslate) }
        val stone = group.firstOrNull { it != deep } ?: deep ?: return null
        return OreBlocks(name(stone), deep?.takeIf { it != stone }?.let(::name), strip = group.filter { it != stone && it != deep }.map(::name))
    }

    fun claims(id: String, config: OreConfig): List<String> = with(config.blocks) {
        listOfNotNull(stone, deepslate) + hosts.values + strip + material(id).map(::name)
    }

    fun adapt(id: String, config: OreConfig): OreConfig? {
        val own = config.blocks
        val blocks = if (present(own.stone)) own.copy(
            deepslate = own.deepslate?.takeIf(::present),
            hosts = own.hosts.filter { (host, ore) -> present(host) && present(ore) },
            strip = own.strip.filter(::present)
        ) else (if (ready()) blocksOf(material(id)) else null) ?: return null
        val core = config.core?.let { if (present(it.block)) it else raw(id)?.let { block -> it.copy(block = name(block)) } }
        fun known(list: List<Weighted>) = list.filter { present(it.block) }
        return config.copy(
            blocks = blocks,
            core = core,
            halo = config.halo?.let { it.copy(blocks = known(it.blocks)) }?.takeIf { it.blocks.isNotEmpty() },
            outcrop = config.outcrop?.let { it.copy(blocks = known(it.blocks)) }?.takeIf { it.blocks.isNotEmpty() }
        )
    }

    fun discover(claimed: Set<String>, taken: Set<String>, provinces: List<String>): Map<String, OreConfig> {
        val groups = LinkedHashMap<String, MutableList<Block>>()
        members(ores).filter(::overworld).sortedWith(order).filter { name(it) !in claimed }
            .forEach { groups.getOrPut(materialOf(it)) { ArrayList() } += it }
        val out = LinkedHashMap<String, OreConfig>()
        groups.forEach { (material, group) ->
            val blocks = blocksOf(group) ?: return@forEach
            val safe = material.replace('/', '_')
            val id = if (safe in taken) "${key(group[0]).namespace}_$safe" else safe
            out[id] = generate(id, blocks, raw(material), provinces)
        }
        return out
    }

    private fun generate(id: String, blocks: OreBlocks, raw: Block?, provinces: List<String>): OreConfig {
        val rng = Rng(id.hashCode().toLong())
        val first = provinces.getOrNull(rng.int(provinces.size.coerceAtLeast(1)))
        val second = provinces.getOrNull(rng.int(provinces.size.coerceAtLeast(1)))
        val weights = listOfNotNull(first?.let { it to 1.0 }, second?.takeIf { it != first }?.let { it to 0.4 }).toMap()
        val low = -50 + rng.int(60)
        val range = listOf(low, (low + 50 + rng.int(40)).coerceAtMost(110))
        val shape = when (rng.int(5)) {
            0 -> Shape(Kind.SHEET, dip = listOf(40.0, 85.0), warp = 0.3)
            1 -> Shape(Kind.SEAM, dip = listOf(0.0, 12.0), warp = 0.22, layers = 2)
            2 -> Shape(Kind.BAND, dip = listOf(0.0, 25.0), warp = 0.2, bandWidth = 5.0, bandCut = -0.1)
            3 -> Shape(Kind.PODS, lumps = listOf(2, 5), warp = 0.25)
            else -> Shape(Kind.CLOUD, warp = 0.3, cut = 0.05)
        }
        return OreConfig(
            blocks = blocks,
            provinces = weights,
            deposit = DepositConfig(shape, spacing = 160, chance = 0.4, height = range, tiers = Defaults.tiers(60.0, 40.0)),
            scatter = ScatterConfig(0.05, listOf(2, 4), range, provinces = weights.keys.toList().ifEmpty { null }),
            core = raw?.let { CoreConfig(0.1, name(it)) },
            halo = Defaults.halo("minecraft:tuff" to 2.0),
            outcrop = Defaults.outcrop("minecraft:gravel" to 2.0)
        )
    }
}
