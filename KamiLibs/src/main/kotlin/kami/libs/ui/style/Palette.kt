package kami.libs.ui.style

import com.google.gson.JsonParser
import kami.libs.KamiLibs
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import kotlin.reflect.KProperty

object Palette {
    private val defaults = LinkedHashMap<String, Int>()
    private val values = HashMap<String, Int>()

    class Token(val name: String) {
        operator fun getValue(owner: Any?, property: KProperty<*>): Int = values[name] ?: defaults.getValue(name)
    }

    private fun token(name: String, rgb: Int, alpha: Int = 0xFF) = Token(name).also { defaults[name] = (alpha shl 24) or (rgb and 0xFFFFFF) }

    val backdrop by token("bg.backdrop", 0x000000, 0x88)
    val canvas by token("bg.canvas", 0x14161A)
    val surface by token("bg.surface", 0x1A1D22)
    val raised by token("bg.raised", 0x21252B)
    val sunken by token("bg.sunken", 0x101216)
    val hover by token("bg.hover", 0x272B32)
    val selected by token("bg.selected", 0x233246)
    val overlay by token("bg.overlay", 0x1D2026, 0xF8)

    val borderSubtle by token("border.subtle", 0x24282E)
    val border by token("border.default", 0x30353D)
    val borderStrong by token("border.strong", 0x444A54)
    val brass by token("border.brass", 0x4C7BBF)

    val text by token("text.primary", 0xE2E5E9)
    val textSecondary by token("text.secondary", 0xA4ABB5)
    val textMuted by token("text.muted", 0x6F7682)
    val textDisabled by token("text.disabled", 0x4A505A)
    val textInverse by token("text.inverse", 0x101216)
    val link by token("text.link", 0x7DA4DA)

    val info by token("sem.info", 0x5E9CCF)
    val success by token("sem.success", 0x55A57C)
    val warning by token("sem.warning", 0xD1A04A)
    val danger by token("sem.danger", 0xD0585C)
    val money by token("sem.money", 0xCDB263)
    val focus by token("sem.focus", 0x7DA4DA)

    val geoProvince by token("geo.province", 0x8E80C8)
    val geoAlly by token("geo.ally", 0x539CC0)
    val geoNeutral by token("geo.neutral", 0x8A919C)
    val geoHostile by token("geo.banished", 0xD0585C)
    val geoUnclaimed by token("geo.nomansland", 0x5C5950)
    val geoReserved by token("geo.reserved", 0x847658)

    val chart = intArrayOf(0xFFE69F00.toInt(), 0xFF56B4E9.toInt(), 0xFF009E73.toInt(), 0xFFF0E442.toInt(), 0xFF0072B2.toInt(), 0xFFD55E00.toInt(), 0xFFCC79A7.toInt(), 0xFF999999.toInt())

    enum class Vision(val overrides: Map<String, Int>) {
        NORMAL(emptyMap()),
        DEUTERANOPIA(mapOf("sem.success" to 0xFF0072B2.toInt(), "sem.danger" to 0xFFD55E00.toInt(), "sem.warning" to 0xFFF0E442.toInt())),
        PROTANOPIA(mapOf("sem.success" to 0xFF56B4E9.toInt(), "sem.danger" to 0xFFE69F00.toInt(), "sem.warning" to 0xFFF0E442.toInt())),
        TRITANOPIA(mapOf("sem.success" to 0xFF009E73.toInt(), "sem.danger" to 0xFFD55E00.toInt(), "sem.warning" to 0xFFCC79A7.toInt(), "sem.info" to 0xFF999999.toInt()))
    }

    private var loaded: Map<String, Int> = emptyMap()
    var vision = Vision.NORMAL
        set(value) { field = value; rebuild() }

    fun names(): Set<String> = defaults.keys

    fun load(resources: ResourceManager) {
        val id = ResourceLocation.fromNamespaceAndPath(KamiLibs.ID, "kami_theme/atlas.json")
        loaded = resources.getResource(id).map { res ->
            runCatching {
                res.openAsReader().use { reader ->
                    JsonParser.parseReader(reader).asJsonObject.entrySet().associate { (k, v) -> k to parse(v.asString) }
                }
            }.onFailure { KamiLibs.LOG.warn("Theme file is broken, using defaults: {}", it.message) }.getOrDefault(emptyMap())
        }.orElse(emptyMap())
        rebuild()
    }

    private fun parse(hex: String): Int {
        val clean = hex.removePrefix("#")
        val v = clean.toLong(16).toInt()
        return if (clean.length <= 6) v or 0xFF000000.toInt() else v
    }

    private fun rebuild() {
        values.clear()
        values.putAll(loaded)
        values.putAll(vision.overrides)
    }

    fun alpha(color: Int, alpha: Int) = (alpha.coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)
    fun opaque(rgb: Int) = 0xFF000000.toInt() or (rgb and 0xFFFFFF)

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - k) + ((b shr shift) and 0xFF) * k).toInt() shl shift
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    fun lighten(c: Int, t: Float) = mix(c, 0xFFFFFFFF.toInt() and (c or 0xFFFFFF), t)
    fun darken(c: Int, t: Float) = mix(c, c and 0xFF000000.toInt(), t)
}

enum class Severity(private val pick: () -> Int, val icon: String) {
    NEUTRAL({ Palette.textSecondary }, "help"),
    INFO({ Palette.info }, "info"),
    SUCCESS({ Palette.success }, "check"),
    WARNING({ Palette.warning }, "warning"),
    DANGER({ Palette.danger }, "danger");

    val color get() = pick()
    val tint get() = Palette.alpha(color, 0x1F)
    val edge get() = Palette.alpha(color, 0x70)

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name.equals(name, true) } ?: NEUTRAL
    }
}
