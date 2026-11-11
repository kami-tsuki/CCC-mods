package kami.essentials.client

import kami.libs.config.Configs
import kami.libs.ui.app.NavPrefs
import kami.libs.ui.settings.ClientSettings
import kotlinx.serialization.Serializable

@Serializable
class Prefs(
    override var railCollapsed: Boolean = false,
    var bankRange: String = "week",
    override var collapsedGroups: MutableSet<String> = mutableSetOf()
) : NavPrefs

object PersonalPrefs {
    private val section by lazy { ClientSettings.register("mods.essentials.personal", Prefs.serializer(), Prefs(), Configs.dir("essentials").resolve("client.json")) }
    val prefs: Prefs get() = section.value

    fun save() = ClientSettings.save()
}
