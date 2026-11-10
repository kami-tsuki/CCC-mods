package kami.economy

import kami.libs.claims.Boost
import kami.libs.config.ConfigModule
import kami.libs.config.Section
import kami.libs.util.Time.DAY_MS
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class StarterGood(
    val item: String, val base: Int, val lot: Int = 64, val depth: Int = 32, val supply: Int = 0, val demand: Int = 0,
    val floor: Int? = null, val ceiling: Int? = null, val spread: Double? = null, val sellCap: Int = 0, val resell: Boolean = true,
    val target: Int? = null, val dailyCap: Int? = null, val infinite: Boolean? = null, val max: Int? = null
) {
    fun normalized(): StarterGood {
        val clean = copy(target = null, dailyCap = null, infinite = null, max = null)
        return when {
            infinite == true -> clean.copy(supply = 0, demand = -1, resell = false, sellCap = dailyCap ?: 3, floor = floor ?: maxOf(1, base / 2))
            target != null -> clean.copy(supply = target, demand = max ?: (target + depth * 0.7).toInt(), sellCap = dailyCap ?: sellCap)
            else -> clean.copy(sellCap = dailyCap ?: sellCap)
        }
    }
}

@Serializable
data class BoostRange(val minPct: Int, val maxPct: Int, val minHours: Int, val maxHours: Int)

@Serializable
data class QuestSettings(
    val enabled: Boolean = true,
    val namespaces: List<String> = listOf("minecraft", "farmersdelight", "createfood", "*delight*"),
    val seed: Int = 0,
    val minGapMinutes: Int = 60,
    val maxGapMinutes: Int = 120,
    val maxActive: Int = 5,
    val hours: List<Int> = listOf(6, 12, 24, 48, 96),
    val pityHours: List<List<Int>> = listOf(listOf(14, 44), listOf(150, 250), listOf(690, 990)),
    val uncommonShare: Double = 0.3,
    val minTier: List<Int> = listOf(1, 1, 2, 3, 4),
    val amounts: List<List<Int>> = listOf(listOf(128, 512), listOf(512, 2048), listOf(2048, 8192), listOf(8192, 32768), listOf(32768, 131072)),
    val minusShare: Double = 0.25,
    val minusMax: Double = 0.45,
    val offsetMean: Double = 0.25,
    val offsetSpread: Double = 0.08,
    val offsetMin: Double = 0.05,
    val offsetMax: Double = 0.5,
    val cooldownDays: Double = 3.0,
    val bankFloor: Long = -1_000_000,
    val research: List<BoostRange> = listOf(BoostRange(5, 10, 1, 3), BoostRange(5, 15, 1, 5), BoostRange(10, 20, 2, 10), BoostRange(20, 30, 5, 10), BoostRange(25, 50, 6, 12)),
    val xp: List<BoostRange> = listOf(BoostRange(10, 20, 1, 3), BoostRange(10, 30, 2, 5), BoostRange(20, 50, 4, 8), BoostRange(40, 80, 6, 12), BoostRange(50, 100, 10, 20))
) {
    fun sane(): QuestSettings {
        fun five(l: List<Int>, d: List<Int>) = if (l.size == 5 && l.all { it >= 0 }) l else d
        val d = QuestSettings()
        return copy(
            minGapMinutes = minGapMinutes.coerceIn(1, 1440),
            maxGapMinutes = maxGapMinutes.coerceIn(minGapMinutes.coerceIn(1, 1440), 2880),
            maxActive = maxActive.coerceIn(0, 20),
            hours = five(hours, d.hours).map { it.coerceIn(1, MAX_HOURS) },
            pityHours = pityHours.takeIf { p -> p.size == 3 && p.all { it.size == 2 && it[0] in 1..it[1] } } ?: d.pityHours,
            uncommonShare = uncommonShare.coerceIn(0.0, 1.0),
            minTier = five(minTier, d.minTier).map { it.coerceIn(1, 5) },
            amounts = amounts.takeIf { a -> a.size == 5 && a.all { it.size == 2 && it[0] in 1..it[1] } } ?: d.amounts,
            minusShare = minusShare.coerceIn(0.0, 1.0),
            minusMax = minusMax.coerceIn(0.0, 1.0),
            offsetMin = offsetMin.coerceIn(0.0, 0.9),
            offsetMax = offsetMax.coerceIn(offsetMin.coerceIn(0.0, 0.9), 0.9),
            offsetMean = offsetMean.coerceIn(0.0, 0.9),
            offsetSpread = offsetSpread.coerceIn(0.0, 0.5),
            cooldownDays = cooldownDays.coerceIn(0.0, 90.0),
            research = research.takeIf { it.size == 5 && it.all(BoostRange::valid) } ?: d.research,
            xp = xp.takeIf { it.size == 5 && it.all(BoostRange::valid) } ?: d.xp
        )
    }

    companion object {
        const val MAX_HOURS = 720
    }
}

