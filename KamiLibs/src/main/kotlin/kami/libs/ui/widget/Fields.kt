package kami.libs.ui.widget

import kami.libs.ui.text.tr
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Sprites
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min

class TextState(initial: String = "") {
    var text = initial
        private set
    var cursor = initial.length
    var anchor = initial.length
    var scroll = 0
    var touched = false
    var error: String? = null

    fun set(value: String) {
        text = value
        cursor = value.length
        anchor = cursor
        scroll = 0
    }

    val selection get() = min(cursor, anchor) until max(cursor, anchor)
    val hasSelection get() = cursor != anchor

    fun insert(s: String, limit: Int, allow: (Char) -> Boolean): Boolean {
        val clean = s.filter { it >= ' ' && allow(it) }
        if (clean.isEmpty() && !hasSelection) return false
        val sel = selection
        val next = text.substring(0, sel.first) + clean + text.substring(sel.last + 1)
        if (next.length > limit) return false
        text = next
        cursor = sel.first + clean.length
        anchor = cursor
        return true
    }

    fun delete(forward: Boolean, word: Boolean): Boolean {
        if (hasSelection) return insert("", Int.MAX_VALUE) { true }
        val target = when {
            forward && word -> wordEnd(cursor)
            forward -> min(text.length, cursor + 1)
            word -> wordStart(cursor)
            else -> max(0, cursor - 1)
        }
        if (target == cursor) return false
        val (a, b) = min(target, cursor) to max(target, cursor)
        text = text.removeRange(a, b)
        cursor = a
        anchor = a
        return true
    }

    fun move(to: Int, select: Boolean) {
        cursor = to.coerceIn(0, text.length)
        if (!select) anchor = cursor
    }

    fun wordStart(from: Int): Int {
        var i = from
        while (i > 0 && text[i - 1] == ' ') i--
        while (i > 0 && text[i - 1] != ' ') i--
        return i
    }

    fun wordEnd(from: Int): Int {
        var i = from
        while (i < text.length && text[i] == ' ') i++
        while (i < text.length && text[i] != ' ') i++
        return i
    }

    fun selected() = if (hasSelection) text.substring(selection.first, selection.last + 1) else ""
}

class FieldResult(val changed: Boolean, val submitted: Boolean, val blurred: Boolean)

