package kami.claims.client.admin

import kami.claims.client.store.ClaimsStore
import kami.claims.admin.CountryDetail
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.adminAct
import kami.libs.ui.app.adminManager
import kami.libs.ui.app.adminPending
import kami.libs.ui.app.lockUnless
import kami.libs.ui.app.reasonDialog
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Icon
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.edgeButton
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.scroll

internal const val SECTION_H = 9
internal const val FIELD_GAP = 6
internal val LABELED_H get() = SECTION_H + 2 + CONTROL_H

internal fun Ui.labeled(r: Rect, label: String): Rect {
    fieldLabel(r.top(SECTION_H), label)
    return Rect(r.x, r.y + SECTION_H + 2, r.w, CONTROL_H)
}

internal class GovernanceKit(private val app: KamiApp) {
    fun lock(d: CountryDetail) = lockUnless(adminManager) ?: tr("kami_claims.admin.disbanded").takeUnless { d.row.active }

    fun send(d: CountryDetail, act: String, args: List<String>, title: String, danger: Boolean = false) =
        reasonDialog({ app.open(it) }, title, danger) { reason, _, _ -> app.adminAct(act, full(d, args), reason) }

    fun button(ui: Ui, row: Row, d: CountryDetail, label: String, icon: Icon, act: String, args: List<String>, title: String, invalid: String? = null, danger: Boolean = false) {
        val why = lock(d) ?: invalid
        val style = if (danger) ButtonStyle.DANGER else ButtonStyle.SECONDARY
        if (ui.edgeButton(row, label, icon, style, why == null, why, pending = adminPending(act, full(d, args)), key = "gov-act:$act:$label")) send(d, act, args, title, danger)
    }

    fun names(d: CountryDetail) = d.members.associate { it.uuid to it.name }

    private fun full(d: CountryDetail, args: List<String>) = listOf(d.row.id, d.rev) + args
}

internal abstract class GovernanceTab(protected val kit: GovernanceKit, private val key: String) {
    private var height = 0
    private var rev: Any? = null

    protected val limits get() = ClaimsStore.snap?.limits

    protected abstract fun revision(d: CountryDetail): Any?

    protected open fun load(d: CountryDetail) {}

    protected abstract fun lines(ui: Ui, f: Flow, d: CountryDetail)

    fun reset() {
        rev = null
    }

    fun draw(ui: Ui, r: Rect, d: CountryDetail) {
        val next = revision(d)
        if (next != rev) {
            rev = next
            load(d)
        }
        ui.scroll(key, r, height) { area ->
            val f = Flow(Rect(area.x, area.y, area.w, Int.MAX_VALUE / 2), 4)
            lines(ui, f, d)
            height = f.rest.y - area.y
        }
    }
}
