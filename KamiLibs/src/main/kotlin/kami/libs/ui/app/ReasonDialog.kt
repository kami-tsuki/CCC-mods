package kami.libs.ui.app

import kami.libs.admin.Durations
import kami.libs.admin.Reasons
import kami.libs.ui.core.Rect
import kami.libs.ui.style.Icons
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.checkbox
import kami.libs.ui.widget.fieldHelp
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.segmented
import kami.libs.ui.widget.textField

private val PRESETS = listOf("30m", "2h", "1d", "7d", "30d", "perm")
private const val CUSTOM = "custom"

fun reasonDialog(
    open: (Dialog) -> Unit, title: String, danger: Boolean = false, withDuration: Boolean = false, withIp: Boolean = false,
    onConfirm: (reason: String, duration: String?, ip: Boolean) -> Unit
) {
    val reason = TextState()
    val custom = TextState()
    var preset = PRESETS[2]
    var ip = false
    val options = PRESETS.map { Option(it, tr("kami_libs.admin.duration.$it")) } + Option(CUSTOM, tr("kami_libs.admin.duration.custom"))
    open(Dialog(title, null, Icons.WARNING, if (danger) DialogKind.DESTRUCTIVE else DialogKind.CONFIRM, 320) { s ->
        val b = s.body
        var y = b.y
        val cleaned = Reasons.clean(reason.text)
        reason.error = tr("kami_libs.admin.reason.invalid").takeIf { cleaned == null }
        fieldLabel(Rect(b.x, y, b.w, 9), tr("kami_libs.admin.reason"), "${reason.text.trim().length}/${Reasons.MAX}")
        y += 11
        var submitted = textField(Rect(b.x, y, b.w, CONTROL_H), reason, tr("kami_libs.admin.reason.placeholder"), maxLength = Reasons.MAX, key = "reason", autoFocus = true).submitted
        y += CONTROL_H + 2
        fieldHelp(Rect(b.x, y, b.w, 9), reason, null)
        y += 12
        val duration = if (!withDuration) null else {
            fieldLabel(Rect(b.x, y, b.w, 9), tr("kami_libs.admin.duration"))
            y += 11
            segmented(Rect(b.x, y, b.w, CONTROL_H), options, preset, key = "duration")?.let { preset = it }
            y += CONTROL_H + 4
            if (preset == CUSTOM) {
                custom.error = tr("kami_libs.admin.duration.invalid").takeIf { !Durations.valid(custom.text.trim()) }
                submitted = textField(Rect(b.x, y, b.w, CONTROL_H), custom, tr("kami_libs.admin.duration.placeholder"), maxLength = 16, key = "duration-custom").submitted || submitted
                y += CONTROL_H + 2
                fieldHelp(Rect(b.x, y, b.w, 9), custom, tr("kami_libs.admin.duration.help"))
                y += 12
                custom.text.trim().takeIf { custom.error == null }
            } else preset
        }
        if (withIp) {
            checkbox(Rect(b.x, y, b.w, 12), tr("kami_libs.admin.ip"), ip, key = "ip")?.let { ip = it }
            y += 16
        }
        s.used = y - b.y
        val valid = reason.error == null && (!withDuration || duration != null)
        val confirm = {
            if (cleaned != null) {
                onConfirm(cleaned, duration, ip)
                s.close()
            }
        }
        if (submitted && valid && s.stale == null) confirm()
        dialogButtons(s, tr("kami_libs.common.confirm"), valid, reason.error ?: custom.error, primaryStyle = if (danger) ButtonStyle.DANGER else ButtonStyle.PRIMARY, onPrimary = confirm)
    })
}
