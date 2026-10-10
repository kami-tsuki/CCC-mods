package kami.libs.xaero

import kami.libs.xaero.impl.Bridge
import kami.libs.xaero.impl.Detect
import kami.libs.xaero.impl.Registry
import java.util.UUID

object XaeroMaps {
    fun waypoints(s: PointSource) = Registry.waypoints.add(s)
    fun markers(s: PointSource) = Registry.markers.add(s)
    fun layer(l: ChunkLayer) = Registry.layers.add(l)
    fun info(l: InfoLine) = Registry.infos.add(l)
    fun tracked(s: TrackedSource) = Registry.tracked.add(s)
    fun lines(s: LineSource) = Registry.lines.add(s)
    fun positionActions(p: PositionActions) = Registry.actions.add(p)
    fun fairPlay(f: () -> Boolean) = Registry.fairPlay.add(f)
    fun radarFilter(f: (UUID) -> Boolean) = Registry.radar.add(f)
    fun toggle(t: MapToggle) = Registry.putToggle(t)
    fun choice(c: MapChoice) = Registry.putChoice(c)
    fun tempWaypoint(p: MapPoint) = Registry.temp(p)
    fun refresh() = Registry.refresh()

    val available: Boolean get() = Detect.any
    val plus: Boolean get() = Detect.plus
    val revision: Int get() = Registry.rev

    internal fun init() = Bridge.init()

    fun on(id: String): Boolean = Registry.on(id)
    fun choice(id: String): String = Registry.choice(id)
}
