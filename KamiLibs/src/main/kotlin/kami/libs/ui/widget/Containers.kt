package kami.libs.ui.widget

import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.style.UiSound
import kotlin.math.max

class ScrollState {
    var offset = 0.0
    var target = 0.0
    var content = 0
    var view = 0
    var reveal: Int? = null

    val max get() = kotlin.math.max(0, content - view)
    fun scrollTo(y: Int) { reveal = y }
}

fun Ui.scroll(key: Any, r: Rect, contentHeight: Int, draw: (Rect) -> Unit): ScrollState {
    val s = remember("scroll:$key") { ScrollState() }
    s.content = contentHeight
    s.view = r.h
    s.reveal?.let { y -> if (y < s.target || y > s.target + r.h - 20) s.target = (y - 20).toDouble(); s.reveal = null }
    val w = wheel(r)
    if (w != 0.0) s.target -= w * 24
    s.target = s.target.coerceIn(0.0, s.max.toDouble())
    s.offset += (s.target - s.offset) * (if (reduceMotion) 1.0 else (1 - kotlin.math.exp(-18.0 * dt)))
    if (kotlin.math.abs(s.target - s.offset) < 0.5) s.offset = s.target
    val bar = s.max > 0
    val content = Rect(r.x, r.y - s.offset.toInt(), r.w - if (bar) 6 else 0, max(contentHeight, r.h))
    clip(r) { draw(content) }
    if (bar) {
        val track = Rect(r.right - 4, r.y + 1, 3, r.h - 2)
        val thumbH = max(12, track.h * r.h / max(1, contentHeight))
        val thumbY = track.y + ((track.h - thumbH) * (s.offset / s.max)).toInt()
        val thumb = Rect(track.x, thumbY, 3, thumbH)
        val id = id("scrollbar:$key")
        val hover = hovering(track.grow(2))
        if (hover) cursor = Cursor.HAND
        if (pressed(track.grow(2)) != null) active = id
        if (active == id && isDown()) {
            val f = ((mouseY - track.y - thumbH / 2).toDouble() / max(1, track.h - thumbH)).coerceIn(0.0, 1.0)
            s.target = f * s.max
            s.offset = s.target
        }
        Draw.fill(g, track, Palette.alpha(Palette.border, 0x80))
        Draw.fill(g, thumb, if (hover || active == id) Palette.textSecondary else Palette.textMuted)
    }
    return s
}

fun Ui.panel(r: Rect, sunken: Boolean = false) = Draw.sprite(g, if (sunken) Sprites.SUNKEN else Sprites.PANEL, r)

fun Ui.card(r: Rect, title: String? = null, icon: Icon? = null, severity: Severity? = null, help: String? = null, trailing: String? = null, key: Any = title ?: "card"): Rect {
    Draw.sprite(g, Sprites.CARD, r)
    severity?.let { Draw.fill(g, Rect(r.x + 1, r.y + 1, 2, r.h - 2), it.color) }
    if (title == null) return r.inset(6)
    val header = Rect(r.x, r.y, r.w, 18)
    Draw.fill(g, header.inset(1, 1, 1, 0), Palette.alpha(severity?.color ?: Palette.raised, if (severity != null) 0x30 else 0xFF))
    Draw.hline(g, r.x + 1, header.bottom, r.w - 2, Palette.borderSubtle)
    var x = r.x + 6
    icon?.let { Draw.icon(g, it, x, r.y + 2, 14); x += 16 }
    val titleColor = severity?.color ?: Palette.text
    val trailingReserve = (trailing?.let { Draw.width(it) + 10 } ?: 0) + if (help != null) 18 else 0
    Draw.text(g, Draw.fit(title, (r.w - (x - r.x) - trailingReserve - 8).coerceAtLeast(20)), x, r.y + 5, TextStyle.TITLE, titleColor)
    trailing?.let { Draw.textRight(g, Draw.fit(it, (r.w / 2).coerceAtLeast(26)), r.right - (if (help != null) 20 else 6), r.y + 5, Palette.textMuted) }
    help?.let { h ->
        val hr = Rect(r.right - 16, r.y + 3, 12, 12)
        Draw.tintedIcon(g, kami.libs.ui.style.Icons.INFO, hr.x - 2, hr.y - 2, 16, if (hovering(hr)) Palette.text else Palette.textMuted)
        tooltip("help:$key", hr) { Tip.text(h, title) }
    }
    return Rect(r.x + 6, header.bottom + 5, r.w - 12, r.h - header.h - 10)
}

fun Ui.section(r: Rect, title: String, trailing: String? = null): Rect {
    Draw.text(g, title, r.x, r.y, TextStyle.LABEL)
    trailing?.let { Draw.textRight(g, it, r.right, r.y, Palette.textMuted) }
    Draw.hline(g, r.x, r.y + 10, r.w, Palette.borderSubtle)
    return r.dropTop(14)
}

