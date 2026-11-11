package kami.libs.ui.style

import net.minecraft.server.packs.resources.ResourceManager
import kotlin.reflect.KProperty

object Palette {
    const val MIN_VISIBLE_ALPHA = 0x08

    internal var atlas: Map<String, Int> = emptyMap()
    private var scoped: Resolved? = null

    class Token(val name: String) {
        operator fun getValue(owner: Any?, property: KProperty<*>): Int = value(name)
    }

    private fun token(name: String) = Token(name)

    private fun active() = scoped ?: Theme.active()

    private fun value(name: String): Int {
        val theme = active()
        return vision.of(theme.def.variant)[name] ?: atlas[name] ?: theme.values.getValue(name)
    }

    val backdrop by token("bg.backdrop")
    val canvas by token("bg.canvas")
    val surface by token("bg.surface")
    val raised by token("bg.raised")
    val sunken by token("bg.sunken")
    val field by token("bg.field")
    val hover by token("bg.hover")
    val selected by token("bg.selected")
    val overlay by token("bg.overlay")

    val bevelLight by token("bevel.light")
    val bevelDark by token("bevel.dark")
    val bevelInsetDark by token("bevel.inset_dark")
    val outline by token("bevel.outline")

    val borderSubtle by token("border.subtle")
    val border by token("border.default")
    val borderStrong by token("border.strong")
    val brass by token("border.brass")
    val accent by token("border.brass")

    val text by token("text.primary")
    val textSecondary by token("text.secondary")
    val textMuted by token("text.muted")
    val textDisabled by token("text.disabled")
    val textInverse by token("text.inverse")
    val link by token("text.link")

    val iconTint by token("icon.tint")
    val iconOff by token("icon.off")

    val tipBg by token("tip.bg")
    val tipBorder by token("tip.border")
    val tipText by token("tip.text")
    val tipSecondary by token("tip.secondary")
    val tipMuted by token("tip.muted")

    val info by token("sem.info")
    val success by token("sem.success")
    val paid by token("sem.paid")
    val warning by token("sem.warning")
    val danger by token("sem.danger")
    val money by token("sem.money")
    val focus by token("sem.focus")

    val geoProvince by token("geo.province")
    val geoAlly by token("geo.ally")
    val geoNeutral by token("geo.neutral")
    val geoHostile by token("geo.banished")
    val geoReserved by token("geo.reserved")

    fun chunkType(id: String): Int? = Tokens.chunkTokens[id]?.let(::value)
    fun rank(id: String): Int? = Tokens.rankTokens[id]?.let(::value)

    val shadow by token("shadow")
    val washLight by token("wash.light")
    val washDark by token("wash.dark")

    val shape: ShapeDef get() = active().shape

    val chart: IntArray get() = active().chart

    enum class Vision(val overrides: Map<String, Int>, val dark: Map<String, Int>) {
        NORMAL(emptyMap(), emptyMap()),
        DEUTERANOPIA(
            mapOf("sem.success" to 0xFF000080.toInt(), "sem.danger" to 0xFFC03000.toInt(), "sem.warning" to 0xFF606030.toInt()),
            mapOf("sem.success" to 0xFF4080E0.toInt(), "sem.danger" to 0xFFE06070.toInt(), "sem.warning" to 0xFFFFFF00.toInt())
        ),
        PROTANOPIA(
            mapOf("sem.success" to 0xFF000080.toInt(), "sem.danger" to 0xFFA00030.toInt(), "sem.warning" to 0xFF806000.toInt()),
            mapOf("sem.success" to 0xFF4080FF.toInt(), "sem.danger" to 0xFFFF4050.toInt(), "sem.warning" to 0xFFE0FF00.toInt())
        ),
        TRITANOPIA(
            mapOf("sem.success" to 0xFF007A58.toInt(), "sem.danger" to 0xFFC02020.toInt(), "sem.warning" to 0xFF8A3AA0.toInt(), "sem.info" to 0xFF303030.toInt()),
            mapOf("sem.success" to 0xFF40D8A0.toInt(), "sem.danger" to 0xFFFF5050.toInt(), "sem.warning" to 0xFFE060C0.toInt(), "sem.info" to 0xFF7A8A9A.toInt())
        );

        fun of(variant: Variant) = if (variant == Variant.DARK) dark else overrides
    }

    var vision = Vision.NORMAL

    fun names(): Set<String> = Tokens.names.toSet()

    fun load(resources: ResourceManager) = ThemeResources.load(resources)

    fun <T> withTheme(def: ThemeDef, block: () -> T): T {
        val previous = scoped
        scoped = Theme.resolvedOf(def.id) ?: ThemeLoader.resolve(def, Theme::lookup)
        try {
            return block()
        } finally {
            scoped = previous
        }
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

    fun forTip(c: Int): Int = Tokens.tipPairs.firstOrNull { value(it.first) == c }?.let { value(it.second) } ?: lighten(c, 0.5f)

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
