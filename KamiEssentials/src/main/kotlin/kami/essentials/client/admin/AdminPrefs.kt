package kami.essentials.client.admin

import kami.libs.config.Configs
import kami.libs.ui.app.NavPrefs
import kami.libs.ui.settings.ClientSettings
import kotlinx.serialization.Serializable

@Serializable
class AdminPrefsData(
    override var railCollapsed: Boolean = false,
    override var collapsedGroups: MutableSet<String> = mutableSetOf()
) : NavPrefs

object AdminPrefs {
    private val section by lazy { ClientSettings.register("mods.essentials.admin", AdminPrefsData.serializer(), AdminPrefsData(), Configs.dir("essentials").resolve("admin.json")) }
    val prefs: AdminPrefsData get() = section.value

    fun save() = ClientSettings.save()
}
