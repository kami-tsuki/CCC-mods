package kami.libs.ui

import kami.libs.KamiLibs
import kami.libs.config.KamiConfig
import kami.libs.config.Section
import kami.libs.ui.core.Ui
import kami.libs.ui.core.UiScale
import kami.libs.ui.style.Palette
import kami.libs.ui.style.UiSound
import kotlinx.serialization.Serializable

@Serializable
class UiSettings(
    var uiScale: Float = UiScale.DEFAULT,
    var vision: String = "NORMAL",
    var reduceMotion: Boolean = false,
    var sounds: Float = 1f,
    var tooltipDelay: Int = 400
)

object UiPrefs {
    private val config = KamiConfig(
        "library", UiSettings(),
        listOf(
            Section(
                "client.json", "Look and feel of all Kami screens on this game client. The preferences screen changes them too.",
                mapOf(
                    "uiScale" to "Size of the interface: 0.8, 0.9 or 1.0.",
                    "vision" to "Colour set: NORMAL, DEUTERANOPIA, PROTANOPIA or TRITANOPIA.",
                    "reduceMotion" to "Turn off pulsing, sliding and flashing.",
                    "sounds" to "Volume of interface sounds, 0 to 1.",
                    "tooltipDelay" to "Milliseconds before a tooltip shows."
                )
            )
        ),
        reloadable = false
    )
    val prefs: UiSettings by lazy { config.load(); config.value }

    fun apply() {
        UiScale.factor = prefs.uiScale
        UiSound.volume = prefs.sounds
        Palette.vision = runCatching { Palette.Vision.valueOf(prefs.vision) }.getOrDefault(Palette.Vision.NORMAL)
    }

    fun apply(ui: Ui) {
        ui.reduceMotion = prefs.reduceMotion
        ui.tooltipDelay = prefs.tooltipDelay.toLong()
    }

    fun save() {
        apply()
        runCatching { config.save(prefs) }.onFailure { KamiLibs.LOG.warn("Could not save client.json: {}", it.message) }
    }
}
