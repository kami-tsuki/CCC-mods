package kami.libs.ui.press

import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Key
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Sprites
import kami.libs.ui.widget.SCROLL_GUTTER
import kami.libs.ui.widget.ScrollState
import kami.libs.ui.widget.scroll
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TextAreaState(initial: String = "", limit: Int = 5000) {
    val buffer = EditBuffer(initial, limit)
    val text get() = buffer.text
    internal var goalX = -1
    internal var follow = true
    internal var clicks = 0
    internal var lastClickAt = 0L
    internal var lastClickPos = -1
    internal var dragging = false
    internal var moved = 0L
    internal var seenCursor = -1
    internal var seenVersion = -1
    internal val memos = arrayOf(Memo(), Memo())

    fun set(value: String) {
        buffer.set(value)
        follow = true
    }
}

class AreaResult(val changed: Boolean, val blurred: Boolean)

internal class AreaLayout(val width: Int, val segs: List<Seg>, val prefix: IntArray, val runs: List<Run>?, val chips: Map<Int, ChipSpec>)

private const val DOUBLE_CLICK_MS = 350L
private const val CHIP_PAD = 6
private const val DRAG_SCROLL = 4.0
private val charWidths = HashMap<Int, Int>()

internal fun clearCharWidths() = charWidths.clear()

private fun charWidth(cp: Int) = charWidths.getOrPut(cp) { Draw.font.width(String(Character.toChars(cp))) }

private val EditMeasure = TextMeasure { text, _ -> Draw.font.width(text) }

internal fun buildLayout(b: EditBuffer, width: Int, styled: Boolean, data: NewsData): AreaLayout {
    val text = b.text
    val runs = if (styled) MarkupStyler.runs(text) else null
    val chips = HashMap<Int, ChipSpec>()
    val w = IntArray(text.length) {
        if (text[it] == '\n' || (it > 0 && text[it].isLowSurrogate() && text[it - 1].isHighSurrogate())) 0 else charWidth(text.codePointAt(it))
    }
    if (runs != null) {
        runs.filter { it.bold }.forEach { r -> for (i in r.start until r.end) if (w[i] > 0) w[i]++ }
        val named = Newsprint.named(data)
        for (a in b.atoms) {
            val spec = Chips.spec(text.substring(a.start, a.end), named) ?: continue
            for (i in a.start until a.end) w[i] = 0
            w[a.start] = Chips.width(spec, EditMeasure, CHIP_PAD / 2, false)
            chips[a.start] = spec
        }
    }
    val prefix = IntArray(text.length + 1)
    for (i in text.indices) prefix[i + 1] = prefix[i] + w[i]
    return AreaLayout(width, Wrap.lines(text, width, prefix, b.atoms), prefix, runs, chips)
}

private fun clipboard(): String = Minecraft.getInstance()?.keyboardHandler?.clipboard.orEmpty()

private fun clipboard(value: String) {
    Minecraft.getInstance()?.keyboardHandler?.clipboard = value
}

private fun firstRun(runs: List<Run>, pos: Int): Int {
    var lo = 0
    var hi = runs.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (runs[mid].end > pos) hi = mid else lo = mid + 1
    }
    return lo
}

private fun runColor(run: Run, enabled: Boolean): Int {
    if (!enabled) return Palette.textDisabled
    val dye = run.dye?.takeIf { !it.free }
    return when {
        run.dim -> if (dye != null) Palette.alpha(PressColors.rgb(dye), 0xB0) else Palette.textMuted
        dye != null -> PressColors.argb(dye)
        run.flags and MarkupStyler.HEAD != 0 -> Palette.brass
        run.flags and MarkupStyler.QUOTE != 0 -> Palette.textSecondary
        else -> Palette.text
    }
}

