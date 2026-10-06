package kami.libs.ui.app

import kami.libs.ui.widget.spinner
import kami.libs.ui.core.Rect
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Sprites
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component

class OpenWait(private val label: () -> String, private val failTitle: () -> String, private val failBody: () -> String, private val timeoutMs: Long = 10_000) {
    private var since = 0L

    val active get() = since > 0

    fun start() { if (since == 0L) since = System.currentTimeMillis() }

    fun done() { since = 0 }

    fun tick() {
        if (since == 0L || System.currentTimeMillis() - since < timeoutMs) return
        since = 0
        val mc = Minecraft.getInstance()
        SystemToast.addOrUpdate(mc.toasts, SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal(failTitle()), Component.literal(failBody()))
    }

    fun render(g: GuiGraphics) {
        val mc = Minecraft.getInstance()
        if (since == 0L || mc.screen != null) return
        val text = label()
        val w = Draw.width(text) + 26
        val box = Rect((g.guiWidth() - w) / 2, g.guiHeight() / 2 + 14, w, 16)
        Draw.sprite(g, Sprites.TOOLTIP, box)
        spinner(g, box.x + 6, box.centerY - 4, System.currentTimeMillis())
        Draw.text(g, text, box.x + 18, box.y + 4, Palette.textSecondary)
    }
}
