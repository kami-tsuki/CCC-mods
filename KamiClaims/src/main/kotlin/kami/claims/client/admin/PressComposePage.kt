package kami.claims.client.admin

import kami.claims.admin.NewsRequests
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.Page
import kami.libs.ui.app.adminAct
import kami.libs.ui.app.adminManager
import kami.libs.ui.app.adminPending
import kami.libs.ui.app.lockUnless
import kami.libs.ui.app.reasonDialog
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Icons
import kami.libs.ui.text.tr
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.edgeButton

internal class PressComposePage(private val app: KamiApp) : Page() {
    private val editor = PressEditor(true)

    override val title get() = tr("kami_claims.admin.press.compose")

    override fun draw(ui: Ui, r: Rect) {
        editor.draw(ui, r.dropBottom(CONTROL_H, 6), "press-compose")
        val args = listOf(editor.title.text.trim(), editor.subtitle.text.trim(), editor.body.text.trim(), editor.author.text.trim())
        val why = lockUnless(adminManager) ?: editor.problem()
        if (ui.edgeButton(r.bottom(CONTROL_H), tr("kami_claims.admin.press.add"), Icons.ADD, enabled = why == null, disabledReason = why, pending = adminPending(NewsRequests.ADD, args), key = "press-add"))
            reasonDialog({ app.open(it) }, tr("kami_claims.admin.press.add.title", args[0])) { reason, _, _ -> app.adminAct(NewsRequests.ADD, args, reason) { editor.clear() } }
    }
}
