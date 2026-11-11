package kami.libs.ui.widget

import net.minecraft.client.gui.GuiGraphics
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
import kami.libs.ui.style.Metrics
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

val CONTROL_H: Int get() = Metrics.controlH
val SMALL_H: Int get() = Metrics.smallH
private const val HOVER_LIGHTEN = 0x1C

fun buttonWidth(label: String, icon: Icon? = null) = Draw.width(label) + (if (icon != null) Draw.ICON else 0) + 14

fun Ui.edgeButton(
    r: Rect, label: String, icon: Icon? = null, style: ButtonStyle = ButtonStyle.SECONDARY, enabled: Boolean = true,
    disabledReason: String? = null, tip: String? = null, pending: Boolean = false, left: Boolean = false, key: Any? = null
) = button(if (left) r.left(buttonWidth(label, icon)) else r.right(buttonWidth(label, icon)), label, icon, style, enabled, disabledReason, tip, pending, key)

fun Ui.edgeButton(
    row: Row, label: String, icon: Icon? = null, style: ButtonStyle = ButtonStyle.SECONDARY, enabled: Boolean = true,
    disabledReason: String? = null, tip: String? = null, pending: Boolean = false, left: Boolean = false, key: Any? = null
) = button(if (left) row.take(buttonWidth(label, icon)) else row.takeFromRight(buttonWidth(label, icon)), label, icon, style, enabled, disabledReason, tip, pending, key)

fun Row.iconSlot() = takeFromRight(SMALL_H).centered(SMALL_H, SMALL_H)

fun Ui.adaptiveButton(
    row: Row, narrow: Boolean, label: String, icon: Icon, style: ButtonStyle = ButtonStyle.SECONDARY, enabled: Boolean = true,
    disabledReason: String? = null, pending: Boolean = false, key: Any? = null
): Boolean {
    val k = key ?: autoKey(label)
    return if (narrow) iconButton(row.iconSlot(), icon, label, enabled = enabled && !pending, disabledReason = disabledReason, key = k)
    else edgeButton(row, label, icon, style, enabled, disabledReason, pending = pending, key = k)
}

private fun Ui.hoverWash(r: Rect, feel: Feel) =
    Draw.fill(g, r.inset(1), Palette.alpha(Palette.washLight, (HOVER_LIGHTEN * feel.hover * (1f - feel.press)).toInt()))

private class Press(val id: String, val hover: Boolean, val down: Boolean)

private fun Ui.pressStart(key: Any, r: Rect, usable: Boolean): Press {
    val id = focusable(key)
    val hover = hover(key, r)
    if (usable && hover) cursor = Cursor.HAND
    if (usable && pressed(r) != null) active = id
    return Press(id, hover, hover && usable && isDown() && active == id)
}

private fun Ui.pressEnd(key: Any, p: Press, usable: Boolean): Boolean {
    val released = active == p.id && released() != null
    if (released) active = null
    val fired = usable && ((released && p.hover) || activatedByKey(key))
    if (fired) UiSound.click()
    return fired
}

fun Ui.button(
    r: Rect, label: String, icon: Icon? = null, style: ButtonStyle = ButtonStyle.SECONDARY, enabled: Boolean = true,
    disabledReason: String? = null, tip: String? = null, pending: Boolean = false, key: Any? = null
): Boolean {
    val k = key ?: autoKey(label)
    val usable = enabled && !pending
    val p = pressStart(k, r, usable)
    val feel = feel(k, p.hover && usable, p.down)
    Draw.sprite(g, style.look.of(false, p.down, usable), r)
    if (usable) hoverWash(r, feel)
    val sink = if (feel.press > 0.5f) 1 else 0
    val color = if (usable) style.text() else Palette.textDisabled
    val iconSlot = if (icon != null || pending) (if (label.isEmpty()) Draw.ICON - 4 else Draw.ICON) else 0
    val shown = Draw.fit(label, r.w - iconSlot - 8)
    val content = iconSlot + Draw.width(shown)
    var x = r.x + maxOf(2, (r.w - content) / 2)
    if (pending) spinner(x, r.centerY - 4 + sink, color)
    else if (icon != null) Draw.leadIcon(g, icon, x, r.centerY + sink, if (!usable) Palette.iconOff else if (style == ButtonStyle.PRIMARY || style == ButtonStyle.DANGER) Palette.textInverse else null)
    x += iconSlot
    if (label.isNotEmpty()) Draw.text(g, shown, x, Draw.textY(r) + sink, TextStyle.BODY, color)
    controlTip(k, r, enabled, disabledReason, tip ?: label.takeIf { it != shown })
    focusRing(k, r)
    return pressEnd(k, p, usable)
}

