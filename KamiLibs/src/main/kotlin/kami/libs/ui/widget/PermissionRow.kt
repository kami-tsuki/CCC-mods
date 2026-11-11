package kami.libs.ui.widget

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette

fun Ui.permissionRow(r: Rect, label: String, checked: Boolean, risk: UiRisk, description: String? = null, lockedReason: String? = null, key: Any? = null): Boolean? {
    val k = key ?: autoKey("perm:$label")
    riskDot(r.x, r.y + (r.h - RISK_DOT) / 2, risk, "$k:risk")
    val locked = lockedReason != null
    val lockW = if (locked) Draw.ICON + 2 else 0
    if (locked) Draw.tintedIcon(g, Icons.LOCK, r.right - Draw.ICON, r.y + (r.h - Draw.ICON) / 2, Draw.ICON, Palette.iconOff)
    return checkbox(Rect(r.x + RISK_DOT + 4, r.y, r.w - RISK_DOT - 4 - lockW, r.h), label, checked, !locked, lockedReason, description, k)
}
