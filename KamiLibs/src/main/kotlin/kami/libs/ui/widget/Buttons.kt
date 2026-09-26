package kami.libs.ui.widget

import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
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
    DANGER(Sprites.Look.DANGER, { Palette.text }),
    GHOST(Sprites.Look.GHOST, { Palette.textSecondary })
}

const val CONTROL_H = 20
const val SMALL_H = 16

fun buttonWidth(label: String, icon: Icon? = null) = Draw.width(label) + (if (icon != null) 20 else 0) + 16

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
    Draw.sprite(g, style.look.of(hover && usable, pressedNow, usable), r)
    val color = if (usable) style.text() else Palette.textDisabled
    val iconSize = if (r.h <= CONTROL_H) 14 else 16
    val iconSlot = if (icon != null || pending) (iconSize + 2) else 0
    val content = iconSlot + Draw.width(label)
    var x = r.x + (r.w - content) / 2
    val shift = if (pressedNow) 1 else 0
    if (pending) spinner(x, r.centerY - 4 + shift, color)
    else if (icon != null) Draw.icon(g, icon, x, r.y + (r.h - iconSize) / 2 + shift, iconSize)
    if (icon != null || pending) x += iconSlot
    if (label.isNotEmpty()) Draw.text(g, Draw.fit(label, r.w - 8 - (x - r.x)), x, r.y + (r.h - 8) / 2 + shift, TextStyle.BODY, color)
    if (!enabled && disabledReason != null) tooltip(key, r) { Tip.disabled(disabledReason) } else tooltip(key, r, tip)
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
    Draw.sprite(g, look.of(hover && enabled, active == id && isDown(), enabled), r)
    if (selected) Draw.fill(g, r.bottom(2).inset(3, 0), Palette.brass)
    val size = if (r.h <= CONTROL_H) 14 else 16
    if (enabled) Draw.icon(g, icon, r, size) else Draw.tintedIcon(g, icon, r.x + (r.w - size) / 2, r.y + (r.h - size) / 2, size, Palette.alpha(0xFFFFFF, 0x60))
    if (!enabled && disabledReason != null) tooltip(key, r) { Tip.disabled(disabledReason) } else tooltip(key, r, tip)
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
    if (enabled && pressed(r) != null) { active = id; state[0] = now }
    val holding = enabled && active == id && isDown() && hover
    if (!holding) state[0] = 0L
    val progress = if (state[0] == 0L) 0f else ((now - state[0]).toFloat() / holdMs).coerceIn(0f, 1f)
    Draw.sprite(g, Sprites.Look.DANGER.of(hover && enabled, holding, enabled), r)
    if (progress > 0f) Draw.fill(g, Rect(r.x + 2, r.y + 2, ((r.w - 4) * progress).toInt(), r.h - 4), Palette.alpha(0xFFFFFF, 0x40))
    val text = if (holding) "Keep holding…" else label
    Draw.textCentered(g, Draw.fit(text, r.w - 8), r, if (enabled) Palette.text else Palette.textDisabled)
    if (!enabled && disabledReason != null) tooltip(key, r) { Tip.disabled(disabledReason) }
    else tooltip(key, r) { Tip("Hold to confirm", listOf("Press and hold for ${holdMs / 1000.0}s. Release early to cancel." to Palette.textSecondary)) }
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
    val step = ((now / 120) % 8).toInt()
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
    Draw.sprite(g, Sprites.KEYCAP, Rect(x, y, w, 12))
    Draw.text(g, label, x + 3, y + 2, Palette.text)
    return w
}

fun Ui.closeButton(r: Rect, key: Any = "close") = iconButton(r, Icons.CLOSE, "Close  [Esc]", key = key)