fun Ui.iconButton(r: Rect, icon: Icon, tip: String, enabled: Boolean = true, selected: Boolean = false, key: Any? = null, disabledReason: String? = null, pending: Boolean = false): Boolean {
    val k = key ?: autoKey(icon.sprite.path)
    val usable = enabled && !pending
    val p = pressStart(k, r, usable)
    val look = if (selected) Sprites.Look.SECONDARY else Sprites.Look.GHOST
    val feel = feel(k, p.hover && usable, p.down)
    Draw.sprite(g, look.of(false, p.down, usable), r)
    if (usable) hoverWash(r, feel)
    if (selected) Draw.hline(g, r.x + 2, r.bottom - 1, r.w - 4, Palette.brass)
    val x = r.x + (r.w - Draw.ICON) / 2
    val y = r.y + (r.h - Draw.ICON) / 2 + if (feel.press > 0.5f) 1 else 0
    if (pending) spinner(r.x + (r.w - 8) / 2, r.centerY - 4, Palette.textDisabled)
    else if (enabled) Draw.icon(g, icon, x, y) else Draw.tintedIcon(g, icon, x, y, Draw.ICON, Palette.iconOff)
    controlTip(k, r, enabled, disabledReason, tip)
    focusRing(k, r)
    return pressEnd(k, p, usable)
}

private const val MIN_HOLD_MS = 400L
private const val KEY_CONFIRM_MS = 1500L

fun Ui.holdButton(r: Rect, label: String, holdMs: Long = 1500, enabled: Boolean = true, disabledReason: String? = null, key: Any? = null): Boolean {
    val k = key ?: autoKey("hold:$label")
    val ms = holdMs.coerceAtLeast(MIN_HOLD_MS)
    val id = focusable(k)
    val hover = hover(k, r)
    val state = remember("holdStart:$k") { longArrayOf(0L, 0L) }
    if (enabled && hover) cursor = Cursor.HAND
    if (enabled && pressed(r) != null) { active = id; state[0] = wallMillis; UiSound.click() }
    val holding = enabled && active == id && isDown() && hover
    if (!holding) state[0] = 0L
    var confirmed = false
    if (enabled && activatedByKey(k, repeats = false)) {
        if (state[1] != 0L && wallMillis - state[1] <= KEY_CONFIRM_MS) confirmed = true else { state[1] = wallMillis; UiSound.click() }
    }
    if (state[1] != 0L && wallMillis - state[1] > KEY_CONFIRM_MS) state[1] = 0L
    val progress = if (state[0] != 0L) ((wallMillis - state[0]).toFloat() / ms).coerceIn(0f, 1f)
    else if (state[1] != 0L) 1f - (wallMillis - state[1]).toFloat() / KEY_CONFIRM_MS else 0f
    Draw.sprite(g, Sprites.Look.DANGER.of(hover && enabled, holding, enabled), r)
    if (progress > 0f) Draw.fill(g, Rect(r.x + 1, r.y + 1, ((r.w - 2) * progress).toInt(), r.h - 2), Palette.alpha(Palette.washLight, 0x28))
    val text = if (holding) tr("kami_libs.common.keep_holding") else label
    Draw.textCentered(g, Draw.fit(text, r.w - 8), r, if (enabled) Palette.textInverse else Palette.textDisabled)
    controlTip(k, r, enabled, disabledReason, tr("kami_libs.common.hold_to_confirm.tooltip", Format.decimal(ms / 1000.0)))
    focusRing(k, r)
    if (released() != null && active == id) active = null
    if (confirmed || (state[0] != 0L && progress >= 1f)) {
        state[0] = 0L
        state[1] = 0L
        active = null
        UiSound.confirm()
        return true
    }
    return false
}

fun Ui.link(x: Int, y: Int, label: String, key: Any? = null): Boolean {
    val k = key ?: autoKey("link:$label")
    val r = Rect(x, y - 1, Draw.width(label), 10)
    val hover = hover(k, r)
    if (hover) cursor = Cursor.HAND
    Draw.text(g, label, x, y, TextStyle.LINK)
    if (hover) Draw.hline(g, x, y + 9, r.w, Palette.link)
    return pressed(r) != null
}

fun Ui.spinner(x: Int, y: Int, color: Int = Palette.text) = spinner(g, x, y, wallMillis, color)

fun spinner(g: GuiGraphics, x: Int, y: Int, millis: Long, color: Int = Palette.text) {
    val step = ((millis / 120) % 8).toInt()
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

fun Ui.disclosure(r: Rect, label: String, open: Boolean, trailing: String? = null, key: Any? = null, hairline: Boolean = false): Boolean {
    val k = key ?: autoKey("disclosure:$label")
    val hover = hover(k, r)
    if (hover) Draw.fill(g, r, Palette.hover)
    val color = if (hover) Palette.text else Palette.textSecondary
    Draw.tintedIcon(g, if (open) Icons.CHEVRON_DOWN else Icons.CHEVRON_RIGHT, r.x - 2, r.centerY - Draw.ICON / 2, Draw.ICON, color)
    Draw.text(g, label, r.x + 11, r.centerY - 4, color)
    trailing?.let { Draw.textRight(g, it, r.right - 2, r.centerY - 4, Palette.textMuted) }
    if (hairline) Draw.hline(g, r.x, r.bottom - 1, r.w, Palette.borderSubtle)
    return clickable(k, r)
}
