package kami.claims.client.app.personal

import kami.claims.client.ClientClaims
import kami.claims.client.WarDisplay
import kami.claims.client.hud.HudEditor
import kami.libs.ui.app.Page
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Icons
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import net.minecraft.client.Minecraft

private const val HUD_H = 100

internal class HudPage : Page() {
    override val title get() = tr("kami_claims.personal.hud")

    private fun label(d: WarDisplay) = when (d) {
        WarDisplay.NONE -> tr("kami_claims.hud.display.none")
        WarDisplay.BOSS -> tr("kami_claims.hud.display.boss")
        WarDisplay.HUD -> tr("kami_claims.hud.display.hud")
        WarDisplay.BOTH -> tr("kami_claims.hud.display.both")
    }

    override fun draw(ui: Ui, r: Rect) {
        val p = ClientClaims.prefs
        val f = Flow(ui.card(r.columns(2, 10)[0].top(HUD_H), tr("kami_claims.personal.hud"), Icons.EYE), 4)
        ui.fieldLabel(f.take(9), tr("kami_claims.hud.war_display"))
        ui.segmented(f.take(CONTROL_H), WarDisplay.entries.map { Option(it, label(it)) }, p.warDisplay, key = "war-display")?.let { p.warDisplay = it; ClientClaims.savePrefs() }
        if (ui.button(f.take(CONTROL_H), tr("kami_claims.hud.edit"), Icons.GEAR, key = "hud-edit")) Minecraft.getInstance().setScreen(HudEditor())
    }
}
