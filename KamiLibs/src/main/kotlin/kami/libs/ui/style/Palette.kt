package kami.libs.ui.style

import com.google.gson.JsonParser
import kami.libs.KamiLibs
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import kotlin.reflect.KProperty

object Palette {
    const val MIN_VISIBLE_ALPHA = 0x08

    private val defaults = LinkedHashMap<String, Int>()
    private val values = HashMap<String, Int>()

    class Token(val name: String) {
        operator fun getValue(owner: Any?, property: KProperty<*>): Int = values[name] ?: defaults.getValue(name)
    }

    private fun token(name: String, rgb: Int, alpha: Int = 0xFF) = Token(name).also { defaults[name] = (alpha shl 24) or (rgb and 0xFFFFFF) }

    val backdrop by token("bg.backdrop", 0x000000, 0x88)
    val canvas by token("bg.canvas", 0xC6C6C6)
    val surface by token("bg.surface", 0xC6C6C6)
    val raised by token("bg.raised", 0xD4D4D4)
    val sunken by token("bg.sunken", 0x8B8B8B)
    val field by token("bg.field", 0xE6E6E6)
    val hover by token("bg.hover", 0xE4E4E4)
    val selected by token("bg.selected", 0xAFC6EB)
    val overlay by token("bg.overlay", 0xC6C6C6, 0xF8)

    val bevelLight by token("bevel.light", 0xFFFFFF)
    val bevelDark by token("bevel.dark", 0x555555)
    val bevelInsetDark by token("bevel.inset_dark", 0x373737)
    val outline by token("bevel.outline", 0x000000)

    val borderSubtle by token("border.subtle", 0xA0A0A0)
    val border by token("border.default", 0x7B7B7B)
    val borderStrong by token("border.strong", 0x555555)
    val brass by token("border.brass", 0x2F66B0)

    val text by token("text.primary", 0x404040)
    val textSecondary by token("text.secondary", 0x5A5A5A)
    val textMuted by token("text.muted", 0x6E6E6E)
    val textDisabled by token("text.disabled", 0x8A8A8A)
    val textInverse by token("text.inverse", 0xFFFFFF)
    val link by token("text.link", 0x1F4FA8)

    val iconTint by token("icon.tint", 0x707070)
    val iconOff by token("icon.off", 0x7A7A7A, 0xC0)

    val tipBg by token("tip.bg", 0x100010, 0xF0)
    val tipBorder by token("tip.border", 0x3C1C7A)
    val tipText by token("tip.text", 0xFFFFFF)
    val tipSecondary by token("tip.secondary", 0xC4C4CC)
    val tipMuted by token("tip.muted", 0x8E8E9A)

    val info by token("sem.info", 0x1E5FA8)
    val success by token("sem.success", 0x1E7A3E)
    val warning by token("sem.warning", 0x9A6200)
    val danger by token("sem.danger", 0xB02A2E)
    val money by token("sem.money", 0x8A6A00)
    val focus by token("sem.focus", 0x2B6CC4)

    val geoProvince by token("geo.province", 0x5E4FA8)
    val geoAlly by token("geo.ally", 0x1F6F96)
    val geoNeutral by token("geo.neutral", 0x5F6670)
    val geoHostile by token("geo.banished", 0xB02A2E)
    val geoReserved by token("geo.reserved", 0x6B5C3C)

    val chart = intArrayOf(0xFFB87800.toInt(), 0xFF1E78B4.toInt(), 0xFF007A58.toInt(), 0xFF8C8200.toInt(), 0xFF0050A0.toInt(), 0xFFB04800.toInt(), 0xFFA84C82.toInt(), 0xFF6E6E6E.toInt())

    enum class Vision(val overrides: Map<String, Int>) {
        NORMAL(emptyMap()),
        DEUTERANOPIA(mapOf("sem.success" to 0xFF0060A0.toInt(), "sem.danger" to 0xFFB04800.toInt(), "sem.warning" to 0xFF8C8200.toInt())),
        PROTANOPIA(mapOf("sem.success" to 0xFF1E78B4.toInt(), "sem.danger" to 0xFFB87800.toInt(), "sem.warning" to 0xFF8C8200.toInt())),
        TRITANOPIA(mapOf("sem.success" to 0xFF007A58.toInt(), "sem.danger" to 0xFFB04800.toInt(), "sem.warning" to 0xFFA84C82.toInt(), "sem.info" to 0xFF6E6E6E.toInt()))
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
    fun fade(color: Int, t: Float): Int {
        val a = ((color ushr 24) * t.coerceIn(0f, 1f)).toInt()
        return if (a < MIN_VISIBLE_ALPHA) 0 else (a shl 24) or (color and 0xFFFFFF)
    }

    fun opaque(rgb: Int) = 0xFF000000.toInt() or (rgb and 0xFFFFFF)

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - k) + ((b shr shift) and 0xFF) * k).toInt() shl shift
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    fun forTip(c: Int): Int = when (c) {
        text -> tipText
        textSecondary -> tipSecondary
        textMuted, textDisabled -> tipMuted
        else -> lighten(c, 0.5f)
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
    val tint get() = Palette.alpha(color, 0x2A)
    val edge get() = Palette.alpha(color, 0x90)

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name.equals(name, true) } ?: NEUTRAL
    }
}
