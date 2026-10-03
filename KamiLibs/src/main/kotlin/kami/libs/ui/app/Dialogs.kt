package kami.libs.ui.app

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.NumberState
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.button
import kami.libs.ui.widget.fieldHelp
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.numberField
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

fun confirmDialog(
    open: (Dialog) -> Unit, title: String, subtitle: String?, icon: Icon, items: List<Consequence>, primary: String,
    danger: Boolean = false, hold: Boolean = false, typed: String? = null, width: Int = 320, stale: () -> String? = { null }, onConfirm: () -> Unit
) {
    val typedState = TextState()
    open(Dialog(title, subtitle, icon, if (danger) DialogKind.DESTRUCTIVE else DialogKind.CONFIRM, width, stale = stale) { s ->
        var y = s.body.y
        y += consequences(s.body.x, y, s.body.w, items) + 4
        if (typed != null) {
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_libs.dialog.type_to_confirm", typed))
            y += 11
            textField(Rect(s.body.x, y, s.body.w, CONTROL_H), typedState, typed, key = "typed-confirm", autoFocus = true)
            y += CONTROL_H + 4
        }
        s.used = y - s.body.y
        val ready = typed == null || typedState.text.equals(typed, true)
        dialogButtons(s, primary, ready, tr("kami_libs.dialog.type_required", typed ?: ""), hold = hold, primaryStyle = if (danger) ButtonStyle.DANGER else ButtonStyle.PRIMARY) {
            onConfirm()
            s.close()
        }
    })
}

fun Ui.numberDialogBody(s: DialogScope, y: Int, state: NumberState, label: String, min: Long, max: Long, unit: String? = null, quick: List<Long> = listOf(10, 100), help: String? = null, key: String = "amount"): Int {
    var cy = y
    fieldLabel(Rect(s.body.x, cy, s.body.w, 9), label)
    cy += 11
    numberField(Rect(s.body.x, cy, s.body.w - 96, CONTROL_H), state, min, max.coerceAtLeast(min), unit = unit, key = key)
    val cells = Rect(s.body.right - 92, cy, 92, CONTROL_H).columns(quick.size + 1, 2)
    quick.forEachIndexed { i, v -> if (button(cells[i], Format.number(v), key = "$key-q$v")) state.commit(v.coerceAtMost(max)) }
    if (button(cells[quick.size], tr("kami_libs.field.max"), key = "$key-qmax")) state.commit(max.coerceAtLeast(min))
    cy += CONTROL_H + 2
    fieldHelp(Rect(s.body.x, cy, s.body.w, 9), state.text, help)
    cy += 14
    return cy - y
}

enum class ReceiptKind { LINE, CHARGE, TOTAL, NOTE }

class ReceiptLine(val label: String, val value: String, val kind: ReceiptKind = ReceiptKind.LINE, val tip: String? = null)

fun receiptDialog(
    open: (Dialog) -> Unit, title: String, icon: Icon, lines: List<ReceiptLine>, primary: String,
    warning: String? = null, stale: () -> String? = { null }, onConfirm: () -> Unit
) {
    open(Dialog(title, null, icon, DialogKind.CONFIRM, 320, stale = stale) { s ->
        val b = s.body
        var y = b.y
        lines.forEachIndexed { i, l ->
            if (l.kind == ReceiptKind.NOTE) {
                y += Draw.paragraph(g, l.label, b.x, y, b.w, Palette.textMuted) + 3
                return@forEachIndexed
            }
            val total = l.kind == ReceiptKind.TOTAL
            val style = if (total) TextStyle.HEADING else TextStyle.BODY
            val color = when (l.kind) {
                ReceiptKind.CHARGE -> Palette.textMuted
                ReceiptKind.TOTAL -> Palette.text
                else -> Palette.textSecondary
            }
            if (total) {
                Draw.hline(g, b.x, y, b.w, Palette.borderSubtle)
                y += 4
            }
            val rowH = if (total) 12 else 11
            val value = Draw.width(l.value, style)
            Draw.text(g, Draw.fit(l.label, b.w - value - 8, style), b.x, y, style, color)
            Draw.textRight(g, l.value, b.right, y, Palette.text, style)
            tooltip("receipt:$i", Rect(b.x, y, b.w, rowH), l.tip)
            y += rowH
        }
        y += 4
        warning?.let { y += consequences(b.x, y, b.w, listOf(Consequence(it, Severity.WARNING))) + 4 }
        s.used = y - b.y
        dialogButtons(s, primary) {
            onConfirm()
            s.close()
        }
    })
}
