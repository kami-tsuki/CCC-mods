package kami.claims.client

import kami.claims.client.hud.TaxHudMode
import kami.claims.client.xaero.ClaimData
import kami.claims.client.xaero.chunkKey
import kami.claims.service.View
import kami.libs.config.KamiConfig
import kami.libs.config.Section
import kami.libs.log.Log
import kami.libs.ui.app.NavPrefs
import kami.libs.xaero.XaeroMaps
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable

@Serializable
enum class BorderMode { OFF, AUTO, ALWAYS, BUILDER }

@Serializable
enum class WarDisplay { NONE, BOSS, HUD, BOTH }

@Serializable
class Prefs(
    var overlay: Boolean = true,
    var hud: Boolean = true,
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
    var holdSeconds: Float = 1.5f,
    var tourDone: Boolean = false,
    var skipClaimConfirm: Boolean = false,
    var dismissed: MutableSet<String> = mutableSetOf(),
    var hiddenSteps: Boolean = false,
    var denialTips: Int = 0,
    override var railCollapsed: Boolean = false,
    override var collapsedGroups: MutableSet<String> = mutableSetOf(),
    var warDisplay: WarDisplay = WarDisplay.BOTH,
    var taxHud: TaxHudMode = TaxHudMode.OFF,
    var taxCondDebt: Boolean = true,
    var taxCondPrepaid: Boolean = true,
    var taxPrepaidDays: Int = 3,
    var taxCondDue: Boolean = false,
    var taxDueHours: Int = 12,
    var taxCondLapse: Boolean = true,
    var taxLapseDays: Int = 3
) : NavPrefs

object ClientClaims : ClaimData {
    private val config = KamiConfig(
        "claims", Prefs(),
        listOf(
            Section(
                "client.json", "Settings of this game client for the country screen, the map, borders and the HUD. The settings page in game changes them too.",
                mapOf(
                    "overlay" to "Show claims on Xaero's maps.",
                    "hud" to "Show the territory HUD.",
                    "claimable" to "Mark plots you could claim on the claims map.",
                    "borderMode" to "Border display in the world: OFF, AUTO (near borders and when blocked), ALWAYS or BUILDER.",
                    "borderDensity" to "Most border particles per second.",
                    "borderLines" to "Draw borders as thin walls instead of particles.",
                    "blockHints" to "Color the block outline red when you can't build there and amber across your border.",
                    "hudBorderDistance" to "Show the distance to the nearest border in the HUD.",
                    "worldToasts" to "Show warnings like debt as small notifications while playing.",
                    "mapMode" to "Map mode the claims map opens with.",
                    "mapLayers" to "Map layers that are switched on.",
                    "terrain" to "Draw terrain from explored chunks on the claims map.",
                    "terrainCacheMb" to "Most disk space for the map terrain cache, in megabytes.",
                    "holdSeconds" to "Seconds to hold a button for dangerous actions.",
                    "tourDone" to "The guided tour was finished or skipped.",
                    "skipClaimConfirm" to "Skip claim confirmation when the treasury lasts 7+ days.",
                    "dismissed" to "Alerts you dismissed.",
                    "hiddenSteps" to "The goals checklist is hidden.",
                    "denialTips" to "How often the border tip was shown after a blocked action.",
                    "railCollapsed" to "The sidebar is collapsed to icons.",
                    "collapsedGroups" to "Navigation groups you collapsed.",
                    "warDisplay" to "Where the war status shows: NONE, BOSS (boss bar), HUD or BOTH.",
                    "taxHud" to "Tax timer HUD: OFF, ALWAYS or IMPORTANT (only when a condition below is met).",
                    "taxCondDebt" to "Tax timer counts unpaid rent debt as important.",
                    "taxCondPrepaid" to "Tax timer counts low prepaid rent as important.",
                    "taxPrepaidDays" to "Prepaid rent at or below this many days of rent is important.",
                    "taxCondDue" to "Tax timer counts a rent bill that is due soon as important.",
                    "taxDueHours" to "Rent due within this many hours is important.",
                    "taxCondLapse" to "Tax timer counts claims that will soon lapse as important.",
                    "taxLapseDays" to "Claims lapsing within this many days are important."
                )
            )
        ),
        legacy = "kami_claims_client.json", reloadable = false
    )
    val prefs: Prefs by lazy { config.load(); config.value }

    @Volatile
    var dims: List<String> = emptyList()
        private set
    @Volatile
    var types: List<String> = emptyList()
        private set
    @Volatile
    var countries: List<View.CountryView> = emptyList()
        private set
    @Volatile
    override var rev = -1
        private set
    var war = 0
    var viewingAs = ""

    @Volatile
    private var maps: List<Map<Long, View.Entry>> = emptyList()

    @Volatile
    private var reserves: List<Set<Long>> = emptyList()

    @Volatile
    private var regions = ConcurrentHashMap<Long, Int>()

    private val log = Log.of("claims")

    fun savePrefs() = runCatching { config.save(prefs) }.onFailure { log.warn("Could not save client.json: {}", it.message) }

    fun finishTour() {
        prefs.tourDone = true
        savePrefs()
    }

    fun update(p: View.Payload) {
        val built = p.dims.map { HashMap<Long, View.Entry>() }
        p.entries.forEach { built[it.dim.coerceIn(0, built.size - 1)][key(it.x, it.z)] = it }
        val held = p.dims.map { HashSet<Long>() }
        p.reserved.forEach { held.getOrNull(it.dim)?.add(key(it.x, it.z)) }
        regions = ConcurrentHashMap()
        maps = built
        reserves = held
        dims = p.dims
        types = p.types
        countries = p.countries
        rev = p.rev
        war = p.war
        XaeroMaps.refresh()
    }

    fun key(x: Int, z: Int) = chunkKey(x, z)

    override val enabled get() = prefs.overlay

    override fun at(dim: String, x: Int, z: Int): View.Entry? = maps.getOrNull(dims.indexOf(dim))?.get(key(x, z))

    override fun view(dim: String): Map<Long, View.Entry>? = maps.getOrNull(dims.indexOf(dim))

    fun reservedView(dim: String): Set<Long>? = reserves.getOrNull(dims.indexOf(dim))

    override fun reserved(dim: String, x: Int, z: Int) = reserves.getOrNull(dims.indexOf(dim))?.contains(key(x, z)) == true

    override fun country(e: View.Entry) = countries.getOrNull(e.country)

    override fun typeName(e: View.Entry) = types.getOrNull(e.type)

    fun active(dim: String) = dim in dims

    private fun regionKey(dim: String, rx: Int, rz: Int) = key(rx, rz) * 31 + dim.hashCode()

    override fun regionHash(dim: String, rx: Int, rz: Int): Int {
        val index = dims.indexOf(dim)
        val map = maps.getOrNull(index) ?: return 0
        val reserved = reserves.getOrNull(index)
        return regions.computeIfAbsent(regionKey(dim, rx, rz)) {
            var h = 1
            var found = false
            for (x in rx * 32 until rx * 32 + 32) for (z in rz * 32 until rz * 32 + 32) {
                val e = map[key(x, z)]
                if (e == null) {
                    if (reserved?.contains(key(x, z)) == true) { found = true; h = 31 * h + (x * 5 + z * 11) }
                    continue
                }
                found = true
                h = 31 * h + (e.country * 131 + e.flags * 17 + e.type + x * 7 + z * 3)
            }
            if (!found) 0 else if (h == 0) 1 else h
        }
    }
}
