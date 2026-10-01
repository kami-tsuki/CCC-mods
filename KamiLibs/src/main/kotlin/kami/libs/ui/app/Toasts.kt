package kami.libs.ui.app

import kami.libs.ui.anim.Ease
import kami.libs.ui.anim.reveal
import kami.libs.ui.anim.Spring
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.style.UiSound
import kami.libs.ui.text.tr
import kami.libs.ui.widget.iconFor
import kami.libs.ui.widget.link

private const val ENTER_MS = 220
private const val LEAVE_SECONDS = 0.14f
private const val ENTER_DISTANCE = 140
private const val LEAVE_DISTANCE = 36
private const val REFLOW_STIFFNESS = 260f

class Toast(val severity: Severity, val title: String, val body: String? = null, val action: String? = null, val onAction: (() -> Unit)? = null) {
    val id = nextId++
    var shown = 0.0
    var leaving = 0f
    var dismissed = false
    val y = Spring(Float.NaN)
    val lifetime get() = when (severity) {
        Severity.DANGER -> Long.MAX_VALUE
        Severity.WARNING -> 8000L
        else -> 4500L
    }

    private companion object { var nextId = 0L }
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
        items.removeAll { it.dismissed && (ui.reduceMotion || it.leaving >= LEAVE_SECONDS) }
        var next = 0
        while (next < items.size) {
            val t = items[next++]
            val w = 190
            val bodyLines = t.body?.let { Draw.wrap(it, w - 30).take(3) } ?: emptyList()
            val h = 18 + bodyLines.size * Draw.LINE + (if (t.action != null) 11 else 0) + 4
            val slot = y.toFloat()
            if (t.y.value.isNaN() || ui.reduceMotion) t.y.value = slot
            if (t.dismissed) t.leaving += ui.dt else { y += h + 4; t.y.step(slot, ui.dt, REFLOW_STIFFNESS) }
            val enter = ui.reveal("toast-enter:${t.id}", ms = ENTER_MS)
            val leave = Ease.outCubic.at(t.leaving / LEAVE_SECONDS)
            val dx = ((1f - enter) * ENTER_DISTANCE + leave * LEAVE_DISTANCE).toInt()
            val box = Rect(area.right - w + dx, t.y.value.toInt(), w, h)
            val live = !t.dismissed
            val hover = live && ui.hovering(box)
            if (live && !hover) t.shown += ui.dt
            if (t.shown * 1000 > t.lifetime) t.dismissed = true
            ui.block(box)
            Draw.shadow(ui.g, box, 1)
            Draw.sprite(ui.g, Sprites.TOAST, box)
            Draw.vline(ui.g, box.x, box.y, box.h, t.severity.color)
            Draw.icon(ui.g, iconFor(t.severity), box.x + 5, box.y + 3)
            Draw.text(ui.g, Draw.fit(t.title, w - 44), box.x + 24, box.y + 7, TextStyle.HEADING, t.severity.color)
            bodyLines.forEachIndexed { li, line -> ui.g.drawString(Draw.font, line, box.x + 24, box.y + 18 + li * Draw.LINE, Palette.textSecondary, false) }
            if (t.lifetime != Long.MAX_VALUE) {
                val left = (1.0 - t.shown * 1000 / t.lifetime).coerceIn(0.0, 1.0)
                Draw.fill(ui.g, Rect(box.x + 1, box.bottom - 2, ((box.w - 2) * left).toInt(), 1), Palette.alpha(t.severity.color, 0x90))
            }
            val closeR = Rect(box.right - 13, box.y + 4, 9, 9)
            if (live && ui.hovering(closeR)) ui.cursor = Cursor.HAND
            Draw.text(ui.g, "×", closeR.x + 1, closeR.y, if (live && ui.hovering(closeR)) Palette.text else Palette.textMuted)
            if (live) ui.tooltip("toast-close:${t.id}", closeR, tr("kami_libs.toast.dismiss.tooltip"))
            if (live && ui.pressed(closeR) != null) t.dismissed = true
            t.action?.let { label ->
                if (live && ui.link(box.x + 24, box.y + 18 + bodyLines.size * Draw.LINE, label, key = "toast-action:${t.id}")) { t.onAction?.invoke(); t.dismissed = true }
            }
            if (live && ui.pressed(box) != null) t.dismissed = true
            Draw.veilBox(ui.g, box.inset(1), minOf(enter, 1f - leave))
        }
    }

    fun clear() = items.clear()
}
