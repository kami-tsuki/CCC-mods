package kami.economy.client

import kami.libs.config.KamiConfig
import kami.libs.config.Section
import kami.libs.log.Log
import kami.libs.ui.app.NavPrefs
import kotlinx.serialization.Serializable

@Serializable
class Prefs(
    var tourDone: Boolean = false,
    var chartRange: String = "week",
    override var railCollapsed: Boolean = false,
    override var collapsedGroups: MutableSet<String> = mutableSetOf(),
    var watch: MutableList<String>? = null,
    var showWorth: Boolean = true
): NavPrefs

object MarketPrefs {
    private val config = KamiConfig(
        "economy", Prefs(),
        listOf(
            Section(
                "client.json", "Settings of this game client for the market screen.",
                mapOf(
                    "tourDone" to "The guided tour was finished or skipped.",
                    "chartRange" to "Price chart range on the item page: day, week, month or all.",
                    "railCollapsed" to "The sidebar is collapsed to icons.",
                    "collapsedGroups" to "Navigation groups you collapsed.",
                    "watch" to "Legacy local watchlist, uploaded to the server once and then removed.",
                    "showWorth" to "Show the market value of your inventory under inventory screens."
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
