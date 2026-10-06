package kami.economy.client.ui

import kami.economy.economy.TradeFlag
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.widget.statusPill

internal fun flagsText(csv: String): String = TradeFlag.parse(csv).joinToString(", ") { tr("kami_economy.flag.${it.name.lowercase()}") }

internal fun statusTip(csv: String, reason: String) = tr("kami_economy.status.tip", flagsText(csv), reason)

internal fun frozenFor(action: String, csv: String, reason: String): String? =
    tr("kami_economy.status.frozen", reason).takeIf { TradeFlag.blocks(action, TradeFlag.parse(csv)) }

internal fun Ui.tradeLock(c: Rect, csv: String, reason: String, key: String): Int {
    if (csv.isEmpty()) return 0
    val label = tr("kami_economy.status.pill")
    val w = Draw.width(label) + 16
    statusPill(c.right - w, c.centerY - 6, label, Severity.WARNING, statusTip(csv, reason), key)
    return w + 4
}