@Serializable
data class VillagerSettings(
    val enabled: Boolean = true,
    val spreadPct: Double = 0.05,
    val demandCap: Double = 0.5,
    val impact: Double = 0.25,
    val unknownMul: Double = 3.0,
    val buySeedMul: Double = 0.5,
    val craftMul: Double = 1.25,
    val decayPct: Double = 0.04,
    val minPrice: Int = 1,
    val emeraldPrice: Double = 24.0
) {
    fun sane() = copy(
        spreadPct = spreadPct.coerceIn(0.0, 0.5),
        demandCap = demandCap.coerceIn(0.0, 5.0),
        impact = impact.coerceIn(0.0, 1.0),
        unknownMul = unknownMul.coerceIn(1.0, 20.0),
        buySeedMul = buySeedMul.coerceIn(0.05, 1.0),
        craftMul = craftMul.coerceIn(1.0, 5.0),
        decayPct = decayPct.coerceIn(0.0, 0.5),
        minPrice = minPrice.coerceAtLeast(1),
        emeraldPrice = emeraldPrice.coerceIn(0.0, 1_000_000.0)
    )
}

private fun BoostRange.valid() = minPct in 1..maxPct && maxPct <= Boost.MAX_PCT && minHours in 1..maxHours && maxHours <= Boost.MAX_HOURS

@Serializable
data class Settings(
    val marketTickInterval: Int = 60 * 20,
    val priceDeltaThresholdPct: Double = 0.01,

    val sellTaxPct: Double = 0.10,
    val allyTaxRate: Double = 0.08,
    val maxPrice: Int = 1_000_000,
    val maxAmount: Int = 10_000,
    val marketSlots: Int = 5,
    val auctionSlots: Int = 5,
    val lots: Map<String, Int> = emptyMap(),
    val auctionFeePct: Double = 0.05,
    val auctionDurationMillis: Long = 3 * DAY_MS,
    val auctionCheckIntervalTicks: Int = 20 * 60,

    val starterGoods: List<StarterGood> = emptyList(),
    val catalog: Int = 0,
    val serverSpread: Double = 0.05,
    val courseDepth: Int = 32,
    val courseClip: Double = 0.05,

    val creativeItemIds: List<String> = listOf(
        "minecraft:barrier", "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
        "minecraft:command_block_minecart", "minecraft:debug_stick", "minecraft:structure_block", "minecraft:structure_void",
        "minecraft:jigsaw", "minecraft:light", "minecraft:knowledge_book", "minecraft:spawner"
    ),
    val storageItemIds: List<String> = listOf("minecraft:shulker_box", "minecraft:bundle"),

    val historyFiveMinRetention: Int = 288,
    val historyHourlyRetention: Int = 24 * 30,
    val historyDailyRetention: Int = 400,

    val listLimit: Int = 300,
    val guiCooldown: Int = 4,

    val allowCreativeVendors: Boolean = false,
    val levelLocks: Boolean = true,

    val discordMoveThresholdPct: Double = 10.0,
    val discordItemCooldownMinutes: Int = 60,
    val discordDigestHours: Int = 6,
    val discordAlertCooldownMinutes: Int = 60,
    val discordItemIconUrl: String = "",

    val quests: QuestSettings = QuestSettings(),
    val villagers: VillagerSettings = VillagerSettings()
) {
    val storageItemSet: Set<String> by lazy { storageItemIds.toHashSet() }
    val creativeItemSet: Set<String> by lazy { creativeItemIds.toHashSet() }

    val taxPct: Int get() = (sellTaxPct * 100).roundToInt()
    val allyTaxPct: Int get() = (allyTaxRate * 100).roundToInt()
    val auctionFeeBps: Int get() = (auctionFeePct * 10_000).roundToInt()

    fun lotOf(item: String): Int = kami.economy.economy.Variants.of(item)?.let { kami.economy.economy.Variants.lot(item, lotOf(it.core)) } ?: (lots[item] ?: kami.economy.economy.Stocks.good(item)?.lot ?: kami.economy.villager.VillagerCourses.lot(item))?.takeIf { it in 1..maxAmount } ?: 1

    fun validPrice(price: Int) = price in 1..maxPrice
    fun validAmount(amount: Int) = amount in 1..maxAmount
}

