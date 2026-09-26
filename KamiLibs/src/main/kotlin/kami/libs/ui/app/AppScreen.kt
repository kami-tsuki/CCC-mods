package kami.libs.ui.app

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

open class AppScreen(val app: KamiApp, title: Component) : Screen(title) {
    override fun isPauseScreen() = false

    override fun render(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
        renderBackground(g, mx, my, delta)
        app.render(g, mx, my, width, height)
    }

    override fun mouseClicked(mx: Double, my: Double, button: Int): Boolean {
        when (button) {
            GLFW.GLFW_MOUSE_BUTTON_4 -> app.back()
            GLFW.GLFW_MOUSE_BUTTON_5 -> app.forward()
            else -> app.ui.input.press(mx.toInt(), my.toInt(), button)
        }
        return true
    }

    override fun mouseReleased(mx: Double, my: Double, button: Int): Boolean {
        app.ui.input.release(mx.toInt(), my.toInt(), button)
        return true
    }

    override fun mouseDragged(mx: Double, my: Double, button: Int, dx: Double, dy: Double) = true

    override fun mouseScrolled(mx: Double, my: Double, sx: Double, sy: Double): Boolean {
        app.ui.input.scroll(sy)
        return true
    }

    override fun keyPressed(key: Int, scan: Int, mods: Int): Boolean {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (!app.escape()) onClose()
            return true
        }
        app.ui.input.key(key, mods)
        return true
    }

    override fun charTyped(c: Char, mods: Int): Boolean {
        app.ui.input.char(c)
        return true
    }

    override fun removed() {
        app.closed()
        super.removed()
    }
}
