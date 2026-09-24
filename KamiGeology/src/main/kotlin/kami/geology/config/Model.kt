package kami.geology.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GeneralConfig(
    val dimensions: List<String> = listOf("minecraft:overworld"),
    val removeOriginalOres: Boolean = true,
    val disableOreVeins: Boolean = true,
    val discoverOres: Boolean = true,
    val fallbackProvince: String? = "plains",
    val removeFeatures: List<String> = listOf(
        "createnuclear:striated_ores_overworld",
        "tfmg:galena", "tfmg:bauxite", "tfmg:lignite", "tfmg:fireclay"
    ),
    val keepFeatures: List<String> = emptyList(),
    val replaceable: List<String> = listOf("#minecraft:stone_ore_replaceables", "#minecraft:deepslate_ore_replaceables"),
    val deepslateHosts: List<String> = listOf("#minecraft:deepslate_ore_replaceables"),
    val siteCache: Int = 32768,
    val auditThreshold: Int = 500,
    val chains: Map<String, List<String>> = linkedMapOf(
        "steel" to listOf("iron", "coal"),
        "brass" to listOf("copper", "zinc"),
        "bronze" to listOf("copper", "tin"),
        "aluminium" to listOf("bauxite", "coal"),
        "batteries" to listOf("lead", "lithium"),
        "reactor" to listOf("uranium", "lead", "iron"),
        "stainless" to listOf("iron", "chromium", "nickel"),
        "hardmetal" to listOf("tungsten", "cobalt", "graphite"),
        "catalysts" to listOf("platinum", "vanadium", "molybdenum"),
        "electronics" to listOf("silver", "antimony", "graphite"),
        "fertiliser" to listOf("phosphorus", "coal"),
        "fluorochemicals" to listOf("fluorite", "phosphorus"),
        "acids" to listOf("sulfur", "salt")
    ),
    val richness: Richness = Richness()
)

@Serializable
data class Richness(
    val enabled: Boolean = true,
    val grades: List<Grade> = listOf(
        Grade("poor", 30.0, 0.6, 0.75),
        Grade("normal", 55.0, 1.0, 1.0),
        Grade("rich", 15.0, 1.6, 1.25)
    )
)

@Serializable
data class Grade(val name: String, val weight: Double, val drops: Double, val density: Double)

@Serializable
data class OreConfig(
    val enabled: Boolean = true,
    val blocks: OreBlocks,
    val provinces: Map<String, Double> = emptyMap(),
    val deposit: DepositConfig? = null,
    val scatter: ScatterConfig? = null,
    val core: CoreConfig? = null,
    val halo: HaloConfig? = null,
    val outcrop: OutcropConfig? = null
)

@Serializable
data class OreBlocks(
    val stone: String,
    val deepslate: String? = null,
    val hosts: Map<String, String> = emptyMap(),
    val strip: List<String> = emptyList()
)

@Serializable
data class DepositConfig(
    val shape: Shape = Shape(),
    val spacing: Int = 128,
    val chance: Double = 0.5,
    val height: List<Int> = listOf(-60, 60),
    val minDepth: Int = 6,
    val density: Double = 0.85,
    val bodyRadius: Double = 0.6,
    val fringe: Double = 0.15,
    val airDiscard: Double = 0.6,
    val tiers: List<Tier>,
    val limit: List<Int>? = null,
    val minSurface: Int? = null,
    val surfaceDepth: List<Int>? = null,
    val anchor: Anchor? = null
)

@Serializable
data class Shape(
    val kind: Kind = Kind.SHEET,
    val dip: List<Double> = listOf(0.0, 20.0),
    val warp: Double = 0.3,
    val warpScale: Double = 24.0,
    val layers: Int = 1,
    val bandWidth: Double = 6.0,
    val bandCut: Double = 0.0,
    val taper: Double = 0.6,
    val lean: Double = 0.15,
    val lumps: List<Int> = listOf(4, 8),
    val cut: Double = 0.0
)

@Serializable
enum class Kind {
    @SerialName("sheet") SHEET,
    @SerialName("seam") SEAM,
    @SerialName("band") BAND,
    @SerialName("pipe") PIPE,
    @SerialName("pods") PODS,
    @SerialName("cloud") CLOUD
}

@Serializable
data class Tier(
    val name: String,
    val weight: Double,
    val length: List<Int>,
    val width: List<Int>,
    val thickness: List<Int>
)

@Serializable
data class Anchor(
    val ore: String,
    val tiers: List<String> = emptyList(),
    val chance: Double = 0.1,
    val offsetX: List<Int> = listOf(-16, 16),
    val offsetY: List<Int> = listOf(-8, 8),
    val offsetZ: List<Int> = listOf(-16, 16),
    val absoluteY: Boolean = false
)

@Serializable
data class CoreConfig(
    val chance: Double,
    val block: String,
    val radius: Double = 0.3,
    val airDiscard: Double = 0.3
)

@Serializable
data class Weighted(val block: String, val weight: Double = 1.0)

@Serializable
data class HaloConfig(
    val blocks: List<Weighted>,
    val scale: Double = 1.4,
    val density: Double = 0.5,
    val replace: List<String>? = null
)

@Serializable
data class OutcropConfig(
    val blocks: List<Weighted>,
    val maxDepth: Int = 40,
    val density: Double = 0.35,
    val oreShare: Double = 0.12,
    val replace: List<String> = listOf("#minecraft:dirt", "#minecraft:sand", "#minecraft:base_stone_overworld", "minecraft:gravel")
)

@Serializable
data class ScatterConfig(
    val perChunk: Double,
    val size: List<Int> = listOf(2, 5),
    val height: List<Int> = listOf(-60, 100),
    val distribution: Distribution = Distribution.UNIFORM,
    val airDiscard: Double = 0.5,
    val provinces: List<String>? = null
)

@Serializable
enum class Distribution {
    @SerialName("uniform") UNIFORM,
    @SerialName("triangle") TRIANGLE
}
