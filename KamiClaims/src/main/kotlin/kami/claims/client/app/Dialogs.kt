package kami.claims.client.app

import kami.libs.ui.text.tr
import kami.claims.client.store.ClaimsStore
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.confirmDialog
import kami.libs.ui.app.consequences
import kami.claims.research.Tokens
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.fieldHelp
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.textField
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

    fun rename(app: ClaimsApp) {
        val name = TextState()
        app.open(Dialog(tr("kami_claims.rename.title"), tr("kami_claims.rename.subtitle"), Icons.EDIT) { s ->
            val snap = ClaimsStore.snap ?: return@Dialog
            val min = snap.limits?.nameMin ?: 3
            val max = snap.limits?.nameMax ?: 24
            name.error = when {
                name.text.length < min -> tr("kami_claims.wizard.name.error.short", min)
                !name.text.all { it.isLetterOrDigit() || it == '_' || it == '-' } -> tr("kami_claims.wizard.name.error.chars")
                snap.countries.any { it.name.equals(name.text, true) } -> tr("kami_claims.wizard.name.error.taken")
                else -> null
            }
            val offer = tokenOffer(Tokens.RENAME)
            var y = s.body.y
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_claims.wizard.name"), "${name.text.length}/$max"); y += 11
            textField(Rect(s.body.x, y, s.body.w, CONTROL_H), name, snap.info?.name.orEmpty(), Icons.FLAG, maxLength = max, allow = { it.isLetterOrDigit() || it == '_' || it == '-' }, key = "rename-field", autoFocus = true)
            name.touched = name.text.isNotEmpty()
            y += CONTROL_H + 2
            fieldHelp(Rect(s.body.x, y, s.body.w, 9), name, tr("kami_claims.rename.desc")); y += 16
            y += consequences(s.body.x, y, s.body.w, listOf(Consequence(offer.text, offer.severity), Consequence(tr("kami_claims.rename.keeps")))) + 4
            s.used = y - s.body.y
            val reason = name.error ?: tr("kami_claims.token.unaffordable")
            dialogButtons(s, tr("kami_claims.rename.action"), name.error == null && name.text.isNotEmpty() && offer.affordable, reason) {
                ClaimsStore.send("rename", name.text, key = "rename")
                s.close()
            }
        })
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
