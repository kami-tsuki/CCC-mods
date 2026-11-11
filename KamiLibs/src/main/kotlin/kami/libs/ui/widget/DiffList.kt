package kami.libs.ui.widget

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr

class DiffLine(val text: String, val added: Boolean, val tip: String? = null) {
    companion object {
        fun change(title: String, old: String, new: String) = listOf(DiffLine("$title: $old", false), DiffLine("$title: $new", true))
    }
}

fun <T> diffOf(before: Set<T>, after: Set<T>, label: (T) -> String, tip: (T) -> String? = { null }) =
    (after - before).map { DiffLine(label(it), true, tip(it)) } + (before - after).map { DiffLine(label(it), false, tip(it)) }

const val DIFF_ROW_H = 14

fun diffListHeight(count: Int) = count * DIFF_ROW_H

fun Ui.diffList(r: Rect, lines: List<DiffLine>, key: Any = "diff") {
    if (lines.isEmpty()) {
        Draw.textCentered(g, tr("kami_libs.diff.empty"), r.top(DIFF_ROW_H * 2), Palette.textMuted)
        return
    }
    scroll(key, r, diffListHeight(lines.size)) { content ->
        lines.forEachIndexed { i, line ->
            val row = Rect(content.x, content.y + i * DIFF_ROW_H, content.w, DIFF_ROW_H)
            val color = if (line.added) Palette.success else Palette.danger
            Draw.fill(g, row, Palette.alpha(color, 0x1A))
            Draw.fill(g, row.left(2), color)
            Draw.tintedIcon(g, if (line.added) Icons.ADD else Icons.REMOVE, row.x + 4, row.y + (row.h - Draw.ICON) / 2, Draw.ICON, color)
            val x = row.x + 6 + Draw.ICON
            val shown = Draw.fit(line.text, row.right - x - 4)
            Draw.text(g, shown, x, Draw.textY(row), Palette.text)
            tooltip("$key:$i", row, line.tip ?: line.text.takeIf { it != shown })
        }
    }
}
