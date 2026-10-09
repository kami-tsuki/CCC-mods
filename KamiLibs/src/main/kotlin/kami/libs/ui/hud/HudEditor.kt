package kami.libs.ui.hud

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.core.UiScale
import kami.libs.ui.pin.PinWindow
import kami.libs.ui.pin.Pins
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Sprites
import kami.libs.ui.text.tr
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class HudEditor(private val parent: Screen?) : Screen(Component.literal(tr("kami_libs.hud.edit"))) {
    private class Item(val id: String, val pin: PinWindow?, val rect: Rect)

    private class Drag(val id: String, val dx: Int, val dy: Int)

    private val ui = Ui()
    private var drag: Drag? = null
    private var lineX: Int? = null
    private var lineY: Int? = null

    private val scale get() = UiScale.factor
    private val uiScreen get() = Rect(0, 0, UiScale.layout(width), UiScale.layout(height))

    private fun items(): List<Item> = HudLayout.elements.values.map { Item(it.id, null, HudLayout.sampleSize(it).let { (w, h) -> HudLayout.place(it.id, width, height, w, h) }) } +
        Pins.all().filter { Pins.visible(it) }.map { w ->
            val r = Pins.rect(w, uiScreen)
            Item(w.id, w, Rect((r.x * scale).toInt(), (r.y * scale).toInt(), (r.w * scale).toInt(), (r.h * scale).toInt()))
        }

    override fun init() {
        val w = 90
        addRenderableWidget(Button.builder(Component.literal(tr("kami_libs.hud.reset"))) { HudLayout.reset(); Pins.resetGeo() }.bounds(width / 2 - w - 2, height - 28, w, 20).build())
        addRenderableWidget(Button.builder(Component.literal(tr("kami_libs.common.done"))) { onClose() }.bounds(width / 2 + 2, height - 28, w, 20).build())
    }

    override fun renderBackground(g: GuiGraphics, mx: Int, my: Int, delta: Float) = Draw.fill(g, Rect(0, 0, width, height), 0x30000000)

    override fun render(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
        super.render(g, mx, my, delta)
        val faint = Palette.alpha(Palette.border, 0x50)
        HudLayout.guides(width).forEach { Draw.vline(g, it, 0, height, if (it == lineX) Palette.brass else faint) }
        HudLayout.guides(height).forEach { Draw.hline(g, 0, it, width, if (it == lineY) Palette.brass else faint) }
        Draw.textCentered(g, tr("kami_libs.hud.hint"), Rect(0, height - 44, width, 12), Palette.textMuted)
        ui.frame(g, -1, -1, width, height) { Pins.preview(ui) }
        val all = items()
        HudLayout.elements.values.forEach { e ->
            val b = all.first { it.id == e.id }.rect
            Draw.sprite(g, Sprites.TOOLTIP, b)
            val rows = e.lines?.invoke()
            if (rows == null) Draw.text(g, e.sample(), b.x + 5, b.y + 4, e.color())
            else rows.forEachIndexed { i, (text, color) -> Draw.text(g, text, b.x + 5, b.y + 4 + i * HudLayout.LINE_H, color) }
        }
        val focus = drag?.id ?: all.lastOrNull { it.rect.contains(mx, my) }?.id
        all.forEach { Draw.outline(g, it.rect, if (it.id == focus) Palette.brass else Palette.borderSubtle) }
        all.firstOrNull { it.id == focus }?.let { anchor(g, it) }
    }

    private fun anchor(g: GuiGraphics, item: Item) {
        val a = HudLayout.anchored(item.rect, width, height).anchor
        val (px, py) = HudLayout.point(a, width, height)
        val tx = item.rect.x + (a.ax * item.rect.w).toInt()
        val ty = item.rect.y + (a.ay * item.rect.h).toInt()
        Draw.line(g, px, py, tx, ty, Palette.alpha(Palette.brass, 0xA0))
        Draw.fill(g, Rect(px - 2, py - 2, 5, 5), Palette.brass)
    }

    override fun mouseClicked(mx: Double, my: Double, button: Int): Boolean {
        if (super.mouseClicked(mx, my, button)) return true
        val hit = items().lastOrNull { it.rect.contains(mx.toInt(), my.toInt()) } ?: return false
        drag = Drag(hit.id, mx.toInt() - hit.rect.x, my.toInt() - hit.rect.y)
        return true
    }

    override fun mouseDragged(mx: Double, my: Double, button: Int, dx: Double, dy: Double): Boolean {
        val d = drag ?: return super.mouseDragged(mx, my, button, dx, dy)
        val all = items()
        val item = all.firstOrNull { it.id == d.id } ?: return true
        var x = mx.toInt() - d.dx
        var y = my.toInt() - d.dy
        lineX = null
        lineY = null
        if (!hasAltDown()) {
            val others = all.filter { it.id != d.id }.map { it.rect }
            HudLayout.snap(x, item.rect.w, HudLayout.guides(width) + others.flatMap { listOf(it.x, it.centerX, it.right) }).let { x = it.first; lineX = it.second }
            HudLayout.snap(y, item.rect.h, HudLayout.guides(height) + others.flatMap { listOf(it.y, it.y + it.h / 2, it.bottom) }).let { y = it.first; lineY = it.second }
        }
        val pin = item.pin
        if (pin == null) HudLayout.set(d.id, HudLayout.anchored(Rect(x, y, item.rect.w, item.rect.h), width, height))
        else Pins.rect(pin, uiScreen).let { Pins.place(pin.id, Rect((x / scale).toInt(), (y / scale).toInt(), it.w, it.h), uiScreen) }
        return true
    }

    override fun mouseReleased(mx: Double, my: Double, button: Int): Boolean {
        drag = null
        lineX = null
        lineY = null
        return super.mouseReleased(mx, my, button)
    }

    override fun removed() {
        HudLayout.save()
        Pins.commit()
    }

    override fun onClose() = Minecraft.getInstance().setScreen(parent)

    override fun isPauseScreen() = false
}
