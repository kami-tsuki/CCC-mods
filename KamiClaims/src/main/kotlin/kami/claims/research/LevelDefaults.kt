package kami.claims.research

import kami.libs.claims.FeatureIds

object LevelDefaults {
    val capacities: Map<Capacity, Int> = mapOf(
        Capacity.CHUNKS to 36,
        Capacity.PROVINCES to 0,
        Capacity.CITIZENS to 1,
        Capacity.RESEARCH_SLOTS to 1,
        Capacity.QUEUE_SLOTS to 1,
        Capacity.TREASURY to 10_000,
        Capacity.OFFICERS to 0,
        Capacity.MARKET_SLOTS to 5,
        Capacity.AUCTION_SLOTS to 5,
        Capacity.FREE_CHUNKS to 0,
        Capacity.PLOTS to 25,
        Capacity.JOB_SLOTS to 1
    )

    val sources: Map<String, XpSource> = mapOf(
        "research" to XpSource(0.1),
        "tasks" to XpSource(20.0, 300),
        "taxes" to XpSource(0.2, 300),
        "claims" to XpSource(1.0, 150),
        "playtime" to XpSource(15.0, 300),
        "trade" to XpSource(1.0, 300),
        "auction_sold" to XpSource(100.0, 500),
        "rent" to XpSource(100.0, 500),
        "wage" to XpSource(10.0, 100),
        "prospect" to XpSource(2.0, 40),
        "deposit_found" to XpSource(25.0, 100),
        "alliance" to XpSource(150.0, 300),
        "province" to XpSource(150.0, 300),
        "citizen" to XpSource(25.0, 100),
        "discovery" to XpSource(50.0, 200)
    )

    val counted: List<String> = listOf("plot", "wage", "alliance", "province", "prospect", "deposit_found")

    val rules: Map<Int, LevelRule> = mapOf(
        2 to rule(MinChunks(9)),
        3 to rule(MinChunks(1, "mining"), MinChunks(1, "forestry"), MinChunks(1, "farming")),
        4 to rule(MinChunks(2, "residential")),
        5 to rule(MinChunks(12), MinPlots(1)),
        6 to rule(MinCounter(Counters.TREES_GROWN, 10)),
        7 to rule(MinChunks(16), MinChunks(3, "factory")),
        8 to rule(MinTreasury(512), MinCounter(Counters.TAXES_COLLECTED, 64)),
        9 to rule(MinChunks(25)),
        10 to rule(MinCitizens(2), FlagSet, MinTreasury(1000))
    )

    const val VERSION = 1

    private val housingRewards: Map<Int, List<Unlock>> = mapOf(
        13 to listOf(FeatureUnlock(Features.PLOTS_FAMILY)),
        23 to listOf(FeatureUnlock(Features.PLOTS_ALLIES)),
        27 to listOf(FeatureUnlock(Features.PLOTS_PUBLIC))
    )

    private val jobSlotRewards: Map<Int, List<Unlock>> = listOf(16, 61, 125).associateWith { listOf(add(Capacity.JOB_SLOTS, 1)) }

    private fun merge(base: Map<Int, List<Unlock>>, extra: Map<Int, List<Unlock>>) =
        (base.keys + extra.keys).sorted().associateWith { base[it].orEmpty() + extra[it].orEmpty() }

    val rewards: Map<Int, List<Unlock>> = merge(merge(baseRewards(), housingRewards), jobSlotRewards)

    fun upgrade(config: LevelsConfig): LevelsConfig {
        if (config.version >= VERSION) return config
        val unlocks = config.rewards.values.flatten()
        val features = unlocks.filterIsInstance<FeatureUnlock>().map { it.id }.toSet()
        val housing = housingRewards.filterValues { list -> list.filterIsInstance<FeatureUnlock>().none { it.id in features } }
        val jobSlots = if (unlocks.any { it is CapacityUnlock && it.key == Capacity.JOB_SLOTS }) emptyMap() else jobSlotRewards
        val plots = if (config.capacities[Capacity.PLOTS] == 4) Capacity.PLOTS to 25 else null
        return config.copy(version = VERSION, capacities = config.capacities + listOfNotNull(plots), rewards = merge(merge(config.rewards, housing), jobSlots))
    }

