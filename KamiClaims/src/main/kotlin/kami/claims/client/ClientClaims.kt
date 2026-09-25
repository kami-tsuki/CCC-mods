package kami.claims.client

import kami.claims.service.View
import kami.libs.config.KamiConfig
import kami.libs.config.Section
import kotlinx.serialization.Serializable

@Serializable
class Prefs(var overlay: Boolean = true, var hud: Boolean = true, var labels: Boolean = true, var grid: Boolean = true, var claimable: Boolean = true)

object ClientClaims {
    private val config = KamiConfig(
        "claims", Prefs(),
        listOf(
            Section(
                "client.json", "Map and HUD settings of this game client. The buttons on the claims map change them too.",
                mapOf(
                    "overlay" to "Show claims on Xaero's maps.",
                    "hud" to "Show the territory HUD.",
                    "labels" to "Show chunk type letters on the claims map.",
                    "grid" to "Show the chunk grid on the claims map.",
                    "claimable" to "Mark chunks you could claim on the claims map."
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
    private val regions = HashMap<Long, Int>()

    fun savePrefs() = runCatching { config.save(prefs) }

    fun update(p: View.Payload) {
        dims = p.dims
        types = p.types
        countries = p.countries
        rev = p.rev
        maps = p.dims.map { HashMap<Long, View.Entry>() }
        p.entries.forEach { maps[it.dim.coerceIn(0, maps.size - 1)][key(it.x, it.z)] = it }
        regions.clear()
    }

    fun key(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    fun at(dim: String, x: Int, z: Int): View.Entry? = maps.getOrNull(dims.indexOf(dim))?.get(key(x, z))

    fun country(e: View.Entry) = countries.getOrNull(e.country)

    fun typeName(e: View.Entry) = types.getOrNull(e.type)

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
