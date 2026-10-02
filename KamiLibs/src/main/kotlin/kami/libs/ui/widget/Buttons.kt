package kami.libs.ui.widget

import kami.libs.ui.style.Format
import kami.libs.ui.text.tr
import kami.libs.ui.anim.feel
import kami.libs.ui.anim.Feel
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.style.UiSound

enum class ButtonStyle(val look: Sprites.Look, val text: () -> Int) {
    PRIMARY(Sprites.Look.PRIMARY, { Palette.textInverse }),
    SECONDARY(Sprites.Look.SECONDARY, { Palette.text }),
    DANGER(Sprites.Look.DANGER, { Palette.textInverse }),
    GHOST(Sprites.Look.GHOST, { Palette.textSecondary })
}

const val CONTROL_H = 18
const val SMALL_H = 16
private const val HOVER_LIGHTEN = 0x1C

fun buttonWidth(label: String, icon: Icon? = null) = Draw.width(label) + (if (icon != null) Draw.ICON else 0) + 14

fun Ui.edgeButton(
    r: Rect, label: String, icon: Icon? = null, style: ButtonStyle = ButtonStyle.SECONDARY, enabled: Boolean = true,
    disabledReason: String? = null, tip: String? = null, pending: Boolean = false, left: Boolean = false, key: Any = label
) = button(if (left) r.left(buttonWidth(label, icon)) else r.right(buttonWidth(label, icon)), label, icon, style, enabled, disabledReason, tip, pending, key)

fun Ui.edgeButton(
    row: Row, label: String, icon: Icon? = null, style: ButtonStyle = ButtonStyle.SECONDARY, enabled: Boolean = true,
    disabledReason: String? = null, tip: String? = null, pending: Boolean = false, left: Boolean = false, key: Any = label
) = button(if (left) row.take(buttonWidth(label, icon)) else row.takeFromRight(buttonWidth(label, icon)), label, icon, style, enabled, disabledReason, tip, pending, key)

fun Row.iconSlot() = takeFromRight(SMALL_H).centered(SMALL_H, SMALL_H)

fun Ui.adaptiveButton(
    row: Row, narrow: Boolean, label: String, icon: Icon, style: ButtonStyle = ButtonStyle.SECONDARY, enabled: Boolean = true,
    disabledReason: String? = null, pending: Boolean = false, key: Any = label
) = if (narrow) iconButton(row.iconSlot(), icon, label, enabled = enabled && !pending, disabledReason = disabledReason, key = key)
else edgeButton(row, label, icon, style, enabled, disabledReason, pending = pending, key = key)

private fun Ui.hoverWash(r: Rect, feel: Feel) =
    Draw.fill(g, r.inset(1), Palette.alpha(0xFFFFFF, (HOVER_LIGHTEN * feel.hover * (1f - feel.press)).toInt()))

fun Ui.button(
    r: Rect, label: String, icon: Icon? = null, style: ButtonStyle = ButtonStyle.SECONDARY, enabled: Boolean = true,
    disabledReason: String? = null, tip: String? = null, pending: Boolean = false, key: Any = label
): Boolean {
    val id = focusable(key)
    val hover = hover(key, r)
    val usable = enabled && !pending
    val pressedNow = hover && usable && isDown() && active == id
    if (usable && hover) cursor = Cursor.HAND
    if (usable && pressed(r) != null) active = id
    val feel = feel(key, hover && usable, pressedNow)
    Draw.sprite(g, style.look.of(false, pressedNow, usable), r)
    if (usable) hoverWash(r, feel)
    val sink = if (feel.press > 0.5f) 1 else 0
    val color = if (usable) style.text() else Palette.textDisabled
    val iconSlot = if (icon != null || pending) (if (label.isEmpty()) Draw.ICON - 4 else Draw.ICON) else 0
    val content = iconSlot + Draw.width(label)
    var x = r.x + maxOf(2, (r.w - content) / 2)
    if (pending) spinner(x, r.centerY - 4 + sink, color)
    else if (icon != null) Draw.leadIcon(g, icon, x, r.centerY + sink, if (!usable) Palette.iconOff else if (style == ButtonStyle.PRIMARY || style == ButtonStyle.DANGER) Palette.textInverse else null)
    x += iconSlot
    val shown = Draw.fit(label, r.w - 6 - (x - r.x))
    if (label.isNotEmpty()) Draw.text(g, shown, x, r.y + (r.h - 8) / 2 + sink, TextStyle.BODY, color)
    controlTip(key, r, enabled, disabledReason, tip ?: label.takeIf { it != shown })
    focusRing(key, r)
    val released = active == id && released() != null
    if (released) active = null
    val fired = usable && ((released && hover) || activatedByKey(key))
    if (fired) UiSound.click()
    return fired
}

fun Ui.iconButton(r: Rect, icon: Icon, tip: String, enabled: Boolean = true, selected: Boolean = false, key: Any = icon.sprite.path, disabledReason: String? = null): Boolean {
    val id = focusable(key)
    val hover = hover(key, r)
    if (enabled && hover) cursor = Cursor.HAND
    if (enabled && pressed(r) != null) active = id
    val look = if (selected) Sprites.Look.SECONDARY else Sprites.Look.GHOST
    val down = enabled && active == id && isDown()
    val feel = feel(key, hover && enabled, down)
    Draw.sprite(g, look.of(false, down, enabled), r)
    if (enabled) hoverWash(r, feel)
    if (selected) Draw.hline(g, r.x + 2, r.bottom - 1, r.w - 4, Palette.brass)
    val x = r.x + (r.w - Draw.ICON) / 2
    val y = r.y + (r.h - Draw.ICON) / 2 + if (feel.press > 0.5f) 1 else 0
    if (enabled) Draw.icon(g, icon, x, y) else Draw.tintedIcon(g, icon, x, y, Draw.ICON, Palette.iconOff)
    controlTip(key, r, enabled, disabledReason, tip)
    focusRing(key, r)
    val released = active == id && released() != null
    if (released) active = null
    val fired = enabled && ((released && hover) || activatedByKey(key))
    if (fired) UiSound.click()
    return fired
}