    private fun baseRewards(): Map<Int, List<Unlock>> = mapOf(
        2 to listOf(add(Capacity.CITIZENS, 3)),
        3 to listOf(add(Capacity.QUEUE_SLOTS, 1), FeatureUnlock(Features.claimType("residential"))),
        4 to listOf(add(Capacity.CITIZENS, 6), FeatureUnlock(Features.claimType("factory"))),
        5 to listOf(TokenUnlock(Tokens.RENAME)),
        6 to listOf(add(Capacity.FREE_CHUNKS, 1), add(Capacity.CHUNKS, 12)),
        7 to listOf(add(Capacity.TREASURY, 40_000)),
        8 to listOf(FeatureUnlock(Features.CHANCELLOR), add(Capacity.CITIZENS, 10)),
        9 to listOf(add(Capacity.OFFICERS, 2), add(Capacity.CHUNKS, 16)),
        10 to listOf(
            add(Capacity.CITIZENS, 16), add(Capacity.CHUNKS, 32), FeatureUnlock(Features.claimType("wilderness")), FeatureUnlock(Features.claimType("infrastructure")), FeatureUnlock(Features.BANISH)
        ),
        11 to listOf(add(Capacity.OFFICERS, 1), add(Capacity.PROVINCES, 1)),
        12 to listOf(add(Capacity.QUEUE_SLOTS, 1)),
        13 to listOf(add(Capacity.CITIZENS, 5)),
        14 to listOf(add(Capacity.CHUNKS, 64)),
        16 to listOf(TokenUnlock(Tokens.CAPITAL_MOVE)),
        17 to listOf(add(Capacity.TREASURY, 50_000)),
        18 to listOf(add(Capacity.MARKET_SLOTS, 3)),
        19 to listOf(add(Capacity.AUCTION_SLOTS, 3)),
        20 to listOf(add(Capacity.TREASURY, 400_000), add(Capacity.CHUNKS, 64), add(Capacity.CITIZENS, 8)),
        21 to listOf(FeatureUnlock(Features.ALLIANCES)),
        22 to listOf(add(Capacity.PROVINCES, 1)),
        23 to listOf(FeatureUnlock(Features.EMBARGOES)),
        24 to listOf(add(Capacity.FREE_CHUNKS, 3)),
        25 to listOf(FeatureUnlock(Features.claimType("market")), FeatureUnlock(FeatureIds.VENDORS)),
        30 to listOf(add(Capacity.RESEARCH_SLOTS, 1), add(Capacity.TREASURY, 500_000)),
        35 to listOf(add(Capacity.TREASURY, 500_000)),
        40 to listOf(add(Capacity.TREASURY, 500_000)),
        45 to listOf(add(Capacity.TREASURY, 250_000)),
        50 to listOf(add(Capacity.TREASURY, 250_000)),
        55 to listOf(add(Capacity.TREASURY, 2_500_000)),
        60 to listOf(add(Capacity.RESEARCH_SLOTS, 1), add(Capacity.TREASURY, 5_000_000)),
        65 to listOf(add(Capacity.TREASURY, 10_000_000)),
        70 to listOf(add(Capacity.TREASURY, 20_000_000)),
        75 to listOf(add(Capacity.TREASURY, 35_000_000)),
        80 to listOf(add(Capacity.TREASURY, 50_000_000)),
        85 to listOf(add(Capacity.TREASURY, 75_000_000)),
        90 to listOf(add(Capacity.TREASURY, 150_000_000)),
        95 to listOf(add(Capacity.TREASURY, 250_000_000)),
        100 to listOf(add(Capacity.TREASURY, 400_000_000))
    )

    private fun rule(vararg requires: Condition) = LevelRule(requires = requires.toList())

    private fun add(key: Capacity, amount: Int) = CapacityUnlock(key, amount)
}
