package kami.libs.ui

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

abstract class KamiScreen(title: Component) : Screen(title) {
    protected open val dimColor: Int = 0xB0000000.toInt()
    val ui = Ui()

    protected abstract fun draw(ui: Ui, r: Rect)

    override fun renderBackground(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
        if (dimColor != 0) g.fill(0, 0, width, height, dimColor)
    }

    override fun render(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
        renderBackground(g, mx, my, delta)
        ui.frame(g, mx, my, width, height) { draw(ui, ui.screen) }
    }

    override fun isPauseScreen() = false

    override fun mouseClicked(mx: Double, my: Double, button: Int): Boolean {
        ui.input.press(mx.toInt(), my.toInt(), button)
        return true
    }

    override fun mouseReleased(mx: Double, my: Double, button: Int): Boolean {
        ui.input.release(mx.toInt(), my.toInt(), button)
        return true
    }

    override fun mouseDragged(mx: Double, my: Double, button: Int, dx: Double, dy: Double) = true

    override fun mouseScrolled(mx: Double, my: Double, sx: Double, sy: Double): Boolean {
        ui.input.scroll(sy)
        return true
    }

    override fun keyPressed(key: Int, scan: Int, mods: Int): Boolean {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (!ui.escape()) onClose()
            return true
        }
        ui.input.key(key, mods)
        return true
    }

    override fun charTyped(c: Char, mods: Int): Boolean {
        ui.input.char(c)
        return true
    }

    override fun removed() {
        ui.close()
        super.removed()
    }
}
