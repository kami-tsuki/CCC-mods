package kami.libs.ui

import kami.libs.config.Configs
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Ui
import kami.libs.ui.core.UiScale
import kami.libs.ui.settings.ClientSettings
import kami.libs.ui.style.Density
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Metrics
import kami.libs.ui.style.Mode
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Theme
import kami.libs.ui.style.UiSound
import kotlinx.serialization.Serializable

@Serializable
class UiSettings(
    var uiScale: Float = UiScale.DEFAULT,
    var theme: String = "minecraft",
    var mode: String = "light",
    var density: String = "comfortable",
    var vision: String = "NORMAL",
    var reduceMotion: Boolean = false,
    var sounds: Float = 1f,
    var tooltipDelay: Int = 400,
    var detailedTooltips: Boolean = true,
    var autoRefresh: Boolean = true,
    var windowLarge: Boolean = false
)

object UiPrefs {
    private val section by lazy {
        ClientSettings.register("ui", UiSettings.serializer(), UiSettings(), Configs.dir("library").resolve("client.json"))
    }
    private var signature = 0
    private val queued = ArrayList<(UiSettings) -> Unit>()
    private var persist = false
    val prefs: UiSettings get() = section.value
    val mode: Mode get() = runCatching { Mode.valueOf(prefs.mode.trim().uppercase()) }.getOrDefault(Mode.LIGHT)

    fun change(save: Boolean = true, edit: (UiSettings) -> Unit) {
        queued += edit
        persist = persist || save
    }

    fun apply() {
        UiScale.configure(prefs.uiScale)
        Theme.use(prefs.theme, mode)
        Metrics.use(Density.parse(prefs.density))
        UiSound.volume = prefs.sounds
        Palette.vision = runCatching { Palette.Vision.valueOf(prefs.vision) }.getOrDefault(Palette.Vision.NORMAL)
    }

    fun refreshCaches() {
        val next = ((Theme.version * 31 + Metrics.density.ordinal) * 31 + UiScale.factor.hashCode()) * 31 + Palette.vision.ordinal
        if (next == signature) return
        signature = next
        Memo.invalidate()
        Draw.clearWidths()
    }

    fun apply(ui: Ui) {
        if (queued.isNotEmpty()) {
            queued.forEach { it(prefs) }
            queued.clear()
            apply()
            if (persist) save()
            persist = false
        }
        ui.reduceMotion = prefs.reduceMotion
        ui.tooltipDelay = prefs.tooltipDelay.toLong()
    }

    fun save() {
        apply()
        ClientSettings.save()
    }
}
