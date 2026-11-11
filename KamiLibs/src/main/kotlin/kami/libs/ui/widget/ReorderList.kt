package kami.libs.ui.widget

import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Slop
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import org.lwjgl.glfw.GLFW

private const val HANDLE_W = 10
private const val NONE = -1
private const val BUTTON_W = 14
private class Drag { var key: Any? = null; var startY = 0; var count = 0; var active = false }

internal fun reorderTarget(from: Int, to: Int, count: Int, locked: (Int) -> Boolean, canMove: (Int, Int) -> Boolean): Int? =
    to.takeIf { it in 0 until count && it != from && !locked(from) && !locked(it) && canMove(from, it) }

internal fun dropIndex(y: Int, top: Int, rowHeight: Int, count: Int) = ((y - top) / rowHeight).coerceIn(0, maxOf(0, count - 1))

fun Ui.reorderList(
    r: Rect, count: Int, rowHeight: Int = 20, locked: (Int) -> Boolean = { false }, canMove: (Int, Int) -> Boolean = { _, _ -> true },
    draggable: Boolean = true, key: Any = "reorder", lockedReason: String? = null, rowId: (Int) -> Any = { it }, onSelect: ((Int) -> Unit)? = null, row: Ui.(Int, Rect) -> Unit
): Pair<Int, Int>? {
    val drag = remember("drag:$key") { Drag() }
    var result: Pair<Int, Int>? = null
    fun rowRect(i: Int) = Rect(r.x, r.y + i * rowHeight, r.w, rowHeight)
    fun move(from: Int, to: Int) {
        if (result == null) reorderTarget(from, to, count, locked, canMove)?.let { result = from to it; focus = id("$key:row:$it") }
    }
    val from = drag.key?.takeIf { drag.count == count }?.let { k -> (0 until count).firstOrNull { rowId(it) == k } } ?: NONE
    if (from == NONE) { drag.key = null; drag.active = false }
    val target = if (from != NONE && drag.active) reorderTarget(from, dropIndex(mouseY, r.y, rowHeight, count), count, locked, canMove) else null
    for (i in 0 until count) {
        val rr = rowRect(i)
        val rowKey = "$key:row:$i"
        val fixed = locked(i)
        focusable(rowKey)
        if (from == i && drag.active) Draw.fill(g, rr, Palette.selected)
        else if (hovering(rr) && from == NONE) Draw.fill(g, rr, Palette.hover)
        if (i > 0) Draw.hline(g, r.x, rr.y, r.w, Palette.borderSubtle)
        val handle = Rect(rr.x + 1, rr.y, HANDLE_W, rr.h)
        if (fixed) {
            Draw.tintedIcon(g, Icons.LOCK, handle.x, rr.y + (rr.h - Draw.ICON) / 2, Draw.ICON, Palette.iconOff)
            tooltip("$key:fixed:$i", handle, tr("kami_libs.reorder.fixed"))
        } else if (draggable) {
            val over = hovering(handle)
            for (dy in -3..3 step 3) for (dx in 0..1) Draw.fill(g, Rect(handle.x + 2 + dx * 4, handle.centerY + dy - 1, 2, 2), if (over) Palette.textSecondary else Palette.textMuted)
            if (over) cursor = Cursor.MOVE
            tooltip("$key:grip:$i", handle, tr("kami_libs.reorder.drag"))
            if (pressed(handle) != null) { drag.key = rowId(i); drag.count = count; drag.startY = mouseY; drag.active = false; focus = id(rowKey) }
        } else if (lockedReason != null) {
            for (dy in -3..3 step 3) for (dx in 0..1) Draw.fill(g, Rect(handle.x + 2 + dx * 4, handle.centerY + dy - 1, 2, 2), Palette.textDisabled)
            tooltip("$key:grip:$i", handle, lockedReason)
        }
        val buttons = if (fixed) 0 else BUTTON_W * 2
        val content = Rect(rr.x + HANDLE_W + 3, rr.y, r.w - HANDLE_W - 3 - buttons, rr.h)
        if (input.presses.any { it.button == 0 && !it.consumed && content.contains(it.x, it.y) && canHit(it.x, it.y) }) { focus = id(rowKey); onSelect?.invoke(i) }
        if (onSelect != null && activatedByKey(rowKey, false)) onSelect(i)
        row(i, content)
        if (!fixed) {
            val up = Rect(rr.right - BUTTON_W * 2, rr.y + (rr.h - 12) / 2, 12, 12)
            val down = Rect(rr.right - BUTTON_W, up.y, 12, 12)
            if (iconButton(up, Icons.SORT_UP, tr("kami_libs.reorder.up"), enabled = lockedReason == null && reorderTarget(i, i - 1, count, locked, canMove) != null, key = "$key:up:$i", disabledReason = lockedReason)) move(i, i - 1)
            if (iconButton(down, Icons.SORT_DOWN, tr("kami_libs.reorder.down"), enabled = lockedReason == null && reorderTarget(i, i + 1, count, locked, canMove) != null, key = "$key:down:$i", disabledReason = lockedReason)) move(i, i + 1)
            if (focused(rowKey)) {
                if (input.takeKey(GLFW.GLFW_KEY_UP) { it.alt } != null) move(i, i - 1)
                else if (input.takeKey(GLFW.GLFW_KEY_DOWN) { it.alt } != null) move(i, i + 1)
            }
        }
        focusRing(rowKey, rr)
    }
    if (from != NONE) {
        if (!drag.active && Slop.exceeded(0, mouseY - drag.startY)) drag.active = true
        if (drag.active) {
            cursor = Cursor.MOVE
            if (target != null) Draw.fill(g, Rect(r.x, (if (target > from) rowRect(target).bottom else rowRect(target).y) - 1, r.w, 2), Palette.brass)
        }
        if (!isDown()) {
            if (drag.active && target != null) { result = from to target; focus = id("$key:row:$target") }
            drag.key = null
            drag.active = false
        }
    }
    return result
}
