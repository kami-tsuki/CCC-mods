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
    var xaeroMode: String = "political",
    var xaeroWar: Boolean = true,
    var xaeroWarTint: Boolean = true,
    var xaeroWaypoints: Boolean = true,
    var xaeroMarkers: Boolean = true,
    var xaeroTracked: Boolean = true,
    var xaeroPlus: Boolean = true,
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
                    "xaeroMode" to "Xaero map colors: political (country colors) or relations (your relation to each country).",
                    "xaeroWar" to "Show war chunks (safe, staked, enemy) on Xaero's maps.",
                    "xaeroWarTint" to "Tint enemy chunks red on Xaero's maps.",
                    "xaeroWaypoints" to "Show your capital, plots and safe chunks as Xaero minimap waypoints.",
                    "xaeroMarkers" to "Show your capital, plots and safe chunks as Xaero world map markers.",
                    "xaeroTracked" to "Show country members out of render range on Xaero's maps.",
                    "xaeroPlus" to "Draw country borders and names on XaeroPlus maps.",
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

    var dims: List<String> = emptyList()
        private set
    var types: List<String> = emptyList()
        private set
    var countries: List<View.CountryView> = emptyList()
        private set
    override var rev = -1
        private set
    var war = 0
    var viewingAs = ""
    private var maps: List<HashMap<Long, View.Entry>> = emptyList()
    private var reserves: List<HashSet<Long>> = emptyList()
    private val regions = HashMap<Long, Int>()

    private val log = Log.of("claims")

    fun savePrefs() = runCatching { config.save(prefs) }.onFailure { log.warn("Could not save client.json: {}", it.message) }

    fun finishTour() {
        prefs.tourDone = true
        savePrefs()
    }

    fun update(p: View.Payload) {
        dims = p.dims
        types = p.types
        countries = p.countries
        rev = p.rev
        maps = p.dims.map { HashMap<Long, View.Entry>() }
        p.entries.forEach { maps[it.dim.coerceIn(0, maps.size - 1)][key(it.x, it.z)] = it }
        reserves = p.dims.map { HashSet<Long>() }
        p.reserved.forEach { reserves.getOrNull(it.dim)?.add(key(it.x, it.z)) }
        war = p.war
        regions.clear()
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

    override fun regionHash(dim: String, rx: Int, rz: Int): Int = regions.getOrPut(regionKey(dim, rx, rz)) {
        var h = 1
        var found = false
        val map = maps.getOrNull(dims.indexOf(dim)) ?: return@getOrPut 0
        for (x in rx * 32 until rx * 32 + 32) for (z in rz * 32 until rz * 32 + 32) {
            val e = map[key(x, z)]
            if (e == null) {
                if (reserves.getOrNull(dims.indexOf(dim))?.contains(key(x, z)) == true) { found = true; h = 31 * h + (x * 5 + z * 11) }
                continue
            }
            found = true
            h = 31 * h + (e.country * 131 + e.flags * 17 + e.type + x * 7 + z * 3)
        }
        if (!found) 0 else if (h == 0) 1 else h
    }
}
