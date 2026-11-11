package kami.libs.ui.hud

import kami.libs.ui.UiPrefs
import kami.libs.ui.core.Rect
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.util.Compact
import kotlinx.serialization.Serializable
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class BarLayout { BAR, LABEL, INSIDE }

enum class BarText { LEVEL, PERCENT, VALUES }

enum class BarStyle { SMOOTH, SEGMENTED }

enum class BarColor(val color: () -> Int) {
    BRASS({ Palette.brass }), BLUE({ Palette.info }), GREEN({ Palette.success }), AMBER({ Palette.warning }), RED({ Palette.danger })
}

enum class BarWidth(val px: Int) { S(80), M(120), L(160), XL(200) }

enum class BarThickness(val px: Int) { THIN(3), NORMAL(5), THICK(8) }

@Serializable
data class BarPrefs(
    var shown: Boolean = true, var layout: BarLayout = BarLayout.LABEL, var text: BarText = BarText.PERCENT, var width: BarWidth = BarWidth.M,
    var thickness: BarThickness = BarThickness.NORMAL, var color: BarColor = BarColor.BRASS, var style: BarStyle = BarStyle.SMOOTH
)

class BarData(val title: String, val level: Int, val fraction: Float, val value: Long, val max: Long, val maxed: Boolean, val hint: String = "")

class HudBar(val id: String, val title: () -> String, val default: HudPos, val preset: BarPrefs, val data: () -> BarData?)

object HudBars {
    private const val LABEL_H = 11
    private const val INSIDE_H = 11
    private const val SEGMENTS = 10
    private const val EASE = 0.2f
    private const val SETTLED = 0.002f

    private class Eased(var level: Int, var value: Float)

    private val bars = LinkedHashMap<String, HudBar>()
    private val eased = HashMap<String, Eased>()

    fun register(bar: HudBar) {
        bars[bar.id] = bar
        HudLayout.element(HudElement(bar.id, bar.default, bar.title, size = { size(prefs(bar)) }, draw = { g, r -> draw(g, r, bar, sample(bar), false) }))
        HudLayout.option(toggle(bar))
        HudLayout.option(option(bar, "layout", BarLayout.entries, { it.layout }, { p, v -> p.layout = v }))
        HudLayout.option(option(bar, "text", BarText.entries, { it.text }, { p, v -> p.text = v }))
        HudLayout.option(option(bar, "width", BarWidth.entries, { it.width }, { p, v -> p.width = v }) { it.px.toString() })
        HudLayout.option(option(bar, "thickness", BarThickness.entries, { it.thickness }, { p, v -> p.thickness = v }) { it.px.toString() })
        HudLayout.option(option(bar, "color", BarColor.entries, { it.color }, { p, v -> p.color = v }))
        HudLayout.option(option(bar, "style", BarStyle.entries, { it.style }, { p, v -> p.style = v }))
    }

    fun visible(id: String) = bars[id]?.let { prefs(it).shown } == true

