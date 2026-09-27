package kami.libs.ui.map

import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import org.lwjgl.glfw.GLFW
import kotlin.math.max

class Viewport(var unitsPerPx: Double = 1.0, val minUnitsPerPx: Double = 0.05, val maxUnitsPerPx: Double = 64.0) {
    var cx = 0.0
    var cz = 0.0
    var view = Rect.ZERO
    var locked = false
    var dragging = false
        private set
    private var lastX = 0
    private var lastY = 0

    private val midX get() = view.x + view.w / 2.0
    private val midY get() = view.y + view.h / 2.0

    fun worldX(px: Double) = cx + (px - midX) * unitsPerPx
    fun worldZ(py: Double) = cz + (py - midY) * unitsPerPx
    fun screenX(wx: Double) = midX + (wx - cx) / unitsPerPx
    fun screenY(wz: Double) = midY + (wz - cz) / unitsPerPx
    fun contains(px: Double, py: Double) = px >= view.x && px < view.right && py >= view.y && py < view.bottom

    fun zoomAt(factor: Double, px: Double = midX, py: Double = midY): Boolean {
        if (locked) return false
        val wx = worldX(px)
        val wz = worldZ(py)
        val next = (unitsPerPx * factor).coerceIn(minUnitsPerPx, maxUnitsPerPx)
        if (next == unitsPerPx) return false
        unitsPerPx = next
        cx = wx - (px - midX) * unitsPerPx
        cz = wz - (py - midY) * unitsPerPx
        return true
    }

    fun pan(dxPx: Double, dyPx: Double): Boolean {
        if (locked || (dxPx == 0.0 && dyPx == 0.0)) return false
        cx -= dxPx * unitsPerPx
        cz -= dyPx * unitsPerPx
        return true
    }

    fun fit(x0: Double, z0: Double, w: Double, h: Double, fill: Double = 1.0) {
        cx = x0 + w / 2
        cz = z0 + h / 2
        unitsPerPx = max(w / max(1, view.w), h / max(1, view.h)) / fill
    }

    fun interact(ui: Ui, r: Rect, zoomStep: Double = 1.25, key: Any = "viewport"): Boolean {
        view = r
        var changed = false
        val wheel = ui.wheel(r)
        if (wheel != 0.0) changed = zoomAt(if (wheel > 0) 1 / zoomStep else zoomStep, ui.mouseX.toDouble(), ui.mouseY.toDouble())
        ui.pressed(r)?.let {
            dragging = !locked
            lastX = it.x; lastY = it.y
            if (dragging) ui.active = ui.id("$key:drag")
        }
        if (dragging && !ui.isDown()) dragging = false
        if (dragging) {
            changed = pan((ui.mouseX - lastX).toDouble(), (ui.mouseY - lastY).toDouble()) || changed
            lastX = ui.mouseX; lastY = ui.mouseY
            ui.cursor = Cursor.MOVE
        }
        if (ui.hovering(r) && !ui.typing) changed = keyboard(ui, zoomStep) || changed
        return changed
    }

    private fun keyboard(ui: Ui, zoomStep: Double): Boolean {
        val step = if (ui.input.shift) 240.0 else 60.0
        var changed = false
        listOf(GLFW.GLFW_KEY_LEFT to (step to 0.0), GLFW.GLFW_KEY_RIGHT to (-step to 0.0), GLFW.GLFW_KEY_UP to (0.0 to step), GLFW.GLFW_KEY_DOWN to (0.0 to -step),
            GLFW.GLFW_KEY_A to (step to 0.0), GLFW.GLFW_KEY_D to (-step to 0.0), GLFW.GLFW_KEY_W to (0.0 to step), GLFW.GLFW_KEY_S to (0.0 to -step)).forEach { (k, d) ->
            if (ui.input.takeKey(k) { !it.ctrl } != null) changed = pan(d.first, d.second) || changed
        }
        if (ui.input.takeKey(GLFW.GLFW_KEY_EQUAL) != null || ui.input.takeKey(GLFW.GLFW_KEY_KP_ADD) != null) changed = zoomAt(1 / zoomStep) || changed
        if (ui.input.takeKey(GLFW.GLFW_KEY_MINUS) != null || ui.input.takeKey(GLFW.GLFW_KEY_KP_SUBTRACT) != null) changed = zoomAt(zoomStep) || changed
        return changed
    }
}
