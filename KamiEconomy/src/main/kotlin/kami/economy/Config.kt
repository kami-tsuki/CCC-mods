package kami.economy

import kami.libs.config.Configs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files

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

    val coins: Map<String, Int> = linkedMapOf(
        "numismatics:spur" to 1, "numismatics:bevel" to 8, "numismatics:sprocket" to 16,
        "numismatics:cog" to 64, "numismatics:crown" to 512, "numismatics:sun" to 4096
    ),
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
    private val json = Configs.json { coerceInputValues = true }
    var s = Settings()

    fun load() {
        val path = Configs.file("kami_economy.json")
        val parsed = if (Files.exists(path)) runCatching { json.decodeFromString<Settings>(Files.readString(path)) } else null
        s = (parsed?.getOrNull() ?: Settings()).sane()
        parsed?.exceptionOrNull()?.let { KamiEconomy.LOG.error("Invalid kami_economy.json, using defaults without overwriting it", it) }
            ?: Files.writeString(path, json.encodeToString(s))
    }
}