fun Ui.textField(
    r: Rect, state: TextState, placeholder: String = "", icon: Icon? = null, enabled: Boolean = true, maxLength: Int = 64,
    allow: (Char) -> Boolean = { true }, key: Any = "field", clearable: Boolean = false, autoFocus: Boolean = false, suffix: String? = null
): FieldResult {
    val id = focusable(key)
    val hover = hover(key, r)
    if (autoFocus && remember("autofocus:$key") { booleanArrayOf(false) }.let { if (!it[0]) { it[0] = true; true } else false }) focus = id
    if (enabled && hover) cursor = Cursor.TEXT
    val wasFocused = focus == id
    if (enabled && pressed(r) != null) {
        focus = id
        state.move(state.text.length, false)
    }
    if (focus == id && input.presses.any { !it.consumed && !r.contains(it.x, it.y) }) focus = null
    val focused = focus == id && enabled
    val invalid = state.touched && state.error != null
    Draw.sprite(g, Sprites.input(focused, invalid, enabled, hover), r)
    var left = r.x + 5
    icon?.let { left = r.x + 4 + Draw.leadIcon(g, it, r.x + 4, r.centerY, Palette.textMuted) }
    val showClear = clearable && state.text.isNotEmpty() && enabled
    val suffixW = suffix?.let { Draw.width(it) + 6 } ?: 0
    val right = r.right - 5 - suffixW - (if (showClear) 14 else 0)
    val inner = Rect(left, r.y, max(4, right - left), r.h)
    var changed = false
    var submitted = false
    if (focused) {
        markTyping()
        val chars = input.chars.toString()
        if (chars.isNotEmpty() && state.insert(chars, maxLength, allow)) changed = true
        input.chars.setLength(0)
        input.keys.filter { !it.consumed }.forEach { k ->
            val handled = when (k.code) {
                GLFW.GLFW_KEY_BACKSPACE -> { changed = state.delete(false, k.ctrl) || changed; true }
                GLFW.GLFW_KEY_DELETE -> { changed = state.delete(true, k.ctrl) || changed; true }
                GLFW.GLFW_KEY_LEFT -> { state.move(if (k.ctrl) state.wordStart(state.cursor) else state.cursor - 1, k.shift); true }
                GLFW.GLFW_KEY_RIGHT -> { state.move(if (k.ctrl) state.wordEnd(state.cursor) else state.cursor + 1, k.shift); true }
                GLFW.GLFW_KEY_HOME -> { state.move(0, k.shift); true }
                GLFW.GLFW_KEY_END -> { state.move(state.text.length, k.shift); true }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { submitted = true; state.touched = true; true }
                GLFW.GLFW_KEY_A -> if (k.ctrl) { state.anchor = 0; state.cursor = state.text.length; true } else false
                GLFW.GLFW_KEY_C -> if (k.ctrl) { Minecraft.getInstance().keyboardHandler.clipboard = state.selected(); true } else false
                GLFW.GLFW_KEY_X -> if (k.ctrl) { Minecraft.getInstance().keyboardHandler.clipboard = state.selected(); changed = state.delete(false, false) || changed; true } else false
                GLFW.GLFW_KEY_V -> if (k.ctrl) { changed = state.insert(Minecraft.getInstance().keyboardHandler.clipboard, maxLength, allow) || changed; true } else false
                else -> false
            }
            if (handled) k.consumed = true
        }
    }
    val cursorX = Draw.font.width(state.text.substring(0, state.cursor))
    if (cursorX - state.scroll > inner.w - 2) state.scroll = cursorX - inner.w + 2
    if (cursorX - state.scroll < 0) state.scroll = cursorX
    clip(inner) {
        val ty = r.y + (r.h - 8) / 2
        if (state.text.isEmpty() && !focused) Draw.text(g, Draw.fit(placeholder, inner.w), inner.x, ty, Palette.textMuted)
        if (focused && state.hasSelection) {
            val a = Draw.font.width(state.text.substring(0, state.selection.first)) - state.scroll
            val b = Draw.font.width(state.text.substring(0, state.selection.last + 1)) - state.scroll
            Draw.fill(g, Rect(inner.x + a, ty - 1, b - a, 10), Palette.alpha(Palette.focus, 0x60))
        }
        Draw.text(g, state.text, inner.x - state.scroll, ty, if (enabled) Palette.text else Palette.textDisabled)
        if (focused && (wallMillis / 500) % 2 == 0L) Draw.vline(g, inner.x + cursorX - state.scroll, ty - 1, 10, Palette.text)
    }
    suffix?.let { Draw.text(g, it, r.right - suffixW, r.y + (r.h - 8) / 2, Palette.textMuted) }
    if (showClear) {
        val c = Rect(r.right - 18, r.y + (r.h - 12) / 2, 12, 12)
        if (hovering(c)) cursor = Cursor.HAND
        Draw.tintedIcon(g, Icons.CLOSE, c.x - 2, c.y - 2, 16, if (hovering(c)) Palette.text else Palette.textMuted)
        tooltip("$key:clear", c, tr("kami_libs.field.clear.tooltip"))
        if (pressed(c) != null) { state.set(""); changed = true }
    }
    if (invalid) tooltip(key, r) { Tip.disabled(state.error!!) }
    val blurred = wasFocused && focus != id
    if (blurred) state.touched = true
    return FieldResult(changed, submitted, blurred)
}

fun Ui.fieldLabel(r: Rect, label: String, trailing: String? = null) {
    Draw.text(g, label.uppercase(), r.x, r.y, Palette.textMuted)
    trailing?.let { Draw.textRight(g, it, r.right, r.y, Palette.textMuted) }
}

fun Ui.fieldHelp(r: Rect, state: TextState?, help: String?) {
    val error = state?.takeIf { it.touched }?.error
    when {
        error != null -> { Draw.icon(g, Icons.CROSS, r.x - 3, r.y - 4, 16); Draw.text(g, Draw.fit(error, r.w - 12), r.x + 12, r.y, Palette.danger) }
        help != null -> Draw.text(g, Draw.fit(help, r.w), r.x, r.y, Palette.textMuted)
    }
}

class NumberState(value: Long) {
    val text = TextState(value.toString())
    var value = value
        private set

    fun sync(external: Long) {
        if (external != value) { value = external; text.set(external.toString()) }
    }

    fun commit(v: Long) { value = v; text.set(v.toString()) }
}

