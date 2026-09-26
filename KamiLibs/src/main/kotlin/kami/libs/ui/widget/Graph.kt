package kami.libs.ui.widget

import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.max
import kotlin.math.min

class GraphNode(val key: String, val title: String, val subtitle: String, val link: String = "", val linkColor: Int = Palette.border, val severity: Severity? = null, val badge: Ui.(GuiGraphics, Rect) -> Unit = { _, _ -> })

fun Ui.hierarchy(r: Rect, root: GraphNode, children: List<GraphNode>, selected: String?, key: Any = "hierarchy"): String? {
    var clicked: String? = null
    val nodeW = min(150, max(90, (r.w - 8) / max(1, min(children.size, 4)) - 8))
    val nodeH = 34
    fun node(n: GraphNode, box: Rect) {
        val hover = hovering(box)
        if (hover) cursor = Cursor.HAND
        Draw.sprite(g, if (hover) Sprites.CARD_HOVER else Sprites.CARD, box)
        if (n.key == selected) Draw.outline(g, box, Palette.brass)
        n.severity?.let { Draw.fill(g, Rect(box.x + 1, box.y + 1, 2, box.h - 2), it.color) }
        n.badge(this, g, Rect(box.x + 5, box.y + 5, 12, 9))
        Draw.text(g, Draw.fit(n.title, box.w - 26), box.x + 20, box.y + 6, TextStyle.HEADING)
        Draw.text(g, Draw.fit(n.subtitle, box.w - 10), box.x + 5, box.y + 20, n.severity?.color ?: Palette.textMuted)
        if (pressed(box) != null) clicked = n.key
        attention(box, n.severity == Severity.WARNING)
    }
    val rootBox = Rect(r.centerX - nodeW / 2, r.y + 4, nodeW, nodeH)
    val perRow = max(1, (r.w + 8) / (nodeW + 8))
    val rows = children.chunked(perRow)
    rows.forEachIndexed { ri, row ->
        val y = rootBox.bottom + 22 + ri * (nodeH + 22)
        val totalW = row.size * nodeW + (row.size - 1) * 8
        var x = r.centerX - totalW / 2
        row.forEach { n ->
            val box = Rect(x, y, nodeW, nodeH)
            val busX = box.centerX
            val busY = rootBox.bottom + 10
            Draw.vline(g, rootBox.centerX, rootBox.bottom, 10, Palette.borderStrong)
            Draw.hline(g, min(busX, rootBox.centerX), busY, kotlin.math.abs(busX - rootBox.centerX) + 1, Palette.borderStrong)
            Draw.fill(g, Rect(busX - 1, busY, 2, y - busY), n.linkColor)
            if (n.link.isNotEmpty()) {
                val lw = Draw.width(n.link) + 6
                Draw.fill(g, Rect(busX + 3, y - 12, lw, 10), Palette.canvas)
                Draw.text(g, n.link, busX + 6, y - 11, n.linkColor)
            }
            node(n, box)
            x += nodeW + 8
        }
    }
    node(root, rootBox)
    return clicked
}

fun hierarchyHeight(width: Int, children: Int): Int {
    val nodeW = min(150, max(90, (width - 8) / max(1, min(children, 4)) - 8))
    val perRow = max(1, (width + 8) / (nodeW + 8))
    val rows = (children + perRow - 1) / perRow
    return 4 + 34 + rows * (34 + 22) + 4
}
