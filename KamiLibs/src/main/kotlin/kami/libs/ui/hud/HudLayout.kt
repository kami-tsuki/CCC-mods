package kami.libs.ui.hud

import kami.libs.config.Configs
import kami.libs.config.writeAtomic
import kami.libs.log.Log
import kami.libs.ui.core.Rect
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import net.minecraft.client.gui.GuiGraphics
import kotlinx.serialization.Serializable
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Anchor(val ax: Float, val ay: Float) {
    TOP_LEFT(0f, 0f), TOP(0.5f, 0f), TOP_RIGHT(1f, 0f),
    LEFT(0f, 0.5f), CENTER(0.5f, 0.5f), RIGHT(1f, 0.5f),
    BOTTOM_LEFT(0f, 1f), BOTTOM(0.5f, 1f), BOTTOM_RIGHT(1f, 1f)
}

@Serializable
class HudPos(var anchor: Anchor = Anchor.TOP_LEFT, var dx: Int = 0, var dy: Int = 0)

class HudElement(
    val id: String, val default: HudPos, val sample: () -> String, val color: () -> Int = { Palette.text },
    val lines: (() -> List<Pair<String, Int>>)? = null, val size: (() -> Pair<Int, Int>)? = null, val draw: ((GuiGraphics, Rect) -> Unit)? = null
)

class HudOption(val label: () -> String, val choices: List<Pair<String, () -> String>>, val get: () -> String, val set: (String) -> Unit)

@Serializable
class HudData(val pos: MutableMap<String, HudPos> = mutableMapOf(), val bars: MutableMap<String, BarPrefs> = mutableMapOf())

object HudLayout {
    const val MARGIN = 4
    const val BOX_H = 15
    const val SNAP = 5

    val elements = LinkedHashMap<String, HudElement>()
    val options = ArrayList<HudOption>()
    val bars get() = data.bars
    private val json = Configs.json()
    private val file by lazy { Configs.dir("libs").resolve("hud.json") }
    private val data: HudData by lazy { runCatching { json.decodeFromString(HudData.serializer(), Files.readString(file)) }.getOrNull() ?: HudData() }

    const val LINE_H = 9

    fun sampleSize(e: HudElement): Pair<Int, Int> {
        e.size?.let { return it() }
        val rows = e.lines?.invoke() ?: return Draw.width(e.sample()) + 10 to BOX_H
        return (rows.maxOfOrNull { Draw.width(it.first) } ?: 0) + 10 to rows.size * LINE_H + 6
    }

    fun element(e: HudElement) { elements[e.id] = e }

    fun option(o: HudOption) { options += o }

    fun pos(id: String): HudPos = data.pos[id] ?: elements[id]?.default ?: HudPos()

    fun set(id: String, p: HudPos) { data.pos[id] = p }

    fun reset() { data.pos.clear(); data.bars.clear(); HudBars.reset(); save() }

    fun place(id: String, sw: Int, sh: Int, w: Int, h: Int = BOX_H) = place(pos(id), sw, sh, w, h)

    fun place(p: HudPos, sw: Int, sh: Int, w: Int, h: Int): Rect {
        val x = MARGIN + (p.anchor.ax * (sw - w - 2 * MARGIN)).roundToInt() + p.dx
        val y = MARGIN + (p.anchor.ay * (sh - h - 2 * MARGIN)).roundToInt() + p.dy
        return Rect(x.coerceIn(0, maxOf(0, sw - w)), y.coerceIn(0, maxOf(0, sh - h)), w, h)
    }

    fun anchored(r: Rect, sw: Int, sh: Int): HudPos {
        val a = Anchor.entries.first { it.ax == third(r.centerX, sw) && it.ay == third(r.y + r.h / 2, sh) }
        val base = place(HudPos(a), sw, sh, r.w, r.h)
        return HudPos(a, r.x - base.x, r.y - base.y)
    }

    fun point(a: Anchor, sw: Int, sh: Int) = Pair(MARGIN + (a.ax * (sw - 2 * MARGIN)).roundToInt(), MARGIN + (a.ay * (sh - 2 * MARGIN)).roundToInt())

    fun guides(size: Int) = listOf(MARGIN, size / 4, size / 3, size / 2, size * 2 / 3, size * 3 / 4, size - MARGIN)

    fun snap(start: Int, len: Int, lines: List<Int>): Pair<Int, Int?> {
        val best = lines.flatMap { line -> listOf(0, len / 2, len).map { off -> line to line - (start + off) } }.minByOrNull { abs(it.second) }
        return if (best != null && abs(best.second) <= SNAP) Pair(start + best.second, best.first) else Pair(start, null)
    }

    fun save() {
        runCatching { writeAtomic(file, json.encodeToString(HudData.serializer(), data)) }
            .onFailure { Log.of("libs").warn("Could not save hud.json: {}", it.message) }
    }

    private fun third(c: Int, s: Int) = when {
        c < s / 3 -> 0f
        c > s * 2 / 3 -> 1f
        else -> 0.5f
    }
}
