package kami.claims.client.app

import kami.libs.ui.text.tr
import kami.claims.client.store.ClaimsStore
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.dialogButtons
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.NumberState
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.button
import kami.libs.ui.widget.fieldHelp
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.numberField
import kami.libs.ui.widget.property
import kami.libs.ui.widget.textField

class Consequence(val text: String, val severity: Severity = Severity.NEUTRAL)

fun Ui.consequences(x: Int, y: Int, w: Int, items: List<Consequence>): Int {
    var cy = y
    items.forEach { c ->
        val color = if (c.severity == Severity.NEUTRAL) Palette.textSecondary else c.severity.color
        val bullet = when (c.severity) {
            Severity.DANGER -> Icons.DANGER
            Severity.WARNING -> Icons.WARNING
            Severity.SUCCESS -> Icons.CHECK
            else -> Icons.CHEVRON_RIGHT
        }
        val indent = Draw.leadIcon(g, bullet, x, cy + Draw.LINE / 2 - 1) + 2
        cy += Draw.paragraph(g, c.text, x + indent, cy, w - indent, color) + 3
    }
    return cy - y
}

object Dialogs {
    fun confirm(
        app: ClaimsApp, title: String, subtitle: String?, icon: Icon, items: List<Consequence>, primary: String, name: String, args: Array<String>,
        danger: Boolean = false, hold: Boolean = false, typed: String? = null, stale: () -> String? = { null }, after: () -> Unit = {}
    ) {
        val typedState = TextState()
        app.open(Dialog(title, subtitle, icon, if (danger) DialogKind.DESTRUCTIVE else DialogKind.CONFIRM, 320, stale = stale) { s ->
            var y = s.body.y
            y += consequences(s.body.x, y, s.body.w, items) + 4
            if (typed != null) {
                fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_claims.dialog.type_to_confirm", typed))
                y += 11
                textField(Rect(s.body.x, y, s.body.w, CONTROL_H), typedState, typed, key = "typed-confirm", autoFocus = true)
                y += CONTROL_H + 4
            }
            s.used = y - s.body.y
            val ready = typed == null || typedState.text.equals(typed, true)
            dialogButtons(s, primary, ready, tr("kami_claims.dialog.type_required", typed ?: ""), hold = hold, primaryStyle = if (danger) kami.libs.ui.widget.ButtonStyle.DANGER else kami.libs.ui.widget.ButtonStyle.PRIMARY) {
                ClaimsStore.send(name, *args, key = name)
                after()
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
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_claims.money.amount")); y += 11
            numberField(Rect(s.body.x, y, s.body.w - 96, CONTROL_H), amount, 1, max.coerceAtLeast(1), unit = "◎", key = "amount")
            val quick = Rect(s.body.right - 92, y, 92, CONTROL_H).columns(3, 2)
            listOf(10L, 100L).forEachIndexed { i, v -> if (button(quick[i], Format.number(v), key = "q$v")) amount.commit(v.coerceAtMost(max)) }
            if (button(quick[2], tr("kami_libs.field.max"), key = "qmax")) amount.commit(max.coerceAtLeast(1))
            y += CONTROL_H + 2
            val spend = info.upkeep + info.jobs - info.income
            val help = when {
                !deposit -> tr("kami_claims.money.withdraw.desc")
                spend > 0 -> tr("kami_claims.money.deposit.desc_runway", Format.days((info.treasury + amount.value) / spend))
                else -> tr("kami_claims.money.deposit.desc")
            }
            fieldHelp(Rect(s.body.x, y, s.body.w, 9), amount.text, help)
            y += 14
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
