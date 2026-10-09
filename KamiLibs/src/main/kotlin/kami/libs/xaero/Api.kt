package kami.libs.xaero

import net.minecraft.network.chat.Component
import java.util.UUID

class MapAction(val label: Component, val enabled: Boolean = true, val run: () -> Unit)

class MapPoint(
    val id: String,
    val dim: String,
    val x: Int,
    val y: Int?,
    val z: Int,
    val name: String,
    val initials: String,
    val color: Int,
    val tooltip: List<Component> = emptyList(),
    val actions: List<MapAction> = emptyList(),
    val waypoint: Boolean = true
)

class ChunkPaint(val fill: Int, val north: Int, val east: Int, val south: Int, val west: Int)

class Tracked(val id: UUID, val dim: String, val x: Double, val y: Double, val z: Double)

class MapLine(val x1: Int, val z1: Int, val x2: Int, val z2: Int, val color: Int)

class MapLabel(val text: String, val x: Int, val z: Int, val color: Int, val scale: Float = 1f)

interface PointSource {
    val id: String
    val toggle: String
    fun rev(): Int
    fun points(dim: String): List<MapPoint>
}

interface ChunkLayer {
    val id: String
    val toggle: String
    val order: Int
    fun regionHash(dim: String, rx: Int, rz: Int): Int
    fun paint(dim: String, cx: Int, cz: Int): ChunkPaint?
    fun tooltip(dim: String, cx: Int, cz: Int, blunt: Boolean): Component?
}

fun interface InfoLine {
    fun lines(dim: String, x: Int, y: Int, z: Int): List<Component>
}

fun interface TrackedSource {
    fun players(): Collection<Tracked>
}

interface LineSource {
    val id: String
    val toggle: String
    fun lines(dim: String): List<MapLine>
    fun labels(dim: String): List<MapLabel>
}

fun interface PositionActions {
    fun actions(dim: String, x: Int, z: Int): List<MapAction>
}

class MapToggle(
    val id: String,
    val group: String,
    val label: String,
    val tip: String,
    val default: Boolean,
    val visible: () -> Boolean = { true }
)

class MapChoice(
    val id: String,
    val group: String,
    val label: String,
    val options: List<Pair<String, String>>,
    val default: String
)
