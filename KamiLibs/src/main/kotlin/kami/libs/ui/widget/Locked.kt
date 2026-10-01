package kami.libs.ui.widget

import kami.libs.ui.anim.anim
import kami.libs.ui.anim.floatText
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.TextStyle
import kami.libs.ui.style.UiSound
import kami.libs.ui.text.tr

class Lock(val label: String, val how: String? = null) {
    val reason get() = how ?: label

    companion object {
        fun level(level: Int, what: String? = null) = Lock(
            tr("kami_libs.lock.ui.level", level),
            if (what == null) tr("kami_libs.lock.ui.level.how", level) else tr("kami_libs.lock.ui.level.how_for", level, what)
        )

        fun research(name: String) = Lock(tr("kami_libs.lock.ui.research", name), tr("kami_libs.lock.ui.research.how", name))

        fun raise(level: Int, max: Int) = Lock(tr("kami_libs.lock.ui.raise", level, Format.number(max)), tr("kami_libs.lock.ui.raise.how", level, Format.number(max)))
    }
}

const val CAPACITY_BAR_H = PROGRESS_LABELLED_H
const val CAPACITY_HINT_H = 10
private const val VEIL_ALPHA = 0x98
private const val HINT_H = 13
private const val CHIP_H = 13

fun lockChipWidth(lock: Lock) = Draw.width(lock.label) + Draw.ICON + 9

fun Ui.lockChip(x: Int, y: Int, lock: Lock, key: Any = "lock:${lock.label}"): Int =
    chip(x, y, lock.label, Palette.warning, Icons.LOCK, lock.how, key)

internal fun Ui.lockClick(r: Rect, lock: Lock): Boolean {
    if (!hovering(r)) return false
    cursor = Cursor.HAND
    if (pressed(r) == null) return false
    UiSound.warning()
    floatText(mouseX, mouseY - 6, lock.label, Palette.warning)
    return true
}

fun Ui.locked(r: Rect, lock: Lock?, key: Any = "locked", draw: (Rect) -> Unit): Boolean {
    draw(r)
    return lock != null && lockVeil(r, lock, key)
}

fun Ui.lockVeil(r: Rect, lock: Lock, key: Any = "locked"): Boolean {
    Draw.fill(g, r, Palette.alpha(Palette.canvas, VEIL_ALPHA))
    val reveal = anim("$key:reveal", if (hover(key, r)) 1f else 0f, 16f)
    lockChip(r.right - lockChipWidth(lock) - 3, r.y + 3, lock, "$key:chip")
    val how = lock.how
    if (how != null && r.h >= 2 * HINT_H && reveal > 0.02f) {
        val strip = Rect(r.x + 1, r.bottom - 1 - (HINT_H * reveal).toInt(), r.w - 2, HINT_H)
        Draw.fill(g, strip, Palette.alpha(Palette.canvas, 0xE0))
        Draw.text(g, Draw.fit(how, strip.w - 8), strip.x + 4, strip.y + 3, Palette.warning)
    }
    tooltip(key, r, how)
    return lockClick(r, lock)
}

fun Ui.lockedButton(
    r: Rect, label: String, lock: Lock?, icon: Icon? = null, style: ButtonStyle = ButtonStyle.SECONDARY, enabled: Boolean = true,
    disabledReason: String? = null, tip: String? = null, pending: Boolean = false, key: Any = label
): Boolean {
    if (lock == null) return button(r, label, icon, style, enabled, disabledReason, tip, pending, key)
    button(r, label, Icons.LOCK, style, false, lock.reason, key = key)
    lockClick(r, lock)
    return false
}

fun Ui.lockedPanel(r: Rect, title: String, teaser: String, lock: Lock, icon: Icon = Icons.LOCK, key: Any = "locked-panel") {
    panel(r, sunken = true)
    val w = (r.w - 24).coerceAtMost(260)
    val teaserH = Draw.paragraphHeight(teaser, w)
    val how = lock.how?.let { Draw.paragraphHeight(it, w) } ?: 0
    val height = Draw.ICON + 6 + 12 + teaserH + 8 + CHIP_H + if (how > 0) 6 + how else 0
    var y = r.y + ((r.h - height) / 2).coerceAtLeast(4)
    val x = r.x + (r.w - w) / 2
    Draw.tintedIcon(g, icon, r.centerX - Draw.ICON / 2, y, Draw.ICON, Palette.textMuted)
    y += Draw.ICON + 6
    Draw.textCentered(g, Draw.fit(title, w), Rect(x, y, w, 10), Palette.text, TextStyle.HEADING)
    y += 12
    Draw.paragraph(g, teaser, x, y, w, Palette.textMuted)
    y += teaserH + 8
    val chipW = lockChipWidth(lock)
    lockChip(r.centerX - chipW / 2, y, lock, "$key:chip")
    y += CHIP_H
    lock.how?.let { Draw.paragraph(g, it, x, y + 6, w, Palette.warning) }
    tooltip(key, r, lock.how)
}

private fun Ui.capacityNext(x: Int, r: Rect, next: Lock, full: Boolean, reserve: Int) {
    val color = if (full) Palette.warning else Palette.textMuted
    val textX = x + Draw.leadIcon(g, Icons.LOCK, x, r.centerY, color) + 1
    Draw.text(g, Draw.fit(next.label, r.right - textX - reserve), textX, r.y + 1, color)
}

fun Ui.capacityRow(stack: Stack, label: String, used: Int, max: Int, next: Lock?, key: Any = "capacity:$label") {
    val full = max > 0 && used >= max
    progressBar(stack.take(CAPACITY_BAR_H), used.toLong(), max.toLong(), label, null, if (max > 0 && used * 10L >= max * 9L) Palette.warning else Palette.brass, Palette.warning, key = key)
    if (next == null) return
    val hint = stack.take(CAPACITY_HINT_H)
    if (full) Draw.text(g, tr("kami_libs.lock.ui.full"), hint.right - Draw.width(tr("kami_libs.lock.ui.full")), hint.y + 1, Palette.warning)
    capacityNext(hint.x, hint, next, full, 40)
    tooltip("$key:next", hint, next.how)
}

fun Ui.capacityLine(r: Rect, label: String, used: Int, max: Int, next: Lock?, key: Any = "capacity-line:$label") {
    val full = max > 0 && used >= max
    val count = "${Format.number(used)}/${Format.number(max)}"
    Draw.text(g, label, r.x, r.y + 1, Palette.textSecondary)
    val x = r.x + Draw.width(label) + 6
    Draw.text(g, count, x, r.y + 1, if (full) Palette.warning else Palette.text)
    if (next == null) return
    capacityNext(x + Draw.width(count) + 6, r, next, full, 0)
    tooltip(key, r, next.how)
}
