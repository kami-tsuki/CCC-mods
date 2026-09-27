package kami.libs.ui.map

import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

class ChunkPoint(val x: Int, val z: Int)

class MapInput(val hover: ChunkPoint?, val click: ChunkPoint?, val rightClick: ChunkPoint?, val dragStart: ChunkPoint?, val dragging: ChunkPoint?, val dragEnd: ChunkPoint?, val doubleClick: ChunkPoint?)

class ChunkMap(var zoom: Float = 12f) {
    var cx = 0.0
    var cz = 0.0
    var view = Rect.ZERO
        private set
    private var targetZoom = zoom
    private var panButton = -1
    private var lastX = 0
    private var lastY = 0
    private var pressX = 0
    private var pressY = 0
    private var moved = false
    private var selecting = false
    private var anchor: ChunkPoint? = null
    private var lastClick = 0L
    private var lastClickChunk: ChunkPoint? = null

    val levels = listOf(2f, 3f, 4f, 6f, 8f, 10f, 12f, 16f, 20f, 24f, 32f, 48f)

    fun chunkAt(px: Int, py: Int) = ChunkPoint(floor(cx + (px - view.centerX) / zoom).toInt(), floor(cz + (py - view.centerY) / zoom).toInt())
    fun sx(x: Double) = (view.centerX + (x - cx) * zoom).roundToInt()
    fun sz(z: Double) = (view.centerY + (z - cz) * zoom).roundToInt()
    fun cell(x: Int, z: Int): Rect {
        val x0 = sx(x.toDouble()); val z0 = sz(z.toDouble())
        return Rect(x0, z0, sx(x + 1.0) - x0, sz(z + 1.0) - z0)
    }
    fun area(x1: Int, z1: Int, x2: Int, z2: Int): Rect {
        val x0 = sx(minOf(x1, x2).toDouble()); val z0 = sz(minOf(z1, z2).toDouble())
        return Rect(x0, z0, sx(maxOf(x1, x2) + 1.0) - x0, sz(maxOf(z1, z2) + 1.0) - z0)
    }

    fun visibleX() = floor(cx - view.w / 2.0 / zoom).toInt() - 1..floor(cx + view.w / 2.0 / zoom).toInt() + 1
    fun visibleZ() = floor(cz - view.h / 2.0 / zoom).toInt() - 1..floor(cz + view.h / 2.0 / zoom).toInt() + 1

    fun center(x: Int, z: Int) { cx = x + 0.5; cz = z + 0.5 }

    fun zoomBy(steps: Int) {
        val i = levels.indexOfFirst { it >= targetZoom }.let { if (it < 0) levels.lastIndex else it }
        targetZoom = levels[(i + steps).coerceIn(0, levels.lastIndex)]
    }

    fun zoomTo(value: Float) { targetZoom = value.coerceIn(levels.first(), levels.last()) }

    fun update(ui: Ui) {
        if (zoom == targetZoom) return
        zoom = if (ui.reduceMotion) targetZoom else zoom + (targetZoom - zoom) * (1 - kotlin.math.exp(-16f * ui.dt))
        if (abs(zoom - targetZoom) < 0.05f) zoom = targetZoom
    }

    fun drawBase(ui: Ui, r: Rect, dim: String, terrain: Boolean, grid: Boolean) {
        view = r
        update(ui)
        Draw.fill(ui.g, r, Palette.sunken)
        if (!terrain) {
            Draw.fill(ui.g, r, Palette.alpha(Palette.surface, 0xE8))
            return
        }
        val tiles = TerrainCache.TILE_CHUNKS
        val xs = visibleX()
        val zs = visibleZ()
        for (rx in Math.floorDiv(xs.first, tiles)..Math.floorDiv(xs.last, tiles)) for (rz in Math.floorDiv(zs.first, tiles)..Math.floorDiv(zs.last, tiles)) {
            val tile = TerrainCache.tile(dim, rx, rz, false) ?: continue
            val dest = area(rx * tiles, rz * tiles, rx * tiles + tiles - 1, rz * tiles + tiles - 1)
            ui.g.blit(tile.bind(), dest.x, dest.y, dest.w, dest.h, 0f, 0f, tiles * 16, tiles * 16, tiles * 16, tiles * 16)
        }
        if (grid && zoom >= 8) ui.g.drawManaged {
            val line = Palette.alpha(0x000000, 0x1A)
            for (x in xs) Draw.vline(ui.g, sx(x.toDouble()), r.y, r.h, line)
            for (z in zs) Draw.hline(ui.g, r.x, sz(z.toDouble()), r.w, line)
        }
    }

