package kami.essentials.skills

import kami.libs.ui.hud.BarData
import kami.libs.ui.style.Format
import kami.libs.ui.text.tr

object SkillClient {
    @Volatile private var values: Map<String, Double> = emptyMap()
    @Volatile private var view: SkillView? = null

    fun update(view: SkillView?) {
        this.view = view
        values = view?.let { SkillTree.values(it.nodes) } ?: emptyMap()
    }

    fun value(key: String) = Effects.capped(key, values[key] ?: 0.0)

    fun bar(): BarData? {
        val v = view ?: return null
        val span = v.nextXp - v.levelXp
        val maxed = v.level >= v.maxLevel || span <= 0
        val title = v.cls?.let { tr("kami_libs.skill.class.$it") } ?: tr("kami_essentials.hud.xp")
        val gated = !maxed && v.xp >= v.nextXp && v.active < v.nextGate
        val hint = if (gated) tr("kami_essentials.skill.gate.left", Format.duration((v.nextGate - v.active) * 1000)) else ""
        return BarData(title, v.level, if (maxed) 1f else ((v.xp - v.levelXp).toFloat() / span).coerceIn(0f, 1f), v.xp - v.levelXp, span, maxed, hint)
    }
}
