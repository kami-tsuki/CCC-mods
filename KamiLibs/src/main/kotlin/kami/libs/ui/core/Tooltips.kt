package kami.libs.ui.core

import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.util.FormattedCharSequence
import kotlin.math.max
import kotlin.math.min

object Tooltips {
    private const val MAX_WIDTH = 180
    private const val PAD = 4
    private const val RISE = 2

    private fun layout(t: Tip): Pair<Int, List<Pair<List<FormattedCharSequence>, Int>>> {
        val titleW = t.title?.let { Draw.width(it, TextStyle.HEADING) + if (t.icon != null) Draw.ICON_SLOT else 0 } ?: 0
        val natural = t.lines.maxOfOrNull { Draw.font.width(it.first) } ?: 0
        val keysW = t.keys?.let { Draw.font.width(it) } ?: 0
        val width = min(MAX_WIDTH, max(max(titleW, natural), max(keysW, if (t.extra != null) 120 else 0)))
        return width to t.lines.map { Draw.wrap(it.first, width) to it.second }
    }

    fun draw(g: GuiGraphics, t: Tip, mx: Int, my: Int, screen: Rect, appear: Float = 1f) {
        val (width, wrapped) = layout(t)
        var height = PAD * 2
        if (t.title != null) height += Draw.LINE + 1
        height += wrapped.sumOf { it.first.size } * Draw.LINE
        if (t.extra != null) height += t.extraHeight + 4
        if (t.keys != null) height += Draw.LINE + 2
        height -= 2
        val w = width + PAD * 2
        var x = mx + 10
        var y = my + 10
        if (x + w > screen.right - 4) x = mx - w - 8
        if (y + height > screen.bottom - 4) y = max(4, screen.bottom - height - 4)
        val box = Rect(max(4, x), y + ((1f - appear) * RISE).toInt(), w, height)
        Draw.shadow(g, box, 1)
        Draw.sprite(g, Sprites.TOOLTIP, box)
        t.severity?.let { Draw.fill(g, box.left(1).inset(0, 1), it.color) }
        var cy = box.y + PAD
        val cx = box.x + PAD
        t.title?.let { title ->
            var tx = cx
            t.icon?.let { tx += Draw.leadIcon(g, it, cx, cy + 4) }
            Draw.text(g, title, tx, cy, TextStyle.HEADING, t.severity?.color ?: Palette.text)
            cy += Draw.LINE + 1
        }
        wrapped.forEach { (lines, color) ->
            lines.forEach { line -> g.drawString(Draw.font, line, cx, cy, color, false); cy += Draw.LINE }
        }
        t.extra?.let { draw ->
            cy += 2
            draw(g, Rect(cx, cy, width, t.extraHeight))
            cy += t.extraHeight + 2
        }
        t.keys?.let {
            Draw.hline(g, cx, cy, width, Palette.borderSubtle)
            Draw.text(g, it, cx, cy + 2, Palette.textMuted)
        }
        Draw.veilBox(g, box.inset(1), appear)
    }
}
