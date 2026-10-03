package kami.economy

import kami.libs.config.ConfigModule
import kami.libs.config.Section
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class CategoryBounds(val floor: Int? = null, val ceiling: Int? = null, val maxMovePct: Double? = null)

@Serializable
data class StarterGood(val item: String, val base: Int, val lot: Int = 64, val target: Int = 64, val depth: Int = 32, val dailyCap: Int? = null, val infinite: Boolean = false)

private fun starters(base: Int, vararg items: String) = items.map { StarterGood(it, base) }

/** Goods the market always buys at a fixed price, up to [cap] lots per player per day; it never sells them back. */
private fun unlimited(base: Int, lot: Int, cap: Int, vararg items: String) = items.map { StarterGood(it, base, lot = lot, dailyCap = cap, infinite = true) }

/** Crops sell infinitely, 3 stacks per player per day; [base] is the price of one stack of 64. */
private fun crops(base: Int, vararg items: String) = unlimited(base, 64, 3, *items)

@Serializable
data class Settings(
    val marketTickInterval: Int = 60 * 20,
    val priceDeltaThresholdPct: Double = 0.01,
    val maxPriceMovePct: Double = 0.05,
    val defaultFloor: Int = 1,
    val defaultCeiling: Int? = null,
    val categoryBounds: Map<String, CategoryBounds> = emptyMap(),

    val sellTaxPct: Double = 0.10,
    val allyTaxRate: Double = 0.08,
    val maxPrice: Int = 1_000_000,
    val maxAmount: Int = 10_000,
    val marketSlots: Int = 5,
    val auctionSlots: Int = 5,
    val lots: Map<String, Int> = emptyMap(),
    val auctionFeePct: Double = 0.05,
    val auctionDurationMillis: Long = 3 * 24 * 60 * 60 * 1000L,
    val auctionCheckIntervalTicks: Int = 20 * 60,

    val starterGoods: List<StarterGood> = starters(20, "#minecraft:logs", "minecraft:baked_potato") +
        starters(15, "minecraft:bread") +
        starters(40, "#c:ingots/iron", "#c:ingots/copper") +
        starters(50, "#c:ingots/zinc", "#c:ingots/lead") +
        starters(80, "#c:ingots/gold") +
        // Bulk field crops
        crops(6, "minecraft:wheat", "minecraft:sugar_cane", "minecraft:melon_slice", "minecraft:sweet_berries", "farmersdelight:rice", "croptopia:barley", "croptopia:oat", "croptopia:corn", "croptopia:rice", "croptopia:soybean") +
        // Staple vegetables
        crops(8, "minecraft:carrot", "minecraft:potato", "minecraft:beetroot", "minecraft:glow_berries", "farmersdelight:cabbage", "farmersdelight:tomato", "farmersdelight:onion", "croptopia:lettuce", "croptopia:cabbage", "croptopia:onion", "croptopia:garlic", "croptopia:radish", "croptopia:turnip", "croptopia:rutabaga", "croptopia:spinach", "croptopia:kale", "croptopia:celery", "croptopia:leek", "croptopia:greenonion", "croptopia:cucumber", "croptopia:zucchini", "croptopia:squash", "croptopia:greenbean", "croptopia:blackbean", "croptopia:peanut", "croptopia:tomato", "croptopia:tomatillo", "croptopia:sweetpotato", "croptopia:yam", "croptopia:mustard") +
        // Garden vegetables, berries and vines
        crops(10, "croptopia:bellpepper", "croptopia:chile_pepper", "croptopia:eggplant", "croptopia:broccoli", "croptopia:cauliflower", "croptopia:asparagus", "croptopia:artichoke", "croptopia:strawberry", "croptopia:blueberry", "croptopia:blackberry", "croptopia:raspberry", "croptopia:cranberry", "croptopia:currant", "croptopia:elderberry", "croptopia:grape", "croptopia:hops", "croptopia:rhubarb", "croptopia:cantaloupe", "croptopia:honeydew", "croptopia:basil", "croptopia:pepper", "croptopia:kiwi") +
        // Pumpkins and nether wart
        crops(12, "minecraft:pumpkin", "minecraft:nether_wart") +
        // Tree fruit
        crops(14, "minecraft:apple", "minecraft:cocoa_beans", "croptopia:apricot", "croptopia:avocado", "croptopia:banana", "croptopia:cherry", "croptopia:date", "croptopia:fig", "croptopia:grapefruit", "croptopia:kumquat", "croptopia:lemon", "croptopia:lime", "croptopia:mango", "croptopia:nectarine", "croptopia:orange", "croptopia:peach", "croptopia:pear", "croptopia:persimmon", "croptopia:plum", "croptopia:starfruit", "croptopia:coconut", "croptopia:dragonfruit", "croptopia:olive", "croptopia:pineapple") +
        // Nuts, coffee, tea and roots
        crops(18, "croptopia:almond", "croptopia:cashew", "croptopia:pecan", "croptopia:walnut", "croptopia:coffee_beans", "croptopia:tea_leaves", "croptopia:ginger", "croptopia:turmeric", "croptopia:saguaro") +
        // Rare spices
        crops(24, "croptopia:cinnamon", "croptopia:nutmeg", "croptopia:vanilla") +
        unlimited(400, 8, 4, "tfmg:steel_mechanism") +
        unlimited(600, 8, 4, "tfmg:circuit_board"),
    val dailySellLots: Int = 3,
    val resetHour: Int = 6,
    val recoveryPct: Int = 5,
    val band: List<Double> = listOf(0.5, 2.0),

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
    val levelLocks: Boolean = true
) {
    val storageItemSet: Set<String> by lazy { storageItemIds.toHashSet() }
    val creativeItemSet: Set<String> by lazy { creativeItemIds.toHashSet() }

    val taxPct: Int get() = (sellTaxPct * 100).roundToInt()
    val allyTaxPct: Int get() = (allyTaxRate * 100).roundToInt()

    val bandLow: Double get() = band.getOrNull(0)?.takeIf { it in 0.01..1.0 } ?: 0.5
    val bandHigh: Double get() = band.getOrNull(1)?.takeIf { it in 1.0..100.0 } ?: 2.0

    fun lotOf(item: String): Int = (lots[item] ?: kami.economy.economy.Stocks.good(item)?.lot)?.takeIf { it in 1..maxAmount } ?: 1

    fun validPrice(price: Int) = price in 1..maxPrice
    fun validAmount(amount: Int) = amount in 1..maxAmount
}

