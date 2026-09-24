package kami.libs.ui

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

abstract class KamiScreen(title: Component) : Screen(title) {
    protected open val dimColor: Int = 0xB0000000.toInt()

    override fun renderBackground(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
        if (dimColor != 0) g.fill(0, 0, width, height, dimColor)
    }

    override fun isPauseScreen() = false

    private fun stepAmount(): Int = when {
        hasShiftDown() && hasControlDown() -> 1000
        hasControlDown() -> 100
        hasShiftDown() -> 10
        else -> 1
    }

    protected fun numberField(x: Int, y: Int, w: Int, h: Int, value: Int, min: Int, max: () -> Int, onChange: (Int) -> Unit) {
        val btn = 16
        val wide = 26
        val gap = 2
        val fieldW = (w - btn * 2 - wide * 2 - gap * 4).coerceAtLeast(30)

        fun clamp(v: Int) = v.coerceIn(min, max().coerceAtLeast(min))

        val field = EditBox(font, x, y, fieldW, h, Component.literal("value"))
        field.value = clamp(value).toString()
        field.setFilter { it.isEmpty() || it.all(Char::isDigit) }
        field.setResponder { s -> s.toIntOrNull()?.let { onChange(clamp(it)) } }
        addRenderableWidget(field)

        fun set(v: Int) {
            val clamped = clamp(v)
            field.value = clamped.toString()
            onChange(clamped)
        }

        var bx = x + fieldW + gap
        addRenderableWidget(Button.builder(Component.literal("-")) { set((field.value.toIntOrNull() ?: value) - stepAmount()) }.bounds(bx, y, btn, h).build())
        bx += btn + gap
        addRenderableWidget(Button.builder(Component.literal("+")) { set((field.value.toIntOrNull() ?: value) + stepAmount()) }.bounds(bx, y, btn, h).build())
        bx += btn + gap
        addRenderableWidget(Button.builder(Component.literal("Min")) { set(min) }.bounds(bx, y, wide, h).build())
        bx += wide + gap
        addRenderableWidget(Button.builder(Component.literal("Max")) { set(max()) }.bounds(bx, y, wide, h).build())
    }
}