fun Ui.divider(x: Int, y: Int, w: Int, label: String? = null, color: Int = Palette.borderSubtle) {
    if (label == null) return Draw.hline(g, x, y, w, color)
    val lw = Draw.width(label) + 8
    Draw.hline(g, x, y, 8, color)
    Draw.text(g, label, x + 12, y - 4, Palette.textMuted)
    Draw.hline(g, x + 12 + lw, y, w - 12 - lw, color)
}

class TabItem(val label: String, val icon: Icon? = null, val badge: Int = 0, val badgeSeverity: Severity = Severity.INFO, val disabledReason: String? = null)

fun Ui.subTabs(r: Rect, tabs: List<TabItem>, selected: Int, key: Any = "tabs"): Int? {
    var result: Int? = null
    var x = r.x
    Draw.hline(g, r.x, r.bottom - 1, r.w, Palette.borderSubtle)
    val remainingMin = { index: Int -> ((tabs.size - index - 1) * 36).coerceAtLeast(0) }
    tabs.forEachIndexed { i, t ->
        val natural = Draw.width(t.label) + 16 + (if (t.icon != null) 18 else 0) + (if (t.badge > 0) Draw.width(t.badge.toString()) + 10 else 0)
        val maxW = (r.right - x - remainingMin(i)).coerceAtLeast(36)
        val w = natural.coerceIn(36, maxW)
        val cell = Rect(x, r.y, w, r.h)
        val usable = t.disabledReason == null
        focusable("$key:$i")
        val hover = hover("$key:$i", cell) && usable
        if (hover) cursor = Cursor.HAND
        if (i == selected) Draw.sprite(g, Sprites.TAB_ACTIVE, cell.inset(0, 0, 0, 1))
        else if (hover) Draw.fill(g, cell.inset(0, 2, 0, 1), Palette.hover)
        var tx = cell.x + 8
        t.icon?.let { Draw.icon(g, it, tx, cell.y + (cell.h - 14) / 2, 14); tx += 16 }
        val badgeText = if (t.badge > 0) t.badge.toString() else null
        val badgeW = badgeText?.let { max(10, Draw.width(it) + 5) } ?: 0
        val textW = (cell.right - tx - if (badgeW > 0) badgeW + 4 else 6).coerceAtLeast(6)
        val label = Draw.fit(t.label, textW)
        Draw.text(g, label, tx, cell.y + (cell.h - 8) / 2, when {
            !usable -> Palette.textDisabled
            i == selected -> Palette.text
            else -> Palette.textMuted
        })
        if (badgeText != null) badge(cell.right - badgeW - 4, cell.y + (cell.h - 10) / 2, badgeText, t.badgeSeverity)
        if (i == selected) Draw.fill(g, Rect(cell.x + 2, cell.bottom - 2, cell.w - 4, 2), Palette.brass)
        t.disabledReason?.let { reason -> tooltip("$key:$i", cell) { Tip.disabled(reason) } }
        focusRing("$key:$i", cell)
        if (usable && i != selected && (pressed(cell) != null || activatedByKey("$key:$i"))) { result = i; UiSound.page() }
        x += w + 2
    }
    return result
}

fun Ui.badge(x: Int, y: Int, text: String, severity: Severity = Severity.INFO): Int {
    val w = max(10, Draw.width(text) + 5)
    Draw.tinted(g, Sprites.BADGE, Rect(x, y, w, 10), severity.color)
    Draw.text(g, text, x + (w - Draw.width(text)) / 2 + 1, y + 1, Palette.textInverse)
    return w
}

fun Ui.emptyState(r: Rect, title: String, body: String, illustration: net.minecraft.resources.ResourceLocation? = null, action: String? = null, actionIcon: Icon? = null, key: Any = "empty"): Boolean {
    var y = r.y + max(4, (r.h - 120) / 3)
    illustration?.let { Draw.sprite(g, it, Rect(r.centerX - 32, y, 64, 64)); y += 70 }
    Draw.text(g, title, r.centerX - Draw.width(title, TextStyle.TITLE) / 2, y, TextStyle.TITLE)
    y += 14
    val lines = Draw.wrap(body, kotlin.math.min(260, r.w - 20))
    lines.forEach { line -> g.drawString(Draw.font, line, r.centerX - Draw.font.width(line) / 2, y, Palette.textSecondary, false); y += Draw.LINE }
    if (action == null) return false
    val w = buttonWidth(action, actionIcon)
    return button(Rect(r.centerX - w / 2, y + 6, w, CONTROL_H), action, actionIcon, ButtonStyle.PRIMARY, key = "$key:action")
}
