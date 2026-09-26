package kami.libs.ui.app

import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.style.UiSound
import kami.libs.ui.widget.iconFor
import kami.libs.ui.widget.link

class Toast(val severity: Severity, val title: String, val body: String? = null, val action: String? = null, val onAction: (() -> Unit)? = null) {
    val created = System.currentTimeMillis()
    var shown = 0L
    var dismissed = false
    val lifetime get() = when (severity) {
        Severity.DANGER -> Long.MAX_VALUE
        Severity.WARNING -> 8000L
        else -> 4500L
    }
}

class Toasts {
    private val items = ArrayList<Toast>()

    fun push(t: Toast, sound: Boolean = true) {
        items.removeAll { it.title == t.title && it.body == t.body }
        items += t
        while (items.size > 4) items.removeAt(0)
        if (sound) UiSound.of(t.severity)
    }

    fun draw(ui: Ui, area: Rect) {
        var y = area.y
        items.removeAll { it.dismissed }
        items.toList().forEachIndexed { i, t ->
            val w = 190
            val bodyLines = t.body?.let { Draw.wrap(it, w - 30).take(3) } ?: emptyList()
            val h = 18 + bodyLines.size * Draw.LINE + (if (t.action != null) 11 else 0) + 4
            val r = Rect(area.right - w, y, w, h)
            val hover = ui.hovering(r)
            if (hover) t.shown -= (ui.dt * 1000).toLong()
            t.shown += (ui.dt * 1000).toLong()
            if (t.shown > t.lifetime) t.dismissed = true
            val slide = ui.animate("toast:${t.created}", 0f, 10f, start = 200f)
            val box = r.offset(slide.toInt(), 0)
            ui.block(box)
            Draw.shadow(ui.g, box, 2)
            Draw.sprite(ui.g, Sprites.TOAST, box)
            Draw.fill(ui.g, Rect(box.x + 1, box.y + 1, 2, box.h - 2), t.severity.color)
            Draw.icon(ui.g, iconFor(t.severity), box.x + 5, box.y + 3)
            Draw.text(ui.g, Draw.fit(t.title, w - 44), box.x + 24, box.y + 7, TextStyle.HEADING, t.severity.color)
            bodyLines.forEachIndexed { li, line -> ui.g.drawString(Draw.font, line, box.x + 24, box.y + 18 + li * Draw.LINE, Palette.textSecondary, false) }
            val closeR = Rect(box.right - 13, box.y + 4, 9, 9)
            if (ui.hovering(closeR)) ui.cursor = Cursor.HAND
            Draw.text(ui.g, "×", closeR.x + 1, closeR.y, if (ui.hovering(closeR)) Palette.text else Palette.textMuted)
            if (ui.pressed(closeR) != null) t.dismissed = true
            t.action?.let { label ->
                if (ui.link(box.x + 24, box.y + 18 + bodyLines.size * Draw.LINE, label, key = "toast-action:${t.created}")) { t.onAction?.invoke(); t.dismissed = true }
            }
            if (!t.dismissed && ui.pressed(box) != null) t.dismissed = true
            y += h + 4
        }
    }

    fun clear() = items.clear()
}