    fun interact(ui: Ui, r: Rect, selectWithLeft: Boolean, key: Any = "map"): MapInput {
        val over = ui.hovering(r)
        val hover = if (over) chunkAt(ui.mouseX, ui.mouseY) else null
        var click: ChunkPoint? = null
        var right: ChunkPoint? = null
        var dragEnd: ChunkPoint? = null
        var double: ChunkPoint? = null
        val wheel = ui.wheel(r)
        if (wheel != 0.0) {
            val before = chunkPointExact(ui.mouseX, ui.mouseY)
            zoomBy(if (wheel > 0) 1 else -1)
            zoom = targetZoom
            val after = chunkPointExact(ui.mouseX, ui.mouseY)
            cx += before.first - after.first
            cz += before.second - after.second
        }
        for (b in listOf(0, 1, 2)) ui.pressed(r, b)?.let {
            panButton = b
            lastX = it.x; lastY = it.y; pressX = it.x; pressY = it.y
            moved = false
            selecting = b == 0 && (selectWithLeft || ui.input.shift)
            anchor = if (selecting) chunkAt(it.x, it.y) else null
            ui.active = ui.id("$key:drag")
        }
        val dragging = panButton >= 0 && ui.isDown(panButton)
        if (dragging) {
            if (abs(ui.mouseX - pressX) + abs(ui.mouseY - pressY) > 3) moved = true
            if (!selecting && moved) {
                cx -= (ui.mouseX - lastX) / zoom
                cz -= (ui.mouseY - lastY) / zoom
                ui.cursor = Cursor.MOVE
            }
            lastX = ui.mouseX; lastY = ui.mouseY
        }
        val release = ui.input.releases.firstOrNull { it.button == panButton }
        if (panButton >= 0 && release != null) {
            val at = chunkAt(release.x, release.y)
            when {
                panButton == 1 && !moved -> right = at
                panButton == 0 && selecting && moved -> dragEnd = at
                panButton == 0 && !moved -> {
                    click = at
                    val now = System.currentTimeMillis()
                    if (lastClickChunk?.let { it.x == at.x && it.z == at.z } == true && now - lastClick < 350) double = at
                    lastClick = now
                    lastClickChunk = at
                }
            }
            val start = anchor
            panButton = -1
            selecting = false
            anchor = null
            return MapInput(hover, click, right, start, null, dragEnd, double)
        }
        if (over) keyboard(ui)
        return MapInput(hover, null, null, anchor, if (selecting && moved) chunkAt(ui.mouseX, ui.mouseY) else null, null, null)
    }

    private fun chunkPointExact(px: Int, py: Int) = (cx + (px - view.centerX) / zoom) to (cz + (py - view.centerY) / zoom)

    private fun keyboard(ui: Ui) {
        if (ui.typing) return
        val step = (if (ui.input.shift) 8.0 else 2.0) * 12 / zoom
        listOf(GLFW.GLFW_KEY_LEFT to (-step to 0.0), GLFW.GLFW_KEY_RIGHT to (step to 0.0), GLFW.GLFW_KEY_UP to (0.0 to -step), GLFW.GLFW_KEY_DOWN to (0.0 to step),
            GLFW.GLFW_KEY_A to (-step to 0.0), GLFW.GLFW_KEY_D to (step to 0.0), GLFW.GLFW_KEY_W to (0.0 to -step), GLFW.GLFW_KEY_S to (0.0 to step)).forEach { (k, d) ->
            if (ui.input.takeKey(k) { !it.ctrl } != null) { cx += d.first; cz += d.second }
        }
        if (ui.input.takeKey(GLFW.GLFW_KEY_EQUAL) != null || ui.input.takeKey(GLFW.GLFW_KEY_KP_ADD) != null) zoomBy(1)
        if (ui.input.takeKey(GLFW.GLFW_KEY_MINUS) != null || ui.input.takeKey(GLFW.GLFW_KEY_KP_SUBTRACT) != null) zoomBy(-1)
    }
}
