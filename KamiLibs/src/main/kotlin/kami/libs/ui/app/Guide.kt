package kami.libs.ui.app

import kami.libs.ui.style.Format
import kami.libs.ui.text.tr
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.button
import kami.libs.ui.widget.link
import kotlin.math.max
import kotlin.math.min

class Callout(val anchor: String, val title: String, val text: String, val route: Route? = null)

class Tour(val steps: List<Callout>, val onFinish: () -> Unit) {
    var index = 0
        private set
    var done = false
        private set

    fun draw(ui: Ui, app: KamiApp) {
        val step = steps.getOrNull(index) ?: return finish()
        step.route?.let { if (app.route.page != it.page) app.navigate(it, sound = false) }
        val target = ui.anchors[step.anchor]
        ui.block(ui.screen)
        spotlight(ui, target)
        val w = 200
        val textH = Draw.paragraphHeight(step.text, w - 16)
        val h = 26 + textH + 28
        val box = place(ui.screen, target, w, h)
        Draw.shadow(ui.g, box, 3)
        Draw.sprite(ui.g, Sprites.POPOVER, box)
        Draw.text(ui.g, "${index + 1} / ${steps.size}", box.right - 34, box.y + 7, Palette.textMuted)
        Draw.text(ui.g, Draw.fit(step.title, w - 50), box.x + 8, box.y + 7, TextStyle.HEADING, Palette.brass)
        Draw.paragraph(ui.g, step.text, box.x + 8, box.y + 21, w - 16)
        if (ui.link(box.x + 8, box.bottom - 18, tr("kami_libs.tour.skip"), key = "tour-skip")) finish()
        val last = index == steps.lastIndex
        if (ui.button(Rect(box.right - 70, box.bottom - 24, 62, 18), if (last) tr("kami_libs.common.done") else tr("kami_libs.common.next"), style = ButtonStyle.PRIMARY, key = "tour-next")) {
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
        var y = list.y + 24
        callouts.forEachIndexed { i, c ->
            Draw.text(ui.g, "${i + 1}", list.x + 8, y, TextStyle.HEADING, Palette.brass)
            Draw.text(ui.g, Draw.fit(c.title, list.w - 30), list.x + 20, y, TextStyle.HEADING)
            y += 11
            y += Draw.paragraph(ui.g, c.text, list.x + 20, y, list.w - 28) + 6
        }
        if (ui.button(Rect(list.x + 8, list.bottom - 26, list.w - 16, 18), tr("kami_libs.guide.close"), style = ButtonStyle.PRIMARY, key = "help-close")) close()
        ui.onEscape(80, close)
        if (ui.input.presses.any { !list.contains(it.x, it.y) }) close()
    }
}
