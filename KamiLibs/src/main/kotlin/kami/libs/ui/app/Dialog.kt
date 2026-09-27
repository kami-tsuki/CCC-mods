package kami.libs.ui.app

import kami.libs.ui.text.tr
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.style.UiSound
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.banner
import kami.libs.ui.widget.button
import kami.libs.ui.widget.buttonWidth
import kami.libs.ui.widget.closeButton
import kami.libs.ui.widget.holdButton
import kami.libs.ui.widget.scroll
import net.minecraft.resources.ResourceLocation
import kotlin.math.max
import kotlin.math.min

enum class DialogKind { INFO, CONFIRM, DESTRUCTIVE }

class DialogScope(val dialog: Dialog, val body: Rect, val footer: Rect) {
    var used = 0
    val step get() = dialog.step
    val stale get() = dialog.staleReason
    fun close() = dialog.close()
    fun next() { dialog.step = min(dialog.steps.lastIndex.coerceAtLeast(0), dialog.step + 1); UiSound.page() }
    fun back() { dialog.step = max(0, dialog.step - 1); UiSound.page() }
}

class Dialog(
    val title: String,
    val subtitle: String? = null,
    val icon: Icon? = null,
    val kind: DialogKind = DialogKind.CONFIRM,
    val width: Int = 300,
    val steps: List<String> = emptyList(),
    val illustration: ResourceLocation? = null,
    val stale: () -> String? = { null },
    val content: Ui.(DialogScope) -> Unit
) {
    var step = 0
    var open = true
        private set
    var staleReason: String? = null
        private set
    private var bodyHeight = 60

    fun close() { open = false }

    fun draw(ui: Ui, screen: Rect) {
        staleReason = stale()
        val headerH = if (subtitle != null) 30 else 20
        val stepsH = if (steps.size > 1) 16 else 0
        val hazardH = if (kind == DialogKind.DESTRUCTIVE) 3 else 0
        val footerH = CONTROL_H + 10
        val staleH = if (staleReason != null) 26 else 0
        val maxBody = (screen.h - 40 - headerH - stepsH - hazardH - footerH - staleH).coerceAtLeast(48)
        val w = min(width, screen.w - 16)
        val h = hazardH + headerH + stepsH + staleH + min(bodyHeight, maxBody) + footerH + 12
        val box = screen.centered(w, h)
        ui.block(screen)
        Draw.fill(ui.g, screen, Palette.backdrop)
        Draw.shadow(ui.g, box, 2)
        Draw.sprite(ui.g, if (kind == DialogKind.DESTRUCTIVE) Sprites.MODAL_DANGER else Sprites.MODAL, box)
        var y = box.y + 1
        if (hazardH > 0) { Draw.sprite(ui.g, Sprites.HAZARD, Rect(box.x + 1, y, box.w - 2, hazardH)); y += hazardH }
        var tx = box.x + 8
        icon?.let { tx += Draw.leadIcon(ui.g, it, tx, y + 10) }
        Draw.text(ui.g, Draw.fit(title, box.right - tx - 24), tx, y + 7, TextStyle.HEADING, if (kind == DialogKind.DESTRUCTIVE) Palette.danger else Palette.text)
        subtitle?.let { Draw.text(ui.g, Draw.fit(it, box.right - tx - 10), tx, y + 18, Palette.textSecondary) }
        if (ui.closeButton(Rect(box.right - 19, y + 2, 16, 16), "dialog-close")) close()
        y += headerH
        Draw.hline(ui.g, box.x + 1, y - 1, box.w - 2, Palette.borderSubtle)
        if (stepsH > 0) {
            val cells = Rect(box.x + 8, y + 3, box.w - 16, 10).columns(steps.size, 4)
            steps.forEachIndexed { i, label ->
                val c = cells[i]
                val color = when {
                    i < step -> Palette.brass
                    i == step -> Palette.text
                    else -> Palette.textMuted
                }
                Draw.fill(ui.g, Rect(c.x, c.bottom - 1, c.w, 2), if (i <= step) Palette.brass else Palette.border)
                Draw.text(ui.g, Draw.fit("${i + 1} $label", c.w), c.x, c.y - 1, color)
            }
            y += stepsH
        }
        staleReason?.let {
            ui.banner(Rect(box.x + 8, y + 2, box.w - 16, 22), Severity.WARNING, tr("kami_libs.dialog.stale"), it, key = "stale")
            y += staleH
        }
        val bodyArea = Rect(box.x + 8, y + 6, box.w - 16, min(bodyHeight, maxBody))
        val footer = Rect(box.x + 8, box.bottom - footerH, box.w - 16, CONTROL_H)
        val scope = DialogScope(this, bodyArea, footer)
        ui.scroll("dialog-body", bodyArea, bodyHeight) { area ->
            val inner = DialogScope(this, area.withHeight(max(area.h, bodyHeight)), footer)
            ui.content(inner)
            scope.used = inner.used
        }
        if (scope.used > 0) bodyHeight = scope.used
        ui.onEscape(40) { close() }
    }
}

fun Ui.dialogButtons(scope: DialogScope, primary: String, enabled: Boolean = true, disabledReason: String? = null, cancel: String = tr("kami_libs.common.cancel"), hold: Boolean = false, primaryStyle: ButtonStyle = ButtonStyle.PRIMARY, onPrimary: () -> Unit) {
    overlay {
        val f = scope.footer
        val usable = enabled && scope.stale == null
        val reason = scope.stale ?: disabledReason
        val pw = max(110, buttonWidth(primary))
        val primaryRect = Rect(f.right - pw, f.y, pw, CONTROL_H)
        val cw = buttonWidth(cancel)
        if (button(Rect(primaryRect.x - cw - 6, f.y, cw, CONTROL_H), cancel, key = "dialog-cancel")) scope.close()
        val fired = if (hold) holdButton(primaryRect, primary, enabled = usable, disabledReason = reason, key = "dialog-hold")
        else button(primaryRect, primary, style = primaryStyle, enabled = usable, disabledReason = reason, key = "dialog-primary")
        if (fired) onPrimary()
    }
}

fun Ui.wizardButtons(scope: DialogScope, finish: String, canNext: Boolean = true, nextReason: String? = null, hold: Boolean = false, onFinish: () -> Unit) {
    overlay {
        val last = scope.step >= scope.dialog.steps.lastIndex
        val f = scope.footer
        val left = tr(if (scope.step > 0) "kami_libs.common.back" else "kami_libs.common.cancel")
        if (button(Rect(f.x, f.y, max(70, buttonWidth(left)), CONTROL_H), left, key = if (scope.step > 0) "wizard-back" else "wizard-cancel")) {
            if (scope.step > 0) scope.back() else scope.close()
        }
        if (!last) {
            val next = tr("kami_libs.common.next")
            val nw = max(90, buttonWidth(next))
            if (button(Rect(f.right - nw, f.y, nw, CONTROL_H), next, style = ButtonStyle.PRIMARY, enabled = canNext, disabledReason = nextReason, key = "wizard-next")) scope.next()
            return@overlay
        }
        val w = max(120, buttonWidth(finish))
        val r = Rect(f.right - w, f.y, w, CONTROL_H)
        val usable = canNext && scope.stale == null
        val fired = if (hold) holdButton(r, finish, enabled = usable, disabledReason = scope.stale ?: nextReason, key = "wizard-hold")
        else button(r, finish, style = ButtonStyle.PRIMARY, enabled = usable, disabledReason = scope.stale ?: nextReason, key = "wizard-finish")
        if (fired) onFinish()
    }
}
