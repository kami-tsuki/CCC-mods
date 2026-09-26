package kami.libs.ui.widget

import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.style.UiSound
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min

class Option<T>(
    val value: T, val label: String, val icon: Icon? = null, val description: String? = null,
    val color: Int? = null, val disabledReason: String? = null
)

private fun Ui.clickable(key: Any, r: Rect, enabled: Boolean): Boolean {
    focusable(key)
    val hover = hover(key, r)
    if (enabled && hover) cursor = Cursor.HAND
    val hit = enabled && (pressed(r) != null || activatedByKey(key))
    if (hit) UiSound.click()
    return hit
}

fun Ui.checkbox(r: Rect, label: String, checked: Boolean?, enabled: Boolean = true, disabledReason: String? = null, tip: String? = null, key: Any = "check:$label"): Boolean? {
    val box = Rect(r.x, r.y + (r.h - 12) / 2, 12, 12)
    val hit = clickable(key, r, enabled)
    Draw.sprite(g, when (checked) { true -> Sprites.CHECKBOX_ON; null -> Sprites.CHECKBOX_MIXED; false -> Sprites.CHECKBOX }, box)
    if (hovering(r) && enabled) Draw.outline(g, box, Palette.borderStrong)
    if (label.isNotEmpty()) Draw.text(g, Draw.fit(label, r.w - 16), r.x + 16, r.y + (r.h - 8) / 2, if (enabled) Palette.textSecondary else Palette.textDisabled)
    if (!enabled && disabledReason != null) tooltip(key, r) { Tip.disabled(disabledReason) } else tooltip(key, r, tip)
    focusRing(key, box)
    return if (hit) checked != true else null
}

fun Ui.toggle(r: Rect, on: Boolean, label: String = "", enabled: Boolean = true, disabledReason: String? = null, tip: String? = null, key: Any = "toggle:$label"): Boolean? {
    val track = Rect(r.x, r.y + (r.h - 12) / 2, 22, 12)
    val hit = clickable(key, r, enabled)
    val t = animate("toggle:$key", if (on) 1f else 0f)
    Draw.sprite(g, if (on) Sprites.TOGGLE_ON else Sprites.TOGGLE, track)
    Draw.sprite(g, Sprites.KNOB, Rect(track.x + 1 + (10 * t).toInt(), track.y + 1, 10, 10))
    if (label.isNotEmpty()) Draw.text(g, Draw.fit(label, r.w - 28), r.x + 27, r.y + (r.h - 8) / 2, if (enabled) Palette.textSecondary else Palette.textDisabled)
    if (!enabled && disabledReason != null) tooltip(key, r) { Tip.disabled(disabledReason) } else tooltip(key, r, tip)
    focusRing(key, track)
    return if (hit) !on else null
}

fun <T> Ui.radioGroup(r: Rect, options: List<Option<T>>, selected: T, enabled: Boolean = true, rowHeight: Int = 14, key: Any = "radio"): T? {
    var result: T? = null
    var y = r.y
    options.forEach { o ->
        val descH = o.description?.let { Draw.paragraphHeight(it, r.w - 16) } ?: 0
        val row = Rect(r.x, y, r.w, rowHeight + descH)
        val usable = enabled && o.disabledReason == null
        if (clickable("$key:${o.label}", row, usable)) result = o.value
        Draw.sprite(g, if (o.value == selected) Sprites.RADIO_ON else Sprites.RADIO, Rect(r.x, y + 1, 12, 12))
        var tx = r.x + 16
        o.icon?.let { Draw.icon(g, it, tx, y - 2); tx += 18 }
        Draw.text(g, o.label, tx, y + 3, if (usable) (if (o.value == selected) Palette.text else Palette.textSecondary) else Palette.textDisabled)
        o.description?.let { Draw.paragraph(g, it, r.x + 16, y + rowHeight, r.w - 16, Palette.textMuted) }
        o.disabledReason?.let { reason -> tooltip("$key:${o.label}", row) { Tip.disabled(reason) } }
        y += row.h + 4
    }
    return result
}

fun radioGroupHeight(options: List<Option<*>>, width: Int, rowHeight: Int = 14) =
    options.sumOf { rowHeight + (it.description?.let { d -> Draw.paragraphHeight(d, width - 16) } ?: 0) + 4 }

fun <T> Ui.segmented(r: Rect, options: List<Option<T>>, selected: T, enabled: Boolean = true, key: Any = "segmented"): T? {
    var result: T? = null
    val cells = r.columns(options.size, 0)
    Draw.sprite(g, Sprites.SUNKEN, r)
    options.forEachIndexed { i, o ->
        val cell = cells[i]
        val usable = enabled && o.disabledReason == null
        val chosen = o.value == selected
        val hover = hovering(cell) && usable
        if (clickable("$key:$i", cell, usable)) result = o.value
        if (chosen) Draw.sprite(g, Sprites.Look.SECONDARY.of(hover, false, true), cell.inset(1))
        else if (hover) Draw.fill(g, cell.inset(1), Palette.hover)
        val content = (if (o.icon != null) 18 else 0) + Draw.width(o.label)
        var x = cell.x + max(3, (cell.w - content) / 2)
        o.icon?.let { Draw.icon(g, it, x, cell.y + (cell.h - 16) / 2); x += 18 }
        if (o.label.isNotEmpty()) Draw.text(g, Draw.fit(o.label, cell.right - x - 3), x, cell.y + (cell.h - 8) / 2, when {
            !usable -> Palette.textDisabled
            chosen -> Palette.text
            else -> Palette.textMuted
        })
        if (chosen) Draw.fill(g, Rect(cell.x + 3, cell.bottom - 2, cell.w - 6, 1), Palette.brass)
        val tip = o.disabledReason?.let { Tip.disabled(it) } ?: o.description?.let { Tip.text(it, o.label) }
        tooltip("$key:$i", cell) { tip }
    }
    return result
}

