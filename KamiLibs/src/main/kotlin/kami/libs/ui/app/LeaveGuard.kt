package kami.libs.ui.app

import kami.libs.ui.core.Rect
import kami.libs.ui.style.Icons
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.button
import kami.libs.ui.widget.buttonWidth

private const val GUARD_W = 300
private const val GUARD_GAP = 4
private const val MIN_ACTION_W = 110

class LeaveGuard(private val ask: (save: (() -> Unit)?, saveReason: String?, discard: () -> Unit) -> Unit, private val go: (Route) -> Unit) {
    private var cleared = false

    fun allow(next: Route, dirty: Boolean, save: (() -> Unit)? = null, saveReason: String? = null, discard: () -> Unit): Boolean {
        if (cleared) { cleared = false; return true }
        if (!dirty) return true
        fun proceed(run: () -> Unit): () -> Unit = { run(); cleared = true; try { go(next) } finally { cleared = false } }
        ask(save?.let(::proceed), saveReason, proceed(discard))
        return false
    }
}

fun leaveGuard(app: KamiApp) = LeaveGuard({ save, reason, discard -> app.open(leaveDialog(save, reason, discard)) }, { if (it == Route.CLOSE) app.closeScreen() else app.resume() })

fun leaveDialog(save: (() -> Unit)?, saveReason: String?, discard: () -> Unit) = Dialog(tr("kami_libs.leave.title"), tr("kami_libs.leave.desc"), Icons.SAVE, width = GUARD_W) { s ->
    s.used = GUARD_GAP
    overlay {
        var x = s.footer.right
        if (save != null || saveReason != null) {
            val label = tr("kami_libs.leave.save")
            val w = buttonWidth(label, Icons.SAVE).coerceAtLeast(MIN_ACTION_W)
            x -= w
            if (button(Rect(x, s.footer.y, w, CONTROL_H), label, Icons.SAVE, ButtonStyle.PRIMARY, save != null, saveReason, key = "leave-save") && save != null) { s.close(); save() }
            x -= GUARD_GAP
        }
        val discardLabel = tr("kami_libs.common.discard")
        val dw = buttonWidth(discardLabel, Icons.UNDO)
        x -= dw
        if (button(Rect(x, s.footer.y, dw, CONTROL_H), discardLabel, Icons.UNDO, ButtonStyle.DANGER, key = "leave-discard")) { s.close(); discard() }
        val stay = tr("kami_libs.leave.stay")
        if (button(Rect(s.footer.x, s.footer.y, buttonWidth(stay), CONTROL_H), stay, key = "leave-stay")) s.close()
    }
}
