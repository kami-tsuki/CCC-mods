package kami.geology.config

import kotlin.math.roundToInt

object Defaults {
    private val DEEP = listOf(-60, 6)

    private val base = listOf(
        Triple("small", listOf(16, 26), Pair(listOf(10, 18), listOf(6, 10))),
        Triple("medium", listOf(32, 50), Pair(listOf(20, 34), listOf(10, 16))),
        Triple("large", listOf(60, 90), Pair(listOf(36, 56), listOf(14, 22))),
        Triple("motherlode", listOf(100, 150), Pair(listOf(60, 90), listOf(18, 30)))
    )

    val provinces: Map<String, List<String>> = linkedMapOf(
        "frozen" to listOf(
            "minecraft:snowy_plains", "minecraft:snowy_taiga", "minecraft:ice_spikes", "minecraft:frozen_river",
            "minecraft:snowy_slopes", "minecraft:grove", "minecraft:frozen_peaks",
            "#c:is_snowy", "#c:is_icy",
            "#terralith:reference/temperature/frozen_all", "#terralith:reference/temperature/frozen_with_structures"
        ),
        "arid" to listOf(
            "#minecraft:is_badlands", "#minecraft:is_savanna", "minecraft:desert", "#c:is_desert", "#c:is_badlands", "#c:is_savanna",
            "#terralith:reference/desert_all", "#terralith:reference/desert_with_structures",
            "#terralith:reference/badlands_all", "#terralith:reference/badlands_with_structures",
            "#terralith:reference/savanna", "#terralith:volcanic", "#terralith:oases"
        ),
        "wetland" to listOf(
            "#minecraft:is_jungle", "#terralith:reference/jungle", "#minecraft:is_river", "#minecraft:is_beach",
            "#minecraft:is_ocean", "minecraft:mushroom_fields", "#c:is_jungle", "#c:is_river", "#c:is_beach", "#c:is_ocean"
        ),
        "highlands" to listOf(
            "#minecraft:is_mountain", "#minecraft:is_hill", "#c:is_mountain", "#c:is_mountain_peak", "#c:is_mountain_slope", "#terralith:cliffs", "minecraft:meadow", "minecraft:stony_peaks",
            "#terralith:reference/mountain_peak", "#terralith:reference/mountain_slope",
            "#terralith:reference/windswept", "#terralith:highlands"
        ),
        "boreal" to listOf(
            "#minecraft:is_forest", "minecraft:taiga", "minecraft:old_growth_pine_taiga", "minecraft:old_growth_spruce_taiga",
            "minecraft:swamp", "minecraft:mangrove_swamp", "#c:is_forest", "#c:is_taiga", "#c:is_swamp",
            "#terralith:reference/forest", "#terralith:reference/taiga", "#terralith:reference/swamp"
        ),
        "plains" to listOf(
            "minecraft:plains", "minecraft:sunflower_plains", "minecraft:cherry_grove", "#c:is_plains",
            "#terralith:reference/plains", "#terralith:shrublands"
        )
    )

