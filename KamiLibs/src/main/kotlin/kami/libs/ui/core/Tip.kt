package kami.libs.ui.core

import kami.libs.ui.style.Icon
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import net.minecraft.client.gui.GuiGraphics
import org.lwjgl.glfw.GLFW

enum class Cursor(val shape: Int) { ARROW(GLFW.GLFW_ARROW_CURSOR), HAND(GLFW.GLFW_HAND_CURSOR), TEXT(GLFW.GLFW_IBEAM_CURSOR), MOVE(GLFW.GLFW_CROSSHAIR_CURSOR) }

class Tip(
    val title: String? = null,
    val lines: List<Pair<String, Int>> = emptyList(),
    val severity: Severity? = null,
    val icon: Icon? = null,
    val keys: String? = null,
    val extraHeight: Int = 0,
    val extra: ((GuiGraphics, Rect) -> Unit)? = null,
    val more: List<Pair<String, Int>> = emptyList()
) {
    fun shown(detailed: Boolean) = if (detailed) Tip(title, lines + more, severity, icon, keys, extraHeight, extra) else Tip(title, lines, severity, icon, extraHeight = extraHeight, extra = extra)

    companion object {
        fun text(text: String, title: String? = null) = Tip(title, listOf(text to Palette.textSecondary))
        fun disabled(reason: String) = Tip(null, listOf(reason to Palette.warning), Severity.WARNING)
    }
}
