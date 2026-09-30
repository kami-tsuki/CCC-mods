package kami.libs.ui.map

import kami.libs.ui.core.Ui
import org.lwjgl.glfw.GLFW

object CameraInput {
    private val PAN_KEYS = listOf(
        GLFW.GLFW_KEY_LEFT to (-1.0 to 0.0), GLFW.GLFW_KEY_RIGHT to (1.0 to 0.0),
        GLFW.GLFW_KEY_UP to (0.0 to -1.0), GLFW.GLFW_KEY_DOWN to (0.0 to 1.0),
        GLFW.GLFW_KEY_A to (-1.0 to 0.0), GLFW.GLFW_KEY_D to (1.0 to 0.0),
        GLFW.GLFW_KEY_W to (0.0 to -1.0), GLFW.GLFW_KEY_S to (0.0 to 1.0)
    )

    fun panKeys(ui: Ui, step: Double, apply: (dx: Double, dz: Double) -> Unit) {
        PAN_KEYS.forEach { (k, d) -> if (ui.input.takeKey(k) { !it.ctrl } != null) apply(d.first * step, d.second * step) }
    }
}