fun Ui.holdButton(r: Rect, label: String, holdMs: Long = 1500, enabled: Boolean = true, disabledReason: String? = null, key: Any = "hold:$label"): Boolean {
    val id = focusable(key)
    val hover = hover(key, r)
    val state = remember("holdStart:$key") { longArrayOf(0L) }
    if (enabled && hover) cursor = Cursor.HAND
    if (enabled && pressed(r) != null) { active = id; state[0] = wallMillis }
    val holding = enabled && active == id && isDown() && hover
    if (!holding) state[0] = 0L
    val progress = if (state[0] == 0L) 0f else ((wallMillis - state[0]).toFloat() / holdMs).coerceIn(0f, 1f)
    Draw.sprite(g, Sprites.Look.DANGER.of(hover && enabled, holding, enabled), r)
    if (progress > 0f) Draw.fill(g, Rect(r.x + 1, r.y + 1, ((r.w - 2) * progress).toInt(), r.h - 2), Palette.alpha(0xFFFFFF, 0x28))
    val text = if (holding) tr("kami_libs.common.keep_holding") else label
    Draw.textCentered(g, Draw.fit(text, r.w - 8), r, if (enabled) Palette.textInverse else Palette.textDisabled)
    controlTip(key, r, enabled, disabledReason, tr("kami_libs.common.hold_to_confirm.tooltip", Format.decimal(holdMs / 1000.0)))
    if (released() != null && active == id) active = null
    if (progress >= 1f) {
        state[0] = 0L
        active = null
        UiSound.confirm()
        return true
    }
    return false
}

fun Ui.link(x: Int, y: Int, label: String, key: Any = "link:$label"): Boolean {
    val r = Rect(x, y - 1, Draw.width(label), 10)
    val hover = hover(key, r)
    if (hover) cursor = Cursor.HAND
    Draw.text(g, label, x, y, TextStyle.LINK)
    if (hover) Draw.hline(g, x, y + 9, r.w, Palette.link)
    return pressed(r) != null
}

fun Ui.spinner(x: Int, y: Int, color: Int = Palette.text) {
    val step = ((wallMillis / 120) % 8).toInt()
    for (i in 0 until 8) {
        val angle = i * Math.PI / 4
        val px = x + 4 + (Math.cos(angle) * 3).toInt()
        val py = y + 4 + (Math.sin(angle) * 3).toInt()
        val a = 0x30 + ((i - step).mod(8)) * 0x1C
        g.fill(px, py, px + 1, py + 1, Palette.alpha(color, a))
    }
}

fun Ui.keycap(x: Int, y: Int, label: String): Int {
    val w = Draw.width(label) + 6
    Draw.sprite(g, Sprites.KEYCAP, Rect(x, y, w, 11))
    Draw.text(g, label, x + 3, y + 2, Palette.textSecondary)
    return w
}

fun Ui.closeButton(r: Rect, key: Any = "close") = iconButton(r, Icons.CLOSE, tr("kami_libs.common.close.tooltip"), key = key)

fun Ui.controlTip(key: Any, r: Rect, enabled: Boolean, disabledReason: String?, tip: String?) {
    if (!enabled && disabledReason != null) tooltip(key, r) { Tip.disabled(disabledReason) }
    else if (!tip.isNullOrEmpty()) tooltip(key, r, tip)
}

fun Ui.pager(r: Rect, page: Int, pages: Int, key: Any = "pager"): Int? {
    val prev = r.left(r.h)
    val next = r.right(r.h)
    var result: Int? = null
    if (iconButton(prev, Icons.BACK, tr("kami_libs.pager.prev.tooltip"), enabled = page > 0, key = "$key:prev")) result = page - 1
    if (iconButton(next, Icons.FORWARD, tr("kami_libs.pager.next.tooltip"), enabled = page < pages - 1, key = "$key:next")) result = page + 1
    Draw.textCentered(g, tr("kami_libs.format.ratio", page + 1, pages.coerceAtLeast(1)), Rect(prev.right, r.y, next.x - prev.right, r.h), Palette.textSecondary)
    return result
}

fun Ui.disclosure(r: Rect, label: String, open: Boolean, trailing: String? = null, key: Any = "disclosure:$label", hairline: Boolean = false): Boolean {
    val hover = hover(key, r)
    if (hover) Draw.fill(g, r, Palette.hover)
    val color = if (hover) Palette.text else Palette.textSecondary
    Draw.tintedIcon(g, if (open) Icons.CHEVRON_DOWN else Icons.CHEVRON_RIGHT, r.x - 2, r.centerY - Draw.ICON / 2, Draw.ICON, color)
    Draw.text(g, label, r.x + 11, r.centerY - 4, color)
    trailing?.let { Draw.textRight(g, it, r.right - 2, r.centerY - 4, Palette.textMuted) }
    if (hairline) Draw.hline(g, r.x, r.bottom - 1, r.w, Palette.borderSubtle)
    return clickable(key, r)
}
