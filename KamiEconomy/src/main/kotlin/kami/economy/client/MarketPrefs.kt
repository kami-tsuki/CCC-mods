package kami.economy.client

import kami.libs.config.KamiConfig
import kami.libs.config.Section
import kami.libs.log.Log
import kotlinx.serialization.Serializable

@Serializable
class Prefs(
    var tourDone: Boolean = false,
    var chartRange: String = "week",
    var collapsedGroups: MutableSet<String> = mutableSetOf()
)

object MarketPrefs {
    private val config = KamiConfig(
        "economy", Prefs(),
        listOf(
            Section(
                "client.json", "Settings of this game client for the market screen.",
                mapOf(
                    "tourDone" to "The guided tour was finished or skipped.",
                    "chartRange" to "Price chart range on the item page: day, week, month or all.",
                    "collapsedGroups" to "Navigation groups you collapsed."
                )
            )
        ),
        reloadable = false
    )
    val prefs: Prefs by lazy { config.load(); config.value }

    private val log = Log.of("economy")

    fun save() = runCatching { config.save(prefs) }.onFailure { log.warn("Could not save client.json: {}", it.message) }

    fun finishTour() {
        prefs.tourDone = true
        save()
    }
}