internal fun Settings.sane(): Settings = copy(
    marketTickInterval = marketTickInterval.coerceAtLeast(20),
    auctionDurationMillis = auctionDurationMillis.coerceIn(60_000L, 365 * DAY_MS),
    guiCooldown = guiCooldown.coerceIn(1, 200),
    sellTaxPct = sellTaxPct.coerceIn(0.0, 0.9),
    allyTaxRate = allyTaxRate.coerceIn(0.0, 0.9),
    auctionFeePct = auctionFeePct.coerceIn(0.0, 0.9),
    listLimit = listLimit.coerceIn(20, 2000),
    discordMoveThresholdPct = discordMoveThresholdPct.coerceAtLeast(0.1),
    discordItemCooldownMinutes = discordItemCooldownMinutes.coerceAtLeast(1),
    discordDigestHours = discordDigestHours.coerceAtLeast(0),
    discordAlertCooldownMinutes = discordAlertCooldownMinutes.coerceAtLeast(1),
    discordItemIconUrl = discordItemIconUrl.trim(),
    maxPrice = maxPrice.coerceIn(1, 100_000_000),
    maxAmount = maxAmount.coerceIn(1, 1_000_000),
    serverSpread = serverSpread.coerceIn(0.0, 0.5),
    courseDepth = courseDepth.coerceAtLeast(1),
    courseClip = courseClip.coerceIn(0.01, 1.0),
    priceDeltaThresholdPct = priceDeltaThresholdPct.coerceIn(0.0, 1.0),
    auctionCheckIntervalTicks = auctionCheckIntervalTicks.coerceAtLeast(20),
    quests = quests.sane(),
    villagers = villagers.sane(),
    starterGoods = starterGoods.map { it.normalized() }.filter { g -> g.valid().also { ok -> if (!ok) LOG.warn("Dropped invalid starter good {}", g.item) } }
)

