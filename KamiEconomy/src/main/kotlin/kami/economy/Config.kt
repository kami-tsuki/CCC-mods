package kami.economy

import kami.libs.config.KamiConfig
import kami.libs.config.Section
import kotlinx.serialization.Serializable

@Serializable
data class CategoryBounds(val floor: Int? = null, val ceiling: Int? = null, val maxMovePct: Double? = null)

@Serializable
data class EndlessItem(val item: String, val floorPrice: Int, val synthPremiumFactor: Double = 1.25)

@Serializable
data class Settings(
    val marketTickInterval: Int = 60 * 20,
    val priceDeltaThresholdPct: Double = 0.01,
    val maxPriceMovePct: Double = 0.05,
    val defaultFloor: Int = 1,
    val defaultCeiling: Int? = null,
    val categoryBounds: Map<String, CategoryBounds> = emptyMap(),

    val sellTaxPct: Double = 0.10,
    val auctionFeePct: Double = 0.05,
    val auctionDurationMillis: Long = 3 * 24 * 60 * 60 * 1000L,
    val auctionCheckIntervalTicks: Int = 20 * 60,

    val endlessSupply: List<EndlessItem> = listOf(
        EndlessItem("minecraft:oak_log", 6),
        EndlessItem("minecraft:wheat", 2),
        EndlessItem("minecraft:cobblestone", 1)
    ),
    val buyInfiniteEnabled: Boolean = false,
    val sellInfiniteEnabled: Boolean = true,

    val creativeItemIds: List<String> = listOf(
        "minecraft:barrier", "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
        "minecraft:command_block_minecart", "minecraft:debug_stick", "minecraft:structure_block", "minecraft:structure_void",
        "minecraft:jigsaw", "minecraft:light", "minecraft:knowledge_book", "minecraft:spawner"
    ),
    val storageItemIds: List<String> = listOf("minecraft:shulker_box", "minecraft:bundle"),
    val componentBlocklist: List<String> = listOf(
        "minecraft:custom_name", "minecraft:custom_data", "minecraft:trim",
        "minecraft:block_entity_data", "minecraft:container", "minecraft:bundle_contents",
        "minecraft:charged_projectiles", "minecraft:fireworks",
        "minecraft:writable_book_content", "minecraft:written_book_content", "minecraft:profile"
    ),

    val historyRawRetention: Int = 360,
    val historyHourlyRetention: Int = 24 * 30,
    val historyDailyRetention: Int = 400,

    val pageSize: Int = 30,
    val guiCooldown: Int = 4,

    val allowCreativeVendors: Boolean = false
) {
    val endlessByItem: Map<String, EndlessItem> by lazy { endlessSupply.associateBy { it.item } }
    val storageItemSet: Set<String> by lazy { storageItemIds.toHashSet() }
    val componentBlockSet: Set<String> by lazy { componentBlocklist.toHashSet() }
    val creativeItemSet: Set<String> by lazy { creativeItemIds.toHashSet() }
}

private fun Settings.sane(): Settings = copy(
    marketTickInterval = marketTickInterval.coerceAtLeast(20),
    sellTaxPct = sellTaxPct.coerceIn(0.0, 0.9),
    auctionFeePct = auctionFeePct.coerceIn(0.0, 0.9),
    maxPriceMovePct = maxPriceMovePct.coerceIn(0.001, 1.0),
    pageSize = pageSize.coerceIn(5, 100)
)

object Config {
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
                "sellTaxPct" to "Tax taken from every market sale, 0 to 0.9."
            )
        ),
        Section(
            "auctions.json", "The auction house for unique items.",
            mapOf(
                "auctionFeePct" to "Fee taken from a finished auction, 0 to 0.9.",
                "auctionDurationMillis" to "How long an auction runs in milliseconds. 259200000 is three days.",
                "auctionCheckIntervalTicks" to "Ticks between two checks for finished auctions."
            )
        ),
        Section(
            "starter-items.json", "Basic items the server always buys, so new players can earn their first coins.",
            mapOf(
                "endlessSupply" to "Items with endless demand. floorPrice is the least the server pays.",
                "endlessSupply.synthPremiumFactor" to "Markup when the server also sells this item, based on the market price.",
                "buyInfiniteEnabled" to "The server also sells these items without limit.",
                "sellInfiniteEnabled" to "Players can always sell these items to the server."
            )
        ),
        Section(
            "blocked-items.json", "Items kept out of the market. Coins from library/coins.json are always blocked.",
            mapOf(
                "creativeItemIds" to "Items that can never be traded.",
                "storageItemIds" to "Containers that may only be sold at the auction house.",
                "componentBlocklist" to "Reserved for item data rules, not used yet."
            )
        ),
        Section(
            "general.json", "Price history, GUI and vendor settings.",
            mapOf(
                "historyRawRetention" to "Price points kept at full detail per item.",
                "historyHourlyRetention" to "Hourly price points kept per item. 720 is 30 days.",
                "historyDailyRetention" to "Daily price points kept per item.",
                "pageSize" to "Items per market page, 5 to 100.",
                "guiCooldown" to "Ticks between two GUI actions of one player.",
                "allowCreativeVendors" to "Allow the creative vendor block from Numismatics."
            )
        )
    )

    val file = KamiConfig("economy", Settings(), sections, legacy = "kami_economy.json", sane = { it.sane() })

    var s: Settings
        get() = file.value
        set(value) { file.value = value }

    fun load() = file.load()
}