fun Ui.numberField(
    r: Rect, state: NumberState, min: Long = 0, max: Long = Long.MAX_VALUE, step: Long = 1, unit: String? = null,
    enabled: Boolean = true, key: Any = "number"
): Long? {
    val buttons = (r.h - 6).coerceIn(11, 14)
    val gap = 2
    val fieldW = r.w - buttons * 2 - gap * 2
    val compact = fieldW < 64
    val by = r.y + (r.h - buttons) / 2
    val dec = Rect(r.x, by, buttons, buttons)
    val inc = Rect(r.right - buttons, by, buttons, buttons)
    val field = if (compact) r else Rect(r.x + buttons + gap, r.y, fieldW, r.h)
    var result: Long? = null
    fun snap(v: Long): Long {
        if (step <= 1) return v.coerceIn(min, max)
        val lo = -Math.floorDiv(-min, step) * step
        val hi = Math.floorDiv(max, step) * step
        if (hi < lo) return v.coerceIn(min, max)
        return (Math.floorDiv(v + step / 2, step) * step).coerceIn(lo, hi)
    }
    fun apply(v: Long) {
        val clamped = snap(v)
        state.commit(clamped)
        state.text.error = null
        result = clamped
    }
    fun stepSize() = step * when {
        input.ctrl && input.shift -> 1000
        input.ctrl -> 100
        input.shift -> 10
        else -> 1
    }
    if (!compact) {
        if (button(dec, "-", style = ButtonStyle.GHOST, enabled = enabled && state.value > min, key = "$key:dec")) apply(state.value - stepSize())
        if (button(inc, "+", style = ButtonStyle.GHOST, enabled = enabled && state.value < max, key = "$key:inc")) apply(state.value + stepSize())
    }
    val wheel = wheel(field)
    if (enabled && wheel != 0.0) apply(state.value + (if (wheel > 0) 1 else -1) * stepSize())
    val res = textField(field, state.text, "", enabled = enabled, maxLength = 16, allow = { it.isDigit() || it in ",._kKmM-" }, key = "$key:text", suffix = unit)
    val parsed = Format.parseAmount(state.text.text)
    state.text.error = when {
        parsed == null -> tr("kami_libs.field.number.error.invalid")
        parsed < min -> tr("kami_libs.field.number.error.min", Format.number(min))
        parsed > max -> tr("kami_libs.field.number.error.max", Format.number(max))
        else -> null
    }
    if ((res.submitted || res.blurred) && parsed != null && state.text.error == null && (parsed != state.value || snap(parsed) != parsed)) apply(parsed)
    tooltip("$key:tip", r) { if (enabled) Tip(null, listOf(tr("kami_libs.field.number.tooltip") to Palette.textMuted)) else null }
    return result
}

fun Ui.slider(r: Rect, value: Double, min: Double, max: Double, step: Double, enabled: Boolean = true, format: (Double) -> String = { "%.0f".format(it) }, tip: String? = null, key: Any = "slider"): Double? {
    val id = focusable(key)
    val track = Rect(r.x + 4, r.centerY - 2, r.w - 8, 4)
    val hover = hover(key, r)
    if (enabled && hover) cursor = Cursor.HAND
    if (enabled && pressed(r) != null) active = id
    val dragging = active == id && isDown()
    var result: Double? = null
    fun snap(v: Double) = (Math.round((v - min) / step) * step + min).coerceIn(min, max)
    if (dragging) {
        val f = ((mouseX - track.x).toDouble() / track.w).coerceIn(0.0, 1.0)
        val v = snap(min + f * (max - min))
        if (v != value) result = v
    }
    if (enabled && focused(key)) {
        if (input.takeKey(GLFW.GLFW_KEY_LEFT) != null) result = snap(value - step)
        if (input.takeKey(GLFW.GLFW_KEY_RIGHT) != null) result = snap(value + step)
    }
    val w = wheel(r)
    if (enabled && w != 0.0) result = snap(value + if (w > 0) step else -step)
    val shown = result ?: value
    val f = if (max > min) ((shown - min) / (max - min)).toFloat() else 0f
    Draw.sprite(g, Sprites.TRACK, track)
    Draw.tinted(g, Sprites.FILL, track.withWidth(max(2, (track.w * f).toInt())), if (enabled) Palette.brass else Palette.textDisabled)
    val knob = Rect(track.x + (track.w * f).toInt() - 3, r.centerY - 6, 6, 12)
    Draw.sprite(g, Sprites.Look.SECONDARY.of(hover, dragging, enabled), knob)
    if (dragging || hover) {
        val label = format(shown)
        val bw = Draw.width(label) + 8
        overlay { Draw.sprite(g, Sprites.TOOLTIP, Rect(knob.centerX - bw / 2, knob.y - 15, bw, 13)); Draw.text(g, label, knob.centerX - bw / 2 + 4, knob.y - 12, Palette.text) }
    }
    focusRing(key, r)
    controlTip(key, r, enabled, null, tip)
    return result
}

fun Ui.searchField(r: Rect, state: TextState, placeholder: String = tr("kami_libs.common.search"), key: Any = "search"): Boolean {
    if (input.takeKey(GLFW.GLFW_KEY_F) { it.ctrl } != null) focus = id(key)
    return textField(r, state, placeholder, Icons.SEARCH, key = key, clearable = true).changed
}
