package kami.claims.client.app

import kami.claims.client.store.ClaimsStore
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.DialogScope
import kami.libs.ui.app.dialogButtons
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.NumberState
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.button
import kami.libs.ui.widget.callout
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
        Draw.icon(g, bullet, x, cy - 3, 12)
        cy += Draw.paragraph(g, c.text, x + 16, cy, w - 16, color) + 3
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
                fieldLabel(Rect(s.body.x, y, s.body.w, 9), "Type $typed")
                y += 11
                textField(Rect(s.body.x, y, s.body.w, CONTROL_H), typedState, typed, key = "typed-confirm", autoFocus = true)
                y += CONTROL_H + 4
            }
            s.used = y - s.body.y
            val ready = typed == null || typedState.text.equals(typed, true)
            dialogButtons(s, primary, ready, "Type required", hold = hold, primaryStyle = if (danger) kami.libs.ui.widget.ButtonStyle.DANGER else kami.libs.ui.widget.ButtonStyle.PRIMARY) {
                ClaimsStore.send(name, *args, key = name)
                after()
                s.close()
            }
        })
    }

    fun money(app: ClaimsApp, deposit: Boolean, suggested: Long = 10) {
        val amount = NumberState(suggested.coerceAtLeast(1))
        app.open(Dialog(if (deposit) "Deposit" else "Withdraw", "Treasury", if (deposit) Icons.DEPOSIT else Icons.WITHDRAW) { s ->
            val snap = ClaimsStore.snap ?: return@Dialog
            val info = snap.info ?: return@Dialog
            val max = if (deposit) snap.funds else info.treasury
            var y = s.body.y
            property(Rect(s.body.x, y, s.body.w, 11), "Treasury", Format.money(info.treasury), Palette.money); y += 12
            property(Rect(s.body.x, y, s.body.w, 11), "You carry", Format.money(snap.funds), Palette.money); y += 16
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), "Amount"); y += 11
            numberField(Rect(s.body.x, y, s.body.w - 96, CONTROL_H), amount, 1, max.coerceAtLeast(1), unit = "◎", key = "amount")
            val quick = Rect(s.body.right - 92, y, 92, CONTROL_H).columns(3, 2)
            listOf(10L, 100L).forEachIndexed { i, v -> if (button(quick[i], v.toString(), key = "q$v")) amount.commit(v.coerceAtMost(max)) }
            if (button(quick[2], "Max", key = "qmax")) amount.commit(max.coerceAtLeast(1))
            y += CONTROL_H + 2
            val runway = if (deposit && info.upkeep + info.jobs > info.income) " Runway ${(info.treasury + amount.value) / (info.upkeep + info.jobs - info.income)}d." else ""
            fieldHelp(Rect(s.body.x, y, s.body.w, 9), amount.text, if (deposit) "Source: bank then inventory.$runway" else "Logged in treasury ledger.")
            y += 14
            if (!deposit) y += callout(Rect(s.body.x, y, s.body.w, 0), Severity.INFO, "Destination: bank or inventory.") + 4
            s.used = y - s.body.y
            val valid = amount.text.error == null && amount.value in 1..max
            dialogButtons(s, if (deposit) "Deposit ${Format.money(amount.value)}" else "Withdraw ${Format.money(amount.value)}", valid, if (max <= 0) (if (deposit) "No coins available" else "Treasury is empty") else "Use 1-${Format.number(max)}") {
                ClaimsStore.send(if (deposit) "deposit" else "withdraw", amount.value.toString())
                s.close()
            }
        })
    }

    fun heading(ui: Ui, s: DialogScope, y: Int, text: String, color: Int = Palette.text): Int {
        Draw.text(ui.g, text, s.body.x, y, TextStyle.HEADING, color)
        return 12
    }
}
