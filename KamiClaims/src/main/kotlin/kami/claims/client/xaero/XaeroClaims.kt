package kami.claims.client.xaero

import kami.claims.client.ClientClaims
import kami.claims.client.ClientHooks
import kami.claims.client.VisibilityState
import kami.claims.client.app.Vocabulary
import kami.claims.client.store.ClaimsStore
import kami.claims.client.world.AccessGuess
import kami.claims.service.View
import kami.libs.ui.app.Route
import kami.libs.xaero.MapAction
import kami.libs.xaero.MapChoice
import kami.libs.xaero.MapPoint
import kami.libs.xaero.MapToggle
import kami.libs.xaero.PositionActions
import kami.libs.xaero.XaeroMaps
import net.minecraft.network.chat.Component

object XaeroClaims {
    private const val GROUP = "kami_claims.maps.group"

    private fun text(key: String) = Component.translatable(key).string

    fun register() {
        val p = ClientClaims.prefs
        val style = ClaimStyle({ p.xaeroMode }, { p.xaeroWarTint }, Vocabulary::relationColor, { Vocabulary.type(it).label })
        val data: ClaimData = ClientClaims
        XaeroMaps.layer(ClaimsChunkLayer(data, style))
        XaeroMaps.layer(WarChunkLayer(data))
        XaeroMaps.info(TerritoryInfo(data, style) { dim, x, z -> AccessGuess.allowed(dim, x, z, "place") })
        XaeroMaps.waypoints(ClaimPoints("claims.waypoints", "waypoints", data, ::text, ClientHooks::openRoute))
        XaeroMaps.markers(ClaimPoints("claims.markers", "markers", data, ::text, ClientHooks::openRoute))
        XaeroMaps.lines(ClaimBorders(data, style))
        XaeroMaps.positionActions(PositionActions { dim, x, z -> positionActions(data, dim, x, z) })
        XaeroMaps.tracked(VisibilityState)
        XaeroMaps.radarFilter(VisibilityState::isHidden)
        XaeroMaps.fairPlay { ClientClaims.war and View.WAR_FAIRPLAY != 0 }
        toggle("claims", { p.overlay }) { p.overlay = it }
        toggle("war", { p.xaeroWar }) { p.xaeroWar = it }
        toggle("war_tint", { p.xaeroWarTint }) { p.xaeroWarTint = it }
        toggle("waypoints", { p.xaeroWaypoints }) { p.xaeroWaypoints = it }
        toggle("markers", { p.xaeroMarkers }) { p.xaeroMarkers = it }
        toggle("tracked", { p.xaeroTracked }) { p.xaeroTracked = it }
        toggle("plus", { p.xaeroPlus }) { p.xaeroPlus = it }
        XaeroMaps.choice(
            MapChoice(
                "mode", GROUP, "kami_claims.maps.mode",
                listOf("political" to "kami_claims.maps.mode.political", "relations" to "kami_claims.maps.mode.relations"),
                { p.xaeroMode }, { p.xaeroMode = it; ClientClaims.savePrefs() }
            )
        )
    }

    private fun toggle(id: String, get: () -> Boolean, set: (Boolean) -> Unit) =
        XaeroMaps.toggle(MapToggle(id, GROUP, "kami_claims.maps.$id", "kami_claims.maps.$id.tip", get, { set(it); ClientClaims.savePrefs() }))

    private fun positionActions(data: ClaimData, dim: String, x: Int, z: Int): List<MapAction> {
        if (!data.enabled || data.view(dim) == null) return emptyList()
        val e = data.at(dim, x shr 4, z shr 4)
        val out = ArrayList<MapAction>()
        e?.let { data.country(it) }?.let { c -> out += MapAction(Component.translatable("kami_claims.xaero.action.country")) { ClientHooks.openRoute(Route("world", focus = "country:${c.name}")) } }
        if (e == null && ClaimsStore.info != null) {
            out += MapAction(Component.translatable("kami_claims.xaero.action.claim")) {
                ClientHooks.openRoute(Route("map", mapOf("x" to (x shr 4).toString(), "z" to (z shr 4).toString(), "dim" to dim, "plan" to "claim")))
            }
        }
        out += MapAction(Component.translatable("kami_claims.xaero.action.waypoint")) {
            XaeroMaps.tempWaypoint(MapPoint("claims.temp:$dim:$x:$z", dim, x, null, z, Component.translatable("kami_claims.xaero.point.temp", x, z).string, "W", 0x55FFFF))
        }
        return out
    }
}
