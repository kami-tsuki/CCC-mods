package kami.economy.client

import kami.libs.config.Configs
import kami.libs.ui.app.NavPrefs
import kami.libs.ui.settings.ClientSettings
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
    private val section by lazy {
        ClientSettings.register("mods.economy", Prefs.serializer(), Prefs(), Configs.dir("economy").resolve("client.json"))
    }
    val prefs: Prefs get() = section.value

    fun save() = ClientSettings.save()

    fun finishTour() {
        prefs.tourDone = true
        save()
    }
}
