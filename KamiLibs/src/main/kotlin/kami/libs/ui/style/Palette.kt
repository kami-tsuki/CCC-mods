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

    val backdrop by token("bg.backdrop", 0x000000, 0x99)
    val canvas by token("bg.canvas", 0x111318)
    val surface by token("bg.surface", 0x181B21)
    val raised by token("bg.raised", 0x20242C)
    val sunken by token("bg.sunken", 0x0C0E12)
    val hover by token("bg.hover", 0x272C36)
    val selected by token("bg.selected", 0x2C3A52)
    val overlay by token("bg.overlay", 0x1C2028, 0xF5)

    val borderSubtle by token("border.subtle", 0x262B34)
    val border by token("border.default", 0x353B47)
    val borderStrong by token("border.strong", 0x4A5262)
    val brass by token("border.brass", 0xB08D57)

    val text by token("text.primary", 0xECEEF2)
    val textSecondary by token("text.secondary", 0xAEB5C1)
    val textMuted by token("text.muted", 0x737B8A)
    val textDisabled by token("text.disabled", 0x4E5562)
    val textInverse by token("text.inverse", 0x111318)
    val link by token("text.link", 0x7FB2FF)

    val info by token("sem.info", 0x56B6F7)
    val success by token("sem.success", 0x4CC38A)
    val warning by token("sem.warning", 0xF5A524)
    val danger by token("sem.danger", 0xF2555A)
    val money by token("sem.money", 0xF2C94C)
    val focus by token("sem.focus", 0x7FB2FF)

    val geoProvince by token("geo.province", 0xA78BFA)
    val geoAlly by token("geo.ally", 0x38BDF8)
    val geoNeutral by token("geo.neutral", 0x9AA3B2)
    val geoHostile by token("geo.banished", 0xF2555A)
    val geoUnclaimed by token("geo.nomansland", 0x6B6453)
    val geoReserved by token("geo.reserved", 0x8C7A5B)

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
    NEUTRAL({ Palette.textSecondary }, "info"),
    INFO({ Palette.info }, "info"),
    SUCCESS({ Palette.success }, "check"),
    WARNING({ Palette.warning }, "warning"),
    DANGER({ Palette.danger }, "danger");

    val color get() = pick()
    val tint get() = Palette.alpha(color, 0x2E)
    val edge get() = Palette.alpha(color, 0x99)

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name.equals(name, true) } ?: NEUTRAL
    }
}
