package kami.claims.client.app

import kami.libs.ui.text.tr
import kami.claims.client.store.ClaimsStore
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.confirmDialog
import kami.libs.ui.app.dialogButtons
import kami.libs.ui.app.numberDialogBody
import kami.libs.ui.core.Rect
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.widget.NumberState
import kami.libs.ui.widget.property

object Dialogs {
    fun confirm(
        app: ClaimsApp, title: String, subtitle: String?, icon: Icon, items: List<Consequence>, primary: String, name: String, args: Array<String>,
        danger: Boolean = false, hold: Boolean = false, typed: String? = null, stale: () -> String? = { null }, after: () -> Unit = {}
    ) = confirmDialog(app::open, title, subtitle, icon, items, primary, danger, hold, typed, stale = stale) {
        ClaimsStore.send(name, *args, key = name)
        after()
    }

    fun money(app: ClaimsApp, deposit: Boolean, suggested: Long = 10) {
        val amount = NumberState(suggested.coerceAtLeast(1))
        app.open(Dialog(tr(if (deposit) "kami_claims.money.deposit" else "kami_claims.money.withdraw"), tr("kami_claims.kpi.treasury"), if (deposit) Icons.DEPOSIT else Icons.WITHDRAW) { s ->
            val snap = ClaimsStore.snap ?: return@Dialog
            val info = snap.info ?: return@Dialog
            val max = if (deposit) snap.funds else info.treasury
            var y = s.body.y
            property(Rect(s.body.x, y, s.body.w, 11), tr("kami_claims.kpi.treasury"), Format.money(info.treasury), Palette.money); y += 12
            property(Rect(s.body.x, y, s.body.w, 11), tr("kami_claims.kpi.funds"), Format.money(snap.funds), Palette.money); y += 16
            val spend = info.upkeep + info.jobs - info.income
            val help = when {
                !deposit -> tr("kami_claims.money.withdraw.desc")
                spend > 0 -> tr("kami_claims.money.deposit.desc_runway", Format.days((info.treasury + amount.value) / spend))
                else -> tr("kami_claims.money.deposit.desc")
            }
            y += numberDialogBody(s, y, amount, tr("kami_claims.money.amount"), 1, max.coerceAtLeast(1), unit = "◎", help = help)
            s.used = y - s.body.y
            val valid = amount.text.error == null && amount.value in 1..max
            val reason = if (max <= 0) tr(if (deposit) "kami_claims.money.disabled.no_coins" else "kami_claims.money.disabled.empty") else tr("kami_claims.money.disabled.range", Format.number(max))
            dialogButtons(s, tr(if (deposit) "kami_claims.money.deposit.action" else "kami_claims.money.withdraw.action", Format.money(amount.value)), valid, reason) {
                ClaimsStore.send(if (deposit) "deposit" else "withdraw", amount.value.toString())
                s.close()
            }
        })
    }
}