    val ores: Map<String, OreConfig> = linkedMapOf<String, OreConfig>(
        "coal" to OreConfig(
            blocks = OreBlocks("minecraft:coal_ore", "minecraft:deepslate_coal_ore"),
            provinces = mapOf("boreal" to 1.0, "wetland" to 0.35),
            deposit = DepositConfig(
                shape = Shape(Kind.SEAM, dip = listOf(0.0, 12.0), warp = 0.22, layers = 2),
                spacing = 96, chance = 0.5, height = listOf(30, 120), density = 0.9,
                tiers = tiers(40.0, 35.0, 20.0, 5.0, thickness = 0.45)
            ),
            scatter = ScatterConfig(1.2, listOf(3, 8), listOf(0, 128)),
            core = CoreConfig(0.20, "minecraft:coal_block"),
            halo = halo("minecraft:tuff" to 3.0, "minecraft:andesite" to 1.0, "create:limestone" to 2.0),
            outcrop = outcrop("minecraft:coarse_dirt" to 3.0, "minecraft:gravel" to 2.0)
        ),
        "iron" to OreConfig(
            blocks = OreBlocks("minecraft:iron_ore", "minecraft:deepslate_iron_ore"),
            provinces = mapOf("highlands" to 1.0, "plains" to 0.8),
            deposit = DepositConfig(
                shape = Shape(Kind.BAND, dip = listOf(0.0, 25.0), warp = 0.2, bandWidth = 5.0, bandCut = -0.1),
                spacing = 112, chance = 0.5, height = listOf(-20, 90),
                tiers = tiers(40.0, 40.0, 20.0)
            ),
            scatter = ScatterConfig(0.4, listOf(2, 6), listOf(-56, 72), Distribution.TRIANGLE),
            core = CoreConfig(0.10, "minecraft:raw_iron_block"),
            halo = halo("minecraft:granite" to 2.0, "minecraft:andesite" to 1.0),
            outcrop = outcrop("minecraft:coarse_dirt" to 2.0, "minecraft:gravel" to 1.0)
        ),
        "copper" to OreConfig(
            blocks = OreBlocks("minecraft:copper_ore", "minecraft:deepslate_copper_ore"),
            provinces = mapOf("arid" to 1.0, "plains" to 0.6),
            deposit = DepositConfig(
                shape = Shape(Kind.CLOUD, warp = 0.3, cut = 0.05),
                spacing = 112, chance = 0.45, height = listOf(0, 90), density = 0.8,
                tiers = tiers(30.0, 40.0, 30.0)
            ),
            scatter = ScatterConfig(0.35, listOf(2, 6), listOf(-16, 112), Distribution.TRIANGLE),
            core = CoreConfig(0.15, "minecraft:raw_copper_block"),
            halo = halo("minecraft:andesite" to 2.0, "minecraft:tuff" to 1.0),
            outcrop = outcrop("minecraft:gravel" to 3.0, "minecraft:moss_block" to 1.0)
        ),
        "gold" to OreConfig(
            blocks = OreBlocks("minecraft:gold_ore", "minecraft:deepslate_gold_ore"),
            provinces = mapOf("arid" to 0.9, "highlands" to 0.35),
            deposit = DepositConfig(
                shape = Shape(Kind.SHEET, dip = listOf(55.0, 85.0), warp = 0.35),
                spacing = 160, chance = 0.4, height = listOf(-40, 30), density = 0.8,
                tiers = tiers(60.0, 40.0, length = 0.9, width = 0.6, thickness = 0.35)
            ),
            scatter = ScatterConfig(0.06, listOf(1, 4), listOf(-64, 32)),
            core = CoreConfig(0.05, "minecraft:raw_gold_block"),
            halo = halo("minecraft:granite" to 2.0, "minecraft:calcite" to 1.0),
            outcrop = outcrop("minecraft:gravel" to 3.0, "minecraft:sand" to 1.0)
        ),
        "redstone" to OreConfig(
            blocks = OreBlocks("minecraft:redstone_ore", "minecraft:deepslate_redstone_ore"),
            provinces = mapOf("arid" to 1.0),
            deposit = DepositConfig(
                shape = Shape(Kind.PODS, lumps = listOf(3, 6), warp = 0.25),
                spacing = 144, chance = 0.4, height = listOf(-60, -10),
                tiers = tiers(55.0, 45.0)
            ),
            scatter = ScatterConfig(0.1, listOf(2, 5), listOf(-64, 15)),
            core = CoreConfig(0.15, "minecraft:redstone_block"),
            halo = halo("minecraft:tuff" to 2.0, "minecraft:smooth_basalt" to 1.0)
        ),
        "lapis" to OreConfig(
            blocks = OreBlocks("minecraft:lapis_ore", "minecraft:deepslate_lapis_ore"),
            provinces = mapOf("wetland" to 1.0),
            deposit = DepositConfig(
                shape = Shape(Kind.PODS, lumps = listOf(2, 5), warp = 0.25),
                spacing = 144, chance = 0.35, height = listOf(-30, 40),
                tiers = tiers(65.0, 35.0)
            ),
            scatter = ScatterConfig(0.05, listOf(2, 4), listOf(-64, 64), Distribution.TRIANGLE),
            core = CoreConfig(0.15, "minecraft:lapis_block"),
            halo = halo("minecraft:calcite" to 2.0, "minecraft:diorite" to 1.0),
            outcrop = outcrop("minecraft:gravel" to 2.0, "minecraft:sand" to 1.0)
        ),
        "emerald" to OreConfig(
            blocks = OreBlocks("minecraft:emerald_ore", "minecraft:deepslate_emerald_ore"),
            provinces = mapOf("highlands" to 1.0, "frozen" to 0.7, "arid" to 0.35, "boreal" to 0.35),
            deposit = DepositConfig(
                shape = Shape(Kind.PODS, lumps = listOf(1, 3), warp = 0.2),
                spacing = 96, chance = 0.55, height = listOf(-16, 250), minSurface = 90, surfaceDepth = listOf(8, 70), density = 0.9,
                tiers = tiers(55.0, 45.0)
            ),
            core = CoreConfig(0.10, "minecraft:emerald_block"),
            halo = halo("minecraft:calcite" to 2.0, "minecraft:diorite" to 1.0),
            outcrop = outcrop("minecraft:gravel" to 2.0)
        ),
        "tin" to OreConfig(
            blocks = OreBlocks("create_ironworks:tin_ore", "create_ironworks:deepslate_tin_ore"),
            provinces = mapOf("frozen" to 1.0),
            deposit = DepositConfig(
                shape = Shape(Kind.SHEET, dip = listOf(65.0, 90.0), warp = 0.25),
                spacing = 160, chance = 0.45, height = listOf(20, 90),
                tiers = tiers(60.0, 40.0, length = 1.3, width = 0.7, thickness = 0.3)
            ),
            scatter = ScatterConfig(0.1, listOf(2, 5), listOf(-30, 70)),
            core = CoreConfig(0.15, "create_ironworks:raw_tin_block"),
            halo = halo("minecraft:granite" to 2.0, "minecraft:diorite" to 1.0),
            outcrop = outcrop("minecraft:gravel" to 2.0, "minecraft:coarse_dirt" to 1.0)
        ),
        "lithium" to OreConfig(
            blocks = OreBlocks("tfmg:lithium_ore", "tfmg:deepslate_lithium_ore"),
            deposit = DepositConfig(
                shape = Shape(Kind.SHEET, dip = listOf(65.0, 90.0), warp = 0.2),
                density = 0.9,
                tiers = tiers(100.0, length = 1.0, width = 0.6, thickness = 0.4),
                anchor = Anchor("tin", emptyList(), 0.4, listOf(-6, 6), listOf(-6, 6), listOf(-6, 6))
            ),
            core = CoreConfig(0.10, "tfmg:raw_lithium_block"),
            halo = halo("minecraft:granite" to 2.0)
        ),
        "lead" to OreConfig(
            blocks = OreBlocks(
                "tfmg:lead_ore", "tfmg:deepslate_lead_ore",
                strip = listOf("createnuclear:lead_ore", "createnuclear:deepslate_lead_ore", "createnuclear:raw_lead_block")
            ),
            provinces = mapOf("frozen" to 1.0, "highlands" to 0.4, "arid" to 0.25),
            deposit = DepositConfig(
                shape = Shape(Kind.SHEET, dip = listOf(50.0, 85.0), warp = 0.35),
                spacing = 160, chance = 0.45, height = listOf(-10, 60),
                tiers = tiers(55.0, 45.0, length = 1.1, width = 0.7, thickness = 0.35)
            ),
            scatter = ScatterConfig(0.1, listOf(2, 5), listOf(-40, 60)),
            core = CoreConfig(0.10, "tfmg:raw_lead_block"),
            halo = halo("minecraft:tuff" to 2.0, "minecraft:calcite" to 1.0),
            outcrop = outcrop("minecraft:gravel" to 2.0, "minecraft:coarse_dirt" to 1.0)
        ),
        "uranium" to OreConfig(
            blocks = OreBlocks("createnuclear:uranium_ore", "createnuclear:deepslate_uranium_ore"),
            deposit = DepositConfig(
                shape = Shape(Kind.PODS, lumps = listOf(2, 4), warp = 0.3),
                tiers = tiers(100.0),
                anchor = Anchor("lead", emptyList(), 0.12, listOf(-10, 10), listOf(-8, 8), listOf(-10, 10))
            ),
            core = CoreConfig(0.05, "createnuclear:raw_uranium_block"),
            halo = halo("minecraft:granite" to 2.0)
        ),
        "nickel" to OreConfig(
            blocks = OreBlocks("tfmg:nickel_ore", "tfmg:deepslate_nickel_ore"),
            provinces = mapOf("wetland" to 1.0, "boreal" to 0.4),
            deposit = DepositConfig(
                shape = Shape(Kind.SHEET, dip = listOf(0.0, 25.0), warp = 0.3),
                spacing = 176, chance = 0.4, height = listOf(-50, 10),
                tiers = tiers(40.0, 60.0, length = 1.2, width = 1.2, thickness = 0.6)
            ),
            scatter = ScatterConfig(0.03, listOf(2, 4), listOf(-60, 20)),
            core = CoreConfig(0.15, "tfmg:raw_nickel_block"),
            halo = halo("minecraft:tuff" to 2.0, "minecraft:smooth_basalt" to 1.0)
        ),
        "zinc" to OreConfig(
            blocks = OreBlocks("create:zinc_ore", "create:deepslate_zinc_ore"),
            provinces = mapOf("wetland" to 0.9, "plains" to 0.3),
            deposit = DepositConfig(
                shape = Shape(Kind.SHEET, dip = listOf(40.0, 80.0), warp = 0.3),
                spacing = 144, chance = 0.45, height = listOf(-30, 60),
                tiers = tiers(50.0, 40.0, 10.0, length = 1.0, width = 0.7, thickness = 0.4)
            ),
            scatter = ScatterConfig(0.1, listOf(2, 5), listOf(-40, 60)),
            core = CoreConfig(0.10, "create:raw_zinc_block"),
            halo = halo("minecraft:calcite" to 2.0, "minecraft:tuff" to 1.0),
            outcrop = outcrop("minecraft:gravel" to 2.0, "minecraft:moss_block" to 1.0)
        ),
        "bauxite" to OreConfig(
            blocks = OreBlocks("tfmg:bauxite"),
            provinces = mapOf("wetland" to 1.0, "arid" to 0.4),
            deposit = DepositConfig(
                shape = Shape(Kind.SHEET, dip = listOf(0.0, 8.0), warp = 0.25),
                spacing = 144, chance = 0.45, height = listOf(30, 100), minDepth = 4,
                tiers = tiers(35.0, 45.0, 20.0, length = 1.1, width = 1.1, thickness = 0.4)
            ),
            halo = halo("minecraft:granite" to 1.0, "minecraft:andesite" to 1.0),
            outcrop = outcrop("minecraft:coarse_dirt" to 2.0, "minecraft:gravel" to 1.0)
        ),
        "galena" to OreConfig(
            blocks = OreBlocks("tfmg:galena"),
            provinces = mapOf("boreal" to 0.7, "highlands" to 0.4),
            deposit = DepositConfig(
                shape = Shape(Kind.SHEET, dip = listOf(55.0, 85.0), warp = 0.3),
                spacing = 160, chance = 0.4, height = listOf(-30, 50),
                tiers = tiers(60.0, 40.0, length = 1.0, width = 0.6, thickness = 0.35)
            ),
            halo = halo("minecraft:calcite" to 2.0, "minecraft:tuff" to 1.0),
            outcrop = outcrop("minecraft:gravel" to 2.0)
        ),
        "lignite" to OreConfig(
            blocks = OreBlocks("tfmg:lignite"),
            provinces = mapOf("boreal" to 0.6, "plains" to 0.5),
            deposit = DepositConfig(
                shape = Shape(Kind.SEAM, dip = listOf(0.0, 10.0), warp = 0.22, layers = 2),
                spacing = 112, chance = 0.45, height = listOf(35, 100), minDepth = 4,
                tiers = tiers(45.0, 40.0, 15.0, thickness = 0.4)
            ),
            halo = halo("minecraft:tuff" to 1.0),
            outcrop = outcrop("minecraft:coarse_dirt" to 2.0, "minecraft:gravel" to 1.0)
        ),
        "fireclay" to OreConfig(
            blocks = OreBlocks("tfmg:fireclay"),
            provinces = mapOf("plains" to 0.7, "wetland" to 0.4),
            deposit = DepositConfig(
                shape = Shape(Kind.SHEET, dip = listOf(0.0, 8.0), warp = 0.25),
                spacing = 128, chance = 0.45, height = listOf(40, 85), minDepth = 4,
                tiers = tiers(45.0, 40.0, 15.0, length = 1.0, width = 1.0, thickness = 0.35)
            ),
            outcrop = outcrop("minecraft:gravel" to 2.0, "minecraft:sand" to 1.0)
        ),
        "silver" to OreConfig(
            blocks = deep("silver"),
            provinces = mapOf("highlands" to 1.0, "boreal" to 0.5),
            deposit = DepositConfig(
                shape = Shape(Kind.SHEET, dip = listOf(60.0, 85.0), warp = 0.35),
                spacing = 160, chance = 0.4, height = listOf(-55, 0), limit = DEEP,
                tiers = tiers(60.0, 40.0, length = 0.9, width = 0.6, thickness = 0.35)
            ),
            scatter = ScatterConfig(0.05, listOf(2, 4), DEEP),
            halo = halo("minecraft:calcite" to 2.0, "minecraft:tuff" to 1.0)
        ),
        "antimony" to OreConfig(
            blocks = deep("antimony"),
            deposit = DepositConfig(
                shape = Shape(Kind.PODS, lumps = listOf(2, 4), warp = 0.25),
                limit = DEEP, tiers = tiers(70.0, 30.0),
                anchor = Anchor("silver", emptyList(), 0.35, listOf(-14, 14), listOf(-8, 8), listOf(-14, 14))
            ),
            halo = halo("minecraft:calcite" to 2.0)
        ),
        "chromium" to OreConfig(
            blocks = deep("chromite"),
            provinces = mapOf("arid" to 0.8, "highlands" to 0.6),
            deposit = DepositConfig(
                shape = Shape(Kind.BAND, dip = listOf(0.0, 10.0), warp = 0.2, bandWidth = 4.0, bandCut = 0.0),
                spacing = 176, chance = 0.4, height = listOf(-55, -5), limit = DEEP,
                tiers = tiers(40.0, 60.0, length = 1.2, width = 1.2, thickness = 0.5)
            ),
            halo = halo("minecraft:smooth_basalt" to 2.0, "minecraft:tuff" to 1.0)
        ),
        "cobalt" to OreConfig(
            blocks = deep("cobalt"),
            deposit = DepositConfig(
                shape = Shape(Kind.CLOUD, warp = 0.3, cut = 0.05),
                limit = DEEP, tiers = tiers(60.0, 40.0),
                anchor = Anchor("nickel", emptyList(), 0.3, listOf(-14, 14), listOf(-6, 6), listOf(-14, 14))
            ),
            halo = halo("minecraft:tuff" to 2.0, "minecraft:smooth_basalt" to 1.0)
        ),
        "platinum" to OreConfig(
            blocks = deep("platinum"),
            deposit = DepositConfig(
                shape = Shape(Kind.PODS, lumps = listOf(1, 3), warp = 0.2),
                height = listOf(-58, -10), limit = DEEP, tiers = tiers(70.0, 30.0),
                anchor = Anchor("nickel", emptyList(), 0.15, listOf(-10, 10), listOf(0, 0), listOf(-10, 10), absoluteY = true)
            ),
            core = CoreConfig(0.10, "chemica:raw_platinum_block"),
            halo = halo("minecraft:smooth_basalt" to 2.0, "minecraft:tuff" to 1.0)
        ),
        "molybdenum" to OreConfig(
            blocks = deep("molybdenum"),
            deposit = DepositConfig(
                shape = Shape(Kind.CLOUD, warp = 0.3, cut = 0.1),
                height = listOf(-58, -8), limit = DEEP, density = 0.7, tiers = tiers(50.0, 50.0),
                anchor = Anchor("copper", listOf("medium", "large"), 0.3, listOf(-20, 20), listOf(0, 0), listOf(-20, 20), absoluteY = true)
            ),
            halo = halo("minecraft:tuff" to 2.0, "minecraft:andesite" to 1.0)
        ),
        "vanadium" to OreConfig(
            blocks = deep("vanadium"),
            deposit = DepositConfig(
                shape = Shape(Kind.PODS, lumps = listOf(2, 4), warp = 0.25),
                height = listOf(-58, -15), limit = DEEP, tiers = tiers(60.0, 40.0),
                anchor = Anchor("iron", listOf("medium", "large"), 0.25, listOf(-16, 16), listOf(0, 0), listOf(-16, 16), absoluteY = true)
            ),
            halo = halo("minecraft:granite" to 2.0, "minecraft:andesite" to 1.0)
        ),
        "tungsten" to OreConfig(
            blocks = deep("wolframite"),
            deposit = DepositConfig(
                shape = Shape(Kind.SHEET, dip = listOf(60.0, 90.0), warp = 0.25),
                height = listOf(-58, -12), limit = DEEP,
                tiers = tiers(60.0, 40.0, length = 0.9, width = 0.6, thickness = 0.3),
                anchor = Anchor("tin", emptyList(), 0.35, listOf(-10, 10), listOf(0, 0), listOf(-10, 10), absoluteY = true)
            ),
            halo = halo("minecraft:granite" to 2.0, "minecraft:diorite" to 1.0)
        ),
        "rutile" to OreConfig(
            blocks = deep("rutile"),
            provinces = mapOf("wetland" to 0.8, "boreal" to 0.5),
            deposit = DepositConfig(
                shape = Shape(Kind.SHEET, dip = listOf(0.0, 15.0), warp = 0.3),
                spacing = 176, chance = 0.4, height = listOf(-45, -15), limit = DEEP,
                tiers = tiers(45.0, 55.0, length = 1.1, width = 1.1, thickness = 0.4)
            ),
            halo = halo("minecraft:tuff" to 2.0, "minecraft:calcite" to 1.0)
        ),
        "fluorite" to OreConfig(
            blocks = deep("fluorite"),
            provinces = mapOf("arid" to 0.7, "highlands" to 0.6),
            deposit = DepositConfig(
                shape = Shape(Kind.PODS, lumps = listOf(3, 6), warp = 0.25),
                spacing = 144, chance = 0.45, height = listOf(-50, 6), limit = DEEP,
                tiers = tiers(55.0, 45.0)
            ),
            scatter = ScatterConfig(0.08, listOf(3, 6), DEEP),
            halo = halo("minecraft:calcite" to 2.0, "minecraft:diorite" to 1.0)
        ),
        "graphite" to OreConfig(
            blocks = deep("graphite"),
            provinces = mapOf("boreal" to 0.7, "highlands" to 0.5),
            deposit = DepositConfig(
                shape = Shape(Kind.SEAM, dip = listOf(0.0, 15.0), warp = 0.22),
                spacing = 128, chance = 0.45, height = listOf(-45, 4), limit = DEEP,
                tiers = tiers(50.0, 40.0, 10.0, thickness = 0.4)
            ),
            scatter = ScatterConfig(0.1, listOf(4, 8), DEEP),
            halo = halo("minecraft:tuff" to 2.0, "minecraft:smooth_basalt" to 1.0)
        ),
        "phosphorus" to OreConfig(
            blocks = OreBlocks("chemica:phosphorus_ore", "chemica:deepslate_phosphorus_ore"),
            provinces = mapOf("wetland" to 1.0, "plains" to 0.6),
            deposit = DepositConfig(
                shape = Shape(Kind.SHEET, dip = listOf(0.0, 8.0), warp = 0.25),
                spacing = 144, chance = 0.45, height = listOf(10, 70), minDepth = 4,
                tiers = tiers(45.0, 40.0, 15.0, length = 1.1, width = 1.1, thickness = 0.4)
            ),
            scatter = ScatterConfig(0.15, listOf(3, 6), listOf(-40, 60)),
            halo = halo("minecraft:calcite" to 1.0, "minecraft:tuff" to 1.0),
            outcrop = outcrop("minecraft:gravel" to 2.0, "minecraft:sand" to 1.0)
        ),
        "sulfur" to OreConfig(
            blocks = OreBlocks("createbigpharma:sulfur_ore", "createbigpharma:deepslate_sulfur_ore"),
            provinces = mapOf("arid" to 1.0, "highlands" to 0.5),
            deposit = DepositConfig(
                shape = Shape(Kind.PODS, lumps = listOf(3, 6), warp = 0.3),
                spacing = 128, chance = 0.45, height = listOf(-40, 45),
                tiers = tiers(45.0, 40.0, 15.0)
            ),
            scatter = ScatterConfig(0.15, listOf(3, 6), listOf(-48, 32)),
            halo = halo("minecraft:smooth_basalt" to 2.0, "minecraft:tuff" to 2.0, "minecraft:calcite" to 1.0),
            outcrop = outcrop("minecraft:sand" to 2.0, "minecraft:gravel" to 1.0)
        ),
        "salt" to OreConfig(
            blocks = OreBlocks("createbigpharma:salt_ore", "createbigpharma:deepslate_salt_ore"),
            provinces = mapOf("arid" to 1.0, "wetland" to 0.6),
            deposit = DepositConfig(
                shape = Shape(Kind.SHEET, dip = listOf(0.0, 6.0), warp = 0.25),
                spacing = 128, chance = 0.45, height = listOf(20, 70), minDepth = 4,
                tiers = tiers(35.0, 45.0, 20.0, length = 1.1, width = 1.1, thickness = 0.4)
            ),
            scatter = ScatterConfig(0.2, listOf(3, 6), listOf(-16, 72)),
            halo = halo("minecraft:calcite" to 2.0, "minecraft:sand" to 1.0),
            outcrop = outcrop("minecraft:sand" to 2.0, "minecraft:gravel" to 1.0)
        ),
        "diamond" to OreConfig(
            blocks = OreBlocks("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"),
            deposit = DepositConfig(
                shape = Shape(Kind.PIPE, dip = listOf(0.0, 0.0), warp = 0.2, taper = 0.65, lean = 0.2),
                density = 0.6, height = listOf(-60, -32), limit = listOf(-60, -32),
                tiers = listOf(
                    Tier("small", 65.0, listOf(10, 16), listOf(10, 16), listOf(36, 56)),
                    Tier("medium", 35.0, listOf(16, 24), listOf(16, 24), listOf(50, 80))
                ),
                anchor = Anchor("coal", listOf("large", "motherlode"), 0.07, absoluteY = true, offsetX = listOf(-18, 18), offsetY = listOf(0, 0), offsetZ = listOf(-18, 18))
            ),
            scatter = ScatterConfig(0.006, listOf(1, 2), listOf(-60, -32), Distribution.TRIANGLE),
            core = CoreConfig(0.01, "minecraft:diamond_block", radius = 0.2),
            halo = HaloConfig(listOf(Weighted("minecraft:tuff", 3.0), Weighted("minecraft:smooth_basalt", 1.0)), scale = 1.3)
        )
    ).mapValues { (_, config) -> homed(config) }

    private fun deep(name: String) = OreBlocks("chemica:deepslate_${name}_ore")

    private fun homed(config: OreConfig): OreConfig {
        val scatter = config.scatter ?: return config
        if (scatter.provinces != null || config.provinces.isEmpty()) return config
        return config.copy(scatter = scatter.copy(provinces = config.provinces.keys.toList()))
    }

    fun tiers(
        small: Double = 0.0, medium: Double = 0.0, large: Double = 0.0, motherlode: Double = 0.0,
        length: Double = 1.0, width: Double = 1.0, thickness: Double = 1.0
    ): List<Tier> = base.zip(listOf(small, medium, large, motherlode)).filter { it.second > 0.0 }.map { (row, weight) ->
        Tier(row.first, weight, scaled(row.second, length), scaled(row.third.first, width), scaled(row.third.second, thickness))
    }

    private fun scaled(range: List<Int>, factor: Double) = range.map { (it * factor).roundToInt().coerceAtLeast(2) }

    fun halo(vararg blocks: Pair<String, Double>) = HaloConfig(blocks.map { Weighted(it.first, it.second) })

    fun outcrop(vararg blocks: Pair<String, Double>) = OutcropConfig(blocks.map { Weighted(it.first, it.second) })
}
