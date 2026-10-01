package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Dialogs
import kami.claims.client.app.OfferRows
import kami.claims.client.app.Tenure
import kami.claims.client.rankOf
import kami.claims.client.store.ClaimsStore
import kami.claims.net.Detail
import kami.claims.net.Info
import kami.claims.net.OfferLine
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

class OfferEditor(private val page: ClaimsPage) {
    private class Keys(val row: String, val rent: String)
    private class Look(val title: String, val state: String, val labels: Map<String, String>, val locks: Map<String, Lock>, val keys: Map<String, Keys>)
    private class RentField {
        val text = TextState()
        var seen = -2
    }

    private var open = false
    private val looks = Memo()
    private val hintsMemo = Memo()
    private val removeLock = Memo()

    fun close() { open = false }

    fun draw(ui: Ui, f: Flow, d: Detail, o: OfferLine, x: Int, z: Int) {
        val info = page.info ?: return
        val look = looks.of(o.levels, o.custom, x, z, Format.locale) {
            Look(
                tr("kami_claims.map.offer"), tr(if (o.custom) "kami_claims.map.offer.custom" else "kami_claims.map.offer.default"),
                OfferRows.CATS.associateWith { Tenure.category(it) }, o.levels.mapValues { Lock.level(it.value, Tenure.category(it.key)) },
                OfferRows.CATS.associateWith { Keys("offer:$x:$z:$it", "offer-rent:$x:$z:$it") }
            )
        }
        f.skip(4)
        if (ui.disclosure(f.take(14), look.title, open, look.state, "offer-fold", hairline = true)) open = !open
        if (!open) return
        val hints = hintsMemo.of(info) { OfferRows.CATS.map { info.offer.rent[it]?.toString() ?: "" } }
        val waiting = page.pending("plot_offer")
        OfferRows.CATS.forEachIndexed { i, cat ->
            val row = f.take(CONTROL_H)
            val lock = look.locks[cat]
            val keys = look.keys.getValue(cat)
            val field = ui.remember(keys.row) { RentField() }
            val external = if (o.custom) (o.rent[cat] ?: -1) else -1
            if (external != field.seen) {
                field.seen = external
                field.text.set(if (external < 0) "" else external.toString())
            }
            val on = cat in o.open
            OfferRows.draw(ui, row, cat, look.labels.getValue(cat), lock, on, !waiting, null, 56, keys.row) { box ->
                val res = ui.textField(box, field.text, hints[i], enabled = lock == null && !waiting, maxLength = 3, allow = { it.isDigit() }, key = keys.rent)
                if (res.submitted || res.blurred) {
                    val value = (field.text.text.toIntOrNull() ?: -1).coerceAtMost(info.maxRent)
                    if (value >= 0) field.text.set(value.toString())
                    if (value != field.seen) { field.seen = value; send(cat, on, value, x, z) }
                }
            }?.let { send(cat, it, field.seen, x, z) }
        }
        if (ui.button(f.take(CONTROL_H), tr("kami_claims.map.offer.reset"), Icons.UNDO, ButtonStyle.SECONDARY, o.custom, tr("kami_claims.map.offer.reset.none"), pending = page.pending("plot_offer"), key = "offer-reset")) {
            page.act("plot_offer", "reset", x.toString(), z.toString())
        }
        if (d.taken && d.owner.isNotEmpty()) removeTenant(ui, f, d, info, x, z)
    }

    private fun send(cat: String, open: Boolean, rent: Int, x: Int, z: Int) =
        page.act("plot_offer", cat, if (open) "on" else "off", rent.toString(), x.toString(), z.toString())

    private fun removeTenant(ui: Ui, f: Flow, d: Detail, info: Info, x: Int, z: Int) {
        val tenantRank = info.members.firstOrNull { it.id == d.ownerId }?.rank?.let(::rankOf)
        val lock = if (tenantRank != null && tenantRank >= ClaimsStore.rank) removeLock.of(ClaimsStore.rank, Format.locale) { Lock(tr("kami_claims.map.plot.remove.lock"), tr("kami_claims.error.lower_ranks")) } else null
        val moving = d.state == Tenure.MOVING
        if (ui.lockedButton(f.take(CONTROL_H), tr("kami_claims.map.plot.remove"), lock, Icons.REMOVE, ButtonStyle.DANGER, !moving, tr("kami_claims.error.plot_moving"), pending = page.pending("plot_remove"), key = "plot-remove")) Dialogs.removeTenant(page.app, x, z, d.owner)
    }
}
