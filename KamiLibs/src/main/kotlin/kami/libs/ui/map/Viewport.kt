package kami.libs.ui.map

import kami.libs.ui.anim.Spring
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Slop
import kami.libs.ui.core.Ui
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private const val GLIDE_STIFFNESS = 110f
private const val X = 0
private const val Z = 1
private const val SCALE = 2
private const val MAX_WHEEL = 3.0

class Viewport(var unitsPerPx: Double = 1.0, minUnitsPerPx: Double = 0.05, maxUnitsPerPx: Double = 64.0) {
    var minUnitsPerPx = minUnitsPerPx
        private set
    var maxUnitsPerPx = maxUnitsPerPx
        private set
    var cx = 0.0
    var cz = 0.0
    var view = Rect.ZERO
    var locked = false
    var dragging = false
        private set
    var tapped = false
        private set
    var tapX = 0
        private set
    var tapY = 0
        private set
    private var armed = false
    private var lastX = 0
    private var lastY = 0
    private var pressX = 0
    private var pressY = 0
    private var bounds: DoubleArray? = null
    private val glideProgress = Spring(0f)
    private val glideFrom = DoubleArray(3)
    private val glideTo = DoubleArray(3)
    private var gliding = false

    private val midX get() = view.x + view.w / 2.0
    private val midY get() = view.y + view.h / 2.0

    fun worldX(px: Double) = cx + (px - midX) * unitsPerPx
    fun worldZ(py: Double) = cz + (py - midY) * unitsPerPx
    fun screenX(wx: Double) = midX + (wx - cx) / unitsPerPx
    fun screenY(wz: Double) = midY + (wz - cz) / unitsPerPx
    fun contains(px: Double, py: Double) = px >= view.x && px < view.right && py >= view.y && py < view.bottom

    fun limit(x0: Double, z0: Double, w: Double, h: Double) {
        bounds = doubleArrayOf(x0, z0, x0 + w, z0 + h)
        clamp()
    }

    fun clamp() {
        val b = bounds ?: return
        cx = cx.coerceIn(b[0], b[2])
        cz = cz.coerceIn(b[1], b[3])
    }

    fun zoomAt(factor: Double, px: Double = midX, py: Double = midY): Boolean {
        if (locked) return false
        val wx = worldX(px)
        val wz = worldZ(py)
        val next = (unitsPerPx * factor).coerceIn(minUnitsPerPx, maxUnitsPerPx)
        if (next == unitsPerPx) return false
        unitsPerPx = next
        cx = wx - (px - midX) * unitsPerPx
        cz = wz - (py - midY) * unitsPerPx
        clamp()
        return true
    }

    fun pan(dxPx: Double, dyPx: Double): Boolean {
        if (locked || (dxPx == 0.0 && dyPx == 0.0)) return false
        cx -= dxPx * unitsPerPx
        cz -= dyPx * unitsPerPx
        clamp()
        return true
    }

    fun fit(x0: Double, z0: Double, w: Double, h: Double, fill: Double = 1.0) {
        cx = x0 + w / 2
        cz = z0 + h / 2
        unitsPerPx = max(w / max(1, view.w), h / max(1, view.h)) / fill
        minUnitsPerPx = min(minUnitsPerPx, unitsPerPx)
        maxUnitsPerPx = max(maxUnitsPerPx, unitsPerPx)
    }

    fun range(min: Double, max: Double) {
        minUnitsPerPx = min
        maxUnitsPerPx = max
        unitsPerPx = unitsPerPx.coerceIn(min, max)
    }

    fun glide(targetX: Double, targetZ: Double, targetUnitsPerPx: Double = unitsPerPx) {
        glideFrom[X] = cx; glideFrom[Z] = cz; glideFrom[SCALE] = unitsPerPx
        glideTo[X] = targetX; glideTo[Z] = targetZ; glideTo[SCALE] = targetUnitsPerPx
        glideProgress.value = 0f
        glideProgress.velocity = 0f
        gliding = true
    }

    fun cancelGlide() { gliding = false }

    fun step(dt: Float, instant: Boolean) {
        if (!gliding) return
        if (instant) glideProgress.value = 1f else glideProgress.step(1f, dt, GLIDE_STIFFNESS)
        val p = glideProgress.value.toDouble()
        val done = instant || glideProgress.settled(1f)
        cx = glideFrom[X] + (glideTo[X] - glideFrom[X]) * p
        cz = glideFrom[Z] + (glideTo[Z] - glideFrom[Z]) * p
        unitsPerPx = glideFrom[SCALE] * (glideTo[SCALE] / glideFrom[SCALE]).pow(p)
        if (done) { cx = glideTo[X]; cz = glideTo[Z]; unitsPerPx = glideTo[SCALE]; gliding = false }
        clamp()
    }

    fun interact(ui: Ui, r: Rect, zoomStep: Double = 1.25, key: Any = "viewport", arrows: Boolean = true): Boolean {
        view = r
        tapped = false
        var changed = false
        val wheel = ui.wheel(r)
        if (wheel != 0.0) changed = zoomAt(zoomStep.pow(-wheel.coerceIn(-MAX_WHEEL, MAX_WHEEL)), ui.mouseX.toDouble(), ui.mouseY.toDouble())
        ui.pressed(r)?.let {
            armed = !locked
            pressX = it.x; pressY = it.y
            lastX = it.x; lastY = it.y
        }
        if (armed && !dragging && Slop.exceeded(ui.mouseX - pressX, ui.mouseY - pressY) && ui.isDown()) {
            dragging = true
            ui.active = ui.id("$key:drag")
        }
        if (armed) ui.released()?.let {
            if (!dragging && !Slop.exceeded(it.x - pressX, it.y - pressY)) { tapped = true; tapX = it.x; tapY = it.y }
            armed = false
        }
        if (dragging && !ui.isDown()) dragging = false
        if (!ui.isDown()) armed = false
        if (dragging) {
            changed = pan((ui.mouseX - lastX).toDouble(), (ui.mouseY - lastY).toDouble()) || changed
            lastX = ui.mouseX; lastY = ui.mouseY
            ui.cursor = Cursor.MOVE
        }
        if (ui.hovering(r) && !ui.typing) changed = keyboard(ui, zoomStep, arrows) || changed
        if (changed || dragging) cancelGlide()
        return changed
    }

    private fun keyboard(ui: Ui, zoomStep: Double, arrows: Boolean): Boolean {
        val step = if (ui.input.shift) 240.0 else 60.0
        var changed = false
        CameraInput.panKeys(ui, step, arrows) { dx, dz -> changed = pan(-dx, -dz) || changed }
        if (ui.input.takeKey(GLFW.GLFW_KEY_EQUAL) != null || ui.input.takeKey(GLFW.GLFW_KEY_KP_ADD) != null) changed = zoomAt(1 / zoomStep) || changed
        if (ui.input.takeKey(GLFW.GLFW_KEY_MINUS) != null || ui.input.takeKey(GLFW.GLFW_KEY_KP_SUBTRACT) != null) changed = zoomAt(zoomStep) || changed
        return changed
    }
}
