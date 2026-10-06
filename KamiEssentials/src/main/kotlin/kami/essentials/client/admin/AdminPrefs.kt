package kami.essentials.client.admin

import kami.libs.config.KamiConfig
import kami.libs.config.Section
import kami.libs.log.Log
import kotlinx.serialization.Serializable

@Serializable
class AdminPrefsData(
    var railCollapsed: Boolean = false,
    var collapsedGroups: MutableSet<String> = mutableSetOf()
)

object AdminPrefs {
    private val config = KamiConfig(
        "essentials", AdminPrefsData(),
        listOf(
            Section(
                "admin.json", "Settings of this game client for the admin screen.",
                mapOf(
                    "railCollapsed" to "The side bar is collapsed to icons.",
                    "collapsedGroups" to "Navigation groups you collapsed."
                )
            )
        ),
        reloadable = false
    )
    val prefs: AdminPrefsData by lazy { config.load(); config.value }

    private val log = Log.of("essentials")

    fun save() = runCatching { config.save(prefs) }.onFailure { log.warn("Could not save admin.json: {}", it.message) }
}
