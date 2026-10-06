package kami.essentials.client

import kami.libs.config.KamiConfig
import kami.libs.config.Section
import kami.libs.log.Log
import kotlinx.serialization.Serializable

@Serializable
class Prefs(
    var railCollapsed: Boolean = false,
    var bankRange: String = "week",
    var collapsedGroups: MutableSet<String> = mutableSetOf()
)

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