fun Ui.textArea(
    r: Rect, state: TextAreaState, placeholder: String = "", enabled: Boolean = true, readOnly: Boolean = false, key: Any = "area",
    styled: Boolean = false, paste: (String) -> String = { it }, data: NewsData = NoNewsData, shortcut: (Key) -> Boolean = { false }
): AreaResult {
    val id = focusable(key)
    val b = state.buffer
    b.atomic = styled
    val hover = hover(key, r)
    if (enabled && hover) cursor = Cursor.TEXT
    val wasFocused = focus == id
    val startVersion = b.version
    if (focus == id && input.presses.any { !it.consumed && !r.contains(it.x, it.y) }) focus = null
    val inner = r.inset(4, 3)
    val line = Draw.LINE

    fun layoutAt(slot: Int, w: Int) = state.memos[slot].of(b.version, w, styled, b.atomic, data) { buildLayout(b, w, styled, data) }

    fun lay(): AreaLayout {
        val full = layoutAt(0, inner.w)
        return if (full.segs.size * line + 2 > inner.h) layoutAt(1, inner.w - SCROLL_GUTTER) else full
    }

    val s = remember("scroll:$key") { ScrollState() }
    val focused = focus == id && enabled

    fun vertical(delta: Int, select: Boolean) {
        val l = lay()
        if (state.goalX < 0) state.goalX = Wrap.xOf(l.prefix, l.segs[Wrap.lineOf(l.segs, b.cursor)], b.cursor)
        b.move(Wrap.vertical(l.segs, l.prefix, b.cursor, delta, state.goalX), select, delta > 0)
    }

    fun edge(end: Boolean, select: Boolean) {
        val l = lay()
        val seg = l.segs[Wrap.lineOf(l.segs, b.cursor)]
        b.move(if (end) Wrap.lastPos(seg) else seg.start, select, end)
    }

    fun handle(k: Key): Boolean {
        val ctrl = k.ctrl
        val shift = k.shift
        val page = max(1, inner.h / line - 1)
        var vert = false
        val handled = if (!readOnly && shortcut(k)) true else when (k.code) {
            GLFW.GLFW_KEY_BACKSPACE -> { if (!readOnly) b.delete(false, ctrl); true }
            GLFW.GLFW_KEY_DELETE -> { if (!readOnly) b.delete(true, ctrl); true }
            GLFW.GLFW_KEY_LEFT -> { b.step(-1, shift, ctrl); true }
            GLFW.GLFW_KEY_RIGHT -> { b.step(1, shift, ctrl); true }
            GLFW.GLFW_KEY_UP -> { vert = true; vertical(-1, shift); true }
            GLFW.GLFW_KEY_DOWN -> { vert = true; vertical(1, shift); true }
            GLFW.GLFW_KEY_PAGE_UP -> { vert = true; vertical(-page, shift); true }
            GLFW.GLFW_KEY_PAGE_DOWN -> { vert = true; vertical(page, shift); true }
            GLFW.GLFW_KEY_HOME -> { if (ctrl) b.move(0, shift) else edge(false, shift); true }
            GLFW.GLFW_KEY_END -> { if (ctrl) b.move(b.text.length, shift) else edge(true, shift); true }
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { if (!readOnly) b.enter(); true }
            GLFW.GLFW_KEY_A -> ctrl && run { b.selectAll(); true }
            GLFW.GLFW_KEY_C -> ctrl && run { if (b.hasSelection) clipboard(b.selected()); true }
            GLFW.GLFW_KEY_X -> ctrl && run {
                if (b.hasSelection) {
                    clipboard(b.selected())
                    if (!readOnly) b.delete(false, false)
                }
                true
            }
            GLFW.GLFW_KEY_V -> ctrl && run { if (!readOnly) b.insert(paste(clipboard()), paste = true); true }
            GLFW.GLFW_KEY_Z -> ctrl && run { if (!readOnly) { if (shift) b.redo() else b.undo() }; true }
            GLFW.GLFW_KEY_Y -> ctrl && run { if (!readOnly) b.redo(); true }
            else -> false
        }
        if (handled) {
            if (!vert) state.goalX = -1
            state.follow = true
        }
        return handled
    }

    if (focused) {
        markTyping()
        val chars = input.chars.toString()
        if (chars.isNotEmpty() && !readOnly && b.insert(chars)) {
            state.follow = true
            state.goalX = -1
        }
        input.chars.setLength(0)
        input.keys.filter { !it.consumed }.forEach { k -> if (handle(k)) k.consumed = true }
    }

    val layout = lay()
    if (b.cursor != state.seenCursor || b.version != state.seenVersion) {
        state.seenCursor = b.cursor
        state.seenVersion = b.version
        state.moved = wallMillis
    }
    val contentH = layout.segs.size * line + 2
    if (state.follow) {
        val top = Wrap.lineOf(layout.segs, b.cursor) * line
        if (top < s.target) s.target = top.toDouble()
        if (top + line > s.target + inner.h) s.target = (top + line - inner.h).toDouble()
        state.follow = false
    }
    Draw.sprite(g, Sprites.input(focused, false, enabled, hover), r)
    scroll(key, inner, contentH) { content ->
        fun posAt(x: Int, y: Int): Int {
            val l = ((y - content.y) / line).coerceIn(0, layout.segs.lastIndex)
            return Wrap.posAt(layout.prefix, layout.segs[l], x - content.x)
        }
        val textArea = Rect(inner.x, inner.y, inner.w - if (s.max > 0) SCROLL_GUTTER else 0, inner.h)
        var fresh = false
        if (enabled) pressed(textArea)?.let { click ->
            fresh = true
            focus = id
            active = id
            val pos = posAt(click.x, click.y)
            state.clicks = if (wallMillis - state.lastClickAt < DOUBLE_CLICK_MS && abs(pos - state.lastClickPos) <= 1) state.clicks + 1 else 1
            state.lastClickAt = wallMillis
            state.lastClickPos = pos
            when {
                state.clicks == 2 -> b.selectWord(pos)
                state.clicks >= 3 -> b.selectLine(pos)
                else -> b.move(pos, input.shift)
            }
            state.dragging = true
            state.goalX = -1
        }
        if (!isDown()) state.dragging = false
        else if (!fresh && state.dragging && active == id && state.clicks == 1) posAt(mouseX, mouseY).let { b.move(it, true, it >= b.anchor) }
        val first = ((inner.y - content.y) / line).coerceAtLeast(0)
        val last = ((inner.bottom - content.y) / line).coerceAtMost(layout.segs.lastIndex)
        val shown = focus == id
        if (b.text.isEmpty()) placeholder.split('\n').forEachIndexed { i, hint -> Draw.text(g, Draw.fit(hint, textArea.w), content.x, content.y + 1 + i * line, Palette.textMuted) }
        for (i in first..last) drawLine(layout, b, layout.segs[i], content.x, content.y + i * line, enabled, shown)
        if (shown && enabled && (wallMillis - state.moved) / 500 % 2 == 0L) {
            val l = Wrap.lineOf(layout.segs, b.cursor)
            Draw.vline(g, content.x + Wrap.xOf(layout.prefix, layout.segs[l], b.cursor), content.y + l * line, line, Palette.text)
        }
    }
    if (state.dragging && active == id) {
        if (mouseY < inner.y) s.target -= DRAG_SCROLL else if (mouseY > inner.bottom) s.target += DRAG_SCROLL
    }
    if (enabled && pressed(r) != null) focus = id
    return AreaResult(b.version != startVersion, wasFocused && focus != id)
}

