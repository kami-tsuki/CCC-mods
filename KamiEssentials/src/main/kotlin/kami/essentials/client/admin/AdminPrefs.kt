package kami.essentials.client.admin

import kami.libs.config.KamiConfig
import kami.libs.config.Section
import kami.libs.log.Log
import kami.libs.ui.app.NavPrefs
import kotlinx.serialization.Serializable

@Serializable
class AdminPrefsData(
    override var railCollapsed: Boolean = false,
    override var collapsedGroups: MutableSet<String> = mutableSetOf()
) : NavPrefs

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