    fun render(g: GuiGraphics) {
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui || mc.screen != null || mc.player == null) return
        bars.values.forEach { bar ->
            if (!prefs(bar).shown) return@forEach
            val data = bar.data() ?: return@forEach
            val (w, h) = size(prefs(bar))
            draw(g, HudLayout.place(bar.id, g.guiWidth(), g.guiHeight(), w, h), bar, data, true)
        }
    }

    fun reset() = eased.clear()

    private fun prefs(bar: HudBar) = HudLayout.bars.getOrPut(bar.id) { bar.preset.copy() }

    private fun size(p: BarPrefs): Pair<Int, Int> {
        val bar = p.thickness.px + 2
        return p.width.px to when (p.layout) {
            BarLayout.BAR -> bar
            BarLayout.LABEL -> LABEL_H + bar
            BarLayout.INSIDE -> max(bar, INSIDE_H)
        }
    }

    private fun sample(bar: HudBar) = BarData(bar.title(), 12, 0.62f, 620, 1000, false)

    private fun label(d: BarData, p: BarPrefs): String {
        val level = tr("kami_libs.hud.bar.level", d.level)
        return when {
            d.maxed -> "$level · ${tr("kami_libs.hud.bar.max")}"
            p.text == BarText.LEVEL -> level
            p.text == BarText.PERCENT -> "$level · ${(d.fraction * 100).toInt()}%"
            d.hint.isNotEmpty() -> "$level · ${d.hint}"
            else -> "$level · ${Compact.of(d.value)} / ${Compact.of(d.max)}"
        }
    }

    private fun draw(g: GuiGraphics, r: Rect, bar: HudBar, d: BarData, live: Boolean) {
        val p = prefs(bar)
        val color = p.color.color()
        val fraction = if (live) ease(bar.id, d) else d.fraction
        val track = if (p.layout == BarLayout.LABEL) Rect(r.x, r.bottom - p.thickness.px - 2, r.w, p.thickness.px + 2) else r
        val text = label(d, p)
        if (p.layout == BarLayout.LABEL) {
            Draw.text(g, Draw.fit(d.title, r.w - Draw.width(text) - 8), r.x + 1, r.y + 1, Palette.textSecondary, true)
            Draw.text(g, text, r.right - Draw.width(text) - 1, r.y + 1, Palette.text, true)
        }
        Draw.fill(g, track, Palette.alpha(Palette.washDark, 0xB0))
        Draw.outline(g, track, Palette.alpha(Palette.mix(color, Palette.border, 0.5f), 0xE0))
        fill(g, Rect(track.x + 1, track.y + 1, track.w - 2, track.h - 2), p.style, color, fraction)
        if (p.layout == BarLayout.INSIDE) Draw.textCentered(g, text, track, Palette.text)
    }

    private fun fill(g: GuiGraphics, inner: Rect, style: BarStyle, color: Int, fraction: Float) {
        val gloss = Palette.alpha(0xFFFFFF, 0x38)
        fun span(x: Int, w: Int) {
            if (w <= 0) return
            Draw.fill(g, Rect(x, inner.y, w, inner.h), color)
            if (inner.h >= 3) Draw.fill(g, Rect(x, inner.y, w, 1), gloss)
        }
        if (style == BarStyle.SMOOTH) return span(inner.x, (inner.w * fraction).roundToInt())
        repeat(SEGMENTS) { i ->
            val x0 = inner.x + inner.w * i / SEGMENTS
            val x1 = inner.x + inner.w * (i + 1) / SEGMENTS - 1
            span(x0, ((x1 - x0) * (fraction * SEGMENTS - i).coerceIn(0f, 1f)).roundToInt())
        }
    }

    private fun ease(id: String, d: BarData): Float {
        val s = eased.getOrPut(id) { Eased(d.level, d.fraction) }
        if (s.level != d.level || UiPrefs.prefs.reduceMotion) { s.level = d.level; s.value = d.fraction }
        else s.value += (d.fraction - s.value) * EASE
        if (abs(d.fraction - s.value) < SETTLED) s.value = d.fraction
        return s.value
    }

    private fun toggle(bar: HudBar) = HudOption(
        { "${bar.title()} · ${tr("kami_libs.hud.bar.shown")}" },
        listOf("on" to { tr("kami_libs.common.on") }, "off" to { tr("kami_libs.common.off") }),
        { if (prefs(bar).shown) "on" else "off" },
        { prefs(bar).shown = it == "on"; HudLayout.save() }
    )

    private fun <E : Enum<E>> option(bar: HudBar, key: String, entries: List<E>, get: (BarPrefs) -> E, set: (BarPrefs, E) -> Unit, name: (E) -> String = { tr("kami_libs.hud.bar.$key.${it.name.lowercase()}") }) = HudOption(
        { "${bar.title()} · ${tr("kami_libs.hud.bar.$key")}" },
        entries.map { e -> e.name to { name(e) } },
        { get(prefs(bar)).name },
        { v -> entries.first { it.name == v }.let { set(prefs(bar), it); HudLayout.save() } }
    )
}
