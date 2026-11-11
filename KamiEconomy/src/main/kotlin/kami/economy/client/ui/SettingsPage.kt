package kami.economy.client.ui

import kami.economy.client.MarketPrefs
import kami.economy.economy.Notify
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Page
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Metrics
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

private const val STACK_W = 380
private const val ROWS_GAP = 4

internal class SettingsPage(private val app: MarketApp) : Page() {
    override val title get() = tr("kami_libs.common.settings")
    override val help get() = listOf(Callout("settings:notify", tr("kami_economy.settings.notify"), tr("kami_economy.help.notify.desc")))

    private fun cardH(rows: Int) = Metrics.cardHeaderH + 12 + rows * (Metrics.sectionRowH + 1 + ROWS_GAP)

    override fun draw(ui: Ui, r: Rect) {
        val notifyH = cardH(Notify.TOPICS.size)
        val stacked = r.w < STACK_W
        val flow = Flow(r, 6)
        val box = if (stacked) flow.take(notifyH) else r.top(notifyH)
        val displayBox = if (stacked) flow.take(cardH(1)) else r.top(cardH(1)).let { Rect(it.x + it.w / 2 + 5, it.y, it.w / 2 - 5, it.h) }
        val notifyBox = if (stacked) box else box.left(box.w / 2 - 5)
        val view = Flow(ui.card(displayBox, tr("kami_economy.settings.display"), Icons.SETTINGS), ROWS_GAP)
        ui.toggle(view.take(Metrics.sectionRowH + 1), MarketPrefs.prefs.showWorth, tr("kami_economy.settings.worth"), key = "worth")?.let { MarketPrefs.prefs.showWorth = it; MarketPrefs.save() }
        ui.anchor("settings:notify", notifyBox)
        val f = Flow(ui.card(notifyBox, tr("kami_economy.settings.notify"), Icons.BELL), ROWS_GAP)
        Notify.TOPICS.forEach { t ->
            ui.toggle(f.take(Metrics.sectionRowH + 1), t !in app.snap.muted, tr("kami_economy.settings.notify.$t"), key = "notify:$t")
                ?.let { app.request("notify", t, if (it) "on" else "off") }
        }
    }
}
