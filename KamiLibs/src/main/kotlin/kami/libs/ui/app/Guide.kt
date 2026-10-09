package kami.libs.ui.app

import kami.libs.ui.style.Format
import kami.libs.ui.text.tr
import kami.libs.ui.anim.pulse
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.button
import kami.libs.ui.widget.link
import kami.libs.ui.widget.scroll
import kotlin.math.max
import kotlin.math.min

class Callout(val anchor: String, val title: String, val text: String, val route: Route? = null)

class Tour(val steps: List<Callout>, val onFinish: () -> Unit) {
    private var index = 0
    private var dir = 1
    private var missing = 0
    var done = false
        private set

    fun draw(ui: Ui, app: KamiApp) {
        val step = steps.getOrNull(index) ?: return finish()
        step.route?.let { if (app.route.page != it.page) app.navigate(it, sound = false) }
        val target = ui.anchors[step.anchor]
        ui.block(ui.screen)
        if (target == null) {
            spotlight(ui, null)
            if (++missing > 2) {
                missing = 0
                index += dir
                if (index < 0) { index = 0; dir = 1 }
            }
            return ui.onEscape(90) { finish() }
        }
        missing = 0
        spotlight(ui, target)
        val w = 200
        val textH = Draw.paragraphHeight(step.text, w - 16)
        val h = min(26 + textH + 28, ui.screen.h - 8)
        val box = place(ui.screen, target, w, h)
        Draw.shadow(ui.g, box, 3)
        Draw.sprite(ui.g, Sprites.POPOVER, box)
        Draw.text(ui.g, "${index + 1} / ${steps.size}", box.right - 34, box.y + 7, Palette.textMuted)
        ui.fitText(box.x + 8, box.y + 7, w - 50, step.title, Palette.brass, TextStyle.HEADING)
        Draw.paragraph(ui.g, step.text, box.x + 8, box.y + 21, w - 16)
        val skip = tr("kami_libs.tour.skip")
        if (ui.link(box.x + 8, box.bottom - 18, skip, key = "tour-skip")) finish()
        if (index > 0 && ui.link(box.x + 20 + Draw.width(skip), box.bottom - 18, tr("kami_libs.common.back"), key = "tour-back")) { dir = -1; index-- }
        val last = index == steps.lastIndex
        if (ui.button(Rect(box.right - 70, box.bottom - 24, 62, 18), if (last) tr("kami_libs.common.done") else tr("kami_libs.common.next"), style = ButtonStyle.PRIMARY, key = "tour-next")) {
            dir = 1
            if (last) finish() else index++
        }
        ui.onEscape(90) { finish() }
    }

    private fun finish() {
        if (done) return
        done = true
        onFinish()
    }

    companion object {
        fun spotlight(ui: Ui, target: Rect?) {
            val dim = Palette.alpha(0, 0xB0)
            val s = ui.screen
            if (target == null) return Draw.fill(ui.g, s, dim)
            val t = target.grow(3)
            Draw.fill(ui.g, Rect(s.x, s.y, s.w, t.y - s.y), dim)
            Draw.fill(ui.g, Rect(s.x, t.bottom, s.w, s.bottom - t.bottom), dim)
            Draw.fill(ui.g, Rect(s.x, t.y, t.x - s.x, t.h), dim)
            Draw.fill(ui.g, Rect(t.right, t.y, s.right - t.right, t.h), dim)
            Draw.outline(ui.g, t, Palette.alpha(Palette.brass, (0x80 + 0x7F * ui.pulse()).toInt()))
        }

        fun place(screen: Rect, target: Rect?, w: Int, h: Int): Rect {
            if (target == null) return screen.centered(w, h)
            val below = target.bottom + 8
            val y = if (below + h < screen.bottom - 4) below else max(4, target.y - h - 8)
            val x = (target.centerX - w / 2).coerceIn(4, screen.right - w - 4)
            return Rect(x, y, w, h)
        }
    }
}

class HelpOverlay(val callouts: List<Callout>) {
    fun draw(ui: Ui, close: () -> Unit) {
        ui.block(ui.screen)
        Draw.fill(ui.g, ui.screen, Palette.alpha(0, 0x90))
        val listW = min(220, ui.screen.w / 3)
        val list = Rect(ui.screen.right - listW - 8, 8, listW, ui.screen.h - 16)
        callouts.forEachIndexed { i, c ->
            val target = ui.anchors[c.anchor] ?: return@forEachIndexed
            Draw.outline(ui.g, target.grow(2), Palette.brass)
            val marker = Rect(target.x - 2, target.y - 2, 12, 12)
            Draw.fill(ui.g, marker, Palette.brass)
            Draw.textCentered(ui.g, "${i + 1}", marker, Palette.textInverse)
        }
        Draw.sprite(ui.g, Sprites.POPOVER, list)
        Draw.text(ui.g, tr("kami_libs.guide.title").uppercase(Format.locale), list.x + 8, list.y + 8, TextStyle.TITLE, Palette.brass)
        val view = Rect(list.x + 8, list.y + 24, list.w - 16, list.h - 24 - 34)
        val total = callouts.sumOf { 17 + Draw.paragraphHeight(it.text, list.w - 28) }
        ui.scroll("help-list", view, total) { area ->
            var y = area.y
            callouts.forEachIndexed { i, c ->
                Draw.text(ui.g, "${i + 1}", area.x, y, TextStyle.HEADING, Palette.brass)
                ui.fitText(area.x + 12, y, area.w - 12, c.title, style = TextStyle.HEADING)
                y += 11
                y += Draw.paragraph(ui.g, c.text, area.x + 12, y, area.w - 12) + 6
            }
        }
        if (ui.button(Rect(list.x + 8, list.bottom - 26, list.w - 16, 18), tr("kami_libs.common.close"), style = ButtonStyle.PRIMARY, key = "help-close")) close()
        ui.onEscape(80, close)
        if (ui.input.presses.any { !list.contains(it.x, it.y) }) close()
    }
}
