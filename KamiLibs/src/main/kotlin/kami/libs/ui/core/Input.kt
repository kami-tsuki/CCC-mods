package kami.libs.ui.core

import org.lwjgl.glfw.GLFW

class Click(val x: Int, val y: Int, val button: Int, var consumed: Boolean = false)
class Key(val code: Int, val mods: Int, var consumed: Boolean = false) {
    val ctrl get() = mods and GLFW.GLFW_MOD_CONTROL != 0
    val shift get() = mods and GLFW.GLFW_MOD_SHIFT != 0
    val alt get() = mods and GLFW.GLFW_MOD_ALT != 0
}

class Input {
    val presses = ArrayList<Click>()
    val releases = ArrayList<Click>()
    val keys = ArrayList<Key>()
    val chars = StringBuilder()
    private val down = BooleanArray(8)
    var wheel = 0.0
    var wheelConsumed = false
    var shift = false
    var ctrl = false

    fun isDown(button: Int) = button in down.indices && down[button]

    fun press(x: Int, y: Int, button: Int) {
        presses += Click(UiScale.unscale(x), UiScale.unscale(y), button)
        if (button in down.indices) down[button] = true
    }

    fun release(x: Int, y: Int, button: Int) {
        releases += Click(UiScale.unscale(x), UiScale.unscale(y), button)
        if (button in down.indices) down[button] = false
    }

    fun key(code: Int, mods: Int) { keys += Key(code, mods) }
    fun char(c: Char) { chars.append(c) }
    fun scroll(amount: Double) { wheel += amount }

    fun takeKey(code: Int, filter: (Key) -> Boolean = { true }): Key? =
        keys.firstOrNull { !it.consumed && it.code == code && filter(it) }?.also { it.consumed = true }

    fun takeWheel(): Double {
        if (wheelConsumed) return 0.0
        wheelConsumed = true
        return wheel
    }

    fun endFrame(shiftDown: Boolean, ctrlDown: Boolean) {
        presses.clear()
        releases.clear()
        keys.clear()
        chars.setLength(0)
        wheel = 0.0
        wheelConsumed = false
        shift = shiftDown
        ctrl = ctrlDown
    }

    fun reset() {
        endFrame(false, false)
        down.fill(false)
    }
}