private fun Settings.sane(): Settings = copy(
    marketTickInterval = marketTickInterval.coerceAtLeast(20),
    sellTaxPct = sellTaxPct.coerceIn(0.0, 0.9),
    allyTaxRate = allyTaxRate.coerceIn(0.0, 0.9),
    auctionFeePct = auctionFeePct.coerceIn(0.0, 0.9),
    maxPriceMovePct = maxPriceMovePct.coerceIn(0.001, 1.0),
    listLimit = listLimit.coerceIn(20, 2000),
    maxPrice = maxPrice.coerceIn(1, 100_000_000),
    maxAmount = maxAmount.coerceIn(1, 1_000_000),
    dailySellLots = dailySellLots.coerceAtLeast(0),
    resetHour = resetHour.coerceIn(0, 23),
    recoveryPct = recoveryPct.coerceIn(0, 100),
    priceDeltaThresholdPct = priceDeltaThresholdPct.coerceAtLeast(0.0),
    auctionCheckIntervalTicks = auctionCheckIntervalTicks.coerceAtLeast(20),
    starterGoods = starterGoods.filter { it.base > 0 && it.lot > 0 && it.target >= 0 && it.depth > 0 }
)

private val sections = listOf(
    Section(
        "market.json", "Market prices and the sales tax. Prices are in spurs.",
        mapOf(
            "marketTickInterval" to "Ticks between two price updates. 1200 is one minute.",
            "priceDeltaThresholdPct" to "Smallest price change, 0 to 1, that gets sent to players right away.",
            "maxPriceMovePct" to "Most a price may move in one update, 0 to 1.",
            "defaultFloor" to "Lowest price of any item.",
            "defaultCeiling" to "Highest price of any item, or null for no limit.",
            "categoryBounds" to "Own floor, ceiling and maxMovePct per item id. Leave a value null to use the default.",
            "sellTaxPct" to "Tax taken from every market sale, 0 to 0.9.",
            "allyTaxRate" to "Tax on player trades between allied countries or one country family, 0 to 0.9.",
            "maxPrice" to "Highest price per lot a player may ask or bid.",
            "maxAmount" to "Most items one player may buy or sell in one trade.",
            "marketSlots" to "Active sell and buy orders one player may hold when no country raises the limit.",
            "lots" to "Items per lot by item id, default 1. Prices are per lot and trades move whole lots."
        )
    ),
    Section(
        "auctions.json", "The auction house for unique items.",
        mapOf(
            "auctionSlots" to "Active auctions one player may hold when no country raises the limit.",
            "auctionFeePct" to "Fee taken from a finished auction, 0 to 0.9.",
            "auctionDurationMillis" to "How long an auction runs in milliseconds. 259200000 is three days.",
            "auctionCheckIntervalTicks" to "Ticks between two checks for finished auctions."
        )
    ),
    Section(
        "starter-items.json", "Basic goods the market buys and sells from its own stock, so new players can earn their first coins.",
        mapOf(
            "starterGoods" to "Item id or #tag, base price per lot, lot size, target stock and depth in lots, optional own daily cap. infinite = the market always buys it at the base price up to the daily cap per player and never sells it back.",
            "dailySellLots" to "Lots per player and day the market buys at the full base price.",
            "resetHour" to "Server hour, 0 to 23, when the daily cap resets.",
            "recoveryPct" to "Share of the gap to the target stock that closes each day, 0 to 100.",
            "band" to "Lowest and highest price as a factor of the base price."
        )
    ),
    Section(
        "blocked-items.json", "Items kept out of the market. Coins from library/coins.json are always blocked.",
        mapOf(
            "creativeItemIds" to "Items that can never be traded.",
            "storageItemIds" to "Containers that may only be sold at the auction house."
        )
    ),
    Section(
        "general.json", "Price history, GUI and vendor settings.",
        mapOf(
            "historyFiveMinRetention" to "Five-minute price points kept per item. 288 is 1 day.",
            "historyHourlyRetention" to "Hourly price points kept per item. 720 is 30 days.",
            "historyDailyRetention" to "Daily price points kept per item.",
            "listLimit" to "Most rows of one list sent to a player, 20 to 2000.",
            "guiCooldown" to "Ticks between two GUI actions of one player.",
            "allowCreativeVendors" to "Allow the creative vendor block from Numismatics.",
            "levelLocks" to "Enforce the country level feature locks economy:auctions and economy:vendors. A feature no level reward mentions stays unlocked."
        )
    )
)

object Config : ConfigModule<Settings>("economy", Settings.serializer(), Settings(), sections, "kami_economy.json", { it.sane() })
