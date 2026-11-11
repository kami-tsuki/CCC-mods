package kami.libs.ui.style

import java.time.LocalTime

enum class Mode { LIGHT, DARK, AUTO }

enum class Variant { LIGHT, DARK }

data class ThemeDef(
    val id: String,
    val family: String,
    val name: String,
    val base: String?,
    val variant: Variant,
    val colors: Map<String, Int>,
    val chart: List<Int> = emptyList(),
    val shape: Map<String, String> = emptyMap(),
    val sound: Map<String, String> = emptyMap()
)

data class ThemeFamily(val id: String, val name: String, val light: ThemeDef?, val dark: ThemeDef?) {
    fun defFor(night: Boolean): ThemeDef = (if (night) dark ?: light else light ?: dark)!!
}

class Resolved(val def: ThemeDef, val values: Map<String, Int>, val chart: IntArray, val shape: ShapeDef, val sound: Map<String, SoundSpec>)

object Theme {
    private const val AUTO_CHECK_MS = 30_000L
    private const val DAY_START = 7
    private const val DAY_END = 19

    private val builtin: Map<String, ThemeDef> = listOf("minecraft", "minecraft_dark", "light", "dark", "office", "office_dark", "vista", "vista_dark", "cyberpunk", "high_contrast", "high_contrast_dark").map { id ->
        val stream = requireNotNull(Theme::class.java.getResourceAsStream("/assets/kami_libs/kami_theme/$id.json")) { "missing built-in theme $id" }
        ThemeLoader.parse(stream.use { it.readBytes().decodeToString() })
    }.associateBy { it.id }

    private class Catalog(val resolved: Map<String, Resolved>, val defs: Map<String, ThemeDef>, val list: List<ThemeFamily>)

    private fun catalogOf(resolved: Map<String, Resolved>): Catalog {
        val defs = resolved.mapValues { it.value.def }
        return Catalog(resolved, defs, group(defs.values))
    }

    @Volatile
    private var catalog: Catalog = catalogOf(build(builtin).first)

    @Volatile
    private var familyId = "minecraft"

    @Volatile
    private var mode = Mode.LIGHT

    @Volatile
    private var checkedAt = 0L

    @Volatile
    private var active: Resolved = pick()

    @Volatile
    var version = 0
        private set

    fun resolve(mode: Mode, hour: Int): Mode = when (mode) {
        Mode.AUTO -> if (hour in DAY_START until DAY_END) Mode.LIGHT else Mode.DARK
        else -> mode
    }

    fun use(family: String, mode: Mode) {
        familyId = family
        this.mode = mode
        checkedAt = System.currentTimeMillis()
        refresh()
    }

    fun families(): List<ThemeFamily> = catalog.list

    internal fun resolvedOf(id: String): Resolved? = catalog.resolved[id]

    fun current(): ThemeDef = active().def

    internal fun active(): Resolved {
        if (mode == Mode.AUTO) {
            val now = System.currentTimeMillis()
            if (now - checkedAt >= AUTO_CHECK_MS) {
                checkedAt = now
                refresh()
            }
        }
        return active
    }

    internal fun lookup(id: String): ThemeDef? = catalog.defs[id]

    fun install(extra: List<ThemeDef>): List<String> {
        val rejected = ArrayList<String>()
        var candidate = builtin + extra.associateBy { it.id }
        var built = build(candidate)
        if (built.second.isNotEmpty()) {
            rejected += built.second
            candidate = candidate.filterKeys { it !in built.second } + builtin.filterKeys { it in built.second }
            built = build(candidate)
            rejected += built.second
        }
        catalog = catalogOf(built.first)
        refresh(force = true)
        return rejected
    }

    private fun build(map: Map<String, ThemeDef>): Pair<Map<String, Resolved>, List<String>> {
        val ok = LinkedHashMap<String, Resolved>()
        val bad = ArrayList<String>()
        map.values.forEach { def -> runCatching { ThemeLoader.resolve(def, map::get) }.onSuccess { ok[def.id] = it }.onFailure { bad += def.id } }
        return ok to bad
    }

    private fun group(all: Collection<ThemeDef>): List<ThemeFamily> = all.groupBy { it.family }.map { (id, members) ->
        val light = members.firstOrNull { it.variant == Variant.LIGHT }
        val dark = members.firstOrNull { it.variant == Variant.DARK }
        ThemeFamily(id, (light ?: dark)!!.name, light, dark)
    }

    private fun pick(): Resolved {
        val c = catalog
        val family = c.list.firstOrNull { it.id == familyId } ?: c.list.first { it.id == "minecraft" }
        return c.resolved.getValue(family.defFor(resolve(mode, LocalTime.now().hour) == Mode.DARK).id)
    }

    private fun refresh(force: Boolean = false) {
        val next = pick()
        if (force || next !== active) {
            active = next
            version++
        }
    }
}
