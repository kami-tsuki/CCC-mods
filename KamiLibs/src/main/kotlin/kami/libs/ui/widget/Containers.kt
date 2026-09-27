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
    val content = Rect(r.x, r.y - s.offset.toInt(), r.w - if (bar) 4 else 0, max(contentHeight, r.h))
    clip(r) { draw(content) }
    if (bar) {
        val track = Rect(r.right - 3, r.y + 1, 2, r.h - 2)
        val thumbH = max(12, track.h * r.h / max(1, contentHeight))
        val thumbY = track.y + ((track.h - thumbH) * (s.offset / s.max)).toInt()
        val thumb = Rect(track.x, thumbY, 2, thumbH)
        val id = id("scrollbar:$key")
        val hover = hovering(track.grow(2))
        if (hover) cursor = Cursor.HAND
        if (pressed(track.grow(2)) != null) active = id
        if (active == id && isDown()) {
            val f = ((mouseY - track.y - thumbH / 2).toDouble() / max(1, track.h - thumbH)).coerceIn(0.0, 1.0)
            s.target = f * s.max
            s.offset = s.target
        }
        Draw.fill(g, track, Palette.alpha(Palette.border, 0x60))
        Draw.fill(g, thumb, if (hover || active == id) Palette.textSecondary else Palette.textMuted)
    }
    return s
}

const val CARD_HEADER_H = 16

fun Ui.panel(r: Rect, sunken: Boolean = false) = Draw.sprite(g, if (sunken) Sprites.SUNKEN else Sprites.PANEL, r)

fun Ui.card(r: Rect, title: String? = null, icon: Icon? = null, severity: Severity? = null, help: String? = null, trailing: String? = null, key: Any = title ?: "card"): Rect {
    Draw.sprite(g, Sprites.CARD, r)
    severity?.let { Draw.vline(g, r.x, r.y, r.h, it.color) }
    if (title == null) return r.inset(5)
    val header = Rect(r.x, r.y, r.w, CARD_HEADER_H)
    Draw.fill(g, header.inset(1, 1, 1, 0), severity?.let { Palette.alpha(it.color, 0x1C) } ?: Palette.raised)
    Draw.hline(g, r.x + 1, header.bottom, r.w - 2, Palette.borderSubtle)
    var x = r.x + 5
    icon?.let { x += Draw.leadIcon(g, it, x, header.centerY) }
    val titleColor = severity?.color ?: Palette.text
    val textY = header.y + (header.h - 8) / 2 + 1
    val trailingReserve = (trailing?.let { Draw.width(it) + 8 } ?: 0) + if (help != null) 14 else 0
    val shownTitle = Draw.fit(title, (r.w - (x - r.x) - trailingReserve - 6).coerceAtLeast(20), TextStyle.HEADING)
    Draw.text(g, shownTitle, x, textY, TextStyle.HEADING, titleColor)
    if (shownTitle != title) tooltip("title:$key", header) { Tip.text(title) }
    trailing?.let { Draw.textRight(g, Draw.fit(it, (r.w / 2).coerceAtLeast(26)), r.right - (if (help != null) 17 else 5), textY, Palette.textMuted) }
    help?.let { h ->
        val hr = Rect(r.right - 15, header.y + (header.h - 12) / 2 + 1, 12, 12)
        Draw.tintedIcon(g, kami.libs.ui.style.Icons.HELP, hr.x - 2, hr.y - 2, Draw.ICON, if (hovering(hr)) Palette.text else Palette.textMuted)
        tooltip("help:$key", hr) { Tip.text(h, title) }
    }
    return Rect(r.x + 5, header.bottom + 4, r.w - 10, r.h - header.h - 8)
}

fun Ui.section(r: Rect, title: String, trailing: String? = null): Rect {
    Draw.text(g, title, r.x, r.y, TextStyle.LABEL)
    trailing?.let { Draw.textRight(g, it, r.right, r.y, Palette.textMuted) }
    Draw.hline(g, r.x, r.y + 10, r.w, Palette.borderSubtle)
    return r.dropTop(13)
}

class TabItem(val label: String, val icon: Icon? = null, val badge: Int = 0, val badgeSeverity: Severity = Severity.INFO, val disabledReason: String? = null)

fun Ui.subTabs(r: Rect, tabs: List<TabItem>, selected: Int, key: Any = "tabs"): Int? {
    var result: Int? = null
    var x = r.x
    Draw.hline(g, r.x, r.bottom - 1, r.w, Palette.borderSubtle)
    val remainingMin = { index: Int -> ((tabs.size - index - 1) * 36).coerceAtLeast(0) }
    tabs.forEachIndexed { i, t ->
        val natural = Draw.width(t.label) + 14 + (if (t.icon != null) Draw.ICON_SLOT else 0) + (if (t.badge > 0) Draw.width(t.badge.toString()) + 10 else 0)
        val maxW = (r.right - x - remainingMin(i)).coerceAtLeast(36)
        val w = natural.coerceIn(36, maxW)
        val cell = Rect(x, r.y, w, r.h)
        val usable = t.disabledReason == null
        focusable("$key:$i")
        val hover = hover("$key:$i", cell) && usable
        if (hover) cursor = Cursor.HAND
        if (i == selected) Draw.sprite(g, Sprites.TAB_ACTIVE, cell.inset(0, 0, 0, 1))
        else if (hover) Draw.fill(g, cell.inset(0, 2, 0, 1), Palette.hover)
        var tx = cell.x + 7
        t.icon?.let { tx += Draw.leadIcon(g, it, tx, cell.centerY) }
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
        if (i == selected) Draw.fill(g, Rect(cell.x, cell.bottom - 2, cell.w, 2), Palette.brass)
        controlTip("$key:$i", cell, usable, t.disabledReason, t.label.takeIf { it != label })
        focusRing("$key:$i", cell)
        if (usable && i != selected && (pressed(cell) != null || activatedByKey("$key:$i"))) { result = i; UiSound.page() }
        x += w + 1
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
    illustration?.let { Draw.sprite(g, it, Rect(r.centerX - 32, y, 64, 64)); y += 68 }
    Draw.text(g, title, r.centerX - Draw.width(title, TextStyle.HEADING) / 2, y, TextStyle.HEADING)
    y += 13
    val lines = Draw.wrap(body, kotlin.math.min(260, r.w - 20))
    lines.forEach { line -> g.drawString(Draw.font, line, r.centerX - Draw.font.width(line) / 2, y, Palette.textSecondary, false); y += Draw.LINE }
    if (action == null) return false
    val w = buttonWidth(action, actionIcon)
    return button(Rect(r.centerX - w / 2, y + 6, w, CONTROL_H), action, actionIcon, ButtonStyle.PRIMARY, key = "$key:action")
}
