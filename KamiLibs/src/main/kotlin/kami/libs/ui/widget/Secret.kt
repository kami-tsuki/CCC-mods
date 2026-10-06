package kami.libs.ui.widget

import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import net.minecraft.client.Minecraft

private const val MASK = "••••••"
private const val COPY_W = 12

private class Reveals(var frame: Long = -1, val ids: HashSet<String> = HashSet())

private val reveals = HashMap<String, Reveals>()

private fun Ui.revealed(): HashSet<String> {
    val s = reveals.getOrPut(id("").substringBefore('/')) { Reveals() }
    if (frame > s.frame + 1) s.ids.clear()
    s.frame = frame
    return s.ids
}

fun Ui.secret(r: Rect, value: String, key: Any = "secret:$value", color: Int = Palette.text, align: Align = Align.LEFT) {
    val ids = revealed()
    val id = id(key)
    val open = id in ids
    val c = Rect(r.right - COPY_W, r.y + (r.h - COPY_W) / 2, COPY_W, COPY_W)
    val t = r.dropRight(COPY_W, 2)
    val s = if (open) Draw.fit(value, t.w) else MASK
    val x = if (align == Align.RIGHT) t.right - Draw.width(s) else t.x
    Draw.text(g, s, x, r.y + (r.h - 8) / 2, if (open) color else Palette.textMuted)
    if (hovering(t)) cursor = Cursor.HAND
    tooltip("$key:show", t, if (open) value.takeIf { s != it } else tr("kami_libs.secret.reveal"))
    if (pressed(t) != null && !ids.remove(id)) ids += id
    if (hovering(c)) cursor = Cursor.HAND
    Draw.tintedIcon(g, Icons.COPY, c.x - 2, c.y - 2, 16, if (hovering(c)) Palette.text else Palette.textMuted)
    tooltip("$key:copy", c, tr("kami_libs.common.copy"))
    if (pressed(c) != null) Minecraft.getInstance().keyboardHandler.clipboard = value
}

fun Ui.secretProperty(r: Rect, label: String, value: String, color: Int = Palette.text, key: Any = "secret-prop:$label:$value"): Rect {
    Draw.text(g, label.uppercase(), r.x, r.y + 1, Palette.textMuted)
    val labelW = Draw.width(label.uppercase()) + 4
    secret(Rect(r.x + labelW, r.y, r.w - labelW, 10), value, key, color, Align.RIGHT)
    return r.dropTop(12)
}

fun <T> Column.Companion.secret(title: String, width: Int, value: (T) -> String?) =
    Column<T>(title, width) { _, r, row -> value(row)?.takeIf { it.isNotEmpty() }?.let { secret(r, it, "secret-cell:$it") } }
