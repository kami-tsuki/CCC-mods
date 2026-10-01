package kami.claims.client.app

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.Lock
import kami.libs.ui.widget.locked
import kami.libs.ui.widget.toggle

object OfferRows {
    val CATS = listOf("citizen", "parent", "province", "allied", "random")
    val ICONS = mapOf("citizen" to Icons.PERSON, "parent" to Icons.CROWN, "province" to Icons.AREA, "allied" to Icons.HANDSHAKE, "random" to Icons.GLOBE)

    fun draw(ui: Ui, row: Rect, cat: String, label: String, lock: Lock?, on: Boolean, editable: Boolean, reason: String?, fieldW: Int, key: String, field: (Rect) -> Unit): Boolean? {
        var changed: Boolean? = null
        val fixed = cat == "citizen"
        ui.locked(row, lock, "$key:lock") {
            val line = Row(row, 6)
            val box = line.takeFromRight(fieldW)
            val toggle = line.takeFromRight(26)
            val x = row.x + Draw.leadIcon(ui.g, ICONS.getValue(cat), row.x, row.centerY) + 2
            Draw.text(ui.g, Draw.fit(label, toggle.x - x - 4), x, row.y + 5, Palette.text)
            changed = ui.toggle(toggle, on || fixed, "", editable && !fixed && lock == null, if (fixed) tr("kami_claims.law.offer.citizen") else reason, key = "$key:on")
            field(box)
        }
        return changed
    }
}
