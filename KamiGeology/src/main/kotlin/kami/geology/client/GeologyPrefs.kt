package kami.geology.client

import kami.libs.ui.settings.ClientSettings
import kotlinx.serialization.Serializable

@Serializable
class GeologyPrefsData(
    var grid: Boolean = true,
    var tint: Boolean = true,
    var deposits: Boolean = true,
    var scatter: Boolean = true,
    var mode: String = "ORES",
    var zoom: Double = 4.0
)

object GeologyPrefs {
    private val section by lazy { ClientSettings.register("mods.geology", GeologyPrefsData.serializer(), GeologyPrefsData()) }
    val prefs: GeologyPrefsData get() = section.value

    fun save() = ClientSettings.save()
}
