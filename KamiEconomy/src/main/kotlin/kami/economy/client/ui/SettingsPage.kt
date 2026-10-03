package kami.economy.client.ui

import kami.economy.economy.Notify
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Page
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Icons
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

private const val NOTIFY_H = 80

internal class SettingsPage(private val app: MarketApp) : Page() {
    override val title get() = tr("kami_economy.nav.settings")
    override val help get() = listOf(Callout("settings:notify", tr("kami_economy.settings.notify"), tr("kami_economy.help.notify.desc")))

    override fun draw(ui: Ui, r: Rect) {
        val box = Flow(r.columns(2, 10)[0], 6).take(NOTIFY_H)
        ui.anchor("settings:notify", box)
        val f = Flow(ui.card(box, tr("kami_economy.settings.notify"), Icons.BELL), 4)
        Notify.TOPICS.forEach { t ->
            ui.toggle(f.take(14), t !in app.snap.muted, tr("kami_economy.settings.notify.$t"), tip = tr("kami_economy.settings.notify.$t.tip"), key = "notify:$t")
                ?.let { app.request("notify", t, if (it) "on" else "off") }
        }
    }
}