fun <T> Ui.select(
    r: Rect, options: List<Option<T>>, selected: T?, placeholder: String = "Choose…", enabled: Boolean = true,
    disabledReason: String? = null, key: Any = "select", searchable: Boolean = options.size > 7, menuWidth: Int = r.w
): T? {
    val state = remember("open:$key") { booleanArrayOf(false) }
    val pick = remember("pick:$key") { Picked() }
    @Suppress("UNCHECKED_CAST")
    val deferred = if (pick.has) (pick.value as T).also { pick.clear() } else null
    val filter = remember("filter:$key") { TextState() }
    focusable(key)
    val hover = hover(key, r)
    if (enabled && hover) cursor = Cursor.HAND
    val current = options.firstOrNull { it.value == selected }
    Draw.sprite(g, Sprites.input(state[0], false, enabled, hover), r)
    var tx = r.x + 5
    current?.color?.let { Draw.fill(g, Rect(tx, r.centerY - 3, 6, 6), it); tx += 10 }
    current?.icon?.let { Draw.icon(g, it, tx, r.y + (r.h - 16) / 2); tx += 18 }
    Draw.text(g, Draw.fit(current?.label ?: placeholder, r.right - tx - 16), tx, r.y + (r.h - 8) / 2, if (current == null) Palette.textMuted else if (enabled) Palette.text else Palette.textDisabled)
    Draw.icon(g, Icons.CHEVRON_DOWN, r.right - 17, r.y + (r.h - 16) / 2)
    if (!enabled && disabledReason != null) tooltip(key, r) { Tip.disabled(disabledReason) } else current?.description?.let { d -> tooltip(key, r) { Tip.text(d, current.label) } }
    focusRing(key, r)
    if (enabled && (pressed(r) != null || activatedByKey(key))) {
        state[0] = !state[0]
        filter.set("")
        UiSound.click()
    }
    if (!state[0]) return deferred
    onEscape(50) { state[0] = false }
    overlay {
        val visible = options.filter { filter.text.isBlank() || it.label.contains(filter.text, true) }
        val rowH = if (visible.any { it.description != null }) 24 else 16
        val searchH = if (searchable) 22 else 0
        val height = min(visible.size * rowH + 6 + searchH, 220)
        val below = r.bottom + 2 + height <= screen.bottom - 4
        val menu = Rect(min(r.x, screen.right - menuWidth - 4), if (below) r.bottom + 2 else r.y - 2 - height, menuWidth, max(height, 22))
        block(menu)
        Draw.shadow(g, menu, 2)
        Draw.sprite(g, Sprites.POPOVER, menu)
        var area = menu.inset(3)
        if (searchable) {
            textField(area.top(18), filter, "Filter…", Icons.SEARCH, key = "filterField:$key", autoFocus = true)
            area = area.dropTop(18, 4)
        }
        scroll("menu:$key", area, visible.size * rowH) { content ->
            visible.forEachIndexed { i, o ->
                val row = Rect(content.x, content.y + i * rowH, content.w, rowH)
                val usable = o.disabledReason == null
                val over = hovering(row) && usable
                if (over) { Draw.fill(g, row, Palette.hover); cursor = Cursor.HAND }
                if (o.value == selected) Draw.fill(g, row.left(2), Palette.brass)
                var x = row.x + 5
                o.color?.let { Draw.fill(g, Rect(x, row.y + 5, 6, 6), it); x += 10 }
                o.icon?.let { Draw.icon(g, it, x, row.y + (if (o.description != null) 4 else 0)); x += 18 }
                Draw.text(g, Draw.fit(o.label, row.right - x - 4), x, row.y + 4, if (usable) Palette.text else Palette.textDisabled)
                o.description?.let { Draw.text(g, Draw.fit(it, row.right - x - 4), x, row.y + 13, Palette.textMuted) }
                o.disabledReason?.let { reason -> tooltip("opt:$key:$i", row) { Tip.disabled(reason) } }
                if (usable && pressed(row) != null) {
                    pick.put(o.value)
                    state[0] = false
                    UiSound.click()
                }
            }
        }
        if (input.presses.any { !it.consumed && !menu.contains(it.x, it.y) && !r.contains(it.x, it.y) }) state[0] = false
        if (input.takeKey(GLFW.GLFW_KEY_ENTER) != null && visible.size == 1) { pick.put(visible[0].value); state[0] = false }
    }
    return deferred
}

class Picked {
    var has = false
        private set
    var value: Any? = null
        private set

    fun put(v: Any?) { value = v; has = true }
    fun clear() { has = false }
}
