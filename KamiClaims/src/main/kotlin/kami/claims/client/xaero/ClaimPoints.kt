package kami.claims.client.xaero

import kami.claims.service.View
import kami.libs.ui.app.Route
import kami.libs.xaero.MapAction
import kami.libs.xaero.MapPoint
import kami.libs.xaero.PointSource
import kami.libs.xaero.XaeroMaps
import net.minecraft.network.chat.Component

class ClaimPoints(
    override val id: String,
    override val toggle: String,
    private val data: ClaimData,
    private val tr: (String) -> String,
    private val open: (Route) -> Unit
) : PointSource {
    override fun rev() = data.rev

    override fun points(dim: String): List<MapPoint> {
        if (!data.enabled) return emptyList()
        val out = ArrayList<MapPoint>()
        data.view(dim)?.values?.forEach { e ->
            val own = e.flags and View.OWN != 0
            val kind = when {
                own && e.flags and View.CAPITAL != 0 -> "capital"
                e.flags and View.MINE != 0 -> "plot"
                e.flags and View.SAFE != 0 && own -> "safe"
                else -> return@forEach
            }
            out += point(dim, kind, e)
        }
        return out
    }

    private fun point(dim: String, kind: String, e: View.Entry): MapPoint {
        val x = e.x * 16 + 8
        val z = e.z * 16 + 8
        val name = tr("kami_claims.xaero.point.$kind")
        val (initials, color) = when (kind) {
            "capital" -> "C" to 0xFFAA00
            "plot" -> "P" to 0x55FFFF
            else -> "S" to SAFE_GREEN
        }
        val plain = MapPoint("$id:$dim:$kind:${e.x}:${e.z}", dim, x, null, z, name, initials, color)
        val actions = ArrayList<MapAction>()
        if (kind == "capital") actions += MapAction(Component.translatable("kami_claims.xaero.action.dashboard")) { open(Route("dashboard")) }
        if (kind == "plot") actions += MapAction(Component.translatable("kami_claims.xaero.action.plots")) { open(Route("plots", mapOf("dim" to dim), "${e.x}:${e.z}")) }
        actions += MapAction(Component.translatable("kami_claims.xaero.action.map")) { open(Route("map", mapOf("x" to e.x.toString(), "z" to e.z.toString(), "dim" to dim))) }
        actions += MapAction(Component.translatable("kami_claims.xaero.action.waypoint")) { XaeroMaps.tempWaypoint(plain) }
        return MapPoint(plain.id, dim, x, null, z, name, initials, color, listOf(Component.literal(name)), actions)
    }
}