private fun Ui.drawLine(l: AreaLayout, b: EditBuffer, seg: Seg, x: Int, y: Int, enabled: Boolean, focused: Boolean) {
    val text = b.text
    if (b.hasSelection) {
        val from = max(seg.start, b.selStart)
        val to = min(seg.end, b.selEnd)
        val newline = !seg.soft && b.selStart <= seg.end && b.selEnd > seg.end
        if (from <= to && (from < to || newline)) {
            val x0 = x + Wrap.xOf(l.prefix, seg, from)
            val x1 = x + Wrap.xOf(l.prefix, seg, to) + if (newline) 3 else 0
            Draw.fill(g, Rect(x0, y, x1 - x0, Draw.LINE), Palette.alpha(Palette.focus, if (focused) 0x60 else 0x30))
        }
    }
    if (seg.start == seg.end) return
    val runs = l.runs
    if (runs == null) {
        Draw.text(g, text.substring(seg.start, seg.end), x, y + 1, if (enabled) Palette.text else Palette.textDisabled)
        return
    }
    var i = firstRun(runs, seg.start)
    while (i < runs.size && runs[i].start < seg.end) {
        val run = runs[i++]
        val a = max(run.start, seg.start)
        val e = min(run.end, seg.end)
        if (run.token) {
            for (atom in b.atoms) if (atom.start >= a && atom.end <= e) l.chips[atom.start]?.let { chip(x + l.prefix[atom.start] - l.prefix[seg.start], y, it, l.prefix[atom.end] - l.prefix[atom.start], enabled) }
            continue
        }
        val component = Component.literal(text.substring(a, e)).withStyle(Style.EMPTY.withBold(run.bold).withItalic(run.italic))
        g.drawString(Draw.font, component, x + l.prefix[a] - l.prefix[seg.start], y + 1, runColor(run, enabled), false)
    }
}

private fun Ui.chip(x: Int, y: Int, spec: ChipSpec, w: Int, enabled: Boolean) {
    val known = spec.known && enabled
    Draw.box(g, Rect(x, y, w, Draw.LINE), Palette.alpha(Palette.brass, if (spec.known) 0x30 else 0x14), Palette.alpha(Palette.brass, if (spec.known) 0x90 else 0x50))
    val ink = when {
        !enabled -> Palette.textDisabled
        spec.known -> Palette.text
        else -> Palette.textMuted
    }
    Newsprint.glyph(g, spec, x + CHIP_PAD / 2, y, null, ink)
    var cx = x + CHIP_PAD / 2 + Chips.glyphWidth(spec.ref)
    if (spec.label.isNotEmpty()) {
        Draw.text(g, spec.label, cx + 2, y + 1, ink)
        cx += 2 + Draw.font.width(spec.label)
    }
    if (spec.value.isNotEmpty()) Draw.text(g, spec.value, cx + 3, y + 1, if (known && !spec.valueMuted) Palette.brass else Palette.textMuted)
}