private val sections = listOf(
    Section(
        "market.json", "Market prices and sales tax (spurs).",
        mapOf(
            "marketTickInterval" to "Ticks between price updates (1200 = 1 min).",
            "priceDeltaThresholdPct" to "Smallest price change (0-1) pushed to players at once.",
            "sellTaxPct" to "Sales tax, 0-0.9.",
            "allyTaxRate" to "Tax between allied countries or one family, 0-0.9.",
            "maxPrice" to "Max price per lot.",
            "maxAmount" to "Max items per trade.",
            "marketSlots" to "Order slots per player (without country bonus).",
            "lots" to "Items per lot by item id (default 1). Prices are per lot."
        )
    ),
    Section(
        "auctions.json", "The auction house for unique items.",
        mapOf(
            "auctionSlots" to "Auction slots per player (without country bonus).",
            "auctionFeePct" to "Auction fee, 0-0.9.",
            "auctionDurationMillis" to "Auction length in ms (259200000 = 3 days).",
            "auctionCheckIntervalTicks" to "Ticks between auction-end checks."
        )
    ),
    Section(
        "starter-items.json", "Goods the market buys and sells from its own stock, so new players can earn first coins.",
        mapOf(
            "starterGoods" to "Item id or #tag with base (price per lot), lot, depth, supply, demand (lots, -1 = infinite), optional floor, ceiling, spread, sellCap (lots per player per 24h, 0 = unlimited), resell (false = bought lots are not restocked).",
            "catalog" to "Starter catalog version already merged. Removed goods stay removed.",
            "serverSpread" to "Spread the market adds when selling and subtracts when buying, 0-0.5.",
            "courseDepth" to "Depth in lots for non-starter items.",
            "courseClip" to "Max course pull of one trade, 0.01-1."
        )
    ),
    Section(
        "quests.json", "Timed server buy orders for food, paid tax-free from the server bank, plus a country boost.",
        mapOf(
            "quests.enabled" to "Spawn new quests.",
            "quests.namespaces" to "Namespaces whose food can become quests and gets a seeded course. * = wildcard.",
            "quests.seed" to "Last recipe seeding. Set 0 to seed new dishes on next start.",
            "quests.minGapMinutes" to "Min minutes between quest spawns.",
            "quests.maxGapMinutes" to "Max minutes between quest spawns.",
            "quests.maxActive" to "Max open quests.",
            "quests.hours" to "Hours per rarity (common to legendary).",
            "quests.pityHours" to "[min, max] hours since last rare/mythic/legendary. Impossible before min, certain at max.",
            "quests.uncommonShare" to "Uncommon share of common+uncommon spawns, 0-1.",
            "quests.minTier" to "Lowest tier (1-5) per rarity.",
            "quests.amounts" to "[min, max] items per rarity for tier 1; each tier halves it.",
            "quests.minusShare" to "Base share priced below course, 0-1. Overdemand raises it to minusMax.",
            "quests.minusMax" to "Max share priced below course.",
            "quests.offsetMean" to "Typical offset from course (0.25 = 25%).",
            "quests.offsetSpread" to "Random spread around offsetMean.",
            "quests.offsetMin" to "Min offset.",
            "quests.offsetMax" to "Max offset.",
            "quests.cooldownDays" to "Days until a used item is fully likely again.",
            "quests.bankFloor" to "Minimum server bank balance after a quest payout; the amount shrinks or the spawn is skipped.",
            "quests.research" to "Research boost per rarity: minPct, maxPct (5% steps), minHours, maxHours.",
            "quests.xp" to "Country XP boost per rarity: minPct, maxPct (10% steps), minHours, maxHours."
        )
    ),
    Section(
        "villagers.json", "Villagers trade for money at the market course instead of emeralds.",
        mapOf(
            "villagers.enabled" to "Replace emerald trades with money trades paid by bank card.",
            "villagers.spreadPct" to "Max per-villager offset from the course, 0-0.5 (0.05 = +-5%).",
            "villagers.demandCap" to "Max surcharge from vanilla demand, 0-5 (0.5 = +50%).",
            "villagers.impact" to "Course pull of one villager trade, in market lots, 0-1.",
            "villagers.unknownMul" to "Start price factor for unknown goods a villager sells.",
            "villagers.buySeedMul" to "Start price factor for unknown goods a villager buys.",
            "villagers.craftMul" to "Factor on the recipe cost when it seeds a price.",
            "villagers.decayPct" to "Daily drop of a start price nobody traded yet, 0-0.5.",
            "villagers.minPrice" to "Smallest price of one villager trade.",
            "villagers.emeraldPrice" to "Value of one emerald for start prices when emeralds are no starter good. 0 = unknown goods stay unpriced."
        )
    ),
    Section(
        "blocked-items.json", "Items kept out of the market. library/coins.json coins are always blocked.",
        mapOf(
            "creativeItemIds" to "Items that can never be traded.",
            "storageItemIds" to "Containers sold only at auction."
        )
    ),
    Section(
        "general.json", "Price history, GUI and vendor settings.",
        mapOf(
            "historyFiveMinRetention" to "5-minute points kept per item (288 = 1 day).",
            "historyHourlyRetention" to "Hourly points kept per item (720 = 30 days).",
            "historyDailyRetention" to "Daily points kept per item.",
            "listLimit" to "Max rows per list, 20-2000.",
            "guiCooldown" to "Ticks between GUI actions per player.",
            "allowCreativeVendors" to "Allow the Numismatics creative vendor.",
            "levelLocks" to "Enforce country-level locks economy:auctions and economy:vendors. Unmentioned features stay unlocked.",
            "discordMoveThresholdPct" to "Price move (%) since the last Discord post that posts the item and alerts watchers.",
            "discordItemCooldownMinutes" to "Minutes before an item may be posted to Discord again.",
            "discordDigestHours" to "Hours between market digests. 0 = off.",
            "discordAlertCooldownMinutes" to "Minutes between price-alert DMs per player.",
            "discordItemIconUrl" to "Optional https icon URL with {namespace} and {path}, e.g. https://example.com/icons/{namespace}/{path}.png. Empty = no icons."
        )
    )
)

object Config : ConfigModule<Settings>("economy", Settings.serializer(), Settings(), sections, "kami_economy.json", { it.sane() })

private fun StarterGood.valid(): Boolean =
    base > 0 && lot > 0 && depth > 0 && supply >= -1 && demand >= -1 && sellCap >= 0 && (floor ?: 1) >= 1 && (ceiling ?: Int.MAX_VALUE) >= (floor ?: maxOf(1, base / 2)) && (spread ?: 0.0) in 0.0..0.5
