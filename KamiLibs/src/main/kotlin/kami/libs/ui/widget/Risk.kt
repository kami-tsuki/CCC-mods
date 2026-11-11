package kami.libs.ui.widget

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr

enum class UiRisk(val langKey: String, val glyph: String, private val pick: () -> Int) {
    LOW("kami_libs.risk.low", "", { Palette.success }),
    MEDIUM("kami_libs.risk.medium", "·", { Palette.info }),
    HIGH("kami_libs.risk.high", "!", { Palette.warning }),
    CRITICAL("kami_libs.risk.critical", "!!", { Palette.danger });

    val color get() = pick()
    val label get() = tr(langKey)
}

const val RISK_DOT = 10

fun Ui.riskDot(x: Int, y: Int, risk: UiRisk, key: Any? = null): Int {
    val k = key ?: autoKey("risk:${risk.name}")
    val r = Rect(x, y, RISK_DOT, RISK_DOT)
    Draw.fill(g, r, risk.color)
    Draw.outline(g, r, Palette.alpha(Palette.outline, 0x80))
    if (risk.glyph.isNotEmpty()) Draw.text(g, risk.glyph, r.x + (RISK_DOT - Draw.width(risk.glyph)) / 2, r.y + 1, Palette.outline)
    tooltip(k, r.grow(2), Tip.text(tr("kami_libs.risk.tooltip", risk.label)))
    return RISK_DOT
}
