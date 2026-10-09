package kami.libs.ui.widget

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.style.TextStyle

fun Ui.cellText(r: Rect, text: String, color: Int = Palette.text, align: Align = Align.LEFT, key: Any = "cell:${r.x},${r.y}"): Int =
    fitText(r.x, r.y + (r.h - 8) / 2, r.w, text, color, align = align, key = key)

fun Ui.fitText(x: Int, y: Int, w: Int, text: String, color: Int = Palette.text, style: TextStyle = TextStyle.BODY, align: Align = Align.LEFT, key: Any = "$x,$y"): Int {
    val shown = Draw.fit(text, w, style)
    val tw = Draw.width(shown, style)
    val dx = when (align) {
        Align.LEFT -> x
        Align.RIGHT -> x + w - tw
        Align.CENTER -> x + (w - tw) / 2
    }
    if (shown != text) tooltip("fit:$key", Rect(x, y - 1, w, 8 * style.scale + 2), text)
    return Draw.text(g, shown, dx, y, style, color)
}
