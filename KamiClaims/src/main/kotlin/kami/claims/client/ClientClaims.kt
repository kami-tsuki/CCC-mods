package kami.claims.client

import kami.claims.client.hud.TaxHudMode
import kami.claims.client.compat.xaero.ClaimData
import kami.claims.client.compat.xaero.chunkKey
import kami.claims.service.View
import kami.libs.config.Configs
import kami.libs.ui.settings.ClientSettings
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
    var taxLapseDays: Int = 3,
    var pressAutosave: Boolean = true
) : NavPrefs

object ClientClaims : ClaimData {
    private val section by lazy { ClientSettings.register("mods.claims", Prefs.serializer(), Prefs(), legacy = Configs.dir("claims").resolve("client.json")) }
    val prefs: Prefs get() = section.value

    private class Snapshot(
        val dims: List<String>, val types: List<String>, val countries: List<View.CountryView>, val rev: Int,
        val maps: List<Map<Long, View.Entry>>, val reserves: List<Set<Long>>
    ) {
        val regions = ConcurrentHashMap<Long, Int>()
    }

    @Volatile
    private var snap = Snapshot(emptyList(), emptyList(), emptyList(), -1, emptyList(), emptyList())

    val dims get() = snap.dims
    val types get() = snap.types
    val countries get() = snap.countries
    override val rev get() = snap.rev
    var war = 0
    var viewingAs = ""

    fun savePrefs() = ClientSettings.save()

    fun finishTour() {
        prefs.tourDone = true
        savePrefs()
    }

    fun update(p: View.Payload) {
        val built = p.dims.map { HashMap<Long, View.Entry>() }
        p.entries.forEach { built.getOrNull(it.dim)?.set(key(it.x, it.z), it) }
        val held = p.dims.map { HashSet<Long>() }
        p.reserved.forEach { held.getOrNull(it.dim)?.add(key(it.x, it.z)) }
        snap = Snapshot(p.dims, p.types, p.countries, p.rev, built, held)
        war = p.war
        XaeroMaps.refresh()
    }

    fun clear() {
        snap = Snapshot(emptyList(), emptyList(), emptyList(), -1, emptyList(), emptyList())
        war = 0
        viewingAs = ""
        XaeroMaps.refresh()
    }

    fun key(x: Int, z: Int) = chunkKey(x, z)

    override val enabled get() = prefs.overlay

    override fun at(dim: String, x: Int, z: Int): View.Entry? = snap.let { it.maps.getOrNull(it.dims.indexOf(dim))?.get(key(x, z)) }

    override fun view(dim: String): Map<Long, View.Entry>? = snap.let { it.maps.getOrNull(it.dims.indexOf(dim)) }

    fun reservedView(dim: String): Set<Long>? = snap.let { it.reserves.getOrNull(it.dims.indexOf(dim)) }

    override fun reserved(dim: String, x: Int, z: Int) = reservedView(dim)?.contains(key(x, z)) == true

    override fun country(e: View.Entry) = countries.getOrNull(e.country)

    override fun typeName(e: View.Entry) = types.getOrNull(e.type)

    fun active(dim: String) = dim in dims

    private fun regionKey(dim: String, rx: Int, rz: Int) = key(rx, rz) * 31 + dim.hashCode()

    override fun regionHash(dim: String, rx: Int, rz: Int): Int {
        val s = snap
        val index = s.dims.indexOf(dim)
        val map = s.maps.getOrNull(index) ?: return 0
        val reserved = s.reserves.getOrNull(index)
        return s.regions.computeIfAbsent(regionKey(dim, rx, rz)) {
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
