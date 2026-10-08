package kami.economy

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
    val discordItemIconUrl: String = ""
) {
    val storageItemSet: Set<String> by lazy { storageItemIds.toHashSet() }
    val creativeItemSet: Set<String> by lazy { creativeItemIds.toHashSet() }

    val taxPct: Int get() = (sellTaxPct * 100).roundToInt()
    val allyTaxPct: Int get() = (allyTaxRate * 100).roundToInt()
    val auctionFeeBps: Int get() = (auctionFeePct * 10_000).roundToInt()

    fun lotOf(item: String): Int = kami.economy.economy.Variants.of(item)?.let { kami.economy.economy.Variants.lot(item, lotOf(it.core)) } ?: (lots[item] ?: kami.economy.economy.Stocks.good(item)?.lot)?.takeIf { it in 1..maxAmount } ?: 1

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
    starterGoods = starterGoods.map { it.normalized() }.filter { g -> g.valid().also { ok -> if (!ok) LOG.warn("Dropped invalid starter good {}", g.item) } }
)

private val sections = listOf(
    Section(
        "market.json", "Market prices and the sales tax. Prices are in spurs.",
        mapOf(
            "marketTickInterval" to "Ticks between two price updates. 1200 is one minute.",
            "priceDeltaThresholdPct" to "Smallest price change, 0 to 1, that gets sent to players right away.",
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
            "starterGoods" to "Item id or #tag with base price per lot, lot size, depth in lots (how strongly a trade moves the course), supply and demand in lots (-1 = infinite; supply 0 = nothing to buy, demand 0 = nothing bought; demand also grows when players buy from the server), optional floor and ceiling price, optional spread, sellCap (lots per player in 24 hours the market buys, 0 = unlimited) and resell (false = lots the market buys are not added to its supply).",
            "catalog" to "Last bundled starter catalog merged into starterGoods. Goods you remove stay removed.",
            "serverSpread" to "Share the market adds to the course when it sells and takes off when it buys, 0 to 0.5.",
            "courseDepth" to "Depth in lots for the course of items that are not starter goods.",
            "courseClip" to "Most a single player trade may pull the course from its current value, 0.01 to 1."
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
            "levelLocks" to "Enforce the country level feature locks economy:auctions and economy:vendors. A feature no level reward mentions stays unlocked.",
            "discordMoveThresholdPct" to "Price move in percent, since the last Discord post, that posts an item to the market channel and alerts watchers.",
            "discordItemCooldownMinutes" to "Minutes before the same item may be posted to Discord again.",
            "discordDigestHours" to "Hours between two market digests in the Discord market channel. 0 turns the digest off.",
            "discordAlertCooldownMinutes" to "Minutes between two price alert direct messages to one player.",
            "discordItemIconUrl" to "Optional https URL pattern for item icons in Discord embeds. {namespace} and {path} are replaced with the item id parts, e.g. https://example.com/icons/{namespace}/{path}.png. Empty shows no icons."
        )
    )
)

object Config : ConfigModule<Settings>("economy", Settings.serializer(), Settings(), sections, "kami_economy.json", { it.sane() })

private fun StarterGood.valid(): Boolean =
    base > 0 && lot > 0 && depth > 0 && supply >= -1 && demand >= -1 && sellCap >= 0 && (floor ?: 1) >= 1 && (ceiling ?: Int.MAX_VALUE) >= (floor ?: maxOf(1, base / 2)) && (spread ?: 0.0) in 0.0..0.5
