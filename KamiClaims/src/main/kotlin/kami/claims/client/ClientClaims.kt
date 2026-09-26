package kami.claims.client

import kami.claims.service.View
import kami.libs.config.KamiConfig
import kami.libs.config.Section
import kotlinx.serialization.Serializable

@Serializable
enum class BorderMode { OFF, AUTO, ALWAYS, BUILDER }

@Serializable
class Prefs(
    var overlay: Boolean = true,
    var hud: Boolean = true,
    var labels: Boolean = true,
    var grid: Boolean = true,
    var claimable: Boolean = true,
    var borderMode: BorderMode = BorderMode.AUTO,
    var borderDensity: Int = 300,
    var borderLines: Boolean = false,
    var blockHints: Boolean = true,
    var hudBorderDistance: Boolean = true,
    var worldToasts: Boolean = true,
    var mapMode: String = "political",
    var mapLayers: MutableSet<String> = mutableSetOf("borders", "labels", "markers", "grid"),
    var terrain: Boolean = true,
    var terrainCacheMb: Int = 256,
    var vision: String = "NORMAL",
    var reduceMotion: Boolean = false,
    var sounds: Float = 1f,
    var tooltipDelay: Int = 400,
    var holdSeconds: Float = 1.5f,
    var tourDone: Boolean = false,
    var skipClaimConfirm: Boolean = false,
    var dismissed: MutableSet<String> = mutableSetOf(),
    var hiddenSteps: Boolean = false,
    var denialTips: Int = 0
)

object ClientClaims {
    private val config = KamiConfig(
        "claims", Prefs(),
        listOf(
            Section(
                "client.json", "Settings of this game client for the country screen, the map, borders and the HUD. The settings page in game changes them too.",
                mapOf(
                    "overlay" to "Show claims on Xaero's maps.",
                    "hud" to "Show the territory HUD.",
                    "labels" to "Show country names on the claims map.",
                    "grid" to "Show the chunk grid on the claims map.",
                    "claimable" to "Mark plots you could claim on the claims map.",
                    "borderMode" to "Border display in the world: OFF, AUTO (near borders and when blocked), ALWAYS or BUILDER.",
                    "borderDensity" to "Most border particles per second.",
                    "borderLines" to "Draw borders as thin walls instead of particles.",
                    "blockHints" to "Colour the block outline red when you can't build there and amber across your border.",
                    "hudBorderDistance" to "Show the distance to the nearest border in the HUD.",
                    "worldToasts" to "Show warnings like debt as small notifications while playing.",
                    "mapMode" to "Map mode the claims map opens with.",
                    "mapLayers" to "Map layers that are switched on.",
                    "terrain" to "Draw terrain from explored chunks on the claims map.",
                    "terrainCacheMb" to "Most disk space for the map terrain cache, in megabytes.",
                    "vision" to "Colour set: NORMAL, DEUTERANOPIA, PROTANOPIA or TRITANOPIA.",
                    "reduceMotion" to "Turn off pulsing, sliding and flashing.",
                    "sounds" to "Volume of interface sounds, 0 to 1.",
                    "tooltipDelay" to "Milliseconds before a tooltip shows.",
                    "holdSeconds" to "Seconds to hold a button for dangerous actions.",
                    "tourDone" to "The guided tour was finished or skipped.",
                    "skipClaimConfirm" to "Claim without a confirmation when the treasury lasts at least 7 more days.",
                    "dismissed" to "Alerts you dismissed.",
                    "hiddenSteps" to "The next steps checklist is hidden.",
                    "denialTips" to "How often the border tip was shown after a blocked action."
                )
            )
        ),
        legacy = "kami_claims_client.json", reloadable = false
    )
    val prefs: Prefs by lazy { config.load(); config.value }

    var dims: List<String> = emptyList()
        private set
    var types: List<String> = emptyList()
        private set
    var countries: List<View.CountryView> = emptyList()
        private set
    var rev = -1
        private set
    var viewingAs = ""
    private var maps: List<HashMap<Long, View.Entry>> = emptyList()
    private var reserves: List<HashSet<Long>> = emptyList()
    private val regions = HashMap<Long, Int>()

    fun savePrefs() = runCatching { config.save(prefs) }

    fun update(p: View.Payload) {
        dims = p.dims
        types = p.types
        countries = p.countries
        rev = p.rev
        maps = p.dims.map { HashMap<Long, View.Entry>() }
        p.entries.forEach { maps[it.dim.coerceIn(0, maps.size - 1)][key(it.x, it.z)] = it }
        reserves = p.dims.map { HashSet<Long>() }
        p.reserved.forEach { reserves.getOrNull(it.dim)?.add(key(it.x, it.z)) }
        regions.clear()
    }

    fun key(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    fun at(dim: String, x: Int, z: Int): View.Entry? = maps.getOrNull(dims.indexOf(dim))?.get(key(x, z))

    fun reserved(dim: String, x: Int, z: Int) = reserves.getOrNull(dims.indexOf(dim))?.contains(key(x, z)) == true

    fun country(e: View.Entry) = countries.getOrNull(e.country)

    fun typeName(e: View.Entry) = types.getOrNull(e.type)

    fun active(dim: String) = dim in dims

    private fun regionKey(dim: String, rx: Int, rz: Int) = key(rx, rz) * 31 + dim.hashCode()

    fun regionHash(dim: String, rx: Int, rz: Int): Int = regions.getOrPut(regionKey(dim, rx, rz)) {
        var h = 1
        var found = false
        val map = maps.getOrNull(dims.indexOf(dim)) ?: return@getOrPut 0
        for (x in rx * 32 until rx * 32 + 32) for (z in rz * 32 until rz * 32 + 32) {
            val e = map[key(x, z)] ?: continue
            found = true
            h = 31 * h + (e.country * 131 + e.flags * 17 + e.type + x * 7 + z * 3)
        }
        if (!found) 0 else if (h == 0) 1 else h
    }
}
