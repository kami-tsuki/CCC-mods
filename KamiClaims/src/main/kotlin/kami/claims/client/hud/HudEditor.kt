package kami.claims.client.hud

import kami.claims.client.ClientClaims
import kami.libs.ui.core.Rect
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Sprites
import kami.libs.ui.text.tr
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class HudEditor : Screen(Component.literal(tr("kami_claims.hud.edit.title"))) {
    private enum class Slot { WAR, TERRITORY }

    private var drag: Slot? = null
    private var offX = 0
    private var offY = 0

    private fun text(s: Slot) = if (s == Slot.WAR) tr("kami_claims.hud.edit.sample_war") else tr("kami_claims.hud.edit.sample_territory")

    private fun color(s: Slot) = if (s == Slot.WAR) Palette.danger else Palette.text

    private fun width(s: Slot) = Draw.width(text(s)) + 10

    private fun box(s: Slot): Rect {
        val p = ClientClaims.prefs
        return if (s == Slot.WAR) HudLayout.place(this.width, height, width(s), p.warX, p.warY) else HudLayout.place(this.width, height, width(s), p.territoryX, p.territoryY)
    }

    private fun move(s: Slot, x: Int, y: Int) {
        val fx = HudLayout.fraction(this.width, width(s), x)
        val fy = HudLayout.fraction(height, HudLayout.BOX_H, y)
        val p = ClientClaims.prefs
        if (s == Slot.WAR) { p.warX = fx; p.warY = fy } else { p.territoryX = fx; p.territoryY = fy }
    }

    private fun reset() {
        val p = ClientClaims.prefs
        p.warX = 0f
        p.warY = 0f
        p.territoryX = 0.5f
        p.territoryY = 0f
    }

    override fun init() {
        val w = 80
        addRenderableWidget(Button.builder(Component.literal(tr("kami_claims.hud.edit.reset"))) { reset() }.bounds(width / 2 - w - 2, height - 28, w, 20).build())
        addRenderableWidget(Button.builder(Component.literal(tr("kami_claims.hud.edit.done"))) { onClose() }.bounds(width / 2 + 2, height - 28, w, 20).build())
    }

    override fun render(g: GuiGraphics, mx: Int, my: Int, delta: Float) {
        super.render(g, mx, my, delta)
        Draw.textCentered(g, tr("kami_claims.hud.edit.hint"), Rect(0, height / 2 - 6, width, 12), Palette.textMuted)
        Slot.entries.forEach { s ->
            val b = box(s)
            Draw.sprite(g, Sprites.TOOLTIP, b)
            Draw.text(g, text(s), b.x + 5, b.y + 4, color(s))
            Draw.outline(g, b, if (drag == s || (drag == null && b.contains(mx, my))) Palette.brass else Palette.borderSubtle)
        }
    }

    override fun mouseClicked(mx: Double, my: Double, button: Int): Boolean {
        if (super.mouseClicked(mx, my, button)) return true
        val hit = Slot.entries.reversed().firstOrNull { box(it).contains(mx.toInt(), my.toInt()) } ?: return false
        drag = hit
        offX = mx.toInt() - box(hit).x
        offY = my.toInt() - box(hit).y
        return true
    }

    override fun mouseDragged(mx: Double, my: Double, button: Int, dx: Double, dy: Double): Boolean {
        val s = drag ?: return super.mouseDragged(mx, my, button, dx, dy)
        move(s, mx.toInt() - offX, my.toInt() - offY)
        return true
    }

    override fun mouseReleased(mx: Double, my: Double, button: Int): Boolean {
        drag = null
        return super.mouseReleased(mx, my, button)
    }

    override fun removed() {
        ClientClaims.savePrefs()
    }

    override fun isPauseScreen() = false
}
