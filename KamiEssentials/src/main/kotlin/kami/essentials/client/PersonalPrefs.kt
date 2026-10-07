package kami.essentials.client

import kami.libs.config.KamiConfig
import kami.libs.config.Section
import kami.libs.log.Log
import kami.libs.ui.app.NavPrefs
import kotlinx.serialization.Serializable

@Serializable
class Prefs(
    override var railCollapsed: Boolean = false,
    var bankRange: String = "week",
    override var collapsedGroups: MutableSet<String> = mutableSetOf()
) : NavPrefs

object PersonalPrefs {
    private val config = KamiConfig(
        "essentials", Prefs(),
        listOf(
            Section(
                "client.json", "Settings of this game client for the personal screen.",
                mapOf(
                    "railCollapsed" to "The side bar is collapsed to icons.",
                    "bankRange" to "Income and expense breakdown range: week or month.",
                    "collapsedGroups" to "Navigation groups you collapsed."
                )
            )
        ),
        reloadable = false
    )
    val prefs: Prefs by lazy { config.load(); config.value }

    private val log = Log.of("essentials")

    fun save() = runCatching { config.save(prefs) }.onFailure { log.warn("Could not save client.json: {}", it.message) }
}
